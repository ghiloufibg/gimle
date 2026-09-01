package com.gimle.hugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Cursor and filter behaviour across the list changing underneath them. */
class UiStateTest {

  private final UiState ui = new UiState();

  @Test
  void the_cursor_stays_on_the_same_instance_when_a_new_row_appears_above_it() {
    List<InstanceRow> before = rows("alpha/0", "beta/0");
    ui.moveSelection(before, 1);
    assertEquals(1, ui.selectionIndex(before));

    List<InstanceRow> after = rows("aardvark/0", "alpha/0", "beta/0");

    assertEquals(2, ui.selectionIndex(after), "the cursor must follow the instance, not the row");
    assertEquals(Optional.of(key("beta/0")), ui.selected());
  }

  @Test
  void a_selection_whose_instance_has_gone_falls_back_to_a_row_that_still_exists() {
    List<InstanceRow> before = rows("alpha/0", "beta/0");
    ui.moveSelection(before, 1);

    assertEquals(0, ui.selectionIndex(rows("alpha/0")));
  }

  @Test
  void moving_the_cursor_stops_at_both_ends_rather_than_wrapping() {
    List<InstanceRow> rows = rows("alpha/0", "beta/0", "gamma/0");
    ui.selectFirst(rows);

    ui.moveSelection(rows, -1);
    assertEquals(0, ui.selectionIndex(rows));

    ui.moveSelection(rows, 50);
    assertEquals(2, ui.selectionIndex(rows));
  }

  @Test
  void an_empty_list_has_no_selection_at_all() {
    ui.moveSelection(List.of(), 1);

    assertEquals(-1, ui.selectionIndex(List.of()));
    assertEquals(Optional.empty(), ui.selected());
  }

  @Test
  void typing_a_filter_builds_it_up_and_escape_clears_both_it_and_the_editing_mode() {
    ui.beginFilter();
    ui.appendToFilter('g');
    ui.appendToFilter('r');
    ui.backspaceFilter();
    ui.appendToFilter('o');
    assertEquals("go", ui.filter());
    assertTrue(ui.filterEditing());

    ui.commitFilter();
    assertFalse(ui.filterEditing());
    assertEquals("go", ui.filter());

    ui.clearFilter();
    assertEquals("", ui.filter());
  }

  @Test
  void inspecting_opens_whatever_is_selected_and_closing_puts_it_back() {
    List<InstanceRow> rows = rows("alpha/0", "beta/0");
    ui.moveSelection(rows, 1);

    ui.inspectSelected(rows);
    assertTrue(ui.inspectingInstance());
    assertEquals(Optional.of(key("beta/0")), ui.inspecting());

    ui.closeInspection();
    assertFalse(ui.inspectingInstance());
  }

  @Test
  void inspecting_nothing_opens_nothing() {
    ui.inspectSelected(List.of());

    assertFalse(ui.inspectingInstance());
  }

  private static List<InstanceRow> rows(final String... keys) {
    return List.of(keys).stream().map(UiStateTest::row).toList();
  }

  private static InstanceRow row(final String key) {
    return new InstanceRow(
        key(key),
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
        Optional.empty());
  }

  private static InstanceKey key(final String value) {
    String[] parts = value.split("/");
    return new InstanceKey(Optional.empty(), parts[0], Integer.parseInt(parts[1]));
  }
}
