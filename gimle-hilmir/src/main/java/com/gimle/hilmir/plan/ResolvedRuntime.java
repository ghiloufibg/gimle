package com.gimle.hilmir.plan;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.topology.RuntimeSettings;
import java.nio.file.Path;

/**
 * A topology's {@link RuntimeSettings} with every optional field filled in -- what {@link
 * LaunchPlanner#plan} actually consumes, so the planner itself never has to reach for a default (in
 * particular, the classpath default is {@code System.getProperty("java.class.path")}, which would
 * make the planner's own output depend on which JVM happened to run it). Resolution is a pure
 * function of its arguments, deliberately kept out of {@link LaunchPlanner} itself: the caller (a
 * CLI entry point or a test) supplies the actual defaults, so {@code LaunchPlanner.plan} stays
 * exactly as testable as any other pure function of its inputs.
 */
public record ResolvedRuntime(String javaExecutable, String classpath, Path dataRoot) {

  public ResolvedRuntime {
    if (javaExecutable == null || javaExecutable.isBlank()) {
      throw new GimleManifestException("resolved javaExecutable must be a non-blank string");
    }
    if (classpath == null || classpath.isBlank()) {
      throw new GimleManifestException("resolved classpath must be a non-blank string");
    }
    if (dataRoot == null) {
      throw new GimleManifestException("resolved dataRoot must not be null");
    }
  }

  /**
   * Fills every field {@code settings} left unset with the corresponding default -- each default is
   * supplied by the caller rather than read here, which is what keeps this method itself free of
   * any {@code System.getProperty} call.
   */
  public static ResolvedRuntime resolve(
      final RuntimeSettings settings,
      final String defaultJavaExecutable,
      final String defaultClasspath,
      final Path defaultDataRoot) {
    return new ResolvedRuntime(
        settings.javaExecutable().orElse(defaultJavaExecutable),
        settings.classpath().orElse(defaultClasspath),
        settings.dataRoot().orElse(defaultDataRoot));
  }
}
