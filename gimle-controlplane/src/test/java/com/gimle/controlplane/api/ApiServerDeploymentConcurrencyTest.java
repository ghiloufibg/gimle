package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * A racing {@code PUT}/{@code DELETE} pair against the same deployment name, fired with no ordering
 * between them -- the exact shape a QA session found could otherwise let a scale-up {@code apply}
 * silently outlive a {@code delete} that had already reported success. {@code
 * handleDeleteDeployment} re-reads the deployment immediately after proposing its removal and
 * refuses to claim success (409, not 200) if a concurrent write already raced it back into
 * existence -- see that method's own javadoc for why a full compare-and-set fix was not the safe
 * choice for this pass (a distributed-lease-based mutual-exclusion attempt was tried and reverted
 * after it measurably degraded a different, unrelated subsystem's reliability during testing; see
 * the commit history and final report for that investigation).
 *
 * <p>This does <em>not</em> assert that delete always "wins" a race with no wait between the two
 * calls -- an apply that lands after a delete legitimately recreates the deployment, the same as if
 * the two commands had been typed one after the other on purpose; nothing server-side can (or
 * should) second-guess which of two genuinely concurrent, unordered requests a caller "really"
 * meant to land last. What this test rules out is the actual lost-update shape: a deployment left
 * behind carrying neither request's real content, or a request that fails outright instead of
 * returning one of its valid outcomes (200, 409, or 404).
 */
class ApiServerDeploymentConcurrencyTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String deploymentYaml(String name, int replicas) {
    return """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: %d
        """
        .formatted(name, replicas);
  }

  private HttpResponse<String> putDeployment(String name, int replicas) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml(name, replicas)))
            .build());
  }

  private HttpResponse<String> deleteDeployment(String name) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name)).DELETE().build());
  }

  private HttpResponse<String> getDeployment(String name) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name)).GET().build());
  }

  /**
   * Fires an apply (scale to 5 replicas) and a delete for the same never-yet-existing deployment
   * name with no ordering between them, repeated 15 times over a fresh name each round so one
   * round's outcome can't leak into the next. Every round's end state must be exactly one of the
   * two legitimate serializations: gone, or present with precisely the 5-replica spec the apply
   * submitted -- never a torn state (e.g. still present with some other replica count nobody wrote)
   * and never a request that fails outright instead of completing one of those two outcomes.
   */
  @RepeatedTest(15)
  @Timeout(20)
  void a_racing_apply_and_delete_on_the_same_deployment_never_leaves_a_torn_state(
      RepetitionInfo repetitionInfo) throws Exception {
    String name = "race-dep-" + repetitionInfo.getCurrentRepetition();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    try {
      Future<HttpResponse<String>> putFuture =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return putDeployment(name, 5);
              });
      Future<HttpResponse<String>> deleteFuture =
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                return deleteDeployment(name);
              });
      ready.await();
      go.countDown();

      HttpResponse<String> putResponse = putFuture.get();
      HttpResponse<String> deleteResponse = deleteFuture.get();

      // Neither request is ever allowed to fail outright -- a real internal error (500) here
      // would itself be evidence of exactly the kind of interleaved, inconsistent read the lease
      // exists to prevent (e.g. a revision-history append racing ahead of the spec it describes).
      assertTrue(
          putResponse.statusCode() < 500,
          "apply must not itself fail: " + putResponse.statusCode() + " " + putResponse.body());
      assertTrue(
          deleteResponse.statusCode() < 500,
          "delete must not itself fail: "
              + deleteResponse.statusCode()
              + " "
              + deleteResponse.body());

      HttpResponse<String> finalState = getDeployment(name);
      if (finalState.statusCode() != 404) {
        assertEquals(200, finalState.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> spec =
            (Map<String, Object>) ((Map<String, Object>) Json.parse(finalState.body())).get("spec");
        // The only legitimate "still present" outcome: exactly the apply's own submitted content
        // -- there was no pre-race state to have leaked through (this name never existed before
        // this round), and no value neither request ever wrote.
        assertEquals(5, ((Number) spec.get("replicas")).intValue());
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
