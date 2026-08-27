package com.gimle.ragnarok.surtr;

import java.nio.file.Path;

/**
 * Resolves a workload's {@code module:} artifact id to the module name and built jar Surtr deploys
 * under many templated names -- the "pause image" trick. This is the seam a caller supplies its own
 * module provenance through: a fixture reusing a repo-local build, a bundled jar shipped with the
 * tool, or an operator-supplied {@code --module-jar}.
 */
public interface ModuleJarSource {

  /** The fully-qualified module name a workload's {@code module:} artifact id resolves to. */
  String moduleName(String artifactId);

  /** The built jar to deploy for {@code artifactId}. */
  Path jar(String artifactId);
}
