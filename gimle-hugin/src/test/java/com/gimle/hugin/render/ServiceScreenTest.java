package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.model.ServiceRow;
import com.gimle.hugin.model.ServiceSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The services view, rendered as strings. */
class ServiceScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final ServiceScreen screen = new ServiceScreen(new Painter(ColorMode.NONE));

  @Test
  void every_service_gets_a_row_with_its_ports_protocol_backing_and_endpoint_count() {
    List<String> lines = render(snapshot(), new Viewport(120, 30));

    String greeter = lineContaining(lines, "greeter");
    assertTrue(greeter.contains("8080→9090"), greeter);
    assertTrue(greeter.contains("TCP"), greeter);
    assertTrue(greeter.contains("READY"), greeter);
    assertTrue(greeter.contains("greeter-provider"), greeter);
    assertTrue(greeter.contains("acme"), greeter);
  }

  @Test
  void a_service_resolving_to_no_endpoints_reads_as_bad_in_words_and_not_only_in_colour() {
    String row = lineContaining(render(snapshot(), new Viewport(120, 30)), "orphan");

    assertTrue(row.contains("NO ENDPOINTS"), row);
    assertTrue(row.contains("0"), row);
  }

  @Test
  void with_colour_on_a_service_resolving_to_nothing_is_painted_in_the_bad_token() {
    ServiceScreen coloured = new ServiceScreen(new Painter(ColorMode.TRUECOLOR));

    List<String> lines = coloured.render(snapshot(), new Viewport(120, 30), false, NOW);

    String row = lineContaining(lines, "orphan");
    assertTrue(row.contains(Ansi.CSI + "38;2;254;98;112m"), row);
  }

  @Test
  void a_service_whose_endpoints_could_not_be_read_says_unknown_rather_than_none() {
    String row = lineContaining(render(snapshot(), new Viewport(120, 30)), "unreadable");

    assertTrue(row.contains("UNKNOWN"), row);
    assertTrue(row.contains("—"), row);
    assertFalse(row.contains("NO ENDPOINTS"), row);
  }

  @Test
  void an_external_name_service_shows_the_host_it_aliases_instead_of_deployments() {
    String row = lineContaining(render(snapshot(), new Viewport(160, 30)), "legacy-billing");

    assertTrue(row.contains("→ billing.example.com"), row);
  }

  @Test
  void an_undeclared_target_port_reads_as_auto_rather_than_repeating_the_dialled_one() {
    String row = lineContaining(render(snapshot(), new Viewport(120, 30)), "cache");

    assertTrue(row.contains("6379→auto"), row);
  }

  @Test
  void the_status_line_counts_the_services_their_endpoints_and_the_unresolved_ones() {
    String status = render(snapshot(), new Viewport(160, 30)).getFirst();

    assertTrue(status.contains("GIMLÉ TOP"), status);
    assertTrue(status.contains("SERVICES"), status);
    assertTrue(status.contains("localhost:8080"), status);
    assertTrue(status.contains("connected"), status);
    assertTrue(status.contains("services 5"), status);
    assertTrue(status.contains("endpoints 6"), status);
    assertTrue(status.contains("1 unresolved"), status);
  }

  @Test
  void the_section_label_says_how_many_services_resolve_to_nothing() {
    String label = lineContaining(render(snapshot(), new Viewport(120, 30)), "SERVICES  5");

    assertTrue(label.contains("1 with no endpoints"), label);
  }

  @Test
  void a_stale_snapshot_keeps_its_rows_and_says_how_old_they_are() {
    ServiceSnapshot stale = snapshot().stale("could not reach control plane");

    List<String> lines = render(stale, new Viewport(120, 30));

    assertTrue(lines.getFirst().contains("could not reach control plane"), lines.getFirst());
    assertTrue(lines.getFirst().contains("old"), lines.getFirst());
    assertTrue(lines.stream().anyMatch(line -> line.contains("greeter-provider")));
  }

  @Test
  void a_paused_view_says_so_on_the_status_line() {
    List<String> lines = screen.render(snapshot(), new Viewport(120, 30), true, NOW);

    assertTrue(lines.getFirst().contains("PAUSED"), lines.getFirst());
  }

  @Test
  void a_cluster_with_no_services_says_so_rather_than_drawing_an_empty_table() {
    ServiceSnapshot empty =
        new ServiceSnapshot("localhost:8080", Optional.of(NOW), List.of(), Optional.empty());

    List<String> lines = render(empty, new Viewport(120, 30));

    assertTrue(lines.stream().anyMatch(line -> line.contains("no services declared")), "" + lines);
  }

  @Test
  void more_services_than_fit_are_counted_rather_than_silently_cut() {
    List<ServiceRow> many =
        IntStream.range(0, 40)
            .mapToObj(index -> row("service-" + index, 8080, OptionalInt.of(9090), 1))
            .toList();
    ServiceSnapshot snapshot =
        new ServiceSnapshot("localhost:8080", Optional.of(NOW), many, Optional.empty());

    List<String> lines = render(snapshot, new Viewport(120, 20));

    assertTrue(
        lines.stream().anyMatch(line -> line.contains("more below this window")), "" + lines);
  }

  @Test
  void columns_align_across_every_row_at_eighty_columns() {
    assertColumnsAlign(new Viewport(80, 30));
  }

  @Test
  void columns_align_across_every_row_at_two_hundred_columns() {
    assertColumnsAlign(new Viewport(200, 30));
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
  void a_long_service_name_truncates_rather_than_pushing_the_row_out_of_shape() {
    ServiceSnapshot snapshot =
        new ServiceSnapshot(
            "localhost:8080",
            Optional.of(NOW),
            List.of(
                row(
                    "a-service-with-a-really-very-long-name-indeed",
                    8080,
                    OptionalInt.of(9090),
                    2)),
            Optional.empty());

    String row = lineContaining(render(snapshot, new Viewport(80, 24)), "a-service");

    assertTrue(row.contains("…"), row);
    assertTrue(Ansi.visibleWidth(row) <= 80, row);
  }

  @Test
  void the_key_bar_is_the_last_line_and_the_frame_never_exceeds_the_viewport() {
    Viewport viewport = new Viewport(120, 12);

    List<String> lines = render(snapshot(), viewport);

    assertEquals(viewport.rows(), lines.size());
    assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    assertTrue(lines.getLast().contains("q quit"), lines.getLast());
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(snapshot(), new Viewport(120, 30))) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private void assertColumnsAlign(final Viewport viewport) {
    List<String> lines = render(snapshot(), viewport);
    String header = lineContaining(lines, "NAME");
    int stateColumn = header.indexOf("STATE");

    for (String name : List.of("greeter", "orphan", "unreadable", "cache")) {
      String row = lineContaining(lines, name);
      String state = row.substring(stateColumn).trim().split("\\s+")[0];
      assertTrue(
          List.of("READY", "NO", "UNKNOWN").contains(state),
          "expected a service state at column " + stateColumn + " of: " + row);
    }
  }

  private List<String> render(final ServiceSnapshot snapshot, final Viewport viewport) {
    return screen.render(snapshot, viewport, false, NOW);
  }

  private static String lineContaining(final List<String> lines, final String needle) {
    return lines.stream()
        .filter(line -> line.contains(needle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line containing '" + needle + "' in " + lines));
  }

  private static ServiceSnapshot snapshot() {
    return new ServiceSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        List.of(
            tenanted("greeter", "acme", 8080, OptionalInt.of(9090), 3),
            row("orphan", 80, OptionalInt.of(80), 0),
            row("unreadable", 8443, OptionalInt.of(8443), -1),
            row("cache", 6379, OptionalInt.empty(), 2),
            external("legacy-billing", "billing.example.com", 443)),
        Optional.empty());
  }

  private static ServiceRow row(
      final String name, final int port, final OptionalInt targetPort, final int endpoints) {
    return new ServiceRow(
        name,
        Optional.empty(),
        List.of(name + "-provider"),
        port,
        targetPort,
        false,
        Optional.empty(),
        "TCP",
        endpointCount(endpoints));
  }

  private static ServiceRow tenanted(
      final String name,
      final String tenantId,
      final int port,
      final OptionalInt targetPort,
      final int endpoints) {
    return new ServiceRow(
        name,
        Optional.of(tenantId),
        List.of(name + "-provider"),
        port,
        targetPort,
        false,
        Optional.empty(),
        "TCP",
        endpointCount(endpoints));
  }

  private static ServiceRow external(final String name, final String host, final int port) {
    return new ServiceRow(
        name,
        Optional.empty(),
        List.of(),
        port,
        OptionalInt.empty(),
        false,
        Optional.of(host),
        "TCP",
        OptionalInt.of(1));
  }

  /** A negative count stands for the endpoint read nobody could make: unknown, not zero. */
  private static OptionalInt endpointCount(final int endpoints) {
    return endpoints < 0 ? OptionalInt.empty() : OptionalInt.of(endpoints);
  }
}
