package com.gimle.hugin;

import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.NodeRow;
import com.gimle.hugin.model.SortKey;
import java.util.List;
import java.util.Optional;

/**
 * Everything the operator has done that the cluster itself doesn't know about: where the cursor is,
 * what's typed in the filter, whether refresh is paused, and which of the views -- the cluster
 * table, the services table, one instance's drill-down -- is open.
 *
 * <p>Owned by the render loop's own thread and never touched by the poller, so it needs no
 * synchronization of its own. Selection is held as an {@link InstanceKey} rather than a row index:
 * a new instance appearing above the cursor would otherwise silently move it onto a different one
 * between two polls.
 */
public final class UiState {

  /** Which of the cluster view's two tables the cursor and {@code enter} currently act on. */
  public enum Focus {
    INSTANCES,
    NODES
  }

  private Focus focus = Focus.INSTANCES;
  private Optional<String> selectedNode = Optional.empty();
  private Optional<String> inspectingNode = Optional.empty();
  private Optional<InstanceKey> selected = Optional.empty();
  private Optional<InstanceKey> inspecting = Optional.empty();
  private String filter = "";
  private boolean filterEditing;
  private boolean helpVisible;
  private boolean viewingServices;
  private SortKey sortKey = SortKey.NAME;

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

  public Focus focus() {
    return focus;
  }

  public void toggleFocus() {
    focus = focus == Focus.INSTANCES ? Focus.NODES : Focus.INSTANCES;
  }

  public Optional<String> selectedNode() {
    return selectedNode;
  }

  public Optional<String> inspectingNode() {
    return inspectingNode;
  }

  public boolean inspectingNode(final String nodeId) {
    return inspectingNode.filter(nodeId::equals).isPresent();
  }

  /**
   * Where the node cursor sits, by the same "follow the thing, not the row number" rule the
   * instance cursor uses -- a node leaving the list drops the cursor to the first row rather than
   * to nothing.
   */
  public int nodeSelectionIndex(final List<NodeRow> rows) {
    if (rows.isEmpty()) {
      return -1;
    }
    if (selectedNode.isPresent()) {
      for (int index = 0; index < rows.size(); index++) {
        if (rows.get(index).nodeId().equals(selectedNode.get())) {
          return index;
        }
      }
    }
    return 0;
  }

  public void moveNodeSelection(final List<NodeRow> rows, final int delta) {
    if (rows.isEmpty()) {
      selectedNode = Optional.empty();
      return;
    }
    int target = Math.clamp((long) nodeSelectionIndex(rows) + delta, 0, rows.size() - 1);
    selectedNode = Optional.of(rows.get(target).nodeId());
  }

  public void selectFirstNode(final List<NodeRow> rows) {
    selectedNode = rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst().nodeId());
  }

  public void selectLastNode(final List<NodeRow> rows) {
    selectedNode = rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast().nodeId());
  }

  /** Opens the node drill-down on whatever node is selected. A no-op when none is. */
  public void inspectSelectedNode(final List<NodeRow> rows) {
    int index = nodeSelectionIndex(rows);
    if (index >= 0) {
      inspectingNode = Optional.of(rows.get(index).nodeId());
    }
  }

  public void closeNodeInspection() {
    inspectingNode = Optional.empty();
  }

  public SortKey sortKey() {
    return sortKey;
  }

  /**
   * Cycles the ordering. The selection is held as an instance key rather than a row index, so it
   * follows its own instance to wherever the new ordering puts it instead of staying on a row
   * number that now means something else.
   */
  public void cycleSort() {
    sortKey = sortKey.next();
  }

  public boolean viewingServices() {
    return viewingServices;
  }

  public void showServices() {
    viewingServices = true;
  }

  public void closeServices() {
    viewingServices = false;
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
