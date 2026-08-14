package com.gimle.holmgang.load;

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
 * Real HTTP load against the deployed {@code greeter-load-generator} module -- every request it
 * receives becomes one real cross-worker fabric call to greeter-provider's {@code Greeter}, so the
 * rate this drives is the actual per-module signal the platform's autoscaling reads, not a
 * synthetic stand-in. Run as Gatling's own CLI in a separate JVM (see {@link LoadGenerator}); every
 * knob is a system property so one class serves different scenarios without recompiling. {@code
 * gimle.load.concurrentUsers} > 0 switches from the open model (fixed requests/sec, what a
 * request-rate target needs) to the closed model (fixed requests continuously in flight, what a
 * queue-depth target needs).
 */
public class GreeterLoadSimulation extends Simulation {

  private static final String BASE_URL =
      System.getProperty("gimle.load.baseUrl", "http://127.0.0.1:19077");
  private static final double REQUESTS_PER_SECOND =
      Double.parseDouble(System.getProperty("gimle.load.requestsPerSecond", "20"));
  private static final int DURATION_SECONDS =
      Integer.parseInt(System.getProperty("gimle.load.durationSeconds", "60"));
  private static final int CONCURRENT_USERS =
      Integer.parseInt(System.getProperty("gimle.load.concurrentUsers", "0"));

  private final HttpProtocolBuilder httpProtocol =
      http.baseUrl(BASE_URL).acceptHeader("text/plain").userAgentHeader("gimle-holmgang-gatling");

  private final ScenarioBuilder callGreeter =
      scenario("holmgang-load").exec(http("call greeter-load-generator").get("/call"));

  {
    if (CONCURRENT_USERS > 0) {
      setUp(
              callGreeter.injectClosed(
                  constantConcurrentUsers(CONCURRENT_USERS)
                      .during(Duration.ofSeconds(DURATION_SECONDS))))
          .protocols(httpProtocol);
    } else {
      final OpenInjectionStep openLoad =
          constantUsersPerSec(REQUESTS_PER_SECOND).during(Duration.ofSeconds(DURATION_SECONDS));
      setUp(callGreeter.injectOpen(openLoad)).protocols(httpProtocol);
    }
  }
}
