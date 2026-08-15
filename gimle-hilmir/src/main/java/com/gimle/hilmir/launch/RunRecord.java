package com.gimle.hilmir.launch;

import com.gimle.core.exception.GimleManifestException;
import java.util.List;

/**
 * One process {@link MachineLauncher#up} spawned, durable enough (via {@link RunLedger}) for a
 * later {@code down}/{@code status} invocation -- necessarily a fresh JVM, with no in-memory state
 * left over from {@code up} -- to find and act on it again. {@code readinessAddress} is carried
 * alongside the pid/command/log file rather than re-derived later: {@code status} reports on it
 * without needing to re-plan anything, and a blank value (matching {@code
 * com.gimle.hilmir.plan.ProcessCommand}'s own convention) means "no port-based signal for this
 * process kind."
 */
public record RunRecord(
    String id,
    String role,
    long pid,
    List<String> command,
    String logFile,
    String readinessAddress) {

  public RunRecord {
    if (id == null || id.isBlank()) {
      throw new GimleManifestException("a run record must declare a non-blank id");
    }
    if (role == null || role.isBlank()) {
      throw new GimleManifestException("a run record must declare a non-blank role");
    }
    command = List.copyOf(command);
    if (logFile == null || logFile.isBlank()) {
      throw new GimleManifestException("a run record must declare a non-blank log file");
    }
    if (readinessAddress == null) {
      throw new GimleManifestException(
          "a run record's readiness address must not be null (use \"\" if none)");
    }
  }
}
