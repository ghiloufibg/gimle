package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.Tenant;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * A deployment stuck with replicas the scheduler cannot place used to report only how many were
 * unplaced, never why: the reconciler's own refusal went to the control plane's platform log, where
 * it was re-logged every tick, and to the unplaced index's own event timeline -- somewhere nobody
 * thinks to look for a replica that never started. Reading {@code get deployments orders-service}
 * left an operator with {@code UNPLACED(1)} and nowhere to go.
 */
class ApiServerUnplacedReasonTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  private void putDeployment(String name, int replicas) throws Exception {
    String yaml =
        """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders.jar
        replicas: %d
        """
            .formatted(name, replicas);
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
                .PUT(HttpRequest.BodyPublishers.ofString(yaml))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), response.body());
  }

  private void recordSchedulerRefusal(String deploymentName, int instanceIndex, String message) {
    inProcessStore
        .store()
        .putInstanceEvent(
            Optional.of(Tenant.DEFAULT_TENANT_ID),
            new InstanceEvent(
                UUID.randomUUID().toString(),
                deploymentName,
                instanceIndex,
                InstanceEventKind.TRANSITION_FAILED,
                message,
                1_000L));
  }

  private Map<String, Object> getDeployment(String name) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), response.body());
    return Json.asObject(Json.parse(response.body()));
  }

  @Test
  void a_stuck_deployment_reports_the_schedulers_own_refusal_in_its_status() throws Exception {
    putDeployment("orders-service", 2);
    recordSchedulerRefusal("orders-service", 0, "no node supports isolation tier TIER_2");

    Map<String, Object> status = getDeployment("orders-service");

    assertEquals(2, ((Number) status.get("unplacedCount")).intValue());
    assertEquals("no node supports isolation tier TIER_2", status.get("unplacedReason"));
  }

  @Test
  void the_lowest_numbered_unplaced_replica_wins_rather_than_an_arbitrary_one() throws Exception {
    putDeployment("orders-service", 3);
    recordSchedulerRefusal("orders-service", 1, "insufficient capacity on every node");
    recordSchedulerRefusal("orders-service", 2, "anti-affinity leaves no candidate");

    assertEquals(
        "insufficient capacity on every node",
        getDeployment("orders-service").get("unplacedReason"));
  }

  @Test
  void a_deployment_nothing_has_refused_yet_reports_no_reason_at_all() throws Exception {
    putDeployment("orders-service", 1);

    Map<String, Object> status = getDeployment("orders-service");

    assertEquals(1, ((Number) status.get("unplacedCount")).intValue());
    assertFalse(status.containsKey("unplacedReason"));
  }
}
