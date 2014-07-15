package com.typesafe.dbuild

import sbt._
import distributed.project.model

object DistributedBuildKeys {
  // TODO - make a task that generates this metadata and just call it!
  type ArtifactMap = Seq[model.ArtifactLocation]
  val extractArtifacts = TaskKey[ArtifactMap]("distributed-build-extract-artifacts")
  // Used during the index generation
  val moduleInfo = TaskKey[com.typesafe.reactiveplatform.manifest.ModuleInfo]("module-info")
}