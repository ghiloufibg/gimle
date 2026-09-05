package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.time.TestClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Duplicate suppression is what turns a client-side retry from "hope the target is idempotent" into
 * a real guarantee, so these cover both halves of it: a repeat of an id already answered must not
 * re-execute, and two genuinely different calls must never be collapsed into one because they
 * happened to be in flight at the same time.
 *
 * <p>{@link TestClock} arrives by parameter injection via {@code TestClockExtension}, auto-
 * registered repo-wide, so the real 60-second window is exercised without waiting for it.
 */
class InvocationDeduplicatorTest {

  private static final Duration WINDOW = InvocationDeduplicator.DEFAULT_WINDOW;

  private ExecutorService threads;

  @AfterEach
  void tearDown() {
    if (threads != null) {
      threads.shutdownNow();
    }
  }

  private static FabricFrame response(String payload) {
    return new FabricFrame.InvokeResponse(1L, payload.getBytes(), 0);
  }

  @Test
  void a_first_request_runs_its_dispatch(TestClock clock) {
    InvocationDeduplicator deduplicator = new InvocationDeduplicator(WINDOW, 16, clock);
    AtomicInteger dispatches = new AtomicInteger();

    FabricFrame first =
        deduplicator.dispatchOnce(
            7L,
            () -> {
              dispatches.incrementAndGet();
              return response("first");
            });

    assertEquals(1, dispatches.get());
    assertEquals("first", new String(((FabricFrame.InvokeResponse) first).serializedReturn()));
  }

  @Test
  void a_duplicate_correlation_id_replays_the_original_answer_without_re_executing(
      TestClock clock) {
    InvocationDeduplicator deduplicator = new InvocationDeduplicator(WINDOW, 16, clock);
    AtomicInteger dispatches = new AtomicInteger();

    FabricFrame first =
        deduplicator.dispatchOnce(7L, () -> response("run-" + dispatches.incrementAndGet()));
    FabricFrame retry =
        deduplicator.dispatchOnce(7L, () -> response("run-" + dispatches.incrementAndGet()));

    assertEquals(1, dispatches.get(), "the retry must not have invoked the target a second time");
    assertSame(first, retry, "the retry must observe the original attempt's own answer");
  }

  @Test
  void distinct_correlation_ids_each_run_their_own_dispatch(TestClock clock) {
    InvocationDeduplicator deduplicator = new InvocationDeduplicator(WINDOW, 16, clock);
    AtomicInteger dispatches = new AtomicInteger();

    FabricFrame first =
        deduplicator.dispatchOnce(7L, () -> response("run-" + dispatches.incrementAndGet()));
    FabricFrame second =
        deduplicator.dispatchOnce(8L, () -> response("run-" + dispatches.incrementAndGet()));

    assertEquals(2, dispatches.get());
    assertNotSame(first, second);
  }

  /**
   * The window is finite on purpose, and this is the failure path that costs something: past it, a
   * retry really does execute again. That's precisely why {@code Idempotent} is a declaration by
   * the method's author rather than something the platform infers from the window existing.
   */
  @Test
  void an_id_replayed_past_the_window_runs_for_real_again(TestClock clock) {
    InvocationDeduplicator deduplicator = new InvocationDeduplicator(WINDOW, 16, clock);
    AtomicInteger dispatches = new AtomicInteger();

    deduplicator.dispatchOnce(7L, () -> response("run-" + dispatches.incrementAndGet()));
    clock.advance(WINDOW.minusNanos(1));
    deduplicator.dispatchOnce(7L, () -> response("run-" + dispatches.incrementAndGet()));
    assertEquals(1, dispatches.get(), "one nanosecond short of the window is still suppressed");

    clock.advance(Duration.ofNanos(1));
    deduplicator.dispatchOnce(7L, () -> response("run-" + dispatches.incrementAndGet()));
    assertEquals(2, dispatches.get(), "at exactly the window the id is no longer remembered");
  }

  @Test
  @Timeout(10)
  void a_duplicate_arriving_while_the_original_is_still_running_waits_for_it(TestClock clock)
      throws Exception {
    InvocationDeduplicator deduplicator = new InvocationDeduplicator(WINDOW, 16, clock);
    AtomicInteger dispatches = new AtomicInteger();
    CountDownLatch originalStarted = new CountDownLatch(1);
    CountDownLatch releaseOriginal = new CountDownLatch(1);

    threads = Executors.newVirtualThreadPerTaskExecutor();
    Future<FabricFrame> original =
        threads.submit(
            () ->
                deduplicator.dispatchOnce(
                    7L,
                    () -> {
                      dispatches.incrementAndGet();
                      originalStarted.countDown();
                      awaitQuietly(releaseOriginal);
                      return response("original");
                    }));
    assertTrue(originalStarted.await(5, TimeUnit.SECONDS));

    Future<FabricFrame> retry =
        threads.submit(
            () ->
                deduplicator.dispatchOnce(
                    7L, () -> response("run-" + dispatches.incrementAndGet())));
    releaseOriginal.countDown();

    assertSame(original.get(5, TimeUnit.SECONDS), retry.get(5, TimeUnit.SECONDS));
    assertEquals(1, dispatches.get(), "the in-flight original is the only execution");
  }

  /**
   * A dispatch that produced no response frame at all leaves nothing to replay, so the id must not
   * be remembered as answered -- otherwise the next attempt inherits a failure its caller never
   * saw, forever.
   */
  @Test
  void an_id_whose_dispatch_threw_is_not_remembered(TestClock clock) {
    InvocationDeduplicator deduplicator = new InvocationDeduplicator(WINDOW, 16, clock);

    assertThrows(
        IllegalStateException.class,
        () ->
            deduplicator.dispatchOnce(
                7L,
                () -> {
                  throw new IllegalStateException("dispatch blew up");
                }));

    FabricFrame retry = deduplicator.dispatchOnce(7L, () -> response("second-chance"));
    assertEquals(
        "second-chance", new String(((FabricFrame.InvokeResponse) retry).serializedReturn()));
  }

  @Test
  void the_table_is_capped_so_a_long_lived_listener_cannot_grow_without_bound(TestClock clock) {
    InvocationDeduplicator deduplicator = new InvocationDeduplicator(WINDOW, 4, clock);
    AtomicInteger dispatches = new AtomicInteger();

    for (long id = 0; id < 100; id++) {
      long correlationId = id;
      deduplicator.dispatchOnce(correlationId, () -> response("run-" + correlationId));
    }
    // The earliest ids are long evicted, so replaying one runs for real rather than being served
    // from a table that grew to hold all hundred.
    deduplicator.dispatchOnce(0L, () -> response("re-run-" + dispatches.incrementAndGet()));

    assertEquals(1, dispatches.get());
  }

  @Test
  void rejects_a_non_positive_entry_cap() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new InvocationDeduplicator(WINDOW, 0, new TestClock()));
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
