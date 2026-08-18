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

  /**
   * Every real Maven distribution's {@code bin/} -- including one resolved through the Maven
   * Wrapper -- carries both {@code mvn} (the POSIX shell script) and {@code mvn.cmd} (the Windows
   * batch launcher) as plain regular files side by side. An OS-blind existence check finds {@code
   * mvn} first regardless of platform and hands it to {@link ProcessBuilder} on Windows too, where
   * {@code CreateProcess} rejects it outright (error 193, "not a valid Win32 application") since it
   * has no PE header. The resolved launcher must track the actual platform, not file-list order.
   */
  @Test
  void when_both_launchers_exist_the_platform_appropriate_one_is_chosen(@TempDir Path home)
      throws Exception {
    Path binDir = Files.createDirectories(home.resolve("bin"));
    Path shellScript = binDir.resolve("mvn");
    Path windowsBatch = binDir.resolve("mvn.cmd");
    Files.writeString(shellScript, "#!/bin/sh\n");
    Files.writeString(windowsBatch, "@ECHO OFF\r\n");

    Path expected =
        System.getProperty("os.name", "").toLowerCase().contains("win")
            ? windowsBatch
            : shellScript;
    assertEquals(Optional.of(expected), GimleProcesses.mavenLauncherUnder(home.toString()));
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
