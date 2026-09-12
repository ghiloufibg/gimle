package com.gimle.os;

import com.gimle.core.io.PathSegmentNames;
import com.gimle.core.module.VolumeRequest;
import java.util.Optional;

/**
 * What a {@link VolumeManager} remembers about one allocated named volume, so {@link
 * VolumeManager#hostPath}/{@link VolumeManager#release} don't need the caller to re-supply the
 * request — mirrors {@link ResourceLimitHandle}'s identical role for {@link ResourceLimiter}.
 *
 * <p>{@code tenantId} is part of this volume's on-disk identity, not just metadata: two tenants are
 * free to run a {@code StatefulSet} named identically (the platform's storage layer keys everything
 * else by {@code (tenantId, name)} the same way — see {@code StateStore}'s own {@code scopedKey}),
 * so a volume path keyed on {@code statefulSetName} alone would let one tenant's instance silently
 * allocate into, and read stale data out of, another tenant's directory whenever both land on the
 * same node. Absent means the untenanted namespace, distinct from every real tenant id.
 */
public record VolumeHandle(
    Optional<String> tenantId,
    String statefulSetName,
    int instanceIndex,
    String volumeName,
    VolumeRequest request) {

  public VolumeHandle {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    // Both become raw filesystem path segments in LocalDiskVolumeManager, joined straight onto
    // the instance's own data directory -- validated here too, not only there, so no path built
    // from this handle's own fields can ever carry a traversal or absolute-path segment,
    // regardless of which VolumeManager implementation or reconstruction path built it.
    PathSegmentNames.requireValidSegment(statefulSetName, "statefulSetName");
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    PathSegmentNames.requireValidSegment(volumeName, "volumeName");
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
  }
}
