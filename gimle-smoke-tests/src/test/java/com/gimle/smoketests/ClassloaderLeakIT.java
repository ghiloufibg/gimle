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
 * Classloader leak detection (CLAUDE.md's own framing -- "first-class", a {@code PhantomReference}
 * to a disposed module's own loader, reported if it survives a configurable window). Building this
 * scenario surfaced a real gap: {@code WorkerRuntime}'s {@code LeakTracker} existed only in {@code
 * gimle-module}'s own unit tests -- {@code WorkerMain} never actually wired it into the real {@code
 * ModuleController} it constructs, so a real leak in a real deployed module went completely
 * undetected and unreported. Fixed alongside this test: {@code WorkerMain} now constructs a {@code
 * LeakTracker} and wires {@code LeakTracker#track} as {@code ModuleController}'s own {@code
 * onDisposed} callback, logging a {@code ModuleLeakDetected} via slf4j so it lands in that worker's
 * real {@code worker-platform.log}.
 */
@Tag("smoke")
class ClassloaderLeakIT extends GreeterSmokeClusterSupport {

  /**
   * A module whose {@code onStart} deliberately leaves a platform thread running that {@code
   * onStop} never interrupts -- {@link GreeterSmokeClusterSupport#buildLeakyProviderJar} for the
   * full reasoning -- redeployed once to force its disposal, then polls the shared worker's own
   * {@code PLATFORM} log for {@code LeakTracker}'s own report line. Needs a second, unrelated
   * {@code TIER_1} module (the real, deliberately-inert {@code hello-module}) sharing the same
   * worker throughout: {@code AgentMain} only kills a worker process outright when the instance
   * being torn down is the *only* one hosted there (Tier 1 density's own survival guarantee) --
   * without that anchor, the leaky instance's own worker (and the {@code LeakTracker} living inside
   * it) would be killed together with it, before the detection window ever had a chance to fire.
   */
  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_module_that_leaks_its_own_classloader_on_redeploy_is_reported_by_leak_tracker()
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
    submitDeployment(baseUrl, "leak-anchor-deployment", "com.gimle.examples.hello", helloJar);
    await(
        () -> isActive(baseUrl, "leak-anchor-deployment"),
        Duration.ofSeconds(60),
        "leak-anchor-deployment should reach ACTIVE before the leaky module joins its worker");

    Path leakyV1 = buildLeakyProviderJar("1.0.0-leaky");
    submitDeploymentWithReplicas(
        baseUrl,
        "leaky-deployment",
        "com.gimle.fixture.leaky",
        "1.0.0-leaky",
        leakyV1,
        1,
        Optional.empty());
    await(
        () -> isActive(baseUrl, "leaky-deployment"),
        Duration.ofSeconds(60),
        "leaky-deployment should reach ACTIVE before being redeployed");

    // Redeploying disposes v1's ModuleLayer in place (DeploymentReconciler's own rolling-update
    // contract) -- but v1's own onStart left a platform thread running that onStop never
    // interrupts, so that thread's own Runnable (loaded by v1's classloader) keeps it reachable,
    // and therefore un-collectable, for as long as the thread itself keeps running: deliberately.
    Path leakyV2 = buildLeakyProviderJar("1.0.1-leaky");
    submitDeploymentWithReplicas(
        baseUrl,
        "leaky-deployment",
        "com.gimle.fixture.leaky",
        "1.0.1-leaky",
        leakyV2,
        1,
        Optional.empty());
    await(
        () -> allInstancesOnVersion(baseUrl, "leaky-deployment", "1.0.1-leaky", 1),
        Duration.ofSeconds(60),
        "leaky-deployment should migrate to 1.0.1-leaky");

    await(
        () ->
            instanceLogContains(
                baseUrl, "leak-anchor-deployment", 0, "PLATFORM", "classloader leak detected"),
        Duration.ofSeconds(90),
        "WorkerMain's LeakTracker should report the v1 instance's deliberately-retained"
            + " classloader as a leak within its own detection window, in the shared worker's own"
            + " platform log (served under the anchor's own instance key -- see"
            + " AgentMain#startShippingInstanceLogs's own javadoc on which instance's key a"
            + " Tier-1-density worker's real platform log lives under)");
  }
}
