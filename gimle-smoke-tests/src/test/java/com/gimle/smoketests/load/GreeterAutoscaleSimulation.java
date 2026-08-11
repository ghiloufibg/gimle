package com.gimle.smoketests.load;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

/**
 * Real, external HTTP load against {@code greeter-load-generator} (gimle-examples/) -- every
 * request it sends becomes one real cross-worker fabric call to greeter-provider's {@code Greeter},
 * so the rate/concurrency this simulation drives is the actual signal {@code AutoscaleReconciler}'s
 * per-signal targets read off greeter-provider's own worker-reported metrics, not a synthetic
 * stand-in for it. {@code GreeterSmokeTestIT} spawns this as its own JVM process (the same shape it
 * already spawns every other cluster component in, via {@code io.gatling.app.Gatling -s
 * GreeterAutoscaleSimulation}), never embeds it -- Gatling's own runtime isn't designed to run
 * twice in one JVM, and every other subprocess this suite launches follows the same pattern already
 * (Playwright's own {@code bun run test:e2e} is the closest precedent).
 *
 * <p>Every knob below is a system property rather than a constant so the same class serves
 * different scenarios without recompiling: {@code GreeterSmokeTestIT} passes them as {@code -D}
 * flags on the spawned JVM's own command line, matching how every other spawned process in that
 * suite is configured.
 *
 * <p>{@code gimle.load.concurrentUsers} switches the whole injection model, not just one knob: 0
 * (the default) drives Gatling's <i>open</i> model, a fixed requests/sec rate regardless of how
 * long each request takes -- what {@code targetRequestRatePerSecond} needs. A positive value
 * instead drives the <i>closed</i> model, holding exactly that many requests continuously in flight
 * (Gatling re-injects a replacement the instant one completes) -- what {@code targetQueueDepth}
 * needs: sustaining more concurrent in-flight requests than {@code WorkerRuntime}'s per-module
 * {@code BoundedModuleScheduler} concurrency bound (4) is the only way to build a real, sustained
 * backlog on it, since a fixed request rate alone says nothing about how many of those requests are
 * actually in flight at once.
 */
public class GreeterAutoscaleSimulation extends Simulation {

  private static final String BASE_URL =
      System.getProperty("gimle.load.baseUrl", "http://127.0.0.1:19077");
  private static final double REQUESTS_PER_SECOND =
      Double.parseDouble(System.getProperty("gimle.load.requestsPerSecond", "20"));
  private static final int DURATION_SECONDS =
      Integer.parseInt(System.getProperty("gimle.load.durationSeconds", "60"));
  private static final int CONCURRENT_USERS =
      Integer.parseInt(System.getProperty("gimle.load.concurrentUsers", "0"));

  private final HttpProtocolBuilder httpProtocol =
      http.baseUrl(BASE_URL)
          .acceptHeader("text/plain")
          .userAgentHeader("gimle-smoke-tests-gatling");

  private final ScenarioBuilder callGreeter =
      scenario("greeter-autoscale-load").exec(http("call greeter-load-generator").get("/call"));

  {
    if (CONCURRENT_USERS > 0) {
      setUp(
              callGreeter.injectClosed(
                  constantConcurrentUsers(CONCURRENT_USERS)
                      .during(Duration.ofSeconds(DURATION_SECONDS))))
          .protocols(httpProtocol);
    } else {
      OpenInjectionStep openLoad =
          constantUsersPerSec(REQUESTS_PER_SECOND).during(Duration.ofSeconds(DURATION_SECONDS));
      setUp(callGreeter.injectOpen(openLoad)).protocols(httpProtocol);
    }
  }
}
