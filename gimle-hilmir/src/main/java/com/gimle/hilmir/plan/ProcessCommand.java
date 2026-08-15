package com.gimle.hilmir.plan;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.topology.ProcessRole;
import java.nio.file.Path;
import java.util.List;

/**
 * One process {@link LaunchPlanner} says should run, and everything a launcher needs to actually
 * run it: the full command line, where to redirect its output, its own scoped data directory, and
 * how to tell it's up.
 *
 * <p>{@code command} is not quite the literal argument vector a launcher should spawn: {@code
 * runtime.classpath}'s resolution already happened (see {@link ResolvedRuntime}), but a launcher
 * still owns two further steps before spawning -- rewriting it through {@link JavaArgFile} to stay
 * under the OS command-line length limit, and, when {@link #needsBootstrapToken()} is {@code true},
 * minting an agent's certificate-bootstrap token via a live call against an already-running control
 * plane and appending {@code -Dgimle.tls.bootstrapToken=<token>} -- both of which require the
 * cluster to already be partway up, which is precisely what a pure planner cannot assume.
 */
public record ProcessCommand(
    ProcessRole role,
    String id,
    String machine,
    List<String> command,
    String logFileName,
    Path dataDir,
    String readinessAddress,
    boolean needsBootstrapToken) {

  public ProcessCommand {
    if (role == null) {
      throw new GimleManifestException("a process command must declare a role");
    }
    if (id == null || id.isBlank()) {
      throw new GimleManifestException("a process command must declare a non-blank id");
    }
    if (machine == null || machine.isBlank()) {
      throw new GimleManifestException(
          "process command " + id + " must declare a non-blank machine");
    }
    if (command == null || command.isEmpty()) {
      throw new GimleManifestException(
          "process command " + id + " must have a non-empty command line");
    }
    command = List.copyOf(command);
    if (logFileName == null || logFileName.isBlank()) {
      throw new GimleManifestException(
          "process command " + id + " must declare a non-blank log file name");
    }
    if (dataDir == null) {
      throw new GimleManifestException("process command " + id + " must declare a data directory");
    }
    if (readinessAddress == null) {
      throw new GimleManifestException(
          "process command " + id + "'s readiness address must not be null (use \"\" if none)");
    }
  }
}
