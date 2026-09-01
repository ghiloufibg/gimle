package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** The drill-down's two background reads: the lifecycle timeline and the live log tail. */
class InstanceWatcherTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final String BACKLOG_PATH =
      "/logs/instances/greeter-consumer/0?category=APPLICATION&limit=200";
  private static final String FOLLOW_PATH =
      "/logs/instances/greeter-consumer/0?category=APPLICATION&follow=true"
          + "&cursor=2026-09-01T14%3A02%3A41.204Z";

  @Test
  void the_tail_shows_backlog_first_then_the_lines_the_follow_stream_delivers() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                BACKLOG_PATH,
                Map.of(
                    "lines",
                    List.of(logLine("2026-09-01T14:02:41.204Z", "INFO", "Greeting request 1841"))))
            .withStream(
                FOLLOW_PATH,
                "{\"timestamp\":\"2026-09-01T14:02:41.702Z\",\"level\":\"ERROR\","
                    + "\"logger\":\"x\",\"message\":\"no healthy endpoint\"}\n");

    try (InstanceWatcher watcher =
        new InstanceWatcher(reader, consumerInstance(), LogCategory.APPLICATION)) {
      watcher.start();
      awaitTrue(() -> watcher.lines().size() >= 2);

      List<LogLine> lines = watcher.lines();
      assertEquals("Greeting request 1841", lines.get(0).message().orElseThrow());
      assertEquals(Optional.of("ERROR"), lines.get(1).level());
      assertEquals("no healthy endpoint", lines.get(1).message().orElseThrow());
    }
  }

  @Test
  void the_follow_stream_resumes_from_the_newest_backlog_line_rather_than_from_now() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                BACKLOG_PATH,
                Map.of(
                    "lines",
                    List.of(logLine("2026-09-01T14:02:41.204Z", "INFO", "Greeting request 1841"))));

    try (InstanceWatcher watcher =
        new InstanceWatcher(reader, consumerInstance(), LogCategory.APPLICATION)) {
      watcher.start();
      awaitTrue(() -> reader.requestedPaths().contains(FOLLOW_PATH));
    }
  }

  @Test
  void a_tenanted_instance_carries_its_tenant_on_every_route_it_reads() {
    InstanceKey key = new InstanceKey(Optional.of("acme"), "greeter-provider", 1);
    FakeClusterReader reader = new FakeClusterReader();

    try (InstanceWatcher watcher = new InstanceWatcher(reader, key, LogCategory.PLATFORM)) {
      watcher.start();
      awaitTrue(() -> reader.requestedPaths().size() >= 3);

      List<String> paths = reader.requestedPaths();
      assertTrue(
          paths.stream().allMatch(path -> path.contains("tenant=acme")),
          "every route must be tenant-scoped, got: " + paths);
      assertTrue(
          paths.stream().anyMatch(path -> path.contains("category=PLATFORM")),
          "the chosen log category must reach the route, got: " + paths);
    }
  }

  @Test
  void the_events_route_is_addressed_by_the_tenant_scoped_triple() {
    InstanceKey key = new InstanceKey(Optional.of("acme"), "greeter-provider", 1);
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/events?deployment=greeter-provider&instance=1&tenant=acme",
                List.of(
                    Map.of(
                        "kind",
                        "ACTIVE",
                        "message",
                        "STARTING -> ACTIVE",
                        "occurredAtEpochMilli",
                        Instant.now().toEpochMilli())));

    try (InstanceWatcher watcher = new InstanceWatcher(reader, key, LogCategory.APPLICATION)) {
      watcher.start();
      awaitTrue(() -> !watcher.events().isEmpty());

      assertEquals("ACTIVE", watcher.events().getFirst().kind());
      assertEquals("STARTING -> ACTIVE", watcher.events().getFirst().message());
    }
  }

  @Test
  void a_log_route_that_fails_leaves_a_reason_on_the_pane_rather_than_ending_the_session() {
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(new CliException("no placement found for greeter-consumer#0"));

    try (InstanceWatcher watcher =
        new InstanceWatcher(reader, consumerInstance(), LogCategory.APPLICATION)) {
      watcher.start();
      awaitTrue(() -> watcher.logError().isPresent());

      assertTrue(watcher.logError().orElseThrow().contains("no placement found"));
    }
  }

  @Test
  void a_follow_stream_that_ends_on_its_own_is_not_reported_as_an_error() {
    // Nothing is registered for either route, so the backlog is empty and the follow stream closes
    // immediately -- what an instance that has finished running actually looks like.
    FakeClusterReader reader = new FakeClusterReader();

    try (InstanceWatcher watcher =
        new InstanceWatcher(reader, consumerInstance(), LogCategory.APPLICATION)) {
      watcher.start();
      awaitTrue(() -> reader.requestedPaths().stream().anyMatch(path -> path.contains("follow")));

      assertEquals(Optional.empty(), watcher.logError());
      assertTrue(watcher.lines().isEmpty());
    }
  }

  private static InstanceKey consumerInstance() {
    return new InstanceKey(Optional.empty(), "greeter-consumer", 0);
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

  private static Map<String, Object> logLine(
      final String timestamp, final String level, final String message) {
    return Map.of("timestamp", timestamp, "level", level, "logger", "x", "message", message);
  }
}
