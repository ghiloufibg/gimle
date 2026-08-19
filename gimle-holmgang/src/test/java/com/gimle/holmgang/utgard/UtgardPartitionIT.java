package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gimle.holmgang.WorkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * Disconnects one machine's whole container from the shared Docker network -- a real network
 * partition, distinct from {@code UtgardMachineLossIT}'s hard kill: every process inside, including
 * the platform's own agent, stays alive and keeps running, it simply cannot reach or be reached by
 * anything else in the cluster. Asserts the same gossip-driven failure detection reschedules the
 * hosted instance onto the surviving node while the partition holds, then reconnects the network
 * and asserts the cluster converges back to exactly one {@code ACTIVE} instance -- proving the
 * platform reconciles away the stale replica the reconnected (never-killed) agent still thinks it's
 * running, rather than ending up with two.
 *
 * <p>Topology mirrors {@code UtgardMachineLossIT}: {@code helm-1} hosts the store, control plane,
 * and Fafnir; {@code helm-2} and {@code helm-3} each host one agent.
 *
 * <p>This suite could not be run against a live Docker daemon in the environment this class was
 * authored in -- see {@code UtgardMachines}' own javadoc and this class's {@code @BeforeAll} for
 * the clean-skip path a blocked image pull takes here. The scenario is written to run for real
 * elsewhere.
 */
@Tag("holmgang")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UtgardPartitionIT {

  private static final String GIMLE_VERSION = "0.1.0-alpha.2";
  private static final String SERVER_MACHINE = "helm-1";
  private static final String AGENT_MACHINE_A = "helm-2";
  private static final String AGENT_MACHINE_B = "helm-3";
  private static final String TOPOLOGY_PATH = "/opt/gimle/topology.yaml";
  private static final String DEPLOYMENT_NAME = "utgard-partition-greeter";
  private static final String CONTAINER_JAR_PATH = "/opt/gimle/examples/greeter-provider.jar";

  private UtgardMachines machines;
  private final Path workDir =
      Path.of("target", "holmgang", "utgard-partition-" + Long.toHexString(System.nanoTime()));
  private boolean failed;

  @BeforeAll
  void startFleet() {
    try {
      machines = UtgardMachines.start(List.of(SERVER_MACHINE, AGENT_MACHINE_A, AGENT_MACHINE_B));
    } catch (final UtgardDockerUnavailableException e) {
      assumeTrue(false, e.getMessage());
    }
  }

  @AfterAll
  void stopFleet() {
    if (machines != null) {
      machines.close();
    }
    if (WorkDirs.shouldDelete(failed)) {
      WorkDirs.deleteRecursively(workDir);
    }
  }

  @Test
  @Timeout(value = 12, unit = TimeUnit.MINUTES)
  void a_partitioned_machine_is_rescheduled_around_then_the_cluster_converges_on_reconnect() {
    try {
      runScenario();
    } catch (final RuntimeException | AssertionError e) {
      failed = true;
      throw e;
    }
  }

  private void runScenario() {
    final String topologyYaml =
        UtgardTopologies.plaintext(
            "utgard-partition",
            List.of(SERVER_MACHINE, AGENT_MACHINE_A, AGENT_MACHINE_B),
            SERVER_MACHINE,
            SERVER_MACHINE,
            SERVER_MACHINE,
            List.of(AGENT_MACHINE_A, AGENT_MACHINE_B));
    machines.writeFileToEveryMachine(topologyYaml, TOPOLOGY_PATH);

    bringUpMachine(SERVER_MACHINE);
    bringUpMachine(AGENT_MACHINE_A);
    bringUpMachine(AGENT_MACHINE_B);

    final Path jar = exampleJar("greeter-provider");
    machines.copyHostFileToContainer(AGENT_MACHINE_A, jar, CONTAINER_JAR_PATH);
    machines.copyHostFileToContainer(AGENT_MACHINE_B, jar, CONTAINER_JAR_PATH);

    final String baseUrl =
        "http://localhost:"
            + machines.mappedPort(SERVER_MACHINE, UtgardMachines.CONTROL_PLANE_PORT);
    final UtgardControlPlaneClient client = new UtgardControlPlaneClient(baseUrl);
    UtgardPoll.await(client::isServing, Duration.ofSeconds(30), "control plane serving");

    client.submitDeployment(
        DEPLOYMENT_NAME, "com.gimle.examples.greeter.provider", "1.0.0", CONTAINER_JAR_PATH, 1);
    UtgardPoll.await(
        () -> client.isActive(DEPLOYMENT_NAME),
        Duration.ofMinutes(2),
        "initial placement reaching ACTIVE");

    final String originalNodeId =
        client
            .nodeOfInstanceZero(DEPLOYMENT_NAME)
            .orElseThrow(
                () -> new AssertionError("no node hosts instance 0 of " + DEPLOYMENT_NAME));
    final String partitionedMachine = machineOfNodeId(originalNodeId);
    final String survivingMachine =
        partitionedMachine.equals(AGENT_MACHINE_A) ? AGENT_MACHINE_B : AGENT_MACHINE_A;

    machines.disconnectFromNetwork(partitionedMachine);
    try {
      // Real gossip suspicion/confirmation plus reschedule takes real wall-clock time.
      UtgardPoll.await(
          () ->
              client.isActive(DEPLOYMENT_NAME)
                  && client
                      .nodeOfInstanceZero(DEPLOYMENT_NAME)
                      .map(id -> !id.equals(originalNodeId))
                      .orElse(false),
          Duration.ofMinutes(5),
          "instance rescheduled off the partitioned node " + originalNodeId);
      assertEquals(
          survivingMachine,
          machineOfNodeId(
              client
                  .nodeOfInstanceZero(DEPLOYMENT_NAME)
                  .orElseThrow(() -> new AssertionError("instance lost placement"))),
          "expected the rescheduled instance on the surviving machine " + survivingMachine);
    } finally {
      machines.reconnectToNetwork(partitionedMachine);
    }

    // The reconnected agent never died -- it still believes it's running the original instance.
    // Convergence means the reconciler tears that stale replica down rather than the cluster
    // settling at two ACTIVE instances for one replica of desired state.
    UtgardPoll.await(
        () -> client.activeInstanceCount(DEPLOYMENT_NAME) == 1,
        Duration.ofMinutes(3),
        "cluster converging back to exactly one ACTIVE instance after reconnect");
  }

  private void bringUpMachine(final String machine) {
    final UtgardExecResult result =
        machines.hilmir(machine, "up", "-f", TOPOLOGY_PATH, "--machine", machine);
    UtgardForensics.record(workDir, "up-" + machine, result);
    UtgardExec.requireSuccess(result, "up on " + machine);
  }

  private static String machineOfNodeId(final String nodeId) {
    return nodeId.startsWith("node-") ? nodeId.substring("node-".length()) : nodeId;
  }

  private static Path exampleJar(final String artifact) {
    final Path jar =
        Path.of("")
            .toAbsolutePath()
            .getParent()
            .resolve("gimle-examples")
            .resolve(artifact)
            .resolve("target")
            .resolve(artifact + "-" + GIMLE_VERSION + ".jar");
    assertTrue(
        Files.isRegularFile(jar),
        "expected a built jar at " + jar + " -- run `mvn install` before -Pvalidation");
    return jar;
  }
}
