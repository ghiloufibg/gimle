package com.gimle.ragnarok.surtr;

import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;

/**
 * A {@link ModuleJarSource} backing an operator-supplied {@code --module-jar}: reads the jar's own
 * bundled {@code gimle-module.yaml} once (via {@link ModuleArtifactReader}, the same reader Andvari
 * pushes already resolve a module's coordinate through) to learn its module name, then always
 * returns that one jar regardless of the workload's own artifact id -- an operator running a
 * single-module workload against a jar they built themselves has no need for a name-to-jar map.
 */
public final class SingleJarModuleJarSource implements ModuleJarSource {

  private final Path jar;
  private final String moduleName;

  public SingleJarModuleJarSource(final Path jar) {
    this.jar = jar;
    this.moduleName = ModuleArtifactReader.read(jar).id().name();
  }

  @Override
  public String moduleName(final String artifactId) {
    return moduleName;
  }

  @Override
  public Path jar(final String artifactId) {
    return jar;
  }
}
