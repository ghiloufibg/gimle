package com.gimle.worker.testsupport;

import com.gimle.module.probe.LivenessProbe;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Instantiated by reflection inside the module's own dynamically-created ModuleLayer, so the only
 * way a test can steer its answer is through shared static state -- the same trick {@code
 * ServiceConsumerHooks} uses in {@code gimle-module}'s own integration tests.
 */
public final class ControllableLivenessProbe implements LivenessProbe {

  public static final AtomicBoolean ALIVE = new AtomicBoolean(true);

  @Override
  public boolean isAlive() {
    return ALIVE.get();
  }
}
