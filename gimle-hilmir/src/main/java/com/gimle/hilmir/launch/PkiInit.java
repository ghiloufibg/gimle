package com.gimle.hilmir.launch;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.BundledJreResolver;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.Topology;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.crypto.KeyGenerator;

/**
 * {@code hilmir pki init} -- a one-shot local generation of a brand-new cluster's shared secret
 * material: TLS (a spawn of the platform's own {@code PkiBootstrapMain}, the same shape {@code
 * gimle-holmgang}'s own {@code GimleCluster.generateTlsMaterial} uses for a test cluster) and, when
 * a Fafnir key file is configured, the Fafnir key itself. Both are cluster-wide material every
 * machine needs an identical copy of before any process starts -- generating them here, once,
 * locally, before {@code --remote up} ever dispatches anywhere, is what lets {@code
 * com.gimle.hilmir.remote.RemoteDispatch} distribute already-consistent files instead of risking
 * two machines each generating their own independently.
 */
public final class PkiInit {

  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private static final int FAFNIR_KEY_BITS = 256;

  private PkiInit() {}

  public static void run(
      final Topology topology, final ResolvedRuntime runtime, final PrintStream out) {
    run(topology, runtime, out, System.getenv("GIMLE_HOME"));
  }

  /**
   * Split out from {@link #run(Topology, ResolvedRuntime, PrintStream)} so a test can supply a fake
   * {@code GIMLE_HOME} directly instead of mutating the JVM's real environment -- the same shape
   * {@link com.gimle.hilmir.plan.LaunchPlanner}'s own package-private {@code plan} overload uses
   * for the identical problem.
   *
   * <p>TLS generation runs whenever {@code tls.materialDir} is configured; otherwise it's skipped
   * entirely, but Fafnir key generation still applies whenever a Fafnir spans more than one machine
   * (a plaintext multi-machine Fafnir deployment still needs an identical key everywhere). A
   * topology with neither -- no TLS material to generate and no multi-machine Fafnir key to
   * generate -- has nothing for this command to do, and is rejected outright.
   */
  static void run(
      final Topology topology,
      final ResolvedRuntime runtime,
      final PrintStream out,
      final String gimleHomeEnv) {
    final boolean fafnirSpansMultipleMachines = fafnirSpansMultipleMachines(topology);
    if (topology.tls().isPresent()) {
      runTlsBootstrap(topology, runtime, out, gimleHomeEnv);
    } else if (fafnirSpansMultipleMachines) {
      out.println(
          "topology '"
              + topology.name()
              + "' declares no tls.materialDir; skipping TLS material generation");
    } else {
      throw new HilmirException(
          "topology '"
              + topology.name()
              + "' declares no tls.materialDir and fafnir doesn't span multiple machines -- pki"
              + " init has nothing to do");
    }
    generateFafnirKeyIfNeeded(topology, out);
  }

  private static void runTlsBootstrap(
      final Topology topology,
      final ResolvedRuntime runtime,
      final PrintStream out,
      final String gimleHomeEnv) {
    final Path materialDir = topology.tls().orElseThrow().materialDir();
    final String caCommonName = topology.name() + "-ca";
    final List<String> hostnames = distinctHostnames(topology);
    createDirectories(runtime.dataRoot());

    final List<String> command =
        buildCommand(topology, runtime, gimleHomeEnv, materialDir, caCommonName, hostnames);
    runOneShot(command, runtime.dataRoot().resolve("pki-init.log"));
    out.println(
        "wrote cluster TLS material for " + hostnames.size() + " hostname(s) to " + materialDir);
    out.println(
        "bootstrap console password (username 'admin') written to "
            + bootstrapPasswordFile(materialDir)
            + "; read it, then delete that file");
  }

  /**
   * Just the command line, with no process spawning -- pulled out of {@link #runTlsBootstrap} so a
   * test can assert on the resolved {@code java} executable directly (bundled-JRE or not) without
   * needing {@code gimle-pki} on this module's own classpath to actually run it.
   */
  static List<String> buildCommand(
      final Topology topology,
      final ResolvedRuntime runtime,
      final String gimleHomeEnv,
      final Path materialDir,
      final String caCommonName,
      final List<String> hostnames) {
    final List<String> command =
        new ArrayList<>(
            List.of(
                BundledJreResolver.resolveJavaExecutable(topology, runtime, "pki", gimleHomeEnv),
                "-cp",
                runtime.classpath(),
                "com.gimle.pki.PkiBootstrapMain",
                // This subprocess's output is captured into pki-init.log, so the one-time bootstrap
                // password must go to its own owner-only file rather than being printed into a log
                // file that outlives the command.
                "--password-file",
                bootstrapPasswordFile(materialDir).toString(),
                materialDir.toString(),
                caCommonName));
    command.addAll(hostnames);
    return command;
  }

  static Path bootstrapPasswordFile(final Path materialDir) {
    return materialDir.resolve("bootstrap-password.txt");
  }

  private static List<String> distinctHostnames(final Topology topology) {
    return topology.machines().stream().map(Machine::host).distinct().toList();
  }

  private static boolean fafnirSpansMultipleMachines(final Topology topology) {
    return topology.fafnir().replicas().stream().map(ServiceReplica::machine).distinct().count()
        > 1;
  }

  /**
   * Generates a fresh AES-256 key at {@code fafnir.keyFile} when one is configured and no file
   * already exists there -- mirrors {@code gimle-fafnir}'s own {@code KeyFileManager.loadOrCreate}
   * key format (a bare {@value #FAFNIR_KEY_BITS}-bit key, no header) without adding a compile
   * dependency on that module, the same "spawn or hand-roll, never link" posture every other
   * platform component gets from this module. An existing key file is always left untouched -- this
   * is provisioning for a cluster that doesn't have one yet, never a rotation.
   */
  private static void generateFafnirKeyIfNeeded(final Topology topology, final PrintStream out) {
    final Optional<Path> keyFile = topology.fafnir().keyFile();
    if (keyFile.isEmpty()) {
      return;
    }
    if (Files.exists(keyFile.get())) {
      out.println("fafnir key file " + keyFile.get() + " already exists; leaving it untouched");
      return;
    }
    writeFafnirKey(keyFile.get());
    out.println("generated fafnir key file at " + keyFile.get());
  }

  private static void writeFafnirKey(final Path keyFile) {
    try {
      final Path parent = keyFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      final KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(FAFNIR_KEY_BITS);
      Files.write(keyFile, generator.generateKey().getEncoded());
      restrictPermissions(keyFile);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("AES key generation unavailable", e);
    } catch (final IOException e) {
      throw new HilmirException("failed writing fafnir key file " + keyFile, e);
    }
  }

  /**
   * Restricts a freshly-written Fafnir key file to owner-read/write only wherever the filesystem
   * supports POSIX permissions -- mirrors {@code gimle-pki}'s own {@code
   * PkiBootstrapMain#restrictPermissions}, silently skipped rather than failed where unsupported
   * (local Windows development only).
   */
  private static void restrictPermissions(final Path path) throws IOException {
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
  }

  private static void createDirectories(final Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (final IOException e) {
      throw new HilmirException("failed creating data root " + dir, e);
    }
  }

  private static void runOneShot(final List<String> command, final Path logFile) {
    final List<String> rewritten = JavaArgFile.rewrite(command, Path.of(logFile + ".args"));
    final ProcessBuilder processBuilder = new ProcessBuilder(rewritten);
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    try {
      final Process process = processBuilder.start();
      if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new HilmirException("pki init timed out after " + TIMEOUT + "; see " + logFile);
      }
      if (process.exitValue() != 0) {
        throw new HilmirException(
            "pki init failed with exit code " + process.exitValue() + "; see " + logFile);
      }
    } catch (final IOException e) {
      throw new HilmirException("pki init could not start", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("pki init was interrupted", e);
    }
  }
}
