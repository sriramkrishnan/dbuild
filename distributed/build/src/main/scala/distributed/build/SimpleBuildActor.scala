package distributed
package build

import project.model._
import project.build._
import repo.core.{ Repository, LocalRepoHelper }
import project.dependencies.ExtractBuildDependencies
import logging.Logger
import akka.actor.{ Actor, ActorRef, Props }
import akka.pattern.{ ask, pipe }
import akka.dispatch.{ Future, Futures }
import akka.util.duration._
import akka.util.Timeout
import actorpatterns.forwardingErrorsToFutures
import sbt.Path._
import java.io.File
import distributed.repo.core.ProjectDirs
import org.apache.maven.execution.BuildFailure
import Logger.prepareLogMsg

case class RunDistributedBuild(conf: DBuildConfiguration, confName: String, target: File, logger: Logger)

// Very simple build actor that isn't smart about building and only works locally.
class SimpleBuildActor(extractor: ActorRef, builder: ActorRef, repository: Repository) extends Actor {
  def receive = {
    case RunDistributedBuild(conf, confName, target, log) => forwardingErrorsToFutures(sender) {
      val listener = sender
      implicit val ctx = context.system
      val result = try {
        val logger = log.newNestedLogger(hashing sha1 conf.build)
        val notifTask=new Notifications(conf, confName, log)
        // add further new tasks at the beginning of this list, leave notifications at the end
        val tasks: Seq[OptionTask] = Seq(new DeployBuild(conf, log), notifTask)
        tasks foreach { _.beforeBuild }
        def afterTasks(error: String, rdb: Option[RepeatableDistributedBuild], futureBuildResult: Future[BuildOutcome]): Future[BuildOutcome] = {
          if (tasks.nonEmpty) futureBuildResult map {
            // >>>> careful with map() on Futures: exceptions must be caught separately!!
            wrapExceptionIntoOutcome[BuildOutcome](log) { buildOutcome =>
              val taskOuts = tasks map { t =>
                try { // even if one task fails, we move on to the rest
                  t.afterBuild(rdb, buildOutcome)
                  (t.id, true)
                } catch {
                  case e =>
                    (t.id, false)
                }
              }
              log.info("---==  Tasks Report ==---")
              val (good, bad) = taskOuts.partition(_._2)
              if (good.nonEmpty) log.info(good.map(_._1).mkString("Successful: ", ", ", ""))
              if (bad.nonEmpty) log.info(bad.map(_._1).mkString("Failed: ", ", ", ""))
              log.info("---==  End Tasks Report ==---")
              if (bad.nonEmpty)
                TaskFailed(".", buildOutcome.outcomes, buildOutcome, "Tasks failed: " + bad.map(_._1).mkString(", "))
              else
                buildOutcome
            }
          }
          else futureBuildResult
        }
        // "conf" contains the project configs as written in the configuration file.
        // Their 'extra' field could be None, or contain information that must be completed
        // according to the build system in use for that project.
        // Only each build system knows its own defaults (which may change over time),
        // therefore we have to ask to the build system itself to expand the 'extra' field
        // as appropriate.
        //
        analyze(conf.build, target, log.newNestedLogger(hashing sha1 conf.build)) flatMap {
          wrapExceptionIntoOutcomeF[ExtractionOutcome](log) {
            case extractionOutcome: ExtractionFailed =>
              // This is a bit of a hack, in order to get better notifications: we
              // replace extractionOutcome.outcomes.outcomes so that, for each extraction
              // that was ok in extractionOutcome.outcomes, a fictitious dependency is
              // created in order to point out that we could not proceed due to some
              // other failing extraction, and we list which ones.
              val remappedExtractionOutcome=extractionOutcome.copy(outcomes=
                extractionOutcome.outcomes.map(o=>if (o.isInstanceOf[ExtractionOK]) o.withOutcomes(extractionOutcome.outcomes.diff(Seq(o))) else o))
              afterTasks("After extraction failed, tasks failed", None, Future(remappedExtractionOutcome))
            case extractionOutcome: ExtractionOK =>
              val fullBuild = RepeatableDistributedBuild.fromExtractionOutcome(conf, extractionOutcome)
              val fullLogger = log.newNestedLogger(fullBuild.uuid)
              publishFullBuild(fullBuild, fullLogger)
              val futureBuildResult = runBuild(target, fullBuild, fullLogger)
              afterTasks("After building, some tasks failed", Some(fullBuild), futureBuildResult)
            case _ => sys.error("Internal error: extraction did not return ExtractionOutcome. Please report.")
          }
        }
      } catch {
        case e =>
          Future(BuildFailed(".", Seq.empty, "Unexpected. Cause: " + prepareLogMsg(log, e)))
      }
      result pipeTo listener
    }
  }

  final def wrapExceptionIntoOutcomeF[A <: BuildOutcome](log: logging.Logger)(f: A => Future[BuildOutcome])(a: A): Future[BuildOutcome] = {
    implicit val ctx = context.system
    try f(a) catch {
      case e =>
        Future(BuildFailed(".", a.outcomes, "Unexpected. Cause: " + prepareLogMsg(log, e)))
    }
  }
  final def wrapExceptionIntoOutcome[A <: BuildOutcome](log: logging.Logger)(f: A => BuildOutcome)(a: A): BuildOutcome = {
    implicit val ctx = context.system
    try f(a) catch {
      case e =>
        BuildFailed(".", a.outcomes, "Unexpected. Cause: " + prepareLogMsg(log, e))
    }
  }

  /**
   * Publishing the full build to the repository and logs the output for
   * re-use.
   */
  def publishFullBuild(build: RepeatableDistributedBuild, log: Logger): Unit = {
    log.info("---==  RepeatableBuild ==---")
    log.info(" uuid = " + build.uuid)
    log.debug("---==   Repeatable Build Config   ===---")
    log.debug(build.repeatableBuildString)
    log.debug("---== End Repeatable Build Config ===---")
    log.info("---== Dependency Information ===---")
    build.repeatableBuilds foreach { b =>
      log.info("Project " + b.config.name)
      log.info(b.dependencies.map { _.config.name } mkString ("  depends on: ", ", ", ""))
    }
    log.info("---== End Dependency Information ===---")
    log.info("---== Writing dbuild Metadata ===---")
    LocalRepoHelper.publishBuildMeta(build, repository, log)
    log.info("---== End Writing dbuild Metadata ===---")
  }

  def logPoms(build: RepeatableDistributedBuild, arts: BuildArtifactsIn, log: Logger): Unit =
    try {
      log info "Printing Poms!"
      val poms = repo.PomHelper.makePomStrings(build, arts)
      log info (poms mkString "----------")
    } catch {
      case e: Throwable =>
        log trace e
        throw e
    }

  implicit val buildTimeout: Timeout = 4 hours

  // Chain together some Asynch to run this build.
  def runBuild(target: File, build: RepeatableDistributedBuild, log: Logger): Future[BuildOutcome] = {
    implicit val ctx = context.system
    val tdir = ProjectDirs.targetDir
    type State = Future[BuildOutcome]
    def runBuild(): Seq[State] =
      build.graph.traverse { (children: Seq[State], p: ProjectConfigAndExtracted) =>
        val b = build.buildMap(p.config.name)
        Future.sequence(children) flatMap {
          // excess of caution? In theory all Future.sequence()s
          // should be wrapped, but in practice even if we receive
          // an exception here (inside the sequence(), but before
          // the builder ? .., it means something /truly/ unusual
          // happened, and getting an exception is appropriate.
          //   wrapExceptionIntoOutcome[Seq[BuildOutcome]](log) { ...
          outcomes =>
            if (outcomes exists { _.isInstanceOf[BuildBad] }) {
              Future(BuildBrokenDependency(b.config.name, outcomes))
            } else {
              val outProjects = p.extracted.projects
              buildProject(tdir, b, outProjects, outcomes, log.newNestedLogger(b.config.name))
            }
        }
      }(Some((a, b) => a.config.name < b.config.name))

    // TODO - REpository management here!!!!
    ProjectDirs.userRepoDirFor(build) { localRepo =>
      // we go from a Seq[Future[BuildOutcome]] to a Future[Seq[BuildOutcome]]
      Future.sequence(runBuild()).map { outcomes =>
        if (outcomes exists { case _: BuildBad => true; case _ => false })
          // "." is the name of the root project
          BuildFailed(".", outcomes, "cause: one or more projects failed")
        else {
          BuildSuccess(".", outcomes, BuildArtifactsOut(Seq.empty))
        }
      }
    }
  }

  // Asynchronously extract information from builds.
  def analyze(config: DistributedBuildConfig, target: File, log: Logger): Future[ExtractionOutcome] = {
    implicit val ctx = context.system
    val uuid = hashing sha1 config
    val tdir = target / "extraction" / uuid
    val futureOutcomes: Future[Seq[ExtractionOutcome]] =
      Future.traverse(config.projects)(extract(tdir, log))
    futureOutcomes map { s: Seq[ExtractionOutcome] =>
      if (s exists { _.isInstanceOf[ExtractionFailed] })
        ExtractionFailed(".", s, "cause: one or more projects failed")
      else {
        val sok = s.collect({ case e: ExtractionOK => e })
        ExtractionOK(".", sok, sok flatMap { _.pces })
      }
    }
  }

  // Our Asynchronous API.
  def extract(target: File, logger: Logger)(config: ProjectBuildConfig): Future[ExtractionOutcome] =
    (extractor ? ExtractBuildDependencies(config, target, logger.newNestedLogger(config.name))).mapTo[ExtractionOutcome]

  // TODO - Repository Knowledge here
  // outProjects is the list of Projects that will be generated by this build, as reported during extraction.
  // we will need it to calculate the version string in LocalBuildRunner, but won't need it any further 
  def buildProject(target: File, build: RepeatableProjectBuild, outProjects: Seq[Project], children: Seq[BuildOutcome], logger: Logger): Future[BuildOutcome] =
    (builder ? RunBuild(target, build, outProjects, children, logger)).mapTo[BuildOutcome]
}