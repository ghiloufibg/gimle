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
 * Tiered self-healing (CLAUDE.md's own framing: module dispose+reinstantiate vs. worker {@code
 * destroyForcibly}+respawn vs. machine-level reschedule are three distinct recovery paths) at the
 * worker tier: {@code WorkerProcessSupervisor} (gimle-agent) actually respawns a killed worker
 * process and the deployment genuinely recovers to {@code ACTIVE}, not just that the agent's own
 * bookkeeping believes it should. A natural home for future self-healing scenarios too (e.g.
 * crash-loop backoff, restart-budget exhaustion) as they're added.
 */
@Tag("smoke")
class SelfHealingIT extends GreeterSmokeClusterSupport {

  /**
   * QA hardening pass, Phase 3: the agent-death test above (and every other existing scenario in
   * this class) never kills the *worker* JVM itself -- a genuinely different failure domain (see
   * CLAUDE.md's own "tiered self-healing" framing: module dispose+reinstantiate vs. worker
   * destroyForcibly+respawn vs. machine-level reschedule are three distinct recovery paths). This
   * proves the middle tier: WorkerProcessSupervisor (gimle-agent) actually respawns a killed worker
   * process and the deployment genuinely recovers to ACTIVE again, not just that the agent's own
   * bookkeeping believes it should.
   */
  @Test
  @Timeout(value = 6, unit = java.util.concurrent.TimeUnit.MINUTES)
  void a_crashed_workers_instance_is_respawned_and_returns_to_active() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    provisionTenantAndSecret(baseUrl);
    submitDeployment(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Optional.of(SECRET_TENANT_ID));
    await(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE before the worker is killed");

    ProcessHandle firstWorker =
        findWorkerDescendant(cluster.agentProcess())
            .orElseThrow(
                () -> new AssertionError("expected a live WorkerMain descendant of the agent"));
    long firstWorkerPid = firstWorker.pid();
    firstWorker.destroyForcibly();

    await(
        () -> {
          Optional<ProcessHandle> current = findWorkerDescendant(cluster.agentProcess());
          // Both halves matter: a *new* worker process (proof the supervisor actually respawned
          // one, not that the old one lingers) that has *also* brought the deployment back to
          // ACTIVE (proof the respawned worker is genuinely healthy, not just alive).
          return current.isPresent()
              && current.get().pid() != firstWorkerPid
              && isActive(baseUrl, "greeter-provider-deployment");
        },
        Duration.ofSeconds(60),
        "a new worker process should replace pid "
            + firstWorkerPid
            + " and the deployment should return to ACTIVE");
  }
}
