package com.gimle.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A deliberately crashing stand-in for a worker JVM, used by {@link WorkerProcessSupervisorTest} to
 * exercise real kill-and-respawn behavior without needing the full {@code gimle-worker} runtime.
 * {@code WorkerProcessSupervisor} reuses the same {@code baseCommand} across every respawn, so
 * varying behavior across invocations (crash instantly the first N times, then stay up for a while)
 * needs state that outlives any single invocation -- a counter file, incremented on each run.
 *
 * <p>Args: {@code <counterFile> <immediateCrashCount> <stableSleepMillis> <exitCode>}. Ignores any
 * further trailing argument -- {@code WorkerProcessSupervisor} always appends the control-socket
 * path as its final argument, which this driver never uses.
 */
public final class CrashingWorkerDriver {

  private CrashingWorkerDriver() {}

  public static void main(String[] args) throws InterruptedException {
    Path counterFile = Path.of(args[0]);
    int immediateCrashCount = Integer.parseInt(args[1]);
    long stableSleepMillis = Long.parseLong(args[2]);
    int exitCode = Integer.parseInt(args[3]);

    int invocation = incrementAndGetCount(counterFile);
    if (invocation > immediateCrashCount) {
      Thread.sleep(stableSleepMillis);
    }
    System.exit(exitCode);
  }

  private static int incrementAndGetCount(Path counterFile) {
    try {
      int previous =
          Files.exists(counterFile) ? Integer.parseInt(Files.readString(counterFile).strip()) : 0;
      int next = previous + 1;
      Files.writeString(counterFile, Integer.toString(next));
      return next;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
