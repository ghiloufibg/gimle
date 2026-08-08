package com.gimle.module.leak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mandatory Phase 1 acceptance test: redeploy-in-a-loop with flat metaspace. Runs {@link
 * RedeployLoopDriver} as a subprocess with a fixed {@code -XX:MaxMetaspaceSize} so a genuine
 * classloader leak would exhaust it and crash the process, rather than just growing this test JVM's
 * own metaspace unboundedly across the run.
 */
class RedeployLoopFlatMetaspaceTest {

  private static final int ITERATIONS = 500;
  private static final int SAMPLE_EVERY = 25;

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void redeploy_loop_keeps_metaspace_flat() throws Exception {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.redeployloop {
                  exports com.gimle.fixture.redeployloop;
                }
                """)
            .withClass(
                "com.gimle.fixture.redeployloop.Marker",
                """
                package com.gimle.fixture.redeployloop;
                public class Marker {
                  public String value() { return "marker"; }
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.redeployloop", "1.0.0"))
            .build(tempDir, "redeployloop.jar");

    // Exercised once here so a broken fixture fails fast with a clear message, before spending
    // the subprocess's time budget on it.
    ModuleId id = ModuleArtifactReader.read(jar).id();
    assertEquals("com.gimle.fixture.redeployloop", id.name());

    String javaExecutable = javaExecutable();
    String classpath = buildClasspath();

    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-XX:MaxMetaspaceSize=96m",
            "-cp",
            classpath,
            RedeployLoopDriver.class.getName(),
            jar.toAbsolutePath().toString(),
            Integer.toString(ITERATIONS),
            Integer.toString(SAMPLE_EVERY));
    pb.redirectErrorStream(true);
    Process process = pb.start();

    List<long[]> samples = new ArrayList<>(); // [iteration, bytesUsed]
    boolean sawDone = false;
    List<String> allLines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        allLines.add(line);
        if (line.startsWith("SAMPLE ")) {
          String[] parts = line.split(" ");
          samples.add(new long[] {Long.parseLong(parts[1]), Long.parseLong(parts[2])});
        } else if (line.equals("DONE")) {
          sawDone = true;
        }
      }
    }

    boolean finished = process.waitFor(2, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
      fail("redeploy-loop subprocess did not finish in time");
    }
    int exitCode = process.exitValue();

    if (exitCode != 0 || !sawDone) {
      fail(
          "redeploy-loop subprocess failed (exit="
              + exitCode
              + ", sawDone="
              + sawDone
              + "); output:\n"
              + String.join("\n", allLines));
    }

    assertTrue(samples.size() >= 4, "expected several metaspace samples, got " + samples.size());
    assertPlateaus(samples);
  }

  /**
   * Compares the min/max of the samples' second half against a generous ratio — a genuine per-cycle
   * metadata leak keeps climbing throughout that window, while healthy behavior settles into a
   * narrow band after initial classloading/JIT warm-up.
   */
  private static void assertPlateaus(List<long[]> samples) {
    int half = samples.size() / 2;
    List<long[]> latterHalf = samples.subList(half, samples.size());
    long min = latterHalf.stream().mapToLong(s -> s[1]).min().orElseThrow();
    long max = latterHalf.stream().mapToLong(s -> s[1]).max().orElseThrow();
    long slackBytes = 4L * 1024 * 1024; // 4 MiB absolute slack for GC/JIT noise at a small baseline
    assertTrue(
        max <= (long) (min * 1.5) + slackBytes,
        "metaspace usage did not plateau across the redeploy loop: min="
            + min
            + " max="
            + max
            + " over samples "
            + latterHalf.stream().map(s -> s[0] + "->" + s[1]).toList());
  }

  /**
   * The path to the {@code java} launcher that started this JVM — asked of the runtime itself
   * ({@link ProcessHandle}) rather than guessed from {@code os.name}, so there's no platform-name
   * string matching to get wrong or to need updating for a platform the check didn't anticipate.
   * Falls back to probing both conventional launcher filenames against the filesystem (still no OS
   * check — just "which of these files exists") for the rare environment where {@link
   * ProcessHandle.Info#command()} isn't populated.
   */
  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }

  private static String buildClasspath() throws IOException {
    List<Path> entries =
        List.of(
            modulePathEntryOf(com.gimle.core.module.ModuleId.class),
            modulePathEntryOf(com.gimle.module.lifecycle.ModuleController.class),
            modulePathEntryOf(RedeployLoopDriver.class),
            modulePathEntryOf(org.yaml.snakeyaml.Yaml.class),
            // ModuleController logs every lifecycle transition (a real fix, not incidental --
            // TransitionFailed's cause used to be dropped even from this worker's own log); slf4j
            // API is now a real class-init-time dependency of a class this subprocess loads, not
            // just a compile-time one satisfied by gimle-core's own logging setup elsewhere.
            modulePathEntryOf(org.slf4j.Logger.class));
    StringBuilder cp = new StringBuilder();
    for (Path entry : entries) {
      if (cp.length() > 0) {
        cp.append(java.io.File.pathSeparator);
      }
      cp.append(entry.toAbsolutePath());
    }
    return cp.toString();
  }

  private static Path modulePathEntryOf(Class<?> anchor) {
    try {
      return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
