package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.ControlMessageCodec;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link AgentMain#readLoop}'s handling of a worker-relayed {@code RelayResourceStatusPut}: an
 * untenanted instance is refused locally (no anonymous status reporting), malformed typed fields
 * are refused locally before any token mint or real call, and the happy path mints a workload token
 * and issues the real {@code PUT /resources/{kind}/{name}/status} under it -- with a failed mint
 * synthesized as a {@code 502} rather than a bare relay. Each test uses its own deployment name:
 * the agent's workload-token cache is static, keyed by {@code deploymentName#nodeId}, so a shared
 * name would let one test's minted (or failed) token leak into another's.
 */
class AgentRelayStatusPutTest {

  private HttpServer controlPlaneStub;

  @AfterEach
  void tearDown() {
    if (controlPlaneStub != null) {
      controlPlaneStub.stop(0);
    }
  }

  /** One observed request against the control-plane stub. */
  private record ObservedRequest(String method, String uri, String authorization, String body) {}

  /**
   * A stub that mints workload tokens ({@code mintStatus 200} answers a real-looking token) and
   * answers every other request with {@code status}/{@code body}, recording what it saw.
   */
  private HttpServer startControlPlaneStub(
      int mintStatus,
      AtomicInteger mintCount,
      List<ObservedRequest> observed,
      int status,
      String body)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] requestBody;
          try (InputStream in = exchange.getRequestBody()) {
            requestBody = in.readAllBytes();
          }
          byte[] response;
          if ("/workload-tokens".equals(exchange.getRequestURI().getPath())) {
            mintCount.incrementAndGet();
            long expires = System.currentTimeMillis() + Duration.ofHours(1).toMillis();
            response =
                ("{\"token\":\"tok-test\",\"expiresAtEpochMilli\":" + expires + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(mintStatus, response.length);
          } else {
            observed.add(
                new ObservedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(requestBody, StandardCharsets.UTF_8)));
            response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
          }
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  private static AssignedInstance assignedInstance(String deploymentName, Optional<String> tenant) {
    return new AssignedInstance(
        deploymentName, 0, new ModuleId("op", Version.parse("1.0.0")), "/no/matter.jar", tenant);
  }

  private static ControlMessage.RelayResourceStatusPut statusPut(
      String correlationId, String tenantId) {
    return new ControlMessage.RelayResourceStatusPut(
        correlationId, "custom.Greeting", tenantId, "hello", "{\"timesSaid\":3}");
  }

  @Test
  @Timeout(15)
  void an_untenanted_instance_is_refused_locally_with_a_403() throws Exception {
    AtomicInteger mintCount = new AtomicInteger();
    List<ObservedRequest> observed = new CopyOnWriteArrayList<>();
    controlPlaneStub = startControlPlaneStub(200, mintCount, observed, 200, "{}");
    URI baseUrl = URI.create("http://127.0.0.1:" + controlPlaneStub.getAddress().getPort());

    RelayHarness harness =
        RelayHarness.start(baseUrl, assignedInstance("status-untenanted", Optional.empty()));
    try {
      harness.sendRaw(statusPut("corr-1", "acme"));
      ControlMessage.RelayControlPlaneResult result = harness.receiveResult();

      assertEquals("corr-1", result.correlationId());
      assertEquals(403, result.status());
      assertTrue(result.body().contains("untenanted"));
      assertEquals(0, mintCount.get(), "no token must be minted for a refused put");
      assertTrue(observed.isEmpty(), "nothing must reach the control plane");
    } finally {
      harness.close();
    }
  }

  @Test
  @Timeout(15)
  void a_malformed_field_is_refused_locally_before_any_mint_or_call() throws Exception {
    AtomicInteger mintCount = new AtomicInteger();
    List<ObservedRequest> observed = new CopyOnWriteArrayList<>();
    controlPlaneStub = startControlPlaneStub(200, mintCount, observed, 200, "{}");
    URI baseUrl = URI.create("http://127.0.0.1:" + controlPlaneStub.getAddress().getPort());

    RelayHarness harness =
        RelayHarness.start(baseUrl, assignedInstance("status-malformed", Optional.of("acme")));
    try {
      // Each variant smuggles path structure through one typed field; all must die locally.
      List<ControlMessage.RelayResourceStatusPut> malformed =
          List.of(
              new ControlMessage.RelayResourceStatusPut(
                  "corr-2", "custom.Greeting/../secrets", "acme", "hello", "{}"),
              new ControlMessage.RelayResourceStatusPut(
                  "corr-3", "custom.Greeting", "acme", "..", "{}"),
              new ControlMessage.RelayResourceStatusPut(
                  "corr-4", "custom.Greeting", "acme?admin=true", "hello", "{}"),
              new ControlMessage.RelayResourceStatusPut("corr-5", "", "acme", "hello", "{}"));
      for (ControlMessage.RelayResourceStatusPut put : malformed) {
        harness.sendRaw(put);
        ControlMessage.RelayControlPlaneResult result = harness.receiveResult();
        assertEquals(put.correlationId(), result.correlationId());
        assertEquals(400, result.status(), "must reject: " + put);
      }
      assertEquals(0, mintCount.get());
      assertTrue(observed.isEmpty());
    } finally {
      harness.close();
    }
  }

  @Test
  @Timeout(15)
  void a_tenanted_put_mints_a_token_and_issues_the_real_status_request_under_it() throws Exception {
    AtomicInteger mintCount = new AtomicInteger();
    List<ObservedRequest> observed = new CopyOnWriteArrayList<>();
    controlPlaneStub = startControlPlaneStub(200, mintCount, observed, 200, "{\"ok\":true}");
    URI baseUrl = URI.create("http://127.0.0.1:" + controlPlaneStub.getAddress().getPort());

    RelayHarness harness =
        RelayHarness.start(baseUrl, assignedInstance("status-happy", Optional.of("acme")));
    try {
      harness.sendRaw(statusPut("corr-6", "acme"));
      ControlMessage.RelayControlPlaneResult result = harness.receiveResult();

      assertEquals("corr-6", result.correlationId());
      assertEquals(200, result.status());
      assertEquals("{\"ok\":true}", result.body());
      assertEquals(1, mintCount.get());
      assertEquals(1, observed.size());
      ObservedRequest request = observed.get(0);
      assertEquals("PUT", request.method());
      assertEquals("/resources/custom.Greeting/hello/status?tenant=acme", request.uri());
      assertEquals("Bearer tok-test", request.authorization());
      assertEquals("{\"timesSaid\":3}", request.body());
    } finally {
      harness.close();
    }
  }

  @Test
  @Timeout(15)
  void a_cluster_scoped_put_carries_no_tenant_query_parameter() throws Exception {
    AtomicInteger mintCount = new AtomicInteger();
    List<ObservedRequest> observed = new CopyOnWriteArrayList<>();
    controlPlaneStub = startControlPlaneStub(200, mintCount, observed, 200, "{}");
    URI baseUrl = URI.create("http://127.0.0.1:" + controlPlaneStub.getAddress().getPort());

    RelayHarness harness =
        RelayHarness.start(baseUrl, assignedInstance("status-cluster", Optional.of("acme")));
    try {
      harness.sendRaw(statusPut("corr-7", ""));
      ControlMessage.RelayControlPlaneResult result = harness.receiveResult();

      assertEquals(200, result.status());
      assertEquals(1, observed.size());
      assertEquals("/resources/custom.Greeting/hello/status", observed.get(0).uri());
    } finally {
      harness.close();
    }
  }

  @Test
  @Timeout(15)
  void a_failed_token_mint_is_synthesized_as_a_502_and_the_put_never_goes_out() throws Exception {
    AtomicInteger mintCount = new AtomicInteger();
    List<ObservedRequest> observed = new CopyOnWriteArrayList<>();
    controlPlaneStub = startControlPlaneStub(500, mintCount, observed, 200, "{}");
    URI baseUrl = URI.create("http://127.0.0.1:" + controlPlaneStub.getAddress().getPort());

    RelayHarness harness =
        RelayHarness.start(baseUrl, assignedInstance("status-mint-fail", Optional.of("acme")));
    try {
      harness.sendRaw(statusPut("corr-8", "acme"));
      ControlMessage.RelayControlPlaneResult result = harness.receiveResult();

      assertEquals("corr-8", result.correlationId());
      assertEquals(502, result.status());
      assertTrue(result.body().contains("no workload identity"));
      assertEquals(1, mintCount.get(), "the mint must have been attempted");
      assertTrue(observed.isEmpty(), "the status put itself must never go out without a token");
    } finally {
      harness.close();
    }
  }

  /**
   * The same one-{@code readLoop}-against-a-raw-worker-socket wiring {@code
   * AgentRelayControlPlaneReadTest.RelayHarness} establishes, with the {@link AssignedInstance}
   * parameterized so each test controls its own tenancy and (cache-isolating) deployment name.
   */
  private static final class RelayHarness implements AutoCloseable {
    private final ControlChannelServer server;
    private final SocketChannel workerSocket;
    private final WorkerConnection connection;
    private final BufferedReader workerIn;
    private final Thread readerThread;

    private RelayHarness(
        ControlChannelServer server,
        SocketChannel workerSocket,
        WorkerConnection connection,
        BufferedReader workerIn,
        Thread readerThread) {
      this.server = server;
      this.workerSocket = workerSocket;
      this.connection = connection;
      this.workerIn = workerIn;
      this.readerThread = readerThread;
    }

    static RelayHarness start(URI baseUrl, AssignedInstance assigned) throws IOException {
      Path socketPath = Files.createTempDirectory("gimle-agent-statusput-uds-").resolve("c.sock");
      ControlChannelServer server = new ControlChannelServer(socketPath);
      SocketChannel workerSocket = SocketChannel.open(StandardProtocolFamily.UNIX);
      workerSocket.connect(UnixDomainSocketAddress.of(socketPath));
      BufferedReader workerIn =
          new BufferedReader(
              new InputStreamReader(Channels.newInputStream(workerSocket), StandardCharsets.UTF_8));

      SupervisedInstance instance = new SupervisedInstance(assigned, null, server, null);
      instance.connection = server.accept();
      java.util.Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
      Thread readerThread =
          Thread.ofVirtual()
              .start(
                  () ->
                      AgentMain.readLoop(
                          instance,
                          assigned.deploymentName() + "#0",
                          null,
                          new ServiceCatalog(),
                          HttpClient.newHttpClient(),
                          baseUrl,
                          "node-a",
                          supervised,
                          null,
                          new ConcurrentHashMap<>()));
      return new RelayHarness(server, workerSocket, instance.connection, workerIn, readerThread);
    }

    void sendRaw(ControlMessage message) throws IOException {
      String line = ControlMessageCodec.encode(message) + "\n";
      workerSocket.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
    }

    ControlMessage.RelayControlPlaneResult receiveResult() throws IOException {
      String line = workerIn.readLine();
      if (line == null) {
        throw new AssertionError("worker socket closed before a relay result arrived");
      }
      ControlMessage decoded = ControlMessageCodec.decode(line);
      if (!(decoded instanceof ControlMessage.RelayControlPlaneResult result)) {
        throw new AssertionError("expected a RelayControlPlaneResult, got: " + decoded);
      }
      return result;
    }

    @Override
    public void close() throws IOException {
      workerSocket.close();
      connection.close();
      server.close();
      try {
        readerThread.join(Duration.ofSeconds(5).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
