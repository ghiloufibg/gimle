package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.ResourceColumn;
import com.gimle.hugin.model.ResourceKind;
import com.gimle.hugin.model.ResourceRow;
import com.gimle.hugin.model.ResourceSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The resource browser, rendered as strings. */
class ResourceScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final ResourceScreen screen = new ResourceScreen(new Painter(ColorMode.NONE));

  @Test
  void the_header_is_the_kinds_own_columns_in_the_order_it_declared_them() {
    // Nothing here is fixed per table: a kind registered after this code was written renders by
    // the same path a built-in one does.
    String header = lineContaining(render(tenants(), "", wide()), "POSTURE");

    assertTrue(header.indexOf("NAME") < header.indexOf("POSTURE"), header);
    assertTrue(header.indexOf("POSTURE") < header.indexOf("INSTANCES"), header);
    assertTrue(header.contains("OVER QUOTA"), header);
  }

  @Test
  void every_resource_gets_a_row_of_the_cells_its_columns_resolved_to() {
    String row = lineContaining(render(tenants(), "", wide()), "acme");

    assertTrue(row.contains("STRICT"), row);
    assertTrue(row.contains("3"), row);
  }

  @Test
  void the_view_names_the_route_it_read_so_nobody_has_to_guess_what_they_are_looking_at() {
    String label = labelLine(render(tenants(), "", wide()));

    assertTrue(label.startsWith("TENANTS"), label);
    assertTrue(label.contains("/tenants"), label);
  }

  @Test
  void a_registered_kind_says_it_is_one_because_its_columns_were_somebody_elses_choice() {
    // Two clusters can show the same kind differently; that is worth saying rather than hiding.
    assertTrue(labelLine(render(greetings(), "", wide())).contains("registered"), "custom");
    assertFalse(labelLine(render(tenants(), "", wide())).contains("registered"), "built-in");
  }

  @Test
  void a_caller_without_permission_to_list_is_told_so_rather_than_shown_an_empty_table() {
    List<String> lines =
        render(ResourceSnapshot.forbidden("localhost:8080", tenantKind()), "", wide());

    assertTrue(lines.stream().anyMatch(line -> line.contains("does not carry permission")));
    assertFalse(lines.stream().anyMatch(line -> line.contains("no tenants declared")));
  }

  @Test
  void an_empty_collection_says_so_rather_than_drawing_a_bare_header() {
    ResourceSnapshot empty =
        new ResourceSnapshot(
            "localhost:8080", Optional.of(NOW), tenantKind(), List.of(), true, Optional.empty());

    assertTrue(
        render(empty, "", wide()).stream().anyMatch(line -> line.contains("no tenants declared")));
  }

  @Test
  void the_filter_narrows_the_table_and_says_how_much_of_it_is_left() {
    List<String> lines = render(tenants(), "acme", wide());

    assertTrue(lines.stream().anyMatch(line -> line.contains("acme")));
    assertFalse(lines.stream().anyMatch(line -> line.contains("beta-corp")));
    assertTrue(labelLine(lines).contains("1 of 2"), labelLine(lines));
  }

  @Test
  void a_blank_cell_reads_as_absent_rather_than_as_nothing_at_all() {
    // A run of empty columns is indistinguishable from a row that stopped early.
    ResourceSnapshot snapshot =
        snapshotOf(tenantKind(), row("gamma", "gamma", "gamma", "", "", "", ""));

    assertTrue(lineContaining(render(snapshot, "", wide()), "gamma").contains(Text.ABSENT));
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = render(tenants(), "", viewport);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(
            Ansi.visibleWidth(line) <= viewport.columns(),
            "line wider than " + viewport.columns() + ": " + line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
      // The prompt is the only way to reach another kind, so it has to survive the fit.
      assertTrue(lines.getLast().contains(": kind"), lines.getLast());
    }
  }

  @Test
  void a_kind_declaring_many_columns_still_fits_a_narrow_terminal() {
    // A custom kind's column count is whatever its definition declared, not a number chosen here.
    ResourceKind wide =
        ResourceKind.fromDefinition(
            "Wide",
            "wides",
            Optional.of("many columns"),
            List.of(
                ResourceColumn.of("ALPHA", "spec.a"),
                ResourceColumn.of("BRAVO", "spec.b"),
                ResourceColumn.of("CHARLIE", "spec.c"),
                ResourceColumn.of("DELTA", "spec.d"),
                ResourceColumn.of("ECHO", "spec.e")));
    ResourceSnapshot snapshot = snapshotOf(wide, row("one", "acme", "a", "b", "c", "d", "e"));

    for (String line : render(snapshot, "", new Viewport(80, 24))) {
      assertTrue(Ansi.visibleWidth(line) <= 80, line);
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(tenants(), "", wide())) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private static Viewport wide() {
    return new Viewport(140, 30);
  }

  private List<String> render(
      final ResourceSnapshot snapshot, final String filter, final Viewport viewport) {
    UiState ui = new UiState();
    ui.beginFilter();
    for (char character : filter.toCharArray()) {
      ui.appendToFilter(character);
    }
    ui.commitFilter();
    return screen.render(snapshot, ui, viewport, false, NOW);
  }

  private static ResourceKind tenantKind() {
    return ResourceKind.builtIns().stream()
        .filter(kind -> kind.key().equals("tenants"))
        .findFirst()
        .orElseThrow();
  }

  private static ResourceSnapshot tenants() {
    return snapshotOf(
        tenantKind(),
        row("acme", "acme", "acme", "STRICT", "3", "10", "no"),
        row("beta-corp", "beta-corp", "beta-corp", "SHARED", "1", "4", "no"));
  }

  private static ResourceSnapshot greetings() {
    ResourceKind kind =
        ResourceKind.fromDefinition(
            "Greeting",
            "greetings",
            Optional.of("a greeting"),
            List.of(ResourceColumn.of("MESSAGE", "spec.message")));
    return snapshotOf(kind, row("hello", "acme", "hello", "acme", "góðan dag"));
  }

  private static ResourceSnapshot snapshotOf(final ResourceKind kind, final ResourceRow... rows) {
    return new ResourceSnapshot(
        "localhost:8080", Optional.of(NOW), kind, List.of(rows), true, Optional.empty());
  }

  private static ResourceRow row(final String name, final String tenant, final String... cells) {
    return new ResourceRow(name, Optional.ofNullable(tenant), List.of(cells), Map.of("name", name));
  }

  /**
   * The label line, found by the route only it carries -- the status bar above it also names the
   * kind, so matching on the kind's own name would just as often find that one instead.
   */
  private static String labelLine(final List<String> lines) {
    return lines.stream()
        .filter(line -> line.contains("/tenants") || line.contains("/resources/"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no label line in " + lines));
  }

  private static String lineContaining(final List<String> lines, final String needle) {
    return lines.stream()
        .filter(line -> line.contains(needle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line containing '" + needle + "' in " + lines));
  }
}
