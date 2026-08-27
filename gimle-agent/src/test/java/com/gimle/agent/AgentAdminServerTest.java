package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.agent.testsupport.InProcessStore;
import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.Json;
import com.gimle.core.restart.RestartTracker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link AgentAdminServer} in plaintext mode (no client certificate needed -- see {@code
 * authorizeFault}'s own carve-out, the same one {@code AndvariServerAuthTest}/{@code
 * FafnirServerAuthTest} already rely on for their own plaintext-mode coverage) against a real
 * {@link InProcessStore} and a real {@link WorkerProcessSupervisor} supervising a genuine {@code
 * sleep 300} subprocess -- so a kill actually kills an OS process and the supervisor's own
 * crash-detection genuinely respawns it, not a mock standing in for either. The RBAC decision logic
 * itself (mTLS-cert-resolved principal, {@code Authorizer.authorize}) is structurally identical to
 * Fafnir's/ Andvari's own already-covered {@code authorizeSecrets}/{@code authorizeArtifacts} --
 * re-proving the same generic mechanics here would be low-marginal-value redundant coverage, not a
 * new risk surface, so this suite focuses on what actually is new: the kill/status dispatch itself.
 */
final class AgentAdminServerTest {

  private static final ResourceSpec RESOURCES = new ResourceSpec("16Mi", "500m");

  @TempDir private Path tempDir;

  private InProcessStore store;
  private AgentAdminServer server;
  private Map<String, SupervisedInstance> supervised;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    supervised = new ConcurrentHashMap<>();
    server = new AgentAdminServer(store.client(), 0, supervised);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
  }

  @AfterEach
  void tearDown() {
    server.close();
    supervised.values().forEach(instance -> instance.supervisor.close());
    store.close();
  }

  private SupervisedInstance startFakeWorker(String deploymentName, int instanceIndex)
      throws IOException {
    ModuleDescriptor descriptor =
        new ModuleDescriptor(
            "orders-module",
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
    AssignedInstance assigned =
        new AssignedInstance(
            deploymentName,
            instanceIndex,
            descriptor.id(),
            "/does/not/matter.jar",
            Optional.empty());
    WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            deploymentName + "#" + instanceIndex,
            // The trailing arg WorkerProcessSupervisor.spawn() appends (the control-socket path)
            // lands as sh's own $0, not consumed as sleep's own duration -- a real long-lived
            // process with no real worker JVM needed for this suite's own purposes.
            () -> List.of("sh", "-c", "sleep 300"),
            tempDir.resolve(deploymentName + "-" + instanceIndex + ".sock"),
            new RestartTracker(
                Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10)),
            exhaustedWorkerId -> {});
    supervisor.start();
    SupervisedInstance instance = new SupervisedInstance(assigned, supervisor, null, descriptor);
    supervised.put(deploymentName + "#" + instanceIndex, instance);
    return instance;
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(10)
  void get_reports_pid_and_alive_for_a_supervised_worker() throws Exception {
    SupervisedInstance instance = startFakeWorker("orders", 0);

    HttpResponse<String> response = get("/admin/faults/workers/orders/0");

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals(instance.supervisor.process().pid(), ((Number) body.get("pid")).longValue());
    assertEquals(true, body.get("alive"));
  }

  @Test
  @Timeout(10)
  void get_for_an_unsupervised_instance_is_404() throws Exception {
    HttpResponse<String> response = get("/admin/faults/workers/orders/0");

    assertEquals(404, response.statusCode());
  }

  @Test
  @Timeout(30)
  void kill_with_the_correct_pid_kills_the_process_and_the_supervisor_respawns_it()
      throws Exception {
    SupervisedInstance instance = startFakeWorker("orders", 0);
    long originalPid = instance.supervisor.process().pid();

    HttpResponse<String> response =
        post("/admin/faults/workers/orders/0/kill", Map.of("pid", originalPid));

    assertEquals(200, response.statusCode());
    assertEquals(true, Json.asObject(Json.parse(response.body())).get("killed"));
    assertFalse(
        ProcessHandle.of(originalPid).map(ProcessHandle::isAlive).orElse(false),
        "the killed process should genuinely be dead");

    // The supervisor's own onExit respawns it -- the platform's own recovery, not this endpoint's
    // job, matching WORKER_KILL's own contract (see FaultKind's own javadoc).
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    long newPid = -1;
    while (System.nanoTime() < deadline) {
      long candidate = instance.supervisor.process().pid();
      if (candidate != originalPid
          && ProcessHandle.of(candidate).map(ProcessHandle::isAlive).orElse(false)) {
        newPid = candidate;
        break;
      }
      Thread.sleep(200);
    }
    assertTrue(newPid > 0 && newPid != originalPid, "expected a genuinely new pid to appear");
  }

  @Test
  @Timeout(10)
  void kill_with_the_wrong_pid_is_refused_and_kills_nothing() throws Exception {
    SupervisedInstance instance = startFakeWorker("orders", 0);
    long realPid = instance.supervisor.process().pid();

    HttpResponse<String> response =
        post("/admin/faults/workers/orders/0/kill", Map.of("pid", realPid + 999));

    assertEquals(409, response.statusCode());
    assertEquals(false, Json.asObject(Json.parse(response.body())).get("killed"));
    assertTrue(ProcessHandle.of(realPid).map(ProcessHandle::isAlive).orElse(false));
  }

  @Test
  @Timeout(10)
  void kill_for_an_unsupervised_instance_is_404() throws Exception {
    HttpResponse<String> response =
        post("/admin/faults/workers/orders/0/kill", Map.of("pid", 12345));

    assertEquals(404, response.statusCode());
  }
}
