package com.gimle.hugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.NodeSortKey;
import com.gimle.hugin.model.ResourceRow;
import com.gimle.hugin.model.SortKey;
import com.gimle.hugin.model.WorkloadKind;
import java.util.List;
import java.util.Map;
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
  void the_services_view_opens_on_its_own_key_and_closes_back_to_the_cluster_view() {
    assertFalse(ui.viewingServices());

    ui.showServices();
    assertTrue(ui.viewingServices());

    ui.closeServices();
    assertFalse(ui.viewingServices());
  }

  @Test
  void inspecting_nothing_opens_nothing() {
    ui.inspectSelected(List.of());

    assertFalse(ui.inspectingInstance());
  }

  private static List<InstanceRow> rows(final String... keys) {
    return List.of(keys).stream().map(UiStateTest::row).toList();
  }

  // ---- pointing at another control plane ----

  @Test
  void leaving_every_view_puts_the_cluster_table_back_with_nothing_carried_over() {
    // Every screen here is about one cluster; carried across, each would be describing the
    // previous one under the new one's name.
    ui.showServices();
    ui.showActivity();
    ui.showResources();
    ui.describe("checkout-api");
    ui.beginFilter();
    ui.appendToFilter('a');
    ui.commitFilter();
    ui.inspectSelected(rows("checkout-api/0"));

    ui.leaveEveryView();

    assertFalse(ui.viewingServices());
    assertFalse(ui.viewingActivity());
    assertFalse(ui.viewingResources());
    assertFalse(ui.viewingKinds());
    assertFalse(ui.inspectingInstance());
    assertTrue(ui.describing().isEmpty());
    assertTrue(ui.inspectingNode().isEmpty());
    assertEquals("", ui.filter());
  }

  // ---- the log view's own toggles ----

  @Test
  void the_log_view_starts_with_timestamps_shown_and_wrapping_off() {
    // One row per line is what makes a tail scannable; a wrapped stack trace would push
    // everything above it off the top before anyone asked for that.
    assertTrue(ui.logTimestamps());
    assertFalse(ui.logWrap());

    ui.toggleLogWrap();
    ui.toggleLogTimestamps();

    assertFalse(ui.logTimestamps());
    assertTrue(ui.logWrap());
  }

  // ---- listing what the prompt can open ----

  @Test
  void the_kinds_list_and_the_browser_are_never_open_at_the_same_time() {
    ui.showKinds();
    assertTrue(ui.viewingKinds());

    ui.showResources();

    assertFalse(ui.viewingKinds(), "opening a kind leaves the list it was chosen from");
    assertTrue(ui.viewingResources());
  }

  // ---- picking an ordering outright ----

  @Test
  void a_digit_picks_the_ordering_at_that_position_rather_than_cycling_to_it() {
    // Reaching the last of seven orderings by repeating a key is six presses and a wrong guess.
    ui.sortBy(1);
    assertEquals(SortKey.NAME, ui.sortKey());

    ui.sortBy(SortKey.count());
    assertEquals(SortKey.values()[SortKey.count() - 1], ui.sortKey());

    ui.sortNodesBy(2);
    assertEquals(NodeSortKey.values()[1], ui.nodeSortKey());
  }

  @Test
  void a_position_no_column_occupies_leaves_the_ordering_alone_rather_than_wrapping() {
    ui.sortBy(3);
    SortKey chosen = ui.sortKey();

    ui.sortBy(0);
    ui.sortBy(SortKey.count() + 1);
    ui.sortBy(99);

    assertEquals(chosen, ui.sortKey());
  }

  @Test
  void describing_by_name_opens_the_pane_on_something_no_table_has_been_read_for_yet() {
    // `d` in the cluster view knows the workload's name before the browser has read anything.
    ui.showResources();
    ui.describe("checkout-api");

    assertEquals(Optional.of("checkout-api"), ui.describing());
  }

  // ---- the `:` command prompt ----

  @Test
  void typing_a_kind_builds_it_up_and_backspace_takes_it_back_down() {
    ui.beginCommand();
    "tenants".chars().forEach(character -> ui.appendToCommand((char) character));
    assertEquals("tenants", ui.command());

    ui.backspaceCommand();
    assertEquals("tenant", ui.command());
    assertTrue(ui.commandEditing());
  }

  @Test
  void a_rejected_command_leaves_a_message_behind_because_the_prompt_it_came_from_is_gone() {
    // The screen does not change on a rejected command, so without the message an operator who
    // mistyped is answered by nothing happening at all.
    ui.beginCommand();
    ui.appendToCommand('x');
    ui.failCommand("no kind named 'x'");

    assertFalse(ui.commandEditing());
    assertEquals(Optional.of("no kind named 'x'"), ui.commandError());

    ui.clearCommandError();
    assertTrue(ui.commandError().isEmpty());
  }

  @Test
  void opening_the_prompt_again_clears_whatever_the_last_one_was_rejected_for() {
    ui.beginCommand();
    ui.failCommand("no kind named 'x'");

    ui.beginCommand();

    assertTrue(ui.commandError().isEmpty());
    assertEquals("", ui.command());
  }

  @Test
  void cancelling_the_prompt_opens_nothing_and_leaves_no_message() {
    ui.beginCommand();
    ui.appendToCommand('t');
    ui.cancelCommand();

    assertFalse(ui.commandEditing());
    assertFalse(ui.viewingResources());
    assertTrue(ui.commandError().isEmpty());
  }

  // ---- the resource browser and its describe pane ----

  @Test
  void the_resource_cursor_follows_its_own_row_rather_than_a_row_number() {
    List<ResourceRow> rows = resources("alpha", "beta", "gamma");
    ui.moveResourceSelection(rows, 2);
    assertEquals(2, ui.resourceSelectionIndex(rows));

    // "alpha" leaves the collection; the cursor stays on gamma rather than sliding onto beta.
    assertEquals(1, ui.resourceSelectionIndex(resources("beta", "gamma")));
  }

  @Test
  void a_cursor_whose_resource_is_gone_falls_back_to_the_first_row_not_to_nothing() {
    ui.moveResourceSelection(resources("alpha"), 0);

    assertEquals(0, ui.resourceSelectionIndex(resources("beta", "gamma")));
    assertEquals(-1, ui.resourceSelectionIndex(List.of()));
  }

  @Test
  void opening_a_new_kind_puts_the_cursor_back_at_the_top_of_it() {
    // The cursor names a resource, and a resource of one kind means nothing in another's list.
    ui.moveResourceSelection(resources("alpha", "beta"), 1);
    ui.showResources();

    assertEquals(0, ui.resourceSelectionIndex(resources("alpha", "beta")));
    assertTrue(ui.describing().isEmpty());
  }

  @Test
  void describing_opens_on_the_selected_resource_and_closes_back_to_the_table() {
    List<ResourceRow> rows = resources("alpha", "beta");
    ui.moveResourceSelection(rows, 1);
    ui.describeSelected(rows);
    assertEquals(Optional.of("beta"), ui.describing());

    // Closing the pane returns to the table with the cursor still on what was being described.
    ui.closeDescribe();
    assertTrue(ui.describing().isEmpty());
    assertEquals(1, ui.resourceSelectionIndex(rows));
  }

  @Test
  void describing_nothing_is_a_no_op_rather_than_an_empty_pane() {
    ui.describeSelected(List.of());

    assertTrue(ui.describing().isEmpty());
  }

  @Test
  void the_describe_offset_is_clamped_to_what_the_document_actually_has() {
    // The document's length is only known at render time, so a stored scroll can outrun it.
    ui.scrollDescribeToBottom();
    assertEquals(20, ui.describeOffset(30, 10));

    assertEquals(0, ui.describeOffset(5, 10), "a document that fits never scrolls");

    ui.scrollDescribeToTop();
    ui.scrollDescribe(-5);
    assertEquals(0, ui.describeOffset(30, 10), "scrolling up past the top stops at it");
  }

  private static List<ResourceRow> resources(final String... names) {
    return List.of(names).stream()
        .map(name -> new ResourceRow(name, Optional.empty(), List.of(name), Map.of("name", name)))
        .toList();
  }

  private static InstanceRow row(final String key) {
    return new InstanceRow(
        key(key),
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

  private static InstanceKey key(final String value) {
    String[] parts = value.split("/");
    return new InstanceKey(Optional.empty(), parts[0], Integer.parseInt(parts[1]));
  }
}
