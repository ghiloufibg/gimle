package com.gimle.core.exception;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;

/**
 * A module's declared isolation tier isn't implemented by the active {@code ResourceLimiter}, or
 * applying a resource limit failed. Tier mismatches are rejected outright — never silently placed
 * on a weaker tier. The active limiter is the sole authority on what it supports; this is
 * deliberately not framed as a platform check (see the Phase 2 design doc §1) — the same rejection
 * applies uniformly on every OS rather than branching on one.
 */
public class GimleIsolationException extends RuntimeException {

  private GimleIsolationException(String message) {
    super(message);
  }

  private GimleIsolationException(String message, Throwable cause) {
    super(message, cause);
  }

  public static GimleIsolationException tierUnsupported(ModuleId id, IsolationTier tier) {
    return new GimleIsolationException(
        "module " + id + " requires isolation tier " + tier + " which is not yet implemented");
  }

  public static GimleIsolationException resourceLimitFailed(String workerId, Throwable cause) {
    return new GimleIsolationException(
        "failed to apply resource limits for worker " + workerId, cause);
  }
}
