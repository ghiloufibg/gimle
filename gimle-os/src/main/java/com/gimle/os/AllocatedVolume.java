package com.gimle.os;

import java.nio.file.Path;

/**
 * One named volume directory a {@link VolumeManager} currently holds on disk, as reported by {@link
 * VolumeManager#listAllocated()}: its owning coordinate, the volume's name within that instance,
 * its host path, and the bytes its files currently occupy (a walk-and-sum at listing time -- an
 * observation, not an enforced limit, matching the advisory posture of {@code
 * VolumeRequest#sizeBytes} itself). "Allocated" here means "present on disk": a retained volume
 * whose instance was permanently removed still lists, which is exactly what makes an operator able
 * to find and reclaim it.
 */
public record AllocatedVolume(
    String statefulSetName, int instanceIndex, String volumeName, Path hostPath, long usedBytes) {

  public AllocatedVolume {
    if (statefulSetName == null || statefulSetName.isBlank()) {
      throw new IllegalArgumentException("statefulSetName must not be blank");
    }
    if (instanceIndex < 0) {
      throw new IllegalArgumentException("instanceIndex must not be negative: " + instanceIndex);
    }
    if (volumeName == null || volumeName.isBlank()) {
      throw new IllegalArgumentException("volumeName must not be blank");
    }
    if (hostPath == null) {
      throw new IllegalArgumentException("hostPath must not be null");
    }
    if (usedBytes < 0) {
      throw new IllegalArgumentException("usedBytes must not be negative: " + usedBytes);
    }
  }
}
