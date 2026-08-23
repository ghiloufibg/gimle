package com.gimle.worker.testsupport;

import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.JobHooks;
import com.gimle.module.lifecycle.ModuleContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;

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

  /**
   * A snapshot of this thread's own MDC context map at the moment {@link #run} starts -- proves (or
   * disproves) that {@code WorkerRuntime#runJobHooks}'s own MDC tagging actually reached the
   * virtual thread {@link #run} executes on, the same way a hosted module's own logging would see
   * it. {@code Map.of()} (never {@code null}) when the context is empty, matching {@code
   * MDC.getCopyOfContextMap()}'s own null-means-empty convention normalized away for assertions.
   */
  public static final AtomicReference<Map<String, String>> MDC_SNAPSHOT =
      new AtomicReference<>(Map.of());

  @Override
  public CompletionStatus run(ModuleContext ctx) {
    RAN.set(true);
    Map<String, String> mdc = MDC.getCopyOfContextMap();
    MDC_SNAPSHOT.set(mdc == null ? Map.of() : Map.copyOf(mdc));
    RuntimeException toThrow = THROW_INSTEAD.get();
    if (toThrow != null) {
      throw toThrow;
    }
    return STATUS_TO_RETURN.get();
  }
}
