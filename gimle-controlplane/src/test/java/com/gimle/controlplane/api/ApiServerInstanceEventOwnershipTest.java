package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Which instance timeline a relayed lifecycle event lands on ({@code POST /nodes/{id}/events}).
 *
 * <p>The interesting case is the one a real cluster produces on every delete: the workload's spec
 * and its instance timelines go at once, but the instances themselves are only stopped afterwards,
 * so their closing events arrive after the thing that owned them is gone.
 */
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerInstanceEventOwnershipTest {

  private static final ModuleId MODULE = new ModuleId("com.acme.orders", Version.parse("1.0.0"));

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

  private void putDeployment(String name, String tenantId) {
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutDeployment(
                new DeploymentSpec(
                    name,
                    MODULE,
                    "/artifacts/orders.jar",
                    1,
                    PlacementConstraints.NONE,
                    Optional.empty(),
                    Optional.of(tenantId)),
                inProcessStore.client().getDeploymentGeneration(Optional.of(tenantId), name)));
  }

  private void putAssignment(String name, int index, String tenantId) {
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    name,
                    index,
                    "node-a",
                    MODULE,
                    "/artifacts/orders.jar",
                    OptionalInt.empty(),
                    Optional.of(tenantId))));
  }

  private HttpResponse<String> relayEvent(String name, int index, String id, String kind)
      throws Exception {
    String body =
        Json.write(
            Map.of(
                "id", id,
                "deploymentName", name,
                "instanceIndex", index,
                "kind", kind,
                "message", kind.toLowerCase(java.util.Locale.ROOT),
                "occurredAtEpochMilli", System.currentTimeMillis()));
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/node-a/events"))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private List<String> timelineKinds(String name, int index) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/events?deployment=" + name + "&instance=" + index))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode(), response.body());
    return Json.asObjectList(Json.parse(response.body())).stream()
        .map(event -> String.valueOf(event.get("kind")))
        .toList();
  }

  @Test
  @Timeout(20)
  void a_reused_name_never_inherits_the_deleted_occupants_closing_events() throws Exception {
    putDeployment("orders", Tenant.DEFAULT_TENANT_ID);
    putAssignment("orders", 0, Tenant.DEFAULT_TENANT_ID);
    relayEvent("orders", 0, "e1", "ACTIVE");

    // The delete wipes both the spec and the timeline; the instance is only torn down after it,
    // so its closing events are still in flight at this point.
    inProcessStore
        .client()
        .propose(
            new StateMutation.RemoveDeployment(
                Optional.of(Tenant.DEFAULT_TENANT_ID),
                "orders",
                inProcessStore
                    .client()
                    .getDeploymentGeneration(Optional.of(Tenant.DEFAULT_TENANT_ID), "orders")));
    relayEvent("orders", 0, "e2", "STOPPING");
    relayEvent("orders", 0, "e3", "UNINSTALLED");

    putDeployment("orders", Tenant.DEFAULT_TENANT_ID);
    putAssignment("orders", 0, Tenant.DEFAULT_TENANT_ID);
    relayEvent("orders", 0, "e4", "RESOLVED");
    relayEvent("orders", 0, "e5", "ACTIVE");

    assertEquals(List.of("ACTIVE", "RESOLVED"), timelineKinds("orders", 0));
  }

  @Test
  @Timeout(20)
  void an_event_for_a_workload_that_does_not_exist_is_discarded_not_filed() throws Exception {
    HttpResponse<String> response = relayEvent("never-created", 0, "e1", "ACTIVE");

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("discarded"), response.body());
    assertEquals(List.of(), timelineKinds("never-created", 0));
  }

  /**
   * The ordinary teardown a live workload goes through -- a scale-down or a rolling replacement --
   * still records both closing events: the workload it belongs to is still there.
   */
  @Test
  @Timeout(20)
  void a_live_workloads_own_teardown_events_are_still_recorded() throws Exception {
    putDeployment("orders", Tenant.DEFAULT_TENANT_ID);
    putAssignment("orders", 1, Tenant.DEFAULT_TENANT_ID);
    relayEvent("orders", 1, "e1", "ACTIVE");
    inProcessStore
        .client()
        .propose(
            new StateMutation.RemoveAssignment(Optional.of(Tenant.DEFAULT_TENANT_ID), "orders", 1));
    relayEvent("orders", 1, "e2", "STOPPING");
    relayEvent("orders", 1, "e3", "UNINSTALLED");

    assertEquals(List.of("UNINSTALLED", "STOPPING", "ACTIVE"), timelineKinds("orders", 1));
  }

  /**
   * A closing event arriving once the assignment is gone must still be filed under the tenant the
   * workload's own spec names -- filed untenanted instead, it would survive that workload's later
   * removal and reappear beneath whatever reused the name.
   */
  @Test
  @Timeout(20)
  void a_closing_event_with_no_assignment_left_is_filed_under_the_specs_tenant() throws Exception {
    putDeployment("orders", "acme");
    relayEvent("orders", 0, "e1", "UNINSTALLED");

    assertEquals(
        List.of("UNINSTALLED"),
        inProcessStore.client().listInstanceEvents(Optional.of("acme"), "orders", 0).stream()
            .map(event -> event.kind().name())
            .toList());
  }
}
