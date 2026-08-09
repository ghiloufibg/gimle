package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.InstanceMdcKeys;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class BoundedModuleSchedulerTest {

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  @Test
  void max_concurrency_below_one_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> new BoundedModuleScheduler(ID, 0));
  }

  @Test
  void submitted_task_runs_and_returns_its_result() throws Exception {
    try (BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 4)) {
      Future<String> future = scheduler.submit(() -> "done");
      assertEquals("done", future.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void a_thrown_exception_surfaces_through_the_future() {
    try (BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 4)) {
      Future<String> future =
          scheduler.submit(
              () -> {
                throw new IllegalStateException("boom");
              });
      ExecutionException e =
          assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
      assertTrue(e.getCause() instanceof IllegalStateException);
    }
  }

  @Test
  void concurrency_bound_limits_how_many_tasks_run_at_once() throws Exception {
    int maxConcurrency = 2;
    int taskCount = 8;
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxObservedConcurrent = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(taskCount);

    try (BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, maxConcurrency)) {
      for (int i = 0; i < taskCount; i++) {
        scheduler.submit(
            () -> {
              int current = concurrent.incrementAndGet();
              maxObservedConcurrent.updateAndGet(prev -> Math.max(prev, current));
              try {
                Thread.sleep(50);
              } finally {
                concurrent.decrementAndGet();
                done.countDown();
              }
              return null;
            });
      }
      assertTrue(done.await(10, TimeUnit.SECONDS), "all tasks should complete within the timeout");
    }

    assertTrue(
        maxObservedConcurrent.get() <= maxConcurrency,
        "observed concurrency "
            + maxObservedConcurrent.get()
            + " exceeded the bound of "
            + maxConcurrency);
  }

  @Test
  void closed_scheduler_rejects_further_submissions() {
    BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 2);
    scheduler.close();
    assertThrows(RejectedExecutionException.class, () -> scheduler.submit(() -> "too late"));
  }

  @Test
  void mdc_tags_are_visible_inside_a_tagged_submission() throws Exception {
    Map<String, String> tags =
        Map.of(
            InstanceMdcKeys.DEPLOYMENT_NAME, "orders",
            InstanceMdcKeys.INSTANCE_INDEX, "3");

    try (BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 2, tags)) {
      Future<String> future =
          scheduler.submit(
              () ->
                  MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME)
                      + "-"
                      + MDC.get(InstanceMdcKeys.INSTANCE_INDEX));
      assertEquals("orders-3", future.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void empty_mdc_tags_leave_the_submission_untagged() throws Exception {
    try (BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 2, Map.of())) {
      Future<String> future = scheduler.submit(() -> MDC.get(InstanceMdcKeys.DEPLOYMENT_NAME));
      assertNull(future.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void the_callers_ambient_context_is_restored_inside_the_submitted_task() throws Exception {
    // P2-10: this is the mechanism a caller's OTel span (and anything else riding Context, like
    // baggage) actually depends on to be the parent of whatever span the submitted task starts --
    // proving Context.wrap's capture-and-restore works is the direct test of that claim, without
    // needing a full OTel SDK dependency here just to assert on real Span parent/child ids.
    ContextKey<String> key = ContextKey.named("test-value");
    Context withValue = Context.current().with(key, "hello");

    String observed;
    try (Scope scope = withValue.makeCurrent();
        BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 2)) {
      Future<String> future = scheduler.submit(() -> Context.current().get(key));
      observed = future.get(5, TimeUnit.SECONDS);
    }

    assertEquals("hello", observed);
  }

  @Test
  void a_submission_made_outside_any_context_scope_sees_no_value_for_that_key() throws Exception {
    ContextKey<String> key = ContextKey.named("test-value-absent");
    try (BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 2)) {
      Future<String> future = scheduler.submit(() -> Context.current().get(key));
      assertNull(future.get(5, TimeUnit.SECONDS));
    }
  }
}
