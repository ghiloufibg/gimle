package com.gimle.fabric.testsupport;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Spin-polls a condition instead of a fixed sleep, so async tests fail fast when possible. */
public final class Await {

  private Await() {}

  public static void until(BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout);
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for condition", e);
      }
    }
  }
}
