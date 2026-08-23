package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SleipnirCache} driven directly -- no real training JVM spawn needed for any of this, since
 * key computation only reads {@code <javaExecutable> -version} (fast) and stats/hashes whatever
 * classpath jars a test hands it. {@link SleipnirTrainerTest} covers the training orchestration
 * this class's {@code cacheFor}/{@code commit}/{@code sweep} sit underneath.
 */
class SleipnirCacheTest {

  private static final String JAVA_EXECUTABLE =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();

  @TempDir Path cacheRoot;

  private Path fakeJar(String name, String content) throws IOException {
    Path jar = cacheRoot.resolve(name);
    Files.writeString(jar, content);
    return jar;
  }

  private SleipnirCache cache(Map<String, SupervisedInstance> supervised) {
    return new SleipnirCache(cacheRoot, supervised, JAVA_EXECUTABLE);
  }

  private SleipnirCache cache() {
    return cache(new ConcurrentHashMap<>());
  }

  @Test
  void the_key_is_deterministic_for_the_same_classpath_and_flags() throws IOException {
    Path jar = fakeJar("worker.jar", "fake-worker-bytes");
    List<String> commandTail = List.of("-cp", jar.toString(), "com.gimle.worker.WorkerMain");

    Optional<String> keyA = cache().keyFor(commandTail);
    Optional<String> keyB = cache().keyFor(commandTail);

    assertTrue(keyA.isPresent());
    assertEquals(keyA, keyB);
  }

  @Test
  void a_different_classpath_jars_bytes_change_the_key() throws IOException {
    Path jarA = fakeJar("worker-a.jar", "version-one");
    Path jarB = fakeJar("worker-b.jar", "version-two-with-different-length");

    Optional<String> keyA =
        cache().keyFor(List.of("-cp", jarA.toString(), "com.gimle.worker.WorkerMain"));
    Optional<String> keyB =
        cache().keyFor(List.of("-cp", jarB.toString(), "com.gimle.worker.WorkerMain"));

    assertNotEquals(keyA, keyB);
  }

  @Test
  void an_unrelated_system_property_does_not_change_the_key() throws IOException {
    Path jar = fakeJar("worker.jar", "fake-worker-bytes");
    List<String> commandTail = List.of("-cp", jar.toString(), "com.gimle.worker.WorkerMain");
    String property = "gimle.sleipnir.test.unrelated";
    String previous = System.getProperty(property);
    try {
      System.clearProperty(property);
      Optional<String> before = cache().keyFor(commandTail);
      System.setProperty(property, "some-value-that-should-never-matter");
      Optional<String> after = cache().keyFor(commandTail);
      assertEquals(before, after);
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void a_directory_on_the_classpath_makes_the_key_empty() {
    List<String> commandTail =
        List.of(
            "-cp", cacheRoot.toString(), "com.gimle.worker.WorkerMain"); // a directory, not a jar
    assertFalse(cache().keyFor(commandTail).isPresent());
  }

  @Test
  void cache_for_is_empty_when_nothing_has_been_committed() throws IOException {
    Path jar = fakeJar("worker.jar", "fake-worker-bytes");
    List<String> commandTail = List.of("-cp", jar.toString(), "com.gimle.worker.WorkerMain");
    assertFalse(cache().cacheFor(commandTail).isPresent());
  }

  @Test
  void cache_for_treats_a_bare_aot_with_no_meta_json_as_a_miss() throws IOException {
    Path jar = fakeJar("worker.jar", "fake-worker-bytes");
    List<String> commandTail = List.of("-cp", jar.toString(), "com.gimle.worker.WorkerMain");
    SleipnirCache cache = cache();
    String key = cache.keyFor(commandTail).orElseThrow();

    Files.writeString(cacheRoot.resolve("worker-" + key + ".aot"), "incomplete-write");

    assertFalse(
        cache.cacheFor(commandTail).isPresent(),
        "a .aot with no matching .meta.json must be treated as an incomplete write, not a hit");
  }

  @Test
  void commit_makes_both_files_present_and_cache_for_then_returns_a_hit() throws IOException {
    Path jar = fakeJar("worker.jar", "fake-worker-bytes");
    List<String> commandTail = List.of("-cp", jar.toString(), "com.gimle.worker.WorkerMain");
    SleipnirCache cache = cache();
    String key = cache.keyFor(commandTail).orElseThrow();

    Path tmp = cache.newTrainingOutputPath(key);
    Files.writeString(tmp, "trained-aot-bytes");
    cache.commit(key, tmp);

    assertTrue(Files.isRegularFile(cacheRoot.resolve("worker-" + key + ".aot")));
    assertTrue(Files.isRegularFile(cacheRoot.resolve("worker-" + key + ".meta.json")));
    Optional<Path> hit = cache.cacheFor(commandTail);
    assertTrue(hit.isPresent());
    assertEquals(cacheRoot.resolve("worker-" + key + ".aot"), hit.get());
  }

  @Test
  void sweep_preserves_the_current_key_and_any_key_still_referenced_by_a_live_worker()
      throws IOException {
    Path jar = fakeJar("worker.jar", "fake-worker-bytes");
    List<String> commandTail = List.of("-cp", jar.toString(), "com.gimle.worker.WorkerMain");
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();
    SleipnirCache cache = cache(supervised);
    String currentKey = cache.keyFor(commandTail).orElseThrow();

    writePair(currentKey);
    writePair("stale-key-from-a-prior-jar-version");
    writePair("live-key-referenced-by-a-running-worker");
    Path orphanedTmp = cacheRoot.resolve("worker-abandoned.aot.tmp-1234");
    Files.writeString(orphanedTmp, "never finished training");

    supervised.put(
        "dep#0", supervisedInstanceWithAotCacheKey("live-key-referenced-by-a-running-worker"));

    cache.sweep();

    assertTrue(Files.exists(cacheRoot.resolve("worker-" + currentKey + ".aot")));
    assertTrue(Files.exists(cacheRoot.resolve("worker-" + currentKey + ".meta.json")));
    assertTrue(
        Files.exists(cacheRoot.resolve("worker-live-key-referenced-by-a-running-worker.aot")));
    assertTrue(
        Files.exists(
            cacheRoot.resolve("worker-live-key-referenced-by-a-running-worker.meta.json")));
    assertFalse(Files.exists(cacheRoot.resolve("worker-stale-key-from-a-prior-jar-version.aot")));
    assertFalse(
        Files.exists(cacheRoot.resolve("worker-stale-key-from-a-prior-jar-version.meta.json")));
    assertFalse(Files.exists(orphanedTmp));
  }

  private void writePair(String key) throws IOException {
    Files.writeString(cacheRoot.resolve("worker-" + key + ".aot"), "aot-bytes-" + key);
    Files.writeString(
        cacheRoot.resolve("worker-" + key + ".meta.json"), "{\"key\":\"" + key + "\"}");
  }

  private static SupervisedInstance supervisedInstanceWithAotCacheKey(String key) {
    ModuleDescriptor descriptor =
        new ModuleDescriptor(
            "hello-module",
            Version.parse("1.0.0"),
            List.of(),
            List.of(),
            IsolationTier.TIER_1,
            new ResourceSpec("16Mi", "500m"),
            new ResourceSpec("16Mi", "500m"),
            HealthProbes.NONE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    AssignedInstance assigned =
        new AssignedInstance(
            "hello-deployment", 0, descriptor.id(), "/does/not/matter.jar", Optional.empty());
    SupervisedInstance instance = new SupervisedInstance(assigned, null, null, descriptor);
    instance.aotCacheKey = Optional.of(key);
    return instance;
  }
}
