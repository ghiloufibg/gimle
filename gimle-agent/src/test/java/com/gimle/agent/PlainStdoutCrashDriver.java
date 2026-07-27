package com.gimle.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Like {@link CrashingWorkerDriver}, but also prints one plain (non-JSON) stdout banner line on
 * every invocation before crashing/exiting -- used by {@link SystemLogCaptureTest} to prove SYSTEM
 * capture survives a respawn, since {@code WorkerProcessSupervisor.captureSystemLine} only fires on
 * lines that don't parse as JSON.
 */
public final class PlainStdoutCrashDriver {

  private PlainStdoutCrashDriver() {}

  public static void main(String[] args) throws InterruptedException {
    Path counterFile = Path.of(args[0]);
    int immediateCrashCount = Integer.parseInt(args[1]);
    long stableSleepMillis = Long.parseLong(args[2]);
    int exitCode = Integer.parseInt(args[3]);

    int invocation = incrementAndGetCount(counterFile);
    System.out.println("plain text banner, invocation " + invocation);
    System.out.flush();
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
