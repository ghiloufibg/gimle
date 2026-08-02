package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The self-contained "deploy to a real server" smoke suite: spawns a genuine {@code
 * ControlPlaneMain} + {@code AgentMain} (+ its own {@code WorkerMain} child) subprocess cluster --
 * modeled directly on {@code ControlPlaneAgentWorkerIntegrationTest}'s subprocess patterns
 * (gimle-agent), except that test constructs {@code ApiServer} in-JVM; this needs a real process
 * because the Playwright suite it drives at the end needs a real browser hitting the real bundled
 * web console, which only a genuine {@code ControlPlaneMain} serves.
 *
 * <p>Deploys {@code greeter-provider} and {@code greeter-consumer} (gimle-examples/) via the real
 * HTTP API, asserts both reach {@code ACTIVE} and that the consumer's real fabric call to the
 * provider actually shows up in its own application log, then runs {@code gimle-console}'s
 * Playwright suite against that same live cluster.
 *
 * <p>Uses ports distinct from {@code gimle-console/LOCAL_DEV.md}'s manual walkthrough (8080/9080)
 * so this can run alongside a developer's own manually-started cluster without colliding.
 *
 * <p>Not part of the default {@code mvn verify} -- opt in with {@code -Psmoke}. Assumes {@code mvn
 * install} has already produced every jar this launches, the same precondition LOCAL_DEV.md's own
 * manual flow has.
 */
class GreeterSmokeTestIT {

  private static final int CONTROLPLANE_PORT = 18080;
  private static final int RAFT_PORT = 19080;
  private static final String GOSSIP_ADDRESS = "127.0.0.1:19090";
  private static final String BASE_URL = "http://127.0.0.1:" + CONTROLPLANE_PORT;
  private static final String GIMLE_VERSION = "0.1.0-SNAPSHOT";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private final List<Process> processes = new ArrayList<>();
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void tearDown() {
    // Reverse order: agent before control plane, so the agent's own final heartbeat/teardown
    // attempts don't race a control plane that's already gone.
    for (int i = processes.size() - 1; i >= 0; i--) {
      killWithDescendants(processes.get(i));
    }
  }

  private static void killWithDescendants(Process process) {
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroyForcibly();
  }

  @Test
  @Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
  void greeter_modules_deploy_and_the_consumer_reaches_the_provider_over_the_real_fabric()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    processes.add(
        spawnControlPlane(javaExecutable, classpath, tempDir.resolve("controlplane.log")));
    await(
        () -> httpRespondsQuietly(BASE_URL + "/deployments"),
        Duration.ofSeconds(30),
        "control plane should start accepting requests");

    processes.add(
        spawnAgent(javaExecutable, classpath, GOSSIP_ADDRESS, tempDir.resolve("agent.log")));

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    Path consumerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-consumer/target/greeter-consumer-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);
    assertTrue(Files.isRegularFile(consumerJar), "expected a built jar at " + consumerJar);

    submitDeployment(
        "greeter-provider-deployment", "com.gimle.examples.greeter.provider", providerJar);
    submitDeployment(
        "greeter-consumer-deployment", "com.gimle.examples.greeter.consumer", consumerJar);

    await(
        () -> isActive("greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE");
    await(
        () -> isActive("greeter-consumer-deployment"),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment should reach ACTIVE");

    // Proves the real fabric call happened, not just that both processes started: the consumer
    // retries its lookup+call every 5s, so a healthy cluster should show this well within a
    // minute of both instances going ACTIVE.
    await(
        () -> consumerLogShowsAGreeting(),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment's own log should show a real reply from greeter-provider");

    runPlaywrightSuite(repoRoot);
  }

  private void runPlaywrightSuite(Path repoRoot) throws IOException, InterruptedException {
    Path consoleDir = repoRoot.resolve("gimle-console");
    Path logFile = tempDir.resolve("playwright.log");
    ProcessBuilder pb =
        new ProcessBuilder(bunExecutable(), "run", "test:e2e").directory(consoleDir.toFile());
    pb.environment().put("CONSOLE_BASE_URL", BASE_URL);
    // Not inheritIO(): Surefire/Failsafe's forked-JVM protocol talks to the parent Maven process
    // over this same JVM's own stdout, and a child process writing to it directly (inheritIO
    // shares the file descriptor, not just mirrors it) corrupts that channel. Redirect to a file
    // like every other subprocess this test spawns, and surface it on failure instead.
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    Process playwright = pb.start();
    int exitCode = playwright.waitFor();
    if (exitCode != 0) {
      fail(
          "gimle-console's Playwright suite failed with exit code "
              + exitCode
              + "; see "
              + logFile);
    }
  }

  private static String bunExecutable() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "bun.exe"
        : "bun";
  }

  private Process spawnControlPlane(String javaExecutable, String classpath, Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.controlplane.ControlPlaneMain",
            String.valueOf(CONTROLPLANE_PORT),
            tempDir.resolve("controlplane-state").toString(),
            String.valueOf(RAFT_PORT));
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private Process spawnAgent(
      String javaExecutable, String classpath, String gossipAddress, Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.agent.AgentMain",
            "smoke-node-1",
            BASE_URL,
            gossipAddress,
            "-",
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.worker.WorkerMain");
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private void submitDeployment(String deploymentName, String moduleName, Path jar)
      throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        """
            .formatted(deploymentName, moduleName, jar.toAbsolutePath());
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/deployments/" + deploymentName))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("deployment submission failed: " + response.statusCode() + " " + response.body());
    }
  }

  private boolean isActive(String deploymentName) {
    try {
      Map<String, Object> status = deploymentStatus(deploymentName);
      List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
      if (instances.isEmpty()) {
        return false;
      }
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (!(observation instanceof Map<?, ?> obsMap)
            || !"ACTIVE".equals(obsMap.get("lifecycleState"))) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private Map<String, Object> deploymentStatus(String deploymentName) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(BASE_URL + "/deployments/" + deploymentName))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      return Map.of("instances", List.of());
    }
    return Json.asObject(Json.parse(response.body()));
  }

  private boolean consumerLogShowsAGreeting() {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          BASE_URL
                              + "/logs/instances/greeter-consumer-deployment/0"
                              + "?category=APPLICATION"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 && response.body().contains("Hello, Gimlé!");
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean httpRespondsQuietly(String url) {
    try {
      HttpResponse<Void> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).GET().build(),
                  HttpResponse.BodyHandlers.discarding());
      return response.statusCode() < 500;
    } catch (Exception e) {
      return false;
    }
  }

  private static void await(BooleanSupplier condition, Duration timeout, String description) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout + ": " + description);
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for: " + description, e);
      }
    }
  }

  private static Path repoRoot() {
    return Path.of("").toAbsolutePath().getParent();
  }

  private static String javaExecutable() {
    java.util.Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }
}
