package distributed
package project
package dependencies

import model._


/** Interface for extracting project metadata. */
trait BuildDependencyExtractor {
  /** Extract project metadata from a local
   * project.
   */
  def extract(config: BuildConfig, dir: java.io.File, log: logging.Logger): ExtractedBuildMeta
  /** Returns true or false, depending on whether or not this extractor can handle
   * a given build system.
   */
  def canHandle(system: String): Boolean
}

class MultiBuildDependencyExtractor(extractors: Seq[BuildDependencyExtractor]) extends BuildDependencyExtractor {
  def canHandle(system: String): Boolean = extractors exists (_ canHandle system)
  def extract(config: BuildConfig, dir: java.io.File, log: logging.Logger): ExtractedBuildMeta =
    (extractors 
      find (_ canHandle config.system) 
      map (_.extract(config, dir, log)) 
      getOrElse sys.error("No extractor found for: " + config))
}

