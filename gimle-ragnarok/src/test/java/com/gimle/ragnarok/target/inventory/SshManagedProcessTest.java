package com.gimle.ragnarok.target.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.remote.ResolvedSshTarget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link SshManagedProcess} against a real local process (via {@link FakeRemoteExec},
 * which runs its generated shell scripts locally instead of over SSH) -- a genuine kill/restart
 * cycle on a real OS process, not a mocked one.
 */
final class SshManagedProcessTest {

  @TempDir private Path tempDir;

  private SshManagedProcess process(final Path pidFile, final Path logFile) {
    final ResolvedSshTarget target =
        new ResolvedSshTarget(
            "test-machine",
            "unused",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            ResolvedSshTarget.DEFAULT_INSTALL_DIR,
            Optional.empty(),
            Optional.empty());
    final ManagedRoleSpec role =
        new ManagedRoleSpec("node-1", "store-0", pidFile, logFile, List.of("sleep", "300"));
    return new SshManagedProcess(new FakeRemoteExec(), target, role, "127.0.0.1:0");
  }

  @Test
  @Timeout(value = 20)
  void restart_spawns_a_real_process_and_alive_and_pid_reflect_it() {
    final SshManagedProcess process = process(tempDir.resolve("a.pid"), tempDir.resolve("a.log"));
    assertFalse(process.isAlive(), "no pidfile yet -- must not report alive");

    process.restart();
    assertTrue(process.isAlive());
    final long pid = process.pid();
    assertTrue(pid > 0, "expected a real positive pid, got " + pid);
    assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));

    process.kill();
  }

  @Test
  @Timeout(value = 20)
  void kill_blocks_until_the_real_process_is_dead() {
    final SshManagedProcess process = process(tempDir.resolve("b.pid"), tempDir.resolve("b.log"));
    process.restart();
    final long pid = process.pid();

    process.kill();

    assertFalse(process.isAlive());
    assertFalse(
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
        "the real OS process should be dead once kill() returns");
  }

  @Test
  @Timeout(value = 20)
  void restart_after_kill_produces_a_new_pid() {
    final SshManagedProcess process = process(tempDir.resolve("c.pid"), tempDir.resolve("c.log"));
    process.restart();
    final long firstPid = process.pid();
    process.kill();

    process.restart();
    final long secondPid = process.pid();

    assertNotEquals(firstPid, secondPid);
    assertTrue(process.isAlive());
    process.kill();
  }

  @Test
  @Timeout(value = 20)
  void exit_was_expected_flips_around_kill_and_restart() {
    final SshManagedProcess process = process(tempDir.resolve("d.pid"), tempDir.resolve("d.log"));
    process.restart();
    assertFalse(process.exitWasExpected());

    process.kill();
    assertTrue(process.exitWasExpected());

    process.restart();
    assertFalse(process.exitWasExpected());
    process.kill();
  }

  @Test
  @Timeout(value = 30)
  void on_exit_fires_when_the_process_dies_unexpectedly() throws InterruptedException {
    final SshManagedProcess process = process(tempDir.resolve("e.pid"), tempDir.resolve("e.log"));
    process.restart();
    final long pid = process.pid();
    final AtomicBoolean fired = new AtomicBoolean(false);
    process.onExit(() -> fired.set(true));

    // Kill the real process directly, bypassing SshManagedProcess.kill() -- an unexpected death
    // the background poller must still notice.
    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);

    final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (!fired.get() && System.nanoTime() < deadline) {
      Thread.sleep(200);
    }
    assertTrue(fired.get(), "onExit callback should have fired for an unexpected death");
    assertFalse(process.isAlive(), "the destroyed process should no longer report alive");
  }
}
