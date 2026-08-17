package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VersionTest {

  @Test
  void parses_major_minor_patch() {
    Version v = Version.parse("1.4.2");
    assertEquals(new Version(1, 4, 2, ""), v);
    assertEquals("1.4.2", v.toString());
  }

  @Test
  void parses_qualifier() {
    Version v = Version.parse("2.0.0-rc1");
    assertEquals(new Version(2, 0, 0, "rc1"), v);
    assertEquals("2.0.0-rc1", v.toString());
  }

  @Test
  void orders_by_major_then_minor_then_patch() {
    assertTrue(Version.parse("2.0.0").compareTo(Version.parse("1.9.9")) > 0);
    assertTrue(Version.parse("1.2.0").compareTo(Version.parse("1.10.0")) < 0);
    assertTrue(Version.parse("1.2.3").compareTo(Version.parse("1.2.2")) > 0);
  }

  @Test
  void unqualified_outranks_qualified() {
    assertTrue(Version.parse("1.0.0").compareTo(Version.parse("1.0.0-rc1")) > 0);
  }

  @Test
  void qualifiers_compare_lexicographically() {
    assertTrue(Version.parse("1.0.0-alpha").compareTo(Version.parse("1.0.0-beta")) < 0);
  }

  @Test
  void equal_versions_compare_to_zero() {
    assertEquals(0, Version.parse("1.2.3").compareTo(Version.parse("1.2.3")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.2", "1.2.3.4", "v1.2.3", "1.2.x", "", "1.02.3", "-1.0.0"})
  void rejects_malformed_version_text(String malformed) {
    assertThrows(IllegalArgumentException.class, () -> Version.parse(malformed));
  }

  @Test
  void rejects_null_version_text() {
    assertThrows(IllegalArgumentException.class, () -> Version.parse(null));
  }

  @Test
  void rejects_negative_components() {
    assertThrows(IllegalArgumentException.class, () -> new Version(-1, 0, 0, ""));
  }

  @Test
  void rejects_null_qualifier() {
    assertThrows(IllegalArgumentException.class, () -> new Version(1, 0, 0, null));
  }
}
