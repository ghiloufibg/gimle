package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.testkit.Await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The service fabric's circuit breaker (CLAUDE.md's own framing: "circuit breaking/outlier ejection
 * at the registry level"), proven against real cross-process failures rather than {@code
 * FabricServiceRegistryTest}'s own same-JVM dead-socket/mocked-failure shape. {@code
 * FabricServiceRegistry} keeps one {@code CircuitBreaker} per remote {@code ServiceEndpoint} with
 * no external state export (no metric, no log line marks its own open/close transition) -- this
 * scenario has to infer the breaker having opened indirectly, from the consumer's own real call
 * pattern.
 *
 * <p>Locality-tier spillover (same-worker -&gt; same-machine -&gt; remote) is deliberately out of
 * scope here: this fixture's single-{@code AgentMain} topology can construct same-worker and
 * same-machine candidates but not a genuine cross-machine one, and same-worker Tier 1 packing would
 * need its own dedicated fixture module pair -- see this class's own javadoc for why only the
 * breaker (equally exercisable with two same-machine {@code TIER_2} candidates, which this fixture
 * already builds elsewhere) is covered this pass.
 */
@Tag("smoke")
class ServiceFabricIT extends GreeterSmokeClusterSupport {

  /**
   * Two real, simultaneously-registered {@code Greeter} providers -- the real committed one and a
   * synthetic one that fails every call ({@link
   * GreeterSmokeClusterSupport#buildAlwaysBrokenProviderJar()}) -- plus the real committed
   * consumer, which re-resolves and calls {@code Greeter} every 5s. {@code FabricServiceRegistry}'s
   * own least-outstanding-requests selection has no reason to prefer one healthy-looking candidate
   * over the other at first, so the consumer's calls land on both, including the broken one --
   * until that endpoint's own {@code CircuitBreaker} crosses its error-rate threshold (production
   * default: 5-call window, 50% error rate, {@code WorkerMain}'s own {@code FabricServiceRegistry}
   * construction) and excludes it from selection. Observed purely through the consumer's own real
   * application log: at least one real failure (proving the broken replica was genuinely reached,
   * not just present but never called), followed by a sustained run of nothing but successes
   * (proving the breaker opened and stayed open, not that the load balancer got lucky on one
   * round).
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void a_circuit_breaker_excludes_a_consistently_failing_replica_after_real_failures()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path healthyProviderJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path consumerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-consumer/target/greeter-consumer-" + GIMLE_VERSION + ".jar");
    assertTrue(
        Files.isRegularFile(healthyProviderJar), "expected a built jar at " + healthyProviderJar);
    assertTrue(Files.isRegularFile(consumerJar), "expected a built jar at " + consumerJar);
    Path brokenProviderJar = buildAlwaysBrokenProviderJar();

    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        healthyProviderJar,
        Duration.ofSeconds(30));
    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-broken-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0-broken",
        brokenProviderJar,
        1,
        Optional.empty(),
        Duration.ofSeconds(30));
    submitDeploymentWithRetry(
        baseUrl,
        "greeter-consumer-deployment",
        "com.gimle.examples.greeter.consumer",
        consumerJar,
        Duration.ofSeconds(30));

    Await.until(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE");
    Await.until(
        () -> isActive(baseUrl, "greeter-provider-broken-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-broken-deployment should reach ACTIVE (starting fine is the point --"
            + " only calls into it fail)");
    Await.until(
        () -> isActive(baseUrl, "greeter-consumer-deployment"),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment should reach ACTIVE");

    // CircuitBreaker's own exponential reopen backoff (base cooldown 5s, doubling per reopen up
    // to a 16x/80s cap -- see CircuitBreaker's own javadoc) means a persistently-broken endpoint
    // can be re-tried, fail, and re-open several times before settling into a long enough
    // excluded window for SUSTAINED_SUCCESSES_REQUIRED consecutive successes to actually land: up
    // to roughly 5+10+20+40=75s of reopen backoff alone, on top of the time to the first open and
    // the successes themselves -- 400s gives real margin over that worst case.
    try {
      Await.until(
          () -> breakerHasExcludedTheBrokenReplica(baseUrl),
          Duration.ofSeconds(400),
          "greeter-consumer-deployment's own log should show at least one real failure (the"
              + " broken replica genuinely reached) followed by a sustained run of nothing but"
              + " successes (CircuitBreaker excluding it from further selection) -- see"
              + " FabricServiceRegistry#selectAllowedCandidate");
    } catch (AssertionError e) {
      String log = fetchInstanceLog(baseUrl, "greeter-consumer-deployment", 0, "APPLICATION");
      throw new AssertionError(e.getMessage() + " -- full consumer log:\n" + log, e);
    }
  }

  /**
   * At least one observed failure, then at least {@link #SUSTAINED_SUCCESSES_REQUIRED} consecutive
   * successes after the *last* one -- a single lucky round of least-outstanding-requests selection
   * landing on the healthy replica isn't distinguishable from a genuinely open breaker, but several
   * calls in a row (each 5s apart, so this alone spans {@link #SUSTAINED_SUCCESSES_REQUIRED}*5s of
   * real elapsed time) landing exclusively on the healthy replica, with the broken one never coming
   * back into rotation, is exactly what an excluded candidate looks like from the caller's own log.
   */
  private static final int SUSTAINED_SUCCESSES_REQUIRED = 4;

  /**
   * {@code fetchInstanceLog}'s body is one compact JSON object ({@code AgentLogServer
   * #respondPage}: {@code {"lines":[{...},{...}]}}), not newline-separated text -- an earlier
   * version of this method called {@code log.lines()} on the raw body directly, which (since the
   * whole response has no embedded newlines) always produced a single "line" containing every
   * message concatenated together. That made {@code successesSince} structurally always {@code 0}:
   * {@code lastFailureIndex} could only ever be {@code 0} (a substring match anywhere in the one
   * giant blob) or {@code -1}, and {@code subList(1, 1)} is always empty. The assertion could never
   * pass regardless of whether the breaker actually excluded anything -- caught by a real cluster
   * run that timed out even with genuine, correctly-ordered exclusion happening underneath.
   */
  private boolean breakerHasExcludedTheBrokenReplica(String baseUrl) {
    String body = fetchInstanceLog(baseUrl, "greeter-consumer-deployment", 0, "APPLICATION");
    if (body.isEmpty()) {
      return false;
    }
    List<Map<String, Object>> lines =
        Json.asObjectList(Json.asObject(Json.parse(body)).get("lines"));
    int lastFailureIndex = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (String.valueOf(lines.get(i).get("message")).contains("call to Greeter failed")) {
        lastFailureIndex = i;
      }
    }
    if (lastFailureIndex < 0) {
      return false; // the broken replica hasn't been reached (and failed) for real yet.
    }
    long successesSince =
        lines.subList(lastFailureIndex + 1, lines.size()).stream()
            .map(entry -> String.valueOf(entry.get("message")))
            .filter(message -> message.contains("Hello, Gimlé!"))
            .count();
    return successesSince >= SUSTAINED_SUCCESSES_REQUIRED;
  }
}
