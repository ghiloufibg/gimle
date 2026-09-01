package com.gimle.hugin;

import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import java.util.List;
import java.util.Optional;

/**
 * Everything the operator has done that the cluster itself doesn't know about: where the cursor is,
 * what's typed in the filter, whether refresh is paused, and which instance (if any) is open.
 *
 * <p>Owned by the render loop's own thread and never touched by the poller, so it needs no
 * synchronization of its own. Selection is held as an {@link InstanceKey} rather than a row index:
 * a new instance appearing above the cursor would otherwise silently move it onto a different one
 * between two polls.
 */
public final class UiState {

  private Optional<InstanceKey> selected = Optional.empty();
  private Optional<InstanceKey> inspecting = Optional.empty();
  private String filter = "";
  private boolean filterEditing;
  private boolean helpVisible;

  public Optional<InstanceKey> selected() {
    return selected;
  }

  public Optional<InstanceKey> inspecting() {
    return inspecting;
  }

  public boolean inspectingInstance() {
    return inspecting.isPresent();
  }

  public String filter() {
    return filter;
  }

  public boolean filterEditing() {
    return filterEditing;
  }

  public boolean helpVisible() {
    return helpVisible;
  }

  public void toggleHelp() {
    helpVisible = !helpVisible;
  }

  public void hideHelp() {
    helpVisible = false;
  }

  public void beginFilter() {
    filterEditing = true;
  }

  public void commitFilter() {
    filterEditing = false;
  }

  public void clearFilter() {
    filter = "";
    filterEditing = false;
  }

  public void appendToFilter(final char character) {
    filter += character;
  }

  public void backspaceFilter() {
    if (!filter.isEmpty()) {
      filter = filter.substring(0, filter.length() - 1);
    }
  }

  /**
   * Where the cursor currently sits in {@code rows}. A selection whose instance has left the list
   * -- filtered out, or gone from the cluster -- falls back to the nearest row that still exists
   * rather than to nothing, so the cursor never disappears mid-session.
   */
  public int selectionIndex(final List<InstanceRow> rows) {
    if (rows.isEmpty()) {
      return -1;
    }
    if (selected.isPresent()) {
      for (int index = 0; index < rows.size(); index++) {
        if (rows.get(index).key().equals(selected.get())) {
          return index;
        }
      }
    }
    return 0;
  }

  public void moveSelection(final List<InstanceRow> rows, final int delta) {
    if (rows.isEmpty()) {
      selected = Optional.empty();
      return;
    }
    int target = Math.clamp((long) selectionIndex(rows) + delta, 0, rows.size() - 1);
    selected = Optional.of(rows.get(target).key());
  }

  public void selectFirst(final List<InstanceRow> rows) {
    selected = rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst().key());
  }

  public void selectLast(final List<InstanceRow> rows) {
    selected = rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast().key());
  }

  /** Opens the drill-down on whatever is currently selected. A no-op when nothing is. */
  public void inspectSelected(final List<InstanceRow> rows) {
    int index = selectionIndex(rows);
    if (index >= 0) {
      inspecting = Optional.of(rows.get(index).key());
    }
  }

  public void closeInspection() {
    inspecting = Optional.empty();
  }
}
