package com.gimle.hugin;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.hugin.model.ClusterPoller;
import com.gimle.hugin.model.ClusterSnapshot;
import com.gimle.hugin.model.InstanceKey;
import com.gimle.hugin.model.InstanceRow;
import com.gimle.hugin.model.InstanceWatcher;
import com.gimle.hugin.model.LogCategory;
import com.gimle.hugin.model.SnapshotReader;
import com.gimle.hugin.render.ClusterScreen;
import com.gimle.hugin.render.HelpOverlay;
import com.gimle.hugin.render.InstanceScreen;
import com.gimle.hugin.render.Painter;
import com.gimle.hugin.render.Viewport;
import com.gimle.hugin.term.Key;
import com.gimle.hugin.term.TerminalSession;
import java.time.Duration;
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
  private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(2);

  /**
   * How long a frame waits for a key before repainting anyway. Short enough that a live log tail
   * and an ageing status line both look continuous, long enough to be nearly free.
   */
  private static final int FRAME_TIMEOUT_MILLIS = 200;

  private final ClusterReader reader;
  private final TerminalSession terminal;
  private final ClusterScreen clusterScreen;
  private final InstanceScreen instanceScreen;
  private final HelpOverlay helpOverlay;
  private final UiState ui = new UiState();

  private LogCategory logCategory = LogCategory.APPLICATION;
  private InstanceWatcher watcher;
  private boolean running = true;

  public Hugin(final ClusterReader reader, final TerminalSession terminal, final Painter painter) {
    this.reader = reader;
    this.terminal = terminal;
    this.clusterScreen = new ClusterScreen(painter);
    this.instanceScreen = new InstanceScreen(painter);
    this.helpOverlay = new HelpOverlay(painter);
  }

  public void run() {
    try (ClusterPoller poller =
        new ClusterPoller(new SnapshotReader(reader), REFRESH_INTERVAL, reader.serverAddress())) {
      poller.start();
      while (running) {
        ClusterSnapshot snapshot = poller.current();
        terminal.paint(frame(snapshot, poller.paused()));
        terminal.readKey(FRAME_TIMEOUT_MILLIS).ifPresent(key -> handle(key, snapshot, poller));
      }
    } finally {
      closeWatcher();
    }
  }

  private List<String> frame(final ClusterSnapshot snapshot, final boolean paused) {
    Viewport viewport = terminal.viewport();
    Instant now = Instant.now();
    if (ui.helpVisible()) {
      return helpOverlay.render(viewport);
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

  private void handle(final Key key, final ClusterSnapshot snapshot, final ClusterPoller poller) {
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
    if (ui.inspectingInstance()) {
      handleInstanceKey(key, poller);
      return;
    }
    handleClusterKey(key, snapshot, poller);
  }

  private void handleClusterKey(
      final Key key, final ClusterSnapshot snapshot, final ClusterPoller poller) {
    List<InstanceRow> rows = snapshot.instancesMatching(ui.filter());
    if (key.is(Key.Kind.UP) || key.isChar('k')) {
      ui.moveSelection(rows, -1);
    } else if (key.is(Key.Kind.DOWN) || key.isChar('j')) {
      ui.moveSelection(rows, 1);
    } else if (key.isChar('g')) {
      ui.selectFirst(rows);
    } else if (key.isChar('G')) {
      ui.selectLast(rows);
    } else if (key.is(Key.Kind.ENTER)) {
      openInspection(rows);
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

  private void handleInstanceKey(final Key key, final ClusterPoller poller) {
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

  private void openInspection(final List<InstanceRow> rows) {
    ui.inspectSelected(rows);
    ui.inspecting().ifPresent(this::watch);
  }

  private void closeInspection() {
    ui.closeInspection();
    closeWatcher();
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
