package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** See {@link DoctorMojoTest}'s own javadoc for why this is the lightest reasonable coverage. */
class InitMojoTest {

  @Test
  void the_out_dir_parameter_defaults_to_the_project_basedir_not_the_build_directory() {
    // Regression pin for the documented default (maven-plugin-goals.md's own table: "Never
    // overwrites a file that already exists there") -- a generated
    // gimle-module.yaml/deployment.yaml
    // belongs beside the project's own sources, not inside target/, which `mvn clean` wipes.
    // @Parameter carries RetentionPolicy.CLASS, not RUNTIME, so its defaultValue isn't visible via
    // plain reflection here -- read the plugin descriptor maven-plugin-plugin already generated
    // from that same annotation instead, the actual artifact `mvn gimle:init` resolves its default
    // from.
    String descriptor = readPluginDescriptor();
    Matcher matcher =
        Pattern.compile(
                "<outDir implementation=\"java\\.lang\\.String\" default-value=\"([^\"]*)\"")
            .matcher(descriptor);
    assertTrue(matcher.find(), "expected an outDir parameter in the generated plugin descriptor");
    assertEquals("${project.basedir}", matcher.group(1));
  }

  private static String readPluginDescriptor() {
    try (InputStream in = InitMojoTest.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
      if (in == null) {
        throw new IllegalStateException(
            "plugin.xml not found on the test classpath -- expected maven-plugin-plugin's own"
                + " descriptor goal to have generated it into target/classes by the time tests"
                + " run");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

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
