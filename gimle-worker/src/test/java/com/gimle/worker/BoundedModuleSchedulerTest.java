package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
}
