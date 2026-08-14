package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tiered self-healing (CLAUDE.md's own framing: module dispose+reinstantiate vs. worker {@code
 * destroyForcibly}+respawn vs. machine-level reschedule are three distinct recovery paths): the
 * worker tier, where {@code WorkerProcessSupervisor} (gimle-agent) actually respawns a killed
 * worker process and the deployment genuinely recovers to {@code ACTIVE}; and the module tier one
 * level down, where a module that never passes its own liveness check exhausts its own
 * dispose+reinstantiate restart budget and is escalated to {@code FAILED} for good instead of
 * retrying forever.
 */
@Tag("smoke")
class SelfHealingIT extends GreeterSmokeClusterSupport {

  /**
   * The agent-death test above (and every other existing scenario in this class) never kills the
   * *worker* JVM itself -- a genuinely different failure domain (see CLAUDE.md's own "tiered
   * self-healing" framing: module dispose+reinstantiate vs. worker destroyForcibly+respawn vs.
   * machine-level reschedule are three distinct recovery paths). This proves the middle tier:
   * WorkerProcessSupervisor (gimle-agent) actually respawns a killed worker process and the
   * deployment genuinely recovers to ACTIVE again, not just that the agent's own bookkeeping
   * believes it should.
   */
  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
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
        findWorkerDescendant(cluster.agentProcesses().get(0))
            .orElseThrow(
                () -> new AssertionError("expected a live WorkerMain descendant of the agent"));
    long firstWorkerPid = firstWorker.pid();
    firstWorker.destroyForcibly();

    await(
        () -> {
          Optional<ProcessHandle> current = findWorkerDescendant(cluster.agentProcesses().get(0));
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

  /**
   * The module tier of the same escalation chain the test above exercises at the worker tier --
   * this module ({@link GreeterSmokeClusterSupport#buildAlwaysUnhealthyProviderJar()}) starts and
   * serves normally but its own {@code LivenessProbe} always reports {@code isAlive() == false}, so
   * {@code WorkerRuntime#onLivenessResult} drives its own module-tier {@code RestartTracker}
   * (dispose + reinstantiate, never a worker-JVM respawn) through repeated backoff-delayed restart
   * attempts. Proves the give-up path specifically: once that tracker's budget is exhausted, {@code
   * ModuleController#forceFailed} flips the instance to {@code FAILED} for good, which is the real,
   * observable end state {@code WorkerProcessSupervisorTest}'s own worker-tier equivalent only
   * proves in-process via a package-private accessor.
   */
  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void a_module_that_never_passes_its_own_liveness_check_exhausts_its_restart_budget_and_fails()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path unhealthyJar = buildAlwaysUnhealthyProviderJar();
    // submitDeployment's own convenience overloads hardcode "version: 1.0.0" in the manifest they
    // PUT, but this fixture's own descriptor declares "1.0.0-unhealthy" (matching the "-faulty"/
    // "-slow" naming buildFaultyProviderJar/buildSlowProviderJar already use) -- submitting via
    // the version-aware overload here is what actually resolves that moduleId, not an unrelated
    // one the worker NACKs with "module not registered".
    submitDeploymentWithReplicas(
        baseUrl,
        "greeter-unhealthy-deployment",
        "com.gimle.examples.greeter.provider",
        "1.0.0-unhealthy",
        unhealthyJar,
        1,
        Optional.empty());

    await(
        () -> hasFailedInstance(baseUrl, "greeter-unhealthy-deployment"),
        Duration.ofSeconds(90),
        "greeter-unhealthy-deployment should exhaust its restart budget and reach FAILED");
  }
}
