package com.gimle.core.banner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class GimleVersionTest {

  @Test
  void current_reads_the_reactors_own_version_from_the_filtered_resource() {
    // Not hardcoded to "0.1.0-SNAPSHOT": that would break on every version bump. What actually
    // matters is that gimle-core/pom.xml's resource filtering ran and produced a real,
    // non-placeholder value -- "${project.version}" itself surviving unresolved is the one
    // concrete failure mode worth asserting against.
    String version = GimleVersion.current();
    assertNotNull(version);
    assertFalse(version.isBlank());
    assertFalse(version.contains("${"), "project.version should have been substituted: " + version);
  }

  @Test
  void current_is_stable_across_calls() {
    assertEquals(GimleVersion.current(), GimleVersion.current());
  }
}
