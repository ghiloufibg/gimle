package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The subsystem badges GIMLE-458's own Control plane console screen reads: {@code /health} must
 * report each one's real last-run outcome (fed by {@code ControlPlaneMain}'s own reconcile tick
 * through {@link com.gimle.controlplane.health.ReconcilerHealth}), not a hardcoded "running".
 */
class ApiServerSubsystemHealthTest {

  @TempDir Path tempDir;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private ApiServer server;
  private InProcessStore store;
  private InProcessFafnir fafnir;

  @AfterEach
  void tearDown() throws IOException {
    if (server != null) {
      server.close();
    }
    if (fafnir != null) {
      fafnir.close();
    }
    if (store != null) {
      store.close();
    }
  }

  @Test
  @Timeout(30)
  void a_step_that_never_ran_reports_standby_before_any_reconcile_tick_has_happened()
      throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(store.client(), 0, fafnir.client());
    server.start();

    Map<String, Object> subsystems = subsystemsFromHealth();
    assertEquals("STANDBY", statusOf(subsystems, "scheduler"));
    assertEquals("STANDBY", statusOf(subsystems, "quotaEnforcer"));
    assertEquals("STANDBY", statusOf(subsystems, "heartbeatWorker"));
  }

  @Test
  @Timeout(30)
  void a_recorded_reconcile_success_reports_up_once_this_replica_is_the_leader() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(store.client(), 0, fafnir.client());
    server.start();

    server.reconcilerHealth().setLeader(true);
    server.reconcilerHealth().recordSuccess("quota");
    server.reconcilerHealth().recordSuccess("health");
    server.reconcilerHealth().recordSuccess("deployment");
    server.reconcilerHealth().recordSuccess("job");
    server.reconcilerHealth().recordSuccess("daemonSet");
    server.reconcilerHealth().recordSuccess("statefulSet");

    Map<String, Object> subsystems = subsystemsFromHealth();
    assertEquals("UP", statusOf(subsystems, "scheduler"));
    assertEquals("UP", statusOf(subsystems, "quotaEnforcer"));
    assertEquals("UP", statusOf(subsystems, "heartbeatWorker"));
  }

  @Test
  @Timeout(30)
  void one_failed_scheduling_reconciler_reports_the_combined_scheduler_badge_down()
      throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(store.client(), 0, fafnir.client());
    server.start();

    server.reconcilerHealth().setLeader(true);
    server.reconcilerHealth().recordFailure("deployment", "store leader-election gap");
    server.reconcilerHealth().recordSuccess("job");
    server.reconcilerHealth().recordSuccess("daemonSet");
    server.reconcilerHealth().recordSuccess("statefulSet");

    Map<String, Object> subsystems = subsystemsFromHealth();
    assertEquals("DOWN", statusOf(subsystems, "scheduler"));
    Map<String, Object> scheduler = Json.asObject(subsystems.get("scheduler"));
    assertEquals("store leader-election gap", scheduler.get("detail"));
  }

  @Test
  @Timeout(30)
  void a_control_plane_with_no_andvari_endpoint_reports_artifact_resolver_up_with_a_note()
      throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    fafnir = InProcessFafnir.start(store.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(store.client(), 0, fafnir.client());
    server.start();

    Map<String, Object> subsystems = pollUntilArtifactResolverReported();
    assertEquals("UP", statusOf(subsystems, "artifactResolver"));
    Map<String, Object> artifactResolver = Json.asObject(subsystems.get("artifactResolver"));
    assertTrue(
        String.valueOf(artifactResolver.get("detail")).contains("no artifact registry configured"),
        "expected a note explaining there is nothing to probe: " + artifactResolver);
  }

  private Map<String, Object> pollUntilArtifactResolverReported() throws Exception {
    Instant deadline = Instant.now().plusSeconds(15);
    Map<String, Object> subsystems;
    do {
      subsystems = subsystemsFromHealth();
    } while (!"UP".equals(statusOf(subsystems, "artifactResolver"))
        && Instant.now().isBefore(deadline));
    return subsystems;
  }

  private Map<String, Object> subsystemsFromHealth() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), "expected a healthy cluster: " + response.body());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    return Json.asObject(body.get("subsystems"));
  }

  private static String statusOf(Map<String, Object> subsystems, String name) {
    Map<String, Object> subsystem = Json.asObject(subsystems.get(name));
    return String.valueOf(subsystem.get("status"));
  }
}
