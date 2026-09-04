package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.PermissionRow;
import com.gimle.hugin.model.PermissionSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The kind-by-verb grid, rendered as strings. */
class PermissionScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");
  private static final List<String> VERBS = List.of("READ", "WRITE", "DELETE");

  private final PermissionScreen screen = new PermissionScreen(new Painter(ColorMode.NONE));

  @Test
  void every_cell_is_a_word_so_the_grid_says_the_same_thing_without_colour() {
    String line = lineContaining(render(grid(), ""), "DEPLOYMENT");

    assertTrue(line.contains("yes"), line);
    assertTrue(line.contains("no"), line);
  }

  @Test
  void a_cell_nobody_answered_is_drawn_as_unknown_rather_than_as_a_refusal() {
    // Denial and silence are indistinguishable once drawn, and only one is about anyone's grants.
    String line = lineContaining(render(grid(), ""), "SECRET");

    assertTrue(line.contains("unknown"), line);
  }

  @Test
  void the_identity_the_answers_were_given_for_is_named_on_the_screen() {
    // The same cluster answers a different grid for every certificate that asks it.
    String label = labelLine(render(grid(), ""));

    assertTrue(label.contains("ops@acme"), label);
  }

  @Test
  void an_unidentified_caller_is_said_in_full_rather_than_left_to_be_inferred_from_a_name() {
    // A grid of unbroken yes is exactly what an over-privileged account would produce too.
    List<String> lines = render(anonymousGrid(), "");

    assertTrue(
        lines.stream().anyMatch(line -> line.contains("no client certificate was presented")),
        lines.toString());
    assertTrue(
        lines.stream().anyMatch(line -> line.contains("not about any account's grants")),
        lines.toString());
  }

  @Test
  void an_identified_caller_is_never_given_the_unidentified_warning() {
    List<String> lines = render(grid(), "");

    assertFalse(
        lines.stream().anyMatch(line -> line.contains("no client certificate")), lines.toString());
  }

  @Test
  void a_grid_the_control_plane_would_not_answer_says_so_instead_of_showing_nothing_permitted() {
    // An empty grid reads as "you may do nothing", a much more specific claim than "nobody said".
    List<String> lines =
        render(PermissionSnapshot.unreadable("localhost:8080", "authentication required"), "");

    assertTrue(lines.stream().anyMatch(line -> line.contains("would not say")), lines.toString());
    assertTrue(
        lines.stream().anyMatch(line -> line.contains("authentication required")),
        lines.toString());
  }

  @Test
  void the_number_of_unanswered_cells_is_on_the_label_so_a_partial_read_is_visible() {
    String label = labelLine(render(grid(), ""));

    assertTrue(label.contains("1 unanswered"), label);
  }

  @Test
  void the_filter_narrows_the_rows_shown() {
    List<String> lines = render(grid(), "secret");

    assertTrue(lines.stream().anyMatch(line -> line.contains("SECRET")));
    assertFalse(lines.stream().anyMatch(line -> line.contains("DEPLOYMENT")));
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = screen.render(grid(), new UiState(), viewport, false, NOW);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(Ansi.visibleWidth(line) <= viewport.columns(), line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(grid(), "")) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(final PermissionSnapshot snapshot, final String filter) {
    UiState ui = new UiState();
    ui.beginFilter();
    for (char character : filter.toCharArray()) {
      ui.appendToFilter(character);
    }
    ui.commitFilter();
    return screen.render(snapshot, ui, new Viewport(140, 30), false, NOW);
  }

  private static PermissionSnapshot grid() {
    return snapshot(
        "ops@acme",
        List.of(
            new PermissionRow("DEPLOYMENT", cells(true, false, false)),
            // One verb left out entirely: the cell nobody answered.
            new PermissionRow("SECRET", cells(false, false, null))));
  }

  private static PermissionSnapshot anonymousGrid() {
    return snapshot("anonymous", List.of(new PermissionRow("DEPLOYMENT", cells(true, true, true))));
  }

  private static PermissionSnapshot snapshot(
      final String principal, final List<PermissionRow> rows) {
    return new PermissionSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        principal,
        Optional.empty(),
        VERBS,
        rows,
        true,
        Optional.empty());
  }

  private static Map<String, Boolean> cells(
      final Boolean read, final Boolean write, final Boolean delete) {
    Map<String, Boolean> allowed = new LinkedHashMap<>();
    if (read != null) {
      allowed.put("READ", read);
    }
    if (write != null) {
      allowed.put("WRITE", write);
    }
    if (delete != null) {
      allowed.put("DELETE", delete);
    }
    return allowed;
  }

  /** The label line, found by the wording only it carries. */
  private static String labelLine(final List<String> lines) {
    return lineContaining(lines, "with something permitted");
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
