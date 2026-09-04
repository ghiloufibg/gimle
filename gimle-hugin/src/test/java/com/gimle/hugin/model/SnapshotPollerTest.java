package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The failure posture that matters most while watching a cluster settle: the screen has to keep
 * showing what it last knew rather than going blank the moment a request fails.
 */
class SnapshotPollerTest {

  @Test
  void a_failed_poll_keeps_the_last_good_rows_and_says_why_they_are_stale() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/nodes", List.of(Fixtures.node("node-alpha", 0, 4000, 0, 8)))
            .withList(
                "/deployments",
                List.of(
                    Fixtures.deployment(
                        "greeter-provider",
                        Optional.empty(),
                        List.of(Fixtures.instance(0, "node-alpha", "ACTIVE", 1.0, 0.0)))));
    SnapshotPoller<ClusterSnapshot> poller = poller(reader);
    poller.pollOnce();
    assertTrue(poller.current().connected());

    reader.failWith(new CliException("could not reach control plane at http://localhost:8080"));
    poller.pollOnce();

    ClusterSnapshot stale = poller.current();
    assertFalse(stale.connected());
    assertEquals(1, stale.nodes().size());
    assertEquals(1, stale.instances().size());
    assertTrue(stale.staleReason().orElseThrow().contains("could not reach control plane"));
    assertTrue(stale.fetchedAt().isPresent(), "the age of the last good data must survive");
  }

  @Test
  void a_recovered_poll_replaces_the_stale_marking() {
    FakeClusterReader reader = new FakeClusterReader();
    SnapshotPoller<ClusterSnapshot> poller = poller(reader);
    reader.failWith(new CliException("connection refused"));
    poller.pollOnce();
    assertFalse(poller.current().connected());

    reader.failWith(null);
    poller.pollOnce();

    assertTrue(poller.current().connected());
    assertEquals(Optional.empty(), poller.current().staleReason());
  }

  @Test
  void before_the_first_poll_succeeds_the_snapshot_reads_as_connecting_with_no_age() {
    ClusterSnapshot connecting = poller(new FakeClusterReader()).current();

    assertFalse(connecting.connected());
    assertEquals(Optional.of("connecting"), connecting.staleReason());
    assertEquals(Optional.empty(), connecting.fetchedAt());
  }

  @Test
  void a_paused_poller_stops_reading_until_it_is_resumed() {
    FakeClusterReader reader = new FakeClusterReader();
    SnapshotPoller<ClusterSnapshot> poller = poller(reader);

    poller.togglePaused();

    assertTrue(poller.paused());
    // Toggling back both un-pauses and asks for an immediate read, so resuming never leaves an
    // operator waiting out the rest of an interval for data they just asked to see again.
    poller.togglePaused();
    assertFalse(poller.paused());
  }

  private static SnapshotPoller<ClusterSnapshot> poller(final FakeClusterReader reader) {
    SnapshotReader snapshots = new SnapshotReader(reader);
    return new SnapshotPoller<>(
        snapshots::read,
        ClusterSnapshot.connecting(reader.serverAddress()),
        Duration.ofSeconds(2),
        "test-cluster");
  }
}
