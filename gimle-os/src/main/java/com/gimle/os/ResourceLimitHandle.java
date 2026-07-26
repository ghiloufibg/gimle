package com.gimle.os;

import com.gimle.core.module.ResourceSpec;

/**
 * What a {@link ResourceLimiter} remembers about one worker, so later calls don't need the caller
 * to re-supply it.
 */
public record ResourceLimitHandle(String workerId, ResourceSpec limit) {

  public ResourceLimitHandle {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("workerId must not be blank");
    }
    if (limit == null) {
      throw new IllegalArgumentException("limit must not be null");
    }
  }
}
