package com.gimle.skald.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class ControlPlaneServicePollerTest {

  @Test
  void a_poll_populates_the_directory_from_the_catalog() {
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.put("orders", Optional.empty(), 8080, 8080, List.of(new HostPort("10.0.0.5", 8080)));
    client.put(
        "payments",
        Optional.of("acme"),
        9090,
        9090,
        List.of(new HostPort("10.0.0.6", 9090), new HostPort("10.0.0.7", 9090)));
    CachingServiceDirectory directory = new CachingServiceDirectory();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      int resolved = poller.poll();

      assertEquals(2, resolved);
      assertEquals(
          List.of(new HostPort("10.0.0.5", 8080)), directory.resolveAll("orders").orElseThrow());
      assertEquals(
          List.of(new HostPort("10.0.0.6", 9090), new HostPort("10.0.0.7", 9090)),
          directory.resolveAll("payments.acme").orElseThrow());
    } finally {
      poller.close();
    }
  }

  @Test
  void a_tenant_scoped_service_is_cached_under_its_qualified_name_not_its_bare_one() {
    // ADD-6: the control plane's own /services/{name}/endpoints path needs the bare name, but
    // the directory must be keyed by the qualified name a DNS query resolves to -- caching under
    // the bare name made every tenant-scoped Service unresolvable (NXDOMAIN) no matter how live
    // its endpoint was.
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.put(
        "web-ui",
        Optional.of("orders-platform"),
        80,
        8090,
        List.of(new HostPort("10.0.0.9", 8090)));
    CachingServiceDirectory directory = new CachingServiceDirectory();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      poller.poll();

      assertEquals(Optional.empty(), directory.resolveAll("web-ui"));
      assertEquals(
          List.of(new HostPort("10.0.0.9", 8090)),
          directory.resolveAll("web-ui.orders-platform").orElseThrow());
    } finally {
      poller.close();
    }
  }

  @Test
  void a_service_with_no_endpoints_stays_in_the_cache_as_a_known_empty_name() {
    // Dropping it here is what used to make a Service mid-rollout (or scaled to zero) answer
    // NXDOMAIN, indistinguishable from a name that was never declared at all.
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.put("orders", Optional.empty(), 8080, 8080, List.of());
    CachingServiceDirectory directory = new CachingServiceDirectory();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      int resolved = poller.poll();

      assertEquals(1, resolved);
      assertEquals(Optional.of(List.of()), directory.resolveAll("orders"));
    } finally {
      poller.close();
    }
  }

  @Test
  void a_service_that_vanished_between_the_listing_and_the_fetch_is_left_out_of_the_cache() {
    // An absent Optional from fetchEndpoints is the control plane's own 404: by the time the
    // per-service call ran that Service really was gone, so it must not linger as a known name.
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.put("orders", Optional.empty(), 8080, 8080, List.of(new HostPort("10.0.0.5", 8080)));
    client.listOnly("ghost", Optional.empty());
    CachingServiceDirectory directory = new CachingServiceDirectory();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      int resolved = poller.poll();

      assertEquals(1, resolved);
      assertEquals(Optional.empty(), directory.resolveAll("ghost"));
    } finally {
      poller.close();
    }
  }

  @Test
  void a_successful_poll_resets_the_failure_count_and_advances_last_success() {
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.put("orders", Optional.empty(), 8080, 8080, List.of(new HostPort("10.0.0.5", 8080)));
    CachingServiceDirectory directory = new CachingServiceDirectory();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      poller.poll();

      assertEquals(0, directory.consecutiveFailures());
      assertTrue(directory.timeSinceLastSuccess().compareTo(Duration.ofSeconds(5)) < 0);
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
    directory.replaceAll(Map.of("orders", List.of(new HostPort("10.0.0.5", 8080))));
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.alwaysFailListing();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      int resolved = poller.poll();

      assertEquals(0, resolved);
      assertEquals(
          List.of(new HostPort("10.0.0.5", 8080)),
          directory.resolveAll("orders").orElseThrow()); // still there
    } finally {
      poller.close();
    }
  }

  @Test
  void repeated_failures_accumulate_a_growing_consecutive_failure_count() {
    // Same permanent-failure setup as above, for the same race-avoidance reason -- see that test's
    // own comment.
    CachingServiceDirectory directory = new CachingServiceDirectory();
    FakeServiceCatalogClient client = new FakeServiceCatalogClient();
    client.alwaysFailListing();
    ControlPlaneServicePoller poller =
        new ControlPlaneServicePoller(client, directory, Duration.ofHours(1));
    try {
      poller.poll();
      poller.poll();
      poller.poll();

      assertTrue(directory.consecutiveFailures() >= 3);
    } finally {
      poller.close();
    }
  }

  /**
   * A plain in-memory {@link ServiceCatalogClient}, standing in for a real HTTP round trip in
   * tests.
   */
  private static final class FakeServiceCatalogClient implements ServiceCatalogClient {

    private final Map<String, ServiceListing> listingsByName = new LinkedHashMap<>();
    private final Map<String, ServiceEndpoints> byName = new LinkedHashMap<>();
    private volatile boolean alwaysFailListing;

    void put(
        String name,
        Optional<String> tenantId,
        int port,
        int targetPort,
        List<HostPort> endpoints) {
      listingsByName.put(name, new ServiceListing(name, tenantId));
      byName.put(name, new ServiceEndpoints(name, port, OptionalInt.of(targetPort), endpoints));
    }

    /** A Service present in the catalog listing whose per-service endpoint fetch answers 404. */
    void listOnly(String name, Optional<String> tenantId) {
      listingsByName.put(name, new ServiceListing(name, tenantId));
    }

    void alwaysFailListing() {
      this.alwaysFailListing = true;
    }

    @Override
    public List<ServiceListing> listServices() throws IOException {
      if (alwaysFailListing) {
        throw new IOException("simulated control-plane failure");
      }
      return List.copyOf(listingsByName.values());
    }

    @Override
    public Optional<ServiceEndpoints> fetchEndpoints(ServiceListing listing) {
      return Optional.ofNullable(byName.get(listing.name()));
    }
  }
}
