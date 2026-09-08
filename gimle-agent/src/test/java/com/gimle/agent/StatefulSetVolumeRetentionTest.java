package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.module.VolumeRequest;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.restart.RestartTracker;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.os.VolumeHandle;
import com.gimle.os.localdisk.LocalDiskVolumeManager;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bug 32: a StatefulSet index's volume is now retained on an ordinary scale-down and destroyed only
 * on a genuine permanent removal -- Kubernetes' own default. {@link AgentMain#stopInstance} itself
 * has always taken a {@code releaseVolume} parameter; what these tests pin down is that {@code
 * false} truly leaves the on-disk data untouched and {@code true} truly destroys it, so a caller
 * computing the right value for that parameter (see {@code AgentMain#reconcileAssignments}'s own
 * per-key {@code statefulSetVolumeRetained} check) can rely on it.
 */
class StatefulSetVolumeRetentionTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final ResourceSpec RESOURCES = new ResourceSpec("4Mi", "50m");
  private static final String NODE_ID = "test-node";

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

  /** Never actually spawns a process -- see {@code WorkerCrashCatalogEvictionTest}'s identical. */
  private static WorkerProcessSupervisor stubSupervisor(String workerId) {
    return new WorkerProcessSupervisor(
        workerId,
        List::of,
        Path.of("/does/not/matter.sock"),
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10)),
        exhaustedWorkerId -> {});
  }

  private ControlChannelServer stubServer() throws IOException {
    return new ControlChannelServer(
        Files.createTempDirectory(tempDir, "uds-").resolve("worker.sock"));
  }

  private SupervisedInstance instanceWithVolume(String key, VolumeHandle handle)
      throws IOException {
    ModuleDescriptor descriptor = descriptor();
    AssignedInstance assigned =
        new AssignedInstance(
            "orders", handle.instanceIndex(), descriptor.id(), "/does/not/matter.jar");
    SupervisedInstance instance =
        new SupervisedInstance(assigned, stubSupervisor(key), stubServer(), descriptor);
    instance.volumeHandles = List.of(handle);
    return instance;
  }

  private void stop(
      Map<String, SupervisedInstance> supervised,
      String key,
      LocalDiskVolumeManager volumeManager,
      boolean releaseVolume) {
    AgentMain.stopInstance(
        key,
        supervised,
        new CapacityTracker(1_000_000_000L, 4000L),
        new CapacityTracker(1_000_000_000L, 4000L),
        new ConcurrentHashMap<>(),
        new ConcurrentHashMap<>(),
        volumeManager,
        releaseVolume,
        new ServiceCatalog(),
        NODE_ID);
  }

  @Test
  void release_volume_false_leaves_the_volumes_data_on_disk() throws IOException {
    LocalDiskVolumeManager volumeManager = new LocalDiskVolumeManager(tempDir.resolve("data"));
    VolumeHandle handle =
        volumeManager.allocate(
            Optional.empty(), "orders", 1, "data", new VolumeRequest(1024, ReclaimPolicy.DELETE));
    Path hostPath = volumeManager.hostPath(handle);
    Files.writeString(hostPath.resolve("state.dat"), "important");

    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    String key = "orders#1";
    supervised.put(key, instanceWithVolume(key, handle));

    stop(supervised, key, volumeManager, false);

    assertFalse(supervised.containsKey(key), "the instance itself must still be torn down");
    assertTrue(
        Files.exists(hostPath.resolve("state.dat")),
        "releaseVolume=false (an ordinary scale-down) must leave the volume's data in place");
  }

  @Test
  void release_volume_true_destroys_the_volumes_data() throws IOException {
    LocalDiskVolumeManager volumeManager = new LocalDiskVolumeManager(tempDir.resolve("data"));
    VolumeHandle handle =
        volumeManager.allocate(
            Optional.empty(), "orders", 2, "data", new VolumeRequest(1024, ReclaimPolicy.DELETE));
    Path hostPath = volumeManager.hostPath(handle);
    Files.writeString(hostPath.resolve("state.dat"), "important");

    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    String key = "orders#2";
    supervised.put(key, instanceWithVolume(key, handle));

    stop(supervised, key, volumeManager, true);

    assertFalse(supervised.containsKey(key));
    assertFalse(
        Files.exists(hostPath),
        "releaseVolume=true (a genuine spec deletion) must destroy the volume's data");
  }

  private static HttpServer respondingWithAttached(String attachedJson) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes = ("{\"attached\":" + attachedJson + "}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static AssignedInstance assignedInstance(String name, int index) {
    return new AssignedInstance(
        name,
        index,
        descriptor().id(),
        "/does/not/matter.jar",
        Optional.empty(),
        OptionalInt.empty());
  }

  @Test
  void statefulset_volume_retained_reports_true_when_the_control_plane_says_attached()
      throws IOException {
    HttpServer server = respondingWithAttached("true");
    try {
      URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      assertTrue(
          AgentMain.statefulSetVolumeRetained(
              HttpClient.newHttpClient(), baseUrl, NODE_ID, assignedInstance("orders", 1)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void statefulset_volume_retained_reports_false_when_the_control_plane_says_unattached()
      throws IOException {
    HttpServer server = respondingWithAttached("false");
    try {
      URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      assertFalse(
          AgentMain.statefulSetVolumeRetained(
              HttpClient.newHttpClient(), baseUrl, NODE_ID, assignedInstance("orders", 1)));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void statefulset_volume_retained_fails_closed_toward_retention_when_unreachable() {
    URI unreachable = URI.create("http://127.0.0.1:1/");
    assertTrue(
        AgentMain.statefulSetVolumeRetained(
            HttpClient.newHttpClient(), unreachable, NODE_ID, assignedInstance("orders", 1)),
        "an uncertain answer must never be read as license to destroy an index's data");
  }
}
