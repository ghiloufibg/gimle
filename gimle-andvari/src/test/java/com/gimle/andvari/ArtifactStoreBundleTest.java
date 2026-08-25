package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.andvari.ArtifactStore.PutOutcome;
import com.gimle.andvari.ArtifactStore.PutResult;
import com.gimle.core.module.ArtifactKind;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bundle side of {@link ArtifactStore}'s contract: a {@code BUNDLE} coordinate stores {@code
 * bundle.zip} under the identical immutability rules a jar has, and a coordinate's kind is as
 * immutable as its content and tenant.
 */
class ArtifactStoreBundleTest {

  @TempDir Path tempDir;

  private ArtifactStore store;

  @BeforeEach
  void setUp() throws Exception {
    store = new ArtifactStore(tempDir);
  }

  private static InputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void a_bundle_push_stores_bundle_zip_and_records_the_kind() throws Exception {
    byte[] zip = "pretend-zip-bytes".getBytes(StandardCharsets.UTF_8);

    PutResult result =
        store.put(
            "com.example.report",
            "1.0.0",
            new ByteArrayInputStream(zip),
            "ana",
            Optional.empty(),
            ArtifactKind.BUNDLE);

    assertEquals(PutOutcome.CREATED, result.outcome());
    assertEquals(ArtifactKind.BUNDLE, result.stored().kind());
    Path stored = store.artifactFilePath("com.example.report", "1.0.0").orElseThrow();
    assertEquals("bundle.zip", stored.getFileName().toString());
    assertArrayEquals(zip, Files.readAllBytes(stored));
    assertEquals(
        ArtifactKind.BUNDLE, store.meta("com.example.report", "1.0.0").orElseThrow().kind());
  }

  @Test
  void an_identical_bundle_re_push_is_idempotent() throws Exception {
    store.put(
        "com.example.report", "1.0.0", bytes("same"), "ana", Optional.empty(), ArtifactKind.BUNDLE);

    PutResult second =
        store.put(
            "com.example.report",
            "1.0.0",
            bytes("same"),
            "ben",
            Optional.empty(),
            ArtifactKind.BUNDLE);

    assertEquals(PutOutcome.IDENTICAL, second.outcome());
    assertEquals("ana", second.stored().pushedBy());
  }

  @Test
  void a_re_push_under_a_different_kind_is_a_conflict_even_with_identical_bytes() throws Exception {
    store.put("com.example.app", "1.0.0", bytes("same"), "ana");

    PutResult flipped =
        store.put(
            "com.example.app",
            "1.0.0",
            bytes("same"),
            "eve",
            Optional.empty(),
            ArtifactKind.BUNDLE);

    assertEquals(PutOutcome.CONFLICT, flipped.outcome());
    assertEquals(ArtifactKind.JAR, flipped.stored().kind());
  }

  @Test
  void deleting_a_bundle_coordinate_removes_its_zip() throws Exception {
    store.put(
        "com.example.report", "1.0.0", bytes("v1"), "ana", Optional.empty(), ArtifactKind.BUNDLE);

    assertTrue(store.delete("com.example.report", "1.0.0"));
    assertTrue(store.artifactFilePath("com.example.report", "1.0.0").isEmpty());
    assertTrue(store.meta("com.example.report", "1.0.0").isEmpty());
  }

  @Test
  void quarantining_a_bundle_coordinate_removes_it_from_the_catalog() throws Exception {
    store.put(
        "com.example.report", "1.0.0", bytes("v1"), "ana", Optional.empty(), ArtifactKind.BUNDLE);

    assertTrue(store.quarantine("com.example.report", "1.0.0"));
    assertTrue(store.artifactFilePath("com.example.report", "1.0.0").isEmpty());
    assertTrue(store.moduleIds().isEmpty());
  }

  @Test
  void a_meta_json_without_a_kind_parses_as_a_jar() throws Exception {
    store.put("com.example.app", "1.0.0", bytes("v1"), "ana");
    Path metaFile = tempDir.resolve("artifacts/com.example.app/1.0.0/meta.json");
    String withoutKind = Files.readString(metaFile).replace(",\"kind\":\"JAR\"", "");
    Files.writeString(metaFile, withoutKind);

    assertEquals(ArtifactKind.JAR, store.meta("com.example.app", "1.0.0").orElseThrow().kind());
  }
}
