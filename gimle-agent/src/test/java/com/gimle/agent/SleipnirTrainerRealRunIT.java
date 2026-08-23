package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one {@link SleipnirTrainer} test that must spawn a real training JVM: every other case in
 * {@link SleipnirTrainerTest} runs against an injectable fake. Reuses {@link RealWorkerClasspath}
 * (extracted from {@code WorkerStartupBenchIT}, Phase A) for a genuine, JEP-483-eligible jars-only
 * classpath, so this must be a separate {@code *IT} class rather than a {@code @Tag} on a method
 * inside {@code SleipnirTrainerTest} -- {@code maven-failsafe-plugin} only picks up {@code *IT}
 * classes by naming convention, and {@code SleipnirTrainerTest}'s own {@code excludedGroups} config
 * would otherwise mean this case never runs under any invocation.
 */
@Tag("aotbench")
class SleipnirTrainerRealRunIT {

  private static final String JAVA_EXECUTABLE =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();

  @TempDir Path tempDir;

  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void a_real_training_run_against_the_real_worker_classpath_produces_a_genuine_cache_pair()
      throws Exception {
    assumeTrue(
        Files.isDirectory(RealWorkerClasspath.libDir()),
        "gimle-worker's runtime-image profile hasn't been built. Run:\n"
            + "  mvn -pl gimle-worker -am package -P runtime-image\n"
            + "then:\n"
            + "  mvn -pl gimle-agent -am verify -P aotbench");

    SleipnirCache cache =
        new SleipnirCache(tempDir.resolve("aot-cache"), new ConcurrentHashMap<>(), JAVA_EXECUTABLE);
    SleipnirTrainer trainer = new SleipnirTrainer(JAVA_EXECUTABLE, cache);
    List<String> commandTail =
        List.of("-cp", RealWorkerClasspath.classpath(), "com.gimle.worker.WorkerMain");

    trainer.trainIfNeeded(commandTail);

    Optional<Path> cachedPath = cache.cacheFor(commandTail);
    assertTrue(cachedPath.isPresent(), "expected a genuine trained AOT cache file on disk");
    assertTrue(Files.size(cachedPath.get()) > 0);
  }
}
