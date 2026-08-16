package com.gimle.mavenplugin;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * {@code mvn gimle:flaky-tests} -- runs every {@code @Tag("flaky")} test (see {@code
 * FLAKY_TESTS.md}), one listed module at a time, each as its own genuinely separate {@code mvn -pl
 * <module> test -Dgroups=flaky} child process rather than nested inside this build's own reactor.
 * The two known flaky tests are confirmed non-bugs, root-caused to cross-module Surefire-JVM
 * contention from a multithreaded reactor build (multiple modules' own forks competing for CPU at
 * once) -- an axis a single module's own {@code @Isolated} guard has no visibility into. Spawning
 * each listed module as its own standalone reactor, strictly in sequence, removes that contention
 * by construction: nothing else is running in that reactor to compete with.
 */
@Mojo(name = "flaky-tests", threadSafe = true)
public final class FlakyTestsMojo extends AbstractGimleRootMojo {

  /**
   * A small, manually-maintained, comma-separated list of modules known to carry
   * {@code @Tag("flaky")} tests -- matching {@code FLAKY_TESTS.md} itself being a
   * manually-maintained ledger rather than something discovered by scanning bytecode. A
   * comma-separated {@code String} rather than a {@code List<String>} so a bare {@code
   * -Dgimle.flakyTests.modules=a,b} override works with no XML list syntax needed, the same
   * convention Surefire's own {@code -Dgroups} uses. Add a module's artifactId here when a test in
   * it is promoted onto the standing exclusion list.
   */
  @Parameter(property = "gimle.flakyTests.modules", defaultValue = "gimle-mimir")
  private String modules;

  @Override
  protected void executeAtRoot() throws MojoExecutionException, MojoFailureException {
    String mavenExecutable = GimleProcesses.mavenExecutable();
    for (String module : parseModules(modules)) {
      List<String> command = buildCommand(mavenExecutable, module);
      getLog().info("running flaky tests for " + module + ": " + String.join(" ", command));
      int exitCode =
          GimleProcesses.startAndAwaitExit(
              command, project.getBasedir().toPath(), "flaky-tests-" + module);
      if (exitCode != 0) {
        throw new MojoFailureException(
            "flaky tests in " + module + " failed with exit code " + exitCode);
      }
      getLog().info("flaky tests in " + module + " passed");
    }
  }

  /** Splits and trims the comma-separated {@code modules} parameter, dropping empty entries. */
  static List<String> parseModules(String modules) {
    List<String> result = new ArrayList<>();
    for (String module : modules.split(",")) {
      String trimmed = module.trim();
      if (!trimmed.isEmpty()) {
        result.add(trimmed);
      }
    }
    return List.copyOf(result);
  }

  /**
   * Pure command construction, split out from {@link #executeAtRoot()} so it's unit-testable
   * without Maven's own parameter-injection machinery.
   */
  static List<String> buildCommand(String mavenExecutable, String module) {
    List<String> command = new ArrayList<>();
    command.add(mavenExecutable);
    command.add("-pl");
    command.add(module);
    command.add("test");
    command.add("-Dgroups=flaky");
    // The root pom's own excludedGroups=flaky (see pom.xml) would otherwise cancel this
    // inclusion right back out -- JUnit Platform's tag filter excludes a tag before it ever
    // considers what's included. Clearing it here is what actually lets the flaky-tagged tests
    // run rather than silently matching zero tests.
    command.add("-Dgimle.excludedGroups=");
    // A prior default `mvn verify` may have already cached this exact module's surefire result;
    // the build-cache extension's own checksum doesn't vary with -Dgroups, so without this a
    // "restore from cache" would silently report zero tests instead of actually running them.
    command.add("-Dmaven.build.cache.skipCache=true");
    return List.copyOf(command);
  }
}
