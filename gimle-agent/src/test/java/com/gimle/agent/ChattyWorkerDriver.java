package com.gimle.agent;

/**
 * A stand-in worker JVM that does nothing but print plain (non-JSON) lines to stdout, then blocks
 * forever -- used by {@link WorkerProcessSupervisorSystemLogRotationTest} to drive real SYSTEM
 * capture volume through a real {@link WorkerProcessSupervisor} without needing the full {@code
 * gimle-worker} runtime. Blocking rather than exiting keeps the supervisor from treating this run
 * as a crash and respawning mid-test, which would restart the byte counter this test relies on
 * staying attached to one continuous capture stream.
 *
 * <p>Args: {@code <lineCount> <lineText>}. Ignores any further trailing argument -- {@code
 * WorkerProcessSupervisor} always appends the control-socket path as its final argument, which this
 * driver never uses.
 */
public final class ChattyWorkerDriver {

  private ChattyWorkerDriver() {}

  public static void main(String[] args) throws InterruptedException {
    int lineCount = Integer.parseInt(args[0]);
    String lineText = args[1];
    for (int i = 0; i < lineCount; i++) {
      System.out.println(lineText + " " + i);
      System.out.flush();
    }
    Thread.sleep(Long.MAX_VALUE);
  }
}
