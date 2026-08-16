package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.testkit.Await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Node cordoning, never exercised by any prior real-cluster smoke test. {@code Scheduler}'s own
 * class javadoc states the contract this proves against a real cluster rather than the in-JVM
 * {@code SchedulerTest}/{@code ApiServerTest} fakes: cordoning "never evicts an instance already
 * running on a cordoned node, only keeps new placements off it." This fixture's single-node
 * topology (the sole tier-eligible candidate, cordoned) is exactly the shape that makes {@code
 * Scheduler#place} throw {@code GimleSchedulingException#nodeCordoned} on every reconciler attempt
 * while a new deployment is pending -- the same code path a genuinely capacity-exhausted multi-node
 * cluster would hit, just forced deterministically with one node instead of needing several.
 */
@Tag("smoke")
class NodeCordoningIT extends GreeterSmokeClusterSupport {

  private static final String NODE_ID = "smoke-node-1";

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_cordoned_node_blocks_new_placement_but_never_evicts_an_already_running_instance()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path helloJar =
        repoRoot.resolve(
            "gimle-examples/hello-module/target/hello-module-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);
    assertTrue(Files.isRegularFile(helloJar), "expected a built jar at " + helloJar);

    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Duration.ofSeconds(30));
    Await.until(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE before the node is cordoned");

    cordonNode(baseUrl, NODE_ID);
    Await.until(
        () -> nodeCordonedIs(baseUrl, NODE_ID, true),
        Duration.ofSeconds(30),
        "GET /nodes should report " + NODE_ID + " as cordoned");

    // A cordoned node must never evict an instance already running there -- poll across several
    // real DeploymentReconciler ticks (not a one-shot read right after cordoning) to prove the
    // already-ACTIVE instance stays that way, not just that it hasn't been evicted yet.
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      assertTrue(
          isActive(baseUrl, "greeter-provider-deployment"),
          "an already-running instance must never be evicted by cordoning alone");
      Thread.sleep(1000);
    }

    // A NEW deployment submitted while the node is cordoned must never be able to place: this
    // single-node fixture's only tier-eligible candidate is cordoned, so Scheduler#place has
    // nowhere left to put it.
    submitDeploymentWithRetry(
        baseUrl,
        "hello-module-deployment",
        "com.gimle.examples.hello",
        helloJar,
        Duration.ofSeconds(30));
    long placementDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < placementDeadline) {
      assertFalse(
          isActive(baseUrl, "hello-module-deployment"),
          "a deployment submitted while the sole node is cordoned must never reach ACTIVE");
      Thread.sleep(1000);
    }

    uncordonNode(baseUrl, NODE_ID);
    Await.until(
        () -> nodeCordonedIs(baseUrl, NODE_ID, false),
        Duration.ofSeconds(30),
        "GET /nodes should report " + NODE_ID + " as no longer cordoned");
    Await.until(
        () -> isActive(baseUrl, "hello-module-deployment"),
        Duration.ofSeconds(60),
        "hello-module-deployment should reach ACTIVE once the node is uncordoned");
  }
}
