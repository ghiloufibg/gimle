package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.store.StateStore;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code ApiServer}'s {@code /logs/*} Muninn fallback (design doc Part B/O-11): a gone node or
 * instance -- no registration, a registered node whose agent genuinely can't be reached -- falls
 * through to Muninn's own shipped history instead of a bare 404/502, whenever a {@link
 * MuninnClient} is actually configured. Mirrors {@code ApiServerTest}'s own real-loopback-HTTP
 * setup shape, plus a stub {@code HttpServer} standing in for both Muninn and a live agent.
 */
@ResourceLock("gimle-controlplane-api-server-http")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ApiServerLogsFallbackTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private StateStore store;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;
  private HttpServer muninnStub;
  private HttpServer agentStub;
  private final List<String> muninnReceivedPaths = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startStores() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    store = inProcessStore.store();
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopEverything() {
    if (server != null) {
      server.close();
    }
    if (muninnStub != null) {
      muninnStub.stop(0);
    }
    if (agentStub != null) {
      agentStub.stop(0);
    }
    inProcessFafnir.close();
    inProcessStore.close();
  }

  private void startApiServer(MuninnClient muninnClient) throws IOException {
    server =
        muninnClient == null
            ? new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client())
            : new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client(), muninnClient);
    server.start();
    baseUrl = "http://localhost:" + server.port();
  }

  private HttpServer startStub(String contextPath, List<String> receivedPaths, int statusCode)
      throws IOException {
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        contextPath,
        exchange -> {
          receivedPaths.add(exchange.getRequestURI().getPath());
          byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(statusCode, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    stub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    stub.start();
    return stub;
  }

  private HttpResponse<String> send(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void a_node_with_no_registration_falls_through_to_muninn_when_configured() throws Exception {
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    startApiServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));

    HttpResponse<String> response = send("/logs/nodes/ghost");

    assertEquals(200, response.statusCode());
    assertEquals(1, muninnReceivedPaths.size());
    assertTrue(muninnReceivedPaths.get(0).startsWith("/logs/nodes/ghost/PLATFORM"));
  }

  @Test
  void a_registered_but_unreachable_agent_falls_through_to_muninn_when_configured()
      throws Exception {
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    startApiServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            // A registered agent address that nothing is actually listening on -- the real proxy
            // call itself must fail, not just an empty registration.
            Optional.of("127.0.0.1:1")));

    HttpResponse<String> response = send("/logs/nodes/node-a");

    assertEquals(200, response.statusCode());
    assertEquals(1, muninnReceivedPaths.size());
    assertTrue(muninnReceivedPaths.get(0).startsWith("/logs/nodes/node-a/PLATFORM"));
  }

  @Test
  void a_live_reachable_agent_is_still_served_directly_not_from_muninn() throws Exception {
    List<String> agentReceivedPaths = new CopyOnWriteArrayList<>();
    agentStub = startStub("/logs", agentReceivedPaths, 200);
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    startApiServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));

    HttpResponse<String> response = send("/logs/nodes/node-a");

    assertEquals(200, response.statusCode());
    assertEquals(1, agentReceivedPaths.size());
    assertTrue(muninnReceivedPaths.isEmpty(), "muninn should not have been consulted");
  }

  @Test
  void a_missing_instance_placement_falls_through_to_muninn_when_configured() throws Exception {
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    startApiServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));

    HttpResponse<String> response = send("/logs/instances/ghost-deployment/0");

    assertEquals(200, response.statusCode());
    assertEquals(1, muninnReceivedPaths.size());
    assertTrue(
        muninnReceivedPaths.get(0).startsWith("/logs/instances/ghost-deployment/0/APPLICATION"));
  }

  @Test
  void a_node_with_no_registration_returns_plain_404_when_no_muninn_configured() throws Exception {
    startApiServer(null);

    HttpResponse<String> response = send("/logs/nodes/ghost");

    assertEquals(404, response.statusCode());
    assertTrue(response.body().contains("unknown node: ghost"));
  }

  @Test
  void follow_true_reaching_the_fallback_is_dropped_rather_than_erroring() throws Exception {
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    startApiServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));

    HttpResponse<String> response = send("/logs/nodes/ghost?follow=true");

    assertEquals(200, response.statusCode());
    assertEquals(1, muninnReceivedPaths.size());
    // follow is stripped from the forwarded query, not passed through to Muninn's read surface.
    assertTrue(!muninnReceivedPaths.get(0).contains("follow"));
  }
}
