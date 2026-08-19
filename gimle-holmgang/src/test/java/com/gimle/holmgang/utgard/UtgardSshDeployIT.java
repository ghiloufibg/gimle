package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gimle.hilmir.HilmirMain;
import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.WorkDirs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
 * Proves {@code hilmir up/down/status --remote} genuinely SSHes into a real machine rather than
 * relying on the {@code docker exec} shortcut every other Utgard scenario's own {@link
 * UtgardMachines#hilmir} takes -- the automated stand-in for the manual docker-compose scenario
 * documented under this module's own README ({@code compose/docker-compose.ssh-remote.yml}), which
 * this test drives through the identical machinery ({@code compose/ssh-remote/Dockerfile}, an
 * authorized ephemeral keypair, {@code bin/hilmir} staged the way {@code gimle-dist} ships it) but
 * without a human at a keyboard.
 *
 * <p>{@code HilmirMain.run} is invoked in-process (the same testable entry point {@code
 * HilmirMainTest} uses) rather than as a spawned {@code hilmir} subprocess -- only the SSH hop
 * itself needs to be real, and {@link com.gimle.hilmir.remote.SshProcessExec} already shells out to
 * a genuine {@code ssh}/{@code scp} subprocess underneath, so nothing about the transport is
 * skipped by calling in-process. Deliberately single-machine; see {@link UtgardSshMachine}'s own
 * javadoc for why a genuine multi-machine SSH round trip through Docker is a larger, separate lift.
 *
 * <p>Requires a real {@code ssh}/{@code scp}/{@code ssh-keygen} client on the test runner's own
 * {@code PATH} -- unlike every other Utgard scenario, a Docker daemon alone is not enough.
 */
@Tag("holmgang")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UtgardSshDeployIT {

  private static final String GIMLE_VERSION = "0.1.0-alpha.2";
  private static final String DEPLOYMENT_NAME = "utgard-ssh-greeter";

  private UtgardSshMachine machine;
  private Path workDir;
  private Path privateKeyFile;
  private boolean failed;

  @BeforeAll
  void startMachine() {
    workDir =
        Path.of("target", "holmgang", "utgard-ssh-deploy-" + Long.toHexString(System.nanoTime()));
    final Path keyDir = workDir.resolve("ssh-key");
    final Path publicKeyFile;
    try {
      publicKeyFile = generateEphemeralKeypair(keyDir);
    } catch (final IOException | InterruptedException e) {
      assumeTrue(false, "could not generate an ephemeral SSH keypair (ssh-keygen unavailable?)");
      return;
    }
    privateKeyFile = keyDir.resolve("id_ed25519");
    try {
      machine = UtgardSshMachine.start(publicKeyFile);
    } catch (final UtgardDockerUnavailableException e) {
      assumeTrue(false, e.getMessage());
    }
  }

  @AfterAll
  void stopMachine() {
    if (machine != null) {
      machine.close();
    }
    if (WorkDirs.shouldDelete(failed)) {
      WorkDirs.deleteRecursively(workDir);
    }
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void up_over_a_real_ssh_round_trip_deploys_a_cluster_then_down_tears_it_back_down() {
    try {
      runScenario();
    } catch (final RuntimeException | AssertionError e) {
      failed = true;
      throw e;
    }
  }

  private void runScenario() {
    final Path topologyFile = workDir.resolve("topology.yaml");
    writeTopologyFile(topologyFile);

    final UtgardExecResult upResult = runHilmirRemote(topologyFile, "up");
    UtgardForensics.record(workDir, "up", upResult);
    UtgardExec.requireSuccess(upResult, "hilmir up --remote");

    deployAndAwaitActive();

    final UtgardExecResult statusUpResult = runHilmirRemote(topologyFile, "status");
    UtgardForensics.record(workDir, "status-up", statusUpResult);
    UtgardExec.requireSuccess(statusUpResult, "hilmir status --remote (while up)");
    assertTrue(
        statusUpResult.stdout().contains("alive=true"),
        "expected a live process report, got:\n" + statusUpResult.stdout());

    final UtgardExecResult downResult = runHilmirRemote(topologyFile, "down");
    UtgardForensics.record(workDir, "down", downResult);
    UtgardExec.requireSuccess(downResult, "hilmir down --remote");

    final UtgardExecResult statusDownResult = runHilmirRemote(topologyFile, "status");
    UtgardForensics.record(workDir, "status-down", statusDownResult);
    assertNotEquals(
        0,
        statusDownResult.exitCode(),
        "status --remote still reported a live run after down --remote removed its ledger:\n"
            + UtgardExec.describe(statusDownResult, "status --remote (after down)"));
  }

  private void writeTopologyFile(final Path topologyFile) {
    final String yaml =
        """
        name: utgard-ssh-deploy
        transport: plaintext
        machines:
          - {name: m1, host: 127.0.0.1}
        runtime:
          dataRoot: /data
        store:
          replicas:
            - {machine: m1}
        fafnir:
          keyFile: /data/fafnir.key
          replicas:
            - {machine: m1}
        controlPlane:
          replicas:
            - {machine: m1}
        """;
    try {
      Files.createDirectories(topologyFile.getParent());
      Files.writeString(topologyFile, yaml, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new HolmgangException("failed writing " + topologyFile, e);
    }
  }

  /** Runs {@code hilmir <verb> --remote} in-process against {@link #machine}'s real sshd. */
  private UtgardExecResult runHilmirRemote(final Path topologyFile, final String verb) {
    final List<String> args =
        List.of(
            verb,
            "-f",
            topologyFile.toString(),
            "--remote",
            "--ssh-user",
            UtgardSshMachine.SSH_USER,
            "--ssh-port",
            Integer.toString(machine.mappedSshPort()),
            "--ssh-key",
            privateKeyFile.toString());
    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final int exitCode =
        HilmirMain.run(
            args.toArray(new String[0]),
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    return new UtgardExecResult(
        args,
        exitCode,
        outBytes.toString(StandardCharsets.UTF_8),
        errBytes.toString(StandardCharsets.UTF_8));
  }

  private void deployAndAwaitActive() {
    final Path jar = exampleJar("greeter-provider");
    final String containerJarPath = "/opt/gimle/examples/greeter-provider.jar";
    machine.copyHostFileToContainer(jar, containerJarPath);

    final String baseUrl = "http://localhost:" + machine.mappedControlPlanePort();
    final UtgardControlPlaneClient client = new UtgardControlPlaneClient(baseUrl);
    UtgardPoll.await(client::isServing, Duration.ofSeconds(30), "control plane serving");

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

  /** A fresh ed25519 keypair under {@code keyDir}, returning the public half's path. */
  private static Path generateEphemeralKeypair(final Path keyDir)
      throws IOException, InterruptedException {
    Files.createDirectories(keyDir);
    final Path privateKey = keyDir.resolve("id_ed25519");
    final ProcessBuilder processBuilder =
        new ProcessBuilder(
            "ssh-keygen", "-t", "ed25519", "-N", "", "-f", privateKey.toString(), "-q");
    processBuilder.redirectErrorStream(true);
    final Process process = processBuilder.start();
    final String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw new HolmgangException("ssh-keygen failed to create an ephemeral keypair: " + output);
    }
    return keyDir.resolve("id_ed25519.pub");
  }
}
