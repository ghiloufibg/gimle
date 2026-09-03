package com.gimle.mavenplugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/**
 * "Ensure an Ivaldi server is up" mechanics shared by {@link IvaldiMojo}: a healthy server at the
 * endpoint is reused as-is; otherwise one is spawned detached (its output to a log file, its pid
 * recorded so {@link IvaldiStopMojo} can find it later, never tied to this Maven process's own
 * lifetime) and awaited on its health endpoint. Mirrors {@code SagaServer}'s own plugin-side helper
 * exactly, deliberately colliding on the name with {@code com.gimle.ivaldi.IvaldiServer}: the two
 * are unrelated classes in different Maven modules that happen to describe the same server from two
 * sides.
 */
final class IvaldiServer {

  enum Ensured {
    REUSED,
    SPAWNED
  }

  /** How a server process gets started; a seam so the wait/decision logic is testable alone. */
  interface Spawner {
    Process spawn() throws MojoExecutionException;
  }

  private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(200);

  private IvaldiServer() {}

  static Ensured ensureRunning(IvaldiClient client, Spawner spawner, Duration timeout, Log log)
      throws MojoExecutionException {
    if (client.isHealthy()) {
      return Ensured.REUSED;
    }
    log.info("no Ivaldi server responding at " + client.endpoint() + "; starting one");
    Process process = spawner.spawn();
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (client.isHealthy()) {
        return Ensured.SPAWNED;
      }
      if (!process.isAlive()) {
        throw new MojoExecutionException(
            "spawned Ivaldi server exited with code "
                + process.exitValue()
                + " before becoming healthy; see "
                + logFile());
      }
      sleepQuietly(HEALTH_POLL_INTERVAL);
      if (Thread.currentThread().isInterrupted()) {
        throw new MojoExecutionException("interrupted while waiting for the Ivaldi server");
      }
    }
    throw new MojoExecutionException(
        "Ivaldi server at " + client.endpoint() + " did not become healthy within " + timeout);
  }

  static Path dataDir() {
    return Path.of(System.getProperty("user.home"), ".gimle", "ivaldi");
  }

  static Path pidFile() {
    return dataDir().resolve("ivaldi.pid");
  }

  static Path logFile() {
    return dataDir().resolve("ivaldi.log");
  }

  static List<String> spawnCommand(String javaExecutable, String classpath, String port) {
    return List.of(
        javaExecutable,
        "-cp",
        classpath,
        "-Dgimle.ivaldi.port=" + port,
        "com.gimle.ivaldi.IvaldiMain");
  }

  static Process spawnDetached(List<String> command, Log log) throws MojoExecutionException {
    try {
      Files.createDirectories(dataDir());
      Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()))
              .start();
      Files.writeString(pidFile(), Long.toString(process.pid()));
      log.info("started Ivaldi server (pid " + process.pid() + ", log " + logFile() + ")");
      return process;
    } catch (IOException e) {
      throw new MojoExecutionException("failed to start Ivaldi server: " + command, e);
    }
  }

  static Optional<Long> recordedPid() {
    try {
      return Optional.of(Long.parseLong(Files.readString(pidFile()).trim()));
    } catch (IOException | NumberFormatException e) {
      return Optional.empty();
    }
  }

  static void deletePidFile() {
    try {
      Files.deleteIfExists(pidFile());
    } catch (IOException ignored) {
      // Best-effort cleanup of a hint file; a stale pidfile only costs a failed signal later.
    }
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
