package com.gimle.worker.testsupport;

import com.gimle.module.probe.LivenessProbe;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;

/**
 * Instantiated by reflection inside the module's own dynamically-created ModuleLayer, so the only
 * way a test can steer its answer is through shared static state -- the same trick {@code
 * ServiceConsumerHooks} uses in {@code gimle-module}'s own integration tests.
 */
public final class ControllableLivenessProbe implements LivenessProbe {

  public static final AtomicBoolean ALIVE = new AtomicBoolean(true);

  /**
   * Captures whatever MDC context is visible on the thread this probe is invoked on -- {@code
   * BoundedModuleScheduler} tags that thread's MDC per-submission, so a test can observe here
   * whether {@code WorkerRuntime#onActive}'s identity-lookup-to-MDC-tag glue actually reached the
   * probe dispatch, not just that it compiles.
   */
  public static final AtomicReference<Map<String, String>> LAST_MDC = new AtomicReference<>();

  @Override
  public boolean isAlive() {
    LAST_MDC.set(MDC.getCopyOfContextMap());
    return ALIVE.get();
  }
}
