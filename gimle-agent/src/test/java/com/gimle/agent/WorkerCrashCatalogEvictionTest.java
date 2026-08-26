package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.restart.RestartTracker;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.MemberId;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression: a worker-level crash must evict that worker's own entries from this agent's shared
 * {@link ServiceCatalog} immediately, rather than leaving every other cluster member to keep
 * routing to it for as long as TTL expiry or SWIM dead-node detection takes to notice -- see {@link
 * ServiceCatalog#evictWorker}'s own javadoc for why neither of those fires promptly for a
 * worker-only crash on an otherwise-healthy node.
 */
class WorkerCrashCatalogEvictionTest {

  private static final ServiceExport GREETER =
      new ServiceExport("com.gimle.example.Greeter", Version.parse("1.0.0"));
  private static final ModuleId MODULE =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));
  private static final ResourceSpec RESOURCES = new ResourceSpec("16Mi", "500m");
  private static final MemberId SELF =
      new MemberId("node-a", new InetSocketAddress("127.0.0.1", 7946));

  /**
   * An {@link HttpClient} pointed at a port nothing listens on -- {@code
   * AgentMain#postInstanceEvent}'s own catch-and-log posture around the resulting connection
   * failure means it never surfaces here, so no real control-plane stub is needed just to observe
   * catalog-eviction behavior.
   */
  private static final HttpClient UNREACHABLE_HTTP_CLIENT = HttpClient.newHttpClient();

  private static final URI UNREACHABLE_BASE_URL = URI.create("http://127.0.0.1:1/");

  private static WorkerProcessSupervisor stubSupervisor(String workerId) {
    return new WorkerProcessSupervisor(
        workerId,
        List::of,
        Path.of("/does/not/matter.sock"),
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10)),
        exhaustedWorkerId -> {});
  }

  private static ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        "orders",
        Version.parse("1.0.0"),
        List.of(),
        List.of(),
        IsolationTier.TIER_1,
        RESOURCES,
        RESOURCES,
        HealthProbes.NONE,
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }

  private static SupervisedInstance instanceHostedBy(
      String supervisionKey, int instanceIndex, String fabricWorkerId) {
    ModuleDescriptor descriptor = descriptor();
    AssignedInstance assigned =
        new AssignedInstance(
            "orders", instanceIndex, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance =
        new SupervisedInstance(assigned, stubSupervisor(supervisionKey), null, descriptor);
    instance.fabricWorkerId = fabricWorkerId;
    return instance;
  }

  @Test
  void a_worker_crash_evicts_its_entries_from_the_shared_catalog() {
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        SELF,
        "fabric-worker-1",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));
    assertTrue(catalog.hasAnyKnownExporter(GREETER));

    SupervisedInstance instance = instanceHostedBy("orders#0", 0, "fabric-worker-1");
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("orders#0", instance);

    AgentMain.onWorkerCrash(
        new CrashInfo(CrashInfo.Cause.UNKNOWN, 137, Optional.empty()),
        "orders#0",
        supervised,
        catalog,
        UNREACHABLE_HTTP_CLIENT,
        UNREACHABLE_BASE_URL,
        "node-a");

    assertFalse(catalog.hasAnyKnownExporter(GREETER));
  }

  @Test
  void a_crash_before_the_hello_handshake_leaves_the_catalog_untouched() {
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        SELF,
        "fabric-worker-1",
        MODULE,
        GREETER,
        Optional.empty(),
        new InetSocketAddress("127.0.0.1", 9000));

    // fabricWorkerId left null: this instance's worker crashed before ever completing its Hello
    // handshake, so it never registered anything into the catalog for onWorkerCrash to evict.
    SupervisedInstance instance = instanceHostedBy("orders#1", 1, null);
    Map<String, SupervisedInstance> supervised = new LinkedHashMap<>();
    supervised.put("orders#1", instance);

    AgentMain.onWorkerCrash(
        new CrashInfo(CrashInfo.Cause.UNKNOWN, 1, Optional.empty()),
        "orders#1",
        supervised,
        catalog,
        UNREACHABLE_HTTP_CLIENT,
        UNREACHABLE_BASE_URL,
        "node-a");

    // Unrelated fabric-worker-1's export must survive: nothing here should have touched the
    // catalog.
    assertTrue(catalog.hasAnyKnownExporter(GREETER));
  }
}
