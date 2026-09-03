package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Regression coverage for the flood-to-unresponsiveness failure the fleet's own load test found:
 * a large concurrent burst against an ordinary read route (here, {@code GET /deployments}, the
 * exact route flooded) must be turned away with fast {@code 429}s once past the admission budget,
 * never accepted and left to time out -- and a node agent's own heartbeat traffic, hitting its own
 * reserved lane, must keep succeeding throughout even while the general lane is fully saturated.
 */
// Real ApiServer + real java.net.http.HttpClient on a loopback ephemeral port: excludes this class
// from running concurrently with any other class doing the same (see ApiServerTest's own javadoc).
@ResourceLock("gimle-controlplane-api-server-http")
// Sets ApiServer.ADMISSION_GENERAL_LIMIT_PROPERTY, a JVM-global, before construction.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ApiServerAdmissionControlTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;

  @AfterEach
  void tearDown() {
    System.clearProperty(ApiServer.ADMISSION_GENERAL_LIMIT_PROPERTY);
    if (server != null) {
      server.close();
    }
    if (inProcessFafnir != null) {
      inProcessFafnir.close();
    }
    if (inProcessStore != null) {
      inProcessStore.close();
    }
  }

  @Test
  @Timeout(30)
  void a_flood_past_the_admission_limit_gets_fast_429s_while_node_heartbeats_keep_succeeding()
      throws Exception {
    System.setProperty(ApiServer.ADMISSION_GENERAL_LIMIT_PROPERTY, "4");
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    String baseUrl = "http://localhost:" + server.port();

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // Register the node once up front, the same way a real node agent would before its first
    // heartbeat -- handleHeartbeat itself doesn't require it, but a real deployment always has it.
    send(client, registerRequest(baseUrl));

    int floodSize = 400;
    ExecutorService floodExecutor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> floodFutures = new ArrayList<>(floodSize);
    for (int i = 0; i < floodSize; i++) {
      floodFutures.add(
          floodExecutor.submit(
              () -> {
                start.await();
                HttpRequest request =
                    HttpRequest.newBuilder(URI.create(baseUrl + "/deployments"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                return send(client, request).statusCode();
              }));
    }

    // A node-agent heartbeat hammer running for the whole duration of the flood -- proving the
    // node lane keeps answering while the general lane above is saturated, not just before/after.
    AtomicBoolean floodInFlight = new AtomicBoolean(true);
    List<Integer> heartbeatStatuses = Collections.synchronizedList(new ArrayList<>());
    ExecutorService heartbeatExecutor = Executors.newSingleThreadExecutor();
    Future<?> heartbeatTask =
        heartbeatExecutor.submit(
            () -> {
              while (floodInFlight.get()) {
                try {
                  heartbeatStatuses.add(send(client, heartbeatRequest(baseUrl)).statusCode());
                } catch (IOException | InterruptedException e) {
                  heartbeatStatuses.add(-1);
                }
              }
            });

    start.countDown();
    List<Integer> floodStatuses = new ArrayList<>(floodSize);
    for (Future<Integer> future : floodFutures) {
      floodStatuses.add(future.get(15, TimeUnit.SECONDS));
    }
    floodInFlight.set(false);
    heartbeatTask.get(15, TimeUnit.SECONDS);
    floodExecutor.shutdown();
    heartbeatExecutor.shutdown();

    assertTrue(
        floodStatuses.stream().allMatch(status -> status == 200 || status == 429),
        "every flooded request must resolve to a definite 200 or 429, never anything else: "
            + floodStatuses);
    long rejected = floodStatuses.stream().filter(status -> status == 429).count();
    assertTrue(
        rejected > 0,
        "a 400-request flood against a 4-slot admission budget must trigger real rejections, got: "
            + floodStatuses);

    assertFalse(heartbeatStatuses.isEmpty(), "the heartbeat hammer must have run at all");
    assertTrue(
        heartbeatStatuses.stream().allMatch(status -> status == 200),
        "node heartbeats must never be starved by a flood on an unrelated route, got: "
            + heartbeatStatuses);
  }

  private static HttpResponse<Void> send(HttpClient client, HttpRequest request)
      throws IOException, InterruptedException {
    return client.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private static HttpRequest registerRequest(String baseUrl) {
    return HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/flood-test-node/register"))
        .timeout(Duration.ofSeconds(10))
        .POST(
            HttpRequest.BodyPublishers.ofString(
                "{\"capabilities\":{\"supportedTiers\":[\"TIER_1\"]}}",
                StandardCharsets.UTF_8))
        .build();
  }

  private static HttpRequest heartbeatRequest(String baseUrl) {
    String body =
        """
        {"capacity":{"totalMemoryBytes":1000,"assignedMemoryBytes":0,"totalCpuMillicores":1000,\
        "assignedCpuMillicores":0},"instances":[]}
        """;
    return HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/flood-test-node/heartbeat"))
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }
}
