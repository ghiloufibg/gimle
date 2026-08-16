package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link FlakyTestsMojo#executeAtRoot()} needs a live Maven session to run at all, but the module
 * list parsing and per-module command it builds are pure functions of their own inputs, split out
 * into {@link FlakyTestsMojo#parseModules} and {@link FlakyTestsMojo#buildCommand} specifically so
 * they can be asserted here without any of that machinery -- the same seam {@code DoctorMojoTest}
 * exercises for {@link DoctorMojo}.
 */
class FlakyTestsMojoTest {

  @Test
  void builds_a_single_module_flaky_test_invocation() {
    List<String> command = FlakyTestsMojo.buildCommand("mvn", "gimle-mimir");
    assertEquals(
        List.of(
            "mvn",
            "-pl",
            "gimle-mimir",
            "test",
            "-Dgroups=flaky",
            "-Dgimle.excludedGroups=",
            "-Dmaven.build.cache.skipCache=true"),
        command);
  }

  @Test
  void uses_the_resolved_maven_executable_and_the_given_module() {
    List<String> command = FlakyTestsMojo.buildCommand("/opt/maven/bin/mvn", "gimle-fabric");
    assertEquals("/opt/maven/bin/mvn", command.get(0));
    assertEquals("gimle-fabric", command.get(2));
  }

  @Test
  void parses_a_single_module() {
    assertEquals(List.of("gimle-mimir"), FlakyTestsMojo.parseModules("gimle-mimir"));
  }

  @Test
  void splits_and_trims_a_comma_separated_list() {
    assertEquals(
        List.of("gimle-mimir", "gimle-fabric"),
        FlakyTestsMojo.parseModules("gimle-mimir, gimle-fabric"));
  }

  @Test
  void drops_empty_entries() {
    assertEquals(List.of("gimle-mimir"), FlakyTestsMojo.parseModules("gimle-mimir,,"));
  }
}
