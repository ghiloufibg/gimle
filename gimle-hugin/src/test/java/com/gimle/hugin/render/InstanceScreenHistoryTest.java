package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.hugin.UiState;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.InstanceWatcher;
import com.gimle.hugin.model.LogCategory;
import com.gimle.hugin.model.WorkloadKind;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The two panes of the drill-down that appear only when something is behind them: a measured row's
 * sparkline, and the crash dump listing of an instance that died.
 */
class InstanceScreenHistoryTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Viewport VIEWPORT = new Viewport(120, 40);
  private static final String GLYPHS = "▁▂▃▄▅▆▇█";

  private final InstanceScreen screen = new InstanceScreen(new Painter(ColorMode.NONE));

  @Test
  void a_worker_with_shipped_history_draws_it_beside_the_reading_it_belongs_to() {
    List<String> lines = renderSettled(shipping(), row("ACTIVE", true));

    String requests = line(lines, "req/s");
    assertTrue(requests.contains("12.0"), requests);
    assertTrue(containsGlyph(requests), requests);
    String errors = line(lines, "err/s");
    assertTrue(containsGlyph(errors), errors);
  }

  @Test
  void the_module_metaspace_a_worker_ships_earns_its_own_row_under_the_measured_block() {
    List<String> lines = renderSettled(shipping(), row("ACTIVE", true));

    String metaspace = line(lines, "metaspace");
    assertTrue(metaspace.contains("14Mi"), metaspace);
    assertTrue(containsGlyph(metaspace), metaspace);
  }

  @Test
  void a_cluster_shipping_nowhere_draws_no_sparkline_no_empty_chart_and_no_extra_row() {
    // The proxy answers 404 with no observability sink behind it, which is the majority case.
    RecordingReader silent = new RecordingReader(Map.of(), List.of());
    silent.failHistory();

    List<String> lines = renderSettled(silent, row("ACTIVE", true));

    assertFalse(containsGlyph(line(lines, "req/s")), line(lines, "req/s"));
    assertFalse(containsGlyph(line(lines, "err/s")), line(lines, "err/s"));
    assertFalse(lines.stream().anyMatch(rendered -> rendered.startsWith("metaspace")), "" + lines);
    // Byte for byte the row it has always been -- not a shortened one, not a padded one.
    assertEquals("req/s       12.0", line(lines, "req/s").stripTrailing());
  }

  @Test
  void every_sparkline_ends_in_the_same_column_whatever_its_series_had_to_say() {
    List<String> lines = renderSettled(shipping(), row("ACTIVE", true));

    // Each series is a different length -- two rates, one rate, two gauge readings -- and each is
    // drawn right-aligned in a fixed-width run, so "now" sits in the same column on every row.
    for (String label : List.of("err/s", "metaspace")) {
      assertEquals(
          lastGlyphColumn(line(lines, "req/s")),
          lastGlyphColumn(line(lines, label)),
          "sparklines must all end in the same column, " + label + ": " + line(lines, label));
    }
  }

  @Test
  void a_crashed_instance_lists_the_dumps_its_node_kept_with_their_size_and_age() {
    List<String> lines = renderSettled(crashed(), row("FAILED", false));

    assertTrue(lines.stream().anyMatch(rendered -> rendered.startsWith("CRASH DUMPS")), "" + lines);
    String dump =
        lines.stream()
            .filter(rendered -> rendered.contains("hs_err_pid4471.log"))
            .findFirst()
            .orElseThrow();
    assertTrue(dump.contains("184Ki"), dump);
    // Written nine minutes before the frame's own clock.
    assertTrue(dump.contains("9m ago"), dump);
  }

  @Test
  void a_healthy_instance_spends_no_section_and_no_request_on_crash_dumps() {
    RecordingReader reader = shipping();

    List<String> lines = renderSettled(reader, row("ACTIVE", true));

    assertFalse(
        lines.stream().anyMatch(rendered -> rendered.startsWith("CRASH DUMPS")), "" + lines);
    assertFalse(
        reader.requestedPaths().stream().anyMatch(path -> path.contains("crashdumps")),
        reader.requestedPaths().toString());
  }

  @Test
  void with_colour_switched_off_a_frame_carrying_both_panes_has_no_escape_sequences() {
    for (String rendered : renderSettled(crashed(), row("FAILED", false))) {
      assertFalse(rendered.contains(Ansi.CSI), "found an escape sequence in: " + rendered);
    }
  }

  @Test
  void no_line_of_a_frame_carrying_both_panes_exceeds_the_terminals_width() {
    for (Viewport viewport : List.of(new Viewport(60, 40), new Viewport(200, 40))) {
      InstanceWatcher watcher = watcherOver(crashed());
      try {
        InstanceRow row = row("FAILED", false);
        screen.render(row, watcher, new UiState(), viewport, false, NOW);
        awaitTrue(() -> !watcher.crashDumps().isEmpty() && !watcher.metrics().isEmpty());
        for (String rendered : screen.render(row, watcher, new UiState(), viewport, false, NOW)) {
          assertTrue(
              Ansi.visibleWidth(rendered) <= viewport.columns(),
              "line wider than " + viewport.columns() + ": " + rendered);
        }
      } finally {
        watcher.close();
      }
    }
  }

  /**
   * Renders once to publish the row, waits for the watcher's own thread to finish that first pass,
   * then renders the frame the assertions read.
   */
  private List<String> renderSettled(final RecordingReader reader, final InstanceRow row) {
    InstanceWatcher watcher = watcherOver(reader);
    try {
      screen.render(row, watcher, new UiState(), VIEWPORT, false, NOW);
      awaitTrue(() -> settled(reader, watcher));
      return screen.render(row, watcher, new UiState(), VIEWPORT, false, NOW);
    } finally {
      watcher.close();
    }
  }

  /** Whether a full history pass has landed: everything this reader serves is now published. */
  private static boolean settled(final RecordingReader reader, final InstanceWatcher watcher) {
    boolean asked =
        reader.requestedPaths().stream().anyMatch(path -> path.startsWith("/metrics-history"));
    boolean metrics = reader.historyFails || !watcher.metrics().isEmpty();
    boolean dumps = reader.dumps.isEmpty() || !watcher.crashDumps().isEmpty();
    return asked && metrics && dumps;
  }

  private static InstanceWatcher watcherOver(final ClusterReader reader) {
    InstanceWatcher watcher =
        new InstanceWatcher(
            reader,
            new InstanceKey(Optional.of("acme"), "greeter-consumer", 0),
            LogCategory.APPLICATION);
    watcher.start();
    return watcher;
  }

  private static RecordingReader shipping() {
    return new RecordingReader(historyEnvelope(), List.of());
  }

  private static Map<String, Object> historyEnvelope() {
    return Map.of(
        "lines",
        List.of(
            meter("gimle.module.request.count", "2026-09-01T14:02:10Z", "COUNT", 140.0),
            meter("gimle.module.request.count", "2026-09-01T14:02:20Z", "COUNT", 200.0),
            meter("gimle.module.request.count", "2026-09-01T14:02:30Z", "COUNT", 380.0),
            meter("gimle.module.request.errors", "2026-09-01T14:02:10Z", "COUNT", 1.0),
            meter("gimle.module.request.errors", "2026-09-01T14:02:20Z", "COUNT", 5.0),
            meter("gimle.module.metaspace.bytes", "2026-09-01T14:02:10Z", "VALUE", 13_631_488.0),
            meter("gimle.module.metaspace.bytes", "2026-09-01T14:02:20Z", "VALUE", 14_680_064.0)));
  }

  private static InstanceRow row(final String lifecycleState, final boolean alive) {
    return new InstanceRow(
        new InstanceKey(Optional.of("acme"), "greeter-consumer", 0),
        WorkloadKind.DEPLOYMENT,
        "node-alpha",
        true,
        lifecycleState,
        alive,
        alive,
        12.0,
        0.4,
        3,
        96L * 1024L * 1024L,
        90L,
        Optional.of("greeter-consumer@1.0.0"),
        Optional.of("worker-4471"),
        Optional.of(IsolationTier.TIER_2),
        Optional.of(new ResourceSpec("512Mi", "500m")),
        Map.of(),
        0L);
  }

  private static RecordingReader crashed() {
    return new RecordingReader(
        historyEnvelope(),
        List.of(
            Map.of(
                "name",
                "hs_err_pid4471.log",
                "sizeBytes",
                188_416,
                "lastModified",
                "2026-09-01T13:53:43Z")));
  }

  private static Map<String, Object> meter(
      final String name, final String timestamp, final String statistic, final double value) {
    return Map.of(
        "timestamp",
        timestamp,
        "name",
        name,
        "type",
        "VALUE".equals(statistic) ? "GAUGE" : "COUNTER",
        "tags",
        Map.of("module", "greeter-consumer", "version", "1.0.0"),
        "measurements",
        Map.of(statistic, value));
  }

  private static String line(final List<String> lines, final String label) {
    return lines.stream()
        .filter(rendered -> rendered.startsWith(label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + label + " row in: " + lines));
  }

  private static boolean containsGlyph(final String rendered) {
    return rendered.chars().anyMatch(character -> GLYPHS.indexOf(character) >= 0);
  }

  private static int lastGlyphColumn(final String rendered) {
    for (int index = rendered.length() - 1; index >= 0; index--) {
      if (GLYPHS.indexOf(rendered.charAt(index)) >= 0) {
        return index;
      }
    }
    throw new AssertionError("no sparkline in: " + rendered);
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

  /** Answers the history and crash dump routes, and records every path it was asked for. */
  private static final class RecordingReader implements ClusterReader {

    private final Map<String, Object> history;
    private final List<Map<String, Object>> dumps;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    private volatile boolean historyFails;

    private RecordingReader(
        final Map<String, Object> history, final List<Map<String, Object>> dumps) {
      this.history = history;
      this.dumps = dumps;
    }

    private void failHistory() {
      historyFails = true;
    }

    private List<String> requestedPaths() {
      return List.copyOf(paths);
    }

    @Override
    public List<Map<String, Object>> getList(final String path) {
      paths.add(path);
      return path.contains("crashdumps") ? dumps : List.of();
    }

    @Override
    public Map<String, Object> getObject(final String path) {
      paths.add(path);
      if (path.startsWith("/metrics-history")) {
        if (historyFails) {
          throw new CliException("no muninn endpoint configured");
        }
        return history;
      }
      return Map.of("lines", List.of());
    }

    @Override
    public InputStream openStream(final String path) {
      paths.add(path);
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public String serverAddress() {
      return "localhost:8080";
    }
  }
}
