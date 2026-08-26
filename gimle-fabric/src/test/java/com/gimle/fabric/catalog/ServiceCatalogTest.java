package com.gimle.fabric.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
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
  private static final ModuleId MODULE =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

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
}
