package distributed.project.model

import graph.Graphs
import Utils.writeValue

/** Information on how to build a project.  Consists of both distributed build
 * configuration and extracted information.  Note: That the config in this case
 * should be the "repeatable" SCM uris and full information such that we can
 * generate repeatable builds from this information.
 */
case class ProjectConfigAndExtracted(config: ProjectBuildConfig, extracted: ExtractedBuildMeta)
    

/** This class represents *ALL* IMMUTABLE information about a project such
 * that we can generate a unique and recreatable UUID in which to store
 * the totally unique meta-data information for this project.  This
 * also includes all transitive dependencies so all artifacts can be
 * resolved appropriately.
 * 
 * We also include the (plain) project version string detected during
 * extraction. We will later either append to it the UUID of this
 * RepeatableProjectBuild, or use the explicit string provided in
 * the "setVersion" of the ProjectBuildConfig (if not None).
 */
case class RepeatableProjectBuild(config: ProjectBuildConfig,
                       baseVersion: String,
                       dependencies: Seq[RepeatableProjectBuild]) {
  /** UUID for this project. */
  def uuid = hashing sha1 this
  
  def transitiveDependencyUUIDs: Set[String] = {
    def loop(current: Seq[RepeatableProjectBuild], seen: Set[String]): Set[String] = current match {
      case Seq(head, tail @ _*) =>
        if(seen contains head.uuid) loop(tail, seen)
        else loop(tail ++ head.dependencies, seen + head.uuid)
      case _ => seen
    }
    loop(dependencies, Set.empty)
  }
}

/** A distributed build containing projects in *build order*
 *  Also known as the repeatable config. 
 */
case class RepeatableDistributedBuild(builds: Seq[ProjectConfigAndExtracted]) {
  def repeatableBuildConfig = DistributedBuildConfig(builds map (_.config))
  def repeatableBuildString = writeValue(this)
  
  /** Our own graph helper for interacting with the build meta information. */
  private[this] lazy val graph = new BuildGraph(builds)
  /** All of our repeatable build configuration in build order. */
  lazy val repeatableBuilds: Seq[RepeatableProjectBuild] = {
    def makeMeta(remaining: Seq[ProjectConfigAndExtracted], 
                 current: Map[String, RepeatableProjectBuild],
                 ordered: Seq[RepeatableProjectBuild]): Seq[RepeatableProjectBuild] =
      if(remaining.isEmpty) ordered
      else {
        // Pull out current repeatable config for a project.
        val head = remaining.head
        val node = graph.nodeFor(head) getOrElse sys.error("O NOES -- TODO better graph related puke message")
        val subgraph = Graphs.subGraphFrom(graph)(node) map (_.value)
        val dependencies = 
          for {
            dep <- (subgraph - head)
          } yield current get dep.config.name getOrElse sys.error("ISSUE! Build has circular dependencies.")
        val sortedDeps = dependencies.toSeq.sortBy (_.config.name)
        val headMeta = RepeatableProjectBuild(head.config, head.extracted.version, sortedDeps)
        makeMeta(remaining.tail, current + (headMeta.config.name -> headMeta), ordered :+ headMeta)
      }
    val orderedBuilds = (Graphs safeTopological graph map (_.value)).reverse
    makeMeta(orderedBuilds, Map.empty, Seq.empty)
  }
    
  /** The unique SHA for this build. */
  def uuid: String = hashing sha1 (repeatableBuilds map (_.uuid))
  
}
