package com.gimle.hilmir.launch;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.LaunchPlanner;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.Topology;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one machine's own slice of a topology into real, running OS processes: {@link #up} spawns
 * everything that machine hosts (in the plan's own boot order, waiting on any remote prerequisite
 * first), {@link #down} tears a previous {@code up}'s processes back down, and {@link #status}
 * reports on them -- all three driven off a {@link RunLedger} written to disk, since {@code down}
 * and {@code status} necessarily run in a fresh JVM with no memory of what {@code up} started.
 *
 * <p>{@link #up}'s cross-machine ordering: a topology fully and deterministically describes every
 * machine, so computing the *whole* {@link ClusterPlan} (not just this machine's own slice) lets
 * this machine's launch correctly wait on another machine's own processes without needing any live
 * state from that other machine beyond a successful TCP connect to its already-known,
 * topology-declared address -- see {@link BootOrder} for exactly which commands count as a
 * prerequisite.
 */
public final class MachineLauncher {

  private static final Duration READINESS_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration ONE_SHOT_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration KILL_GRACE_PERIOD = Duration.ofSeconds(5);
  private static final Pattern BOOTSTRAP_TOKEN_PATTERN = Pattern.compile("bootstrap token: (\\S+)");

  private MachineLauncher() {}

  public static List<RunRecord> up(
      final Topology topology,
      final String machineName,
      final ResolvedRuntime runtime,
      final PrintStream out) {
    return up(LaunchPlanner.plan(topology, runtime), topology, machineName, runtime, out);
  }

  /**
   * The {@link ClusterPlan}-driven core of {@link #up}, package-visible so it can be exercised
   * directly against a synthetic plan of cheap commands in tests -- the whole-topology overload
   * always plans real Gimlé process commands, which is exactly the "real multi-process boot" this
   * module's own tests deliberately avoid driving end to end.
   */
  static List<RunRecord> up(
      final ClusterPlan clusterPlan,
      final Topology topology,
      final String machineName,
      final ResolvedRuntime runtime,
      final PrintStream out) {
    final MachinePlan myPlan = clusterPlan.byMachine().get(machineName);
    if (myPlan == null) {
      throw new HilmirException("no machine named '" + machineName + "' in this topology");
    }
    createDirectories(runtime.dataRoot());

    final Set<String> confirmedReady = new LinkedHashSet<>();
    final List<RunRecord> records = new ArrayList<>();
    for (final ProcessCommand command : myPlan.commands()) {
      awaitRemotePrerequisites(clusterPlan, machineName, command, confirmedReady, out);
      final RunRecord record = spawn(topology, runtime, command, out);
      records.add(record);
      if (!record.readinessAddress().isBlank()) {
        confirmedReady.add(record.readinessAddress());
      }
    }
    RunLedger.write(runtime.dataRoot(), records);
    out.println(
        "wrote run ledger for " + records.size() + " process(es) under " + runtime.dataRoot());
    return records;
  }

  /**
   * Waits, in order, on every command anywhere in the cluster that must be up before {@code
   * command} can safely spawn: strictly earlier in the global boot sequence and placed on a
   * different machine (a same-machine prerequisite was already spawned-and-awaited moments earlier
   * in this very loop, in order, so it needs no separate wait). Already-confirmed addresses are
   * skipped so a later command sharing an earlier one's prerequisite doesn't re-poll it.
   */
  private static void awaitRemotePrerequisites(
      final ClusterPlan clusterPlan,
      final String machineName,
      final ProcessCommand command,
      final Set<String> confirmedReady,
      final PrintStream out) {
    for (final ProcessCommand prerequisite :
        BootOrder.remotePrerequisitesOf(clusterPlan, machineName, command)) {
      final String address = prerequisite.readinessAddress();
      if (address.isBlank() || confirmedReady.contains(address)) {
        continue;
      }
      out.println(
          "waiting for "
              + prerequisite.role()
              + " "
              + prerequisite.id()
              + " on "
              + prerequisite.machine()
              + " ("
              + address
              + ")...");
      ReadinessPoller.awaitPortOpen(
          address, READINESS_TIMEOUT, prerequisite.role() + " " + prerequisite.id());
      confirmedReady.add(address);
    }
  }

  private static RunRecord spawn(
      final Topology topology,
      final ResolvedRuntime runtime,
      final ProcessCommand command,
      final PrintStream out) {
    final List<String> commandLine = new ArrayList<>(command.command());
    if (command.needsBootstrapToken()) {
      commandLine.add("-Dgimle.tls.bootstrapToken=" + mintBootstrapToken(topology, runtime));
    }
    final Path argFile = runtime.dataRoot().resolve(command.id() + ".args");
    final List<String> spawnCommand = JavaArgFile.rewrite(commandLine, argFile);
    final Path logFile = runtime.dataRoot().resolve(command.logFileName());
    final ProcessBuilder processBuilder = new ProcessBuilder(spawnCommand);
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    final Process process;
    try {
      process = processBuilder.start();
    } catch (final IOException e) {
      throw new HilmirException("failed spawning " + command.role() + " " + command.id(), e);
    }
    out.println("spawned " + command.role() + " " + command.id() + " (pid " + process.pid() + ")");
    if (!command.readinessAddress().isBlank()) {
      ReadinessPoller.awaitPortOpen(
          command.readinessAddress(), READINESS_TIMEOUT, command.role() + " " + command.id());
    }
    return new RunRecord(
        command.id(),
        command.role().name(),
        process.pid(),
        command.command(),
        command.logFileName(),
        command.readinessAddress());
  }

  private static String mintBootstrapToken(final Topology topology, final ResolvedRuntime runtime) {
    final Path materialDir =
        topology
            .tls()
            .orElseThrow(
                () ->
                    new HilmirException(
                        "cannot mint an agent bootstrap token: topology has no tls.materialDir"))
            .materialDir();
    final Path logFile = runtime.dataRoot().resolve("bootstrap-token-mint.log");
    final List<String> command = new ArrayList<>();
    command.add(runtime.javaExecutable());
    command.add("-Dgimle.transport.protocol=tls");
    command.add("-Dgimle.tls.certFile=" + materialDir.resolve("operator.crt"));
    command.add("-Dgimle.tls.keyFile=" + materialDir.resolve("operator.key"));
    command.add("-Dgimle.tls.caFile=" + materialDir.resolve("ca.crt"));
    command.addAll(List.of("-cp", runtime.classpath(), "com.gimle.cli.GimleCli"));
    command.addAll(List.of("cert", "token", "create"));
    command.addAll(List.of("--server", TopologyAddresses.controlPlaneBaseUrl(topology, 0)));
    runOneShot(command, logFile, "bootstrap token minting");
    final String output = readQuietly(logFile);
    final Matcher matcher = BOOTSTRAP_TOKEN_PATTERN.matcher(output);
    if (!matcher.find()) {
      throw new HilmirException(
          "bootstrap token minting produced no token; captured output:\n" + output);
    }
    return matcher.group(1);
  }

  private static void runOneShot(
      final List<String> command, final Path logFile, final String description) {
    final List<String> rewritten = JavaArgFile.rewrite(command, Path.of(logFile + ".args"));
    final ProcessBuilder processBuilder = new ProcessBuilder(rewritten);
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    try {
      final Process process = processBuilder.start();
      if (!process.waitFor(ONE_SHOT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new HilmirException(description + " timed out after " + ONE_SHOT_TIMEOUT);
      }
      if (process.exitValue() != 0) {
        throw new HilmirException(
            description
                + " failed with exit code "
                + process.exitValue()
                + "; captured output:\n"
                + readQuietly(logFile));
      }
    } catch (final IOException e) {
      throw new HilmirException(description + " could not start", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException(description + " was interrupted", e);
    }
  }

  private static String readQuietly(final Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      return "(failed reading " + file + ": " + e.getMessage() + ")";
    }
  }

  private static void createDirectories(final Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (final IOException e) {
      throw new HilmirException("failed creating data root " + dir, e);
    }
  }

  public static void down(final Path dataRoot, final PrintStream out) {
    final List<RunRecord> records = RunLedger.read(dataRoot);
    for (int i = records.size() - 1; i >= 0; i--) {
      killWithDescendants(records.get(i), out);
    }
    RunLedger.delete(dataRoot);
    out.println("removed run ledger under " + dataRoot);
  }

  private static void killWithDescendants(final RunRecord record, final PrintStream out) {
    final Optional<ProcessHandle> maybeHandle = ProcessHandle.of(record.pid());
    if (maybeHandle.isEmpty()) {
      out.println(record.role() + " " + record.id() + " (pid " + record.pid() + ") already gone");
      return;
    }
    final ProcessHandle handle = maybeHandle.get();
    handle.descendants().forEach(ProcessHandle::destroy);
    handle.destroy();
    if (!awaitExit(handle, KILL_GRACE_PERIOD)) {
      handle.destroyForcibly();
      awaitExit(handle, KILL_GRACE_PERIOD);
    }
    out.println("stopped " + record.role() + " " + record.id() + " (pid " + record.pid() + ")");
  }

  /** Returns {@code true} once {@code handle} has exited, or {@code false} on timeout. */
  private static boolean awaitExit(final ProcessHandle handle, final Duration timeout) {
    try {
      handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (final TimeoutException e) {
      return false;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HilmirException("interrupted while stopping pid " + handle.pid(), e);
    } catch (final ExecutionException e) {
      return true;
    }
  }

  public static void status(final Path dataRoot, final PrintStream out) {
    final List<RunRecord> records = RunLedger.read(dataRoot);
    for (final RunRecord record : records) {
      final boolean alive =
          ProcessHandle.of(record.pid()).map(ProcessHandle::isAlive).orElse(false);
      out.println(
          record.role()
              + " "
              + record.id()
              + " pid="
              + record.pid()
              + " alive="
              + alive
              + " readiness="
              + readinessSummary(record));
    }
  }

  private static String readinessSummary(final RunRecord record) {
    if (record.readinessAddress().isBlank()) {
      return "n/a";
    }
    try {
      return ReadinessPoller.isPortOpen(record.readinessAddress()) ? "open" : "closed";
    } catch (final RuntimeException e) {
      // Best-effort only -- status must never fail just because one record's probe couldn't run.
      return "unknown";
    }
  }
}
