package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.NodeRow;
import com.gimle.hugin.model.ServiceSnapshot;
import com.gimle.hugin.model.WorkloadKind;
import com.gimle.hugin.model.WorkloadRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The findings list, rendered as strings. */
class ScanScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final ScanScreen screen = new ScanScreen(new Painter(ColorMode.NONE));

  @Test
  void each_finding_says_what_is_wrong_without_needing_the_heading_above_it() {
    // A finding scrolled away from its group still has to be readable on its own.
    String line = lineContaining(render(broken(), ""), "checkout-api");

    assertTrue(line.contains("ERROR"), line);
    assertTrue(line.contains("workloads"), line);
    assertTrue(line.contains("replicas placed"), line);
  }

  @Test
  void the_worst_findings_are_at_the_top_where_somebody_reading_one_screen_will_see_them() {
    List<String> lines = render(broken(), "");

    assertTrue(
        indexOfLine(lines, "checkout-api") < indexOfLine(lines, "node-beta"), lines.toString());
  }

  @Test
  void the_counts_of_what_to_fix_and_what_to_watch_are_on_the_label() {
    String label = labelLine(render(broken(), ""));

    assertTrue(label.contains("to fix now"), label);
    assertTrue(label.contains("to watch"), label);
  }

  @Test
  void a_clean_cluster_says_what_it_checked_rather_than_showing_an_empty_pane() {
    List<String> lines = render(healthy(), "");

    assertTrue(
        lines.stream()
            .anyMatch(line -> line.contains("every node, workload, instance and Service")),
        lines.toString());
  }

  @Test
  void a_filter_matching_nothing_is_never_reported_as_a_clean_cluster() {
    // The two read identically as an empty table, and only one of them is good news.
    List<String> lines = render(broken(), "zzz");

    assertTrue(lines.stream().anyMatch(line -> line.contains("nothing matches")), lines.toString());
    assertFalse(
        lines.stream().anyMatch(line -> line.contains("every node, workload")), lines.toString());
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = screen.render(broken(), services(), new UiState(), viewport, false, NOW);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(Ansi.visibleWidth(line) <= viewport.columns(), line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(broken(), "")) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(final ClusterSnapshot cluster, final String filter) {
    UiState ui = new UiState();
    ui.beginFilter();
    for (char character : filter.toCharArray()) {
      ui.appendToFilter(character);
    }
    ui.commitFilter();
    return screen.render(cluster, services(), ui, new Viewport(140, 30), false, NOW);
  }

  private static ServiceSnapshot services() {
    return new ServiceSnapshot("localhost:8080", Optional.of(NOW), List.of(), Optional.empty());
  }

  /** One error, one warning and one note, so ordering and both counts are all observable. */
  private static ClusterSnapshot broken() {
    return new ClusterSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        List.of(node("node-alpha", false), node("node-beta", true)),
        List.of(instance("billing-api", "ACTIVE", true, false)),
        List.of(workload("checkout-api", 3, 1, 2)),
        Optional.empty());
  }

  private static ClusterSnapshot healthy() {
    return new ClusterSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        List.of(node("node-alpha", false)),
        List.of(instance("billing-api", "ACTIVE", true, true)),
        List.of(workload("billing-api", 1, 1, 0)),
        Optional.empty());
  }

  private static NodeRow node(final String nodeId, final boolean cordoned) {
    return new NodeRow(
        nodeId,
        cordoned,
        10L,
        4000L,
        10L,
        8000L,
        1,
        Optional.of(NOW),
        List.of(),
        List.of(),
        List.of());
  }

  private static InstanceRow instance(
      final String name, final String state, final boolean alive, final boolean ready) {
    return new InstanceRow(
        new InstanceKey(Optional.empty(), name, 0),
        WorkloadKind.DEPLOYMENT,
        "node-alpha",
        true,
        state,
        alive,
        ready,
        0.0,
        0.0,
        0,
        0L,
        0L,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        0L);
  }

  private static WorkloadRow workload(
      final String name, final int desired, final int placed, final int unplaced) {
    return new WorkloadRow(
        WorkloadKind.DEPLOYMENT,
        Optional.empty(),
        name,
        desired,
        placed,
        unplaced,
        false,
        false,
        Optional.empty());
  }

  /**
   * The label line, found by wording only it carries -- the header row above it also says SCAN's
   * columns, and the status bar says the cluster's own counts.
   */
  private static String labelLine(final List<String> lines) {
    return lineContaining(lines, "finding");
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
