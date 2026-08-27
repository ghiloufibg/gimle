package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.WorkDirs;
import com.gimle.ragnarok.target.ClusterTarget;
import com.gimle.ragnarok.target.NetworkFaultInjector;
import com.gimle.ragnarok.target.endpoint.TargetSpec;
import com.gimle.ragnarok.target.endpoint.TargetSpecParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the {@code iptables} mechanism {@code SshNetworkFaultInjector} (S13) drives over SSH is
 * real: insert, verify present via {@code iptables -C}, heal, verify removed -- against a genuine
 * {@code sudo iptables} round trip inside {@link UtgardSshMachine}'s container, granted {@code
 * CAP_NET_ADMIN} for exactly this purpose. Deliberately does not stand up a real
 * store/control-plane pair the way {@link RagnarokInventoryChaosIT} does: {@code iptables -C} only
 * checks whether a rule matching a given specification currently exists in a chain, entirely
 * independent of whether anything is actually listening on the matched address/port, so a
 * throwaway, never-reachable {@code storeClientEndpoints} entry is enough to prove the real
 * firewall mechanism works -- there is nothing further this test would learn from a real cluster
 * that {@link SshNetworkFaultInjectorTest} (argv assertions against a recording fake) doesn't
 * already cover more cheaply.
 */
@Tag("holmgang")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagnarokFirewallFaultIT {

  private static final String DUMMY_STORE_HOST = "10.99.99.99";
  private static final int DUMMY_STORE_PORT = 17101;

  private UtgardSshMachine machine;
  private Path workDir;
  private Path privateKeyFile;
  private boolean failed;

  @BeforeAll
  void startMachine() {
    workDir =
        Path.of(
            "target", "holmgang", "ragnarok-firewall-fault-" + Long.toHexString(System.nanoTime()));
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
  void cutting_a_control_plane_from_stores_installs_a_real_iptables_rule_and_heal_removes_it()
      throws IOException, InterruptedException {
    try {
      runScenario();
    } catch (final RuntimeException | AssertionError e) {
      failed = true;
      throw e;
    }
  }

  private void runScenario() throws IOException, InterruptedException {
    final Path targetFile = writeTargetFile(machine.mappedSshPort());
    final TargetSpec spec = TargetSpecParser.resolve(targetFile.toString());

    final String checkRule =
        "sudo iptables -C OUTPUT -d "
            + DUMMY_STORE_HOST
            + " -p tcp --dport "
            + DUMMY_STORE_PORT
            + " -j REJECT --reject-with tcp-reset -m comment --comment ragnarok-fault";

    try (ClusterTarget target = spec.open()) {
      final Optional<NetworkFaultInjector> faults = target.faults();
      assertEquals(
          true,
          faults.isPresent(),
          "an inventory target with a controlPlane role must offer faults()");

      final NetworkFaultInjector.Partition partition = faults.get().cutControlPlaneFromStores(0);
      try {
        assertEquals(
            0,
            sshExec(checkRule),
            "the rule should be present right after cutControlPlaneFromStores");
      } finally {
        partition.heal();
      }
      assertEquals(1, sshExec(checkRule), "heal() should have removed the exact rule it inserted");
    }
  }

  private Path writeTargetFile(final int sshPort) {
    final String yaml =
        """
        controlPlaneBaseUrls: [http://localhost:1]
        storeClientEndpoints: [%s:%d]
        inventory:
          sudo: true
          machines:
            - name: m1
              host: 127.0.0.1
              ssh: {user: %s, port: %d, identityFile: %s}
          controlPlane:
            - machine: m1
              id: controlplane-0
              pidFile: /tmp/controlplane-0.pid
              logFile: /tmp/controlplane-0.log
              command: [java, -version]
        """
            .formatted(
                DUMMY_STORE_HOST,
                DUMMY_STORE_PORT,
                UtgardSshMachine.SSH_USER,
                sshPort,
                privateKeyFile);
    try {
      Files.createDirectories(workDir);
      final Path file = workDir.resolve("target.yaml");
      Files.writeString(file, yaml, StandardCharsets.UTF_8);
      return file;
    } catch (final IOException e) {
      throw new HolmgangException("failed writing target.yaml", e);
    }
  }

  /** A raw {@code ssh} round trip, independent of Ragnarök's own transport -- pure verification. */
  private int sshExec(final String remoteCommand) throws IOException, InterruptedException {
    final List<String> command =
        List.of(
            "ssh",
            "-o",
            "StrictHostKeyChecking=no",
            "-o",
            "UserKnownHostsFile=/dev/null",
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=10",
            "-p",
            String.valueOf(machine.mappedSshPort()),
            "-i",
            privateKeyFile.toString(),
            UtgardSshMachine.SSH_USER + "@localhost",
            remoteCommand);
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    final Process process = processBuilder.start();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    process.getInputStream().transferTo(output);
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new HolmgangException("ssh command timed out: " + remoteCommand);
    }
    return process.exitValue();
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
