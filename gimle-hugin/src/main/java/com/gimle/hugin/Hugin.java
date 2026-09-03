package com.gimle.hugin;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.hugin.model.ActivityReader;
import com.gimle.hugin.model.ActivitySnapshot;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.InstanceWatcher;
import com.gimle.hugin.model.LogCategory;
import com.gimle.hugin.model.NodeRow;
import com.gimle.hugin.model.ServiceReader;
import com.gimle.hugin.model.ServiceSnapshot;
import com.gimle.hugin.model.SnapshotPoller;
import com.gimle.hugin.model.SnapshotReader;
import com.gimle.hugin.render.ActivityScreen;
import com.gimle.hugin.render.ClusterScreen;
import com.gimle.hugin.render.HelpOverlay;
import com.gimle.hugin.render.InstanceScreen;
import com.gimle.hugin.render.NodeScreen;
import com.gimle.hugin.render.Painter;
import com.gimle.hugin.render.ServiceScreen;
import com.gimle.hugin.render.Viewport;
import com.gimle.hugin.term.Key;
import com.gimle.hugin.term.TerminalSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The event loop: read a key or time out, act on it, repaint from whatever snapshot is current.
 *
 * <p>It never performs I/O against the control plane itself -- the poller and, when an instance is
 * open, its watcher do that on their own virtual threads -- so a slow or unreachable control plane
 * costs freshness, never responsiveness. Pressing {@code q} on a cluster that has stopped answering
 * still quits immediately.
 */
public final class Hugin {

  /** Fixed for now, and the same interval the design settled on: it matches what a poll costs. */
  private final RefreshIntervals intervals;

  /**
   * How long a frame waits for a key before repainting anyway. Short enough that a live log tail
   * and an ageing status line both look continuous, long enough to be nearly free.
   */
  private static final int FRAME_TIMEOUT_MILLIS = 200;

  private final ClusterReader reader;
  private final TerminalSession terminal;
  private final ClusterScreen clusterScreen;
  private final InstanceScreen instanceScreen;
  private final ServiceScreen serviceScreen;
  private final HelpOverlay helpOverlay;
  private final UiState ui = new UiState();

  private LogCategory logCategory = LogCategory.APPLICATION;
  private InstanceWatcher watcher;
  private final NodeScreen nodeScreen;
  private final ActivityScreen activityScreen;
  private SnapshotPoller<ActivitySnapshot> activityPoller;
  private ActivityReader activityReader;
  private SnapshotPoller<ServiceSnapshot> servicePoller;
  private boolean running = true;

  public Hugin(
      final ClusterReader reader,
      final TerminalSession terminal,
      final Painter painter,
      final RefreshIntervals intervals) {
    this.reader = reader;
    this.intervals = intervals;
    this.terminal = terminal;
    this.clusterScreen = new ClusterScreen(painter);
    this.instanceScreen = new InstanceScreen(painter);
    this.serviceScreen = new ServiceScreen(painter);
    this.nodeScreen = new NodeScreen(painter);
    this.activityScreen = new ActivityScreen(painter);
    this.helpOverlay = new HelpOverlay(painter);
  }

  public void run() {
    SnapshotReader snapshots = new SnapshotReader(reader);
    try (SnapshotPoller<ClusterSnapshot> poller =
        new SnapshotPoller<>(
            snapshots::read,
            ClusterSnapshot.connecting(reader.serverAddress()),
            intervals.cluster(),
            "hugin-cluster")) {
      poller.start();
      while (running) {
        ClusterSnapshot snapshot = poller.current();
        terminal.paint(frame(snapshot, poller.paused()));
        terminal.readKey(FRAME_TIMEOUT_MILLIS).ifPresent(key -> handle(key, snapshot, poller));
      }
    } finally {
      closeWatcher();
      closeServicePoller();
      closeActivityPoller();
    }
  }

  private List<String> frame(final ClusterSnapshot snapshot, final boolean paused) {
    Viewport viewport = terminal.viewport();
    Instant now = Instant.now();
    if (ui.helpVisible()) {
      return helpOverlay.render(viewport);
    }
    if (ui.viewingActivity() && activityPoller != null) {
      return activityScreen.render(
          activityPoller.current(), ui, viewport, activityPoller.paused(), now);
    }
    if (ui.viewingActivity() && activityPoller != null) {
      return activityScreen.render(
          activityPoller.current(), ui, viewport, activityPoller.paused(), now);
    }
    if (ui.viewingServices() && servicePoller != null) {
      return serviceScreen.render(
          servicePoller.current(), ui, viewport, servicePoller.paused(), now);
    }
    Optional<NodeRow> inspectedNode =
        ui.inspectingNode()
            .flatMap(
                nodeId ->
                    snapshot.nodes().stream().filter(n -> n.nodeId().equals(nodeId)).findFirst());
    if (inspectedNode.isPresent()) {
      return nodeScreen.render(inspectedNode.get(), snapshot, viewport, paused, now);
    }
    // The node was open and has since left the cluster, the same way an inspected instance can.
    if (ui.inspectingNode().isPresent()) {
      ui.closeNodeInspection();
    }
    Optional<InstanceRow> inspected = ui.inspecting().flatMap(snapshot::find);
    if (inspected.isPresent() && watcher != null) {
      return instanceScreen.render(inspected.get(), watcher, viewport, paused, now);
    }
    // The instance was open and has since left the cluster -- deleted, rescheduled, or the whole
    // deployment removed. Falling back to the cluster view is the honest thing to show, rather
    // than a detail pane about something that is no longer there.
    if (ui.inspectingInstance()) {
      closeInspection();
    }
    return clusterScreen.render(snapshot, ui, viewport, paused, now);
  }

  private void handle(
      final Key key, final ClusterSnapshot snapshot, final SnapshotPoller<ClusterSnapshot> poller) {
    if (key.is(Key.Kind.INTERRUPT) || key.is(Key.Kind.END_OF_INPUT)) {
      running = false;
      return;
    }
    if (ui.filterEditing()) {
      handleFilterKey(key);
      return;
    }
    if (ui.helpVisible()) {
      // Any key closes the help: an operator who opened it by accident should not have to work out
      // which key gets them back.
      ui.hideHelp();
      return;
    }
    if (ui.viewingActivity()) {
      handleActivityKey(key);
      return;
    }
    if (ui.viewingServices() && servicePoller != null) {
      handleServicesKey(key, servicePoller);
      return;
    }
    if (ui.viewingActivity()) {
      handleActivityKey(key);
      return;
    }
    if (ui.inspectingNode().isPresent()) {
      handleNodeKey(key, poller);
      return;
    }
    if (ui.inspectingInstance()) {
      handleInstanceKey(key, poller);
      return;
    }
    handleClusterKey(key, snapshot, poller);
  }

  private void handleClusterKey(
      final Key key, final ClusterSnapshot snapshot, final SnapshotPoller<ClusterSnapshot> poller) {
    List<InstanceRow> rows = snapshot.instancesMatching(ui.filter(), ui.sortKey());
    List<NodeRow> nodeRows = snapshot.nodesMatching(ui.filter(), ui.nodeSortKey(), Instant.now());
    boolean onNodes = ui.focus() == UiState.Focus.NODES;
    if (key.is(Key.Kind.TAB)) {
      ui.toggleFocus();
    } else if (key.is(Key.Kind.UP) || key.isChar('k')) {
      moveCursor(onNodes, rows, nodeRows, -1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      moveCursor(onNodes, rows, nodeRows, 1);
    } else if (key.isChar('g')) {
      if (onNodes) {
        ui.selectFirstNode(nodeRows);
      } else {
        ui.selectFirst(rows);
      }
    } else if (key.isChar('G')) {
      if (onNodes) {
        ui.selectLastNode(nodeRows);
      } else {
        ui.selectLast(rows);
      }
    } else if (key.is(Key.Kind.ENTER)) {
      if (onNodes) {
        ui.inspectSelectedNode(nodeRows);
      } else {
        openInspection(rows);
      }
    } else if (key.isChar('o')) {
      // `o` sorts whichever table the cursor is on, so one key means "order this" on both.
      if (onNodes) {
        ui.cycleNodeSort();
      } else {
        ui.cycleSort();
      }
    } else if (key.isChar('a')) {
      openActivity();
    } else if (key.isChar('s')) {
      openServices();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.is(Key.Kind.ESCAPE)) {
      ui.clearFilter();
    } else if (key.isChar('p')) {
      poller.togglePaused();
    } else if (key.isChar('r')) {
      poller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  private void handleServicesKey(final Key key, final SnapshotPoller<ServiceSnapshot> poller) {
    if (key.is(Key.Kind.ESCAPE)) {
      closeServices();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar('p')) {
      poller.togglePaused();
    } else if (key.isChar('r')) {
      poller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  private void handleInstanceKey(final Key key, final SnapshotPoller<ClusterSnapshot> poller) {
    if (key.is(Key.Kind.ESCAPE)) {
      closeInspection();
    } else if (key.isChar('c')) {
      cycleLogCategory();
    } else if (key.isChar('p')) {
      poller.togglePaused();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  private void handleFilterKey(final Key key) {
    if (key.is(Key.Kind.ENTER)) {
      ui.commitFilter();
    } else if (key.is(Key.Kind.ESCAPE)) {
      ui.clearFilter();
    } else if (key.is(Key.Kind.BACKSPACE)) {
      ui.backspaceFilter();
    } else if (key instanceof Key.Character character && character.value() >= ' ') {
      ui.appendToFilter(character.value());
    }
  }

  private void moveCursor(
      final boolean onNodes,
      final List<InstanceRow> rows,
      final List<NodeRow> nodeRows,
      final int delta) {
    if (onNodes) {
      ui.moveNodeSelection(nodeRows, delta);
    } else {
      ui.moveSelection(rows, delta);
    }
  }

  private void handleActivityKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      closeActivity();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar('m') && activityReader != null) {
      activityReader.loadMore();
      activityPoller.refreshNow();
    } else if (key.isChar('p') && activityPoller != null) {
      activityPoller.togglePaused();
    } else if (key.isChar('r') && activityPoller != null) {
      activityPoller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  /**
   * Like the services screen, this polls only while it is open: the audit trail is a read an
   * operator opens deliberately, and one nobody is looking at is a request per interval for a
   * permission not every caller even has.
   */
  private void openActivity() {
    ui.showActivity();
    activityReader = new ActivityReader(reader);
    activityPoller =
        new SnapshotPoller<>(
            activityReader::read,
            ActivitySnapshot.connecting(reader.serverAddress()),
            intervals.activity(),
            "hugin-activity");
    activityPoller.start();
  }

  private void closeActivity() {
    ui.closeActivity();
    closeActivityPoller();
  }

  private void closeActivityPoller() {
    if (activityPoller != null) {
      activityPoller.close();
      activityPoller = null;
    }
    activityReader = null;
  }

  private void handleNodeKey(final Key key, final SnapshotPoller<ClusterSnapshot> poller) {
    if (key.is(Key.Kind.ESCAPE)) {
      ui.closeNodeInspection();
    } else if (key.isChar('p')) {
      poller.togglePaused();
    } else if (key.isChar('r')) {
      poller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  private void openInspection(final List<InstanceRow> rows) {
    ui.inspectSelected(rows);
    ui.inspecting().ifPresent(this::watch);
  }

  private void closeInspection() {
    ui.closeInspection();
    closeWatcher();
  }

  /**
   * The services poll lives only as long as the screen showing it. One read costs a request per
   * declared Service -- the endpoint set has to be asked for one Service at a time -- which is not
   * a price to keep paying on a two-second interval while nobody is looking at the answer.
   */
  private void openServices() {
    ui.showServices();
    closeServicePoller();
    servicePoller =
        new SnapshotPoller<>(
            new ServiceReader(reader)::read,
            ServiceSnapshot.connecting(reader.serverAddress()),
            intervals.services(),
            "hugin-services");
    servicePoller.start();
  }

  private void closeServices() {
    ui.closeServices();
    closeServicePoller();
  }

  private void closeServicePoller() {
    if (servicePoller != null) {
      servicePoller.close();
      servicePoller = null;
    }
  }

  /** Reopens the tail on the other category, keeping the same instance open. */
  private void cycleLogCategory() {
    logCategory = logCategory.next();
    ui.inspecting().ifPresent(this::watch);
  }

  private void watch(final InstanceKey key) {
    closeWatcher();
    watcher = new InstanceWatcher(reader, key, logCategory);
    watcher.start();
  }

  private void closeWatcher() {
    if (watcher != null) {
      watcher.close();
      watcher = null;
    }
  }
}
