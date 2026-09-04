package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.ServiceRow;
import com.gimle.hugin.model.ServiceSnapshot;
import com.gimle.hugin.model.WorkloadKind;
import com.gimle.hugin.model.WorkloadRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The dependency tree, rendered as strings. */
class XrayScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final XrayScreen screen = new XrayScreen(new Painter(ColorMode.NONE));

  @Test
  void the_chain_reads_top_down_with_each_level_indented_under_the_one_above() {
    List<String> lines = render(healthy(), healthyCluster(), "");

    int service = indexOfLine(lines, "checkout-svc");
    int deployment = indexOfLine(lines, "checkout-api ");
    int instance = indexOfLine(lines, "checkout-api/0");
    assertTrue(service < deployment && deployment < instance, lines.toString());
    assertTrue(indent(lines.get(deployment)) > indent(lines.get(service)), "deployment indented");
    assertTrue(indent(lines.get(instance)) > indent(lines.get(deployment)), "instance indented");
  }

  @Test
  void a_service_fronting_nothing_live_reads_as_bad_in_words_and_is_counted_on_the_label() {
    // Legible without colour and countable without reading every row, like every other finding.
    List<String> lines = render(broken(), brokenCluster(), "");

    assertTrue(lineContaining(lines, "typo-api").contains("NOT FOUND"));
    assertTrue(Frames.lineContaining(lines, "CHAIN").contains("fronting nothing live"));
  }

  @Test
  void a_workload_no_service_fronts_gets_a_heading_saying_exactly_that() {
    List<String> lines = render(broken(), brokenCluster(), "");

    assertTrue(lines.stream().anyMatch(line -> line.contains("fronted by no Service")));
    assertTrue(Frames.lineContaining(lines, "CHAIN").contains("fronted by no Service"));
  }

  @Test
  void the_label_says_what_the_tree_is_so_nobody_has_to_infer_the_direction_of_it() {
    assertTrue(
        Frames.lineContaining(render(healthy(), healthyCluster(), ""), "CHAIN")
            .contains("service → deployment"));
  }

  @Test
  void a_cluster_with_no_services_says_nothing_is_fronted_rather_than_drawing_an_empty_tree() {
    ServiceSnapshot none =
        new ServiceSnapshot("localhost:8080", Optional.of(NOW), List.of(), Optional.empty());
    ClusterSnapshot empty =
        new ClusterSnapshot(
            "localhost:8080", Optional.of(NOW), List.of(), List.of(), List.of(), Optional.empty());

    assertTrue(
        screen.render(none, empty, new UiState(), wide(), false, NOW).stream()
            .anyMatch(line -> line.contains("nothing is fronted at all")));
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines =
          screen.render(broken(), brokenCluster(), new UiState(), viewport, false, NOW);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(Ansi.visibleWidth(line) <= viewport.columns(), line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(broken(), brokenCluster(), "")) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(
      final ServiceSnapshot services, final ClusterSnapshot cluster, final String filter) {
    UiState ui = new UiState();
    ui.beginFilter();
    for (char character : filter.toCharArray()) {
      ui.appendToFilter(character);
    }
    ui.commitFilter();
    return screen.render(services, cluster, ui, wide(), false, NOW);
  }

  private static Viewport wide() {
    return new Viewport(140, 40);
  }

  private static ServiceSnapshot healthy() {
    return services(service("checkout-svc", 2, "checkout-api"));
  }

  private static ServiceSnapshot broken() {
    return services(service("orphan-svc", 0, "typo-api"));
  }

  private static ClusterSnapshot healthyCluster() {
    return cluster(List.of(workload("checkout-api", 1)), List.of(instance("checkout-api", 0)));
  }

  private static ClusterSnapshot brokenCluster() {
    return cluster(List.of(workload("batch-worker", 1)), List.of(instance("batch-worker", 0)));
  }

  private static ServiceSnapshot services(final ServiceRow... rows) {
    return new ServiceSnapshot("localhost:8080", Optional.of(NOW), List.of(rows), Optional.empty());
  }

  private static ServiceRow service(
      final String name, final int endpoints, final String... deployments) {
    return new ServiceRow(
        name,
        Optional.of("acme"),
        List.of(deployments),
        8080,
        OptionalInt.empty(),
        Optional.empty(),
        "TCP",
        OptionalInt.of(endpoints));
  }

  private static ClusterSnapshot cluster(
      final List<WorkloadRow> workloads, final List<InstanceRow> instances) {
    return new ClusterSnapshot(
        "localhost:8080", Optional.of(NOW), List.of(), instances, workloads, Optional.empty());
  }

  private static WorkloadRow workload(final String name, final int desired) {
    return new WorkloadRow(
        WorkloadKind.DEPLOYMENT,
        Optional.of("acme"),
        name,
        desired,
        desired,
        0,
        false,
        false,
        Optional.empty());
  }

  private static InstanceRow instance(final String name, final int index) {
    return new InstanceRow(
        new InstanceKey(Optional.of("acme"), name, index),
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
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        0L);
  }

  private static int indent(final String line) {
    return line.length() - line.stripLeading().length();
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
