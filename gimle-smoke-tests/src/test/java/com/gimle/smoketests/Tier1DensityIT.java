package com.gimle.smoketests;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * QA hardening pass, plaintext-cluster coverage: Tier 1 density packing (P1-1), specifically the
 * {@code MAX_TIER1_DENSITY} cap -- previously proven only by {@code AgentMainTest}'s in-process
 * fakes ({@code a_worker_at_the_density_cap_is_not_reused}), never against real worker JVMs. {@code
 * ClassloaderLeakIT} already incidentally packs two different real {@code TIER_1} modules onto one
 * worker as a side effect of needing a stable anchor, confirming the reuse mechanism works end to
 * end for two modules -- this proves the cap itself: four distinct modules really do share one
 * worker process, and a fifth genuinely gets a new one, matching {@code AgentMain
 * #findReusableTier1Worker}'s own density-limit check.
 */
@Tag("smoke")
class Tier1DensityIT extends GreeterSmokeClusterSupport {

  private static final String[] MODULE_NAMES = {
    "com.gimle.fixture.density.a",
    "com.gimle.fixture.density.b",
    "com.gimle.fixture.density.c",
    "com.gimle.fixture.density.d",
    "com.gimle.fixture.density.e"
  };

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_fifth_tier1_module_on_one_node_gets_a_new_worker_once_the_density_cap_is_reached()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    for (int i = 0; i < 4; i++) {
      String moduleName = MODULE_NAMES[i];
      Path jar = buildInertTier1ModuleJar(moduleName, "1.0.0");
      submitDeploymentWithReplicas(
          baseUrl, moduleName + "-deployment", moduleName, "1.0.0", jar, 1);
      await(
          () -> isActive(baseUrl, moduleName + "-deployment"),
          Duration.ofSeconds(60),
          moduleName + "-deployment should reach ACTIVE");
    }

    // All four distinct TIER_1 modules should have packed onto a single real worker JVM --
    // wrapped in a short poll rather than a bare one-shot read, since ACTIVE (the control plane's
    // own heartbeat-derived state) and ProcessHandle#descendants() (a direct OS process-tree
    // walk) are observed through two different channels.
    await(
        () -> findWorkerDescendants(cluster.agentProcesses().get(0)).size() == 1,
        Duration.ofSeconds(15),
        "four distinct untenanted TIER_1 modules should share exactly one worker process");

    String fifthModule = MODULE_NAMES[4];
    Path fifthJar = buildInertTier1ModuleJar(fifthModule, "1.0.0");
    submitDeploymentWithReplicas(
        baseUrl, fifthModule + "-deployment", fifthModule, "1.0.0", fifthJar, 1);
    await(
        () -> isActive(baseUrl, fifthModule + "-deployment"),
        Duration.ofSeconds(60),
        fifthModule + "-deployment should reach ACTIVE");

    await(
        () -> findWorkerDescendants(cluster.agentProcesses().get(0)).size() == 2,
        Duration.ofSeconds(15),
        "a fifth distinct TIER_1 module should genuinely spawn a new worker once"
            + " MAX_TIER1_DENSITY is reached, not pack onto the same worker as the first four");
  }
}
