package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.ControlMessageCodec;
import com.gimle.core.protocol.Json;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.observability.MuninnShipper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AgentMain#startShippingInstanceLogs}/{@link AgentMain#stopShippingInstanceLogs}, exercised
 * directly rather than through the full assignment-reconciliation loop -- the same "call the
 * package-private static seam" style {@code AgentMainTest} already uses for {@code
 * prepareResourceLimit}/{@code buildWorkerCommand}. Against a stub Muninn ingest {@link
 * HttpServer}, matching {@code MuninnShipperTest}'s own shape.
 */
class AgentMuninnShippingTest {

  private HttpServer stub;

  @AfterEach
  void tearDown() {
    if (stub != null) {
      stub.stop(0);
    }
  }

  private HttpServer startStub(AtomicInteger requestCount, List<String> receivedPaths)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ingest",
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
          }
          receivedPaths.add(exchange.getRequestURI().getPath());
          requestCount.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
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
  void a_null_muninn_endpoint_starts_no_shippers(@TempDir Path tempDir) {
    Map<String, List<MuninnShipper>> instanceShippers = new ConcurrentHashMap<>();

    AgentMain.startShippingInstanceLogs(
        null, instanceShippers, "greeter-deployment#0", assignedInstance(), tempDir);

    assertTrue(instanceShippers.isEmpty());
  }

  @Test
  @Timeout(10)
  void a_configured_endpoint_ships_the_instances_application_log_to_its_own_instance_scoped_path(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    stub = startStub(requestCount, receivedPaths);
    String muninnEndpoint = "127.0.0.1:" + stub.getAddress().getPort();

    String key = "greeter-deployment#0";
    Path applicationLog =
        tempDir
            .resolve("workers")
            .resolve(key)
            .resolve("instances")
            .resolve("greeter-deployment-0.log");
    Files.createDirectories(applicationLog.getParent());
    Files.writeString(
        applicationLog,
        Json.write(Map.of("timestamp", "2026-08-10T10:00:00Z", "level", "INFO", "message", "hi"))
            + "\n");

    Map<String, List<MuninnShipper>> instanceShippers = new ConcurrentHashMap<>();
    try {
      AgentMain.startShippingInstanceLogs(
          muninnEndpoint, instanceShippers, key, assignedInstance(), tempDir);

      assertEquals(
          2, instanceShippers.get(key).size(), "expected a PLATFORM + APPLICATION shipper");

      awaitUntil(
          () -> receivedPaths.contains("/ingest/logs/instances/greeter-deployment/0/APPLICATION"),
          Duration.ofSeconds(5));
    } finally {
      AgentMain.stopShippingInstanceLogs(instanceShippers, key);
    }
  }

  @Test
  @Timeout(10)
  void stopping_shipping_removes_the_key_and_closes_every_shipper_so_no_further_ticks_arrive(
      @TempDir Path tempDir) throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    stub = startStub(requestCount, receivedPaths);
    String muninnEndpoint = "127.0.0.1:" + stub.getAddress().getPort();

    String key = "greeter-deployment#0";
    Map<String, List<MuninnShipper>> instanceShippers = new ConcurrentHashMap<>();
    AgentMain.startShippingInstanceLogs(
        muninnEndpoint, instanceShippers, key, assignedInstance(), tempDir);
    assertTrue(instanceShippers.containsKey(key));

    AgentMain.stopShippingInstanceLogs(instanceShippers, key);
    assertTrue(instanceShippers.isEmpty());

    // Give any still-running ticker a moment to prove it really stopped, not just that the map
    // entry is gone.
    int countAtStop = requestCount.get();
    Thread.sleep(200);
    assertEquals(countAtStop, requestCount.get());
  }

  // ---- worker-scoped metrics/traces shipping (design doc §6b/§6d) ----

  @Test
  void a_null_muninn_endpoint_starts_no_worker_shippers() {
    Map<String, AgentMain.WorkerShipperPair> workerShippers = new ConcurrentHashMap<>();

    AgentMain.startShippingWorkerMetricsAndTraces(null, workerShippers, "node-a", "worker-1");

    assertTrue(workerShippers.isEmpty());
  }

  @Test
  @Timeout(10)
  void a_configured_endpoint_starts_one_metrics_and_one_traces_shipper_keyed_by_worker_id()
      throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    stub = startStub(requestCount, receivedPaths);
    String muninnEndpoint = "127.0.0.1:" + stub.getAddress().getPort();

    Map<String, AgentMain.WorkerShipperPair> workerShippers = new ConcurrentHashMap<>();
    try {
      AgentMain.startShippingWorkerMetricsAndTraces(
          muninnEndpoint, workerShippers, "node-a", "worker-1");

      AgentMain.WorkerShipperPair pair = workerShippers.get("worker-1");
      assertNotNull(pair);
      pair.metrics().shipPreparedBatch("{\"name\":\"gimle.test\"}\n");
      pair.traces().shipPreparedBatch("{\"traceId\":\"abc\"}\n");

      awaitUntil(
          () -> receivedPaths.contains("/ingest/metrics/WORKER/node-a:worker-1"),
          Duration.ofSeconds(5));
      awaitUntil(
          () -> receivedPaths.contains("/ingest/traces/WORKER/node-a:worker-1"),
          Duration.ofSeconds(5));
    } finally {
      AgentMain.stopShippingWorkerMetricsAndTraces(workerShippers, "worker-1");
    }
  }

  @Test
  void starting_twice_for_the_same_worker_id_is_a_noop_not_a_second_pair() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    stub = startStub(requestCount, receivedPaths);
    String muninnEndpoint = "127.0.0.1:" + stub.getAddress().getPort();

    Map<String, AgentMain.WorkerShipperPair> workerShippers = new ConcurrentHashMap<>();
    AgentMain.startShippingWorkerMetricsAndTraces(
        muninnEndpoint, workerShippers, "node-a", "worker-1");
    AgentMain.WorkerShipperPair first = workerShippers.get("worker-1");

    AgentMain.startShippingWorkerMetricsAndTraces(
        muninnEndpoint, workerShippers, "node-a", "worker-1");

    assertEquals(1, workerShippers.size());
    assertEquals(first, workerShippers.get("worker-1"));
    AgentMain.stopShippingWorkerMetricsAndTraces(workerShippers, "worker-1");
  }

  @Test
  void stopping_removes_the_key_and_a_missing_worker_id_is_a_noop() {
    Map<String, AgentMain.WorkerShipperPair> workerShippers = new ConcurrentHashMap<>();
    AgentMain.startShippingWorkerMetricsAndTraces(
        "127.0.0.1:1", workerShippers, "node-a", "worker-1");
    assertTrue(workerShippers.containsKey("worker-1"));

    AgentMain.stopShippingWorkerMetricsAndTraces(workerShippers, "worker-1");
    assertTrue(workerShippers.isEmpty());

    // A worker whose Hello never arrived has no entry (and no workerId) -- must not throw.
    AgentMain.stopShippingWorkerMetricsAndTraces(workerShippers, null);
    assertNull(workerShippers.get("worker-1"));
  }

  /**
   * {@link AgentMain#readLoop}'s full dispatch, end to end: a hand-driven "worker" (a raw UDS
   * client speaking the same {@link ControlMessageCodec} line protocol {@code
   * ControlChannelClient}/{@code WorkerConnection} use, avoiding a dependency on {@code
   * gimle-worker}'s package-private client class from this module) connects, sends a real {@code
   * Hello} (which must establish the worker-scoped shipper pair per §6d), then a {@code
   * MetricsSnapshot}/{@code TracesSnapshot} pair -- both must reach the stub Muninn ingest server
   * with their payload forwarded byte-for-byte, no re-serialization (design doc §6d: "a trivial
   * relay... no re-serialization, no re-derivation").
   */
  @Test
  @Timeout(15)
  void hello_then_metrics_and_traces_snapshots_relay_to_the_stub_muninn_server() throws Exception {
    AtomicInteger requestCount = new AtomicInteger();
    List<String> receivedPaths = new CopyOnWriteArrayList<>();
    List<String> receivedBodies = new CopyOnWriteArrayList<>();
    stub =
        startStubCapturingBodies(
            (path, body) -> {
              receivedPaths.add(path);
              receivedBodies.add(body);
              requestCount.incrementAndGet();
            });
    String muninnEndpoint = "127.0.0.1:" + stub.getAddress().getPort();

    Path socketPath = Files.createTempDirectory("gimle-agent-relay-uds-").resolve("c.sock");
    try (ControlChannelServer server = new ControlChannelServer(socketPath);
        SocketChannel workerSocket = connectRawWorker(socketPath)) {
      sendRaw(workerSocket, new ControlMessage.Hello("worker-relay-1", 4242L));
      sendRaw(
          workerSocket,
          new ControlMessage.MetricsSnapshot("worker-relay-1", "{\"name\":\"gimle.test\"}"));
      sendRaw(
          workerSocket,
          new ControlMessage.TracesSnapshot("worker-relay-1", "{\"traceId\":\"xyz\"}"));

      SupervisedInstance instance = new SupervisedInstance(assignedInstance(), null, server, null);
      instance.connection = server.accept();
      Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
      Map<String, AgentMain.WorkerShipperPair> workerShippers = new ConcurrentHashMap<>();
      Thread reader =
          Thread.ofVirtual()
              .start(
                  () ->
                      AgentMain.readLoop(
                          instance,
                          "greeter-deployment#0",
                          null,
                          new ServiceCatalog(),
                          HttpClient.newHttpClient(),
                          URI.create("http://127.0.0.1:1"),
                          "node-a",
                          supervised,
                          muninnEndpoint,
                          workerShippers));
      try {
        awaitUntil(() -> workerShippers.containsKey("worker-relay-1"), Duration.ofSeconds(5));
        awaitUntil(
            () -> receivedPaths.contains("/ingest/metrics/WORKER/node-a:worker-relay-1"),
            Duration.ofSeconds(5));
        awaitUntil(
            () -> receivedPaths.contains("/ingest/traces/WORKER/node-a:worker-relay-1"),
            Duration.ofSeconds(5));
        assertTrue(receivedBodies.stream().anyMatch(b -> b.contains("gimle.test")));
        assertTrue(receivedBodies.stream().anyMatch(b -> b.contains("xyz")));
      } finally {
        instance.connection.close();
        reader.join(Duration.ofSeconds(5).toMillis());
      }
    }
  }

  private HttpServer startStubCapturingBodies(
      java.util.function.BiConsumer<String, String> onRequest) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ingest",
        exchange -> {
          String body;
          try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          onRequest.accept(exchange.getRequestURI().getPath(), body);
          byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  private static SocketChannel connectRawWorker(Path socketPath) throws IOException {
    SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(socketPath));
    return channel;
  }

  private static void sendRaw(SocketChannel channel, ControlMessage message) throws IOException {
    String line = ControlMessageCodec.encode(message) + "\n";
    channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
  }

  private static void awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout);
      }
      Thread.sleep(20);
    }
  }
}
