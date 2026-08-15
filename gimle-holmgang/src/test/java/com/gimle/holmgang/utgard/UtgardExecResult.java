package com.gimle.holmgang.utgard;

import com.gimle.holmgang.HolmgangException;
import java.util.List;

/**
 * The outcome of one command run inside an Utgard container: the command line, its exit code, and
 * its captured stdout/stderr. Deliberately decoupled from Testcontainers' own {@code
 * Container.ExecResult} -- {@link UtgardMachines} is the only place that ever touches Docker, so
 * this plain record is what lets {@link UtgardExec}'s own assertion logic be exercised by a fast
 * unit test that never needs a daemon.
 */
public record UtgardExecResult(List<String> command, int exitCode, String stdout, String stderr) {

  public UtgardExecResult {
    if (command == null || command.isEmpty()) {
      throw new HolmgangException("an exec result must record a non-empty command line");
    }
    command = List.copyOf(command);
    if (stdout == null || stderr == null) {
      throw new HolmgangException("an exec result's captured stdout/stderr must not be null");
    }
  }

  public boolean succeeded() {
    return exitCode == 0;
  }
}
