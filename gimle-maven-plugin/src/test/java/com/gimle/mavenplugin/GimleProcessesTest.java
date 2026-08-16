package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GimleProcessesTest {

  @Test
  void maven_launcher_is_found_under_a_maven_home(@TempDir Path home) throws Exception {
    Path launcher = home.resolve("bin").resolve("mvn");
    Files.createDirectories(launcher.getParent());
    Files.writeString(launcher, "#!/bin/sh\n");

    assertEquals(Optional.of(launcher), GimleProcesses.mavenLauncherUnder(home.toString()));
  }

  @Test
  void a_home_without_the_launcher_yields_empty(@TempDir Path home) {
    assertTrue(GimleProcesses.mavenLauncherUnder(home.toString()).isEmpty());
  }

  @Test
  void a_blank_home_yields_empty() {
    assertTrue(GimleProcesses.mavenLauncherUnder("  ").isEmpty());
    assertTrue(GimleProcesses.mavenLauncherUnder(null).isEmpty());
  }

  @Test
  void maven_launcher_is_found_by_scanning_a_path_variable(@TempDir Path root) throws Exception {
    Path without = Files.createDirectories(root.resolve("without"));
    Path with = Files.createDirectories(root.resolve("with"));
    Path launcher = with.resolve("mvn");
    Files.writeString(launcher, "#!/bin/sh\n");
    String pathValue = without + File.pathSeparator + with;

    assertEquals(Optional.of(launcher), GimleProcesses.mavenLauncherOnPath(pathValue));
  }

  @Test
  void an_empty_path_variable_yields_empty() {
    assertTrue(GimleProcesses.mavenLauncherOnPath("").isEmpty());
    assertTrue(GimleProcesses.mavenLauncherOnPath(null).isEmpty());
  }

  @Test
  void await_exit_returns_the_finished_childs_exit_code() {
    assertEquals(3, GimleProcesses.awaitExit(FakeProcess.exited(3)));
    assertEquals(0, GimleProcesses.awaitExit(FakeProcess.exited(0)));
  }
}
