package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.InstanceWatcher;
import com.gimle.hugin.model.LogCategory;
import com.gimle.hugin.model.WorkloadKind;
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
  void reported_ports_and_volume_usage_appear_when_the_instance_reports_them() {
    List<String> lines = render(row(true), watcher(), new Viewport(120, 34));

    String ports =
        lines.stream().filter(line -> line.startsWith("ports")).findFirst().orElseThrow();
    // Sorted by name, so the pane reads the same on every refresh whatever order they arrived in.
    assertTrue(ports.contains("admin 9090"), ports);
    assertTrue(ports.contains("http 8080"), ports);
    assertTrue(ports.indexOf("admin") < ports.indexOf("http"), ports);
    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("volume") && line.contains("512Mi")),
        lines.toString());
  }

  @Test
  void an_instance_reporting_neither_spends_no_line_on_either() {
    List<String> lines = render(rowReporting(Map.of(), 0L), watcher(), new Viewport(120, 34));

    assertFalse(lines.stream().anyMatch(line -> line.startsWith("ports")), lines.toString());
    assertFalse(lines.stream().anyMatch(line -> line.startsWith("volume")), lines.toString());
  }

  @Test
  void a_dedicated_worker_draws_measured_memory_against_its_own_declared_ceiling() {
    List<String> lines = render(row(true), watcher(), new Viewport(120, 30));

    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("tier") && line.contains("TIER_2")),
        lines.toString());
    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("limit") && line.contains("512Mi memory")),
        lines.toString());
    String memory =
        lines.stream().filter(line -> line.startsWith("memory")).findFirst().orElseThrow();
    assertTrue(memory.contains("96Mi of 512Mi"), memory);
    assertTrue(memory.contains("▇") && memory.contains("19%"), memory);
  }

  @Test
  void a_shared_worker_shows_the_same_limit_as_an_admission_bound_with_no_headroom_bar() {
    List<String> lines =
        render(
            row(
                true,
                Optional.of(IsolationTier.TIER_1),
                Optional.of(new ResourceSpec("512Mi", "500m"))),
            watcher(),
            new Viewport(120, 30));

    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("limit") && line.contains("512Mi memory")),
        lines.toString());
    assertTrue(
        lines.stream().anyMatch(line -> line.contains("not a per-instance ceiling")),
        lines.toString());
    // One heap serves every instance on a shared worker, so a bar drawn against this figure would
    // read as headroom this instance does not individually have.
    String memory =
        lines.stream().filter(line -> line.startsWith("memory")).findFirst().orElseThrow();
    assertTrue(memory.contains("96Mi"), memory);
    assertFalse(memory.contains("▇") || memory.contains("of 512Mi"), memory);
  }

  @Test
  void a_control_plane_serving_neither_field_reads_as_absent_rather_than_as_a_default_tier() {
    List<String> lines =
        render(row(true, Optional.empty(), Optional.empty()), watcher(), new Viewport(120, 30));

    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("tier") && line.contains(Text.ABSENT)),
        lines.toString());
    assertTrue(
        lines.stream().anyMatch(line -> line.startsWith("limit") && line.contains(Text.ABSENT)),
        lines.toString());
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
  void a_terminal_too_short_for_the_panes_still_ends_in_the_key_bar() {
    // The panes above it are cut instead: a frame that ended wherever a pane happened to reach the
    // bottom row would leave an operator no visible way back out of the drill-down.
    List<String> lines = render(row(true), watcher(), new Viewport(120, 12));

    assertEquals(12, lines.size());
    assertTrue(lines.getLast().contains("esc back"), lines.getLast());
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
    return row(
        observed,
        Optional.of(IsolationTier.TIER_2),
        Optional.of(new ResourceSpec("512Mi", "500m")));
  }

  private static InstanceRow rowReporting(final Map<String, Integer> ports, final long volume) {
    InstanceRow base = row(true);
    return new InstanceRow(
        base.key(),
        base.kind(),
        base.nodeId(),
        base.observed(),
        base.lifecycleState(),
        base.alive(),
        base.ready(),
        base.requestRatePerSecond(),
        base.errorRatePerSecond(),
        base.queueDepth(),
        base.memoryBytesUsed(),
        base.cpuMillicoresUsed(),
        base.moduleCoordinate(),
        base.workerId(),
        base.isolationTier(),
        base.resourceLimit(),
        ports,
        volume);
  }

  private static InstanceRow row(
      final boolean observed,
      final Optional<IsolationTier> isolationTier,
      final Optional<ResourceSpec> resourceLimit) {
    return new InstanceRow(
        new InstanceKey(Optional.of("acme"), "greeter-consumer", 0),
        WorkloadKind.DEPLOYMENT,
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
        Optional.of("worker-4471"),
        isolationTier,
        resourceLimit,
        Map.of("http", 8080, "admin", 9090),
        512L * 1024L * 1024L);
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
