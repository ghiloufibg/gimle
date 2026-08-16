package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * {@link AgentMain#readLoop}'s handling of a worker-relayed {@code RelayControlPlaneRead}: the
 * hard-coded whitelist rejects anything outside {@code GET /endpoints/{name}} locally, before any
 * request reaches the control plane, and a whitelisted path triggers a real HTTP call against the
 * control plane's own stub, with the response relayed back byte-for-byte. Drives {@link
 * AgentMain#readLoop} directly with a hand-rolled raw worker socket, the same pattern {@code
 * AgentMuninnShippingTest} already established for exercising this method's dispatch end to end.
 */
class AgentRelayControlPlaneReadTest {

  private HttpServer controlPlaneStub;

  @AfterEach
  void tearDown() {
    if (controlPlaneStub != null) {
      controlPlaneStub.stop(0);
    }
  }

  private HttpServer startControlPlaneStub(
      AtomicInteger requestCount, List<String> receivedPaths, int status, String body)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
          }
          receivedPaths.add(exchange.getRequestURI().getPath());
          requestCount.incrementAndGet();
          byte[] response = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  private static AssignedInstance assignedInstance() {
    return new AssignedInstance(
        "greeter-deployment",
        0,
        new ModuleId("greeter", Version.parse("1.0.0")),
        "/does/not/matter.jar",
        Optional.empty());
  }

  @Test
  @Timeout(15)
  void a_non_whitelisted_path_is_rejected_locally_and_never_reaches_the_control_plane()
      throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    controlPlaneStub = startControlPlaneStub(requestCount, receivedPaths, 200, "{}");
    URI baseUrl = URI.create("http://127.0.0.1:" + controlPlaneStub.getAddress().getPort());

    RelayHarness harness = RelayHarness.start(baseUrl);
    try {
      harness.sendRaw(new ControlMessage.RelayControlPlaneRead("corr-1", "/secrets/acme"));
      ControlMessage.RelayControlPlaneResult result = harness.receiveResult();

      assertEquals("corr-1", result.correlationId());
      assertEquals(403, result.status());
      assertEquals(0, requestCount.get(), "a non-whitelisted path must never reach the stub");
      assertTrue(receivedPaths.isEmpty());
    } finally {
      harness.close();
    }
  }

  @Test
  @Timeout(15)
  void a_path_traversal_attempt_disguised_as_a_single_segment_is_rejected() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    controlPlaneStub = startControlPlaneStub(requestCount, receivedPaths, 200, "{}");
    URI baseUrl = URI.create("http://127.0.0.1:" + controlPlaneStub.getAddress().getPort());

    RelayHarness harness = RelayHarness.start(baseUrl);
    try {
      harness.sendRaw(new ControlMessage.RelayControlPlaneRead("corr-2", "/endpoints/.."));
      ControlMessage.RelayControlPlaneResult result = harness.receiveResult();

      assertEquals(403, result.status());
      assertEquals(0, requestCount.get());
    } finally {
      harness.close();
    }
  }

  @Test
  @Timeout(15)
  void a_whitelisted_path_triggers_a_real_call_and_relays_the_response_back() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    String stubBody = "[{\"instanceIndex\":0,\"nodeId\":\"node-a\"}]";
    controlPlaneStub = startControlPlaneStub(requestCount, receivedPaths, 200, stubBody);
    URI baseUrl = URI.create("http://127.0.0.1:" + controlPlaneStub.getAddress().getPort());

    RelayHarness harness = RelayHarness.start(baseUrl);
    try {
      harness.sendRaw(
          new ControlMessage.RelayControlPlaneRead("corr-3", "/endpoints/greeter-deployment"));
      ControlMessage.RelayControlPlaneResult result = harness.receiveResult();

      assertEquals("corr-3", result.correlationId());
      assertEquals(200, result.status());
      assertEquals(stubBody, result.body());
      assertEquals(1, requestCount.get());
      assertEquals(List.of("/endpoints/greeter-deployment"), receivedPaths);
    } finally {
      harness.close();
    }
  }

  @Test
  @Timeout(15)
  void a_control_plane_transport_failure_is_synthesized_as_a_502() throws Exception {
    // No stub started at all -- baseUrl points at a closed local port, guaranteeing a connection
    // failure rather than depending on timing.
    URI baseUrl = URI.create("http://127.0.0.1:1");

    RelayHarness harness = RelayHarness.start(baseUrl);
    try {
      harness.sendRaw(
          new ControlMessage.RelayControlPlaneRead("corr-4", "/endpoints/greeter-deployment"));
      ControlMessage.RelayControlPlaneResult result = harness.receiveResult();

      assertEquals(502, result.status());
      assertFalse(result.body().isBlank());
    } finally {
      harness.close();
    }
  }

  /**
   * Wires one {@link AgentMain#readLoop} invocation against a raw worker-side socket -- the same
   * shape {@code AgentMuninnShippingTest} hand-rolls inline, extracted here since every test in
   * this class needs the identical setup/teardown.
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

    static RelayHarness start(URI baseUrl) throws IOException {
      Path socketPath = Files.createTempDirectory("gimle-agent-relay-uds-").resolve("c.sock");
      ControlChannelServer server = new ControlChannelServer(socketPath);
      SocketChannel workerSocket = SocketChannel.open(StandardProtocolFamily.UNIX);
      workerSocket.connect(UnixDomainSocketAddress.of(socketPath));
      BufferedReader workerIn =
          new BufferedReader(
              new InputStreamReader(Channels.newInputStream(workerSocket), StandardCharsets.UTF_8));

      SupervisedInstance instance = new SupervisedInstance(assignedInstance(), null, server, null);
      instance.connection = server.accept();
      java.util.Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
      Thread readerThread =
          Thread.ofVirtual()
              .start(
                  () ->
                      AgentMain.readLoop(
                          instance,
                          "greeter-deployment#0",
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
