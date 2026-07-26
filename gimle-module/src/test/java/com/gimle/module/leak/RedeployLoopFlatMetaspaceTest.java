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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
            .with_class(
                "com.gimle.fixture.redeployloop.Marker",
                """
                package com.gimle.fixture.redeployloop;
                public class Marker {
                  public String value() { return "marker"; }
                }
                """)
            .with_descriptor(
                TestModuleBuilder.minimal_descriptor("com.gimle.fixture.redeployloop", "1.0.0"))
            .build(tempDir, "redeployloop.jar");

    // Exercised once here so a broken fixture fails fast with a clear message, before spending
    // the subprocess's time budget on it.
    ModuleId id = ModuleArtifactReader.read(jar).id();
    assertEquals("com.gimle.fixture.redeployloop", id.name());

    String javaExecutable = java_executable();
    String classpath = build_classpath();

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
    assert_plateaus(samples);
  }

  /**
   * Compares the min/max of the samples' second half against a generous ratio — a genuine per-cycle
   * metadata leak keeps climbing throughout that window, while healthy behavior settles into a
   * narrow band after initial classloading/JIT warm-up.
   */
  private static void assert_plateaus(List<long[]> samples) {
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

  private static String java_executable() {
    String exeName =
        System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    return Path.of(System.getProperty("java.home"), "bin", exeName).toString();
  }

  private static String build_classpath() throws IOException {
    List<Path> entries =
        List.of(
            module_path_entry_of(com.gimle.core.module.ModuleId.class),
            module_path_entry_of(com.gimle.module.lifecycle.ModuleController.class),
            module_path_entry_of(RedeployLoopDriver.class),
            module_path_entry_of(org.yaml.snakeyaml.Yaml.class));
    StringBuilder cp = new StringBuilder();
    for (Path entry : entries) {
      if (cp.length() > 0) {
        cp.append(java.io.File.pathSeparator);
      }
      cp.append(entry.toAbsolutePath());
    }
    return cp.toString();
  }

  private static Path module_path_entry_of(Class<?> anchor) {
    try {
      return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
