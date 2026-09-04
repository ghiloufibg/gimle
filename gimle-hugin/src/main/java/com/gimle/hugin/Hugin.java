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
import com.gimle.hugin.model.PermissionReader;
import com.gimle.hugin.model.PermissionSnapshot;
import com.gimle.hugin.model.PulseReader;
import com.gimle.hugin.model.PulseSnapshot;
import com.gimle.hugin.model.ResourceCatalog;
import com.gimle.hugin.model.ResourceKind;
import com.gimle.hugin.model.ResourceReader;
import com.gimle.hugin.model.ResourceRow;
import com.gimle.hugin.model.ResourceSnapshot;
import com.gimle.hugin.model.ServiceReader;
import com.gimle.hugin.model.ServiceSnapshot;
import com.gimle.hugin.model.SnapshotPoller;
import com.gimle.hugin.model.SnapshotReader;
import com.gimle.hugin.model.TraceReader;
import com.gimle.hugin.model.TraceSnapshot;
import com.gimle.hugin.model.VersionReader;
import com.gimle.hugin.model.VersionSnapshot;
import com.gimle.hugin.render.ActivityScreen;
import com.gimle.hugin.render.ClusterScreen;
import com.gimle.hugin.render.DescribeScreen;
import com.gimle.hugin.render.HelpOverlay;
import com.gimle.hugin.render.InstanceScreen;
import com.gimle.hugin.render.KindsScreen;
import com.gimle.hugin.render.NodeScreen;
import com.gimle.hugin.render.Painter;
import com.gimle.hugin.render.PermissionScreen;
import com.gimle.hugin.render.PulseScreen;
import com.gimle.hugin.render.ResourceScreen;
import com.gimle.hugin.render.ScanScreen;
import com.gimle.hugin.render.ServiceScreen;
import com.gimle.hugin.render.TraceScreen;
import com.gimle.hugin.render.VersionScreen;
import com.gimle.hugin.render.Viewport;
import com.gimle.hugin.render.XrayScreen;
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

  private ClusterReader reader;
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
  private final ResourceScreen resourceScreen;
  private final DescribeScreen describeScreen;
  private final KindsScreen kindsScreen;
  private final XrayScreen xrayScreen;
  private final ScanScreen scanScreen;
  private final PermissionScreen permissionScreen;
  private SnapshotPoller<PermissionSnapshot> permissionPoller;
  private final PulseScreen pulseScreen;
  private final TraceScreen traceScreen;
  private final VersionScreen versionScreen;
  private SnapshotPoller<VersionSnapshot> versionPoller;
  private SnapshotPoller<TraceSnapshot> tracePoller;
  private SnapshotPoller<PulseSnapshot> pulsePoller;
  private ResourceCatalog catalog;
  private SnapshotPoller<ResourceSnapshot> resourcePoller;
  private SnapshotPoller<ClusterSnapshot> clusterPoller;
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
    this.resourceScreen = new ResourceScreen(painter);
    this.describeScreen = new DescribeScreen(painter);
    this.kindsScreen = new KindsScreen(painter);
    this.xrayScreen = new XrayScreen(painter);
    this.scanScreen = new ScanScreen(painter);
    this.permissionScreen = new PermissionScreen(painter);
    this.pulseScreen = new PulseScreen(painter);
    this.traceScreen = new TraceScreen(painter);
    this.versionScreen = new VersionScreen(painter);
    this.helpOverlay = new HelpOverlay(painter);
  }

  public void run() {
    startClusterPoller();
    try {
      while (running) {
        // Re-read each frame rather than held once: `:ctx` replaces this poller mid-loop, and a
        // loop holding the old one would keep painting the cluster it was pointed away from.
        SnapshotPoller<ClusterSnapshot> poller = clusterPoller;
        ClusterSnapshot snapshot = poller.current();
        terminal.paint(frame(snapshot, poller.paused()));
        terminal.readKey(FRAME_TIMEOUT_MILLIS).ifPresent(key -> handle(key, snapshot, poller));
      }
    } finally {
      closeWatcher();
      closeServicePoller();
      closeActivityPoller();
      closeResourcePoller();
      closePulsePoller();
      closePermissionPoller();
      closeVersionPoller();
      closeTracePoller();
      closeClusterPoller();
    }
  }

  private void startClusterPoller() {
    closeClusterPoller();
    SnapshotReader snapshots = new SnapshotReader(reader);
    clusterPoller =
        new SnapshotPoller<>(
            snapshots::read,
            ClusterSnapshot.connecting(reader.serverAddress()),
            intervals.cluster(),
            "hugin-cluster");
    clusterPoller.start();
  }

  private void closeClusterPoller() {
    if (clusterPoller != null) {
      clusterPoller.close();
      clusterPoller = null;
    }
  }

  /**
   * Points the whole view at another control plane. Everything currently open is closed first --
   * every screen here is about one cluster, and a drill-down, a service table or a browsed kind
   * carried across would be describing the previous one under the new one's name. The catalog goes
   * with them: which kinds exist is that cluster's own answer.
   */
  private void switchServer(final String address) {
    String target = address.trim();
    if (target.isBlank()) {
      ui.failCommand("usage: :ctx <context-name or host:port>");
      return;
    }
    closeWatcher();
    closeServicePoller();
    closeActivityPoller();
    closeResourcePoller();
    closePulsePoller();
    closePermissionPoller();
    closeVersionPoller();
    ui.leaveEveryView();
    catalog = null;
    reader = reader.forContext(target);
    startClusterPoller();
  }

  private List<String> frame(final ClusterSnapshot full, final boolean paused) {
    Viewport viewport = terminal.viewport();
    Instant now = Instant.now();
    if (ui.helpVisible()) {
      return helpOverlay.render(viewport);
    }
    // Narrowed once here rather than at every screen: the scope is a property of what is being
    // looked at, not of any one view, and threading it through each render call would let one
    // screen quietly forget it.
    ClusterSnapshot snapshot = full.scopedTo(ui.tenantScope());
    if (ui.viewingScan()) {
      return scanScreen.render(snapshot, scannedServices(), ui, viewport, paused, now);
    }
    if (ui.viewingPermissions() && permissionPoller != null) {
      return permissionScreen.render(
          permissionPoller.current(), ui, viewport, permissionPoller.paused(), now);
    }
    if (ui.viewingPulse() && pulsePoller != null) {
      return pulseScreen.render(
          pulsePoller.current(), snapshot, ui, viewport, pulsePoller.paused(), now);
    }
    if (ui.viewingXray() && servicePoller != null) {
      return xrayScreen.render(
          servicePoller.current().scopedTo(ui.tenantScope()),
          snapshot,
          ui,
          viewport,
          servicePoller.paused(),
          now);
    }
    if (ui.viewingKinds()) {
      return kindsScreen.render(catalog(), reader.serverAddress(), viewport);
    }
    if (ui.viewingResources() && resourcePoller != null) {
      return resourceFrame(resourcePoller.current(), viewport, now);
    }
    if (ui.viewingActivity() && activityPoller != null) {
      return activityScreen.render(
          activityPoller.current(), ui, viewport, activityPoller.paused(), now);
    }
    if (ui.viewingServices() && servicePoller != null) {
      return serviceScreen.render(
          servicePoller.current().scopedTo(ui.tenantScope()),
          ui,
          viewport,
          servicePoller.paused(),
          now);
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
    if (inspected.isPresent() && ui.viewingTraces() && tracePoller != null) {
      return traceScreen.render(
          inspected.get(), tracePoller.current(), ui, viewport, tracePoller.paused(), now);
    }
    if (inspected.isPresent() && watcher != null) {
      return instanceScreen.render(inspected.get(), watcher, ui, viewport, paused, now);
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
    if (ui.commandEditing()) {
      handleCommandKey(key);
      return;
    }
    // Any key after a rejected command counts as having read why it was rejected.
    ui.clearCommandError();
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
    if (ui.viewingScan()) {
      handleScanKey(key);
      return;
    }
    if (ui.viewingPermissions()) {
      handlePermissionKey(key);
      return;
    }
    if (ui.viewingPulse()) {
      handlePulseKey(key);
      return;
    }
    if (ui.viewingXray()) {
      handleXrayKey(key);
      return;
    }
    if (ui.viewingKinds()) {
      handleKindsKey(key);
      return;
    }
    if (ui.viewingResources()) {
      handleResourceKey(key);
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
    } else if (key instanceof Key.Character digit && digit.value() >= '1' && digit.value() <= '9') {
      // A digit picks an ordering outright, on the same table `o` would have cycled.
      int position = digit.value() - '0';
      if (onNodes) {
        ui.sortNodesBy(position);
      } else {
        ui.sortBy(position);
      }
    } else if (key.isChar('a')) {
      openActivity();
    } else if (key.isChar('s')) {
      openServices();
    } else if (key.isChar('x')) {
      openXray();
    } else if (key.isChar('P')) {
      openPulse();
    } else if (key.isChar('S')) {
      openScan();
    } else if (key.isChar('R')) {
      openPermissions();
    } else if (key.isChar(':')) {
      ui.beginCommand();
    } else if (key.isChar('d') && !onNodes) {
      describeSelectedWorkload(rows);
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
    } else if (key.isChar(':')) {
      ui.beginCommand();
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
    if (ui.viewingTraces()) {
      handleTraceKey(key);
      return;
    }
    if (key.is(Key.Kind.ESCAPE)) {
      // A filter narrowing the tail is what esc undoes first; only an unfiltered pane closes on
      // it, so nobody loses the instance they were reading to clear a search.
      if (ui.filter().isBlank()) {
        closeInspection();
      } else {
        ui.clearFilter();
      }
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar('c')) {
      cycleLogCategory();
    } else if (key.isChar('w')) {
      ui.toggleLogWrap();
    } else if (key.isChar('t')) {
      ui.toggleLogTimestamps();
    } else if (key.isChar('T')) {
      openTraces();
    } else if (key.isChar('p')) {
      poller.togglePaused();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  /**
   * The {@code :} prompt: type a kind, press enter, and the browser opens on it. Nothing is
   * resolved until enter, so a half-typed kind never opens a screen and never costs a request.
   */
  private void handleCommandKey(final Key key) {
    if (key.is(Key.Kind.ENTER)) {
      // Enter on an empty prompt asks what there is rather than failing to name anything: it is
      // the question someone who does not know the kinds is actually asking.
      if (ui.command().isBlank()) {
        ui.showKinds();
      } else {
        runCommand(ui.command());
      }
    } else if (key.is(Key.Kind.ESCAPE)) {
      ui.cancelCommand();
    } else if (key.is(Key.Kind.BACKSPACE)) {
      ui.backspaceCommand();
    } else if (key instanceof Key.Character character && character.value() >= ' ') {
      ui.appendToCommand(character.value());
    }
  }

  /**
   * The prompt takes a kind name, or one verb that is not a kind. {@code ctx} is here rather than
   * on a key of its own because pointing at another cluster needs an argument, and this is the only
   * place in the view that takes one.
   */
  private void runCommand(final String typed) {
    String command = typed.trim();
    if (command.equals("ctx") || command.startsWith("ctx ")) {
      switchServer(command.substring("ctx".length()));
      return;
    }
    if (command.equals("tenant") || command.startsWith("tenant ")) {
      scopeToTenant(command.substring("tenant".length()).trim());
      return;
    }
    if (command.equals("scan")) {
      openScan();
      return;
    }
    if (command.equals("can")) {
      openPermissions();
      return;
    }
    openResources(command);
  }

  private void handleXrayKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      closeXray();
    } else if (key.is(Key.Kind.UP) || key.isChar('k')) {
      ui.scrollXray(-1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      ui.scrollXray(1);
    } else if (key.isChar('g')) {
      ui.scrollXrayToTop();
    } else if (key.isChar('G')) {
      ui.scrollXrayToBottom();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar(':')) {
      ui.beginCommand();
    } else if (key.isChar('p') && servicePoller != null) {
      servicePoller.togglePaused();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  private void handleScanKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      closeScan();
    } else if (key.is(Key.Kind.UP) || key.isChar('k')) {
      ui.scrollScan(-1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      ui.scrollScan(1);
    } else if (key.isChar('g')) {
      ui.scrollScanToTop();
    } else if (key.isChar('G')) {
      ui.scrollScanToBottom();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar(':')) {
      ui.beginCommand();
    } else if (key.isChar('p') && servicePoller != null) {
      servicePoller.togglePaused();
    } else if (key.isChar('r') && servicePoller != null) {
      servicePoller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  private void handlePermissionKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      closePermissions();
    } else if (key.is(Key.Kind.UP) || key.isChar('k')) {
      ui.scrollPermissions(-1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      ui.scrollPermissions(1);
    } else if (key.isChar('g')) {
      ui.scrollPermissionsToTop();
    } else if (key.isChar('G')) {
      ui.scrollPermissionsToBottom();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar(':')) {
      ui.beginCommand();
    } else if (key.isChar('r') && permissionPoller != null) {
      permissionPoller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  /**
   * The findings list. Every check is arithmetic over readings the view already takes, so the one
   * thing opening this costs is the Services poll the services screen also costs -- and the scan
   * says so itself when that read has not landed, rather than reporting a clean cluster it only
   * half looked at.
   */
  private void openScan() {
    leaveOpenScreen();
    ui.showScan();
    startServicePoller();
  }

  private void closeScan() {
    ui.closeScan();
    closeServicePoller();
  }

  /** Whatever the Services read currently holds, or nothing read at all, narrowed to the scope. */
  private ServiceSnapshot scannedServices() {
    return servicePoller == null
        ? ServiceSnapshot.connecting(reader.serverAddress())
        : servicePoller.current().scopedTo(ui.tenantScope());
  }

  /**
   * The permissions grid, asked once and then only when asked again: it is one request per cell of
   * a grid the size of the cluster's whole resource vocabulary, and the answer does not change
   * between two keystrokes -- a grant arrives by someone editing a Role, which is not a thing that
   * happens while you are reading the screen about it.
   */
  private void openPermissions() {
    leaveOpenScreen();
    ui.showPermissions();
    closePermissionPoller();
    permissionPoller =
        SnapshotPoller.onDemand(
            new PermissionReader(reader, ui.tenantScope())::read,
            PermissionSnapshot.connecting(reader.serverAddress()),
            "hugin-permissions");
    permissionPoller.start();
  }

  private void closePermissions() {
    ui.closePermissions();
    closePermissionPoller();
  }

  /**
   * Leaves whichever full-screen view is open before another is opened over it. Each of these
   * screens is the whole terminal, so two open at once is not a layering question but a bug: one of
   * them is drawn and the other is a poll nobody is reading. Reached from every opener rather than
   * left to each to remember, since the one that forgets is the one that leaks.
   */
  private void leaveOpenScreen() {
    if (ui.viewingScan()) {
      closeScan();
    }
    if (ui.viewingPermissions()) {
      closePermissions();
    }
    if (ui.viewingXray()) {
      closeXray();
    }
    if (ui.viewingPulse()) {
      closePulse();
    }
    if (ui.viewingServices()) {
      closeServices();
    }
    if (ui.viewingActivity()) {
      closeActivity();
    }
    if (ui.viewingResources()) {
      closeResources();
    }
    ui.closeKinds();
  }

  private void closePermissionPoller() {
    if (permissionPoller != null) {
      permissionPoller.close();
      permissionPoller = null;
    }
  }

  private void handlePulseKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      closePulse();
    } else if (key.isChar(':')) {
      ui.beginCommand();
    } else if (key.isChar('p') && pulsePoller != null) {
      pulsePoller.togglePaused();
    } else if (key.isChar('r') && pulsePoller != null) {
      pulsePoller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  /**
   * The tree is a join of two readings rather than a read of its own, so it costs exactly what the
   * services screen costs -- one request per declared Service -- and nothing beyond it.
   */
  private void openXray() {
    leaveOpenScreen();
    ui.showXray();
    startServicePoller();
  }

  private void closeXray() {
    ui.closeXray();
    closeServicePoller();
  }

  /** Two small reads on the slower interval: neither changes faster than a person can read it. */
  private void openPulse() {
    leaveOpenScreen();
    ui.showPulse();
    closePulsePoller();
    pulsePoller =
        new SnapshotPoller<>(
            new PulseReader(reader)::read,
            PulseSnapshot.connecting(reader.serverAddress()),
            intervals.services(),
            "hugin-pulse");
    pulsePoller.start();
  }

  private void closePulse() {
    ui.closePulse();
    closePulsePoller();
  }

  private void closePulsePoller() {
    if (pulsePoller != null) {
      pulsePoller.close();
      pulsePoller = null;
    }
  }

  /**
   * Narrows every screen to one tenant, or back to the whole cluster with {@code all}. The scope is
   * not checked against the tenants that exist: a name matching nothing shows nothing, which is the
   * honest answer for a tenant this certificate cannot see and one that was mistyped alike -- and
   * the bar says which tenant is in scope, so an empty screen is never a mystery.
   */
  private void scopeToTenant(final String tenantId) {
    if (tenantId.isBlank()) {
      ui.failCommand("usage: :tenant <id>, or :tenant all for the whole cluster");
      return;
    }
    if (tenantId.equals("all")) {
      ui.clearTenantScope();
    } else {
      ui.scopeToTenant(tenantId);
    }
    reopenTenantScopedKind();
    // The grid was asked under the old scope and the control plane answers a different one per
    // tenant, so it is re-asked rather than left showing answers to a question no longer being put.
    if (ui.viewingPermissions()) {
      openPermissions();
    }
  }

  /**
   * A tenant-scoped kind is a reading of one tenant, so changing which tenant is in scope makes the
   * open table a reading of the wrong one. It is re-read under the new scope, or closed outright
   * when the scope is dropped, rather than left showing rows the bar now labels as another
   * tenant's.
   */
  private void reopenTenantScopedKind() {
    if (resourcePoller == null || !ui.viewingResources()) {
      return;
    }
    ResourceKind kind = resourcePoller.current().kind();
    if (!kind.tenantScoped()) {
      return;
    }
    if (ui.tenantScope().isEmpty()) {
      closeResources();
      return;
    }
    ui.showResources();
    startResourcePoller(kind);
  }

  private void handleKindsKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      ui.closeKinds();
    } else if (key.isChar(':')) {
      ui.beginCommand();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  private void handleResourceKey(final Key key) {
    if (ui.viewingVersions()) {
      handleVersionKey(key);
      return;
    }
    if (ui.describing().isPresent()) {
      handleDescribeKey(key);
      return;
    }
    List<ResourceRow> rows = resourceRows();
    if (key.is(Key.Kind.ESCAPE)) {
      closeResources();
    } else if (key.is(Key.Kind.UP) || key.isChar('k')) {
      ui.moveResourceSelection(rows, -1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      ui.moveResourceSelection(rows, 1);
    } else if (key.isChar('g')) {
      ui.selectFirstResource(rows);
    } else if (key.isChar('G')) {
      ui.selectLastResource(rows);
    } else if (key.is(Key.Kind.ENTER)) {
      ui.describeSelected(rows);
    } else if (key.isChar('v')) {
      openVersions(rows);
    } else if (key.isChar(':')) {
      ui.beginCommand();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar('p') && resourcePoller != null) {
      resourcePoller.togglePaused();
    } else if (key.isChar('r') && resourcePoller != null) {
      resourcePoller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  private void handleVersionKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      closeVersions();
    } else if (key.is(Key.Kind.UP) || key.isChar('k')) {
      ui.scrollVersions(-1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      ui.scrollVersions(1);
    } else if (key.isChar('g')) {
      ui.scrollVersionsToTop();
    } else if (key.isChar('G')) {
      ui.scrollVersionsToBottom();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar('r') && versionPoller != null) {
      versionPoller.refreshNow();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  /**
   * The revision ledger of whatever the browser has selected. Only the four tenant-scoped kinds
   * keep one, so anything else is answered with that rather than with an empty pane -- "no history"
   * and "no history kept" are different answers and only one of them is about this resource.
   */
  private void openVersions(final List<ResourceRow> rows) {
    if (resourcePoller == null) {
      return;
    }
    ResourceKind kind = resourcePoller.current().kind();
    int index = ui.resourceSelectionIndex(rows);
    if (index < 0) {
      return;
    }
    if (!VersionReader.supports(kind)) {
      ui.failCommand("nothing keeps a revision history of a " + kind.label());
      return;
    }
    String tenantId = ui.tenantScope().orElse("");
    String name = rows.get(index).name();
    ui.showVersions();
    closeVersionPoller();
    versionPoller =
        new SnapshotPoller<>(
            new VersionReader(reader, kind, tenantId, name)::read,
            VersionSnapshot.connecting(reader.serverAddress(), tenantId + "/" + name),
            intervals.services(),
            "hugin-versions");
    versionPoller.start();
  }

  private void closeVersions() {
    ui.closeVersions();
    closeVersionPoller();
  }

  private void closeVersionPoller() {
    if (versionPoller != null) {
      versionPoller.close();
      versionPoller = null;
    }
  }

  private void handleDescribeKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      ui.closeDescribe();
    } else if (key.is(Key.Kind.UP) || key.isChar('k')) {
      ui.scrollDescribe(-1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      ui.scrollDescribe(1);
    } else if (key.isChar('g')) {
      ui.scrollDescribeToTop();
    } else if (key.isChar('G')) {
      ui.scrollDescribeToBottom();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  /**
   * The browser and the describe pane over it. The pane is drawn from the row the current poll
   * carries rather than one captured when it was opened, so a resource that changes underneath
   * shows its change; one that leaves the collection entirely drops back to the table, the same way
   * an inspected instance that leaves the cluster does.
   */
  private List<String> resourceFrame(
      final ResourceSnapshot snapshot, final Viewport viewport, final Instant now) {
    if (ui.viewingVersions() && versionPoller != null) {
      return versionScreen.render(
          versionPoller.current(), ui, viewport, versionPoller.paused(), now);
    }
    Optional<ResourceRow> described = ui.describing().flatMap(snapshot::find);
    if (described.isPresent()) {
      return describeScreen.render(snapshot.kind(), described.get(), ui, viewport);
    }
    // Only a read that actually landed can say a resource is gone. Before the first one the
    // collection is empty because nothing has been asked yet, which is not the same thing -- and
    // `d` names its resource before any read at all.
    if (ui.describing().isPresent() && snapshot.fetchedAt().isPresent()) {
      ui.closeDescribe();
    }
    return resourceScreen.render(
        snapshot.scopedTo(ui.tenantScope()), ui, viewport, resourcePoller.paused(), now);
  }

  private List<ResourceRow> resourceRows() {
    return resourcePoller == null ? List.of() : resourcePoller.current().matching(ui.filter());
  }

  /**
   * Opens the browser on the kind {@code typed} names. The catalog is discovered once per session
   * and kept: it costs a read of the cluster's registered kinds, and those do not change between
   * two presses of {@code :}.
   *
   * <p>Like the services and activity screens, the poll lives only as long as the screen showing it
   * -- and here it is also the only reason any of these routes is called at all, several of which a
   * given certificate may not be permitted to read.
   */
  private void openResources(final String typed) {
    ResourceCatalog catalog = catalog();
    Optional<ResourceKind> kind = catalog.resolve(typed);
    if (kind.isEmpty()) {
      ui.failCommand(
          "no kind named '"
              + typed.trim()
              + "'; try "
              + String.join(", ", catalog.suggestionsFor(typed)));
      return;
    }
    // A tenant's own holdings have no cluster-wide route, so without a tenant there is no request
    // to make. Said outright rather than opened onto an empty table, which would read as this
    // tenant holding none of them.
    if (kind.get().tenantScoped() && ui.tenantScope().isEmpty()) {
      ui.failCommand("a " + kind.get().label() + " listing is per tenant; run :tenant <id> first");
      return;
    }
    leaveOpenScreen();
    ui.showResources();
    startResourcePoller(kind.get());
  }

  /**
   * Opens the describe pane on the workload behind the selected instance row: the same browser and
   * the same pane {@code :} reaches, addressed by name rather than by cursor. Written this way
   * rather than as a single-object fetch of its own so there is one path to a described resource,
   * which is also what gives this one the browser's own behaviour when the workload disappears.
   */
  private void describeSelectedWorkload(final List<InstanceRow> rows) {
    int index = ui.selectionIndex(rows);
    if (index < 0) {
      return;
    }
    InstanceRow row = rows.get(index);
    Optional<ResourceKind> kind = catalog().forRoute(row.kind().route());
    if (kind.isEmpty()) {
      ui.failCommand("nothing browsable describes a " + row.kind().label());
      return;
    }
    leaveOpenScreen();
    ui.showResources();
    ui.describe(row.key().deploymentName());
    startResourcePoller(kind.get());
  }

  /** Discovered once per session: a cluster's registered kinds do not change between two keys. */
  private ResourceCatalog catalog() {
    if (catalog == null) {
      catalog = ResourceCatalog.discover(reader);
    }
    return catalog;
  }

  private void startResourcePoller(final ResourceKind kind) {
    closeResourcePoller();
    resourcePoller =
        new SnapshotPoller<>(
            new ResourceReader(reader, kind, ui.tenantScope())::read,
            ResourceSnapshot.connecting(reader.serverAddress(), kind),
            intervals.services(),
            "hugin-resources");
    resourcePoller.start();
  }

  private void closeResources() {
    ui.closeResources();
    closeResourcePoller();
    closeVersionPoller();
  }

  private void closeResourcePoller() {
    if (resourcePoller != null) {
      resourcePoller.close();
      resourcePoller = null;
    }
  }

  private void handleTraceKey(final Key key) {
    if (key.is(Key.Kind.ESCAPE)) {
      closeTraces();
    } else if (key.is(Key.Kind.UP) || key.isChar('k')) {
      ui.scrollTraces(-1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      ui.scrollTraces(1);
    } else if (key.isChar('g')) {
      ui.scrollTracesToTop();
    } else if (key.isChar('G')) {
      ui.scrollTracesToBottom();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar('p') && tracePoller != null) {
      tracePoller.togglePaused();
    } else if (key.isChar('?')) {
      ui.toggleHelp();
    } else if (key.isChar('q')) {
      running = false;
    }
  }

  /**
   * A worker's shipped spans, addressed by the node and worker the inspected instance names. An
   * instance the agent has not reported a worker for has no history to address at all, so the pane
   * does not open rather than opening on a guess.
   */
  private void openTraces() {
    Optional<InstanceRow> inspected =
        ui.inspecting().flatMap(key -> clusterPoller.current().find(key));
    Optional<String> workerId = inspected.flatMap(InstanceRow::workerId);
    if (inspected.isEmpty() || workerId.isEmpty()) {
      ui.failCommand("this instance reports no worker yet, so it has no traces to read");
      return;
    }
    ui.showTraces();
    closeTracePoller();
    tracePoller =
        new SnapshotPoller<>(
            new TraceReader(reader, inspected.get().nodeId(), workerId.get())::read,
            TraceSnapshot.connecting(reader.serverAddress()),
            intervals.services(),
            "hugin-traces");
    tracePoller.start();
  }

  private void closeTraces() {
    ui.closeTraces();
    closeTracePoller();
  }

  private void closeTracePoller() {
    if (tracePoller != null) {
      tracePoller.close();
      tracePoller = null;
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
    } else if (key.isChar(':')) {
      ui.beginCommand();
    } else if (key.isChar('/')) {
      ui.beginFilter();
    } else if (key.isChar('c')) {
      cycleActivityFeed();
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
   * Like the services screen, this polls only while it is open: these are reads an operator opens
   * deliberately, and one nobody is looking at is a request per interval for a permission not every
   * caller even has -- the alert feed additionally costing one request per declared rule.
   */
  private void openActivity() {
    leaveOpenScreen();
    ui.showActivity();
    startActivityPoller();
  }

  /** Switching feed replaces the poller: each reads a different route and pages independently. */
  private void cycleActivityFeed() {
    ui.cycleFeedMode();
    closeActivityPoller();
    startActivityPoller();
  }

  private void startActivityPoller() {
    activityReader = new ActivityReader(reader, ui.feedMode());
    activityPoller =
        new SnapshotPoller<>(
            activityReader::read,
            ActivitySnapshot.connecting(reader.serverAddress(), ui.feedMode()),
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
    leaveOpenScreen();
    ui.showServices();
    startServicePoller();
  }

  private void startServicePoller() {
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
