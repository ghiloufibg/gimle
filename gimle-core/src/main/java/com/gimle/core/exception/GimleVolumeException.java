package com.gimle.core.exception;

/**
 * A {@code StatefulSet}-shaped instance's declared {@code volume:} could not be allocated or
 * released on this node -- not enough free disk space at allocation time (checked once, soft,
 * matching {@code ResourceLimiter}'s own no-continuous-enforcement posture extended from CPU/memory
 * to disk), or the underlying filesystem operation itself failed. Never silently downgraded to "no
 * volume" -- the same "reject, don't silently proceed without the guarantee that was asked for"
 * posture {@link GimleIsolationException} already has for isolation tiers.
 */
public class GimleVolumeException extends RuntimeException {

  private GimleVolumeException(String message) {
    super(message);
  }

  private GimleVolumeException(String message, Throwable cause) {
    super(message, cause);
  }

  public static GimleVolumeException insufficientSpace(
      String statefulSetName, int instanceIndex, long requestedBytes, long usableBytes) {
    return new GimleVolumeException(
        "statefulset "
            + statefulSetName
            + " instance "
            + instanceIndex
            + " requested a "
            + requestedBytes
            + "-byte volume but only "
            + usableBytes
            + " bytes are free on this node's data root");
  }

  public static GimleVolumeException allocationFailed(
      String statefulSetName, int instanceIndex, Throwable cause) {
    return new GimleVolumeException(
        "failed to allocate volume for statefulset "
            + statefulSetName
            + " instance "
            + instanceIndex,
        cause);
  }

  public static GimleVolumeException releaseFailed(
      String statefulSetName, int instanceIndex, Throwable cause) {
    return new GimleVolumeException(
        "failed to release volume for statefulset "
            + statefulSetName
            + " instance "
            + instanceIndex,
        cause);
  }
}
