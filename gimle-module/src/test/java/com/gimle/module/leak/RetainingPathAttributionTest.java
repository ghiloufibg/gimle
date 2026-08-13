package com.gimle.module.leak;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleId;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.testsupport.SubprocessTestSupport;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.BufferedReader;
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
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * F-01 acceptance test: {@code OldObjectSampleCorrelator} can only surface a retaining path when
 * the JVM it runs in was launched with {@code jdk.OldObjectSample#path-to-gc-roots=true} -- a
 * recording-launch option, not something the correlator can enable on itself in-process. Runs
 * {@link RetainingPathDriver} as a real subprocess with that flag, the same "real subprocess, not
 * mocked JFR" pattern {@code RedeployLoopFlatMetaspaceTest} establishes for metaspace behavior.
 */
class RetainingPathAttributionTest {

  private static final long ATTEMPT_BUDGET_MILLIS = 15_000;

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void leak_detector_surfaces_a_retaining_path_when_the_worker_jvm_enables_path_to_gc_roots()
      throws Exception {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.retaining {
                  exports com.gimle.fixture.retaining;
                }
                """)
            .withClass(
                "com.gimle.fixture.retaining.Payload",
                """
                package com.gimle.fixture.retaining;
                public final class Payload {
                  @SuppressWarnings("unused")
                  private final byte[] data = new byte[8192];
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.retaining", "1.0.0"))
            .build(tempDir, "retaining.jar");

    String javaExecutable = SubprocessTestSupport.javaExecutable();
    String classpath =
        SubprocessTestSupport.buildClasspath(
            List.of(
                ModuleId.class,
                ModuleController.class,
                RetainingPathDriver.class,
                Yaml.class,
                // See RedeployLoopFlatMetaspaceTest's identical entry: ModuleController now logs
                // every lifecycle transition, making slf4j API a real class-init-time dependency.
                Logger.class));

    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-XX:StartFlightRecording:name=gimle-leak-detection,disk=false,"
                + "settings=profile,path-to-gc-roots=true",
            "-cp",
            classpath,
            RetainingPathDriver.class.getName(),
            jar.toAbsolutePath().toString(),
            Long.toString(ATTEMPT_BUDGET_MILLIS));
    pb.redirectErrorStream(true);
    Process process = pb.start();

    List<String> allLines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        allLines.add(line);
      }
    }

    boolean finished = process.waitFor(150, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      fail("retaining-path driver subprocess did not finish in time");
    }
    int exitCode = process.exitValue();
    if (exitCode != 0) {
      fail(
          "retaining-path driver subprocess exited "
              + exitCode
              + "; output:\n"
              + String.join("\n", allLines));
    }

    assertTrue(
        allLines.stream().anyMatch(l -> l.startsWith("RETAINING_PATH_PRESENT")),
        "expected a non-empty retaining path when the worker JVM enables path-to-gc-roots; output:\n"
            + String.join("\n", allLines));
  }
}
