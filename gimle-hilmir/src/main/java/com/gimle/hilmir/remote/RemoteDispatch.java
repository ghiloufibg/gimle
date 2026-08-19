package com.gimle.hilmir.remote;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.Topology;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * {@code --remote} dispatch: re-invokes the exact same, already-proven local {@code hilmir up}/
 * {@code down}/{@code status --machine <name>} on every target machine over SSH -- no new spawn/
 * kill/status logic of its own; {@code com.gimle.hilmir.launch.MachineLauncher}, its run ledger,
 * and its readiness polling are all untouched. This mirrors what {@code gimle-holmgang}'s Utgard
 * test harness already does via {@code docker exec} against a container per machine -- same trick,
 * real SSH instead of the container-exec test stand-in.
 *
 * <p>When {@code machineFilter} is empty, every machine the topology declares is dispatched to
 * concurrently (one virtual thread each); one machine's failure never aborts the others -- the
 * aggregate exit code is non-zero if any machine failed, matching {@code
 * com.gimle.hilmir.launch.MachineLauncher.up}'s own partial-progress philosophy (a later command's
 * failure never discards records from commands that already succeeded).
 */
public final class RemoteDispatch {

  private static final String HOST_KEY_WARNING =
      "--remote: skipping SSH host key verification (v1) -- do not use over an untrusted network";

  private RemoteDispatch() {}

  public static int up(
      final Topology topology,
      final Path topologyFile,
      final Optional<String> machineFilter,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final PrintStream out) {
    return up(
        topology,
        topologyFile,
        machineFilter,
        dataRootOverride,
        cliFlags,
        new SshProcessExec(),
        out);
  }

  static int up(
      final Topology topology,
      final Path topologyFile,
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
        (target, transport) -> {
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
    return down(topology, machineFilter, dataRootOverride, cliFlags, new SshProcessExec(), out);
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

  public static int status(
      final Topology topology,
      final Optional<String> machineFilter,
      final Optional<Path> dataRootOverride,
      final SshCliFlags cliFlags,
      final PrintStream out) {
    return status(topology, machineFilter, dataRootOverride, cliFlags, new SshProcessExec(), out);
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

  private static List<String> machineVerbCommand(
      final ResolvedSshTarget target, final String verb, final Optional<Path> dataRootOverride) {
    final List<String> command =
        new ArrayList<>(
            List.of(target.remoteHilmirBinary(), verb, "--machine", target.machineName()));
    dataRootOverride.ifPresent(p -> command.addAll(List.of("--data-root", p.toString())));
    return command;
  }

  /**
   * The shared fan-out core: resolves which machines to target, then for each one builds its remote
   * command (via {@code commandBuilder}, which may itself use {@code transport} for a
   * side-effecting step like {@code up}'s topology-file copy) and runs it on its own virtual
   * thread. Waits for every thread before returning -- one machine's failure is recorded and
   * reported, never propagated to abort the others.
   */
  private static int dispatch(
      final Topology topology,
      final Optional<String> machineFilter,
      final SshCliFlags cliFlags,
      final RemoteExec exec,
      final PrintStream out,
      final BiFunction<ResolvedSshTarget, RemoteExec, List<String>> commandBuilder) {
    final List<Machine> targets = selectMachines(topology, machineFilter);
    out.println(HOST_KEY_WARNING);
    final List<Thread> threads = new ArrayList<>();
    final List<Integer> exitCodes = new CopyOnWriteArrayList<>();
    for (final Machine machine : targets) {
      final ResolvedSshTarget target =
          ResolvedSshTarget.resolve(machine, topology.runtime(), cliFlags);
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
