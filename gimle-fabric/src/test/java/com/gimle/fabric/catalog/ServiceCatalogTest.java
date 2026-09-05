package com.gimle.fabric.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.fabric.cluster.MemberId;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceCatalogTest {

  private static final ServiceExport GREETER =
      new ServiceExport("com.gimle.example.Greeter", Version.parse("1.0.0"));
  private static final ModuleInstanceId MODULE =
      ModuleInstanceId.unattached(new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")));

  private static MemberId node(String nodeId) {
    return new MemberId(nodeId, new InetSocketAddress("127.0.0.1", 7946));
  }

  @Test
  void a_local_registration_is_immediately_visible() {
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        node("node-a"),
        "worker-1",
        MODULE,
        GREETER,
        Optional.of("/tmp/worker-1.sock"),
        new InetSocketAddress("127.0.0.1", 9000));

    List<ServiceEndpoint> endpoints = catalog.endpointsFor(GREETER);
    assertEquals(1, endpoints.size());
    assertEquals("worker-1", endpoints.get(0).workerId());
    assertTrue(endpoints.get(0).ready());
  }

  @Test
  void unregister_removes_the_endpoint() {
    ServiceCatalog catalog = new ServiceCatalog();
    MemberId self = node("node-a");
    catalog.localRegister(
        self,
        "worker-1",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));
    catalog.localUnregister(self, "worker-1", MODULE, GREETER);

    assertTrue(catalog.endpointsFor(GREETER).isEmpty());
    assertFalse(catalog.hasAnyKnownExporter(GREETER));
  }

  @Test
  void gossip_deltas_round_trip_and_merge_into_a_second_catalog() {
    ServiceCatalog origin = new ServiceCatalog();
    origin.localRegister(
        node("node-a"),
        "worker-1",
        MODULE,
        GREETER,
        Optional.of("/tmp/worker-1.sock"),
        new InetSocketAddress("127.0.0.1", 9000));

    ServiceCatalog remote = new ServiceCatalog();
    remote.onReceived(origin.currentPayload());

    List<ServiceEndpoint> endpoints = remote.endpointsFor(GREETER);
    assertEquals(1, endpoints.size());
    assertEquals("node-a", endpoints.get(0).node().nodeId());
  }

  @Test
  void a_stale_delta_at_a_lower_version_is_ignored() {
    ServiceCatalog catalog = new ServiceCatalog();
    MemberId self = node("node-a");
    catalog.localRegister(
        self,
        "worker-1",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));
    catalog.localUnregister(self, "worker-1", MODULE, GREETER);
    assertTrue(catalog.endpointsFor(GREETER).isEmpty());

    // A stale (lower-version) re-registration delta arriving late over gossip must not resurrect
    // an entry that was already tombstoned by a newer delta. Package-private access lets this
    // test forge a delta at an explicit version, since ServiceCatalog's public API only ever
    // hands out its own next version.
    CatalogDelta staleRegister =
        new CatalogDelta(
            GREETER,
            self.nodeId(),
            "worker-1",
            MODULE,
            1L,
            true,
            self,
            Optional.empty(),
            new InetSocketAddress("127.0.0.1", 9000));
    catalog.onReceived(ServiceCatalogCodec.encode(List.of(staleRegister)));

    assertTrue(catalog.endpointsFor(GREETER).isEmpty());
  }

  @Test
  void evict_worker_removes_only_that_workers_entries_immediately() {
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        node("node-a"),
        "worker-1",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));
    catalog.localRegister(
        node("node-a"),
        "worker-2",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9001));

    // Simulates a supervising agent noticing worker-1's process has crashed, with no graceful
    // ServiceUnregistered ever sent and no SWIM DEAD verdict on node-a itself (the node, and
    // worker-2 on it, are both still alive) -- the case unregister/onMembershipChange don't cover.
    catalog.evictWorker("node-a", "worker-1");

    List<ServiceEndpoint> endpoints = catalog.endpointsFor(GREETER);
    assertEquals(1, endpoints.size());
    assertEquals("worker-2", endpoints.get(0).workerId());
  }

  @Test
  void evict_worker_is_a_no_op_for_a_worker_with_no_known_entries() {
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        node("node-a"),
        "worker-1",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));

    catalog.evictWorker("node-a", "worker-unknown");

    assertEquals(1, catalog.endpointsFor(GREETER).size());
  }

  @Test
  void two_different_workers_can_both_export_the_same_interface() {
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        node("node-a"),
        "worker-1",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));
    catalog.localRegister(
        node("node-a"),
        "worker-2",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9001));

    assertEquals(2, catalog.endpointsFor(GREETER).size());
  }

  /**
   * A redeploy of the same logical service (dispose the old instance, register its replacement)
   * must leave the disposed instance's endpoint gone for good -- not merely present until some
   * caller's circuit breaker happens to notice repeated failures against it.
   */
  @Test
  void a_redeploy_leaves_only_the_replacement_instances_endpoint() {
    ServiceCatalog catalog = new ServiceCatalog();
    MemberId node = node("node-a");
    ModuleInstanceId oldModule =
        ModuleInstanceId.unattached(
            new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")));
    ModuleInstanceId newModule =
        ModuleInstanceId.unattached(
            new ModuleId("com.gimle.example.orders", Version.parse("1.0.1")));

    catalog.localRegister(
        node,
        "worker-old",
        oldModule,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));
    // The old instance is disposed before the replacement registers, the same order a real
    // agent-driven rolling update produces.
    catalog.localUnregister(node, "worker-old", oldModule, GREETER);
    catalog.localRegister(
        node,
        "worker-new",
        newModule,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9001));

    List<ServiceEndpoint> endpoints = catalog.endpointsFor(GREETER);
    assertEquals(1, endpoints.size());
    assertEquals("worker-new", endpoints.get(0).workerId());
  }

  /**
   * Four successive redeploys of the same logical service must never accumulate stale candidates --
   * each generation's disposal must fully remove the previous one's endpoint, the exact "juggling
   * four stale candidate endpoints for one logical service" shape a registry that never actively
   * prunes would exhibit.
   */
  @Test
  void repeated_redeploys_never_accumulate_stale_endpoints() {
    ServiceCatalog catalog = new ServiceCatalog();
    MemberId node = node("node-a");
    String priorWorkerId = null;
    for (int generation = 0; generation < 4; generation++) {
      String workerId = "worker-gen" + generation;
      ModuleInstanceId owner =
          ModuleInstanceId.unattached(
              new ModuleId("com.gimle.example.orders", Version.parse("1.0." + generation)));
      if (priorWorkerId != null) {
        ModuleInstanceId priorOwner =
            ModuleInstanceId.unattached(
                new ModuleId("com.gimle.example.orders", Version.parse("1.0." + (generation - 1))));
        catalog.localUnregister(node, priorWorkerId, priorOwner, GREETER);
      }
      catalog.localRegister(
          node,
          workerId,
          owner,
          GREETER,
          Optional.empty(),
          new InetSocketAddress("127.0.0.1", 9100 + generation));
      priorWorkerId = workerId;

      List<ServiceEndpoint> endpoints = catalog.endpointsFor(GREETER);
      assertEquals(
          1, endpoints.size(), "generation " + generation + " left stale endpoints: " + endpoints);
      assertEquals(workerId, endpoints.get(0).workerId());
    }
  }

  /**
   * Registers one entry, then enough further entries that the first falls out of {@code
   * currentPayload()}'s bounded top-8 recent-delta window -- the exact scenario a partitioned or
   * slow-to-sync node hits under real gossip. The bounded piggyback payload alone can never recover
   * from this (it always reflects only the 8 most-recently-changed entries, however many rounds
   * elapse); only {@code currentFullStatePayload()}, the catalog's own anti-entropy backstop, still
   * carries the fallen-out entry.
   */
  @Test
  void anti_entropy_full_state_payload_recovers_an_entry_that_fell_out_of_the_piggyback_window() {
    ServiceCatalog origin = new ServiceCatalog();
    MemberId node = node("node-a");
    ServiceExport early = new ServiceExport("com.gimle.example.Early", Version.parse("1.0.0"));
    origin.localRegister(
        node,
        "worker-early",
        MODULE,
        early,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));
    churnRegistrations(origin, node, 10);

    ServiceCatalog remote = new ServiceCatalog();
    // Simulates a node that only ever saw the bounded piggyback payload, arriving after "early"
    // had already been pushed out of the top-8 window by the churn above.
    remote.onReceived(origin.currentPayload());
    assertTrue(remote.endpointsFor(early).isEmpty());

    // Anti-entropy fires: the full-state payload still carries "early" even though it's long
    // since fallen out of the recent-delta window.
    remote.onReceived(origin.currentFullStatePayload());
    assertEquals(1, remote.endpointsFor(early).size());
    assertEquals("node-a", remote.endpointsFor(early).get(0).node().nodeId());
  }

  /**
   * Same setup as the registration-recovery test above, but for the opposite direction: an entry
   * "remote" already believes is present gets unregistered on the origin, and that tombstone itself
   * then falls out of the bounded window before "remote" ever re-syncs via ordinary piggyback. Only
   * the full-state anti-entropy payload still carries the tombstone.
   */
  @Test
  void
      anti_entropy_full_state_payload_propagates_an_unregistration_that_fell_out_of_the_piggyback_window() {
    ServiceCatalog origin = new ServiceCatalog();
    MemberId node = node("node-a");
    ServiceExport stale = new ServiceExport("com.gimle.example.Stale", Version.parse("1.0.0"));
    origin.localRegister(
        node,
        "worker-stale",
        MODULE,
        stale,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));

    ServiceCatalog remote = new ServiceCatalog();
    remote.onReceived(origin.currentPayload());
    assertEquals(1, remote.endpointsFor(stale).size());

    origin.localUnregister(node, "worker-stale", MODULE, stale);
    churnRegistrations(origin, node, 10);

    // The unregistration delta has itself fallen out of the bounded piggyback window -- applying
    // it alone leaves "remote" incorrectly still routing to the now-gone endpoint.
    remote.onReceived(origin.currentPayload());
    assertEquals(1, remote.endpointsFor(stale).size());

    // Anti-entropy's full-state payload still carries the tombstone.
    remote.onReceived(origin.currentFullStatePayload());
    assertTrue(remote.endpointsFor(stale).isEmpty());
  }

  /**
   * A catalog bigger than one anti-entropy page (128 entries) must still eventually hand out every
   * entry across successive calls, rotating which slice it sends -- mirroring {@code
   * GossipMember#currentFullState}'s own page-rotation behavior for the membership table.
   */
  @Test
  void anti_entropy_full_state_payload_rotates_pages_for_a_catalog_larger_than_one_page() {
    ServiceCatalog origin = new ServiceCatalog();
    MemberId node = node("node-a");
    int entryCount = 130;
    for (int i = 0; i < entryCount; i++) {
      ServiceExport export = new ServiceExport("com.gimle.example.Svc" + i, Version.parse("1.0.0"));
      origin.localRegister(
          node,
          "worker-" + i,
          MODULE,
          export,
          Optional.empty(),
          new InetSocketAddress("127.0.0.1", 9000 + i));
    }

    ServiceCatalog remote = new ServiceCatalog();
    // Two successive anti-entropy syncs, each capped at 128 entries, must together cover all 130
    // distinct entries via the rotating page offset -- a single page alone cannot.
    remote.onReceived(origin.currentFullStatePayload());
    remote.onReceived(origin.currentFullStatePayload());

    int seen = 0;
    for (int i = 0; i < entryCount; i++) {
      ServiceExport export = new ServiceExport("com.gimle.example.Svc" + i, Version.parse("1.0.0"));
      if (!remote.endpointsFor(export).isEmpty()) {
        seen++;
      }
    }
    assertEquals(entryCount, seen);
  }

  private static void churnRegistrations(ServiceCatalog catalog, MemberId node, int count) {
    for (int i = 0; i < count; i++) {
      ServiceExport churn =
          new ServiceExport("com.gimle.example.Churn" + i, Version.parse("1.0.0"));
      catalog.localRegister(
          node,
          "worker-churn-" + i,
          MODULE,
          churn,
          Optional.empty(),
          new InetSocketAddress("127.0.0.1", 9100 + i));
    }
  }
}
