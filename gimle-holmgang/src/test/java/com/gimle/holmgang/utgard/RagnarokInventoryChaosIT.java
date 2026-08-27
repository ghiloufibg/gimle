package com.gimle.holmgang.utgard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.WorkDirs;
import com.gimle.ragnarok.RagnarokMain;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the {@code inventory:}-backed {@code ClusterTarget} (S10/S11) against a real SSH round
 * trip rather than the in-process {@code GimleCluster} fixture every other Fenrir/Surtr test uses:
 * a real single-node store, Fafnir, and control plane, each launched and bounced by Ragnarök's own
 * {@link com.gimle.ragnarok.target.inventory.SshManagedProcess} over a genuine {@code ssh} session
 * into {@link UtgardSshMachine}'s container, with {@code ragnarok chaos} driving the strike.
 *
 * <p>Exercises {@code CONTROL_PLANE_BOUNCE} specifically, not {@code STORE_BOUNCE}/{@code
 * LEADER_BOUNCE}: both of those are gated by {@code Fenrir}'s own quorum floor ({@code live <=
 * total/2 + 1}), which a single-replica store can never clear -- {@code total=1} means {@code
 * quorumFloor=1} and {@code live=1}, always skipped, regardless of whether SSH process control
 * works. A single-replica control plane has no such floor ({@code Fenrir.controlPlaneBounce} only
 * enforces it once {@code total > 1}), so it is the one bounce kind this trio can prove actually
 * fires and recovers without standing up a 3-node Raft store purely to satisfy that guard.
 *
 * <p>Also deliberately not covering {@code WORKER_KILL}: a worker-kill victim needs a deployed
 * module instance, which in turn needs a real agent, worker, and staged module jar layered on top
 * of this same store/Fafnir/control-plane trio -- a materially larger fixture. The SSH glue {@code
 * WORKER_KILL} itself depends on ({@link com.gimle.ragnarok.target.inventory.SshWorkerHandle}, the
 * agent-log pid-resolution grep in {@code SshInventoryClusterTarget#workerFor}) is covered at the
 * unit level instead; only the end-to-end "deploy a module and kill its worker over SSH" path is
 * left uncovered here.
 *
 * <p>Unlike {@link UtgardSshDeployIT}, this test never invokes {@code bin/hilmir} at all -- {@code
 * hilmir up --remote}'s own spawned-process classpath defaults to whatever {@code bin/hilmir}'s own
 * launcher script was invoked with (see that script's own comment), which only covers the {@code
 * gimle-hilmir}/{@code gimle-core} jars {@link UtgardSshMachine} stages for driving the launcher
 * itself -- not the full store/Fafnir/control-plane dependency closure. This test sidesteps that
 * entirely: every {@code ManagedRoleSpec} command below carries its own explicit {@code -cp}
 * pointing at a directory this test stages with every jar on its own JVM's classpath (a superset
 * covering all three roles), so no topology-wide classpath default is ever consulted.
 */
@Tag("holmgang")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagnarokInventoryChaosIT {

  private static final String RAGNAROK_LIB_DIR = "/opt/gimle/ragnarok-lib";
  private static final String STORE_RAFT_PORT = "9080";
  private static final Duration READY_TIMEOUT = Duration.ofMinutes(2);

  private UtgardSshMachine machine;
  private Path workDir;
  private Path privateKeyFile;
  private boolean failed;

  @BeforeAll
  void startMachine() {
    workDir =
        Path.of(
            "target", "holmgang", "ragnarok-inventory-chaos-" + Long.toHexString(System.nanoTime()));
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
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void ragnarok_chaos_bounces_a_real_control_plane_over_ssh() {
    try {
      runScenario();
    } catch (final RuntimeException | AssertionError e) {
      failed = true;
      throw e;
    }
  }

  private void runScenario() {
    stageRuntimeJars();

    final int sshPort = machine.mappedSshPort();
    final int controlPlanePort = machine.mappedControlPlanePort();
    final int fafnirPort = machine.mappedFafnirPort();
    final int storeClientPort = machine.mappedStoreClientPort();

    final Path targetFile =
        writeTargetFile(sshPort, controlPlanePort, fafnirPort, storeClientPort);

    // Boot the trio in dependency order -- Ragnarök's own restart() is what launches each process
    // for the first time (no pidfile yet means "not alive"), exactly like a bounce's own restart
    // half, just invoked once up front here instead of by a fault.
    final com.gimle.ragnarok.target.endpoint.TargetSpec spec =
        com.gimle.ragnarok.target.endpoint.TargetSpecParser.resolve(targetFile.toString());
    try (com.gimle.ragnarok.target.ClusterTarget cluster = spec.open()) {
      cluster.store(0).orElseThrow().restart();
      UtgardPoll.await(
          () -> cluster.storeLeaderId().isPresent(),
          READY_TIMEOUT,
          "the freshly-launched store electing itself leader");

      cluster.fafnir(0).orElseThrow().restart();
      UtgardPoll.await(
          () -> isPortOpen("localhost", fafnirPort), READY_TIMEOUT, "Fafnir's port opening");

      cluster.controlPlane(0).orElseThrow().restart();
      UtgardPoll.await(
          () -> cluster.api(0).isServing(),
          READY_TIMEOUT,
          "the freshly-launched control plane serving");
    }

    // Now drive a real chaos plan through the ragnarok CLI itself against that same target file,
    // exactly as an operator would -- CONTROL_PLANE_BOUNCE is destructive, so
    // --confirm-destructive is required. Not STORE_BOUNCE: see this class's own javadoc for why a
    // single-replica store can never clear Fenrir's quorum floor.
    final Path plan =
        writePlanFile(
            workDir,
            "bounce-plan.yaml",
            """
            soakSeconds: 5
            strikeEverySeconds: 2
            pools:
              - kind: CONTROL_PLANE_BOUNCE
            """);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final int exitCode =
        RagnarokMain.run(
            new String[] {
              "chaos",
              "--target",
              targetFile.toString(),
              "--plan",
              plan.toString(),
              "--confirm-destructive"
            },
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    final String stdout = out.toString(StandardCharsets.UTF_8);
    assertEquals(
        0,
        exitCode,
        "ragnarok chaos should exit cleanly (every fired fault recovered); stdout:\n"
            + stdout
            + "\nstderr:\n"
            + err);
    assertTrue(
        stdout.contains("RECOVERED"),
        "expected at least one bounce to actually fire and recover (not just SKIPPED, which is"
            + " all an EndpointClusterTarget could ever record), got:\n"
            + stdout);
  }

  private Path writeTargetFile(
      final int sshPort, final int controlPlanePort, final int fafnirPort, final int storeClientPort) {
    final String storeDataDir = "/opt/gimle/data/store-0";
    final String fafnirDataDir = "/opt/gimle/data/fafnir-0";
    final String controlPlaneDataDir = "/opt/gimle/data/controlplane-0";
    final String classpath = RAGNAROK_LIB_DIR + "/*";

    final String yaml =
        """
        controlPlaneBaseUrls: [http://localhost:%d]
        storeClientEndpoints: [localhost:%d]
        inventory:
          machines:
            - name: m1
              host: 127.0.0.1
              ssh: {user: %s, port: %d, identityFile: %s}
          store:
            - machine: m1
              id: store-0
              pidFile: /opt/gimle/data/store-0.pid
              logFile: /opt/gimle/data/store-0.log
              command: [java, -Dgimle.data.root=%s, -Dgimle.log.root=%s-logs, -cp, '%s',
                        com.gimle.mimir.StoreMain, %s, %s, %d, --host, 127.0.0.1]
          fafnir:
            - machine: m1
              id: fafnir-0
              pidFile: /opt/gimle/data/fafnir-0.pid
              logFile: /opt/gimle/data/fafnir-0.log
              command: [java, -Dgimle.data.root=%s, -Dgimle.log.root=%s-logs, -cp, '%s',
                        com.gimle.fafnir.FafnirMain, %d, %s.key, --host, 127.0.0.1,
                        --store-endpoints, 127.0.0.1:%d]
          controlPlane:
            - machine: m1
              id: controlplane-0
              pidFile: /opt/gimle/data/controlplane-0.pid
              logFile: /opt/gimle/data/controlplane-0.log
              command: [java, -Dgimle.data.root=%s, -Dgimle.log.root=%s-logs, -cp, '%s',
                        com.gimle.controlplane.ControlPlaneMain, %d, %s-secret.key, --host, 127.0.0.1,
                        --store-endpoints, 127.0.0.1:%d, --fafnir-endpoint, 127.0.0.1:%d]
        """
            .formatted(
                controlPlanePort,
                storeClientPort,
                UtgardSshMachine.SSH_USER,
                sshPort,
                privateKeyFile,
                storeDataDir,
                storeDataDir,
                classpath,
                storeDataDir,
                STORE_RAFT_PORT,
                storeClientPort,
                fafnirDataDir,
                fafnirDataDir,
                classpath,
                fafnirPort,
                fafnirDataDir,
                storeClientPort,
                controlPlaneDataDir,
                controlPlaneDataDir,
                classpath,
                controlPlanePort,
                controlPlaneDataDir,
                storeClientPort,
                fafnirPort);
    try {
      Files.createDirectories(workDir);
      final Path file = workDir.resolve("target.yaml");
      Files.writeString(file, yaml, StandardCharsets.UTF_8);
      return file;
    } catch (final IOException e) {
      throw new HolmgangException("failed writing target.yaml", e);
    }
  }

  private Path writePlanFile(final Path dir, final String name, final String yaml) {
    try {
      final Path file = dir.resolve(name);
      Files.writeString(file, yaml, StandardCharsets.UTF_8);
      return file;
    } catch (final IOException e) {
      throw new HolmgangException("failed writing " + name, e);
    }
  }

  /** Stages every jar on this test JVM's own classpath under {@link #RAGNAROK_LIB_DIR}. */
  private void stageRuntimeJars() {
    final String classpath = System.getProperty("java.class.path");
    final List<Path> jars = new ArrayList<>();
    for (final String part : classpath.split(File.pathSeparator)) {
      if (part.endsWith(".jar")) {
        final Path jar = Path.of(part).toAbsolutePath();
        if (Files.isRegularFile(jar)) {
          jars.add(jar);
        }
      }
    }
    if (jars.isEmpty()) {
      throw new HolmgangException(
          "expected at least one .jar on the test JVM's own classpath, found none in: " + classpath);
    }
    for (final Path jar : jars) {
      machine.copyHostFileToContainer(jar, RAGNAROK_LIB_DIR + "/" + jar.getFileName());
    }
  }

  private static boolean isPortOpen(final String host, final int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new java.net.InetSocketAddress(host, port), 1000);
      return true;
    } catch (final IOException e) {
      return false;
    }
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
