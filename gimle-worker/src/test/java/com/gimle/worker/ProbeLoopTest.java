package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.worker.testsupport.Await;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProbeLoopTest {

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  private final ProbeLoop probeLoop = new ProbeLoop();
  private final BoundedModuleScheduler scheduler = new BoundedModuleScheduler(ID, 4);

  @AfterEach
  void tear_down() {
    probeLoop.close();
    scheduler.close();
  }

  @Test
  void a_passing_check_reports_true_repeatedly() {
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> true,
        Duration.ofMillis(20),
        Duration.ofSeconds(1),
        results::add);

    Await.at_least(() -> results.size() >= 3, Duration.ofSeconds(2));
    assertTrue(results.stream().allMatch(Boolean::booleanValue));
  }

  @Test
  void a_failing_check_reports_false() {
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> false,
        Duration.ofMillis(20),
        Duration.ofSeconds(1),
        results::add);

    Await.at_least(() -> !results.isEmpty(), Duration.ofSeconds(2));
    assertTrue(results.stream().noneMatch(Boolean::booleanValue));
  }

  @Test
  void a_check_that_throws_is_reported_as_a_failure_not_propagated() {
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> {
          throw new IllegalStateException("probe blew up");
        },
        Duration.ofMillis(20),
        Duration.ofSeconds(1),
        results::add);

    Await.at_least(() -> !results.isEmpty(), Duration.ofSeconds(2));
    assertEquals(false, results.get(0));
  }

  @Test
  void a_check_that_hangs_past_its_timeout_is_reported_as_a_failure() {
    List<Boolean> results = new CopyOnWriteArrayList<>();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> {
          Thread.sleep(TimeUnit.SECONDS.toMillis(30));
          return true;
        },
        Duration.ofMillis(20),
        Duration.ofMillis(100),
        results::add);

    Await.at_least(() -> !results.isEmpty(), Duration.ofSeconds(2));
    assertEquals(false, results.get(0));
  }

  @Test
  void stop_halts_further_invocations_of_that_key() throws InterruptedException {
    AtomicInteger invocationCount = new AtomicInteger();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> {
          invocationCount.incrementAndGet();
          return true;
        },
        Duration.ofMillis(20),
        Duration.ofSeconds(1),
        ignored -> {});

    Await.at_least(() -> invocationCount.get() >= 2, Duration.ofSeconds(2));
    probeLoop.stop("liveness");
    int countAtStop = invocationCount.get();
    Thread.sleep(150);
    assertTrue(
        invocationCount.get() <= countAtStop + 1,
        "no more than one in-flight tick should complete after stop()");
  }

  @Test
  void two_keys_are_scheduled_independently() {
    AtomicBoolean firstRan = new AtomicBoolean();
    AtomicBoolean secondRan = new AtomicBoolean();
    probeLoop.start(
        "liveness",
        scheduler,
        () -> true,
        Duration.ofMillis(20),
        Duration.ofSeconds(1),
        r -> firstRan.set(true));
    probeLoop.start(
        "readiness",
        scheduler,
        () -> true,
        Duration.ofMillis(20),
        Duration.ofSeconds(1),
        r -> secondRan.set(true));

    Await.at_least(() -> firstRan.get() && secondRan.get(), Duration.ofSeconds(2));
  }
}
