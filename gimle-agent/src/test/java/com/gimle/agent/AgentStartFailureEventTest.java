package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.module.artifact.ArtifactPullCache;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.observability.MuninnShipper;
import com.gimle.os.localdisk.LocalDiskVolumeManager;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * A start this node refuses -- a worker ceiling that would overcommit the machine's real memory
 * above all -- used to be reported nowhere an operator would ever address the workload: the
 * deployment kept reporting its desired replicas, its instance timeline stayed empty, and the only
 * account of the refusal was a line in this node's own platform log, reachable solely by knowing to
 * ask a different resource (the node) about a problem the workload has.
 */
class AgentStartFailureEventTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final String NODE_ID = "test-node";

  private HttpServer controlPlane;
  private final List<Map<String, Object>> postedEvents = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> assignments = new ArrayList<>();
  private final Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
  private HttpClient httpClient;
  private URI baseUrl;

  @BeforeEach
  void startStubControlPlane() throws IOException {
    controlPlane = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    controlPlane.createContext(
        "/nodes/" + NODE_ID + "/assignments",
        exchange -> respondJson(exchange, Json.write(assignments)));
    controlPlane.createContext(
        "/nodes/" + NODE_ID + "/events",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          postedEvents.add(Json.asObject(Json.parse(body)));
          respondJson(exchange, "\"ok\"");
        });
    controlPlane.start();
    httpClient = HttpClient.newHttpClient();
    baseUrl = URI.create("http://127.0.0.1:" + controlPlane.getAddress().getPort());
  }

  @AfterEach
  void stopStubControlPlaneAndAnySpawnedWorker() {
    for (SupervisedInstance instance : supervised.values()) {
      instance.supervisor.close();
    }
    controlPlane.stop(0);
  }

  private static void respondJson(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private Path buildModuleJar() {
    return TestModuleBuilder.module(
            """
            module com.gimle.fixture.refused {
            }
            """)
        .withDescriptor(TestModuleBuilder.minimalDescriptor("com.gimle.fixture.refused", "1.0.0"))
        .build(tempDir, "refused.jar");
  }

  private void assign(String deploymentName, Path artifact) {
    assignments.add(
        Map.of(
            "deploymentName",
            deploymentName,
            "instanceIndex",
            0,
            "moduleId",
            Map.of("name", "com.gimle.fixture.refused", "version", "1.0.0"),
            "artifactPath",
            artifact.toString()));
  }

  /** The key an untenanted assignment is supervised (and start-failure-tracked) under. */
  private static String key(String deploymentName) {
    return "#" + deploymentName + "#0";
  }

  /**
   * Sized below one worker's own ceiling, so the committed-memory guard refuses the spawn before
   * anything is forked -- the exact refusal an operator saw only in the node log.
   */
  private static CapacityTracker exhaustedCommittedCapacity() {
    return new CapacityTracker(1024L, 10L);
  }

  private static CapacityTracker roomyCapacity() {
    return new CapacityTracker(1_000_000_000L, 4000L);
  }

  private void reconcile(
      CapacityTracker committedWorkerCapacity, Map<String, String> reportedStartFailures)
      throws Exception {
    AgentMain.reconcileAssignments(
        httpClient,
        baseUrl,
        baseUrl,
        List.of(),
        new ArtifactPullCache(tempDir.resolve("artifact-cache")),
        new SleipnirCache(tempDir.resolve("aot-cache"), supervised, javaExecutable()),
        null,
        NODE_ID,
        supervised,
        new ConcurrentHashMap<>(),
        new ConcurrentHashMap<String, List<MuninnShipper>>(),
        new ConcurrentHashMap<String, AgentMain.WorkerShipperPair>(),
        javaExecutable(),
        List.of("-cp", System.getProperty("java.class.path"), "com.gimle.worker.WorkerMain"),
        new PortableJvmFlagsResourceLimiter(),
        new LocalDiskVolumeManager(tempDir.resolve("volumes")),
        roomyCapacity(),
        committedWorkerCapacity,
        null,
        new ServiceCatalog(),
        tempDir.resolve("logs"),
        1,
        Tier1WorkerBudget.parse("50Mi", "500m", "8Mi"),
        reportedStartFailures);
  }

  @Test
  void a_refused_spawn_is_reported_as_a_durable_transition_failed_event_for_that_instance()
      throws Exception {
    assign("dep-leaktest", buildModuleJar());

    reconcile(exhaustedCommittedCapacity(), new ConcurrentHashMap<>());

    assertEquals(1, postedEvents.size(), "expected exactly one event, got " + postedEvents);
    Map<String, Object> event = postedEvents.get(0);
    assertEquals("dep-leaktest", event.get("deploymentName"));
    assertEquals(0, ((Number) event.get("instanceIndex")).intValue());
    assertEquals("TRANSITION_FAILED", event.get("kind"));
    assertTrue(
        String.valueOf(event.get("causeSummary")).contains("refusing to spawn worker"),
        "the event must carry the node's own refusal, not a generic message: " + event);
  }

  /**
   * Reconciliation is level-triggered, so an unfixable start is retried on every tick. Each retry
   * posting its own event would push every other entry out of the instance's own bounded timeline
   * within minutes -- the timeline that exists precisely to explain this failure.
   */
  @Test
  void an_unchanged_refusal_repeated_every_tick_is_reported_once_rather_than_on_every_tick()
      throws Exception {
    assign("dep-leaktest", buildModuleJar());
    Map<String, String> reportedStartFailures = new ConcurrentHashMap<>();
    CapacityTracker committedWorkerCapacity = exhaustedCommittedCapacity();

    reconcile(committedWorkerCapacity, reportedStartFailures);
    reconcile(committedWorkerCapacity, reportedStartFailures);
    reconcile(committedWorkerCapacity, reportedStartFailures);

    assertEquals(1, postedEvents.size(), "expected one event across three ticks: " + postedEvents);
  }

  /**
   * An artifact this node cannot even read reaches the same catch, and is just as invisible: the
   * instance is placed, so nothing reports it unplaced, and no worker exists to report anything
   * else about it.
   */
  @Test
  void an_unreadable_artifact_is_reported_the_same_way_a_refused_spawn_is() throws Exception {
    assign("dep-missing-jar", tempDir.resolve("nonexistent.jar"));

    reconcile(roomyCapacity(), new ConcurrentHashMap<>());

    assertEquals(1, postedEvents.size(), "expected exactly one event, got " + postedEvents);
    assertEquals("TRANSITION_FAILED", postedEvents.get(0).get("kind"));
    assertEquals("dep-missing-jar", postedEvents.get(0).get("deploymentName"));
  }

  @Test
  void an_assignment_that_starts_normally_reports_no_failure_event() throws Exception {
    assign("dep-healthy", buildModuleJar());
    Map<String, String> reportedStartFailures = new ConcurrentHashMap<>();

    reconcile(roomyCapacity(), reportedStartFailures);

    assertTrue(supervised.containsKey(key("dep-healthy")), "the instance should have started");
    assertTrue(postedEvents.isEmpty(), "a healthy start must report nothing: " + postedEvents);
    assertFalse(reportedStartFailures.containsKey(key("dep-healthy")));
  }

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    return command.orElseGet(
        () -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
  }
}
