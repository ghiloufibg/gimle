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
 * by construction: nothing else is running in that reactor to compete with. Each listed module can
 * optionally be repeated several times in a row ({@code -Dgimle.flakyTests.repeat}), still one
 * clean standalone reactor invocation per repeat, so a nightly run accumulates real pass/fail
 * evidence across many runs rather than a single snapshot.
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

  /**
   * How many times, in a row, to run each listed module's flaky-tagged tests -- each repeat is
   * still its own clean standalone reactor invocation (see {@link #buildCommand(String, String)}),
   * not a Surefire rerun. A single nightly run only reports pass/fail once per test; repeating
   * accumulates real evidence of a known-flaky test's actual failure rate over many runs instead.
   */
  @Parameter(property = "gimle.flakyTests.repeat", defaultValue = "1")
  private int repeat;

  @Override
  protected void executeAtRoot() throws MojoExecutionException, MojoFailureException {
    requirePositiveRepeat(repeat);
    String mavenExecutable = GimleProcesses.mavenExecutable();
    for (String module : parseModules(modules)) {
      for (int attempt = 1; attempt <= repeat; attempt++) {
        List<String> command = buildCommand(mavenExecutable, module);
        getLog()
            .info(
                "running flaky tests for "
                    + module
                    + " (repeat "
                    + attempt
                    + " of "
                    + repeat
                    + "): "
                    + String.join(" ", command));
        int exitCode =
            GimleProcesses.startAndAwaitExit(
                command,
                project.getBasedir().toPath(),
                "flaky-tests-" + module + "-repeat" + attempt);
        if (exitCode != 0) {
          throw new MojoFailureException(
              "flaky tests in "
                  + module
                  + " failed on repeat "
                  + attempt
                  + " of "
                  + repeat
                  + " with exit code "
                  + exitCode);
        }
        getLog()
            .info(
                "flaky tests in " + module + " passed (repeat " + attempt + " of " + repeat + ")");
      }
    }
  }

  /**
   * Guards against a nonsensical {@code repeat} value up front, before spawning anything -- a value
   * below 1 would otherwise silently run each module's flaky tests zero times rather than failing
   * loudly on a plainly wrong invocation.
   */
  static void requirePositiveRepeat(int repeat) throws MojoExecutionException {
    if (repeat < 1) {
      throw new MojoExecutionException("gimle.flakyTests.repeat must be at least 1, got " + repeat);
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
