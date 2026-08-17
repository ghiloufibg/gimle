package com.gimle.holmgang.rtm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// System.setProperty mutates a JVM-global; excludes this class from running concurrently with any
// other class holding the same lock, under class-level parallel execution (root pom.xml) -- the
// same reasoning TransportProtocolTest documents on its own identical annotation.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class RtmCheckConfigTest {

  private static final String ENABLED_PROPERTY = "gimle.holmgang.rtmCheck.enabled";
  private static final String FILE_PROPERTY = "gimle.holmgang.rtmCheck.file";
  private static final String EXCLUDE_PROPERTY = "gimle.holmgang.rtmCheck.exclude";

  @AfterEach
  void clearProperties() {
    System.clearProperty(ENABLED_PROPERTY);
    System.clearProperty(FILE_PROPERTY);
    System.clearProperty(EXCLUDE_PROPERTY);
  }

  @Test
  void enabled_defaults_to_true_when_nothing_is_configured() {
    assertTrue(RtmCheckConfig.enabled());
  }

  @Test
  void enabled_honors_an_explicit_false() {
    System.setProperty(ENABLED_PROPERTY, "false");
    assertFalse(RtmCheckConfig.enabled());
  }

  @Test
  void enabled_honors_an_explicit_true() {
    System.setProperty(ENABLED_PROPERTY, "true");
    assertTrue(RtmCheckConfig.enabled());
  }

  @Test
  void rtm_file_defaults_to_rtm_json_one_directory_up() {
    assertEquals(Path.of("..", "rtm.json"), RtmCheckConfig.rtmFile());
  }

  @Test
  void rtm_file_honors_an_override_property() {
    System.setProperty(FILE_PROPERTY, "/some/other/rtm.json");
    assertEquals(Path.of("/some/other/rtm.json"), RtmCheckConfig.rtmFile());
  }

  @Test
  void rtm_file_falls_back_to_the_default_for_a_blank_override() {
    System.setProperty(FILE_PROPERTY, "   ");
    assertEquals(Path.of("..", "rtm.json"), RtmCheckConfig.rtmFile());
  }

  @Test
  void excluded_ids_default_to_empty_when_nothing_is_configured() {
    assertEquals(Set.of(), RtmCheckConfig.excludedIds());
  }

  @Test
  void excluded_ids_are_split_trimmed_and_upper_cased() {
    System.setProperty(EXCLUDE_PROPERTY, " gimle-012 ,GIMLE-018,gimle-099");
    assertEquals(Set.of("GIMLE-012", "GIMLE-018", "GIMLE-099"), RtmCheckConfig.excludedIds());
  }

  @Test
  void excluded_ids_ignore_blank_entries_from_stray_commas() {
    System.setProperty(EXCLUDE_PROPERTY, "GIMLE-012,,GIMLE-018,");
    assertEquals(Set.of("GIMLE-012", "GIMLE-018"), RtmCheckConfig.excludedIds());
  }
}
