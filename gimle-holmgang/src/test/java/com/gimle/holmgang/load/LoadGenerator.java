package com.gimle.holmgang.load;

import com.gimle.holmgang.HolmgangException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Spawns {@link GreeterLoadSimulation} as {@code io.gatling.app.Gatling}'s own CLI in a separate
 * JVM against this test JVM's classpath -- Gatling's runtime is not designed to run twice in one
 * JVM, and every other cluster component the harness launches is a subprocess already. {@code
 * --add-opens java.base/java.lang} is a documented Gatling requirement on modern JDKs; {@code -nr}
 * skips HTML report generation, since scenarios assert on the cluster's reaction to the load, not
 * on Gatling's own charts.
 */
public final class LoadGenerator {

  private LoadGenerator() {}

  /** Open-model load: a fixed request rate, what a request-rate autoscale target reads. */
  public static Process openModel(
      final int requestsPerSecond,
      final int durationSeconds,
      final Path resultsDir,
      final Path logFile) {
    final ProcessBuilder pb =
        new ProcessBuilder(
            List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                "-Dgimle.load.requestsPerSecond=" + requestsPerSecond,
                "-Dgimle.load.durationSeconds=" + durationSeconds,
                "io.gatling.app.Gatling",
                "-s",
                GreeterLoadSimulation.class.getName(),
                "-rf",
                resultsDir.toString(),
                "-nr"));
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    try {
      return pb.start();
    } catch (final IOException e) {
      throw new HolmgangException("failed spawning the Gatling load generator", e);
    }
  }
}
