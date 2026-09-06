package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ResourceSpec;
import com.gimle.core.protocol.Json;
import com.gimle.os.localdisk.LocalDiskVolumeManager;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The capacity a node reports used to sum only each instance's own declared resource *request* -- a
 * scheduling figure a manifest sets to a few megabytes -- while the budget that actually refuses a
 * spawn sums the real ceiling every worker JVM is started with. A node whose agent was already
 * refusing to spawn anything for lack of real memory therefore kept advertising almost all of the
 * machine as free, so the scheduler kept placing work on it that could never run.
 */
class AgentCapacityReportTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final String NODE_ID = "test-node";
  private static final long NODE_MEMORY_BYTES = new ResourceSpec("16Gi", "4000m").memoryBytes();

  private HttpServer controlPlane;
  private final AtomicReference<Map<String, Object>> heartbeat = new AtomicReference<>();
  private HttpClient httpClient;
  private URI baseUrl;

  @BeforeEach
  void startStubControlPlane() throws IOException {
    controlPlane = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    controlPlane.createContext(
        "/nodes/" + NODE_ID + "/heartbeat",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          heartbeat.set(Json.asObject(Json.parse(body)));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    controlPlane.start();
    httpClient = HttpClient.newHttpClient();
    baseUrl = URI.create("http://127.0.0.1:" + controlPlane.getAddress().getPort());
  }

  @AfterEach
  void stopStubControlPlane() {
    controlPlane.stop(0);
  }

  private Map<String, Object> reportedCapacity(
      CapacityTracker capacityTracker, CapacityTracker committedWorkerCapacity) throws Exception {
    AgentMain.sendHeartbeat(
        httpClient,
        baseUrl,
        NODE_ID,
        new ConcurrentHashMap<>(),
        new ConcurrentHashMap<>(),
        capacityTracker,
        committedWorkerCapacity,
        new LocalDiskVolumeManager(tempDir.resolve("volumes")));
    return Json.asObject(heartbeat.get().get("capacity"));
  }

  private static long longValue(Map<String, Object> capacity, String field) {
    return ((Number) capacity.get(field)).longValue();
  }

  @Test
  void the_reported_capacity_reflects_the_real_worker_ceilings_this_node_has_committed()
      throws Exception {
    CapacityTracker requests = new CapacityTracker(NODE_MEMORY_BYTES, 4000L);
    CapacityTracker committed = new CapacityTracker(NODE_MEMORY_BYTES, 4000L);
    // What a manifest declares, versus what the worker JVM hosting it is actually started with.
    requests.tryAssign("orders#0", new ResourceSpec("16Mi", "10m"));
    committed.tryAssign("orders#0", new ResourceSpec("1Gi", "1000m"));

    Map<String, Object> capacity = reportedCapacity(requests, committed);

    assertEquals(
        new ResourceSpec("1Gi", "1000m").memoryBytes(),
        longValue(capacity, "assignedMemoryBytes"),
        "the binding budget is the committed worker ceiling, not the declared request");
    assertEquals(1000L, longValue(capacity, "assignedCpuMillicores"));
    assertEquals(NODE_MEMORY_BYTES, longValue(capacity, "totalMemoryBytes"));
  }

  /**
   * Packing an instance into an already-running worker costs no additional real memory, so it
   * reserves nothing in the committed budget -- its declared request is then the only figure there
   * is, and must not be rounded away to whatever the spawn budget happens to hold.
   */
  @Test
  void a_request_sum_larger_than_the_committed_sum_is_still_the_one_reported() throws Exception {
    CapacityTracker requests = new CapacityTracker(NODE_MEMORY_BYTES, 4000L);
    CapacityTracker committed = new CapacityTracker(NODE_MEMORY_BYTES, 4000L);
    requests.tryAssign("orders#0", new ResourceSpec("2Gi", "2000m"));

    Map<String, Object> capacity = reportedCapacity(requests, committed);

    assertEquals(
        new ResourceSpec("2Gi", "2000m").memoryBytes(), longValue(capacity, "assignedMemoryBytes"));
    assertEquals(2000L, longValue(capacity, "assignedCpuMillicores"));
  }
}
