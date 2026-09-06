package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workload whose replicas were all placed but none of which ever started used to read back as
 * fully healthy: every index accounted for, nothing unplaced, no quota or limit-range violation,
 * and simply no observation where a live one would be -- exactly the shape a node that refuses to
 * spawn a worker leaves behind. The refusal the owning node recorded against the index was never
 * folded into the workload's own status, so the one place an operator looks said everything was
 * fine while nothing was running.
 */
class ApiServerNotRunningReasonTest {

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

  private void place(String deploymentName, int instanceIndex) {
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    deploymentName,
                    instanceIndex,
                    "node-a",
                    InstanceAssignment.UNSPECIFIED_MODULE,
                    "",
                    OptionalInt.empty(),
                    Optional.of(Tenant.DEFAULT_TENANT_ID))));
  }

  private void recordNodeRefusal(String deploymentName, int instanceIndex, String cause) {
    inProcessStore
        .store()
        .putInstanceEvent(
            Optional.of(Tenant.DEFAULT_TENANT_ID),
            new InstanceEvent(
                UUID.randomUUID().toString(),
                deploymentName,
                instanceIndex,
                InstanceEventKind.TRANSITION_FAILED,
                "instance start refused by this node",
                Optional.of(cause),
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
  void a_placed_replica_whose_node_refused_to_start_it_is_reported_as_not_running()
      throws Exception {
    putDeployment("orders-service", 1);
    place("orders-service", 0);
    recordNodeRefusal(
        "orders-service",
        0,
        "refusing to spawn worker default#orders-service#0: committing its 1Gi ceiling would"
            + " exceed this node's own real memory budget");

    Map<String, Object> status = getDeployment("orders-service");

    assertEquals(
        0, ((Number) status.get("unplacedCount")).intValue(), "the replica really is placed");
    assertEquals(1, ((Number) status.get("notRunningCount")).intValue());
    assertTrue(
        String.valueOf(status.get("notRunningReason")).contains("real memory budget"),
        "the rollup must carry the node's own refusal: " + status.get("notRunningReason"));
    Map<String, Object> instance = Json.asObjectList(status.get("instances")).get(0);
    assertTrue(
        String.valueOf(instance.get("notRunningReason")).contains("refusing to spawn worker"),
        "the index that failed must say so itself: " + instance);
  }

  @Test
  void a_placed_replica_that_has_simply_not_reported_yet_is_not_called_not_running()
      throws Exception {
    putDeployment("orders-service", 1);
    place("orders-service", 0);

    Map<String, Object> status = getDeployment("orders-service");

    assertEquals(0, ((Number) status.get("notRunningCount")).intValue());
    assertFalse(status.containsKey("notRunningReason"));
  }

  /**
   * A refusal recorded against one index says nothing about the others -- a rollup that counted
   * every observation-less instance once any of them had failed would misreport a partially-stuck
   * workload as entirely dead.
   */
  @Test
  void only_the_index_the_refusal_was_recorded_against_is_counted() throws Exception {
    putDeployment("orders-service", 3);
    place("orders-service", 0);
    place("orders-service", 1);
    place("orders-service", 2);
    recordNodeRefusal("orders-service", 1, "no room for its worker's ceiling");

    Map<String, Object> status = getDeployment("orders-service");

    assertEquals(1, ((Number) status.get("notRunningCount")).intValue());
    List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
    for (Map<String, Object> instance : instances) {
      boolean isRefusedIndex = ((Number) instance.get("instanceIndex")).intValue() == 1;
      assertEquals(
          isRefusedIndex,
          instance.containsKey("notRunningReason"),
          "only index 1 should carry a reason: " + instance);
    }
  }
}
