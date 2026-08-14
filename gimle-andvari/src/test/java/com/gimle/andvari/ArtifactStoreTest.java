package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.andvari.ArtifactStore.PutOutcome;
import com.gimle.andvari.ArtifactStore.PutResult;
import java.io.ByteArrayInputStream;
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
  void delete_removes_the_version_and_an_emptied_module_leaves_the_catalog() throws Exception {
    store.put("com.example.app", "1.0.0", bytes("v1"), "ana");

    assertTrue(store.delete("com.example.app", "1.0.0"));

    assertTrue(store.meta("com.example.app", "1.0.0").isEmpty());
    assertTrue(store.jarPath("com.example.app", "1.0.0").isEmpty());
    assertEquals(List.of(), store.moduleIds());
    assertFalse(store.delete("com.example.app", "1.0.0"));
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
  void a_multi_megabyte_body_streams_through_with_a_correct_digest() throws Exception {
    byte[] jar = new byte[3 * 1024 * 1024];
    new Random(42).nextBytes(jar);

    PutResult result = store.put("com.example.big", "1.0.0", new ByteArrayInputStream(jar), "ana");

    assertEquals(PutOutcome.CREATED, result.outcome());
    assertEquals(sha256Of(jar), result.stored().sha256());
    assertEquals(jar.length, result.stored().sizeBytes());
  }

  private static ByteArrayInputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256Of(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
