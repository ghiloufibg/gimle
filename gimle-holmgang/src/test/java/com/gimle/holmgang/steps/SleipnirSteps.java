package com.gimle.holmgang.steps;

import com.gimle.holmgang.HolmgangException;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Proves Sleipnir (the JDK AOT-cache worker-startup accelerator) degrades safely under Holmgang's
 * own conditions -- {@code GimleCluster.classpath()} is the test JVM's own mixed dir+jar classpath,
 * JEP 483-ineligible, so a Holmgang scenario can't measure real cache-accelerated speedup the way
 * {@code WorkerStartupBenchIT} does (see {@code gimle-holmgang/README.md}'s "No checked-in
 * AOT-cache A/B fixture" section). What it can and does prove: the ineligibility is logged once, a
 * deployment still reaches ACTIVE normally, and no cache files are ever written.
 *
 * <p>Reads the node agent's own {@code agent-platform.log} and {@code aot-cache/} directly off disk
 * at the paths {@code LaunchPlanner} itself derives ({@code
 * workDir/<nodeId>-logs/agent-platform.log}, {@code workDir/<nodeId>/aot-cache/}) -- there is no
 * agent-log equivalent of {@code LogConditions} (instance-log-scoped only) to reuse instead.
 */
public final class SleipnirSteps {

  private final ScenarioWorld world;

  public SleipnirSteps(final ScenarioWorld world) {
    this.world = world;
  }

  @Then("within {int}s node {string} logs {string}")
  public void withinSecondsNodeLogs(final int seconds, final String nodeId, final String text) {
    world
        .cluster()
        .when()
        .probe(
            "node " + nodeId + "'s own agent-platform.log contains \"" + text + "\"",
            () -> agentPlatformLog(nodeId).map(content -> content.contains(text)).orElse(false))
        .await(Duration.ofSeconds(seconds));
  }

  @Then("node {string} has no AOT cache files")
  public void nodeHasNoAotCacheFiles(final String nodeId) {
    final Path aotCacheDir = world.cluster().workDir().resolve(nodeId).resolve("aot-cache");
    if (!Files.isDirectory(aotCacheDir)) {
      return; // never created at all -- also a legitimate "no cache files" outcome
    }
    try (Stream<Path> files = Files.list(aotCacheDir)) {
      if (files.findAny().isPresent()) {
        throw new HolmgangException(
            "expected no AOT cache files under " + aotCacheDir + ", found some");
      }
    } catch (IOException e) {
      throw new HolmgangException("failed to list " + aotCacheDir, e);
    }
  }

  private Optional<String> agentPlatformLog(final String nodeId) {
    final Path logFile =
        world.cluster().workDir().resolve(nodeId + "-logs").resolve("agent-platform.log");
    if (!Files.isRegularFile(logFile)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(logFile));
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
