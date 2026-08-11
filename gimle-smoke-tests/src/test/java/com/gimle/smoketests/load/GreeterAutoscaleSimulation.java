package com.gimle.smoketests.load;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

/**
 * Real, external HTTP load against {@code greeter-load-generator} (gimle-examples/) -- every
 * request it sends becomes one real cross-worker fabric call to greeter-provider's {@code Greeter},
 * so the rate this simulation drives is the actual signal {@code AutoscaleReconciler}'s {@code
 * targetRequestRatePerSecond} reads off greeter-provider's own worker-reported metrics, not a
 * synthetic stand-in for it. {@code GreeterSmokeTestIT} spawns this as its own JVM process (the
 * same shape it already spawns every other cluster component in, via {@code io.gatling.app.Gatling
 * -s GreeterAutoscaleSimulation}), never embeds it -- Gatling's own runtime isn't designed to run
 * twice in one JVM, and every other subprocess this suite launches follows the same pattern already
 * (Playwright's own {@code bun run test:e2e} is the closest precedent).
 *
 * <p>Every knob below is a system property rather than a constant so the same class serves
 * different scenarios without recompiling: {@code GreeterSmokeTestIT} passes them as {@code -D}
 * flags on the spawned JVM's own command line, matching how every other spawned process in that
 * suite is configured.
 */
public class GreeterAutoscaleSimulation extends Simulation {

  private static final String BASE_URL =
      System.getProperty("gimle.load.baseUrl", "http://127.0.0.1:19077");
  private static final double REQUESTS_PER_SECOND =
      Double.parseDouble(System.getProperty("gimle.load.requestsPerSecond", "20"));
  private static final int DURATION_SECONDS =
      Integer.parseInt(System.getProperty("gimle.load.durationSeconds", "60"));

  private final HttpProtocolBuilder httpProtocol =
      http.baseUrl(BASE_URL)
          .acceptHeader("text/plain")
          .userAgentHeader("gimle-smoke-tests-gatling");

  private final ScenarioBuilder callGreeter =
      scenario("greeter-autoscale-load").exec(http("call greeter-load-generator").get("/call"));

  {
    setUp(
            callGreeter.injectOpen(
                constantUsersPerSec(REQUESTS_PER_SECOND)
                    .during(Duration.ofSeconds(DURATION_SECONDS))))
        .protocols(httpProtocol);
  }
}
