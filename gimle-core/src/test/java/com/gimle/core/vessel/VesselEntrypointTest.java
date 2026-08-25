package com.gimle.core.vessel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class VesselEntrypointTest {

  @Test
  void a_command_with_a_relative_workdir_is_valid() {
    VesselEntrypoint entrypoint =
        new VesselEntrypoint(List.of("java", "-jar", "quarkus-run.jar"), "app");
    assertEquals(List.of("java", "-jar", "quarkus-run.jar"), entrypoint.command());
    assertEquals("app", entrypoint.workdir());
  }

  @Test
  void a_null_or_blank_workdir_defaults_to_the_bundle_root() {
    assertEquals(
        VesselEntrypoint.DEFAULT_WORKDIR, new VesselEntrypoint(List.of("run"), null).workdir());
    assertEquals(
        VesselEntrypoint.DEFAULT_WORKDIR, new VesselEntrypoint(List.of("run"), "  ").workdir());
  }

  @Test
  void an_empty_command_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> new VesselEntrypoint(List.of(), "."));
  }

  @Test
  void a_blank_command_entry_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new VesselEntrypoint(List.of("java", " "), "."));
  }

  @Test
  void an_absolute_workdir_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new VesselEntrypoint(List.of("run"), "/etc"));
  }

  @Test
  void a_workdir_with_a_parent_segment_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new VesselEntrypoint(List.of("run"), "app/../.."));
  }

  @Test
  void a_workdir_with_backslashes_is_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new VesselEntrypoint(List.of("run"), "app\\conf"));
  }
}
