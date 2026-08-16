package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.testkit.Await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code maxSurge} pinned promotion: {@code DeploymentReconciler#handleSurge} retargets an
 * already-running, already-healthy surge worker onto its final index instead of removing it and
 * starting a fresh one, and {@code AgentMain#renameInPlace} carries that retarget out without ever
 * restarting the worker's real OS process. Nothing previously proved this against a real,
 * separately-spawned worker JVM rather than an in-memory {@code SupervisedInstance}.
 *
 * <p>2 replicas with {@code disruption: {maxUnavailable: 1, maxSurge: 1}}: per {@code
 * DeploymentReconcilerSurgeTest}'s own documented mechanics, {@code maxUnavailable} always claims
 * the lowest mismatched index first (0, ordinary in-place restart) and {@code maxSurge} claims the
 * remaining one (1, provisioned ahead at the next free synthetic index -- deterministically {@code
 * replicas}, i.e. 2, on a deployment's first rollout) -- so this shape reliably exercises both
 * paths side by side in one rollout, letting the same test prove both "the promoted worker's
 * process survives" and, by contrast, "the ordinarily-restarted worker's process does not."
 */
@Tag("smoke")
class SurgePromotionIT extends GreeterSmokeClusterSupport {

  private static final String NODE_ID = "smoke-node-1";

  private static final String DEPLOYMENT_NAME = "greeter-provider-surge-deployment";

  /**
   * Submits the initial 2-replica, {@code maxSurge: 1} rollout at v1.0.0 and awaits both replicas
   * reaching {@code ACTIVE} -- the stable starting point every assertion in this test builds on.
   */
  private void awaitInitialRollout(String baseUrl, Path providerV1Jar) throws Exception {
    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        DEPLOYMENT_NAME,
        "com.gimle.examples.greeter.provider",
        "1.0.0",
        providerV1Jar,
        /* replicas= */ 2,
        Optional.of(new Disruption(/* maxUnavailable= */ 1, /* maxSurge= */ 1)),
        Duration.ofSeconds(30));
    Await.until(
        () -> allInstancesOnVersion(baseUrl, DEPLOYMENT_NAME, "1.0.0", 2),
        Duration.ofSeconds(90),
        "both v1 replicas should reach ACTIVE before any rollout begins");
  }

  /**
   * The live worker's pid for {@code key}, or a hard test failure if none is currently running --
   * {@code contextSuffix} lets each call site describe what stage of the rollout it expected a
   * worker to exist at, without duplicating the lookup-then-fail plumbing itself.
   */
  private long requireWorkerPid(Process agentProcess, String key, String contextSuffix) {
    return findWorkerDescendantForKey(agentProcess, key)
        .orElseThrow(() -> new AssertionError("expected a live worker for " + key + contextSuffix))
        .pid();
  }

  /**
   * Awaits the surge worker's own appearance at {@code surgeKey} (the next free synthetic index,
   * deterministically index 2 on a deployment's first rollout) and captures its pid the moment it
   * first appears, while it's still running under its own spawn-time key, before any promotion has
   * happened.
   */
  private long captureSurgeWorkerPid(Process agentProcess, String surgeKey) {
    AtomicLong surgePid = new AtomicLong(-1);
    Await.until(
        () -> {
          Optional<ProcessHandle> surgeWorker = findWorkerDescendantForKey(agentProcess, surgeKey);
          surgeWorker.ifPresent(handle -> surgePid.set(handle.pid()));
          return surgeWorker.isPresent();
        },
        Duration.ofSeconds(90),
        "a surge worker at synthetic index 2 should be provisioned ahead of promotion");
    return surgePid.get();
  }

  /** Awaits both replicas converging to v1.1.0, one via ordinary restart, one via promotion. */
  private void awaitConvergenceToV2(String baseUrl) {
    Await.until(
        () -> allInstancesOnVersion(baseUrl, DEPLOYMENT_NAME, "1.1.0", 2),
        Duration.ofSeconds(120),
        "both replicas should converge to v1.1.0 -- index 0 via an ordinary restart, index 1 via"
            + " promotion of the surge instance");
  }

  /**
   * The promoted path's own proof: the worker spawned under {@code surgeKey} is still alive and
   * still the exact same OS process -- {@code AgentMain#renameInPlace} only re-keys in-memory
   * bookkeeping and notifies the worker over its control channel, it never stops/restarts it.
   * {@code findWorkerDescendantForKey} matches on the spawn-time key, which the live process's
   * command line never stops carrying, so resolving it again after convergence and comparing {@code
   * pid()} is a direct proof of "still the same process" -- not an inference from timing or absence
   * of a restart log line.
   */
  private void assertPromotedWorkerSurvived(Process agentProcess, String surgeKey, long surgePid) {
    ProcessHandle survivingSurgeWorker =
        findWorkerDescendantForKey(agentProcess, surgeKey)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the surge worker spawned under key "
                            + surgeKey
                            + " should still be running after promotion -- pinned promotion must"
                            + " never tear it down"));
    assertEquals(
        surgePid,
        survivingSurgeWorker.pid(),
        "the worker process backing the promoted instance should be the exact same OS process"
            + " throughout promotion -- pid changed, meaning it was restarted rather than"
            + " retargeted in place");
  }

  /**
   * The contrasting, ordinarily-restarted path: {@code maxUnavailable} claims index 0 outright,
   * which goes through {@code AgentMain}'s plain stopInstance-then-startInstance sequence -- a
   * genuinely new process, unlike the promoted index 1.
   */
  private void assertRestartedWorkerReplaced(Process agentProcess, String key, long originalPid) {
    long finalPid = requireWorkerPid(agentProcess, key, " after convergence");
    assertNotEquals(
        originalPid,
        finalPid,
        "index 0 went through the ordinary maxUnavailable restart path, so its worker process"
            + " should have been replaced -- contrast with index 1's promoted worker, which keeps"
            + " running throughout");
  }

  /** The agent's own platform log recording the pinned-promotion rename, not a plain restart. */
  private void assertPlatformLogRecordsPromotion(String baseUrl, String surgeKey) {
    assertTrue(
        agentPlatformLogContains(
            baseUrl,
            NODE_ID,
            "instance "
                + surgeKey
                + " retargeted to "
                + DEPLOYMENT_NAME
                + "#1 -- renaming in place, no restart"),
        "the agent's own platform log should record the pinned-promotion rename from the surge"
            + " key to the target key");
  }

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_promoted_surge_worker_keeps_the_same_process_while_the_restarted_one_does_not()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);
    Process agentProcess = cluster.agentProcesses().get(0);
    String surgeKey = DEPLOYMENT_NAME + "#2";

    Path providerV1Jar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerV1Jar), "expected a built jar at " + providerV1Jar);

    awaitInitialRollout(baseUrl, providerV1Jar);
    long index0OriginalPid = requireWorkerPid(agentProcess, DEPLOYMENT_NAME + "#0", "");

    Path providerV2Jar = buildProviderV2Jar();
    submitDeploymentWithReplicasWithRetry(
        baseUrl,
        DEPLOYMENT_NAME,
        "com.gimle.examples.greeter.provider",
        "1.1.0",
        providerV2Jar,
        /* replicas= */ 2,
        Optional.of(new Disruption(/* maxUnavailable= */ 1, /* maxSurge= */ 1)),
        Duration.ofSeconds(30));

    // The surge instance is always placed at the next free synthetic index >= replicas
    // (DeploymentReconciler#nextFreeSurgeIndex) -- deterministically index 2 here, since this
    // deployment has never surged before.
    long surgePid = captureSurgeWorkerPid(agentProcess, surgeKey);

    awaitConvergenceToV2(baseUrl);

    assertPromotedWorkerSurvived(agentProcess, surgeKey, surgePid);
    assertRestartedWorkerReplaced(agentProcess, DEPLOYMENT_NAME + "#0", index0OriginalPid);
    assertPlatformLogRecordsPromotion(baseUrl, surgeKey);
  }
}
