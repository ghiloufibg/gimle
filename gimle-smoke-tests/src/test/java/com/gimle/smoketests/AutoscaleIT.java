package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Multi-signal autoscaling (Part C, {@code AutoscaleReconciler}) under real generated load, one
 * scenario per signal: CPU-unreachable-so-request-rate-alone-explains-it, a real ~50%-failure-rate
 * provider variant driving the error-rate signal, and real sustained concurrency (Gatling's closed
 * injection model) driving the queue-depth signal past {@code WorkerRuntime}'s per-module
 * concurrency bound. Each proves its own signal end to end against a real cluster, not just {@code
 * AutoscaleReconcilerTest}'s in-process bypass.
 */
@Tag("smoke")
class AutoscaleIT extends GreeterSmokeClusterSupport {

  /**
   * Multi-metric autoscaling (Part C, {@code AutoscaleReconciler}) under real generated load, not a
   * synthetic stand-in for it: {@code greeter-load-generator} (gimle-examples/) turns every inbound
   * HTTP request into one real cross-worker fabric call to greeter-provider's {@code Greeter}, and
   * Gatling ({@code GreeterAutoscaleSimulation}, spawned as its own JVM the same way every other
   * cluster component here is) drives that HTTP traffic at a real, controlled rate -- so the
   * request rate {@code AutoscaleReconciler}'s policy reads is greeter-provider's own real,
   * worker-reported {@code requestRatePerSecond}, produced by genuine external load, all the way
   * through the real pipeline: worker metrics report (5s) -> agent heartbeat (5s) -> {@code
   * AutoscaleReconciler}'s own tick (2s) -> {@code DeploymentReconciler} scheduling a real second
   * instance. The autoscale policy's {@code targetCpuUtilizationPercent} is set deliberately high
   * (200%, unreachable by this workload) so only the request-rate signal can be what drives the
   * scale-up -- proving a genuinely non-CPU signal works end to end against a real cluster, not
   * just CPU (the one signal that predates Part C and every other real-cluster deployment test here
   * already exercises implicitly).
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployment_scales_up_under_real_gatling_generated_request_rate_load() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitAutoscaleDeployment(
        baseUrl,
        "greeter-provider-autoscale-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0",
        providerJar,
        /* minReplicas= */ 1,
        /* maxReplicas= */ 2,
        /* targetCpuUtilizationPercent= */ 200,
        /* targetRequestRatePerSecond= */ Optional.of(5.0),
        /* targetErrorRatePercent= */ Optional.empty(),
        /* targetQueueDepth= */ Optional.empty());
    await(
        () -> isActive(baseUrl, "greeter-provider-autoscale-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-autoscale-deployment should reach ACTIVE before any load is generated");
    assertEquals(
        1,
        activeInstanceCount(baseUrl, "greeter-provider-autoscale-deployment"),
        "should start at exactly its declared 1 replica before load begins");

    // Comfortably above the 5.0/s target and sustained well past every stage of the real
    // pipeline's own cadence (5s worker report + 5s agent heartbeat + 2s reconcile tick) --
    // generous headroom, not a tight race against those intervals.
    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 20,
            /* durationSeconds= */ 60,
            tempDir.resolve("gatling.log"));
    processes.add(gatling);

    await(
        () -> activeInstanceCount(baseUrl, "greeter-provider-autoscale-deployment") >= 2,
        Duration.ofSeconds(120),
        "greeter-provider-autoscale-deployment should scale up from 1 to 2 replicas under real"
            + " Gatling-generated request-rate load, driven purely by the non-CPU"
            + " targetRequestRatePerSecond signal");
  }

  /**
   * The error-rate autoscaling signal under real load. {@code targetErrorRatePercent} is the one
   * Part C signal never exercised against a real cluster before this -- request rate found a real
   * bug (the {@code DomainCodec} truncation, see above); error rate and queue depth were still
   * unit/integration-only. Deploys {@link #buildFaultyProviderJar()} (real {@code greet} calls,
   * deterministically ~50% of which throw) so the fabric server's own dispatch (see {@code
   * FabricServer#dispatch}) records real errors against the instance's own {@code WorkerMetrics} --
   * the actual signal {@code AutoscaleReconciler}'s {@code errorRatePercent} helper reads, not a
   * synthetic stand-in. CPU and request-rate targets are both set unreachable so only the
   * error-rate signal can explain a scale-up.
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployment_scales_up_under_real_error_rate_load() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    Path faultyProviderJar = buildFaultyProviderJar();

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitAutoscaleDeployment(
        baseUrl,
        "greeter-provider-error-rate-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0-faulty",
        faultyProviderJar,
        /* minReplicas= */ 1,
        /* maxReplicas= */ 2,
        /* targetCpuUtilizationPercent= */ 200,
        /* targetRequestRatePerSecond= */ Optional.empty(),
        // Comfortably below the real ~50% failure rate the faulty provider produces, so a stable
        // request stream alone (no rate target configured) still drives a scale-up purely off
        // error percentage.
        /* targetErrorRatePercent= */ Optional.of(20.0),
        /* targetQueueDepth= */ Optional.empty());
    await(
        () -> isActive(baseUrl, "greeter-provider-error-rate-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-error-rate-deployment should reach ACTIVE before any load is generated");
    assertEquals(
        1,
        activeInstanceCount(baseUrl, "greeter-provider-error-rate-deployment"),
        "should start at exactly its declared 1 replica before load begins");

    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 10,
            /* durationSeconds= */ 60,
            tempDir.resolve("gatling-error-rate.log"));
    processes.add(gatling);

    await(
        () -> activeInstanceCount(baseUrl, "greeter-provider-error-rate-deployment") >= 2,
        Duration.ofSeconds(120),
        "greeter-provider-error-rate-deployment should scale up from 1 to 2 replicas under real"
            + " request failures, driven purely by the non-CPU/non-rate targetErrorRatePercent"
            + " signal");
  }

  /**
   * The queue-depth autoscaling signal under real load. Deploys {@link #buildSlowProviderJar()}
   * (real {@code greet} calls, each sleeping ~300ms) and drives Gatling's <i>closed</i> injection
   * model (see {@code GreeterAutoscaleSimulation}'s own javadoc) to hold more requests continuously
   * in flight than {@code WorkerRuntime}'s per-module {@code BoundedModuleScheduler} concurrency
   * bound (4) -- the only way to build a real, sustained backlog on it. {@code queueDepth} comes
   * straight off that scheduler (see {@code WorkerMain#metricsReportLoop}), the actual signal
   * {@code AutoscaleReconciler}'s {@code targetQueueDepth} reads. CPU, request-rate, and error-rate
   * targets are all unreachable/absent so only the queue-depth signal can explain a scale-up.
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_deployment_scales_up_under_real_queue_depth_load() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    Path slowProviderJar = buildSlowProviderJar();

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitAutoscaleDeployment(
        baseUrl,
        "greeter-provider-queue-depth-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0-slow",
        slowProviderJar,
        /* minReplicas= */ 1,
        /* maxReplicas= */ 2,
        /* targetCpuUtilizationPercent= */ 200,
        /* targetRequestRatePerSecond= */ Optional.empty(),
        /* targetErrorRatePercent= */ Optional.empty(),
        // Comfortably below the ~16-deep backlog 20 concurrent 300ms-each callers sustain against
        // a concurrency bound of 4 (roughly (20-4) requests waiting at any moment).
        /* targetQueueDepth= */ Optional.of(2));
    await(
        () -> isActive(baseUrl, "greeter-provider-queue-depth-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-queue-depth-deployment should reach ACTIVE before any load is generated");
    assertEquals(
        1,
        activeInstanceCount(baseUrl, "greeter-provider-queue-depth-deployment"),
        "should start at exactly its declared 1 replica before load begins");

    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 0,
            /* durationSeconds= */ 60,
            /* concurrentUsers= */ 20,
            tempDir.resolve("gatling-queue-depth.log"));
    processes.add(gatling);

    await(
        () -> activeInstanceCount(baseUrl, "greeter-provider-queue-depth-deployment") >= 2,
        Duration.ofSeconds(120),
        "greeter-provider-queue-depth-deployment should scale up from 1 to 2 replicas under a real"
            + " sustained request backlog, driven purely by the non-CPU/non-rate/non-error"
            + " targetQueueDepth signal");
  }

  /**
   * {@code CombinationMode.WEIGHTED} under real load, blending two genuinely simultaneous signals
   * rather than isolating one at a time the way the three scenarios above each deliberately do.
   * Reuses {@link #buildSlowProviderJar()} and the same closed-injection concurrency Gatling drives
   * for the queue-depth scenario above -- 20 concurrent ~300ms-each callers against a concurrency
   * bound of 4 real, simultaneous request-rate <i>and</i> queue-depth signals, both configured with
   * real, individually-reachable targets and different weights (queue depth weighted 3x request
   * rate), proving the full weighted pipeline (manifest parse -&gt; wire codec -&gt; {@code
   * AutoscaleReconciler}'s blended-ratio math) end to end against a real cluster, not just {@code
   * AutoscaleReconcilerTest}'s in-process bypass.
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_weighted_policy_blends_request_rate_and_queue_depth_signals_under_real_load()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path loadGeneratorJar =
        repoRoot.resolve(
            "gimle-examples/greeter-load-generator/target/greeter-load-generator-"
                + GIMLE_VERSION
                + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    Path slowProviderJar = buildSlowProviderJar();

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitAutoscaleDeployment(
        baseUrl,
        "greeter-provider-weighted-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0-slow",
        slowProviderJar,
        /* minReplicas= */ 1,
        /* maxReplicas= */ 2,
        /* targetCpuUtilizationPercent= */ 200,
        // Comfortably below what 20 concurrent 300ms-each closed-model callers actually sustain
        // (~13 req/s and a ~16-deep backlog respectively against a concurrency bound of 4) -- both
        // signals are genuinely, independently reachable, not just one carrying the other.
        /* targetRequestRatePerSecond= */ Optional.of(5.0),
        /* targetErrorRatePercent= */ Optional.empty(),
        /* targetQueueDepth= */ Optional.of(2),
        /* mode= */ Optional.of("weighted"),
        /* cpuWeight= */ Optional.empty(),
        /* requestRateWeight= */ Optional.of(1.0),
        /* errorRateWeight= */ Optional.empty(),
        /* queueDepthWeight= */ Optional.of(3.0));
    await(
        () -> isActive(baseUrl, "greeter-provider-weighted-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-weighted-deployment should reach ACTIVE before any load is generated");
    assertEquals(
        1,
        activeInstanceCount(baseUrl, "greeter-provider-weighted-deployment"),
        "should start at exactly its declared 1 replica before load begins");

    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 0,
            /* durationSeconds= */ 60,
            /* concurrentUsers= */ 20,
            tempDir.resolve("gatling-weighted.log"));
    processes.add(gatling);

    await(
        () -> activeInstanceCount(baseUrl, "greeter-provider-weighted-deployment") >= 2,
        Duration.ofSeconds(120),
        "greeter-provider-weighted-deployment should scale up from 1 to 2 replicas under real load,"
            + " driven by CombinationMode.WEIGHTED blending the real request-rate and queue-depth"
            + " signals together rather than evaluating either in isolation");
  }
}
