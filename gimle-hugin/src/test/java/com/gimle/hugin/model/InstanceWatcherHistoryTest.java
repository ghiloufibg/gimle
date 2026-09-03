package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The drill-down's third background read: shipped meter history, and the crash dumps of an instance
 * that is not alive. Both are addressed by more than the instance key, so both wait on the row the
 * render loop publishes.
 */
class InstanceWatcherHistoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final String METRICS_PATH = "/metrics-history/WORKER/node-alpha%3Aworker-4471";
  private static final String CRASH_DUMPS_PATH =
      "/logs/instances/greeter-consumer/0/crashdumps?tenant=acme";
  private static final String COUNT_METER = "gimle.module.request.count";

  @Test
  void the_worker_history_is_read_once_the_render_loop_has_published_a_row_to_address_it_with() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                METRICS_PATH,
                Map.of(
                    "lines",
                    List.of(
                        meter(COUNT_METER, "2026-09-01T14:02:10Z", 140.0),
                        meter(COUNT_METER, "2026-09-01T14:02:20Z", 200.0))));

    try (InstanceWatcher watcher = watcherOver(reader)) {
      watcher.observe(row("ACTIVE", true));
      awaitTrue(() -> !watcher.metrics().isEmpty());

      assertEquals(
          List.of(140.0, 200.0), watcher.metrics().series(COUNT_METER).orElseThrow().values());
    }
  }

  @Test
  void nothing_is_read_at_all_until_a_row_names_the_worker_the_history_is_filed_under() {
    FakeClusterReader reader = new FakeClusterReader();

    try (InstanceWatcher watcher = watcherOver(reader)) {
      awaitTrue(() -> reader.requestedPaths().stream().anyMatch(path -> path.contains("follow")));

      assertTrue(watcher.metrics().isEmpty());
      assertFalse(
          reader.requestedPaths().stream().anyMatch(path -> path.startsWith("/metrics-history")),
          reader.requestedPaths().toString());
    }
  }

  @Test
  void an_instance_with_no_worker_yet_has_no_history_of_its_own_to_ask_for() {
    FakeClusterReader reader = new FakeClusterReader();

    try (InstanceWatcher watcher = watcherOver(reader)) {
      watcher.observe(rowWithoutWorker());
      awaitTrue(() -> reader.requestedPaths().stream().anyMatch(path -> path.contains("follow")));

      assertTrue(watcher.metrics().isEmpty());
      assertFalse(
          reader.requestedPaths().stream().anyMatch(path -> path.startsWith("/metrics-history")),
          reader.requestedPaths().toString());
    }
  }

  @Test
  void a_healthy_instance_is_never_asked_for_crash_dumps_it_could_not_have() {
    FakeClusterReader reader =
        new FakeClusterReader().withObject(METRICS_PATH, Map.of("lines", List.of()));

    try (InstanceWatcher watcher = watcherOver(reader)) {
      watcher.observe(row("ACTIVE", true));
      // The metrics read is the first half of the same pass, so its arrival proves the crash dump
      // half ran too -- and chose not to ask.
      awaitTrue(() -> reader.requestedPaths().contains(METRICS_PATH));

      assertTrue(watcher.crashDumps().isEmpty());
      assertFalse(
          reader.requestedPaths().stream().anyMatch(path -> path.contains("crashdumps")),
          reader.requestedPaths().toString());
    }
  }

  @Test
  void a_failed_instance_has_its_nodes_crash_dump_listing_read_and_parsed() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                CRASH_DUMPS_PATH,
                List.of(
                    Map.of(
                        "name",
                        "hs_err_pid4471.log",
                        "sizeBytes",
                        188_416,
                        "lastModified",
                        "2026-09-01T13:54:12Z"),
                    Map.of("sizeBytes", 12)));

    try (InstanceWatcher watcher = watcherOver(reader)) {
      watcher.observe(row("FAILED", false));
      awaitTrue(() -> !watcher.crashDumps().isEmpty());

      List<CrashDump> dumps = watcher.crashDumps();
      // The nameless second entry is dropped on its own rather than taking the listing with it.
      assertEquals(1, dumps.size(), dumps.toString());
      assertEquals("hs_err_pid4471.log", dumps.getFirst().name());
      assertEquals(188_416L, dumps.getFirst().sizeBytes());
      assertEquals(
          Optional.of(Instant.parse("2026-09-01T13:54:12Z")), dumps.getFirst().lastModified());
    }
  }

  @Test
  void a_crash_dump_route_that_fails_leaves_the_listing_empty_rather_than_reporting_anything() {
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(new CliException("no placement found for greeter-consumer#0"));

    try (InstanceWatcher watcher = watcherOver(reader)) {
      watcher.observe(row("FAILED", false));
      awaitTrue(() -> reader.requestedPaths().contains(CRASH_DUMPS_PATH));

      // The section is simply absent. A gone node is the ordinary way to reach this, and an error
      // box in its place would say less than the missing section already does.
      assertTrue(watcher.crashDumps().isEmpty());
      assertTrue(watcher.metrics().isEmpty());
    }
  }

  private static InstanceWatcher watcherOver(final FakeClusterReader reader) {
    InstanceWatcher watcher =
        new InstanceWatcher(
            reader,
            new InstanceKey(Optional.of("acme"), "greeter-consumer", 0),
            LogCategory.APPLICATION);
    watcher.start();
    return watcher;
  }

  private static InstanceRow row(final String lifecycleState, final boolean alive) {
    return row(lifecycleState, alive, Optional.of("worker-4471"));
  }

  private static InstanceRow rowWithoutWorker() {
    return row("STARTING", true, Optional.empty());
  }

  private static InstanceRow row(
      final String lifecycleState, final boolean alive, final Optional<String> workerId) {
    return new InstanceRow(
        new InstanceKey(Optional.of("acme"), "greeter-consumer", 0),
        WorkloadKind.DEPLOYMENT,
        "node-alpha",
        true,
        lifecycleState,
        alive,
        alive,
        12.0,
        0.0,
        0,
        96L * 1024L * 1024L,
        90L,
        Optional.of("greeter-consumer@1.0.0"),
        workerId,
        Optional.of(IsolationTier.TIER_2),
        Optional.of(new ResourceSpec("512Mi", "500m")),
        Map.of(),
        0L);
  }

  private static Map<String, Object> meter(
      final String name, final String timestamp, final double count) {
    return Map.of(
        "timestamp",
        timestamp,
        "name",
        name,
        "type",
        "COUNTER",
        "tags",
        Map.of("module", "greeter-consumer", "version", "1.0.0"),
        "measurements",
        Map.of("COUNT", count));
  }

  private static void awaitTrue(final BooleanSupplier condition) {
    Instant deadline = Instant.now().plus(TIMEOUT);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(Duration.ofMillis(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting", e);
      }
    }
    throw new AssertionError("condition was not met within " + TIMEOUT);
  }
}
