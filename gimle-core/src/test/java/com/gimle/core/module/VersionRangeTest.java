package com.gimle.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VersionRangeTest {

  @Test
  void exact_version_satisfies_only_itself() {
    VersionRange range = VersionRange.parse("1.2.3");
    assertTrue(range.satisfies(Version.parse("1.2.3")));
    assertFalse(range.satisfies(Version.parse("1.2.4")));
    assertEquals("1.2.3", range.toString());
  }

  @Test
  void inclusive_lower_exclusive_upper() {
    VersionRange range = VersionRange.parse("[1.0.0,2.0.0)");
    assertTrue(range.satisfies(Version.parse("1.0.0")));
    assertTrue(range.satisfies(Version.parse("1.9.9")));
    assertFalse(range.satisfies(Version.parse("2.0.0")));
    assertFalse(range.satisfies(Version.parse("0.9.9")));
    assertEquals("[1.0.0,2.0.0)", range.toString());
  }

  @Test
  void exclusive_lower_inclusive_upper() {
    VersionRange range = VersionRange.parse("(1.0.0,2.0.0]");
    assertFalse(range.satisfies(Version.parse("1.0.0")));
    assertTrue(range.satisfies(Version.parse("2.0.0")));
  }

  @Test
  void unbounded_above() {
    VersionRange range = VersionRange.parse("[1.5.0,)");
    assertTrue(range.satisfies(Version.parse("1.5.0")));
    assertTrue(range.satisfies(Version.parse("99.0.0")));
    assertFalse(range.satisfies(Version.parse("1.4.9")));
    assertEquals("[1.5.0,)", range.toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[1.0.0,2.0.0",
        "1.0.0,2.0.0)",
        "[1.0.0 2.0.0)",
        "[,2.0.0)",
        "[bogus,2.0.0)",
        "",
        "   "
      })
  void rejectsMalformedRangeText(String malformed) {
    assertThrows(IllegalArgumentException.class, () -> VersionRange.parse(malformed));
  }

  @Test
  void rejects_lower_bound_exceeding_upper_bound() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VersionRange.BoundedRange(
                Version.parse("2.0.0"), true, Version.parse("1.0.0"), false));
  }

  @Test
  void rejects_null_range_text() {
    assertThrows(IllegalArgumentException.class, () -> VersionRange.parse(null));
  }
}
