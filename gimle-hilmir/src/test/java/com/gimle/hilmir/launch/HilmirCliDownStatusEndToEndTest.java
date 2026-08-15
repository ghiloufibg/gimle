package com.gimle.hilmir.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirMain;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A true CLI-level (through {@link HilmirMain#run}) exercise of {@code status}/{@code down} against
 * a real, killable OS process -- {@link com.gimle.hilmir.launch.fixture.SocketFixtureMain},
 * standing in for the server socket every real Gimlé process kind opens -- so the actual
 * verb-dispatch path (not just {@link MachineLauncher} directly) is proven to find and act on a
 * previous {@code up}'s ledger.
 */
class HilmirCliDownStatusEndToEndTest {

  @TempDir Path tempDir;

  @Test
  void status_then_down_through_the_real_cli_dispatch_reports_and_stops_a_real_process()
      throws IOException {
    final int port = LaunchTestSupport.freePort();
    final ProcessBuilder processBuilder =
        new ProcessBuilder(
            LaunchTestSupport.javaExecutable(),
            "-cp",
            LaunchTestSupport.testClasspath(),
            "com.gimle.hilmir.launch.fixture.SocketFixtureMain",
            String.valueOf(port));
    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(
        ProcessBuilder.Redirect.to(tempDir.resolve("fixture.log").toFile()));
    final Process fixture = processBuilder.start();
    try {
      ReadinessPoller.awaitPortOpen(
          "127.0.0.1:" + port, Duration.ofSeconds(10), "the test fixture process");

      RunLedger.write(
          tempDir,
          List.of(
              new RunRecord(
                  "store-0",
                  "STORE",
                  fixture.pid(),
                  List.of(LaunchTestSupport.javaExecutable(), "-version"),
                  "store-0.log",
                  "127.0.0.1:" + port)));

      final Result statusResult =
          run("status", "--machine", "m1", "--data-root", tempDir.toString());
      assertEquals(0, statusResult.exitCode());
      assertTrue(statusResult.out().contains("alive=true"));
      assertTrue(statusResult.out().contains("readiness=open"));

      final Result downResult = run("down", "--machine", "m1", "--data-root", tempDir.toString());
      assertEquals(0, downResult.exitCode());
      assertFalse(fixture.isAlive());
    } finally {
      fixture.destroyForcibly();
    }
  }

  private record Result(int exitCode, String out, String err) {}

  private static Result run(final String... args) {
    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final int exitCode =
        HilmirMain.run(
            args,
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    return new Result(
        exitCode,
        outBytes.toString(StandardCharsets.UTF_8),
        errBytes.toString(StandardCharsets.UTF_8));
  }
}
