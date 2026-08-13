package com.gimle.worker.testsupport;

import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.JobHooks;
import com.gimle.module.lifecycle.ModuleContext;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instantiated by reflection inside the module's own dynamically-created ModuleLayer, so the only
 * way a test can steer its answer -- or observe that it ran at all -- is through shared static
 * state, the same trick {@link ControllableLivenessProbe} uses.
 */
public final class RecordingJobHooks implements JobHooks {

  public static final AtomicBoolean RAN = new AtomicBoolean(false);
  public static final AtomicReference<CompletionStatus> STATUS_TO_RETURN =
      new AtomicReference<>(CompletionStatus.SUCCEEDED);

  /** Thrown from {@link #run} instead of returning, when non-null -- simulates a crashing job. */
  public static final AtomicReference<RuntimeException> THROW_INSTEAD = new AtomicReference<>();

  @Override
  public CompletionStatus run(ModuleContext ctx) {
    RAN.set(true);
    RuntimeException toThrow = THROW_INSTEAD.get();
    if (toThrow != null) {
      throw toThrow;
    }
    return STATUS_TO_RETURN.get();
  }
}
