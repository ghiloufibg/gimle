package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * No Mojo in this plugin had any test coverage before this class -- every existing goal is only
 * exercised manually (see {@code LOCAL_DEV.md}-style walkthroughs). This is the lightest reasonable
 * coverage for a process-spawning Mojo: {@link DoctorMojo#execute()} itself needs a live Maven
 * session (project, repository system) to run at all, but the command it builds is a pure function
 * of its own parameters, split out into {@link DoctorMojo#buildCommand} specifically so it can be
 * asserted here without any of that machinery.
 */
class DoctorMojoTest {

  @Test
  void builds_the_minimal_command_with_no_optional_flags() {
    List<String> command =
        DoctorMojo.buildCommand("java", "hilmir.jar", "target/app.jar", false, null, null);
    assertEquals(
        List.of(
            "java", "-cp", "hilmir.jar", "com.gimle.hilmir.HilmirMain", "doctor", "target/app.jar"),
        command);
  }

  @Test
  void includes_vessel_flag_when_requested() {
    List<String> command =
        DoctorMojo.buildCommand("java", "hilmir.jar", "target/app.jar", true, null, null);
    assertTrue(command.contains("--vessel"));
  }

  @Test
  void includes_server_and_tenant_flags_when_set() {
    List<String> command =
        DoctorMojo.buildCommand(
            "java", "hilmir.jar", "target/app.jar", false, "127.0.0.1:8080", "acme");
    int serverIndex = command.indexOf("--server");
    int tenantIndex = command.indexOf("--tenant");
    assertTrue(serverIndex >= 0);
    assertEquals("127.0.0.1:8080", command.get(serverIndex + 1));
    assertTrue(tenantIndex >= 0);
    assertEquals("acme", command.get(tenantIndex + 1));
  }

  @Test
  void a_blank_server_is_treated_as_unset() {
    List<String> command =
        DoctorMojo.buildCommand("java", "hilmir.jar", "target/app.jar", false, "  ", null);
    assertFalse(command.contains("--server"));
  }
}
