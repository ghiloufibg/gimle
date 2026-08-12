package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * QA hardening pass, plaintext-cluster coverage: repeated redeploy stability -- a lighter
 * substitute for CLAUDE.md's own "mandatory acceptance test" (redeploy-in-a-loop, flat metaspace),
 * which already exists at the {@code gimle-module} unit tier ({@code RedeployLoopFlatMetaspaceTest}
 * + {@code RedeployLoopDriver}, 500 cycles with a real {@code MemoryPoolMXBean} read) but has never
 * run against a real multi-process cluster. No remote metaspace reading exists anywhere in this
 * codebase for a separately-launched worker process (the metaspace gauge {@code WorkerMetrics
 * #recordMetaspaceBytes} is never actually called from production code), so this does not attempt
 * to reproduce that measurement -- it proves a narrower, still-valuable property instead: a
 * well-behaved module survives several real redeploy cycles on a real shared worker without {@code
 * LeakTracker} ever reporting a false-positive leak, something {@code ClassloaderLeakIT}'s own
 * single v1-&gt;v2 cycle can't catch.
 */
@Tag("smoke")
class RedeployStabilityIT extends GreeterSmokeClusterSupport {

  private static final int REDEPLOY_CYCLES = 8;
  private static final String SUBJECT_MODULE = "com.gimle.fixture.wellbehaved";

  /**
   * Same anchor role {@link ClassloaderLeakIT} already uses: {@code AgentMain} only kills a worker
   * process outright when the instance being torn down is the *only* one hosted there (Tier 1
   * density's own survival guarantee), so without a second, unrelated {@code TIER_1} module sharing
   * the worker throughout, the subject's own worker would die and respawn on every redeploy cycle
   * -- proving nothing about a single long-lived worker surviving repetition.
   */
  @Test
  @Timeout(value = 8, unit = TimeUnit.MINUTES)
  void a_well_behaved_module_survives_repeated_redeploys_without_ever_reporting_a_leak()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path helloJar =
        repoRoot.resolve(
            "gimle-examples/hello-module/target/hello-module-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(helloJar), "expected a built jar at " + helloJar);
    submitDeployment(baseUrl, "redeploy-anchor-deployment", "com.gimle.examples.hello", helloJar);
    await(
        () -> isActive(baseUrl, "redeploy-anchor-deployment"),
        Duration.ofSeconds(60),
        "redeploy-anchor-deployment should reach ACTIVE before the subject module joins its"
            + " worker");

    // buildInertTier1ModuleJar's own no-op hooks are exactly "well behaved" -- no separate fixture
    // needed beyond what Tier1DensityIT already established.
    for (int i = 0; i < REDEPLOY_CYCLES; i++) {
      String version = "1.0." + i;
      Path jar = buildInertTier1ModuleJar(SUBJECT_MODULE, version);
      submitDeploymentWithReplicas(
          baseUrl, "redeploy-subject-deployment", SUBJECT_MODULE, version, jar, 1);
      await(
          () -> allInstancesOnVersion(baseUrl, "redeploy-subject-deployment", version, 1),
          Duration.ofSeconds(60),
          "redeploy-subject-deployment should reach ACTIVE on version " + version);

      // Checked after every cycle, not just once at the end, so a leak reported mid-run can't be
      // missed even if some later mechanism would otherwise obscure it.
      assertFalse(
          instanceLogContains(
              baseUrl, "redeploy-anchor-deployment", 0, "PLATFORM", "classloader leak detected"),
          "no leak should be reported after redeploy cycle " + i + " (version " + version + ")");
    }

    assertTrue(
        isActive(baseUrl, "redeploy-anchor-deployment"),
        "the anchor should still be ACTIVE after " + REDEPLOY_CYCLES + " subject redeploys");
    assertTrue(
        isActive(baseUrl, "redeploy-subject-deployment"),
        "the subject module should still be ACTIVE after " + REDEPLOY_CYCLES + " redeploys");
    assertEquals(
        1,
        findWorkerDescendants(cluster.agentProcesses().get(0)).size(),
        "the anchor and the repeatedly-redeployed subject should have shared exactly one real"
            + " worker process throughout -- proof this ran on one long-lived worker, not that"
            + " each redeploy silently landed on a fresh one");
  }
}
