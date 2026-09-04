package com.gimle.hugin.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.UiState;
import com.gimle.hugin.model.VersionRow;
import com.gimle.hugin.model.VersionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** One resource's revision ledger, rendered as strings. */
class VersionScreenTest {

  private static final Instant NOW = Instant.parse("2026-09-01T14:02:43Z");

  private final VersionScreen screen = new VersionScreen(new Painter(ColorMode.NONE));

  @Test
  void the_revision_in_effect_is_named_on_the_label_and_the_rest_read_as_its_predecessors() {
    List<String> lines = render(ledger(), "");

    assertTrue(labelLine(lines).contains("in effect v3"), labelLine(lines));
    assertTrue(labelLine(lines).contains("newest first"), labelLine(lines));
  }

  @Test
  void a_ledger_that_records_no_author_or_time_leaves_those_columns_blank_rather_than_inventing() {
    // Only the secret ledger records them; a fabricated timestamp would say something false.
    String line = lineContaining(render(ledger(), ""), "v2");

    assertTrue(line.contains("—"), line);
  }

  @Test
  void a_revision_recorded_as_deleted_says_so_in_words() {
    assertTrue(lineContaining(render(ledger(), ""), "v2").contains("DELETED"));
  }

  @Test
  void a_kind_keeping_no_ledger_is_told_apart_from_one_whose_ledger_is_empty() {
    List<String> none =
        render(
            VersionSnapshot.unavailable(
                "localhost:8080", "acme/api", "a tenants keeps no revision history"),
            "");
    List<String> empty =
        render(
            new VersionSnapshot(
                "localhost:8080", Optional.of(NOW), "acme/api", List.of(), true, Optional.empty()),
            "");

    assertTrue(none.stream().anyMatch(line -> line.contains("keeps no revision history")));
    assertTrue(empty.stream().anyMatch(line -> line.contains("no revision has been recorded")));
  }

  @Test
  void the_revision_in_effect_stays_named_even_when_the_filter_hides_it() {
    // Read against the whole ledger rather than what survived the filter, so narrowing to older
    // revisions cannot make one of them read as the revision currently in force.
    List<String> lines = render(ledger(), "INFO");

    assertTrue(labelLine(lines).contains("1 revision"), labelLine(lines));
    assertTrue(labelLine(lines).contains("in effect v3"), labelLine(lines));
    // Matched on the version rather than on the value: the label itself echoes the filter text.
    assertTrue(lineContaining(lines, "v1").contains("INFO"), lineContaining(lines, "v1"));
  }

  @Test
  void the_frame_fits_the_viewport_and_ends_in_the_key_bar() {
    for (Viewport viewport : List.of(new Viewport(80, 24), new Viewport(200, 50))) {
      List<String> lines = screen.render(ledger(), new UiState(), viewport, false, NOW);

      assertEquals(viewport.rows(), lines.size());
      for (String line : lines) {
        assertTrue(Ansi.visibleWidth(line) <= viewport.columns(), line);
      }
      assertTrue(lines.getLast().contains("esc back"), lines.getLast());
    }
  }

  @Test
  void with_colour_switched_off_the_whole_frame_carries_no_escape_sequences() {
    for (String line : render(ledger(), "")) {
      assertFalse(line.contains(Ansi.CSI), "found an escape sequence in: " + line);
    }
  }

  private List<String> render(final VersionSnapshot snapshot, final String filter) {
    UiState ui = new UiState();
    ui.beginFilter();
    for (char character : filter.toCharArray()) {
      ui.appendToFilter(character);
    }
    ui.commitFilter();
    return screen.render(snapshot, ui, new Viewport(140, 30), false, NOW);
  }

  private static VersionSnapshot ledger() {
    return new VersionSnapshot(
        "localhost:8080",
        Optional.of(NOW),
        "acme/db",
        List.of(
            new VersionRow(3, Optional.of("ops@acme"), Optional.of(NOW), "1 key", false),
            new VersionRow(2, Optional.empty(), Optional.empty(), "1 key", true),
            new VersionRow(1, Optional.empty(), Optional.empty(), "INFO", false)),
        true,
        Optional.empty());
  }

  /** The label line, found by wording only it carries -- the title bar also says HISTORY. */
  private static String labelLine(final List<String> lines) {
    return lineContaining(lines, "newest first");
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
