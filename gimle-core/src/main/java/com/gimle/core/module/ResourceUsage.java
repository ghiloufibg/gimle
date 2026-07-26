package com.gimle.core.module;

/** A point-in-time resource usage reading, shape-matched to {@link ResourceSpec}. */
public record ResourceUsage(long memoryBytesUsed, long cpuMillicoresUsed) {

  public ResourceUsage {
    if (memoryBytesUsed < 0) {
      throw new IllegalArgumentException(
          "memoryBytesUsed must not be negative: " + memoryBytesUsed);
    }
    if (cpuMillicoresUsed < 0) {
      throw new IllegalArgumentException(
          "cpuMillicoresUsed must not be negative: " + cpuMillicoresUsed);
    }
  }
}
