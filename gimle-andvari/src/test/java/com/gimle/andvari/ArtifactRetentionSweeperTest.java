package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link ArtifactRetentionSweeper#sweep()} directly rather than waiting on its own
 * scheduler -- the scheduler is just a {@code scheduleAtFixedRate} wrapper around this same method,
 * the same posture {@code RetentionSweeperTest} and {@code IntegrityScrubberTest} already take
 * toward their own sweepers. The constructor's own initial (delay-0) scheduled run and this test's
 * direct {@link ArtifactRetentionSweeper#sweep} call can genuinely race: both may read the store's
 * pre-deletion state independently and each decide the same version qualifies for retirement before
 * either's handler call has actually deleted it, so a version can legitimately be reported more
 * than once. Results are therefore collected into a version-to-reason map (a duplicate report of
 * the same version overwrites with the same, deterministically-computed reason) and asserted by
 * content, never by an exact call count -- the same tolerance {@code IntegrityScrubberTest} already
 * takes toward its own scheduler race.
 */
class ArtifactRetentionSweeperTest {

  @TempDir Path tempDir;

  private ArtifactStore store;

  @BeforeEach
  void setUp() throws Exception {
    store = new ArtifactStore(tempDir);
  }

  @Test
  void retires_the_oldest_versions_once_a_module_exceeds_the_configured_count() throws Exception {
    pushAtTime("com.example.app", "1.0.0", 1_000);
    pushAtTime("com.example.app", "2.0.0", 2_000);
    pushAtTime("com.example.app", "3.0.0", 3_000);
    Map<String, String> retired = new ConcurrentHashMap<>();

    try (ArtifactRetentionSweeper sweeper =
        new ArtifactRetentionSweeper(
            store, retiringHandler(retired), 2, null, Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertEquals(Map.of("1.0.0", "count"), retired);
    assertEquals(2, store.versions("com.example.app").size());
  }

  @Test
  void retires_versions_older_than_the_configured_age() throws Exception {
    long now = System.currentTimeMillis();
    pushAtTime("com.example.app", "1.0.0", now - Duration.ofDays(100).toMillis());
    pushAtTime("com.example.app", "2.0.0", now);
    Map<String, String> retired = new ConcurrentHashMap<>();

    try (ArtifactRetentionSweeper sweeper =
        new ArtifactRetentionSweeper(
            store, retiringHandler(retired), 0, Duration.ofDays(90), Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertEquals(Map.of("1.0.0", "age"), retired);
  }

  @Test
  void a_version_over_both_limits_is_reported_once_with_a_combined_reason() throws Exception {
    long now = System.currentTimeMillis();
    pushAtTime("com.example.app", "1.0.0", now - Duration.ofDays(100).toMillis());
    pushAtTime("com.example.app", "2.0.0", now);
    Map<String, String> retired = new ConcurrentHashMap<>();

    try (ArtifactRetentionSweeper sweeper =
        new ArtifactRetentionSweeper(
            store, retiringHandler(retired), 1, Duration.ofDays(90), Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertEquals(Map.of("1.0.0", "count+age"), retired);
  }

  @Test
  void neither_policy_configured_retires_nothing() throws Exception {
    pushAtTime("com.example.app", "1.0.0", 1_000);
    Map<String, String> retired = new ConcurrentHashMap<>();

    try (ArtifactRetentionSweeper sweeper =
        new ArtifactRetentionSweeper(
            store, retiringHandler(retired), 0, null, Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertTrue(retired.isEmpty());
  }

  @Test
  void a_module_at_or_under_the_count_limit_is_left_alone() throws Exception {
    pushAtTime("com.example.app", "1.0.0", 1_000);
    pushAtTime("com.example.app", "2.0.0", 2_000);
    Map<String, String> retired = new ConcurrentHashMap<>();

    try (ArtifactRetentionSweeper sweeper =
        new ArtifactRetentionSweeper(
            store, retiringHandler(retired), 2, null, Duration.ofDays(1))) {
      sweeper.sweep();
    }

    assertTrue(retired.isEmpty());
    assertEquals(2, store.versions("com.example.app").size());
  }

  private ArtifactRetentionSweeper.RetiredVersionHandler retiringHandler(
      Map<String, String> retired) {
    return (moduleId, version, reason) -> {
      retired.put(version, reason);
      try {
        store.delete(moduleId, version);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  /**
   * {@link ArtifactStore#put} always stamps {@code pushedAtEpochMilli} with {@code
   * System.currentTimeMillis()}, so these tests write {@code meta.json} directly to control that
   * field precisely -- the only way to deterministically test "oldest by push time" without
   * sleeping between pushes.
   */
  private void pushAtTime(String moduleId, String version, long pushedAtEpochMilli)
      throws Exception {
    store.put(
        moduleId, version, new ByteArrayInputStream("v".getBytes(StandardCharsets.UTF_8)), "ana");
    Path metaFile =
        tempDir.resolve("artifacts").resolve(moduleId).resolve(version).resolve("meta.json");
    String rewritten =
        Files.readString(metaFile)
            .replaceAll(
                "\"pushedAtEpochMilli\":\\d+", "\"pushedAtEpochMilli\":" + pushedAtEpochMilli);
    Files.writeString(metaFile, rewritten);
  }
}
