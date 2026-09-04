package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link DocsMojo#execute()} needs a live Maven session to run at all, but the two child commands
 * it constructs are pure functions of their own inputs, split out into {@link
 * DocsMojo#javadocAggregateCommand} and {@link DocsMojo#docsBuildCommand} specifically so they can
 * be asserted here without any of that machinery -- the same seam {@code FlakyTestsMojoTest}
 * exercises for {@link FlakyTestsMojo}. Regression coverage for a real bug: the docs build used to
 * shell out to {@code bun} and copy files directly, bypassing {@code gimle-docs}'s own Maven module
 * (which only joins any reactor under the {@code docs} profile) entirely, so {@code gimle-docs}
 * never appeared in a build using it.
 */
class DocsMojoTest {

  @Test
  void javadoc_aggregate_runs_unbound_against_the_full_default_reactor() {
    assertEquals(List.of("mvn", "javadoc:aggregate"), DocsMojo.javadocAggregateCommand("mvn"));
  }

  @Test
  void docs_build_activates_the_docs_profile_and_targets_gimle_docs() {
    assertEquals(
        List.of("mvn", "-P", "docs", "-pl", "gimle-docs", "install"),
        DocsMojo.docsBuildCommand("mvn"));
  }

  @Test
  void both_commands_use_the_resolved_maven_executable() {
    assertEquals(
        "/opt/maven/bin/mvn", DocsMojo.javadocAggregateCommand("/opt/maven/bin/mvn").get(0));
    assertEquals("/opt/maven/bin/mvn", DocsMojo.docsBuildCommand("/opt/maven/bin/mvn").get(0));
  }
}
