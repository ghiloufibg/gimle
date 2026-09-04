package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.PulseSnapshot;
import com.gimle.hugin.model.WorkloadKind;
import com.gimle.hugin.model.WorkloadRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The one-screen health reading, rendered as strings. */
class PulseScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final PulseScreen screen = new PulseScreen(new Painter(ColorMode.NONE));

  @Test
  void a_healthy_cluster_reads_as_healthy_in_words_rather_than_as_numbers_to_interpret() {
    List<String> lines = render(up(), healthy());

    assertTrue(lineContaining(lines, "status").contains("UP"));
    assertTrue(lineContaining(lines, "instances failed").endsWith("0"));
    assertTrue(lineContaining(lines, "replicas unplaced").endsWith("0"));
  }

  @Test
  void a_readable_rollup_with_no_errors_in_it_says_none_rather_than_listing_nothing() {
    // An empty traffic block and a quiet one look the same on screen unless one of them says so.
    PulseSnapshot quiet =
        new PulseSnapshot(
            "localhost:8080",
            Optional.of(NOW),
            "UP",
            Optional.empty(),
            60L,
            "TLS",
            1,
            List.of(
                new PulseSnapshot.DeploymentTraffic(
                    Optional.of("acme"), "checkout-api", 2, 4.0, 0.0)),
            Optional.empty());

    List<String> lines = render(quiet, healthy());

    assertTrue(lineContaining(lines, "erroring").contains("none"));
    assertFalse(lines.stream().anyMatch(line -> line.contains("no per-deployment rollup")));
  }

  @Test
  void a_control_plane_that_never_answered_reads_differently_from_one_reporting_itself_down() {
    // The second is a process reporting on itself; the first is no process reporting anything.
    List<String> unreachable =
        render(PulseSnapshot.unreachable("localhost:8080", "connection refused"), healthy());

    assertTrue(lineContaining(unreachable, "status").contains("UNREACHABLE"));
    assertTrue(lineContaining(unreachable, "reason").contains("connection refused"));
    // Nothing is claimed about a control plane that did not answer.
    assertFalse(unreachable.stream().anyMatch(line -> line.contains("uptime")));
  }

  @Test
  void a_healthy_control_plane_over_a_broken_cluster_still_reports_the_cluster_as_broken() {
    // Neither reading catches the other's failure, which is why both are on this screen.
    List<String> lines = render(up(), broken());

    assertTrue(lineContaining(lines, "status").contains("UP"));
    assertTrue(lineContaining(lines, "instances failed").endsWith("1"));
    assertTrue(lineContaining(lines, "replicas unplaced").endsWith("2"));
    assertTrue(lineContaining(lines, "workloads unsettled").endsWith("1"));
  }

  @Test
  void an_unreadable_traffic_rollup_says_so_rather_than_reading_as_a_cluster_serving_nothing() {
    List<String> lines = render(up(), healthy());

    assertTrue(lines.stream().anyMatch(line -> line.contains("no per-deployment rollup readable")));
  }

  @Test
  void a_deployment_reporting_errors_is_named_before_the_merely_busy_ones() {
    PulseSnapshot pulse =
        new PulseSnapshot(
            "localhost:8080",
            Optional.of(NOW),
            "UP",
            Optional.empty(),
            60L,
            "TLS",
            1,
            List.of(
                new PulseSnapshot.DeploymentTraffic(Optional.of("acme"), "busy-api", 4, 90.0, 0.0),
                new PulseSnapshot.DeploymentTraffic(
                    Optional.of("acme"), "broken-api", 2, 5.0, 2.5)),
            Optional.empty());

    List<String> lines = render(pulse, healthy());

    int broken = indexOfLine(lines, "broken-api");
    int busy = indexOfLine(lines, "busy-api");
    assertTrue(broken < busy, "erroring before busiest: " + lines);
    assertTrue(lineContaining(lines, "broken-api").contains("err/s"));
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = screen.render(up(), broken(), new UiState(), viewport, false, NOW);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(Ansi.visibleWidth(line) <= viewport.columns(), line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(up(), broken())) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(final PulseSnapshot pulse, final ClusterSnapshot cluster) {
    return screen.render(pulse, cluster, new UiState(), new Viewport(140, 40), false, NOW);
  }

  private static PulseSnapshot up() {
    return new PulseSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        "UP",
        Optional.empty(),
        7200L,
        "TLS",
        3,
        List.of(),
        Optional.empty());
  }

  private static ClusterSnapshot healthy() {
    return cluster(
        List.of(workload("checkout-api", 2, 2, 0)),
        List.of(instance("checkout-api", 0, "ACTIVE", true)));
  }

  private static ClusterSnapshot broken() {
    return cluster(
        List.of(workload("checkout-api", 4, 2, 2)),
        List.of(
            instance("checkout-api", 0, "ACTIVE", true),
            instance("checkout-api", 1, "FAILED", false)));
  }

  private static ClusterSnapshot cluster(
      final List<WorkloadRow> workloads, final List<InstanceRow> instances) {
    return new ClusterSnapshot(
        "localhost:8080", Optional.of(NOW), List.of(), instances, workloads, Optional.empty());
  }

  private static WorkloadRow workload(
      final String name, final int desired, final int placed, final int unplaced) {
    return new WorkloadRow(
        WorkloadKind.DEPLOYMENT,
        Optional.of("acme"),
        name,
        desired,
        placed,
        unplaced,
        false,
        false,
        Optional.empty());
  }

  private static InstanceRow instance(
      final String name, final int index, final String state, final boolean ready) {
    return new InstanceRow(
        new InstanceKey(Optional.of("acme"), name, index),
        WorkloadKind.DEPLOYMENT,
        "node-alpha",
        true,
        state,
        ready,
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

  private static int indexOfLine(final List<String> lines, final String needle) {
    for (int index = 0; index < lines.size(); index++) {
      if (lines.get(index).contains(needle)) {
        return index;
      }
    }
    throw new AssertionError("no line containing '" + needle + "' in " + lines);
  }

  private static String lineContaining(final List<String> lines, final String needle) {
    return lines.get(indexOfLine(lines, needle)).stripTrailing();
  }
}
