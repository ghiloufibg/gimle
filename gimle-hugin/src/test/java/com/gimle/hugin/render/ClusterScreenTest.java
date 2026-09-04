package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.NodeRow;
import com.gimle.hugin.model.NodeSortKey;
import com.gimle.hugin.model.SortKey;
import com.gimle.hugin.model.WorkloadKind;
import com.gimle.hugin.model.WorkloadRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The cluster view, rendered as strings. */
class ClusterScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final ClusterScreen screen = new ClusterScreen(new Painter(ColorMode.NONE));
  private final UiState ui = new UiState();

  @Test
  void the_status_line_names_the_server_the_counts_and_the_health_split() {
    List<String> lines = render(snapshot(), new Viewport(120, 30));

    String status = lines.getFirst();
    assertTrue(status.contains("GIMLÉ TOP"), status);
    assertTrue(status.contains("localhost:8080"), status);
    assertTrue(status.contains("connected"), status);
    assertTrue(status.contains("nodes 3"), status);
    assertTrue(status.contains("instances 5"), status);
    // ACTIVE and COMPLETED are ok, STARTING and the not-yet-observed one are warn, FAILED is bad
    // -- and the three numbers sum to the instance count.
    assertTrue(status.contains("2/2/1"), status);
  }

  @Test
  void every_node_gets_a_row_with_its_state_counts_and_heartbeat_age() {
    List<String> lines = render(snapshot(), new Viewport(120, 30));

    String alpha = lineContaining(lines, "node-alpha");
    assertTrue(alpha.contains("READY"), alpha);
    assertTrue(alpha.contains("1240m/4000m"), alpha);
    assertTrue(alpha.contains("2s"), alpha);

    String charlie = lineContaining(lines, "node-charlie");
    assertTrue(charlie.contains("CORDONED"), charlie);
  }

  @Test
  void every_instance_gets_a_row_with_its_state_readiness_and_metrics() {
    List<String> lines = render(snapshot(), new Viewport(120, 30));

    String consumer = lineContaining(lines, "greeter-consumer");
    assertTrue(consumer.contains("ACTIVE"), consumer);
    assertTrue(consumer.contains("✓"), consumer);
    assertTrue(consumer.contains("12.0"), consumer);
    assertTrue(consumer.contains("96Mi"), consumer);
    assertTrue(consumer.contains("90m"), consumer);
  }

  @Test
  void an_instance_with_no_observation_shows_absent_metrics_rather_than_zeroes() {
    List<String> lines = render(snapshot(), new Viewport(120, 30));

    String pending = lineContaining(lines, "edge-gateway");
    assertTrue(pending.contains("PENDING"), pending);
    assertTrue(pending.contains("—"), pending);
    assertFalse(
        pending.contains("0.0"), "an unobserved instance must not read as idle: " + pending);
  }

  @Test
  void columns_align_across_every_row_at_eighty_columns() {
    assertColumnsAlign(new Viewport(80, 40));
  }

  @Test
  void columns_align_across_every_row_at_two_hundred_columns() {
    assertColumnsAlign(new Viewport(200, 40));
  }

  @Test
  void no_line_ever_exceeds_the_terminal_width() {
    for (Viewport viewport :
        List.of(new Viewport(80, 24), new Viewport(120, 40), new Viewport(200, 60))) {
      for (String line : render(snapshot(), viewport)) {
        assertTrue(
            Ansi.visibleWidth(line) <= viewport.columns(),
            "line wider than " + viewport.columns() + ": " + line);
      }
    }
  }

  @Test
  void a_long_deployment_name_truncates_rather_than_pushing_the_row_out_of_shape() {
    ClusterSnapshot snapshot =
        new ClusterSnapshot(
            "localhost:8080",
            Optional.of(NOW),
            List.of(),
            List.of(
                instance(
                    "a-deployment-with-a-really-very-long-name", 0, "node-alpha", "ACTIVE", 1.0)),
            List.of(),
            Optional.empty());

    List<String> lines = render(snapshot, new Viewport(80, 24));

    String row = lineContaining(lines, "a-deployment");
    assertTrue(row.contains("…"), row);
    assertTrue(Ansi.visibleWidth(row) <= 80, row);
  }

  @Test
  void a_filter_narrows_the_instance_list_and_says_how_much_it_narrowed_it() {
    "greeter".chars().forEach(character -> ui.appendToFilter((char) character));

    List<String> lines = render(snapshot(), new Viewport(120, 30));

    String label = lineContaining(lines, "INSTANCES");
    assertTrue(label.contains("filter greeter"), label);
    assertTrue(label.contains("2 of 5"), label);
    assertTrue(lines.stream().anyMatch(line -> line.contains("greeter-consumer")));
    assertFalse(lines.stream().anyMatch(line -> line.contains("report-nightly")));
  }

  @Test
  void a_stale_snapshot_keeps_its_rows_and_says_how_old_they_are() {
    ClusterSnapshot stale = snapshot().stale("could not reach control plane");

    List<String> lines = render(stale, new Viewport(120, 30));

    String status = lines.getFirst();
    assertTrue(status.contains("could not reach control plane"), status);
    assertTrue(status.contains("old"), status);
    assertTrue(lines.stream().anyMatch(line -> line.contains("greeter-consumer")));
  }

  @Test
  void a_long_failure_message_is_cut_to_the_terminal_rather_than_wrapping_the_status_line() {
    ClusterSnapshot stale =
        snapshot()
            .stale(
                "could not reach control plane at http://a-very-long-control-plane-address:8080:"
                    + " Connection refused");

    for (Viewport viewport : List.of(new Viewport(60, 24), new Viewport(80, 24))) {
      String status = render(stale, viewport).getFirst();
      assertEquals(viewport.columns(), Ansi.visibleWidth(status), status);
    }
  }

  @Test
  void a_long_typed_filter_cannot_push_its_own_label_past_the_terminal_width() {
    "a-filter-far-longer-than-any-narrow-terminal-could-hold"
        .chars()
        .forEach(character -> ui.appendToFilter((char) character));

    for (String line : render(snapshot(), new Viewport(60, 24))) {
      assertTrue(Ansi.visibleWidth(line) <= 60, "line wider than 60: " + line);
    }
  }

  @Test
  void a_paused_view_says_so_on_the_status_line() {
    List<String> lines = screen.render(snapshot(), ui, new Viewport(120, 30), true, NOW);

    assertTrue(lines.getFirst().contains("PAUSED"), lines.getFirst());
  }

  @Test
  void an_empty_cluster_says_so_rather_than_drawing_two_empty_tables() {
    ClusterSnapshot empty =
        new ClusterSnapshot(
            "localhost:8080", Optional.of(NOW), List.of(), List.of(), List.of(), Optional.empty());

    List<String> lines = render(empty, new Viewport(120, 30));

    assertTrue(lines.stream().anyMatch(line -> line.contains("no nodes registered")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("no instances placed")));
  }

  @Test
  void the_key_bar_is_the_last_line_and_the_frame_never_exceeds_the_viewport() {
    Viewport viewport = new Viewport(120, 20);

    List<String> lines = render(snapshot(), viewport);

    assertEquals(viewport.rows(), lines.size());
    assertTrue(lines.getLast().contains("q quit"), lines.getLast());
  }

  @Test
  void the_selected_row_scrolls_into_view_on_a_list_longer_than_the_screen() {
    assertEquals(0, ClusterScreen.scrollOffset(0, 40, 10));
    assertEquals(0, ClusterScreen.scrollOffset(3, 40, 10));
    assertEquals(25, ClusterScreen.scrollOffset(30, 40, 10));
    assertEquals(30, ClusterScreen.scrollOffset(39, 40, 10));
    // A list that fits never scrolls at all, whatever is selected.
    assertEquals(0, ClusterScreen.scrollOffset(9, 10, 10));
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(snapshot(), new Viewport(120, 30))) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  @Test
  void with_colour_on_a_failed_instance_is_painted_in_the_bad_token() {
    ClusterScreen coloured = new ClusterScreen(new Painter(ColorMode.TRUECOLOR));
    ClusterSnapshot snapshot = snapshot();
    // Off the failed row: the selected one is drawn in one flat highlight instead, deliberately.
    ui.moveSelection(snapshot.instances(), 2);

    List<String> lines = coloured.render(snapshot, ui, new Viewport(120, 30), false, NOW);

    String failed = lineContaining(lines, "checkout-api");
    assertTrue(failed.contains(Ansi.CSI + "38;2;254;98;112m"), failed);
  }

  @Test
  void the_selected_row_is_drawn_in_one_flat_highlight_across_the_full_width() {
    ClusterScreen coloured = new ClusterScreen(new Painter(ColorMode.TRUECOLOR));
    ClusterSnapshot snapshot = snapshot();
    ui.selectFirst(snapshot.instances());

    List<String> lines = coloured.render(snapshot, ui, new Viewport(120, 30), false, NOW);

    String selected = lineContaining(lines, "checkout-api");
    assertTrue(
        selected.contains("48;2;18;48;64m"), "expected the selection background: " + selected);
    assertEquals(120, Ansi.visibleWidth(selected));
  }

  private void assertColumnsAlign(final Viewport viewport) {
    List<String> lines = render(snapshot(), viewport);
    String header = lineContaining(lines, "WORKLOAD");
    int stateColumn = header.indexOf("STATE");

    for (String deployment :
        List.of("greeter-provider", "greeter-consumer", "checkout-api", "report-nightly")) {
      String row = lineContaining(lines, deployment);
      String state = row.substring(stateColumn).trim().split("\\s+")[0];
      assertTrue(
          List.of("ACTIVE", "STARTING", "FAILED", "COMPLETED", "PENDING").contains(state),
          "expected a lifecycle state at column " + stateColumn + " of: " + row);
    }
  }

  @Test
  void sorting_by_a_metric_puts_the_worst_instance_on_the_first_row() {
    ui.cycleSort();
    ui.cycleSort();
    ui.cycleSort();

    List<String> lines = render(snapshot(), new Viewport(140, 30));

    assertEquals(SortKey.ERROR_RATE, ui.sortKey());
    assertTrue(lineContaining(lines, "INSTANCES").contains("by err/s"), lines.toString());
    String firstRow = lines.get(lines.indexOf(lineContaining(lines, "WORKLOAD")) + 1);
    assertTrue(firstRow.contains("greeter-consumer"), firstRow);
  }

  @Test
  void the_default_ordering_is_by_name_and_says_nothing_about_itself() {
    List<String> lines = render(snapshot(), new Viewport(140, 30));

    assertEquals(SortKey.NAME, ui.sortKey());
    assertFalse(lineContaining(lines, "INSTANCES").contains(" by "), lines.toString());
  }

  @Test
  void the_cluster_key_bar_still_fits_an_eighty_column_terminal() {
    // The bar grows every time a binding is added; once it exceeds the width the frame cuts its
    // tail, and the tail is where "q quit" lives.
    List<String> lines = render(snapshot(), new Viewport(80, 30));

    String bar = lines.getLast();
    assertTrue(bar.contains("q quit"), bar);
    assertTrue(Ansi.visibleWidth(bar) <= 80, bar);
    // The three keys that reach a screen an operator cannot otherwise find have to be on it.
    assertTrue(bar.contains(": kind"), bar);
    assertTrue(bar.contains("d describe"), bar);
    assertTrue(bar.contains("a activity"), bar);
  }

  @Test
  void tab_moves_the_cursor_to_the_node_table_and_only_one_table_shows_a_cursor() {
    // Selection is a background fill, so this needs a painter that emits colour to see it at all.
    ClusterScreen colour = new ClusterScreen(new Painter(ColorMode.TRUECOLOR));
    String selectionFill = "48;2;18;48;64";

    List<String> beforeTab = colour.render(snapshot(), ui, new Viewport(140, 30), false, NOW);
    assertTrue(rowsWith(beforeTab, selectionFill).stream().anyMatch(r -> r.contains("checkout")));

    ui.toggleFocus();
    List<String> afterTab = colour.render(snapshot(), ui, new Viewport(140, 30), false, NOW);

    assertEquals(UiState.Focus.NODES, ui.focus());
    List<String> highlighted = rowsWith(afterTab, selectionFill);
    assertEquals(1, highlighted.size(), highlighted.toString());
    // Two highlighted rows would leave an operator guessing which one enter acts on.
    assertTrue(highlighted.getFirst().contains("node-alpha"), highlighted.toString());
  }

  private static List<String> rowsWith(final List<String> lines, final String sequence) {
    return lines.stream().filter(line -> line.contains(sequence)).toList();
  }

  @Test
  void o_sorts_whichever_table_the_cursor_is_on() {
    ui.toggleFocus();
    ui.cycleNodeSort();

    List<String> lines = render(snapshot(), new Viewport(140, 30));

    assertEquals(NodeSortKey.CPU, ui.nodeSortKey());
    // The instance ordering is untouched, so one key means "order this" on both tables.
    assertEquals(SortKey.NAME, ui.sortKey());
    assertTrue(lineContaining(lines, "NODES").contains("by cpu"), lines.toString());
  }

  @Test
  void sorting_nodes_by_utilization_compares_each_against_its_own_capacity() {
    // node-alpha is the busiest of the three as a fraction of what it has, which is the reading
    // that predicts a refused placement -- an absolute figure would not.
    ui.toggleFocus();
    ui.cycleNodeSort();

    List<String> lines = render(snapshot(), new Viewport(140, 30));

    int header = lines.indexOf(lineContaining(lines, "STATE     CPU"));
    assertTrue(lines.get(header + 1).contains("node-alpha"), lines.get(header + 1));
  }

  @Test
  void the_default_node_ordering_is_by_id_and_says_nothing_about_itself() {
    assertEquals(NodeSortKey.ID, ui.nodeSortKey());
    assertFalse(
        lineContaining(render(snapshot(), new Viewport(140, 30)), "NODES").contains(" by "));
  }

  @Test
  void the_instance_table_labels_each_row_with_the_kind_of_workload_it_belongs_to() {
    List<String> lines = render(snapshot(), new Viewport(140, 30));

    String header = lineContaining(lines, "WORKLOAD");
    assertTrue(header.contains("KIND"), header);
    assertTrue(lineContaining(lines, "checkout-api").contains("DEPLOY"), lines.toString());
  }

  @Test
  void a_wide_terminal_names_each_rows_tenant_and_a_narrow_one_spends_the_width_on_the_name() {
    List<String> wide = render(snapshot(), new Viewport(180, 30));
    assertTrue(lineContaining(wide, "WORKLOAD").contains("TENANT"));

    // Same rule the KIND column follows: a column that reads the same on every row of a
    // single-tenant cluster is not worth truncating the one that identifies the row.
    List<String> narrow = render(snapshot(), new Viewport(120, 30));
    assertFalse(lineContaining(narrow, "WORKLOAD").contains("TENANT"));
  }

  @Test
  void a_narrow_terminal_drops_the_kind_column_rather_than_truncating_every_workload_name() {
    // The kind is the same word on most rows; the name is what identifies one. At 80 columns the
    // name keeps the width, and the kind stays reachable through the filter and the drill-down.
    List<String> lines = render(snapshot(), new Viewport(80, 30));

    assertFalse(lineContaining(lines, "WORKLOAD").contains("KIND"));
    assertTrue(lineContaining(lines, "greeter-provider").contains("greeter-provider"));
  }

  @Test
  void a_workload_the_scheduler_could_not_fully_place_gets_a_line_of_its_own() {
    // The whole point: two of four replicas exist as instance rows, and without this block the
    // other two are represented by nothing at all.
    List<String> lines = render(withUnsettled(shortOfReplicas()), new Viewport(120, 30));

    String row = lineContaining(lines, "checkout-api");
    assertTrue(row.contains("2 of 4 placed"), row);
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("NOT SETTLED")), lines.toString());
    assertTrue(lines.getFirst().contains("unplaced 2"), lines.getFirst());
  }

  @Test
  void a_quota_or_limit_range_verdict_is_named_rather_than_left_as_a_bare_shortfall() {
    List<String> lines =
        render(
            withUnsettled(
                new WorkloadRow(
                    WorkloadKind.DEPLOYMENT,
                    Optional.of("acme"),
                    "billing-api",
                    3,
                    3,
                    0,
                    true,
                    true,
                    Optional.of("memory limit 2Gi exceeds tenant maximum 1Gi"))),
            new Viewport(160, 30));

    String row = lineContaining(lines, "billing-api");
    assertTrue(row.contains("acme"), row);
    assertTrue(row.contains("quota exceeded"), row);
    assertTrue(row.contains("memory limit 2Gi exceeds tenant maximum 1Gi"), row);
    // Fully placed, so there is no shortfall clause to report alongside the two verdicts.
    assertFalse(row.contains("placed"), row);
  }

  @Test
  void a_healthy_cluster_draws_no_not_settled_block_at_all() {
    List<String> lines = render(snapshot(), new Viewport(120, 30));

    assertFalse(lines.stream().anyMatch(line -> line.startsWith("NOT SETTLED")), lines.toString());
    assertFalse(lines.getFirst().contains("unplaced"), lines.getFirst());
  }

  @Test
  void the_not_settled_block_costs_instance_rows_rather_than_overflowing_the_frame() {
    Viewport viewport = new Viewport(120, 22);

    List<String> lines = render(withUnsettled(shortOfReplicas()), viewport);

    assertEquals(22, lines.size());
    for (String line : lines) {
      assertTrue(Ansi.visibleWidth(line) <= 120, "line wider than 120: " + line);
    }
    assertTrue(lines.getLast().contains("q quit"), lines.getLast());
  }

  private static WorkloadRow shortOfReplicas() {
    return new WorkloadRow(
        WorkloadKind.DEPLOYMENT,
        Optional.empty(),
        "checkout-api",
        4,
        2,
        2,
        false,
        false,
        Optional.empty());
  }

  private static ClusterSnapshot withUnsettled(final WorkloadRow workload) {
    ClusterSnapshot base = snapshot();
    return new ClusterSnapshot(
        base.serverAddress(),
        base.fetchedAt(),
        base.nodes(),
        base.instances(),
        List.of(workload),
        base.staleReason());
  }

  private List<String> render(final ClusterSnapshot snapshot, final Viewport viewport) {
    return screen.render(snapshot, ui, viewport, false, NOW);
  }

  private static String lineContaining(final List<String> lines, final String needle) {
    return lines.stream()
        .filter(line -> line.contains(needle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line containing '" + needle + "' in " + lines));
  }

  private static ClusterSnapshot snapshot() {
    return new ClusterSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        List.of(
            node("node-alpha", 1240, 4000, 2, 8, false, 2),
            node("node-bravo", 620, 4000, 1, 8, false, 1),
            node("node-charlie", 310, 4000, 1, 8, true, 14)),
        List.of(
            instance("checkout-api", 0, "node-bravo", "FAILED", 0.0),
            instance("edge-gateway", 1, "node-charlie", "PENDING", 0.0),
            instance("greeter-consumer", 0, "node-alpha", "ACTIVE", 12.0),
            instance("greeter-provider", 1, "node-charlie", "STARTING", 0.0),
            instance("report-nightly", 0, "node-alpha", "COMPLETED", 0.0)),
        List.of(),
        Optional.empty());
  }

  private static NodeRow node(
      final String nodeId,
      final long assignedCpu,
      final long totalCpu,
      final long assignedMemoryGib,
      final long totalMemoryGib,
      final boolean cordoned,
      final long heartbeatAgeSeconds) {
    return new NodeRow(
        nodeId,
        cordoned,
        assignedCpu,
        totalCpu,
        assignedMemoryGib * 1024L * 1024L * 1024L,
        totalMemoryGib * 1024L * 1024L * 1024L,
        2,
        Optional.of(NOW.minusSeconds(heartbeatAgeSeconds)),
        List.of("TIER_1", "TIER_2"),
        List.of("zone=eu-west"),
        cordoned ? List.of("acme") : List.of());
  }

  private static InstanceRow instance(
      final String deployment,
      final int index,
      final String nodeId,
      final String state,
      final double requestRate) {
    boolean observed = !"PENDING".equals(state);
    return new InstanceRow(
        new InstanceKey(Optional.empty(), deployment, index),
        WorkloadKind.DEPLOYMENT,
        nodeId,
        observed,
        state,
        !"FAILED".equals(state),
        "ACTIVE".equals(state),
        requestRate,
        "greeter-consumer".equals(deployment) ? 0.4 : 0.0,
        0,
        observed ? 96L * 1024L * 1024L : 0L,
        observed ? 90L : 0L,
        Optional.of(deployment + "@1.0.0"),
        Optional.of("worker-4471"),
        Optional.of(IsolationTier.TIER_2),
        Optional.of(new ResourceSpec("512Mi", "500m")),
        Map.of(),
        0L);
  }
}
