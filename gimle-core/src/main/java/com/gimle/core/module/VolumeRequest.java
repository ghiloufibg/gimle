package com.gimle.core.module;

/**
 * A module's declared need for local-disk-backed persistent storage, sibling to {@link
 * ResourceSpec}: a {@code StatefulSet}-shaped module declares {@code volume:} in its own {@code
 * gimle-module.yaml}, not in the workload manifest -- the same place {@code isolation:}/{@code
 * resources:} already live, since this is a property of the artifact itself, not of how many
 * replicas an operator asks for. {@code sizeBytes} is advisory, checked once at {@link
 * com.gimle.os.VolumeManager#allocate} time against free disk space, never continuously enforced
 * afterward -- the same "portable-first, soft limits" posture {@link ResourceSpec}'s own CPU/memory
 * request already has, extended to disk rather than inventing a new enforcement precedent.
 */
public record VolumeRequest(long sizeBytes, String mountPath) {

  public VolumeRequest {
    if (sizeBytes <= 0) {
      throw new IllegalArgumentException("sizeBytes must be positive: " + sizeBytes);
    }
    if (mountPath == null || mountPath.isBlank()) {
      throw new IllegalArgumentException("mountPath must not be blank");
    }
  }
}
