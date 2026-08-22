package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gimle.holmgang.WorkDirs;
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
 * An mtls topology addressed entirely by real container network aliases -- the one Utgard scenario
 * that only means anything with genuine DNS names, unlike {@code gimle-holmgang}'s own single-host
 * mTLS topologies, which are permanently stuck presenting a {@code localhost} certificate because
 * {@code GimleCluster} runs every process as a loopback-addressed subprocess on one machine. {@code
 * hilmir pki init} generates cluster TLS material inside {@code helm-1} (the platform's PKI mints a
 * single-hostname server SAN, so every server-side role -- store, control plane, Fafnir -- is
 * deliberately placed there too, see {@code UtgardTopologies#mtls}); that material is then copied
 * to {@code helm-2} the way an operator would by hand (a real {@code docker cp}-equivalent, see
 * {@code UtgardMachines#copyDirectoryBetweenContainers}); {@code hilmir up} on each machine brings
 * the cluster up, with {@code helm-2}'s agent minting its own certificate-bootstrap token
 * automatically against the already-running control plane. The proof that the CSR bootstrap flow
 * genuinely worked over the real network is the real {@code gimle} CLI, run *inside* a container so
 * it dials {@code helm-1} by the exact hostname its server certificate was minted for (a client on
 * the test host dialing the container's mapped port as {@code localhost} would fail hostname
 * verification against that same certificate -- exactly the failure real DNS-named certificates are
 * supposed to catch, and precisely why this check has to run from inside the network rather than
 * from outside it).
 *
 * <p>This suite could not be run against a live Docker daemon in the environment this class was
 * authored in -- see {@code UtgardMachines}' own javadoc and this class's {@code @BeforeAll} for
 * the clean-skip path a blocked image pull takes here. The scenario is written to run for real
 * elsewhere.
 */
@Tag("holmgang")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UtgardMtlsIT {

  private static final String SERVER_MACHINE = "helm-1";
  private static final String AGENT_MACHINE = "helm-2";
  private static final String TOPOLOGY_PATH = "/opt/gimle/topology.yaml";
  private static final String MATERIAL_DIR = "/opt/gimle/tls";

  private UtgardMachines machines;
  private final Path workDir =
      Path.of("target", "holmgang", "utgard-mtls-" + Long.toHexString(System.nanoTime()));
  private boolean failed;

  @BeforeAll
  void startFleet() {
    try {
      machines = UtgardMachines.start(List.of(SERVER_MACHINE, AGENT_MACHINE));
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
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void an_mtls_cluster_bootstraps_across_containers_addressed_by_real_hostnames() {
    try {
      runScenario();
    } catch (final RuntimeException | AssertionError e) {
      failed = true;
      throw e;
    }
  }

  private void runScenario() {
    final String topologyYaml =
        UtgardTopologies.mtls(
            "utgard-mtls",
            List.of(SERVER_MACHINE, AGENT_MACHINE),
            SERVER_MACHINE,
            List.of(AGENT_MACHINE),
            MATERIAL_DIR);
    machines.writeFileToEveryMachine(topologyYaml, TOPOLOGY_PATH);

    final UtgardExecResult validateResult =
        machines.hilmir(SERVER_MACHINE, "validate", "-f", TOPOLOGY_PATH);
    UtgardForensics.record(workDir, "validate", validateResult);
    assertTrue(
        validateResult.succeeded(),
        "expected no ERROR findings: " + UtgardExec.describe(validateResult, "validate"));

    final UtgardExecResult pkiInitResult =
        machines.hilmir(SERVER_MACHINE, "pki", "init", "-f", TOPOLOGY_PATH);
    UtgardForensics.record(workDir, "pki-init", pkiInitResult);
    UtgardExec.requireSuccess(pkiInitResult, "pki init on " + SERVER_MACHINE);

    machines.copyDirectoryBetweenContainers(
        SERVER_MACHINE, AGENT_MACHINE, MATERIAL_DIR, workDir.resolve("tls-staging"));

    final UtgardExecResult serverUp =
        machines.hilmir(SERVER_MACHINE, "up", "-f", TOPOLOGY_PATH, "--machine", SERVER_MACHINE);
    UtgardForensics.record(workDir, "up-" + SERVER_MACHINE, serverUp);
    UtgardExec.requireSuccess(serverUp, "server machine up");

    final UtgardExecResult agentUp =
        machines.hilmir(AGENT_MACHINE, "up", "-f", TOPOLOGY_PATH, "--machine", AGENT_MACHINE);
    UtgardForensics.record(workDir, "up-" + AGENT_MACHINE, agentUp);
    UtgardExec.requireSuccess(
        agentUp, "agent machine up (its own bootstrap token is minted automatically over TLS)");

    final String expectedNodeId = UtgardTopologies.nodeIdFor(AGENT_MACHINE);
    UtgardPoll.await(
        () -> nodeListShows(expectedNodeId),
        Duration.ofMinutes(2),
        "node " + expectedNodeId + " visible via mTLS");
  }

  /**
   * Runs the real {@code gimle node} CLI verb *inside* {@code helm-1}'s own container, presenting
   * the operator leaf and dialing the control plane by its real hostname -- the only vantage point
   * from which the server certificate {@code hilmir pki init} minted for {@code helm-1} actually
   * verifies.
   */
  private boolean nodeListShows(final String nodeId) {
    final UtgardExecResult result =
        machines.exec(
            SERVER_MACHINE,
            List.of(
                "java",
                "-Dgimle.transport.protocol=tls",
                "-Dgimle.tls.certFile=" + MATERIAL_DIR + "/operator.crt",
                "-Dgimle.tls.keyFile=" + MATERIAL_DIR + "/operator.key",
                "-Dgimle.tls.caFile=" + MATERIAL_DIR + "/ca.crt",
                "-cp",
                machines.containerClasspath(),
                "com.gimle.cli.GimleCli",
                "node",
                "--server",
                "https://" + SERVER_MACHINE + ":" + UtgardMachines.CONTROL_PLANE_PORT));
    UtgardForensics.record(workDir, "gimle-node-list", result);
    return result.succeeded() && result.stdout().contains(nodeId);
  }
}
