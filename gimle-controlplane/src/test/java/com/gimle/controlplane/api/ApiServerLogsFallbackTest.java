package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.JobRun;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * {@code ApiServer}'s {@code /logs/*} surface: the Muninn fallback (a gone node or instance -- no
 * registration, a registered node whose agent genuinely can't be reached -- falls through to
 * Muninn's own shipped history instead of a bare 404/502, whenever a {@link MuninnClient} is
 * actually configured), plus the {@code level}/{@code contains} content filter reaching the live
 * agent, the fallback, and this process's own platform log alike. Mirrors {@code ApiServerTest}'s
 * own real-loopback-HTTP setup shape, plus a stub {@code HttpServer} standing in for both Muninn
 * and a live agent.
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
  private String previousLogRoot;

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
    if (previousLogRoot == null) {
      System.clearProperty("gimle.log.root");
    } else {
      System.setProperty("gimle.log.root", previousLogRoot);
    }
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

  /**
   * {@code receivedPaths} records each request's full path <i>and</i> query, not just its path:
   * what a filtered log read forwards downstream lives entirely in the query string, so recording
   * the path alone would make every assertion about it vacuously true.
   */
  private HttpServer startStub(String contextPath, List<String> receivedPaths, int statusCode)
      throws IOException {
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        contextPath,
        exchange -> {
          receivedPaths.add(exchange.getRequestURI().toString());
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
  void a_muninn_fallback_fails_over_to_a_second_configured_endpoint_when_the_first_is_unreachable()
      throws Exception {
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    // 127.0.0.1:1 is a privileged, never-listening port -- connection refused every time, a
    // deterministic stand-in for "this Muninn replica is down" (the same trick the agent's own
    // unreachable-node test above uses).
    startApiServer(
        new MuninnClient(List.of("127.0.0.1:1", "127.0.0.1:" + muninnStub.getAddress().getPort())));

    HttpResponse<String> response = send("/logs/nodes/ghost");

    assertEquals(200, response.statusCode());
    assertEquals(1, muninnReceivedPaths.size());
    assertTrue(muninnReceivedPaths.get(0).startsWith("/logs/nodes/ghost/PLATFORM"));
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

  /**
   * QA end-user-QA finding: {@code /logs/instances/{name}/{index}} used to resolve placement
   * exclusively via {@code storeClient.listAssignmentsFor}, which only Deployment-kind bookkeeping
   * ever populates -- a StatefulSet/DaemonSet/Job-owned instance 404'd forever, even genuinely
   * {@code ACTIVE} on a live, reachable agent. These three prove each of the non-Deployment kinds
   * now resolves through the same live-agent path {@code
   * a_live_reachable_agent_is_still_served_...} above already proves for Deployment.
   */
  @Test
  void a_statefulset_owned_instance_resolves_to_its_real_placement() throws Exception {
    List<String> agentReceivedPaths = new CopyOnWriteArrayList<>();
    agentStub = startStub("/logs", agentReceivedPaths, 200);
    startApiServer(null);
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));
    store.putStatefulSetAssignment(
        new StatefulSetAssignment(
            "orders-statefulset",
            0,
            "node-a",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders.jar",
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    HttpResponse<String> response = send("/logs/instances/orders-statefulset/0");

    assertEquals(200, response.statusCode());
    assertEquals(1, agentReceivedPaths.size());
  }

  @Test
  void a_daemonset_owned_instance_resolves_to_its_real_placement() throws Exception {
    List<String> agentReceivedPaths = new CopyOnWriteArrayList<>();
    agentStub = startStub("/logs", agentReceivedPaths, 200);
    startApiServer(null);
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));
    store.putDaemonSetAssignment(
        new DaemonSetAssignment(
            "flag-cache-daemonset",
            "node-a",
            new ModuleId("com.example.flagcache", Version.parse("1.0.0")),
            "/artifacts/flag-cache.jar",
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    // A DaemonSet instance's own index is always 0 -- see DaemonSetAssignment's own javadoc.
    HttpResponse<String> response = send("/logs/instances/flag-cache-daemonset/0");

    assertEquals(200, response.statusCode());
    assertEquals(1, agentReceivedPaths.size());
  }

  @Test
  void a_job_owned_instance_resolves_to_its_real_placement() throws Exception {
    List<String> agentReceivedPaths = new CopyOnWriteArrayList<>();
    agentStub = startStub("/logs", agentReceivedPaths, 200);
    startApiServer(null);
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));
    store.putJobRun(
        new JobRun(
            "orders-report-job",
            0,
            "node-a",
            new ModuleId("com.example.reporting", Version.parse("1.0.0")),
            "/artifacts/reporting.jar",
            Instant.now(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    // A Job run's own "index" is its attempt number.
    HttpResponse<String> response = send("/logs/instances/orders-report-job/0");

    assertEquals(200, response.statusCode());
    assertEquals(1, agentReceivedPaths.size());
  }

  @Test
  void a_level_and_text_filter_reach_a_live_agent_verbatim() throws Exception {
    List<String> agentReceivedPaths = new CopyOnWriteArrayList<>();
    agentStub = startStub("/logs", agentReceivedPaths, 200);
    startApiServer(null);
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));

    HttpResponse<String> response = send("/logs/nodes/node-a?level=WARN&contains=timed+out");

    assertEquals(200, response.statusCode());
    assertEquals(1, agentReceivedPaths.size());
    assertTrue(agentReceivedPaths.get(0).contains("level=WARN"), agentReceivedPaths.get(0));
    assertTrue(agentReceivedPaths.get(0).contains("contains=timed"), agentReceivedPaths.get(0));
  }

  @Test
  void the_same_filter_reaches_the_muninn_fallback_for_a_gone_node() throws Exception {
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    startApiServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));

    HttpResponse<String> response = send("/logs/nodes/ghost?level=WARN&contains=timed+out");

    assertEquals(200, response.statusCode());
    assertEquals(1, muninnReceivedPaths.size());
    String forwarded = muninnReceivedPaths.get(0);
    // The category becomes a path segment on Muninn's own read surface, but the content filter is
    // relayed untouched -- an operator gets the same lines whether or not the node still exists.
    assertTrue(forwarded.startsWith("/logs/nodes/ghost/PLATFORM"), forwarded);
    assertTrue(forwarded.contains("level=WARN"), forwarded);
    assertTrue(forwarded.contains("contains=timed"), forwarded);
  }

  @Test
  void a_filtered_instance_read_forwards_the_filter_to_the_muninn_fallback_too() throws Exception {
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    startApiServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));

    HttpResponse<String> response =
        send("/logs/instances/ghost-deployment/0?level=ERROR&contains=boom");

    assertEquals(200, response.statusCode());
    String forwarded = muninnReceivedPaths.get(0);
    assertTrue(forwarded.startsWith("/logs/instances/ghost-deployment/0/APPLICATION"), forwarded);
    assertTrue(forwarded.contains("level=ERROR"), forwarded);
    assertTrue(forwarded.contains("contains=boom"), forwarded);
  }

  /**
   * The control plane serves its own platform log directly rather than proxying it, so the filter
   * has to be applied by this process too -- the same {@code LogFileReader} call the agent makes.
   */
  private void writeControlPlaneLog() throws IOException {
    previousLogRoot = System.getProperty("gimle.log.root");
    Path logRoot = tempDir.resolve("cp-logs");
    Files.createDirectories(logRoot);
    String lines =
        String.join(
                "\n",
                logLine("2026-08-10T10:00:00Z", "DEBUG", "cache warmed"),
                logLine("2026-08-10T10:00:01Z", "INFO", "api server listening"),
                logLine("2026-08-10T10:00:02Z", "WARN", "store leader election in progress"),
                logLine("2026-08-10T10:00:03Z", "ERROR", "downstream call timed out"))
            + "\n";
    Files.writeString(logRoot.resolve("controlplane-platform.log"), lines, StandardCharsets.UTF_8);
    System.setProperty("gimle.log.root", logRoot.toString());
  }

  private static String logLine(String timestamp, String level, String message) {
    return Json.write(
        Map.of(
            "timestamp",
            timestamp,
            "level",
            level,
            "logger",
            "com.gimle.controlplane.api.ApiServer",
            "message",
            message));
  }

  private static List<String> messagesOf(HttpResponse<String> response) {
    return Json.asObjectList(Json.asObject(Json.parse(response.body())).get("lines")).stream()
        .map(l -> String.valueOf(l.get("message")))
        .toList();
  }

  @Test
  void the_control_planes_own_log_applies_the_level_threshold_itself() throws Exception {
    writeControlPlaneLog();
    startApiServer(null);

    HttpResponse<String> response = send("/logs/controlplane?level=WARN");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        List.of("store leader election in progress", "downstream call timed out"),
        messagesOf(response));
  }

  @Test
  void the_control_planes_own_log_applies_a_text_filter_alongside_the_since_cursor()
      throws Exception {
    writeControlPlaneLog();
    startApiServer(null);

    HttpResponse<String> response =
        send("/logs/controlplane?since=2026-08-10T10:00:01Z&contains=TIMED+OUT");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(List.of("downstream call timed out"), messagesOf(response));
  }

  @Test
  void a_zero_match_filter_on_the_control_planes_own_log_is_an_empty_page_not_an_error()
      throws Exception {
    writeControlPlaneLog();
    startApiServer(null);

    HttpResponse<String> response = send("/logs/controlplane?contains=no+such+text+anywhere");

    assertEquals(200, response.statusCode(), response.body());
    assertTrue(messagesOf(response).isEmpty());
  }

  @Test
  void an_unrecognized_level_on_the_control_planes_own_log_is_a_400() throws Exception {
    writeControlPlaneLog();
    startApiServer(null);

    HttpResponse<String> response = send("/logs/controlplane?level=SEVERE");

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("SEVERE"), response.body());
  }
}
