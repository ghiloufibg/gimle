package com.gimle.agent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code gimle-worker}'s real, jars-only launch classpath -- its own jar plus its {@code
 * runtime-image} profile's {@code lib/*.jar} -- shared by every {@code @Tag("aotbench")} test that
 * needs a JEP-483-eligible classpath (a directory anywhere on it disqualifies AOT caching, which is
 * exactly what a plain reactor build's {@code target/classes} would put there). Extracted out of
 * {@code WorkerStartupBenchIT} (Phase A) so {@code SleipnirTrainerTest}'s own real end-to-end
 * training test (Phase B) doesn't duplicate this resolution logic.
 */
final class RealWorkerClasspath {

  private RealWorkerClasspath() {}

  static Path libDir() {
    return targetDir().resolve("runtime-image").resolve("lib");
  }

  /**
   * {@code gimle-worker}'s own {@code target} directory, resolved sibling-relative to this module's
   * basedir (Maven's forked-test working directory) since both modules sit directly under the
   * reactor root. Override with {@code -Dgimle.sleipnir.workerTargetDir=<path>} if that assumption
   * doesn't hold for a given invocation.
   */
  static Path targetDir() {
    String override = System.getProperty("gimle.sleipnir.workerTargetDir");
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    return Path.of("..", "gimle-worker", "target").toAbsolutePath().normalize();
  }

  static String classpath() throws IOException {
    Path workerJar = resolveWorkerJar(targetDir());
    List<String> classpathEntries = new ArrayList<>();
    classpathEntries.add(workerJar.toString());
    classpathEntries.addAll(jarsIn(libDir()));
    return String.join(File.pathSeparator, classpathEntries);
  }

  private static Path resolveWorkerJar(Path targetDir) throws IOException {
    try (Stream<Path> files = Files.list(targetDir)) {
      return files
          .filter(p -> p.getFileName().toString().matches("gimle-worker-.*\\.jar"))
          .filter(p -> !p.getFileName().toString().contains("sources"))
          .filter(p -> !p.getFileName().toString().contains("javadoc"))
          .map(p -> p.toAbsolutePath().normalize())
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("no gimle-worker-*.jar found under " + targetDir));
    }
  }

  private static List<String> jarsIn(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(".jar"))
          .map(p -> p.toAbsolutePath().normalize().toString())
          .sorted()
          .toList();
    }
  }
}
