package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link IntegrityScrubber#scrub()} directly rather than waiting on its own scheduler --
 * the scheduler is just a {@code scheduleAtFixedRate} wrapper around this same method, the same
 * posture {@code RetentionSweeperTest} already takes toward its own sweeper. The scrub interval is
 * kept long enough that the constructor's own initial (delay-0) scheduled run and this test's
 * direct call may race harmlessly -- {@link #scrub} is idempotent, so a mismatch is reported at
 * least once either way, which is all these tests assert on.
 */
class IntegrityScrubberTest {

  @TempDir Path tempDir;

  @Test
  void a_coordinate_whose_bytes_no_longer_match_its_recorded_digest_is_reported() throws Exception {
    ArtifactStore store = new ArtifactStore(tempDir);
    store.put("com.example.app", "1.0.0", bytes("original-bytes"), "ana");
    Path jarFile = tempDir.resolve("artifacts/com.example.app/1.0.0/artifact.jar");
    Files.writeString(jarFile, "corrupted-bytes");
    Set<String> mismatches = ConcurrentHashMap.newKeySet();

    try (IntegrityScrubber scrubber =
        new IntegrityScrubber(
            store,
            (moduleId, version, expected, actual) -> mismatches.add(moduleId + ":" + version),
            Duration.ofDays(1))) {
      int checked = scrubber.scrub();
      assertEquals(1, checked);
    }

    assertTrue(mismatches.contains("com.example.app:1.0.0"));
  }

  @Test
  void an_uncorrupted_coordinate_is_never_reported() throws Exception {
    ArtifactStore store = new ArtifactStore(tempDir);
    store.put("com.example.app", "1.0.0", bytes("untouched-bytes"), "ana");
    Set<String> mismatches = ConcurrentHashMap.newKeySet();

    try (IntegrityScrubber scrubber =
        new IntegrityScrubber(
            store,
            (moduleId, version, expected, actual) -> mismatches.add(moduleId + ":" + version),
            Duration.ofDays(1))) {
      scrubber.scrub();
    }

    assertTrue(mismatches.isEmpty());
  }

  @Test
  void a_version_missing_its_jar_is_skipped_rather_than_reported_as_corrupted() throws Exception {
    ArtifactStore store = new ArtifactStore(tempDir);
    store.put("com.example.app", "1.0.0", bytes("v1"), "ana");
    Path jarFile = tempDir.resolve("artifacts/com.example.app/1.0.0/artifact.jar");
    Files.delete(jarFile);
    Set<String> mismatches = ConcurrentHashMap.newKeySet();

    try (IntegrityScrubber scrubber =
        new IntegrityScrubber(
            store,
            (moduleId, version, expected, actual) -> mismatches.add(moduleId + ":" + version),
            Duration.ofDays(1))) {
      scrubber.scrub();
    }

    assertTrue(mismatches.isEmpty());
  }

  private static ByteArrayInputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }
}
