package com.gimle.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A deliberately crashing stand-in for a worker JVM, used by {@link WorkerProcessSupervisorTest} to
 * exercise real kill-and-respawn behavior without needing the full {@code gimle-worker} runtime.
 * Most tests here hand {@code WorkerProcessSupervisor} the same command supplier across every
 * respawn, so varying behavior across invocations (crash instantly the first N times, then stay up
 * for a while) needs state that outlives any single invocation -- a counter file, incremented on
 * each run.
 *
 * <p>Args: {@code <counterFile> <immediateCrashCount> <stableSleepMillis> <exitCode> [<hsErrDir>]}.
 * The optional 5th argument, when non-blank, has this driver write a stub {@code hs_err_pid<its own
 * pid>.log} into that directory right before exiting -- standing in for a real HotSpot crash dump
 * so {@code WorkerProcessSupervisorTest} can exercise the {@code NATIVE_CRASH} classification path
 * without an actual native fault. Ignores any further trailing argument -- {@code
 * WorkerProcessSupervisor} always appends the control-socket path as its final argument, which this
 * driver never uses.
 */
public final class CrashingWorkerDriver {

  private CrashingWorkerDriver() {}

  public static void main(String[] args) throws InterruptedException {
    Path counterFile = Path.of(args[0]);
    int immediateCrashCount = Integer.parseInt(args[1]);
    long stableSleepMillis = Long.parseLong(args[2]);
    int exitCode = Integer.parseInt(args[3]);
    Optional<Path> hsErrDir =
        args.length > 4 && !args[4].isBlank() ? Optional.of(Path.of(args[4])) : Optional.empty();

    int invocation = incrementAndGetCount(counterFile);
    if (invocation > immediateCrashCount) {
      Thread.sleep(stableSleepMillis);
    }
    hsErrDir.ifPresent(CrashingWorkerDriver::writeStubHsErrFile);
    System.exit(exitCode);
  }

  private static void writeStubHsErrFile(Path dir) {
    try {
      Files.createDirectories(dir);
      long pid = ProcessHandle.current().pid();
      Files.writeString(dir.resolve("hs_err_pid" + pid + ".log"), "stub crash dump\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
