package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.NodeRow;
import com.gimle.hugin.model.WorkloadKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The node drill-down, rendered as strings. */
class NodeScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final NodeScreen screen = new NodeScreen(new Painter(ColorMode.NONE));

  @Test
  void the_detail_pane_shows_state_capacity_and_what_the_node_will_accept() {
    List<String> lines = render(node(false, List.of()), new Viewport(120, 30));

    assertTrue(lines.getFirst().contains("node-alpha"), lines.getFirst());
    assertTrue(field(lines, "state").contains("READY"));
    assertTrue(field(lines, "heartbeat").contains("2s ago"));
    assertTrue(field(lines, "cpu").contains("1240m of 4000m"));
    assertTrue(field(lines, "memory").contains("2.0 of 8.0Gi"));
    assertTrue(field(lines, "tiers").contains("TIER_1  TIER_2"));
    assertTrue(field(lines, "labels").contains("zone=eu-west"));
  }

  @Test
  void a_taint_is_named_rather_than_left_as_another_neutral_attribute() {
    List<String> lines = render(node(true, List.of("acme", "batch")), new Viewport(120, 30));

    assertTrue(field(lines, "taints").contains("acme  batch"));
    assertTrue(field(lines, "state").contains("CORDONED"));
  }

  @Test
  void an_untainted_node_says_so_rather_than_showing_an_empty_field() {
    List<String> lines = render(node(false, List.of()), new Viewport(120, 30));

    assertTrue(field(lines, "taints").contains(Text.ABSENT), field(lines, "taints"));
  }

  @Test
  void the_instances_placed_here_are_listed_and_ones_on_other_nodes_are_not() {
    List<String> lines = render(node(false, List.of()), new Viewport(120, 30));

    assertTrue(lines.stream().anyMatch(line -> line.startsWith("INSTANCES HERE")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("greeter-provider")));
    assertFalse(lines.stream().anyMatch(line -> line.contains("elsewhere-api")), lines.toString());
  }

  @Test
  void a_node_that_has_never_reported_capacity_says_so_instead_of_drawing_empty_gauges() {
    NodeRow never =
        new NodeRow(
            "node-new",
            false,
            0,
            0,
            0,
            0,
            0,
            "UNKNOWN",
            Optional.empty(),
            List.of(),
            List.of(),
            List.of());

    List<String> lines = render(never, snapshot(), new Viewport(120, 30));

    assertTrue(lines.stream().anyMatch(line -> line.contains("never reported capacity")));
    assertTrue(lines.stream().anyMatch(line -> line.contains("nothing placed here")));
    assertTrue(field(lines, "heartbeat").contains(Text.ABSENT));
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = render(node(false, List.of()), viewport);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(
            Ansi.visibleWidth(line) <= viewport.columns(),
            "line wider than " + viewport.columns() + ": " + line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(node(false, List.of("acme")), new Viewport(120, 30))) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(final NodeRow node, final Viewport viewport) {
    return render(node, snapshot(), viewport);
  }

  private List<String> render(
      final NodeRow node, final ClusterSnapshot snapshot, final Viewport viewport) {
    return screen.render(node, snapshot, new UiState(), viewport, false, NOW);
  }

  private static String field(final List<String> lines, final String label) {
    return lines.stream()
        .filter(line -> line.startsWith(label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no '" + label + "' field in " + lines));
  }

  private static NodeRow node(final boolean cordoned, final List<String> taints) {
    return new NodeRow(
        "node-alpha",
        cordoned,
        1240,
        4000,
        2L * 1024L * 1024L * 1024L,
        8L * 1024L * 1024L * 1024L,
        1,
        "HEALTHY",
        Optional.of(NOW.minusSeconds(2)),
        List.of("TIER_1", "TIER_2"),
        List.of("zone=eu-west"),
        taints);
  }

  private static ClusterSnapshot snapshot() {
    return new ClusterSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        List.of(),
        List.of(instance("greeter-provider", "node-alpha"), instance("elsewhere-api", "node-zulu")),
        List.of(),
        Optional.empty());
  }

  private static InstanceRow instance(final String deployment, final String nodeId) {
    return new InstanceRow(
        new InstanceKey(Optional.empty(), deployment, 0),
        WorkloadKind.DEPLOYMENT,
        nodeId,
        true,
        "ACTIVE",
        true,
        true,
        0.0,
        0.0,
        0,
        0L,
        0L,
        Optional.of(deployment + "@1.0.0"),
        Optional.of("worker-1"),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        0L);
  }
}
