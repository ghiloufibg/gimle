package com.gimle.ragnarok.target.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.remote.ResolvedSshTarget;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises {@link SshWorkerHandle} against a real local process (via {@link FakeRemoteExec}) --
 * unlike {@link SshManagedProcess}, this handle tracks a single pid fixed at construction, with no
 * pid file and no restart of its own.
 */
final class SshWorkerHandleTest {

  private static final ResolvedSshTarget TARGET =
      new ResolvedSshTarget(
          "test-machine",
          "unused",
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          ResolvedSshTarget.DEFAULT_INSTALL_DIR,
          Optional.empty(),
          Optional.empty());

  @Test
  @Timeout(value = 20)
  void pid_reflects_the_pid_it_was_constructed_with() throws InterruptedException, IOException {
    final Process process = new ProcessBuilder("sleep", "300").start();
    try {
      final SshWorkerHandle handle =
          new SshWorkerHandle(new FakeRemoteExec(), TARGET, process.pid());
      assertEquals(process.pid(), handle.pid());
      assertTrue(handle.isAlive());
    } finally {
      process.destroyForcibly();
      process.waitFor();
    }
  }

  @Test
  @Timeout(value = 20)
  void kill_stops_the_real_process() throws InterruptedException, IOException {
    final Process process = new ProcessBuilder("sleep", "300").start();
    final SshWorkerHandle handle = new SshWorkerHandle(new FakeRemoteExec(), TARGET, process.pid());
    assertTrue(handle.isAlive());

    handle.kill();

    process.waitFor();
    assertFalse(handle.isAlive());
    assertFalse(process.isAlive());
  }

  @Test
  @Timeout(value = 5)
  void is_alive_is_false_for_a_pid_that_was_never_running() {
    // A pid far outside any real process table -- long.MAX_VALUE is never a real OS pid.
    final SshWorkerHandle handle =
        new SshWorkerHandle(new FakeRemoteExec(), TARGET, Long.MAX_VALUE - 1);
    assertFalse(handle.isAlive());
  }
}
