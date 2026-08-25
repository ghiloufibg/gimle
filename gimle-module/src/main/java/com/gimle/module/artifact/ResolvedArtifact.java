package com.gimle.module.artifact;

import com.gimle.core.module.ArtifactKind;
import com.gimle.core.vessel.VesselEntrypoint;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What an {@link ArtifactPullCache} resolution actually produced: a runnable/loadable jar file
 * ({@link ArtifactKind#JAR} -- {@code path} is a regular file) or an unpacked bundle directory
 * ({@link ArtifactKind#BUNDLE} -- {@code path} is the directory, and {@code entrypoint} carries the
 * launch descriptor read from the {@code gimle-entrypoint.yaml} inside it). Returned instead of a
 * bare {@code Path} so the caller learns what it holds from the same call that resolved it, rather
 * than sniffing the filesystem.
 */
public record ResolvedArtifact(
    Path path, ArtifactKind kind, Optional<VesselEntrypoint> entrypoint) {

  public ResolvedArtifact {
    if (kind == ArtifactKind.BUNDLE && entrypoint.isEmpty()) {
      throw new IllegalArgumentException("a resolved bundle must carry its entrypoint");
    }
    if (kind == ArtifactKind.JAR && entrypoint.isPresent()) {
      throw new IllegalArgumentException("a resolved jar has no entrypoint to carry");
    }
  }
}
