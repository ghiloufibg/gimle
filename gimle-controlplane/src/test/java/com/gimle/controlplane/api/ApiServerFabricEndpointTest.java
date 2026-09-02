package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StatefulSetAssignment;
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
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * {@code ApiServer}'s {@code /instances/{name}/{index}/fabric-endpoint} route: resolving which node
 * currently hosts an instance and proxying the lookup to that node's own agent, which is the only
 * process that knows the address its worker bound.
 *
 * <p>Uses the same stub-{@code HttpServer}-as-agent shape {@code ApiServerLogsFallbackTest} already
 * established -- what matters here is which node was chosen and which path was forwarded, not the
 * agent's own response body, which {@code AgentLogServerTest} covers directly.
 */
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerFabricEndpointTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private StateStore store;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;
  private HttpServer agentStub;
  private final List<String> agentReceivedPaths = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startStores() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    store = inProcessStore.store();
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    client = HttpClient.newHttpClient();
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
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

  private void startAgentStub() throws IOException {
    agentStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    agentStub.createContext(
        "/fabric-endpoints",
        exchange -> {
          agentReceivedPaths.add(exchange.getRequestURI().toString());
          byte[] body = "{\"tcpAddress\":\"127.0.0.1:41234\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    agentStub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    agentStub.start();
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void a_placed_instances_lookup_is_proxied_to_the_node_actually_hosting_it() throws Exception {
    startAgentStub();
    store.putAssignment(
        new InstanceAssignment(
            "greeter-provider",
            2,
            "node-a",
            new ModuleId("com.gimle.examples.greeter", Version.parse("1.0.0")),
            "/artifacts/greeter.jar",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    HttpResponse<String> response = get("/instances/greeter-provider/2/fabric-endpoint");

    assertEquals(200, response.statusCode());
    assertEquals(1, agentReceivedPaths.size());
    assertEquals("/fabric-endpoints/greeter-provider/2", agentReceivedPaths.get(0));
    assertTrue(response.body().contains("127.0.0.1:41234"));
  }

  @Test
  void a_statefulset_placed_instance_resolves_too_not_only_a_deployment() throws Exception {
    // The resolution walks all four placement kinds; a route that only consulted Deployment
    // assignments would 404 here while the instance is plainly running.
    startAgentStub();
    store.putStatefulSetAssignment(
        new StatefulSetAssignment(
            "orders-statefulset",
            0,
            "node-a",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders.jar",
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    HttpResponse<String> response = get("/instances/orders-statefulset/0/fabric-endpoint");

    assertEquals(200, response.statusCode());
    assertEquals("/fabric-endpoints/orders-statefulset/0", agentReceivedPaths.get(0));
  }

  @Test
  void an_instance_with_no_placement_is_a_404_and_reaches_no_agent() throws Exception {
    startAgentStub();

    HttpResponse<String> response = get("/instances/ghost-deployment/0/fabric-endpoint");

    assertEquals(404, response.statusCode());
    assertTrue(agentReceivedPaths.isEmpty(), "no agent should have been dialed");
  }

  @Test
  void a_malformed_instance_path_is_rejected_before_any_lookup() throws Exception {
    assertEquals(404, get("/instances/greeter-provider/0").statusCode());
    assertEquals(404, get("/instances/greeter-provider/0/something-else").statusCode());
    assertEquals(400, get("/instances/greeter-provider/not-a-number/fabric-endpoint").statusCode());
  }

  @Test
  void a_non_get_method_is_rejected() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/instances/greeter-provider/0/fabric-endpoint"))
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(405, response.statusCode());
  }
}
