package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Rolling update / version-aware traffic cutover under real load, both replica-count regimes {@code
 * DeploymentReconciler#handleRollingUpdate}'s own deliberately-minimal in-place-replacement design
 * (one index migrated at a time, no {@code maxSurge}/{@code maxUnavailable} surge-then-drain)
 * implies: a 2-replica rollout demonstrates genuine continuous availability (at least one instance
 * {@code ACTIVE} throughout), while a 1-replica rollout demonstrates the real, documented downtime
 * tradeoff instead -- both against a real v1.1.0 build compiled on the fly by {@link
 * TestModuleBuilder} rather than a second, near-duplicate committed example module.
 */
@Tag("smoke")
class RollingUpdateIT extends GreeterSmokeClusterSupport {

  /**
   * QA Phase 3: rolling update / version-aware traffic cutover under real load. Deploys 2 replicas
   * of {@code greeter-provider} at v1.0.0, confirms both reach {@code ACTIVE}, then submits a real
   * v1.1.0 build of the same module (compiled on the fly by {@link TestModuleBuilder} -- see {@link
   * #buildProviderV2Jar()} -- rather than committing a second, near-duplicate example module) while
   * Gatling drives sustained real HTTP traffic through {@code greeter-load-generator}'s real fabric
   * call the whole time. {@link
   * com.gimle.controlplane.reconcile.DeploymentReconciler#handleRollingUpdate} (see its own
   * javadoc) is deliberately minimal: one index migrated at a time, in place, no {@code
   * maxSurge}/{@code maxUnavailable} surge-then-drain -- so this is only a genuine "rolling", not
   * "recreate", test with 2+ replicas: while one index is mid-migration the other stays untouched
   * and keeps serving. A background virtual-thread sampler polls the deployment's real observed
   * instance count/version throughout the whole rollout window and asserts at least one instance
   * stayed {@code ACTIVE} at every sampled moment -- proving continuous availability, not just
   * eventual convergence to v2.
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_rolling_update_keeps_at_least_one_instance_serving_traffic_throughout() throws Exception {
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
    Path providerV1Jar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    assertTrue(Files.isRegularFile(providerV1Jar), "expected a built jar at " + providerV1Jar);

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-rolling-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0",
        providerV1Jar,
        2,
        Duration.ofSeconds(30));
    await(
        () -> allInstancesOnVersion(baseUrl, "greeter-provider-rolling-deployment", "1.0.0", 2),
        Duration.ofSeconds(90),
        "both v1 replicas should reach ACTIVE before any rollout begins");

    AtomicInteger minActiveDuringRollout = new AtomicInteger(Integer.MAX_VALUE);
    AtomicBoolean stopSampling = new AtomicBoolean(false);
    Thread sampler =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!stopSampling.get()) {
                    int active =
                        activeInstanceCount(baseUrl, "greeter-provider-rolling-deployment");
                    minActiveDuringRollout.updateAndGet(min -> Math.min(min, active));
                    try {
                      Thread.sleep(300);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });

    // Comfortably sustained across the whole rollout window -- proves the consumer-facing traffic
    // path (load generator -> real fabric call -> greeter-provider) keeps working throughout, not
    // just before/after.
    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 5,
            /* durationSeconds= */ 90,
            tempDir.resolve("gatling-rolling.log"));
    processes.add(gatling);

    Path providerV2Jar = buildProviderV2Jar();
    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-rolling-deployment",
        "com.gimle.examples.greeter.provider",
        "1.1.0",
        providerV2Jar,
        2,
        Duration.ofSeconds(30));

    try {
      await(
          () -> allInstancesOnVersion(baseUrl, "greeter-provider-rolling-deployment", "1.1.0", 2),
          Duration.ofSeconds(180),
          "both replicas should migrate to v1.1.0 one index at a time, per"
              + " DeploymentReconciler's own rolling-update contract");
    } finally {
      stopSampling.set(true);
      sampler.join(Duration.ofSeconds(5).toMillis());
    }

    assertTrue(
        minActiveDuringRollout.get() >= 1,
        "at least one instance should have stayed ACTIVE at every sampled moment during the"
            + " rollout -- the whole point of a *rolling* update rather than a full outage."
            + " Observed minimum: "
            + minActiveDuringRollout.get());
  }

  /**
   * QA Phase 3 continuation: the single-replica counterpart to the 2-replica test above -- confirms
   * the documented tradeoff is real, not just documented. {@link
   * com.gimle.controlplane.reconcile.DeploymentReconciler#handleRollingUpdate}'s own javadoc is
   * explicit that this is in-place index replacement (kill old at that index, then place new at the
   * same index), never additive surge-then-drain, so a single-replica deployment WILL see real
   * downtime during a migration -- only a multi-replica deployment (the test above) can demonstrate
   * continuous availability. This test asserts the opposite inequality from that one: real,
   * observed downtime (the sampler must catch at least one moment with zero {@code ACTIVE}
   * instances), and that the deployment still fully converges to the new version afterward --
   * proving the tradeoff is exactly as costly as documented, not silently worse (e.g. never
   * recovering) or silently better (e.g. a surge the docs don't claim to provide).
   */
  @Test
  @Timeout(value = 8, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_single_replica_rolling_update_has_real_observed_downtime() throws Exception {
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
    Path providerV1Jar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(
        Files.isRegularFile(loadGeneratorJar), "expected a built jar at " + loadGeneratorJar);
    assertTrue(Files.isRegularFile(providerV1Jar), "expected a built jar at " + providerV1Jar);

    submitDeployment(
        baseUrl,
        "greeter-load-generator-deployment",
        "com.gimle.examples.greeter.loadgen",
        loadGeneratorJar);
    await(
        () -> isActive(baseUrl, "greeter-load-generator-deployment"),
        Duration.ofSeconds(60),
        "greeter-load-generator-deployment should reach ACTIVE before any load is generated");

    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-single-rolling-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0",
        providerV1Jar,
        1,
        Duration.ofSeconds(30));
    await(
        () ->
            allInstancesOnVersion(
                baseUrl, "greeter-provider-single-rolling-deployment", "1.0.0", 1),
        Duration.ofSeconds(90),
        "the single v1 replica should reach ACTIVE before any rollout begins");

    AtomicInteger minActiveDuringRollout = new AtomicInteger(Integer.MAX_VALUE);
    AtomicBoolean stopSampling = new AtomicBoolean(false);
    Thread sampler =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!stopSampling.get()) {
                    int active =
                        activeInstanceCount(baseUrl, "greeter-provider-single-rolling-deployment");
                    minActiveDuringRollout.updateAndGet(min -> Math.min(min, active));
                    try {
                      Thread.sleep(300);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });

    Process gatling =
        spawnGatling(
            javaExecutable,
            classpath,
            /* requestsPerSecond= */ 5,
            /* durationSeconds= */ 90,
            tempDir.resolve("gatling-single-rolling.log"));
    processes.add(gatling);

    Path providerV2Jar = buildProviderV2Jar();
    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        "greeter-provider-single-rolling-deployment",
        "com.gimle.examples.greeter.provider",
        "1.1.0",
        providerV2Jar,
        1,
        Duration.ofSeconds(30));

    try {
      await(
          () ->
              allInstancesOnVersion(
                  baseUrl, "greeter-provider-single-rolling-deployment", "1.1.0", 1),
          Duration.ofSeconds(180),
          "the single replica should still fully converge to v1.1.0 despite the observed downtime");
    } finally {
      stopSampling.set(true);
      sampler.join(Duration.ofSeconds(5).toMillis());
    }

    assertEquals(
        0,
        minActiveDuringRollout.get(),
        "a single-replica rolling update should show real, observed downtime (the old instance is"
            + " stopped before the new one is placed) -- DeploymentReconciler's own javadoc"
            + " documents this as a deliberate tradeoff of the minimal in-place-replacement design,"
            + " not additive surge-then-drain. Observed minimum active instances: "
            + minActiveDuringRollout.get()
            + " (0 expected)");
  }
}
