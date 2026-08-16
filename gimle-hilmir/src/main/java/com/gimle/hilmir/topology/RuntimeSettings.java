package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The topology-wide runtime knobs every spawned process shares: the {@code java} launcher to
 * invoke, the classpath to run every process kind against, the root directory under which every
 * process gets its own scoped data/log subdirectory, and whether to prefer each component's own
 * bundled jlink JRE over {@code javaExecutable}. The first three are optional here -- a topology
 * document that omits {@code runtime:} entirely still parses -- because a sensible default for each
 * exists at the point a plan is actually resolved (see {@code
 * com.gimle.hilmir.plan.ResolvedRuntime}), not at parse time. {@code useBundledJre} is a plain
 * boolean rather than {@code Optional<Boolean>}: unlike the other three fields it needs no
 * "resolution" step -- its default (never bundle) is already meaningful at parse time, and {@code
 * com.gimle.hilmir.plan.BundledJreResolver} reads it straight off this record rather than off
 * {@code ResolvedRuntime}, since the bundled JRE path differs per component/role while {@code
 * ResolvedRuntime.javaExecutable()} is a single shared value.
 */
public record RuntimeSettings(
    Optional<String> javaExecutable,
    Optional<String> classpath,
    Optional<Path> dataRoot,
    boolean useBundledJre) {

  public static final RuntimeSettings EMPTY =
      new RuntimeSettings(Optional.empty(), Optional.empty(), Optional.empty(), false);

  public RuntimeSettings {
    if (javaExecutable == null || classpath == null || dataRoot == null) {
      throw new GimleManifestException("runtime settings fields must not be null");
    }
    if (javaExecutable.isPresent() && javaExecutable.get().isBlank()) {
      throw new GimleManifestException("runtime.javaExecutable must be non-blank if present");
    }
    if (classpath.isPresent() && classpath.get().isBlank()) {
      throw new GimleManifestException("runtime.classpath must be non-blank if present");
    }
  }
}
