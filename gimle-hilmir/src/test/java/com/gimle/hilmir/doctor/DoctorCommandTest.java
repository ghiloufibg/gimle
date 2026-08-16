package com.gimle.hilmir.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.analyze.testsupport.HilmirTestJarBuilder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorCommandTest {

  @TempDir Path tempDir;

  @Test
  void exits_zero_for_a_clean_module_jar() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withModuleInfo(HilmirTestJarBuilder.minimalModuleInfo("com.example.cmd"))
            .withDescriptor(HilmirTestJarBuilder.minimalDescriptor("com.example.cmd", "1.0.0"))
            .build(tempDir, "clean.jar");

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int exitCode = DoctorCommand.run(List.of(jar.toString()), new PrintStream(buffer));
    assertEquals(0, exitCode);
  }

  @Test
  void exits_non_zero_when_an_error_finding_fires() throws Exception {
    Path war = tempDir.resolve("app.war");
    java.nio.file.Files.writeString(war, "x", StandardCharsets.UTF_8);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int exitCode = DoctorCommand.run(List.of(war.toString()), new PrintStream(buffer));
    assertEquals(1, exitCode);
    assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("UNSUPPORTED_PACKAGING"));
  }

  @Test
  void vessel_flag_downgrades_launcher_archive_finding_to_a_passing_result() throws Exception {
    Path jar =
        HilmirTestJarBuilder.create()
            .withMainClass("org.springframework.boot.loader.JarLauncher")
            .withExtraEntry("BOOT-INF/classes/", new byte[0])
            .withExtraEntry("BOOT-INF/lib/", new byte[0])
            .build(tempDir, "fat.jar");

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int exitCode = DoctorCommand.run(List.of(jar.toString(), "--vessel"), new PrintStream(buffer));
    assertEquals(0, exitCode);
  }

  @Test
  void json_output_is_a_json_array() throws Exception {
    Path war = tempDir.resolve("app.war");
    java.nio.file.Files.writeString(war, "x", StandardCharsets.UTF_8);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DoctorCommand.run(List.of(war.toString(), "-o", "json"), new PrintStream(buffer));
    String output = buffer.toString(StandardCharsets.UTF_8).strip();
    assertTrue(output.startsWith("["));
    assertTrue(output.contains("UNSUPPORTED_PACKAGING"));
  }

  @Test
  void requires_at_least_one_jar_argument() {
    assertThrows(
        HilmirException.class,
        () -> DoctorCommand.run(List.of(), new PrintStream(new ByteArrayOutputStream())));
  }
}
