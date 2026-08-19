package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.WorkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * Boots a real three-machine, plaintext Gimlé cluster across three Docker containers, proving two
 * things a single-machine harness like {@code GimleCluster} structurally cannot: that {@code
 * gimle-hilmir}'s {@code MachineLauncher} genuinely blocks {@code hilmir up} on a remote
 * prerequisite rather than racing it, and eventually proceeds once that prerequisite becomes
 * reachable; and that a real deployment submitted through the control plane's own HTTP API reaches
 * {@code ACTIVE} on a node whose agent is a separate container from the one running the control
 * plane and the store.
 *
 * <p>Placement: {@code helm-1} hosts the store, {@code helm-2} hosts Fafnir, and {@code helm-3}
 * hosts both the control plane and the agent. {@code hilmir up --machine helm-3} is deliberately
 * the *first* call issued (in a background thread, since it must genuinely block) -- the
 * control-plane command on {@code helm-3} needs both the store (rank 0, on {@code helm-1}) and
 * Fafnir (rank 3, on {@code helm-2}) up first, and neither has been started yet, so this proves the
 * wait is real rather than a no-op before {@code helm-1}/{@code helm-2} are brought up afterward to
 * unblock it.
 *
 * <p>This suite could not be run against a live Docker daemon in the environment this class was
 * authored in: the daemon itself starts, but every container-image registry pull is blocked at the
 * network egress layer (see {@code UtgardMachines}' own {@code @BeforeAll} skip path below). The
 * scenario is written to run for real on any machine with ordinary registry access.
 */
@Tag("holmgang")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UtgardDistributedBootIT {

  private static final String GIMLE_VERSION = "0.1.0-alpha.2";
  private static final String STORE_MACHINE = "helm-1";
  private static final String FAFNIR_MACHINE = "helm-2";
  private static final String SERVER_MACHINE = "helm-3";
  private static final String TOPOLOGY_PATH = "/opt/gimle/topology.yaml";
  private static final String DEPLOYMENT_NAME = "utgard-boot-greeter";

  private UtgardMachines machines;
  private final Path workDir =
      Path.of(
          "target", "holmgang", "utgard-distributed-boot-" + Long.toHexString(System.nanoTime()));
  private boolean failed;

  @BeforeAll
  void startFleet() {
    try {
      machines = UtgardMachines.start(List.of(STORE_MACHINE, FAFNIR_MACHINE, SERVER_MACHINE));
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
  void
      a_machine_started_out_of_dependency_order_blocks_then_completes_once_its_prerequisites_are_up() {
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
            "utgard-distributed-boot",
            List.of(STORE_MACHINE, FAFNIR_MACHINE, SERVER_MACHINE),
            STORE_MACHINE,
            SERVER_MACHINE,
            FAFNIR_MACHINE,
            List.of(SERVER_MACHINE));
    machines.writeFileToEveryMachine(topologyYaml, TOPOLOGY_PATH);

    final UtgardExecResult validateResult =
        machines.hilmir(STORE_MACHINE, "validate", "-f", TOPOLOGY_PATH);
    UtgardForensics.record(workDir, "validate", validateResult);
    assertTrue(validateResult.succeeded(), UtgardExec.describe(validateResult, "validate"));

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Deliberately out of dependency order: the server machine's own control-plane command
      // needs the store and Fafnir up first, and neither is started yet.
      final Future<UtgardExecResult> serverMachineUp =
          executor.submit(
              () ->
                  machines.hilmir(
                      SERVER_MACHINE, "up", "-f", TOPOLOGY_PATH, "--machine", SERVER_MACHINE));

      // No event to await here -- this is the negative half of the assertion (still blocked),
      // which by construction cannot complete early the way a positive condition can. The store
      // and Fafnir remain un-started throughout this pause, so a wait genuinely in effect cannot
      // spuriously finish within it.
      sleep(Duration.ofSeconds(5));
      assertFalse(
          serverMachineUp.isDone(),
          "hilmir up --machine "
              + SERVER_MACHINE
              + " returned before its remote prerequisites (store, fafnir) were ever started --"
              + " the remote-prerequisite wait is not actually blocking");

      final UtgardExecResult storeUp =
          machines.hilmir(STORE_MACHINE, "up", "-f", TOPOLOGY_PATH, "--machine", STORE_MACHINE);
      UtgardForensics.record(workDir, "up-" + STORE_MACHINE, storeUp);
      UtgardExec.requireSuccess(storeUp, "store machine up");

      final UtgardExecResult fafnirUp =
          machines.hilmir(FAFNIR_MACHINE, "up", "-f", TOPOLOGY_PATH, "--machine", FAFNIR_MACHINE);
      UtgardForensics.record(workDir, "up-" + FAFNIR_MACHINE, fafnirUp);
      UtgardExec.requireSuccess(fafnirUp, "fafnir machine up");

      final UtgardExecResult serverUp = awaitFuture(serverMachineUp, Duration.ofMinutes(3));
      UtgardForensics.record(workDir, "up-" + SERVER_MACHINE, serverUp);
      UtgardExec.requireSuccess(
          serverUp,
          "server machine up (deliberately started before its remote prerequisites were up)");
    }

    deployAndAwaitActive();
  }

  private static UtgardExecResult awaitFuture(
      final Future<UtgardExecResult> future, final Duration timeout) {
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final TimeoutException e) {
      throw new HolmgangException(
          "hilmir up on " + SERVER_MACHINE + " never completed within " + timeout, e);
    } catch (final ExecutionException e) {
      throw new HolmgangException("hilmir up on " + SERVER_MACHINE + " failed", e.getCause());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted awaiting hilmir up on " + SERVER_MACHINE, e);
    }
  }

  private static void sleep(final Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted", e);
    }
  }

  private void deployAndAwaitActive() {
    final Path jar = exampleJar("greeter-provider");
    final String containerJarPath = "/opt/gimle/examples/greeter-provider.jar";
    machines.copyHostFileToContainer(SERVER_MACHINE, jar, containerJarPath);

    final String baseUrl =
        "http://localhost:"
            + machines.mappedPort(SERVER_MACHINE, UtgardMachines.CONTROL_PLANE_PORT);
    final UtgardControlPlaneClient client = new UtgardControlPlaneClient(baseUrl);
    UtgardPoll.await(
        client::isServing,
        Duration.ofSeconds(30),
        "control plane on " + SERVER_MACHINE + " serving");

    client.submitDeployment(
        DEPLOYMENT_NAME, "com.gimle.examples.greeter.provider", "1.0.0", containerJarPath, 1);
    UtgardPoll.await(
        () -> client.isActive(DEPLOYMENT_NAME),
        Duration.ofMinutes(2),
        "deployment " + DEPLOYMENT_NAME + " reaching ACTIVE");
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
