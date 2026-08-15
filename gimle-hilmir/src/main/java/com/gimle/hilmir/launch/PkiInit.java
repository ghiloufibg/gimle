package com.gimle.hilmir.launch;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.ServiceReplica;
import com.gimle.hilmir.topology.Topology;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@code hilmir pki init} -- a one-shot spawn of the platform's own {@code PkiBootstrapMain} to
 * generate a brand-new cluster's TLS material, the same shape {@code gimle-holmgang}'s own {@code
 * GimleCluster.generateTlsMaterial} uses for a test cluster.
 */
public final class PkiInit {

  private static final Duration TIMEOUT = Duration.ofSeconds(120);

  private PkiInit() {}

  public static void run(
      final Topology topology, final ResolvedRuntime runtime, final PrintStream out) {
    final Path materialDir =
        topology
            .tls()
            .orElseThrow(
                () ->
                    new HilmirException(
                        "topology '"
                            + topology.name()
                            + "' declares no tls.materialDir -- pki init only applies to an mtls"
                            + " topology"))
            .materialDir();
    final String caCommonName = topology.name() + "-ca";
    final String hostname = pickHostname(topology);
    warnIfMultipleHosts(topology, hostname, out);
    createDirectories(runtime.dataRoot());

    final List<String> command =
        List.of(
            runtime.javaExecutable(),
            "-cp",
            runtime.classpath(),
            "com.gimle.pki.PkiBootstrapMain",
            materialDir.toString(),
            caCommonName,
            hostname);
    runOneShot(command, runtime.dataRoot().resolve("pki-init.log"));
    out.println("wrote cluster TLS material to " + materialDir);
  }

  /**
   * The platform's PKI mints DNS-only, single-hostname server SANs today (see {@code
   * TopologyValidator}'s own {@code MTLS_SINGLE_HOSTNAME_PKI} finding) -- the first control-plane
   * replica's own machine is the most useful single hostname to pick, since that is the process
   * every other role's own TLS-mode client dials by name; falling back to the first declared
   * machine only when the topology declares no control-plane replicas at all.
   */
  private static String pickHostname(final Topology topology) {
    final List<ServiceReplica> controlPlanes = topology.controlPlane().replicas();
    if (!controlPlanes.isEmpty()) {
      return TopologyAddresses.hostOf(topology, controlPlanes.get(0).machine());
    }
    if (topology.machines().isEmpty()) {
      throw new HilmirException(
          "topology '" + topology.name() + "' declares no machines to generate TLS material for");
    }
    return topology.machines().get(0).host();
  }

  private static void warnIfMultipleHosts(
      final Topology topology, final String chosenHostname, final PrintStream out) {
    final Set<String> hosts = new LinkedHashSet<>();
    for (final Machine machine : topology.machines()) {
      hosts.add(machine.host());
    }
    if (hosts.size() > 1) {
      out.println(
          "note: transport is mtls across more than one machine, but PkiBootstrapMain mints server"
              + " leaves for a single hostname only -- only "
              + chosenHostname
              + "'s server material was generated; any other machine's server processes will need"
              + " manually-issued material");
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
