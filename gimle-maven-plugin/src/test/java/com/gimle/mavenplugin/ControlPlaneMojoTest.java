package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ControlPlaneMojo#buildCommand()} needs a live Maven session to resolve the runtime
 * classpath at all, but the actual command line it hands to the spawned process is a pure function
 * of its own inputs, split out into the static {@link ControlPlaneMojo#buildCommand(String, String,
 * String, String, String, String, String, String, String, String)} overload specifically so it can
 * be asserted here without any of that machinery -- the same seam {@link InitMojo} establishes for
 * its own {@code buildCommand}.
 */
class ControlPlaneMojoTest {

  @Test
  void threads_the_andvari_endpoint_through_to_control_plane_main() {
    List<String> command =
        ControlPlaneMojo.buildCommand(
            "java",
            "controlplane.jar",
            "8080",
            "secret.key",
            "127.0.0.1:9091",
            "127.0.0.1:9092",
            "127.0.0.1:9094",
            null,
            null,
            null);

    int index = command.indexOf("--andvari-endpoint");
    assertTrue(index >= 0, "expected --andvari-endpoint in the command line, got: " + command);
    assertEquals("127.0.0.1:9094", command.get(index + 1));
  }

  @Test
  void a_blank_andvari_endpoint_is_treated_as_unset() {
    List<String> command =
        ControlPlaneMojo.buildCommand(
            "java",
            "controlplane.jar",
            "8080",
            "secret.key",
            "127.0.0.1:9091",
            "127.0.0.1:9092",
            "   ",
            null,
            null,
            null);

    assertFalse(command.contains("--andvari-endpoint"));
  }

  @Test
  void a_null_andvari_endpoint_is_treated_as_unset() {
    List<String> command =
        ControlPlaneMojo.buildCommand(
            "java",
            "controlplane.jar",
            "8080",
            "secret.key",
            "127.0.0.1:9091",
            "127.0.0.1:9092",
            null,
            null,
            null,
            null);

    assertFalse(command.contains("--andvari-endpoint"));
  }

  @Test
  void the_fafnir_endpoint_is_always_present_unlike_the_optional_andvari_one() {
    List<String> command =
        ControlPlaneMojo.buildCommand(
            "java",
            "controlplane.jar",
            "8080",
            "secret.key",
            "127.0.0.1:9091",
            "127.0.0.1:9092",
            null,
            null,
            null,
            null);

    int index = command.indexOf("--fafnir-endpoint");
    assertTrue(index >= 0);
    assertEquals("127.0.0.1:9092", command.get(index + 1));
  }
}
