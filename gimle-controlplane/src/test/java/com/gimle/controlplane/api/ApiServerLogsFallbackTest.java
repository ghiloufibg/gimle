package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.muninn.MuninnClient;
import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
  private Path caFile;

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
    System.clearProperty("gimle.transport.protocol");
    System.clearProperty("gimle.tls.certFile");
    System.clearProperty("gimle.tls.keyFile");
    System.clearProperty("gimle.tls.caFile");
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

  /**
   * A {@code ConnectException} for a genuinely refused connection carries a real message on every
   * JVM/OS this runs on, so the fallback-endpoint tests above never actually exercise a
   * message-less failure. This exercises {@link ApiServer#describeMuninnFailure} directly instead
   * -- a class of failure the JDK HTTP client really does raise with no message at all (a dropped
   * connection mid-response, a bare {@code ClosedChannelException}), which the un-fixed code
   * rendered as the literal, meaningless text "muninn unreachable: null".
   */
  @Test
  void a_message_less_failure_falls_back_to_the_exception_class_name_not_the_word_null() {
    String described = ApiServer.describeMuninnFailure(new ClosedChannelException());

    assertFalse(described.contains("null"), described);
    assertTrue(described.contains("ClosedChannelException"), described);
  }

  @Test
  void a_message_less_failure_falls_through_to_the_first_cause_that_has_one() {
    Exception withMessage = new IOException("connection reset by peer");
    Exception wrapper = new ClosedChannelException();
    wrapper.initCause(withMessage);

    String described = ApiServer.describeMuninnFailure(wrapper);

    assertTrue(described.contains("connection reset by peer"), described);
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
   * QA finding: {@code gimle logs ... --follow} against a node/instance whose supervising agent is
   * genuinely down used to hang forever -- {@code proxyFollowToAgent} committed to a chunked 200
   * response before ever attempting the connection, so a stopped agent's connection failure (caught
   * only after the fact, and only logged at DEBUG) left the caller with an open, silent connection
   * and no way to tell a hung follow from a healthy, quiet one. The registered node's own
   * log-server address here is a real port nothing listens on (the same {@code 127.0.0.1:1} trick
   * the plain unreachable-agent test above uses), and the instance itself carries a real placement
   * so this request reaches the follow branch at all. {@link Timeout} makes "must not hang" an
   * enforced fact of this test, not just a hope.
   */
  @Test
  @Timeout(15)
  void follow_true_against_an_unreachable_agent_falls_back_to_muninn_instead_of_hanging()
      throws Exception {
    muninnStub = startStub("/logs", muninnReceivedPaths, 200);
    startApiServer(new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort()));
    store.putNodeRegistration(
        new NodeRegistration("node-a", new NodeCapabilities(Set.of()), Optional.of("127.0.0.1:1")));
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/tmp/orders.jar",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    HttpResponse<String> response = send("/logs/instances/orders-service/0?follow=true");

    assertEquals(200, response.statusCode());
    assertEquals(1, muninnReceivedPaths.size());
  }

  /**
   * The other half of the same fix: with no Muninn configured to fall back to, an unreachable agent
   * must still fail fast with a clear reason rather than ever committing to a hanging 200.
   */
  @Test
  @Timeout(15)
  void follow_true_against_an_unreachable_agent_fails_fast_with_no_muninn_configured()
      throws Exception {
    startApiServer(null);
    store.putNodeRegistration(
        new NodeRegistration("node-a", new NodeCapabilities(Set.of()), Optional.of("127.0.0.1:1")));
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/tmp/orders.jar",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));

    HttpResponse<String> response = send("/logs/instances/orders-service/0?follow=true");

    assertEquals(502, response.statusCode());
    assertTrue(response.body().contains("unreachable"), response.body());
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

  /**
   * QA finding: {@code /logs/instances/{name}/{index}} used to resolve its tenant via {@code
   * workloadTenantHint}, which defaults a missing {@code ?tenant=} straight to the untenanted
   * namespace rather than searching for the name the way {@code /endpoints/{name}} already does --
   * a genuinely {@code ACTIVE} instance whose owning tenant wasn't literally the untenanted
   * namespace 404'd on this live path forever, workable only through Muninn's own fallback store.
   * With no {@code ?tenant=} at all, this must still resolve by searching for whichever tenant owns
   * a workload named {@code orders-statefulset}.
   */
  @Test
  void an_instance_in_a_non_default_tenant_resolves_with_no_tenant_flag_at_all() throws Exception {
    List<String> agentReceivedPaths = new CopyOnWriteArrayList<>();
    agentStub = startStub("/logs", agentReceivedPaths, 200);
    startApiServer(null);
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            Optional.of("127.0.0.1:" + agentStub.getAddress().getPort())));
    store.putStatefulSetSpec(
        new StatefulSetSpec(
            "orders-statefulset",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders.jar",
            1,
            PlacementConstraints.NONE,
            Optional.of("acme"),
            Optional.empty(),
            Optional.empty()));
    store.putStatefulSetAssignment(
        new StatefulSetAssignment(
            "orders-statefulset",
            0,
            "node-a",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders.jar",
            Optional.of("acme")));

    HttpResponse<String> response = send("/logs/instances/orders-statefulset/0");

    assertEquals(200, response.statusCode());
    assertEquals(1, agentReceivedPaths.size());
  }

  /**
   * The other half of the same fix: two tenants genuinely sharing {@code orders-statefulset} must
   * never have one silently picked over the other for a bare, untenanted read -- an honest 400
   * telling the caller to disambiguate, not a coin flip.
   */
  @Test
  void an_ambiguous_instance_name_across_two_tenants_is_a_400_not_a_silent_pick() throws Exception {
    startApiServer(null);
    store.putStatefulSetSpec(
        new StatefulSetSpec(
            "orders-statefulset",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders.jar",
            1,
            PlacementConstraints.NONE,
            Optional.of("acme"),
            Optional.empty(),
            Optional.empty()));
    store.putStatefulSetSpec(
        new StatefulSetSpec(
            "orders-statefulset",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders.jar",
            1,
            PlacementConstraints.NONE,
            Optional.of("globex"),
            Optional.empty(),
            Optional.empty()));

    HttpResponse<String> response = send("/logs/instances/orders-statefulset/0");

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("ambiguous"), response.body());
    assertTrue(response.body().contains("?tenant="), response.body());
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

  /**
   * Bug 30 regression, the {@code /logs/*} fallback's own half: unlike {@code
   * ApiServerMetricsHistoryTest}/{@code ApiServerTracesHistoryTest}, this class had no mTLS
   * coverage at all before this test -- every case above runs in plaintext, where {@code
   * requireAuthorized} never even calls {@code resolvePrincipal} (see its own {@code !(exchange
   * instanceof HttpsExchange)} branch), so none of them could have caught a caller identity that
   * failed to reach Muninn. A cert-authenticated operator who genuinely holds {@code LOGS}
   * permission must have their real identity, not this control plane's own leaf, reach Muninn as
   * {@code X-Gimle-Forwarded-Principal}/{@code X-Gimle-Forwarded-Groups} once a node has no
   * registration at all -- {@link #handleNodeLogsProxy}'s emptiest fallback branch.
   */
  @Test
  @Timeout(15)
  void a_cert_authenticated_caller_has_their_identity_forwarded_for_a_node_with_no_registration()
      throws Exception {
    List<com.sun.net.httpserver.Headers> receivedHeaders = new CopyOnWriteArrayList<>();
    muninnStub = startHeaderCapturingMuninnStub(receivedHeaders);
    MuninnClient muninnClient = new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort());
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    restartStoresUnderTls();
    store.putRole(new Role("logs-only", Set.of(Permission.unscoped(ResourceKind.LOGS, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("logs-operator"), "logs-only"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client(), muninnClient);
    server.start();
    HttpClient operatorClient = mutualTlsClient(ca, "CN=logs-operator");

    HttpResponse<String> response =
        operatorClient.send(
            HttpRequest.newBuilder(
                    URI.create("https://localhost:" + server.port() + "/logs/nodes/ghost"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, receivedHeaders.size(), "the request must have actually reached muninn");
    assertEquals("logs-operator", receivedHeaders.get(0).getFirst("X-Gimle-Forwarded-Principal"));
  }

  /**
   * As above, for {@link #handleNodeLogsProxy}'s other fallback branch: a node that is registered
   * but whose agent genuinely cannot be reached, matching the QA-reported "node is genuinely gone"
   * wording most closely.
   */
  @Test
  @Timeout(15)
  void a_cert_authenticated_caller_has_their_identity_forwarded_for_an_unreachable_agent()
      throws Exception {
    List<com.sun.net.httpserver.Headers> receivedHeaders = new CopyOnWriteArrayList<>();
    muninnStub = startHeaderCapturingMuninnStub(receivedHeaders);
    MuninnClient muninnClient = new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort());
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    restartStoresUnderTls();
    store.putRole(new Role("logs-only", Set.of(Permission.unscoped(ResourceKind.LOGS, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("logs-operator"), "logs-only"));
    store.putNodeRegistration(
        new NodeRegistration(
            "node-a",
            new NodeCapabilities(Set.of()),
            // A registered agent address that nothing is actually listening on -- the real proxy
            // call itself must fail, not just an empty registration.
            Optional.of("127.0.0.1:1")));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client(), muninnClient);
    server.start();
    HttpClient operatorClient = mutualTlsClient(ca, "CN=logs-operator");

    HttpResponse<String> response =
        operatorClient.send(
            HttpRequest.newBuilder(
                    URI.create("https://localhost:" + server.port() + "/logs/nodes/node-a"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, receivedHeaders.size(), "the request must have actually reached muninn");
    assertEquals("logs-operator", receivedHeaders.get(0).getFirst("X-Gimle-Forwarded-Principal"));
  }

  /**
   * The {@link #handleInstanceLogsProxy} counterpart: an instance with no placement at all (a name
   * only Muninn still remembers), reached without ever calling {@link #resolveInstanceNodeId} down
   * to a live agent.
   */
  @Test
  @Timeout(15)
  void a_cert_authenticated_caller_has_their_identity_forwarded_for_an_instance_with_no_placement()
      throws Exception {
    List<com.sun.net.httpserver.Headers> receivedHeaders = new CopyOnWriteArrayList<>();
    muninnStub = startHeaderCapturingMuninnStub(receivedHeaders);
    MuninnClient muninnClient = new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort());
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    restartStoresUnderTls();
    store.putRole(new Role("logs-only", Set.of(Permission.unscoped(ResourceKind.LOGS, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("logs-operator"), "logs-only"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client(), muninnClient);
    server.start();
    HttpClient operatorClient = mutualTlsClient(ca, "CN=logs-operator");

    HttpResponse<String> response =
        operatorClient.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "https://localhost:"
                            + server.port()
                            + "/logs/instances/ghost-deployment/0"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, receivedHeaders.size(), "the request must have actually reached muninn");
    assertEquals("logs-operator", receivedHeaders.get(0).getFirst("X-Gimle-Forwarded-Principal"));
  }

  /**
   * The {@link #handleInstanceLogsProxy} counterpart to the registered-but-unreachable node case: a
   * real placement exists, but the node hosting it can't actually be reached.
   */
  @Test
  @Timeout(15)
  void a_cert_authenticated_caller_has_their_identity_forwarded_for_an_instance_on_a_dead_node()
      throws Exception {
    List<com.sun.net.httpserver.Headers> receivedHeaders = new CopyOnWriteArrayList<>();
    muninnStub = startHeaderCapturingMuninnStub(receivedHeaders);
    MuninnClient muninnClient = new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort());
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    restartStoresUnderTls();
    store.putRole(new Role("logs-only", Set.of(Permission.unscoped(ResourceKind.LOGS, Verb.READ))));
    store.putRoleBinding(
        new RoleBinding("b1", RoleBinding.userSubject("logs-operator"), "logs-only"));
    store.putNodeRegistration(
        new NodeRegistration("node-a", new NodeCapabilities(Set.of()), Optional.of("127.0.0.1:1")));
    store.putAssignment(
        new InstanceAssignment(
            "orders-service",
            0,
            "node-a",
            new ModuleId("com.example.orders", Version.parse("1.0.0")),
            "/tmp/orders.jar",
            OptionalInt.empty(),
            Optional.of(Tenant.DEFAULT_TENANT_ID)));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client(), muninnClient);
    server.start();
    HttpClient operatorClient = mutualTlsClient(ca, "CN=logs-operator");

    HttpResponse<String> response =
        operatorClient.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "https://localhost:" + server.port() + "/logs/instances/orders-service/0"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, receivedHeaders.size(), "the request must have actually reached muninn");
    assertEquals("logs-operator", receivedHeaders.get(0).getFirst("X-Gimle-Forwarded-Principal"));
  }

  /**
   * A group-bound caller (no direct user grant) must have its groups, not just its name, forwarded.
   */
  @Test
  @Timeout(15)
  void a_group_bound_cert_caller_has_their_groups_forwarded_to_the_fallback() throws Exception {
    List<com.sun.net.httpserver.Headers> receivedHeaders = new CopyOnWriteArrayList<>();
    muninnStub = startHeaderCapturingMuninnStub(receivedHeaders);
    MuninnClient muninnClient = new MuninnClient("127.0.0.1:" + muninnStub.getAddress().getPort());
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    restartStoresUnderTls();
    store.putRole(new Role("logs-only", Set.of(Permission.unscoped(ResourceKind.LOGS, Verb.READ))));
    store.putRoleBinding(new RoleBinding("b1", RoleBinding.groupSubject("sre-team"), "logs-only"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client(), muninnClient);
    server.start();
    HttpClient operatorClient = mutualTlsClient(ca, "O=sre-team,CN=sre-caller");

    HttpResponse<String> response =
        operatorClient.send(
            HttpRequest.newBuilder(
                    URI.create("https://localhost:" + server.port() + "/logs/nodes/ghost"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, receivedHeaders.size(), "the request must have actually reached muninn");
    com.sun.net.httpserver.Headers forwarded = receivedHeaders.get(0);
    assertEquals("sre-caller", forwarded.getFirst("X-Gimle-Forwarded-Principal"));
    assertEquals("sre-team", forwarded.getFirst("X-Gimle-Forwarded-Groups"));
  }

  /**
   * {@code startStores} (the {@code @BeforeEach} above) already opened a plaintext {@code
   * InProcessStore}/{@code InProcessFafnir} pair before any test method runs, and {@code
   * configureServerTls} only flips {@code gimle.transport.protocol} to {@code tls} afterwards --
   * reusing that plaintext pair would have this class's own {@code ApiServer} construction (which
   * reads a tenant from the store to seed it) fail its own TLS handshake against a store that never
   * came up expecting one. Every mTLS test in this class calls this right after {@code
   * configureServerTls} to get a store pair that actually matches the transport it now declares.
   */
  private void restartStoresUnderTls() throws IOException {
    inProcessFafnir.close();
    inProcessStore.close();
    inProcessStore = InProcessStore.start(tempDir.resolve("store-mtls"));
    store = inProcessStore.store();
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret-mtls.key"));
  }

  private HttpServer startHeaderCapturingMuninnStub(
      List<com.sun.net.httpserver.Headers> receivedHeaders) throws IOException {
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/logs",
        exchange -> {
          // A fresh copy, not the live exchange.getRequestHeaders() reference -- the exchange
          // itself is recycled once this handler returns.
          com.sun.net.httpserver.Headers copy = new com.sun.net.httpserver.Headers();
          copy.putAll(exchange.getRequestHeaders());
          receivedHeaders.add(copy);
          byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    stub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    stub.start();
    return stub;
  }

  private HttpClient mutualTlsClient(CertificateAuthority ca, String subject) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name(subject));
    String safeName = subject.replaceAll("[^a-zA-Z0-9]", "_");
    Path certFile =
        writePem(
            safeName + "-cert.pem",
            Pem.encodeCertificate(ca.signCertificateRequest(csr, Duration.ofDays(1))));
    Path keyFile = writePem(safeName + "-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    TlsSettings settings = new TlsSettings(certFile, keyFile, caFile);
    return HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(settings)).build();
  }

  private void configureServerTls(CertificateAuthority ca) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("O=gimle:controlplane,CN=controlplane"), List.of("localhost"));
    Path certFile =
        writePem(
            "controlplane-cert.pem",
            Pem.encodeCertificate(ca.signCertificateRequest(csr, Duration.ofDays(1))));
    Path keyFile = writePem("controlplane-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    caFile = writePem("test-ca.pem", Pem.encodeCertificate(ca.certificate()));

    System.setProperty("gimle.transport.protocol", "tls");
    System.setProperty("gimle.tls.certFile", certFile.toString());
    System.setProperty("gimle.tls.keyFile", keyFile.toString());
    System.setProperty("gimle.tls.caFile", caFile.toString());
  }

  private Path writePem(String fileName, String pem) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, pem);
    return path;
  }

  private static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
