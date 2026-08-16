package com.gimle.hilmir.plan;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.topology.Topology;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the {@code java} executable one spawned command should use: {@link
 * ResolvedRuntime#javaExecutable()} unchanged when the topology's {@code runtime.useBundledJre} is
 * {@code false} (the default), or {@code ${GIMLE_HOME}/jre/<component>/bin/java} when it's {@code
 * true} -- the identical {@code jre/<component>/} layout {@code gimle-dist}'s own {@code
 * dist-with-jre} profile produces (module-name-based component names: {@code controlplane}, {@code
 * mimir}, {@code fafnir}, {@code muninn}, {@code andvari}, {@code pki}, {@code hilmir}, {@code cli}
 * -- the same names the platform archive's own {@code lib/} jars are already known by, not the
 * topology's own role vocabulary).
 *
 * <p>{@code GIMLE_HOME} is read via {@code System.getenv} the same way {@code
 * com.gimle.hilmir.extension.GatewayJarLocator} already does for {@code hilmir enable}/{@code
 * disable} -- the platform archive's own {@code bin/hilmir} wrapper script exports it before
 * launching. Deliberately never called for {@link com.gimle.hilmir.topology.ProcessRole#AGENT} or
 * {@code WORKER}: both dynamically load code (vessel jars, hosted Gimlé modules) whose own JDK
 * module needs were never part of any jlink derivation, so callers planning those two roles must
 * keep using {@code runtime.javaExecutable()} directly, never this resolver.
 */
public final class BundledJreResolver {

  private BundledJreResolver() {}

  public static String resolveJavaExecutable(
      final Topology topology, final ResolvedRuntime runtime, final String component) {
    if (!topology.runtime().useBundledJre()) {
      return runtime.javaExecutable();
    }
    return resolveJavaExecutable(topology, runtime, component, System.getenv("GIMLE_HOME"));
  }

  /**
   * Split out from {@link #resolveJavaExecutable(Topology, ResolvedRuntime, String)} so a caller
   * can supply {@code GIMLE_HOME} directly instead of reading the real process environment -- a
   * test exercising both the present- and absent-{@code GIMLE_HOME} cases (mutating the JVM's real
   * environment is deliberately awkward), and {@code com.gimle.hilmir.launch.PkiInit}'s own {@code
   * launch}-package callers, which need the identical capability from outside this package. The
   * same shape {@code GatewayJarLocator.resolveModulesDir} already established for the identical
   * problem.
   */
  public static String resolveJavaExecutable(
      final Topology topology,
      final ResolvedRuntime runtime,
      final String component,
      final String gimleHomeEnv) {
    if (!topology.runtime().useBundledJre()) {
      return runtime.javaExecutable();
    }
    if (gimleHomeEnv == null || gimleHomeEnv.isBlank()) {
      throw new GimleManifestException(
          "runtime.useBundledJre is true but GIMLE_HOME is not set -- GIMLE_HOME is exported"
              + " automatically by the platform archive's own bin/hilmir wrapper script; if hilmir"
              + " was launched some other way, export GIMLE_HOME yourself before running, or set"
              + " runtime.useBundledJre: false to keep using runtime.javaExecutable");
    }
    final Path javaBin = Path.of(gimleHomeEnv, "jre", component, "bin", "java");
    if (!Files.isRegularFile(javaBin)) {
      throw new GimleManifestException(
          "runtime.useBundledJre is true but no bundled JRE was found for '"
              + component
              + "' at "
              + javaBin
              + " -- rebuild the distribution archive with the gimle-dist dist-with-jre profile, or"
              + " set runtime.useBundledJre: false");
    }
    return javaBin.toString();
  }
}
