package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.InstanceWatcher;
import com.gimle.hugin.model.LogCategory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** The drill-down, rendered as strings. */
class InstanceScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final InstanceScreen screen = new InstanceScreen(new Painter(ColorMode.NONE));

  @Test
  void the_header_names_the_instance_its_tenant_and_its_node() {
    List<String> lines = render(row(true), watcher(), new Viewport(120, 30));

    String header = lines.getFirst();
    assertTrue(header.contains("greeter-consumer"), header);
    assertTrue(header.contains("tenant acme"), header);
    assertTrue(header.contains("node node-alpha"), header);
  }

  @Test
  void the_detail_pane_shows_state_readiness_and_the_measured_values() {
    List<String> lines = render(row(true), watcher(), new Viewport(120, 30));

    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("state") && line.contains("ACTIVE")));
    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("ready") && line.contains("✓ true")));
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("memory") && line.contains("96Mi")));
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("err/s") && line.contains("0.4")));
    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("worker") && line.contains("worker-4471")));
  }

  @Test
  void an_instance_with_no_observation_says_so_instead_of_showing_measured_zeroes() {
    List<String> lines = render(row(false), watcher(), new Viewport(120, 30));

    assertTrue(
        lines.stream().anyMatch(line -> line.contains("has not reported on it yet")),
        lines.toString());
    assertFalse(lines.stream().anyMatch(line -> line.startsWith("memory")));
  }

  @Test
  void the_event_timeline_and_the_log_tail_both_appear_with_their_own_labels() {
    List<String> lines = render(row(true), watcher(), new Viewport(120, 30));

    assertTrue(lines.stream().anyMatch(line -> line.startsWith("RECENT EVENTS")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("STARTING -> ACTIVE")));
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("LOGS")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("no healthy endpoint")));
  }

  @Test
  void the_log_pane_names_the_category_it_is_following_and_how_to_change_it() {
    String label =
        render(row(true), watcher(), new Viewport(120, 30)).stream()
            .filter(line -> line.startsWith("LOGS"))
            .findFirst()
            .orElseThrow();

    assertTrue(label.contains("application · following"), label);
    assertTrue(label.contains("c: cycle category"), label);
  }

  @Test
  void an_empty_timeline_and_an_empty_tail_both_say_what_they_are_waiting_for() {
    InstanceWatcher quiet = watcherFor(new SilentReader());

    List<String> lines = render(row(true), quiet, new Viewport(120, 30));

    assertTrue(lines.stream().anyMatch(line -> line.contains("no lifecycle events recorded")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("waiting for output")));
    quiet.close();
  }

  @Test
  void no_line_exceeds_the_terminal_width_and_the_key_bar_is_last() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = render(row(true), watcher(), viewport);
      for (String line : lines) {
        assertTrue(
            Ansi.visibleWidth(line) <= viewport.columns(),
            "line wider than " + viewport.columns() + ": " + line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void a_narrow_terminal_cuts_every_pane_rather_than_wrapping_any_of_them() {
    Viewport narrow = new Viewport(60, 24);

    for (String line : render(row(true), watcher(), narrow)) {
      assertTrue(Ansi.visibleWidth(line) <= 60, "line wider than 60: " + line);
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(row(true), watcher(), new Viewport(120, 30))) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(
      final InstanceRow row, final InstanceWatcher watcher, final Viewport viewport) {
    return screen.render(row, watcher, viewport, false, NOW);
  }

  private static InstanceRow row(final boolean observed) {
    return new InstanceRow(
        new InstanceKey(Optional.of("acme"), "greeter-consumer", 0),
        "node-alpha",
        observed,
        observed ? "ACTIVE" : "PENDING",
        observed,
        observed,
        12.0,
        0.4,
        3,
        96L * 1024L * 1024L,
        90L,
        Optional.of("greeter-consumer@1.0.0"),
        Optional.of("worker-4471"));
  }

  /** A watcher over canned responses, already settled by the time the assertions run. */
  private static InstanceWatcher watcher() {
    InstanceWatcher watcher = watcherFor(new CannedReader());
    awaitTrue(() -> !watcher.lines().isEmpty() && !watcher.events().isEmpty());
    return watcher;
  }

  private static InstanceWatcher watcherFor(final ClusterReader reader) {
    InstanceWatcher watcher =
        new InstanceWatcher(
            reader,
            new InstanceKey(Optional.of("acme"), "greeter-consumer", 0),
            LogCategory.APPLICATION);
    watcher.start();
    return watcher;
  }

  private static void awaitTrue(final BooleanSupplier condition) {
    Instant deadline = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(java.time.Duration.ofMillis(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting", e);
      }
    }
    throw new AssertionError("watcher never produced the canned responses");
  }

  /** One event and one log line, whatever is asked for. */
  private static final class CannedReader implements ClusterReader {

    @Override
    public List<Map<String, Object>> getList(final String path) {
      return List.of(
          Map.of(
              "kind",
              "ACTIVE",
              "message",
              "STARTING -> ACTIVE",
              "occurredAtEpochMilli",
              NOW.toEpochMilli()));
    }

    @Override
    public Map<String, Object> getObject(final String path) {
      return Map.of(
          "lines",
          List.of(
              Map.of(
                  "timestamp",
                  "2026-09-01T14:02:41.702Z",
                  "level",
                  "ERROR",
                  "logger",
                  "com.example.Greeter",
                  "message",
                  "Fabric call failed: no healthy endpoint")));
    }

    @Override
    public InputStream openStream(final String path) {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public String serverAddress() {
      return "localhost:8080";
    }
  }

  /** An instance with nothing to say: no events, no lines, no failure either. */
  private static final class SilentReader implements ClusterReader {

    @Override
    public List<Map<String, Object>> getList(final String path) {
      return List.of();
    }

    @Override
    public Map<String, Object> getObject(final String path) {
      return Map.of("lines", List.of());
    }

    @Override
    public InputStream openStream(final String path) {
      return new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String serverAddress() {
      return "localhost:8080";
    }
  }
}
