package com.gimle.agent;

import java.time.Duration;

/**
 * A stand-in for a long-lived worker JVM that does nothing but stay alive, used by {@link
 * AgentAdminServerTest} to exercise kill/respawn dispatch without needing the full {@code
 * gimle-worker} runtime. Replaces the earlier {@code sh -c "sleep 300"} command: a POSIX shell
 * isn't guaranteed to be on {@code PATH} on plain Windows the way it is on Linux/macOS, while this
 * driver only needs the JVM already spawning it. Ignores any trailing argument -- {@code
 * WorkerProcessSupervisor} always appends the control-socket path as its final argument, which this
 * driver never uses.
 */
public final class SleepingWorkerDriver {

  private SleepingWorkerDriver() {}

  public static void main(String[] args) throws InterruptedException {
    Thread.sleep(Duration.ofSeconds(300).toMillis());
  }
}
