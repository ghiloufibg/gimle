package com.gimle.hilmir.launch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

/** Shared plumbing for launch-package tests that spawn a real, cheap OS process. */
final class LaunchTestSupport {

  private static final Duration TEMP_DIR_DRAIN_TIMEOUT = Duration.ofSeconds(20);

  private LaunchTestSupport() {}

  static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  static String testClasspath() {
    return System.getProperty("java.class.path");
  }

  /**
   * A loopback port free at the moment of the call -- released immediately for the caller to bind.
   */
  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  /**
   * Empties every file and subdirectory under {@code root} (leaving {@code root} itself in place,
   * empty), retrying for up to {@link #TEMP_DIR_DRAIN_TIMEOUT} on the same Windows
   * just-terminated-process-still-holds-its-log-file race {@code MachineLauncher} itself tolerates
   * on the production side (see its own {@code awaitFileReleased}) -- this module's own launch
   * tests are exactly the case that races it hardest, spawning and killing several real processes
   * per test under a heavily parallel test run. JUnit's own {@code @TempDir} cleanup at test
   * teardown has no such patience (one attempt, no retry), so a test that itself spawns real
   * processes calls this explicitly before returning, leaving {@code @TempDir}'s own cleanup
   * nothing but an already-empty directory to remove.
   */
  static void drainTempDir(final Path root) {
    final long deadline = System.nanoTime() + TEMP_DIR_DRAIN_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        Files.walkFileTree(
            root,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                  throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
                  throws IOException {
                if (!dir.equals(root)) {
                  Files.deleteIfExists(dir);
                }
                return FileVisitResult.CONTINUE;
              }
            });
        return;
      } catch (final IOException e) {
        try {
          Thread.sleep(100);
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
    // Best-effort: leaves whatever is left for @TempDir's own cleanup to report, the same as if
    // this helper had never been called.
  }
}
