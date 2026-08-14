package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Worker-tier metrics/traces relay, end to end -- unlike {@link ObservabilityIT}, which only proves
 * a control-plane replica's own request metrics round-trip through Muninn, this proves a real
 * *deployed module's* own request counter and the real span its own fabric call produced both reach
 * Muninn under the {@code WORKER} process kind, having traveled worker JVM -> agent (workers have
 * no outbound network identity of their own; {@code WorkerMain}'s {@code
 * muninnMetricsRelayLoop}/{@code RelayingSpanExporter} relay both over the same control channel
 * {@code ControlMessage.MetricsReport} already uses) -> Muninn, readable back through the real
 * {@code GET /metrics-history/*}/{@code GET /traces-history/*} proxy. {@code
 * AgentMuninnShippingTest} already covers this relay at the unit level with a stub Muninn and a
 * hand-driven raw worker socket; this is the same path proven against a real worker JVM, a real
 * fabric call, and a real Muninn process.
 */
@Tag("smoke")
class WorkerObservabilityIT extends GreeterSmokeClusterSupport {

  private static final String PROVIDER_MODULE = "com.gimle.examples.greeter.provider";
  private static final String GREETER_SPAN_NAME = "com.gimle.examples.greeter.Greeter#greet";

  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void
      a_deployed_modules_request_metrics_and_fabric_call_span_round_trip_through_muninn_as_worker_tier_data()
          throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path consumerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-consumer/target/greeter-consumer-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);
    assertTrue(Files.isRegularFile(consumerJar), "expected a built jar at " + consumerJar);

    submitDeployment(baseUrl, "greeter-provider-deployment", PROVIDER_MODULE, providerJar);
    submitDeployment(
        baseUrl, "greeter-consumer-deployment", "com.gimle.examples.greeter.consumer", consumerJar);

    await(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE");
    await(
        () -> isActive(baseUrl, "greeter-consumer-deployment"),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment should reach ACTIVE");

    // The real fabric call: FabricServer starts a real span around it on the provider's own
    // worker, and WorkerMetrics#recordRequest increments a real counter there too -- both driven
    // by the exact same call this await already proves happened.
    await(
        () -> consumerLogShowsAGreeting(baseUrl),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment's own log should show a real reply from greeter-provider");

    Optional<ProcessHandle> providerWorker =
        findWorkerDescendantForKey(
            cluster.agentProcesses().get(0), "greeter-provider-deployment#0");
    assertTrue(providerWorker.isPresent(), "expected a live worker JVM for the provider instance");
    String workerProcessId = "smoke-node-1:worker-" + providerWorker.get().pid();

    await(
        () -> metricsHistoryShowsWorkerRequestCount(baseUrl, workerProcessId, PROVIDER_MODULE),
        Duration.ofSeconds(60),
        "gimle.module.request.count for "
            + PROVIDER_MODULE
            + " should be shipped by the provider's own worker JVM and readable back through"
            + " /metrics-history/WORKER/"
            + workerProcessId);
    await(
        () -> tracesHistoryShowsSpan(baseUrl, workerProcessId, GREETER_SPAN_NAME),
        Duration.ofSeconds(60),
        "the real "
            + GREETER_SPAN_NAME
            + " span FabricServer started around the consumer's inbound call should be shipped by"
            + " the provider's own worker JVM and readable back through /traces-history/WORKER/"
            + workerProcessId);
  }
}
