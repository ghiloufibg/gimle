package com.gimle.hilmir.remote;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.plan.ClusterPlan;
import com.gimle.hilmir.plan.LaunchPlanner;
import com.gimle.hilmir.plan.MachinePlan;
import com.gimle.hilmir.plan.ProcessCommand;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.ProcessRole;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.Transport;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * {@code --remote} dispatch: re-invokes the exact same, already-proven local {@code hilmir up}/
 * {@code down}/{@code status}/{@code upgrade-cluster --machine <name>} on every target machine over
 * SSH -- no new spawn/kill/status/restart logic of its own; {@code
 * com.gimle.hilmir.launch.MachineLauncher}, its run ledger, its readiness polling, and {@code
 * com.gimle.hilmir.upgrade.UpgradeClusterCommand} are all untouched. This mirrors what {@code
 * gimle-holmgang}'s Utgard test harness already does via {@code docker exec} against a container
 * per machine -- same trick, real SSH instead of the container-exec test stand-in.
 *
 * <p>{@code up} additionally self-provisions (ships and unpacks {@code target}'s configured {@code
 * archive} when {@code bin/hilmir} isn't already there) and distributes exactly the TLS/Fafnir-key
 * material each machine's own {@link MachinePlan} says it needs, before the topology file itself is
 * shipped -- see {@link #provisionIfMissing} and {@link #distributeMaterial}. Every verb's dispatch
 * pins each target's SSH host key into a per-topology {@code known_hosts} file before anything else
 * runs against it -- see {@link #dispatch} and {@link RemoteExec#pinHostKey}.
 *
 * <p>When {@code machineFilter} is empty, every machine the topology declares is dispatched to
 * concurrently (one virtual thread each); one machine's failure never aborts the others -- the
 * aggregate exit code is non-zero if any machine failed, matching {@code
 * com.gimle.hilmir.launch.MachineLauncher.up}'s own partial-progress philosophy (a later command's
 * failure never discards records from commands that already succeeded).
 */
public final class RemoteDispatch {

  private RemoteDispatch() {}

  public static int up(
      final Topology topology,
      final Path topologyFile,
      final Optional<String> machineFilter,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final ResolvedRuntime runtime,
      final PrintStream out) {
    return up(
        topology,
        topologyFile,
        machineFilter,
        dataRootOverride,
        cliFlags,
        runtime,
        new SshProcessExec(knownHostsFile(topology)),
        out);
  }

  static int up(
      final Topology topology,
      final Path topologyFile,
      final Optional<String> machineFilter,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final ResolvedRuntime runtime,
      final RemoteExec exec,
      final PrintStream out) {
    final ClusterPlan clusterPlan = LaunchPlanner.plan(topology, runtime);
    return dispatch(
        topology,
        machineFilter,
        cliFlags,
        exec,
        out,
        (target, transport) -> {
          provisionIfMissing(target, transport, out);
          distributeMaterial(topology, clusterPlan, target, transport);
          final String remoteTopologyPath =
              target.installDir() + "/hilmir-remote-topology-" + target.machineName() + ".yaml";
          transport.putFile(target, topologyFile, remoteTopologyPath);
          final List<String> command =
              new ArrayList<>(
                  List.of(
                      target.remoteHilmirBinary(),
                      "up",
                      "-f",
                      remoteTopologyPath,
                      "--machine",
                      target.machineName()));
          dataRootOverride.ifPresent(p -> command.addAll(List.of("--data-root", p.toString())));
          return command;
        });
  }

  public static int down(
      final Topology topology,
      final Optional<String> machineFilter,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final PrintStream out) {
    return down(
        topology,
        machineFilter,
        dataRootOverride,
        cliFlags,
        new SshProcessExec(knownHostsFile(topology)),
        out);
  }

  static int down(
      final Topology topology,
      final Optional<String> machineFilter,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final RemoteExec exec,
      final PrintStream out) {
    return dispatch(
        topology,
        machineFilter,
        cliFlags,
        exec,
        out,
        (target, transport) -> machineVerbCommand(target, "down", dataRootOverride));
  }

  public static int stop(
      final Topology topology,
      final Optional<String> machineFilter,
      final Optional<String> role,
      final Optional<String> id,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final PrintStream out) {
    return stop(
        topology,
        machineFilter,
        role,
        id,
        dataRootOverride,
        cliFlags,
        new SshProcessExec(knownHostsFile(topology)),
        out);
  }

  static int stop(
      final Topology topology,
      final Optional<String> machineFilter,
      final Optional<String> role,
      final Optional<String> id,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final RemoteExec exec,
      final PrintStream out) {
    return dispatch(
        topology,
        machineFilter,
        cliFlags,
        exec,
        out,
        (target, transport) -> {
          final List<String> command = machineVerbCommand(target, "stop", dataRootOverride);
          role.ifPresent(r -> command.addAll(List.of("--role", r)));
          id.ifPresent(i -> command.addAll(List.of("--id", i)));
          return command;
        });
  }

  public static int status(
      final Topology topology,
      final Optional<String> machineFilter,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final PrintStream out) {
    return status(
        topology,
        machineFilter,
        dataRootOverride,
        cliFlags,
        new SshProcessExec(knownHostsFile(topology)),
        out);
  }

  static int status(
      final Topology topology,
      final Optional<String> machineFilter,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final RemoteExec exec,
      final PrintStream out) {
    return dispatch(
        topology,
        machineFilter,
        cliFlags,
        exec,
        out,
        (target, transport) -> machineVerbCommand(target, "status", dataRootOverride));
  }

  public static int upgradeCluster(
      final Topology topology,
      final Path topologyFile,
      final Optional<String> machineFilter,
      final String newClasspath,
      final Optional<String> newJavaExecutable,
      final Optional<Path> dataRootOverride,
      final List<String> roles,
      final SshCliFlags cliFlags,
      final PrintStream out) {
    return upgradeCluster(
        topology,
        topologyFile,
        machineFilter,
        newClasspath,
        newJavaExecutable,
        dataRootOverride,
        roles,
        cliFlags,
        new SshProcessExec(knownHostsFile(topology)),
        out);
  }

  static int upgradeCluster(
      final Topology topology,
      final Path topologyFile,
      final Optional<String> machineFilter,
      final String newClasspath,
      final Optional<String> newJavaExecutable,
      final Optional<Path> dataRootOverride,
      final List<String> roles,
      final SshCliFlags cliFlags,
      final RemoteExec exec,
      final PrintStream out) {
    return dispatch(
        topology,
        machineFilter,
        cliFlags,
        exec,
        out,
        (target, transport) -> {
          final String remoteTopologyPath =
              target.installDir() + "/hilmir-remote-topology-" + target.machineName() + ".yaml";
          transport.putFile(target, topologyFile, remoteTopologyPath);
          final List<String> command =
              new ArrayList<>(
                  List.of(
                      target.remoteHilmirBinary(),
                      "upgrade-cluster",
                      "-f",
                      remoteTopologyPath,
                      "--machine",
                      target.machineName(),
                      "--new-classpath",
                      newClasspath));
          newJavaExecutable.ifPresent(j -> command.addAll(List.of("--new-java-executable", j)));
          dataRootOverride.ifPresent(p -> command.addAll(List.of("--data-root", p.toString())));
          for (final String role : roles) {
            command.addAll(List.of("--role", role));
          }
          return command;
        });
  }

  private static List<String> machineVerbCommand(
      final ResolvedSshTarget target, final String verb, final Optional<Path> dataRootOverride) {
    final List<String> command =
        new ArrayList<>(
            List.of(target.remoteHilmirBinary(), verb, "--machine", target.machineName()));
    dataRootOverride.ifPresent(p -> command.addAll(List.of("--data-root", p.toString())));
    return command;
  }

  /**
   * Ships and unpacks {@code target}'s configured local platform archive when {@code bin/hilmir}
   * isn't already present at {@code target.installDir()} -- checked via a plain {@code test -x},
   * run outside the not-yet-existing {@code installDir} (see {@link RemoteExec#execRaw}). Unpacks
   * to a fresh temp directory first and only then atomically replaces {@code installDir}, so a
   * crash mid-unpack never leaves a half-installed directory visible at the real path.
   */
  private static void provisionIfMissing(
      final ResolvedSshTarget target, final RemoteExec transport, final PrintStream out) {
    final int probeExit =
        transport.execRaw(target, List.of("test", "-x", target.remoteHilmirBinary()), out);
    if (probeExit == 0) {
      return;
    }
    final Path archive =
        target
            .archive()
            .orElseThrow(
                () ->
                    new HilmirException(
                        "machine '"
                            + target.machineName()
                            + "' has no hilmir install at "
                            + target.installDir()
                            + " and no archive configured (machines[].ssh.archive or"
                            + " runtime.ssh.archive) to provision one"));
    out.println(
        "[" + target.machineName() + "] provisioning " + target.installDir() + " from " + archive);
    final String remoteArchive = "/tmp/hilmir-provision-" + target.machineName() + ".tar.gz";
    final String unpackTemp = "/tmp/hilmir-unpack-" + target.machineName();
    transport.putFile(target, archive, remoteArchive);
    // Moves the unpacked archive's own top-level entries (bin/, lib/, modules/, ...) into
    // installDir rather than deleting-and-recreating installDir itself: an operator commonly
    // pre-creates and chowns installDir to the deploy user without granting it write access to
    // installDir's own parent, so "rm -rf installDir" (which needs write on the *parent*) would
    // fail even though the deploy user fully owns installDir's own contents. "mkdir -p" is a
    // harmless no-op when installDir already exists this way.
    final String script =
        "set -e; rm -rf "
            + unpackTemp
            + "; mkdir -p "
            + unpackTemp
            + "; tar -xzf "
            + remoteArchive
            + " -C "
            + unpackTemp
            + " --strip-components=1; mkdir -p "
            + target.installDir()
            + "; find "
            + unpackTemp
            + " -mindepth 1 -maxdepth 1 -exec mv -t "
            + target.installDir()
            + " {} +; rm -rf "
            + unpackTemp
            + "; rm -f "
            + remoteArchive;
    final int exitCode = transport.execRaw(target, List.of("sh", "-c", script), out);
    if (exitCode != 0) {
      throw new HilmirException(
          "provisioning failed on machine '" + target.machineName() + "' (exit " + exitCode + ")");
    }
  }

  /**
   * Ships only what {@code target}'s own {@link MachinePlan} actually needs, already generated
   * locally by {@code hilmir pki init} -- no cross-machine ordering is needed here: every file
   * distributed already has its final, consistent content before any {@code --remote up} dispatch
   * starts, so a plain per-machine copy is all this is. Every leaf is named {@code
   * <role>-<hostname>.crt/.key} (see {@code com.gimle.pki.PkiBootstrapMain}); {@code target.host()}
   * is already this machine's own hostname, so no extra topology lookup is needed here.
   */
  private static void distributeMaterial(
      final Topology topology,
      final ClusterPlan clusterPlan,
      final ResolvedSshTarget target,
      final RemoteExec transport) {
    final MachinePlan machinePlan = clusterPlan.byMachine().get(target.machineName());
    if (machinePlan == null) {
      return;
    }
    final Set<ProcessRole> roles =
        machinePlan.commands().stream().map(ProcessCommand::role).collect(Collectors.toSet());

    if (topology.transport() == Transport.MTLS) {
      final Path materialDir = topology.tls().orElseThrow().materialDir();
      ensureRemoteDir(target, transport, materialDir.toString());
      copyFile(target, transport, materialDir.resolve("ca.crt"));
      // Stores present the control-plane leaf for their own machine (LaunchPlanner's own reuse),
      // so a store-only machine still needs it even without hosting a control-plane replica.
      if (roles.contains(ProcessRole.STORE) || roles.contains(ProcessRole.CONTROL_PLANE)) {
        copyLeaf(target, transport, materialDir, "controlplane");
      }
      if (roles.contains(ProcessRole.FAFNIR)) {
        copyLeaf(target, transport, materialDir, "fafnir");
      }
      if (roles.contains(ProcessRole.MUNINN)) {
        copyLeaf(target, transport, materialDir, "muninn");
      }
      if (roles.contains(ProcessRole.ANDVARI)) {
        copyLeaf(target, transport, materialDir, "andvari");
      }
      // The CA private key signs agent CSRs -- only a machine hosting a control plane ever needs
      // it; the operator identity mints an agent's bootstrap token locally on whichever machine
      // that agent's own `up` runs on.
      if (roles.contains(ProcessRole.CONTROL_PLANE)) {
        copyFile(target, transport, materialDir.resolve("ca.key"));
      }
      if (roles.contains(ProcessRole.AGENT)) {
        copyFile(target, transport, materialDir.resolve("operator.crt"));
        copyFile(target, transport, materialDir.resolve("operator.key"));
      }
    }

    if (roles.contains(ProcessRole.FAFNIR)) {
      topology
          .fafnir()
          .keyFile()
          // Only when hilmir pki init actually generated one locally -- a single-machine Fafnir
          // (the common case) has no local key file to distribute at all, and its own FafnirMain
          // just self-generates one on first start remotely, exactly as it already does for local
          // (non-remote) dispatch; there's nothing here to ship in that case.
          .filter(Files::exists)
          .ifPresent(
              keyFile -> {
                final Path parent = keyFile.getParent();
                if (parent != null) {
                  ensureRemoteDir(target, transport, parent.toString());
                }
                copyFile(target, transport, keyFile);
              });
    }
  }

  private static void copyLeaf(
      final ResolvedSshTarget target,
      final RemoteExec transport,
      final Path materialDir,
      final String role) {
    final String leafName = role + "-" + target.host();
    copyFile(target, transport, materialDir.resolve(leafName + ".crt"));
    copyFile(target, transport, materialDir.resolve(leafName + ".key"));
  }

  private static void copyFile(
      final ResolvedSshTarget target, final RemoteExec transport, final Path localFile) {
    transport.putFile(target, localFile, localFile.toString());
  }

  private static void ensureRemoteDir(
      final ResolvedSshTarget target, final RemoteExec transport, final String dirPath) {
    transport.execRaw(target, List.of("mkdir", "-p", dirPath), discardOut());
  }

  private static PrintStream discardOut() {
    return new PrintStream(OutputStream.nullOutputStream());
  }

  private static Path knownHostsFile(final Topology topology) {
    return topology.runtime().dataRoot().orElse(Path.of("gimle-data")).resolve("known_hosts");
  }

  /**
   * The shared fan-out core: resolves which machines to target, pins every target's SSH host key
   * (sequentially, before any concurrent work starts against any of them -- see {@link
   * RemoteExec#pinHostKey}), then for each successfully-pinned machine builds its remote command
   * (via {@code commandBuilder}, which may itself use {@code transport} for a side-effecting step
   * like provisioning or a file copy) and runs it on its own virtual thread. A pinning failure is
   * recorded exactly like an exec failure -- that one machine never starts, every other machine is
   * unaffected, and the aggregate exit code goes non-zero.
   */
  private static int dispatch(
      final Topology topology,
      final Optional<String> machineFilter,
      final SshCliFlags cliFlags,
      final RemoteExec exec,
      final PrintStream out,
      final BiFunction<ResolvedSshTarget, RemoteExec, List<String>> commandBuilder) {
    final List<Machine> machines = selectMachines(topology, machineFilter);
    final List<ResolvedSshTarget> targets =
        machines.stream()
            .map(m -> ResolvedSshTarget.resolve(m, topology.runtime(), cliFlags))
            .toList();

    final List<Integer> exitCodes = new CopyOnWriteArrayList<>();
    final List<ResolvedSshTarget> pinned = new ArrayList<>();
    final Path localDataRoot = topology.runtime().dataRoot().orElse(Path.of("gimle-data"));
    createDirectories(localDataRoot);
    final Path knownHostsFile = localDataRoot.resolve("known_hosts");
    for (final ResolvedSshTarget target : targets) {
      try {
        exec.pinHostKey(target, knownHostsFile);
        pinned.add(target);
      } catch (final RuntimeException e) {
        out.println("[" + target.machineName() + "] failed: " + e.getMessage());
        exitCodes.add(1);
      }
    }

    final List<Thread> threads = new ArrayList<>();
    for (final ResolvedSshTarget target : pinned) {
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      final List<String> command = commandBuilder.apply(target, exec);
                      final int exitCode = exec.exec(target, command, out);
                      exitCodes.add(exitCode);
                      if (exitCode != 0) {
                        out.println("[" + target.machineName() + "] exited with code " + exitCode);
                      }
                    } catch (final RuntimeException e) {
                      out.println("[" + target.machineName() + "] failed: " + e.getMessage());
                      exitCodes.add(1);
                    }
                  }));
    }
    awaitAll(threads);
    return exitCodes.stream().anyMatch(code -> code != 0) ? 1 : 0;
  }

  private static List<Machine> selectMachines(
      final Topology topology, final Optional<String> machineFilter) {
    if (machineFilter.isEmpty()) {
      return topology.machines();
    }
    return topology.machines().stream()
        .filter(m -> m.name().equals(machineFilter.get()))
        .findFirst()
        .map(List::of)
        .orElseThrow(
            () ->
                new HilmirException(
                    "no machine named '" + machineFilter.get() + "' in this topology"));
  }

  private static void createDirectories(final Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (final IOException e) {
      throw new HilmirException("failed creating " + dir, e);
    }
  }

  private static void awaitAll(final List<Thread> threads) {
    boolean interrupted = false;
    for (final Thread thread : threads) {
      while (true) {
        try {
          thread.join();
          break;
        } catch (final InterruptedException e) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
