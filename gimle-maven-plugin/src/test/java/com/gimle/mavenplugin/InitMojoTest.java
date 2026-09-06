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
  void the_out_dir_parameter_takes_its_project_basedir_default_as_a_file() {
    // A generated gimle-module.yaml/deployment.yaml belongs beside the project's own sources, not
    // inside target/, which `mvn clean` wipes -- so the default is the project directory. The
    // parameter's own declared type is what makes that default arrive at all: Maven evaluates a
    // default value that is exactly one expression to that expression's own type, and
    // ${project.basedir} is a java.io.File, so a String-typed parameter is left null and the goal
    // passes no output directory on at all. @Parameter carries RetentionPolicy.CLASS, not RUNTIME,
    // so this reads the plugin descriptor maven-plugin-plugin generated from that same annotation
    // -- the actual artifact `mvn gimle:init` resolves its default from.
    String descriptor = readPluginDescriptor();
    Matcher matcher =
        Pattern.compile("<outDir implementation=\"([^\"]*)\" default-value=\"([^\"]*)\"")
            .matcher(descriptor);
    assertTrue(matcher.find(), "expected an outDir parameter in the generated plugin descriptor");
    assertEquals("java.io.File", matcher.group(1));
    assertEquals("${project.basedir}", matcher.group(2));
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
