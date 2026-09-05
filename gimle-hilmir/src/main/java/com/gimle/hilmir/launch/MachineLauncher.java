package com.gimle.hilmir.launch;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.LaunchPlanner;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.ProcessRole;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns one machine's own slice of a topology into real, running OS processes: {@link #up} spawns
 * everything that machine hosts (in the plan's own boot order, waiting on any remote prerequisite
 * first) except a role a previous {@code up} already left genuinely running, which it leaves alone
 * rather than spawning a duplicate of; {@link #down} tears a previous {@code up}'s processes back
 * down, and {@link #status} reports on them -- all three driven off a {@link RunLedger} written to
 * disk, since {@code down} and {@code status} necessarily run in a fresh JVM with no memory of what
 * {@code up} started.
 *
 * <p>{@link #up}'s cross-machine ordering: a topology fully and deterministically describes every
 * machine, so computing the *whole* {@link ClusterPlan} (not just this machine's own slice) lets
 * this machine's launch correctly wait on another machine's own processes without needing any live
 * state from that other machine beyond a successful TCP connect to its already-known,
 * topology-declared address -- see {@link BootOrder} for exactly which commands count as a
 * prerequisite.
 */
public final class MachineLauncher {

  /**
   * How long {@link #awaitStoreLeaderServing} waits for the cluster to serve through a leader again
   * after a store restart. Generous on purpose: it must cover a fresh replica rejoining, an
   * election, and a lagging follower catching its log up on a busy cluster, and the cost of waiting
   * too long is a slow rollout, where the cost of not waiting long enough is taking the next
   * replica down mid-recovery.
   */
  private static final Duration STORE_LEADER_TIMEOUT = Duration.ofSeconds(90);

  private static final Duration STORE_LEADER_POLL_INTERVAL = Duration.ofMillis(500);

  /**
   * A node id no real node registers, so the leader-routed read {@link #awaitStoreLeaderServing}
   * probes with always answers empty -- it exists to be routed, not to find anything.
   */
  private static final String LEADER_PROBE_NODE_ID = "hilmir-upgrade-leader-probe";

  private static final Duration READINESS_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration ONE_SHOT_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration KILL_GRACE_PERIOD = Duration.ofSeconds(5);
  private static final Duration FILE_RELEASE_TIMEOUT = Duration.ofSeconds(10);
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
    final MachinePlan myPlan = clusterPlan.requireMachine(machineName);
    createDirectories(runtime.dataRoot());

    final Map<String, RunRecord> alreadyRecorded = existingRecordsById(runtime.dataRoot());
    final Set<String> confirmedReady = new LinkedHashSet<>();
    final List<RunRecord> records = new ArrayList<>();
    boolean succeeded = false;
    try {
      for (final ProcessCommand command : myPlan.commands()) {
        final RunRecord previous = alreadyRecorded.get(command.id());
        final RunRecord record;
        if (previous != null && isActuallyAlive(previous, readinessSummary(previous))) {
          out.println(
              command.role()
                  + " "
                  + command.id()
                  + " (pid "
                  + previous.pid()
                  + ") already running -- skipping respawn");
          record = previous;
          records.add(record);
        } else {
          awaitRemotePrerequisites(clusterPlan, machineName, command, confirmedReady, out);
          final Spawned spawned = spawn(topology, runtime, command, out);
          // Recorded before the readiness wait, not after: a process that starts but never opens
          // its port is exactly the one a failed `up` most needs to kill, and a record added only
          // once it is ready would leave it running with nothing on disk pointing at it.
          records.add(spawned.record());
          awaitReadiness(command, spawned);
          record = spawned.record();
        }
        if (!record.readinessAddress().isBlank()) {
          confirmedReady.add(record.readinessAddress());
        }
      }
      succeeded = true;
    } finally {
      // A later command's readiness check can throw and abort `up` mid-loop -- without this,
      // every process already spawned and confirmed ready in earlier iterations is lost with
      // zero on-disk record, leaving `down`/`status` unable to find them.
      if (!succeeded && !records.isEmpty()) {
        RunLedger.write(runtime.dataRoot(), records);
        out.println(
            "wrote partial run ledger for "
                + records.size()
                + " process(es) under "
                + runtime.dataRoot()
                + " after failure");
      }
    }
    RunLedger.write(runtime.dataRoot(), records);
    out.println(
        "wrote run ledger for " + records.size() + " process(es) under " + runtime.dataRoot());
    return records;
  }

  /**
   * A previous {@code up}'s own ledger, if any, keyed by command id -- consulted so a re-run
   * against a machine with some roles already alive only respawns the ones that genuinely aren't,
   * rather than unconditionally spawning a duplicate of every role (which, for a role like AGENT,
   * means a second live process fighting the original over the same responsibilities). Absent
   * entirely on a machine's first-ever {@code up}, which is not an error.
   */
  private static Map<String, RunRecord> existingRecordsById(final Path dataRoot) {
    final Map<String, RunRecord> byId = new LinkedHashMap<>();
    for (final RunRecord record : RunLedger.tryRead(dataRoot)) {
      byId.put(record.id(), record);
    }
    return byId;
  }

  /**
   * Restarts exactly one already-running process on {@code machineName} -- kills it, spawns its
   * replacement under {@code newRuntime} (typically pointing at a newly-unpacked platform binary's
   * classpath), waits for the replacement's own readiness, and upserts just that one {@link
   * RunLedger} entry -- leaving every other process this machine hosts, and its own ledger record,
   * completely untouched. This is the primitive {@code hilmir upgrade-cluster} drives once per
   * role; see that verb's own package for the per-machine orchestration loop.
   *
   * <p>{@code newRuntime.dataRoot()} plays the same dual role a plain {@code up}'s own {@code
   * runtime.dataRoot()} does: it is both where this machine's run ledger already lives (so the
   * existing entry can be found) and where the replacement process's own scoped data/log
   * subdirectories get created -- a restart is not a chance to relocate a machine's data root, so
   * there is deliberately no second, independent path for that.
   */
  public static RunRecord restartRole(
      final Topology topology,
      final String machineName,
      final ProcessRole role,
      final ResolvedRuntime newRuntime,
      final PrintStream out) {
    return restartRole(
        LaunchPlanner.plan(topology, newRuntime), topology, machineName, role, newRuntime, out);
  }

  /**
   * The {@link ClusterPlan}-driven core of {@link #restartRole}, package-visible for the same
   * reason {@link #up(ClusterPlan, Topology, String, ResolvedRuntime, PrintStream)} is: it lets a
   * test drive this against a synthetic plan of cheap fixture commands instead of the real Gimlé
   * process commands {@link LaunchPlanner} always plans.
   */
  static RunRecord restartRole(
      final ClusterPlan clusterPlan,
      final Topology topology,
      final String machineName,
      final ProcessRole role,
      final ResolvedRuntime newRuntime,
      final PrintStream out) {
    final MachinePlan myPlan = clusterPlan.requireMachine(machineName);
    final ProcessCommand command = findRoleCommand(myPlan, role, machineName);
    final RunRecord existing =
        findExistingLedgerRecord(RunLedger.read(newRuntime.dataRoot()), command, machineName);

    if (role == ProcessRole.STORE) {
      requireStoreQuorumMaintained(clusterPlan, command, "before");
    }

    out.println(
        "stopping " + role + " " + command.id() + " (pid " + existing.pid() + ") for restart...");
    killWithDescendants(newRuntime.dataRoot(), existing, out);

    final Spawned spawned = spawn(topology, newRuntime, command, out);
    final RunRecord fresh = spawned.record();
    // The ledger points at the replacement before anything waits on its port, for the same reason
    // `up` records a process the moment it starts: a restart that never becomes ready must still
    // leave `down` able to find and kill what it started.
    RunLedger.replace(newRuntime.dataRoot(), command.id(), fresh);
    awaitReadiness(command, spawned);

    if (role == ProcessRole.STORE) {
      requireStoreQuorumMaintained(clusterPlan, command, "after");
      awaitStoreLeaderServing(clusterPlan, topology, command, out);
    }

    out.println("restarted " + role + " " + command.id() + " (pid " + fresh.pid() + ") -> ready");
    return fresh;
  }

  private static ProcessCommand findRoleCommand(
      final MachinePlan myPlan, final ProcessRole role, final String machineName) {
    return myPlan.commands().stream()
        .filter(c -> c.role() == role)
        .findFirst()
        .orElseThrow(
            () ->
                new HilmirException(
                    "machine '"
                        + machineName
                        + "' does not host role "
                        + role
                        + " in this topology"));
  }

  private static RunRecord findExistingLedgerRecord(
      final List<RunRecord> ledger, final ProcessCommand command, final String machineName) {
    return ledger.stream()
        .filter(r -> r.id().equals(command.id()))
        .findFirst()
        .orElseThrow(
            () ->
                new HilmirException(
                    "no running "
                        + command.role()
                        + " "
                        + command.id()
                        + " recorded on machine "
                        + machineName
                        + " -- nothing to restart; run 'hilmir up' first"));
  }

  /**
   * A store restart is the one case in this launcher where killing a single process can break a
   * property spanning the whole cluster -- Raft quorum -- rather than just that one machine's own
   * state, so it gets its own explicit gate on top of the ordinary per-process readiness wait every
   * role already gets. Checked once before the kill (so an operator running two concurrent
   * restarts, or restarting the last-standing majority member, is refused up front) and once more
   * after the replacement is ready (so a majority loss that crept in mid-restart, from an unrelated
   * concurrent fault, is not silently reported as success).
   *
   * <p>A single-replica store has no other replicas to protect and no quorum to speak of -- the
   * gate is a no-op in that case, since refusing to ever restart a topology's only store node would
   * make it un-upgradable rather than actually safer.
   */
  static void requireStoreQuorumMaintained(
      final ClusterPlan clusterPlan, final ProcessCommand restarting, final String phase) {
    final List<ProcessCommand> others = otherStoreCommands(clusterPlan, restarting);
    if (others.isEmpty()) {
      return;
    }
    final int total = others.size() + 1;
    final int majority = total / 2 + 1;
    final long reachable =
        others.stream().filter(c -> ReadinessPoller.isPortOpen(c.readinessAddress())).count();
    if (reachable < majority) {
      throw new HilmirException(
          "refusing to restart store "
              + restarting.id()
              + ": only "
              + reachable
              + " of "
              + others.size()
              + " other store replica(s) reachable "
              + phase
              + " restart (need "
              + majority
              + " of "
              + total
              + " total replicas for Raft quorum); restarting now risks losing quorum");
    }
  }

  /**
   * Waits until the store cluster actually has an elected leader serving again, after a store
   * replica was restarted -- the one thing {@link #requireStoreQuorumMaintained} structurally
   * cannot tell a caller. That gate reads TCP ports, and a fresh store process opens its port the
   * moment it binds: well before it has rejoined the Raft cluster, caught its log up, or found a
   * leader. A rollout that treats a bound port as proof of health therefore reports this step
   * successful and moves straight on to the next machine, where taking down another replica while
   * the cluster still has no leader is precisely the quorum loss the gate exists to prevent.
   *
   * <p>The probe is a leader-routed read for a node id no real node uses: it can only be answered
   * where a leader is genuinely serving, and its (always empty) result is discarded -- that one
   * answered at all is the whole signal. Failing here deliberately fails the command rather than
   * warning: an operator who is told a rollout step succeeded will continue the rollout, which is
   * the action that turns a slow recovery into an outage.
   */
  private static void awaitStoreLeaderServing(
      final ClusterPlan clusterPlan,
      final Topology topology,
      final ProcessCommand restarted,
      final PrintStream out) {
    awaitStoreLeaderServing(clusterPlan, topology, restarted, out, STORE_LEADER_TIMEOUT);
  }

  /**
   * The timeout-parameterised core of {@link #awaitStoreLeaderServing}, package-visible for the
   * same reason {@link #restartRole}'s {@link ClusterPlan} overload is: it lets a test assert what
   * this gate accepts and rejects without waiting out the production budget.
   */
  static void awaitStoreLeaderServing(
      final ClusterPlan clusterPlan,
      final Topology topology,
      final ProcessCommand restarted,
      final PrintStream out,
      final Duration timeout) {
    final List<SocketAddress> endpoints = storeClientEndpoints(clusterPlan);
    if (endpoints.isEmpty()) {
      return;
    }
    if (topology.transport() == Transport.MTLS) {
      activateOperatorTls(topology);
    }
    out.println("  waiting for the store cluster to serve through a leader again...");
    final long deadlineNanos = System.nanoTime() + timeout.toNanos();
    String lastError = "no probe completed";
    try (StoreClient client = new StoreClient(endpoints)) {
      while (true) {
        try {
          client.getNodeHeartbeat(LEADER_PROBE_NODE_ID);
          out.println("  store cluster is serving through a leader again");
          return;
        } catch (final RuntimeException e) {
          lastError = String.valueOf(e.getMessage());
        }
        if (System.nanoTime() > deadlineNanos) {
          break;
        }
        try {
          Thread.sleep(STORE_LEADER_POLL_INTERVAL.toMillis());
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new HilmirException("interrupted waiting for a store leader after restart", e);
        }
      }
    }
    throw new HilmirException(
        "store "
            + restarted.id()
            + " restarted and is listening, but the store cluster still has no leader serving"
            + " after "
            + timeout
            + " (last error: "
            + lastError
            + ") -- not reporting this step healthy, since continuing the rollout would take"
            + " another replica down while the cluster cannot serve");
  }

  /** Every store replica's client address anywhere in the cluster, the endpoints a client dials. */
  private static List<SocketAddress> storeClientEndpoints(final ClusterPlan clusterPlan) {
    final List<SocketAddress> endpoints = new ArrayList<>();
    for (final MachinePlan machinePlan : clusterPlan.byMachine().values()) {
      for (final ProcessCommand candidate : machinePlan.commands()) {
        if (candidate.role() == ProcessRole.STORE && !candidate.readinessAddress().isBlank()) {
          endpoints.add(ReadinessPoller.socketAddressOf(candidate.readinessAddress()));
        }
      }
    }
    return endpoints;
  }

  /**
   * {@code StoreConnection} reads these lazily per socket, so they must be set before the {@link
   * StoreClient} above opens one. Same three-file operator identity {@link #mintBootstrapToken}
   * already uses for talking to an already-running cluster.
   */
  private static void activateOperatorTls(final Topology topology) {
    final Path materialDir =
        topology
            .tls()
            .orElseThrow(
                () ->
                    new HilmirException(
                        "cannot reach the store cluster: mtls topology has no tls.materialDir"))
            .materialDir();
    System.setProperty("gimle.transport.protocol", "tls");
    System.setProperty("gimle.tls.certFile", materialDir.resolve("operator.crt").toString());
    System.setProperty("gimle.tls.keyFile", materialDir.resolve("operator.key").toString());
    System.setProperty("gimle.tls.caFile", materialDir.resolve("ca.crt").toString());
  }

  private static List<ProcessCommand> otherStoreCommands(
      final ClusterPlan clusterPlan, final ProcessCommand restarting) {
    final List<ProcessCommand> others = new ArrayList<>();
    for (final MachinePlan machinePlan : clusterPlan.byMachine().values()) {
      for (final ProcessCommand candidate : machinePlan.commands()) {
        if (candidate.role() == ProcessRole.STORE && !candidate.id().equals(restarting.id())) {
          others.add(candidate);
        }
      }
    }
    return others;
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

  /** A started process and the ledger record naming it, before anything has waited on its port. */
  private record Spawned(RunRecord record, Process process, Path logFile) {}

  private static Spawned spawn(
      final Topology topology,
      final ResolvedRuntime runtime,
      final ProcessCommand command,
      final PrintStream out) {
    List<String> commandLine = command.command();
    if (command.needsBootstrapToken()) {
      commandLine =
          insertBootstrapTokenFlag(
              commandLine, mintBootstrapToken(topology, runtime), command.role(), command.id());
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
    return new Spawned(
        new RunRecord(
            command.id(),
            command.role().name(),
            process.pid(),
            command.command(),
            command.logFileName(),
            command.readinessAddress()),
        process,
        logFile);
  }

  private static void awaitReadiness(final ProcessCommand command, final Spawned spawned) {
    if (command.readinessAddress().isBlank()) {
      return;
    }
    ReadinessPoller.awaitPortOpen(
        command.readinessAddress(),
        READINESS_TIMEOUT,
        command.role() + " " + command.id(),
        spawned.process(),
        spawned.logFile());
  }

  /**
   * Inserts {@code -Dgimle.tls.bootstrapToken=<token>} immediately before {@code commandLine}'s own
   * first {@code "-cp"} -- java only recognizes a {@code -D} flag as a JVM system property when it
   * appears before the main class on the command line; appended at the end (after the main class,
   * its own positional args, and -- for an agent -- the entire trailing worker-launch tail) it
   * would already be parsed as a plain program argument instead, silently invisible to {@code
   * AgentMain}'s own {@code System.getProperty} lookup. Every {@link ProcessCommand} this launcher
   * spawns puts its own {@code -cp classpath mainClass} run immediately after every VM option, so
   * inserting right before the first {@code "-cp"} is correct regardless of which role needed the
   * token.
   */
  static List<String> insertBootstrapTokenFlag(
      final List<String> commandLine,
      final String token,
      final ProcessRole role,
      final String commandId) {
    final int cpIndex = commandLine.indexOf("-cp");
    if (cpIndex < 0) {
      throw new HilmirException(
          "cannot mint a bootstrap token for "
              + role
              + " "
              + commandId
              + ": its own command line has no -cp to insert -Dgimle.tls.bootstrapToken before");
    }
    final List<String> withToken = new ArrayList<>(commandLine);
    withToken.add(cpIndex, "-Dgimle.tls.bootstrapToken=" + token);
    return withToken;
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
    command.addAll(List.of("--server", TopologyAddresses.hostPortOf(topology, 0)));
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
      killWithDescendants(dataRoot, records.get(i), out);
    }
    RunLedger.delete(dataRoot);
    out.println("removed run ledger under " + dataRoot);
  }

  /**
   * Stops exactly one of a machine's running processes -- the one {@code role} or {@code id} names
   * -- and drops just that entry from the run ledger, leaving every other process this machine
   * hosts, and its own ledger record, running and recorded. {@code down} is all-or-nothing by
   * design, which leaves an operator wanting to take one replica of a co-located pair out of
   * service with nothing but a raw process kill; this is that operation, and because the stopped
   * process's ledger entry is genuinely gone rather than merely stale, a later {@code up} against
   * the same data root spawns it again while leaving its still-running neighbours alone.
   *
   * <p>Exactly one of {@code role}/{@code id} is expected to be present. {@code role} is the
   * ordinary case and resolves against the ledger's own recorded role names; it deliberately
   * refuses to guess when a machine hosts two processes of that role, naming the ids so the caller
   * can say which one it meant.
   */
  public static RunRecord stop(
      final Path dataRoot,
      final Optional<ProcessRole> role,
      final Optional<String> id,
      final PrintStream out) {
    final List<RunRecord> records = RunLedger.read(dataRoot);
    final List<RunRecord> matches =
        id.map(wanted -> records.stream().filter(r -> r.id().equals(wanted)).toList())
            .orElseGet(
                () ->
                    records.stream()
                        .filter(r -> r.role().equals(role.orElseThrow().name()))
                        .toList());
    if (matches.isEmpty()) {
      throw new HilmirException(
          "nothing named by "
              + id.map(wanted -> "--id " + wanted).orElseGet(() -> "--role " + role.orElseThrow())
              + " is recorded as running under "
              + dataRoot
              + " -- recorded: "
              + describe(records));
    }
    if (matches.size() > 1) {
      throw new HilmirException(
          "--role "
              + role.orElseThrow()
              + " matches "
              + matches.size()
              + " processes under "
              + dataRoot
              + " -- name one with --id: "
              + matches.stream().map(RunRecord::id).collect(Collectors.joining(", ")));
    }
    final RunRecord target = matches.get(0);
    killWithDescendants(dataRoot, target, out);
    RunLedger.remove(dataRoot, target.id());
    out.println("removed " + target.role() + " " + target.id() + " from the run ledger");
    return target;
  }

  private static String describe(final List<RunRecord> records) {
    if (records.isEmpty()) {
      return "nothing";
    }
    return records.stream()
        .map(r -> r.id() + " (" + r.role() + ")")
        .collect(Collectors.joining(", "));
  }

  private static void killWithDescendants(
      final Path dataRoot, final RunRecord record, final PrintStream out) {
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
    // Both files a subsequent spawn() for this same command id will immediately rewrite: the log
    // file (restartRole's replacement process redirects to the identical path) and the @argfile
    // JavaArgFile.rewrite truncates-and-overwrites -- restartRole reuses the killed record's own
    // command id, so both paths collide with whatever this just-dead process itself last touched.
    awaitFileReleased(dataRoot.resolve(record.logFile()));
    awaitFileReleased(dataRoot.resolve(record.id() + ".args"));
    out.println("stopped " + record.role() + " " + record.id() + " (pid " + record.pid() + ")");
  }

  /**
   * On Windows, a just-terminated process's own OS handle to a file it had open (its redirected log
   * file, or the {@code @argfile} the JVM launcher itself read at startup) can outlive {@link
   * ProcessHandle#onExit()} resolving by a few milliseconds -- so a caller that touches that same
   * path immediately after {@code down}/{@code restartRole} returns (an operator archiving logs,
   * {@code restartRole}'s own replacement spawn rewriting the identical {@code @argfile} for a
   * same-id restart, or this launcher's own tests deleting their temp directory) can still race an
   * "in use by another process" failure even though the process is confirmed dead. Polls briefly
   * for the file to become exclusively openable before returning; a no-op in practice on POSIX,
   * which never locks a file this way to begin with. Best-effort: gives up silently after {@link
   * #FILE_RELEASE_TIMEOUT} rather than failing the whole {@code down}, since an unrelated tool (a
   * tailing log viewer, an antivirus scan) could hold the file open indefinitely for reasons
   * outside this launcher's own control.
   */
  private static void awaitFileReleased(final Path file) {
    if (Files.notExists(file)) {
      return;
    }
    final long deadline = System.nanoTime() + FILE_RELEASE_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.WRITE)) {
        return;
      } catch (final IOException e) {
        try {
          Thread.sleep(25);
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
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

  /**
   * The process tree recorded under {@code dataRoot}, as data rather than as printed lines -- what
   * {@link #status} renders, and what a supervising tool needs to re-adopt a cluster it launched
   * before it was itself restarted. Empty when no ledger is there.
   */
  public static List<RunRecord> recordedProcesses(final Path dataRoot) {
    return RunLedger.read(dataRoot);
  }

  public static void status(final Path dataRoot, final PrintStream out) {
    final List<RunRecord> records = recordedProcesses(dataRoot);
    for (final RunRecord record : records) {
      final String readiness = readinessSummary(record);
      out.println(
          record.role()
              + " "
              + record.id()
              + " pid="
              + record.pid()
              + " alive="
              + isActuallyAlive(record, readiness)
              + " readiness="
              + readiness);
    }
  }

  /**
   * A pid the OS process table still lists is not necessarily a running process: a killed process
   * that its own parent hasn't reaped yet lingers as a zombie, and {@link ProcessHandle#isAlive()}
   * reports that pid alive right up until reaping happens -- which, for a process this launcher
   * spawned in a now-long-exited {@code up} invocation, may never happen promptly (its real parent
   * is whatever re-parented it, not this JVM). {@code up} only ever ledgers a record after that
   * process's own readiness address answered open, so a since-observed "closed" is proof the
   * process stopped serving, not evidence it's merely still starting -- overriding a lingering
   * zombie's own stale "alive" pid entry. A blank/unknown readiness reading carries no such proof
   * either way, so those fall back to the plain process-table signal.
   */
  private static boolean isActuallyAlive(final RunRecord record, final String readinessSummary) {
    final boolean processTableAlive =
        ProcessHandle.of(record.pid()).map(ProcessHandle::isAlive).orElse(false);
    return processTableAlive && !readinessSummary.equals("closed");
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
