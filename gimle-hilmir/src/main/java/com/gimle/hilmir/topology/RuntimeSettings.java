package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The topology-wide runtime knobs every spawned process shares: the {@code java} launcher to
 * invoke, the classpath to run every process kind against, the root directory under which every
 * process gets its own scoped data/log subdirectory, whether to prefer each component's own bundled
 * jlink JRE over {@code javaExecutable}, and a topology-wide default {@link SshSettings} for {@code
 * --remote} dispatch (overridable per machine via {@link Machine#ssh()}). The first three -- and
 * {@code ssh} -- are optional here -- a topology document that omits {@code runtime:} entirely
 * still parses -- because a sensible default for each exists at the point a plan is actually
 * resolved (see {@code com.gimle.hilmir.plan.ResolvedRuntime}), not at parse time. {@code
 * useBundledJre} is a plain boolean rather than {@code Optional<Boolean>}: unlike the other fields
 * it needs no "resolution" step -- its default (never bundle) is already meaningful at parse time,
 * and {@code com.gimle.hilmir.plan.BundledJreResolver} reads it straight off this record rather
 * than off {@code ResolvedRuntime}, since the bundled JRE path differs per component/role while
 * {@code ResolvedRuntime.javaExecutable()} is a single shared value.
 */
public record RuntimeSettings(
    Optional<String> javaExecutable,
    Optional<String> classpath,
    Optional<Path> dataRoot,
    boolean useBundledJre,
    Optional<SshSettings> ssh) {

  public static final RuntimeSettings EMPTY =
      new RuntimeSettings(Optional.empty(), Optional.empty(), Optional.empty(), false);

  public RuntimeSettings {
    if (javaExecutable == null || classpath == null || dataRoot == null || ssh == null) {
      throw new GimleManifestException("runtime settings fields must not be null");
    }
    if (javaExecutable.isPresent() && javaExecutable.get().isBlank()) {
      throw new GimleManifestException("runtime.javaExecutable must be non-blank if present");
    }
    if (classpath.isPresent() && classpath.get().isBlank()) {
      throw new GimleManifestException("runtime.classpath must be non-blank if present");
    }
  }

  /** Preserves every pre-existing 4-arg call site: runtime settings with no topology-wide ssh. */
  public RuntimeSettings(
      final Optional<String> javaExecutable,
      final Optional<String> classpath,
      final Optional<Path> dataRoot,
      final boolean useBundledJre) {
    this(javaExecutable, classpath, dataRoot, useBundledJre, Optional.empty());
  }
}
