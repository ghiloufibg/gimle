package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.SpanRow;
import com.gimle.hugin.model.TraceSnapshot;
import com.gimle.hugin.model.WorkloadKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** One instance's shipped spans, rendered as strings. */
class TraceScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final TraceScreen screen = new TraceScreen(new Painter(ColorMode.NONE));

  @Test
  void each_trace_is_a_heading_with_its_own_spans_indented_under_it() {
    // A span alone says almost nothing; what is looked for is the call and what it was part of.
    List<String> lines = render(traces(), "");

    int heading = indexOfLine(lines, "handle-request");
    int child = indexOfLine(lines, "call-provider");
    assertTrue(heading < child, lines.toString());
    assertTrue(indent(lines.get(child)) > indent(lines.get(heading)), "the span is indented");
  }

  @Test
  void a_trace_carrying_a_failed_span_reads_as_failed_in_words_and_is_counted_on_the_label() {
    List<String> lines = render(traces(), "");

    assertTrue(lineContaining(lines, "handle-request").contains("FAILED"));
    assertTrue(labelLine(lines).contains("1 with a failed span"), labelLine(lines));
    assertTrue(lineContaining(lines, "call-provider").contains("ERROR"));
  }

  @Test
  void a_worker_that_ships_nowhere_is_told_apart_from_one_that_has_served_nothing() {
    // The two look identical as an empty list, and only one of them is about this worker at all.
    List<String> notShipped = render(TraceSnapshot.notShipped("localhost:8080"), "");
    List<String> shippedNothing =
        render(
            new TraceSnapshot(
                "localhost:8080", Optional.of(NOW), List.of(), true, Optional.empty()),
            "");

    assertTrue(notShipped.stream().anyMatch(line -> line.contains("ships no traces")));
    assertTrue(shippedNothing.stream().anyMatch(line -> line.contains("nothing shipped yet")));
  }

  @Test
  void no_elapsed_time_is_shown_anywhere_because_the_shipper_records_only_an_end_instant() {
    // Anything here would be invented rather than read, which is worse than its absence.
    for (String line : render(traces(), "")) {
      assertFalse(line.contains("ms"), line);
      assertFalse(line.toLowerCase(java.util.Locale.ROOT).contains("duration"), line);
    }
  }

  @Test
  void the_filter_narrows_to_the_traces_that_match_and_keeps_their_spans_with_them() {
    List<String> lines = render(traces(), "checkout");

    assertTrue(lines.stream().anyMatch(line -> line.contains("checkout-scan")));
    assertFalse(lines.stream().anyMatch(line -> line.contains("handle-request")));
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = screen.render(instance(), traces(), new UiState(), viewport, false, NOW);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(Ansi.visibleWidth(line) <= viewport.columns(), line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(traces(), "")) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(final TraceSnapshot snapshot, final String filter) {
    UiState ui = new UiState();
    ui.beginFilter();
    for (char character : filter.toCharArray()) {
      ui.appendToFilter(character);
    }
    ui.commitFilter();
    return screen.render(instance(), snapshot, ui, new Viewport(140, 30), false, NOW);
  }

  private static TraceSnapshot traces() {
    return new TraceSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        List.of(
            span("t1", "s1", Optional.empty(), "handle-request", "SERVER", "OK", 20),
            span("t1", "s2", Optional.of("s1"), "call-provider", "CLIENT", "ERROR", 19),
            span("t2", "s3", Optional.empty(), "checkout-scan", "SERVER", "OK", 40)),
        true,
        Optional.empty());
  }

  private static SpanRow span(
      final String traceId,
      final String spanId,
      final Optional<String> parent,
      final String name,
      final String kind,
      final String status,
      final int secondsAgo) {
    return new SpanRow(NOW.minusSeconds(secondsAgo), traceId, spanId, parent, name, kind, status);
  }

  private static InstanceRow instance() {
    return new InstanceRow(
        new InstanceKey(Optional.of("acme"), "checkout-api", 0),
        WorkloadKind.DEPLOYMENT,
        "node-alpha",
        true,
        "ACTIVE",
        true,
        true,
        0.0,
        0.0,
        0,
        0L,
        0L,
        Optional.empty(),
        Optional.of("worker-7"),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        0L);
  }

  /**
   * The label line, found by wording only it carries -- the title bar above it also says TRACES, so
   * matching on that would just as often find the bar instead.
   */
  private static String labelLine(final List<String> lines) {
    return lineContaining(lines, "recent, newest first");
  }

  private static int indent(final String line) {
    String withoutClock = line.length() > 10 ? line.substring(10) : line;
    return withoutClock.length() - withoutClock.stripLeading().length();
  }

  private static int indexOfLine(final List<String> lines, final String needle) {
    for (int index = 0; index < lines.size(); index++) {
      if (lines.get(index).contains(needle)) {
        return index;
      }
    }
    throw new AssertionError("no line containing '" + needle + "' in " + lines);
  }

  private static String lineContaining(final List<String> lines, final String needle) {
    return lines.get(indexOfLine(lines, needle));
  }
}
