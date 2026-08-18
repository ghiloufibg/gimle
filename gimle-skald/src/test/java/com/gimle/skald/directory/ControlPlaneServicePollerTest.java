package com.gimle.skald.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ControlPlaneServicePollerTest {

  @Test
  void a_poll_populates_the_directory_from_the_catalog() {
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.put("orders", 8080, 8080, List.of("10.0.0.5"));
    client.put("payments.acme", 9090, 9090, List.of("10.0.0.6", "10.0.0.7"));
    CachingServiceDirectory directory = new CachingServiceDirectory();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      int resolved = poller.poll();

      assertEquals(2, resolved);
      assertEquals(Optional.of("10.0.0.5"), directory.resolveOne("orders"));
      assertEquals(Optional.of("10.0.0.6"), directory.resolveOne("payments.acme"));
    } finally {
      poller.close();
    }
  }

  @Test
  void a_service_with_no_endpoints_is_left_out_of_the_cache() {
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.put("orders", 8080, 8080, List.of());
    CachingServiceDirectory directory = new CachingServiceDirectory();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      poller.poll();
      assertTrue(directory.resolveOne("orders").isEmpty());
    } finally {
      poller.close();
    }
  }

  @Test
  void a_failing_listing_leaves_a_previously_seeded_cache_in_place() {
    // The directory is seeded directly (not via a successful poll) and the client is set to fail
    // permanently, not just once -- the poller constructor schedules its own initial poll
    // immediately (delay 0), which would otherwise race against this test's own explicit poll()
    // call on a one-shot failure flag. A permanent failure makes every invocation, background or
    // explicit, converge on the same outcome, so the race is harmless (the same posture {@code
    // AndvariPeerSyncTest} already takes toward its own scheduler's initial tick).
    CachingServiceDirectory directory = new CachingServiceDirectory();
    directory.replaceAll(Map.of("orders", List.of("10.0.0.5")));
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.alwaysFailListing();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      int resolved = poller.poll();

      assertEquals(0, resolved);
      assertEquals(Optional.of("10.0.0.5"), directory.resolveOne("orders")); // still there
    } finally {
      poller.close();
    }
  }

  /**
   * A plain in-memory {@link ServiceCatalogClient}, standing in for a real HTTP round trip in
   * tests.
   */
  private static final class FakeServiceCatalogClient implements ServiceCatalogClient {

    private final Map<String, ServiceEndpoints> byName = new LinkedHashMap<>();
    private volatile boolean alwaysFailListing;

    void put(String name, int port, int targetPort, List<String> endpointHosts) {
      byName.put(name, new ServiceEndpoints(name, port, targetPort, endpointHosts));
    }

    void alwaysFailListing() {
      this.alwaysFailListing = true;
    }

    @Override
    public List<String> listServiceNames() throws IOException {
      if (alwaysFailListing) {
        throw new IOException("simulated control-plane failure");
      }
      return List.copyOf(byName.keySet());
    }

    @Override
    public Optional<ServiceEndpoints> fetchEndpoints(String serviceName) {
      return Optional.ofNullable(byName.get(serviceName));
    }
  }
}
