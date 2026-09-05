package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * {@link MachineLauncher#isRunning} answers "is this recorded process up right now", which a
 * supervising tool re-asks on every read rather than reporting whatever was true at launch -- a
 * process killed from outside otherwise stays "ready" for as long as the launching tool lives.
 */
class MachineLauncherIsRunningTest {

  @Test
  void a_pid_that_has_already_exited_is_not_running() throws IOException, InterruptedException {
    final Process exited =
        new ProcessBuilder(LaunchTestSupport.javaExecutable(), "-version")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    exited.waitFor();

    assertFalse(MachineLauncher.isRunning(exited.pid(), ""));
  }

  @Test
  void a_live_process_declaring_no_readiness_port_is_running_on_its_pid_alone() {
    assertTrue(MachineLauncher.isRunning(ProcessHandle.current().pid(), ""));
  }

  @Test
  void a_live_process_whose_declared_port_is_closed_is_not_running() throws IOException {
    final String closed = "127.0.0.1:" + LaunchTestSupport.freePort();

    assertFalse(MachineLauncher.isRunning(ProcessHandle.current().pid(), closed));
  }
}
