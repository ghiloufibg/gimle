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
 * A racing {@code PUT}/{@code DELETE} pair against the same, already-existing deployment -- the
 * exact shape a QA session found could let a scale-up {@code apply} silently outlive a {@code
 * delete} that had already reported success (5 running instances survived 30+ seconds past a
 * "deleted" response, in 2 of 3 trials). Closed for real now, not just made honest: {@code
 * handlePutDeployment}/{@code handleDeleteDeployment} both propose a generation-guarded {@code
 * StateMutation.PutDeployment}/{@code RemoveDeployment} (see that record's own javadoc), read as a
 * precondition before racing and checked deterministically -- identically on every node -- at the
 * actual point of application. A first attempt at this fix reused an existing distributed-lease
 * primitive for mutual exclusion and was reverted after it measurably degraded a different,
 * unrelated subsystem's reliability during testing (see the commit history and final report for
 * that investigation); the generation guard replaces it rather than layering on top.
 *
 * <p>Racing two writes against a deployment that already exists (not a brand-new name) is the
 * scenario that actually exercises the guard: both requests read the same starting generation, so
 * whichever commits first is guaranteed to win outright, and the other's own precondition no
 * longer holds against the resulting state -- a real, cluster-wide fact, not a guess. This does
 * <em>not</em> mean delete always "loses" a race that starts before any prior write -- deleting a
 * name that has never existed is the established idempotent-no-op convention (see {@code
 * CHAOS-2}), so a delete racing a brand-new name's very first create is free to succeed alongside
 * it; nothing was destroyed that the create didn't itself just make. What this test rules out is
 * the actual lost-update shape against an object with real prior state: a deployment left behind
 * carrying neither request's real content, both requests reporting success while only one's write
 * actually stuck, or a request that fails outright instead of returning one of its valid, honest
 * outcomes.
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
   * Creates a deployment (3 replicas) synchronously first -- matching the QA report's own
   * reproduction exactly, an <em>already-existing</em> deployment, not a brand-new name -- then
   * fires a scale-to-5 apply and a delete for it with no ordering between them, repeated 15 times
   * over a fresh name each round so one round's outcome can't leak into the next. Both requests
   * read the same starting generation, so the generation guard now guarantees a deterministic
   * outcome every round: exactly one of the two succeeds (200) and the other is refused with an
   * honest 409 (its own precondition no longer holds against whatever the winner just committed) --
   * never both succeeding, never a torn state, and never a request failing outright.
   */
  @RepeatedTest(15)
  @Timeout(20)
  void a_racing_apply_and_delete_on_an_existing_deployment_resolves_to_exactly_one_winner(
      RepetitionInfo repetitionInfo) throws Exception {
    String name = "race-dep-" + repetitionInfo.getCurrentRepetition();
    assertEquals(200, putDeployment(name, 3).statusCode(), "initial create must succeed");

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

      // Neither request is ever allowed to fail outright with a genuine internal error.
      assertTrue(
          putResponse.statusCode() < 500,
          "apply must not itself fail: " + putResponse.statusCode() + " " + putResponse.body());
      assertTrue(
          deleteResponse.statusCode() < 500,
          "delete must not itself fail: "
              + deleteResponse.statusCode()
              + " "
              + deleteResponse.body());

      boolean putWon = putResponse.statusCode() == 200;
      boolean deleteWon = deleteResponse.statusCode() == 200;
      assertTrue(
          putWon ^ deleteWon,
          "exactly one of apply/delete must win against an already-existing deployment, never"
              + " both and never neither: put="
              + putResponse.statusCode()
              + " delete="
              + deleteResponse.statusCode());
      if (!putWon) {
        assertEquals(
            409,
            putResponse.statusCode(),
            "the loser must be a genuine conflict, not some other failure: " + putResponse.body());
      }
      if (!deleteWon) {
        assertEquals(
            409,
            deleteResponse.statusCode(),
            "the loser must be a genuine conflict, not some other failure: "
                + deleteResponse.body());
      }

      HttpResponse<String> finalState = getDeployment(name);
      if (deleteWon) {
        assertEquals(
            404, finalState.statusCode(), "delete won the race -- the deployment must be gone");
      } else {
        assertEquals(
            200, finalState.statusCode(), "apply won the race -- the deployment must exist");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec =
            (Map<String, Object>) ((Map<String, Object>) Json.parse(finalState.body())).get("spec");
        // The only legitimate "still present" outcome: exactly the apply's own submitted content,
        // never the pre-race 3 replicas and never some other value neither request ever wrote.
        assertEquals(5, ((Number) spec.get("replicas")).intValue());
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * The narrower case the class javadoc calls out explicitly: racing a delete against a name that
   * has never existed can never destroy anything a concurrent create just made, since deleting a
   * genuinely absent name is a true no-op (the established idempotent convention, {@code CHAOS-2})
   * that leaves the generation guard's own precondition untouched -- so the create's identical
   * "expected absent" precondition still holds no matter which of the two actually commits first.
   * The create must therefore always succeed here, unconditionally. The delete's own response does
   * still depend on ordering, and correctly so: if its no-op commits before the create, it reports
   * the plain 200 a no-op earns; if the create commits first, the delete's own precondition (this
   * name was absent when I read it) no longer holds against the object that now exists, and it is
   * refused with an honest 409 rather than silently no-op'ing against content it never observed --
   * exactly the same "don't destroy or ignore a state you never actually saw" principle the
   * already-existing-deployment test above proves, not a special case carved out from it.
   */
  @RepeatedTest(5)
  @Timeout(20)
  void a_racing_delete_of_a_never_existing_name_never_blocks_a_concurrent_create(
      RepetitionInfo repetitionInfo) throws Exception {
    String name = "fresh-race-dep-" + repetitionInfo.getCurrentRepetition();
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

      assertEquals(
          200,
          putResponse.statusCode(),
          "creating a brand-new name must always succeed, regardless of a concurrent delete racing"
              + " a name that was never there to begin with: "
              + putResponse.body());
      assertTrue(
          deleteResponse.statusCode() == 200 || deleteResponse.statusCode() == 409,
          "delete of a never-existing name must resolve to its own no-op success (it committed"
              + " before the create) or an honest conflict (the create committed first, so the"
              + " name is no longer absent as this delete's own precondition assumed) -- never"
              + " anything else: "
              + deleteResponse.statusCode()
              + " "
              + deleteResponse.body());

      HttpResponse<String> finalState = getDeployment(name);
      assertEquals(
          200,
          finalState.statusCode(),
          "the create must always have taken effect, whatever the delete's own outcome was");
      @SuppressWarnings("unchecked")
      Map<String, Object> spec =
          (Map<String, Object>) ((Map<String, Object>) Json.parse(finalState.body())).get("spec");
      assertEquals(5, ((Number) spec.get("replicas")).intValue());
    } finally {
      pool.shutdownNow();
    }
  }
}
