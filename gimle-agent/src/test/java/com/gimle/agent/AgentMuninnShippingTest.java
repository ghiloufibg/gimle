package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.Json;
import com.gimle.observability.MuninnShipper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
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
