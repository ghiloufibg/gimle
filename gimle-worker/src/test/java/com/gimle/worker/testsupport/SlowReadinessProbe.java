package com.gimle.worker.testsupport;

import com.gimle.module.probe.ReadinessProbe;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A readiness check that takes {@link #DELAY_MILLIS} to answer -- the "cold cache fill, slow
 * downstream dependency" module whose honest answer arrives later than a worker's default probe
 * timeout allows. Steered through shared static state for the same reason {@code
 * ControllableLivenessProbe} is: this is instantiated by reflection inside the module's own layer.
 */
public final class SlowReadinessProbe implements ReadinessProbe {

  public static final AtomicLong DELAY_MILLIS = new AtomicLong();

  @Override
  public boolean isReady() {
    try {
      Thread.sleep(DELAY_MILLIS.get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    return true;
  }
}
