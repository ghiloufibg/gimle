package com.gimle.hilmir.topology;

import com.gimle.core.exception.GimleManifestException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The topology-wide runtime knobs every spawned process shares: the {@code java} launcher to
 * invoke, the classpath to run every process kind against, and the root directory under which every
 * process gets its own scoped data/log subdirectory. All three are optional here -- a topology
 * document that omits {@code runtime:} entirely still parses -- because a sensible default for each
 * exists at the point a plan is actually resolved (see {@code
 * com.gimle.hilmir.plan.ResolvedRuntime}), not at parse time.
 */
public record RuntimeSettings(
    Optional<String> javaExecutable, Optional<String> classpath, Optional<Path> dataRoot) {

  public static final RuntimeSettings EMPTY =
      new RuntimeSettings(Optional.empty(), Optional.empty(), Optional.empty());

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
