package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** See {@link DoctorMojoTest}'s own javadoc for why this is the lightest reasonable coverage. */
class InitMojoTest {

  @Test
  void builds_the_minimal_command_with_no_out_dir() {
    List<String> command = InitMojo.buildCommand("java", "hilmir.jar", "target/app.jar", null);
    assertEquals(
        List.of(
            "java", "-cp", "hilmir.jar", "com.gimle.hilmir.HilmirMain", "init", "target/app.jar"),
        command);
  }

  @Test
  void includes_out_dir_flag_when_set() {
    List<String> command =
        InitMojo.buildCommand("java", "hilmir.jar", "target/app.jar", "/tmp/out");
    int outDirIndex = command.indexOf("--out-dir");
    assertTrue(outDirIndex >= 0);
    assertEquals("/tmp/out", command.get(outDirIndex + 1));
  }

  @Test
  void a_blank_out_dir_is_treated_as_unset() {
    List<String> command = InitMojo.buildCommand("java", "hilmir.jar", "target/app.jar", "   ");
    assertFalse(command.contains("--out-dir"));
  }
}
