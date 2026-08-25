package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.exception.GimleManifestException;
import org.junit.jupiter.api.Test;

class ArtifactKindTest {

  @Test
  void an_absent_or_blank_value_parses_as_jar() {
    assertEquals(ArtifactKind.JAR, ArtifactKind.parse(null));
    assertEquals(ArtifactKind.JAR, ArtifactKind.parse(""));
    assertEquals(ArtifactKind.JAR, ArtifactKind.parse("  "));
  }

  @Test
  void explicit_kinds_parse_to_themselves() {
    assertEquals(ArtifactKind.JAR, ArtifactKind.parse("JAR"));
    assertEquals(ArtifactKind.BUNDLE, ArtifactKind.parse("BUNDLE"));
  }

  @Test
  void an_unknown_kind_is_rejected() {
    assertThrows(GimleManifestException.class, () -> ArtifactKind.parse("TARBALL"));
  }
}
