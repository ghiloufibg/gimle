package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The two instance-addressing routes that take a bare {@code (name, index)} with no {@code
 * ?tenant=} of their own -- {@code GET /logs/instances/...} and {@code GET
 * /instances/.../fabric-endpoint} -- against a workload that belongs to a tenant other than the
 * default one.
 *
 * <p>Both are proxy routes, so the assertions are about what reached the node agent: which node was
 * chosen, and what the forwarded query actually said. A stub {@code HttpServer} stands in for the
 * agent, the same shape {@code ApiServerFabricEndpointTest} already uses.
 */
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerInstanceTenantResolutionTest {

  private static final String TENANT = "acme";
  private static final ModuleId MODULE = new ModuleId("com.acme.orders", Version.parse("1.4.0"));

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;
  private HttpServer agentStub;
  private final List<String> agentReceivedUris = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startEverything() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    client = HttpClient.newHttpClient();
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();

    agentStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    agentStub.createContext("/", this::serveStub);
    agentStub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    agentStub.start();
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutNodeRegistration(
                new NodeRegistration(
                    "node-a",
                    new NodeCapabilities(Set.of()),
                    Optional.of("127.0.0.1:" + agentStub.getAddress().getPort()))));
  }

  @AfterEach
  void stopEverything() {
    if (server != null) {
      server.close();
    }
    if (agentStub != null) {
      agentStub.stop(0);
    }
    inProcessFafnir.close();
    inProcessStore.close();
  }

  private void serveStub(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    agentReceivedUris.add(exchange.getRequestURI().toString());
    byte[] body = "{\"lines\":[]}".getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
    exchange.close();
  }

  /** A workload owned by {@link #TENANT}, placed on {@code node-a} at {@code index}. */
  private void placeUnderTenant(String name, int index) {
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
                    Optional.of(TENANT)),
                0));
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
                    Optional.of(TENANT))));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  @Timeout(20)
  void a_bare_instance_log_read_tells_the_agent_which_tenant_it_resolved() throws Exception {
    placeUnderTenant("orders", 0);

    HttpResponse<String> response = get("/logs/instances/orders/0?category=APPLICATION&limit=200");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, agentReceivedUris.size(), agentReceivedUris.toString());
    String forwarded = agentReceivedUris.get(0);
    assertTrue(
        forwarded.startsWith("/logs/instances/orders/0?"),
        "unexpected forwarded path: " + forwarded);
    assertTrue(
        forwarded.contains("tenant=" + TENANT),
        "the resolved tenant must reach the agent, which cannot re-derive it from the bare name"
            + " for every hosting mode: "
            + forwarded);
    assertTrue(forwarded.contains("category=APPLICATION"), forwarded);
  }

  @Test
  @Timeout(20)
  void a_declared_tenant_is_forwarded_once_not_twice() throws Exception {
    placeUnderTenant("orders", 0);

    assertEquals(200, get("/logs/instances/orders/0?tenant=" + TENANT).statusCode());

    String forwarded = agentReceivedUris.get(0);
    assertEquals(
        1,
        forwarded.split("tenant=", -1).length - 1,
        "the caller's own tenant is forwarded verbatim, never duplicated: " + forwarded);
  }

  @Test
  @Timeout(20)
  void a_name_no_workload_spec_claims_forwards_no_invented_tenant() throws Exception {
    // Only the assignment exists -- there is no spec to resolve an owner from, so the default
    // namespace stands in for authorization but must not be passed off to the agent as fact.
    inProcessStore
        .client()
        .propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    "ghost",
                    0,
                    "node-a",
                    MODULE,
                    "/artifacts/orders.jar",
                    OptionalInt.empty(),
                    Optional.of(Tenant.DEFAULT_TENANT_ID))));

    assertEquals(200, get("/logs/instances/ghost/0").statusCode());

    assertFalse(agentReceivedUris.get(0).contains("tenant="), agentReceivedUris.get(0));
  }

  @Test
  @Timeout(20)
  void a_bare_fabric_endpoint_lookup_resolves_a_tenanted_instance() throws Exception {
    placeUnderTenant("orders", 0);

    HttpResponse<String> response = get("/instances/orders/0/fabric-endpoint");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals("/fabric-endpoints/orders/0", agentReceivedUris.get(0));
  }

  @Test
  @Timeout(20)
  void a_bare_fabric_endpoint_lookup_for_an_unplaced_name_is_still_a_404() throws Exception {
    assertEquals(404, get("/instances/nothing-here/0/fabric-endpoint").statusCode());
    assertTrue(agentReceivedUris.isEmpty(), agentReceivedUris.toString());
  }
}
