package com.gimle.hugin;

import com.gimle.hugin.model.FeedMode;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.NodeRow;
import com.gimle.hugin.model.NodeSortKey;
import com.gimle.hugin.model.ResourceRow;
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
  private boolean viewingActivity;
  private FeedMode feedMode = FeedMode.AUDIT;
  private SortKey sortKey = SortKey.NAME;
  private NodeSortKey nodeSortKey = NodeSortKey.ID;
  private boolean commandEditing;
  private String command = "";
  private Optional<String> commandError = Optional.empty();
  private boolean viewingResources;
  private boolean viewingKinds;
  private boolean logWrap;
  private boolean logTimestamps = true;
  private Optional<String> selectedResource = Optional.empty();
  private Optional<String> describing = Optional.empty();
  private int describeScroll;

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

  // ---- the `:` command prompt ----

  public boolean commandEditing() {
    return commandEditing;
  }

  public String command() {
    return command;
  }

  /**
   * What the last submitted command was rejected for, if it was. Held rather than shown once and
   * forgotten: the prompt closes on submit, so a message that vanished with it would leave an
   * operator who mistyped looking at an unchanged screen with no idea why.
   */
  public Optional<String> commandError() {
    return commandError;
  }

  public void beginCommand() {
    commandEditing = true;
    command = "";
    commandError = Optional.empty();
  }

  public void appendToCommand(final char character) {
    command += character;
  }

  public void backspaceCommand() {
    if (!command.isEmpty()) {
      command = command.substring(0, command.length() - 1);
    }
  }

  public void cancelCommand() {
    commandEditing = false;
    command = "";
  }

  /** Closes the prompt, reporting why what was typed named no kind. */
  public void failCommand(final String message) {
    commandEditing = false;
    command = "";
    commandError = Optional.of(message);
  }

  /** Dismisses the message. Any keystroke after the failed one counts as having read it. */
  public void clearCommandError() {
    commandError = Optional.empty();
  }

  /**
   * Whether a log line too long for the pane continues onto the next row instead of being cut. Off
   * by default: one line per line is what makes a tail scannable, and a stack trace turned on would
   * push everything above it off the top.
   */
  public boolean logWrap() {
    return logWrap;
  }

  public void toggleLogWrap() {
    logWrap = !logWrap;
  }

  /** Whether the clock column is drawn. Hiding it gives its width to the message. */
  public boolean logTimestamps() {
    return logTimestamps;
  }

  public void toggleLogTimestamps() {
    logTimestamps = !logTimestamps;
  }

  /**
   * Closes every view, leaving the cluster table showing. What {@code :ctx} needs: every screen
   * here is about one cluster, so none of them survives being pointed at another. The filter goes
   * with them -- it was narrowing rows that no longer exist.
   */
  public void leaveEveryView() {
    viewingKinds = false;
    viewingResources = false;
    viewingServices = false;
    viewingActivity = false;
    selectedResource = Optional.empty();
    describing = Optional.empty();
    describeScroll = 0;
    inspecting = Optional.empty();
    inspectingNode = Optional.empty();
    selected = Optional.empty();
    selectedNode = Optional.empty();
    commandEditing = false;
    commandError = Optional.empty();
    clearFilter();
  }

  /** Whether the list of what {@code :} can open is showing. */
  public boolean viewingKinds() {
    return viewingKinds;
  }

  public void showKinds() {
    viewingKinds = true;
    commandEditing = false;
    commandError = Optional.empty();
  }

  public void closeKinds() {
    viewingKinds = false;
  }

  // ---- the resource browser and its describe pane ----

  public boolean viewingResources() {
    return viewingResources;
  }

  /** Opens the browser on a new kind, with the cursor and any previous error both reset. */
  public void showResources() {
    viewingResources = true;
    viewingKinds = false;
    selectedResource = Optional.empty();
    describing = Optional.empty();
    describeScroll = 0;
    commandEditing = false;
    commandError = Optional.empty();
  }

  public void closeResources() {
    viewingResources = false;
    selectedResource = Optional.empty();
    describing = Optional.empty();
  }

  /**
   * Where the cursor sits, by the same "follow the thing, not the row number" rule the other tables
   * use: a resource that has left the list drops the cursor to the first row rather than off the
   * end of one that has since got shorter.
   */
  public int resourceSelectionIndex(final List<ResourceRow> rows) {
    if (rows.isEmpty()) {
      return -1;
    }
    if (selectedResource.isPresent()) {
      for (int index = 0; index < rows.size(); index++) {
        if (rows.get(index).name().equals(selectedResource.get())) {
          return index;
        }
      }
    }
    return 0;
  }

  public void moveResourceSelection(final List<ResourceRow> rows, final int delta) {
    if (rows.isEmpty()) {
      selectedResource = Optional.empty();
      return;
    }
    int target = Math.clamp((long) resourceSelectionIndex(rows) + delta, 0, rows.size() - 1);
    selectedResource = Optional.of(rows.get(target).name());
  }

  public void selectFirstResource(final List<ResourceRow> rows) {
    selectedResource = rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst().name());
  }

  public void selectLastResource(final List<ResourceRow> rows) {
    selectedResource = rows.isEmpty() ? Optional.empty() : Optional.of(rows.getLast().name());
  }

  public Optional<String> describing() {
    return describing;
  }

  /** Opens the describe pane on whatever is selected. A no-op when nothing is. */
  public void describeSelected(final List<ResourceRow> rows) {
    int index = resourceSelectionIndex(rows);
    if (index >= 0) {
      describing = Optional.of(rows.get(index).name());
      describeScroll = 0;
    }
  }

  /**
   * Opens the describe pane on a resource named rather than pointed at -- how {@code d} in the
   * cluster view reaches the workload behind an instance row, which is a name it already knows and
   * not a row in a table that has not been read yet.
   */
  public void describe(final String name) {
    selectedResource = Optional.of(name);
    describing = Optional.of(name);
    describeScroll = 0;
  }

  public void closeDescribe() {
    describing = Optional.empty();
    describeScroll = 0;
  }

  public void scrollDescribe(final int delta) {
    describeScroll = Math.max(0, describeScroll + delta);
  }

  public void scrollDescribeToTop() {
    describeScroll = 0;
  }

  /** Far enough that any document lands on its last page, clamped on read to whatever fits. */
  public void scrollDescribeToBottom() {
    describeScroll = Integer.MAX_VALUE - 1;
  }

  /**
   * The first line to draw, clamped here rather than at every scroll: the document's length is only
   * known at render time, and a scroll position stored past the end would otherwise show a blank
   * pane until it was scrolled back.
   */
  public int describeOffset(final int total, final int available) {
    return total <= available ? 0 : Math.clamp(describeScroll, 0, total - available);
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

  /** Picks an ordering outright. Ignores a position no column occupies rather than wrapping. */
  public void sortBy(final int position) {
    SortKey.at(position).ifPresent(key -> sortKey = key);
  }

  public NodeSortKey nodeSortKey() {
    return nodeSortKey;
  }

  /** Cycles the node ordering. Which table {@code o} acts on follows the cursor's own focus. */
  public void cycleNodeSort() {
    nodeSortKey = nodeSortKey.next();
  }

  public void sortNodesBy(final int position) {
    NodeSortKey.at(position).ifPresent(key -> nodeSortKey = key);
  }

  public FeedMode feedMode() {
    return feedMode;
  }

  public void cycleFeedMode() {
    feedMode = feedMode.next();
  }

  public boolean viewingActivity() {
    return viewingActivity;
  }

  public void showActivity() {
    viewingActivity = true;
  }

  public void closeActivity() {
    viewingActivity = false;
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
