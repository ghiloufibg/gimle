package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The same failure posture the cluster poll has, on the Service table: a read that fails leaves the
 * last good rows up and says why they are old, rather than emptying a table whose emptiness would
 * itself read as a finding.
 */
class ServiceSnapshotPollerTest {

  @Test
  void a_failed_poll_keeps_the_last_good_rows_and_says_why_they_are_stale() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/services", List.of(service("greeter")))
            .withObject("/services/greeter/endpoints", Map.of("endpoints", List.of()));
    SnapshotPoller<ServiceSnapshot> poller = poller(reader);
    poller.pollOnce();
    assertTrue(poller.current().connected());
    assertEquals(1, poller.current().unresolvedCount());

    reader.failWith(new CliException("could not reach control plane at http://localhost:8080"));
    poller.pollOnce();

    ServiceSnapshot stale = poller.current();
    assertFalse(stale.connected());
    assertEquals(1, stale.services().size());
    assertTrue(stale.staleReason().orElseThrow().contains("could not reach control plane"));
    assertTrue(stale.fetchedAt().isPresent(), "the age of the last good data must survive");
  }

  @Test
  void before_the_first_poll_succeeds_the_snapshot_reads_as_connecting_with_no_age() {
    ServiceSnapshot connecting = poller(new FakeClusterReader()).current();

    assertFalse(connecting.connected());
    assertEquals(Optional.of("connecting"), connecting.staleReason());
    assertEquals(Optional.empty(), connecting.fetchedAt());
    assertEquals(List.of(), connecting.services());
  }

  @Test
  void a_paused_poller_stops_reading_until_it_is_resumed() {
    SnapshotPoller<ServiceSnapshot> poller = poller(new FakeClusterReader());

    poller.togglePaused();
    assertTrue(poller.paused());

    poller.togglePaused();
    assertFalse(poller.paused());
  }

  private static SnapshotPoller<ServiceSnapshot> poller(final FakeClusterReader reader) {
    return new SnapshotPoller<>(
        new ServiceReader(reader)::read,
        ServiceSnapshot.connecting(reader.serverAddress()),
        Duration.ofSeconds(2),
        "test-services");
  }

  private static Map<String, Object> service(final String name) {
    Map<String, Object> service = new LinkedHashMap<>();
    service.put("name", name);
    service.put("deploymentNames", List.of(name + "-provider"));
    service.put("port", 8080);
    service.put("sessionAffinity", false);
    service.put("protocol", "TCP");
    return service;
  }
}
