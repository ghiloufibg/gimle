package com.gimle.os;

import com.gimle.core.module.VolumeRequest;

/**
 * What a {@link VolumeManager} remembers about one allocated named volume, so {@link
 * VolumeManager#hostPath}/{@link VolumeManager#release} don't need the caller to re-supply the
 * request — mirrors {@link ResourceLimitHandle}'s identical role for {@link ResourceLimiter}.
 */
public record VolumeHandle(
    String statefulSetName, int instanceIndex, String volumeName, VolumeRequest request) {

  public VolumeHandle {
    if (statefulSetName == null || statefulSetName.isBlank()) {
      throw new IllegalArgumentException("statefulSetName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (volumeName == null || volumeName.isBlank()) {
      throw new IllegalArgumentException("volumeName must not be blank");
    }
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
  }
}
