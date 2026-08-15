package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.andvari.ArtifactStore.PutOutcome;
import com.gimle.andvari.ArtifactStore.PutResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store's own contract in isolation from any HTTP surface: content-addressed immutability
 * (identical re-push idempotent, differing re-push refused, stored bytes never overwritten),
 * streamed digesting, catalog listing, and the path-segment allow-list.
 */
class ArtifactStoreTest {

  @TempDir Path tempDir;

  private ArtifactStore store;

  @BeforeEach
  void setUp() throws Exception {
    store = new ArtifactStore(tempDir);
  }

  @Test
  void a_push_stores_the_bytes_and_computes_their_sha256() throws Exception {
    byte[] jar = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);

    PutResult result = store.put("com.example.app", "1.0.0", new ByteArrayInputStream(jar), "ana");

    assertEquals(PutOutcome.CREATED, result.outcome());
    assertEquals(sha256Of(jar), result.stored().sha256());
    assertEquals(jar.length, result.stored().sizeBytes());
    assertEquals("ana", result.stored().pushedBy());
    Path stored = store.jarPath("com.example.app", "1.0.0").orElseThrow();
    assertArrayEquals(jar, Files.readAllBytes(stored));
  }

  @Test
  void an_identical_re_push_is_idempotent() throws Exception {
    byte[] jar = "same-bytes".getBytes(StandardCharsets.UTF_8);
    store.put("com.example.app", "1.0.0", new ByteArrayInputStream(jar), "ana");

    PutResult second = store.put("com.example.app", "1.0.0", new ByteArrayInputStream(jar), "ben");

    assertEquals(PutOutcome.IDENTICAL, second.outcome());
    // The original push's provenance survives -- an idempotent re-push writes nothing.
    assertEquals("ana", second.stored().pushedBy());
  }

  @Test
  void a_differing_re_push_is_a_conflict_and_the_stored_bytes_are_untouched() throws Exception {
    byte[] original = "original-bytes".getBytes(StandardCharsets.UTF_8);
    byte[] tampered = "tampered-bytes".getBytes(StandardCharsets.UTF_8);
    store.put("com.example.app", "1.0.0", new ByteArrayInputStream(original), "ana");

    PutResult conflict =
        store.put("com.example.app", "1.0.0", new ByteArrayInputStream(tampered), "eve");

    assertEquals(PutOutcome.CONFLICT, conflict.outcome());
    assertEquals(sha256Of(original), conflict.stored().sha256());
    Path stored = store.jarPath("com.example.app", "1.0.0").orElseThrow();
    assertArrayEquals(original, Files.readAllBytes(stored));
  }

  @Test
  void versions_and_module_ids_list_sorted() throws Exception {
    store.put("com.zeta.app", "2.0.0", bytes("z2"), "ana");
    store.put("com.zeta.app", "1.0.0", bytes("z1"), "ana");
    store.put("com.alpha.app", "1.0.0", bytes("a1"), "ana");

    assertEquals(List.of("com.alpha.app", "com.zeta.app"), store.moduleIds());
    assertEquals(
        List.of("1.0.0", "2.0.0"),
        store.versions("com.zeta.app").stream()
            .map(ArtifactStore.StoredArtifact::version)
            .toList());
  }

  @Test
  void versions_sort_semver_aware_not_lexicographically() throws Exception {
    // Plain string order would put "1.10.0" before "1.2.0" (character '1' < '2'), which would
    // silently misreport "1.2.0" as the latest/release version in the generated Maven metadata.
    store.put("com.example.app", "1.2.0", bytes("v1.2.0"), "ana");
    store.put("com.example.app", "1.10.0", bytes("v1.10.0"), "ana");
    store.put("com.example.app", "1.9.0", bytes("v1.9.0"), "ana");

    assertEquals(
        List.of("1.2.0", "1.9.0", "1.10.0"),
        store.versions("com.example.app").stream()
            .map(ArtifactStore.StoredArtifact::version)
            .toList());
  }

  @Test
  void a_non_semver_version_string_still_sorts_deterministically() throws Exception {
    // requireValidSegment allow-lists path-safe characters, not semver shape (see ArtifactStore's
    // own SEGMENT pattern) -- a coordinate like a bare build number must not throw or hide other
    // versions just because Version.parse rejects it.
    store.put("com.example.app", "build-42", bytes("b42"), "ci");
    store.put("com.example.app", "1.0.0", bytes("v1"), "ci");

    assertEquals(
        List.of("1.0.0", "build-42"),
        store.versions("com.example.app").stream()
            .map(ArtifactStore.StoredArtifact::version)
            .toList());
  }

  @Test
  void delete_removes_the_version_and_an_emptied_module_leaves_the_catalog() throws Exception {
    store.put("com.example.app", "1.0.0", bytes("v1"), "ana");

    assertTrue(store.delete("com.example.app", "1.0.0"));

    assertTrue(store.meta("com.example.app", "1.0.0").isEmpty());
    assertTrue(store.jarPath("com.example.app", "1.0.0").isEmpty());
    assertEquals(List.of(), store.moduleIds());
    assertFalse(store.delete("com.example.app", "1.0.0"));
  }

  @Test
  void a_sidecar_round_trips_alongside_its_version() throws Exception {
    store.put("com.example.app", "1.0.0", bytes("v1"), "ana");

    store.putSidecar("com.example.app", "1.0.0", "app-1.0.0.pom", bytes("<project/>"));

    byte[] pom = store.sidecar("com.example.app", "1.0.0", "app-1.0.0.pom").orElseThrow();
    assertEquals("<project/>", new String(pom, StandardCharsets.UTF_8));
    assertTrue(store.sidecar("com.example.app", "1.0.0", "no-such-file").isEmpty());
  }

  @Test
  void delete_also_removes_sidecars_and_leaves_no_orphaned_directory() throws Exception {
    store.put("com.example.app", "1.0.0", bytes("v1"), "ana");
    store.putSidecar("com.example.app", "1.0.0", "app-1.0.0.pom", bytes("<project/>"));

    assertTrue(store.delete("com.example.app", "1.0.0"));

    assertTrue(store.sidecar("com.example.app", "1.0.0", "app-1.0.0.pom").isEmpty());
    assertEquals(List.of(), store.moduleIds());
    assertFalse(Files.exists(tempDir.resolve("artifacts").resolve("com.example.app")));
  }

  @Test
  void path_traversal_and_malformed_segments_are_rejected() {
    for (String bad : new String[] {"..", "../escape", "a/b", "a\\b", "", ".hidden", "-flag"}) {
      assertThrows(IllegalArgumentException.class, () -> store.put("ok", bad, bytes("x"), "ana"));
      assertThrows(IllegalArgumentException.class, () -> store.put(bad, "1.0.0", bytes("x"), "a"));
      assertThrows(IllegalArgumentException.class, () -> store.meta(bad, "1.0.0"));
    }
  }

  @Test
  void construction_sweeps_orphaned_temp_files_left_by_a_previous_run() throws Exception {
    // A file already sitting in tmp/ before the store is even constructed can only be an orphan
    // from a crashed prior process (put/putSidecar's own finally-block cleanup never runs for a
    // hard kill mid-upload) -- never a live upload, since this JVM hasn't issued one yet.
    Path standaloneRoot = tempDir.resolve("standalone");
    Path tmpDir = standaloneRoot.resolve("tmp");
    Files.createDirectories(tmpDir);
    Path orphan = tmpDir.resolve("upload-orphaned.jar");
    Files.writeString(orphan, "leftover from a killed upload");

    new ArtifactStore(standaloneRoot);

    assertFalse(Files.exists(orphan));
  }

  @Test
  void a_multi_megabyte_body_streams_through_with_a_correct_digest() throws Exception {
    byte[] jar = new byte[3 * 1024 * 1024];
    new Random(42).nextBytes(jar);

    PutResult result = store.put("com.example.big", "1.0.0", new ByteArrayInputStream(jar), "ana");

    assertEquals(PutOutcome.CREATED, result.outcome());
    assertEquals(sha256Of(jar), result.stored().sha256());
    assertEquals(jar.length, result.stored().sizeBytes());
  }

  @Test
  void copy_and_digest_streams_the_bytes_and_returns_their_sha256() throws Exception {
    byte[] jar = "pretend-jar-bytes".getBytes(StandardCharsets.UTF_8);
    store.put("com.example.app", "1.0.0", new ByteArrayInputStream(jar), "ana");
    Path stored = store.jarPath("com.example.app", "1.0.0").orElseThrow();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    String digest = ArtifactStore.copyAndDigest(stored, out);

    assertEquals(sha256Of(jar), digest);
    assertArrayEquals(jar, out.toByteArray());
  }

  @Test
  void quarantine_removes_the_version_from_the_live_catalog_and_empties_the_module_dir()
      throws Exception {
    store.put("com.example.app", "1.0.0", bytes("v1"), "ana");

    assertTrue(store.quarantine("com.example.app", "1.0.0"));

    assertTrue(store.meta("com.example.app", "1.0.0").isEmpty());
    assertTrue(store.jarPath("com.example.app", "1.0.0").isEmpty());
    assertEquals(List.of(), store.moduleIds());
    assertFalse(Files.exists(tempDir.resolve("artifacts").resolve("com.example.app")));
  }

  @Test
  void quarantine_preserves_the_corrupted_bytes_for_forensic_inspection() throws Exception {
    store.put("com.example.app", "1.0.0", bytes("v1"), "ana");

    assertTrue(store.quarantine("com.example.app", "1.0.0"));

    Path quarantineDir = tempDir.resolve("quarantine").resolve("com.example.app");
    assertTrue(Files.isDirectory(quarantineDir));
    try (var entries = Files.newDirectoryStream(quarantineDir)) {
      assertTrue(entries.iterator().hasNext(), "expected a quarantined version directory");
    }
  }

  @Test
  void quarantine_returns_false_when_nothing_is_stored_at_the_coordinate() {
    assertFalse(store.quarantine("com.example.ghost", "1.0.0"));
  }

  @Test
  void a_push_within_the_configured_size_limit_succeeds() throws Exception {
    ArtifactStore capped = new ArtifactStore(tempDir.resolve("capped"), 32);
    byte[] jar = "under-the-cap".getBytes(StandardCharsets.UTF_8);

    PutResult result = capped.put("com.example.app", "1.0.0", bytes(new String(jar)), "ana");

    assertEquals(PutOutcome.CREATED, result.outcome());
    assertEquals(sha256Of(jar), result.stored().sha256());
  }

  @Test
  void a_push_over_the_configured_size_limit_is_rejected_and_leaves_no_temp_file()
      throws Exception {
    ArtifactStore capped = new ArtifactStore(tempDir.resolve("capped"), 8);

    assertThrows(
        ArtifactStore.ArtifactTooLargeException.class,
        () -> capped.put("com.example.app", "1.0.0", bytes("this-is-way-over-the-cap"), "ana"));

    assertTrue(capped.meta("com.example.app", "1.0.0").isEmpty());
    // The temp file put() streams into is cleaned up by its own finally block even when the
    // stream itself aborted mid-copy -- no leftover partial upload should survive the rejection.
    try (var entries = Files.newDirectoryStream(tempDir.resolve("capped").resolve("tmp"))) {
      assertFalse(entries.iterator().hasNext(), "expected no leftover temp file after rejection");
    }
  }

  @Test
  void the_default_single_argument_constructor_imposes_no_size_limit() throws Exception {
    // store (from setUp) uses the single-argument constructor -- the multi-megabyte push test
    // above already exercises this implicitly, but this test names the guarantee explicitly so a
    // future change to the default can't silently start capping every existing caller (including
    // every other test in this class) that never opted into a limit.
    byte[] jar = new byte[2 * 1024 * 1024];
    new Random(7).nextBytes(jar);

    PutResult result =
        store.put("com.example.uncapped", "1.0.0", new ByteArrayInputStream(jar), "ana");

    assertEquals(PutOutcome.CREATED, result.outcome());
  }

  private static ByteArrayInputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256Of(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
