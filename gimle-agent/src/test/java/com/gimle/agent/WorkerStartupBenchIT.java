package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * Phase A of the Sleipnir startup-cache design: measures whether a JDK 25 AOT cache (JEP
 * 483/514/515) meaningfully speeds up a real {@code WorkerMain} boot, gated at &ge;25% p50 {@code
 * spawn->Hello} reduction, before any agent-managed trainer/cache production code exists. Lives in
 * this package (not {@code gimle-smoke-tests}) specifically to call {@link
 * AgentMain#buildWorkerCommand} directly -- reusing the real, already-unit-tested flag-building
 * logic rather than reimplementing it, which would risk exactly the flag-drift the design's own
 * risk table warns about.
 *
 * <p>Every spawn here uses a real jars-only classpath ({@code gimle-worker}'s own jar plus its
 * {@code runtime-image} profile's {@code lib/*.jar}) -- JEP 483's own eligibility constraint
 * disqualifies any directory on the classpath, which is exactly what a plain reactor build's {@code
 * target/classes} would put there. Skips via assumption (not failure) when that profile hasn't been
 * built, the same posture {@code SurtrIT} already established for its own workload prerequisite.
 */
@Tag("aotbench")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerStartupBenchIT {

  private static final int SPAWN_ITERATIONS = 30;
  private static final double REQUIRED_P50_REDUCTION = 0.25;
  private static final Duration SPAWN_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration TRAINING_TIMEOUT = Duration.ofSeconds(60);

  // The JDK's own Unified Logging tag for an -Xlog:aot=warning fallback line -- present only when
  // a supplied -XX:AOTCache= failed to apply and the JVM silently continued uncached. Confirmed
  // against a real JDK 25 run before relying on it; if a future JDK changes this wording the
  // symptom is this check going permanently green, not red, so re-verify it if the gate above
  // ever passes suspiciously easily.
  private static final Pattern AOT_FALLBACK_WARNING = Pattern.compile("(?i)\\[warning]\\[aot]");

  // Not @TempDir: that extension's instance-field injection isn't guaranteed to have run before
  // @BeforeAll fires on the shared PER_CLASS instance, and it didn't in practice on this repo's
  // pinned JUnit version -- created and torn down by hand instead.
  private Path tempDir;

  private String realJars;
  private Path trainedCache;
  private PortableJvmFlagsResourceLimiter resourceLimiter;
  private ResourceLimitHandle limitHandle;
  private AssignedInstance assigned;
  private Path workerLogRoot;

  @BeforeAll
  void trainCacheAgainstRealJars() throws Exception {
    tempDir = Files.createTempDirectory("gimle-sleipnir-bench-");
    assumeTrue(
        Files.isDirectory(RealWorkerClasspath.libDir()),
        "gimle-worker's runtime-image profile hasn't been built. Run:\n"
            + "  mvn -pl gimle-worker -am package -P runtime-image\n"
            + "then:\n"
            + "  mvn -pl gimle-agent -am verify -P aotbench");

    realJars = RealWorkerClasspath.classpath();
    workerLogRoot = tempDir.resolve("worker-logs");
    Files.createDirectories(workerLogRoot);

    resourceLimiter = new PortableJvmFlagsResourceLimiter();
    ModuleDescriptor descriptor =
        new ModuleDescriptor(
            "sleipnir-bench",
            Version.parse("1.0.0"),
            List.of(),
            List.of(),
            IsolationTier.TIER_2,
            new ResourceSpec("512Mi", "1000m"),
            new ResourceSpec("512Mi", "1000m"),
            HealthProbes.NONE,
            Optional.empty(),
            Optional.empty(),
            Map.of());
    limitHandle = AgentMain.prepareResourceLimit(resourceLimiter, "sleipnir-bench#0", descriptor);
    assigned =
        new AssignedInstance(
            "sleipnir-bench", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());

    trainedCache = tempDir.resolve("bench.aot");
    List<String> trainingCommand =
        buildCommand(
            List.of(
                "-XX:AOTCacheOutput=" + trainedCache.toAbsolutePath(),
                "-Dgimle.worker.aotTraining=true"));
    HelloTiming training = spawnAndAwaitHello(trainingCommand, TRAINING_TIMEOUT);
    boolean exited = training.process().waitFor(TRAINING_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(
        exited, "training worker did not exit within " + TRAINING_TIMEOUT + " of sending Hello");
    assertEquals(
        0,
        training.process().exitValue(),
        "training worker exited non-zero; output=\n" + String.join("\n", training.outputLines()));
    assertTrue(
        Files.exists(trainedCache) && Files.size(trainedCache) > 0,
        "AOT cache file was not produced at " + trainedCache);
  }

  @AfterAll
  void deleteTempDir() throws IOException {
    if (tempDir == null) {
      return;
    }
    try (Stream<Path> paths = Files.walk(tempDir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  void cached_spawns_reach_hello_at_least_25_percent_faster_at_p50_with_no_aot_fallback()
      throws Exception {
    List<Long> uncachedMillis = new ArrayList<>();
    for (int i = 0; i < SPAWN_ITERATIONS; i++) {
      uncachedMillis.add(measureSpawnToHelloMillis(buildCommand(List.of())));
    }

    List<Long> cachedMillis = new ArrayList<>();
    List<String> allCachedOutput = new ArrayList<>();
    for (int i = 0; i < SPAWN_ITERATIONS; i++) {
      List<String> cachedCommand =
          buildCommand(
              List.of(
                  "-XX:AOTCache=" + trainedCache.toAbsolutePath(),
                  "-XX:AOTMode=auto",
                  "-Xlog:aot=warning"));
      HelloTiming timing = spawnAndAwaitHello(cachedCommand, SPAWN_TIMEOUT);
      cachedMillis.add(millisBetween(timing.spawned(), timing.helloAt()));
      allCachedOutput.addAll(timing.outputLines());
      destroy(timing.process());
    }

    Percentiles uncached = Percentiles.of(uncachedMillis);
    Percentiles cached = Percentiles.of(cachedMillis);
    System.out.printf(
        "Sleipnir Phase A: uncached spawn->Hello p50=%dms p95=%dms; cached p50=%dms p95=%dms%n",
        uncached.p50(), uncached.p95(), cached.p50(), cached.p95());

    // Item 4 (JFR coexistence): the always-on leak-detection JFR flag is already unconditionally
    // present in every command via buildWorkerCommand -- an AOT fallback warning here would mean
    // either a bad cache or a real conflict between that flag and cache use, either way a signal
    // the cached runs above weren't actually measuring a populated cache.
    List<String> fallbackLines =
        allCachedOutput.stream().filter(line -> AOT_FALLBACK_WARNING.matcher(line).find()).toList();
    assertTrue(
        fallbackLines.isEmpty(),
        "cached runs fell back to uncached startup at least once (cache not actually used): "
            + fallbackLines);

    double requiredCachedP50 = uncached.p50() * (1.0 - REQUIRED_P50_REDUCTION);
    assertTrue(
        cached.p50() <= requiredCachedP50,
        "Phase A gate not met: cached p50="
            + cached.p50()
            + "ms, required <= "
            + requiredCachedP50
            + "ms (25% reduction from uncached p50="
            + uncached.p50()
            + "ms). Per the design, stop at Phase A and record why rather than proceeding.");
  }

  @Test
  @Timeout(value = 1, unit = TimeUnit.MINUTES)
  void a_truncated_aot_cache_still_yields_a_normal_worker_start() throws Exception {
    // Flip a byte in place on a copy of the trained cache -- same "corrupt on disk after a valid
    // write" pattern AndvariServerTest uses for its own on-disk-corruption tests. -XX:AOTMode=auto
    // is the JVM's own documented behavior being exercised here, not Gimlé code: a mismatched or
    // corrupt cache must silently degrade to a normal, uncached start.
    byte[] bytes = Files.readAllBytes(trainedCache);
    bytes[0] ^= (byte) 0xFF;
    Path corrupted = tempDir.resolve("corrupted.aot");
    Files.write(corrupted, bytes);

    List<String> command =
        buildCommand(
            List.of(
                "-XX:AOTCache=" + corrupted.toAbsolutePath(),
                "-XX:AOTMode=auto",
                "-Xlog:aot=warning"));
    HelloTiming timing = spawnAndAwaitHello(command, SPAWN_TIMEOUT);
    destroy(timing.process());

    assertTrue(
        timing.helloAt().isAfter(timing.spawned()),
        "expected a normal worker start even with a corrupted AOT cache file");
  }

  // --- command building -------------------------------------------------------------------------

  private List<String> buildCommand(List<String> aotFlags) {
    List<String> commandTail = new ArrayList<>(aotFlags);
    commandTail.add("-cp");
    commandTail.add(realJars);
    commandTail.add("com.gimle.worker.WorkerMain");
    return AgentMain.buildWorkerCommand(
        javaExecutable(),
        commandTail,
        resourceLimiter,
        limitHandle,
        workerLogRoot,
        "bench-node",
        assigned,
        Optional.empty());
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  // --- spawn + measure --------------------------------------------------------------------------

  private long measureSpawnToHelloMillis(List<String> command) throws Exception {
    HelloTiming timing = spawnAndAwaitHello(command, SPAWN_TIMEOUT);
    long millis = millisBetween(timing.spawned(), timing.helloAt());
    destroy(timing.process());
    return millis;
  }

  private static long millisBetween(Instant start, Instant end) {
    return Duration.between(start, end).toMillis();
  }

  private static void destroy(Process process) {
    process.destroyForcibly();
    try {
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private record HelloTiming(
      Instant spawned, Instant helloAt, List<String> outputLines, Process process) {}

  /**
   * Spawns {@code command} with a control-socket path appended as its final positional argument
   * (the piece {@link AgentMain#buildWorkerCommand} deliberately doesn't add -- in production
   * that's {@code WorkerProcessSupervisor}'s job), then blocks up to {@code timeout} for the
   * spawned worker to connect and send {@code Hello}. Does not destroy the process -- callers
   * decide whether to let it exit on its own (training) or kill it once measured (benchmarking).
   */
  private static HelloTiming spawnAndAwaitHello(List<String> command, Duration timeout)
      throws Exception {
    Path socketPath = Files.createTempDirectory("gimle-sleipnir-bench-uds-").resolve("c.sock");
    List<String> full = new ArrayList<>(command);
    full.add(socketPath.toString());

    try (ControlChannelServer server = new ControlChannelServer(socketPath)) {
      ProcessBuilder builder = new ProcessBuilder(full).redirectErrorStream(true);
      Instant spawned = Instant.now();
      Process process = builder.start();

      List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
      Thread.ofVirtual()
          .start(
              () -> {
                try (var reader = process.inputReader()) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    outputLines.add(line);
                  }
                } catch (IOException ignored) {
                  // Process destroyed mid-read (the common case once a measurement is captured) --
                  // nothing left worth capturing.
                }
              });

      CompletableFuture<Instant> helloFuture = new CompletableFuture<>();
      Thread.ofVirtual()
          .start(
              () -> {
                try (WorkerConnection connection = server.accept()) {
                  Optional<ControlMessage> received = connection.receive();
                  if (received.isPresent() && received.get() instanceof ControlMessage.Hello) {
                    helloFuture.complete(Instant.now());
                  } else {
                    helloFuture.completeExceptionally(
                        new IllegalStateException("expected Hello, got " + received));
                  }
                } catch (IOException e) {
                  helloFuture.completeExceptionally(new UncheckedIOException(e));
                }
              });

      try {
        Instant helloAt = helloFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return new HelloTiming(spawned, helloAt, outputLines, process);
      } catch (Exception e) {
        process.destroyForcibly();
        fail(
            "worker did not send Hello within "
                + timeout
                + "; output so far=\n"
                + String.join("\n", outputLines),
            e);
        throw new AssertionError("unreachable");
      }
    }
  }

  /**
   * Nearest-rank p50/p95, modeled on (not cross-module-dependent on) Holmgang's own Percentiles.
   */
  private record Percentiles(long p50, long p95) {
    static Percentiles of(List<Long> valuesMillis) {
      List<Long> sorted = new ArrayList<>(valuesMillis);
      Collections.sort(sorted);
      return new Percentiles(nearestRank(sorted, 0.50), nearestRank(sorted, 0.95));
    }

    private static long nearestRank(List<Long> sorted, double percentile) {
      int index = (int) Math.ceil(percentile * sorted.size()) - 1;
      index = Math.max(0, Math.min(index, sorted.size() - 1));
      return sorted.get(index);
    }
  }
}
