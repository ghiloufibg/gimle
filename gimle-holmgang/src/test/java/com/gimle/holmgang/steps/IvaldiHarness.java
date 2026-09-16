package com.gimle.holmgang.steps;

import com.gimle.core.protocol.Json;
import com.gimle.holmgang.HolmgangException;
import com.gimle.testkit.Await;
import com.gimle.testkit.PortLease;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A real {@code IvaldiMain} subprocess, driven purely over HTTP the way its own console is -- the
 * Gherkin-layer counterpart to {@code gimle-smoke-tests}' own {@code IvaldiRunEngineIT}, which
 * proved this same path (designer document -> tier-2 validation -> a genuine {@code
 * MachineLauncher.up}-booted cluster -> a real deployed instance reaching {@code ACTIVE} -> torn
 * down again) as a plain JUnit {@code *IT} rather than a Holmgang Cucumber scenario. Neither {@code
 * ClusterPool} nor {@code GimleCluster} applies here: this harness's whole point is that *Ivaldi
 * itself*, not this test process, boots and tears down the platform tree via its own {@code
 * RunController}.
 */
final class IvaldiHarness implements AutoCloseable {

  private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(30);
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private final Process process;
  private final String baseUrl;
  private final Path workDir;
  private final Path clusterDataRoot;

  private IvaldiHarness(Process process, String baseUrl, Path workDir, Path clusterDataRoot) {
    this.process = process;
    this.baseUrl = baseUrl;
    this.workDir = workDir;
    this.clusterDataRoot = clusterDataRoot;
  }

  /** Spawns a real {@code IvaldiMain} process, bound to loopback, and waits for it to answer. */
  static IvaldiHarness start() {
    Path workDir = Path.of("target", "holmgang", "ivaldi-" + Long.toHexString(System.nanoTime()));
    Path clusterDataRoot = workDir.resolve("cluster-data");
    Path ivaldiDataRoot = workDir.resolve("ivaldi-data");
    try (PortLease lease = PortLease.reserve(1)) {
      int port = lease.ports().get(0);
      lease.release(port);
      Process process = spawn(port, ivaldiDataRoot, workDir);
      String baseUrl = "http://127.0.0.1:" + port;
      IvaldiHarness harness = new IvaldiHarness(process, baseUrl, workDir, clusterDataRoot);
      harness.awaitHealthy();
      return harness;
    }
  }

  private static Process spawn(int port, Path dataRoot, Path logDir) {
    ProcessBuilder builder =
        new ProcessBuilder(
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            "com.gimle.ivaldi.IvaldiMain",
            "--port",
            String.valueOf(port),
            "--data-root",
            dataRoot.toString());
    builder.redirectErrorStream(true);
    try {
      Files.createDirectories(logDir);
      builder.redirectOutput(logDir.resolve("ivaldi.log").toFile());
      return builder.start();
    } catch (IOException e) {
      throw new HolmgangException("failed spawning IvaldiMain", e);
    }
  }

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
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
    throw new HolmgangException("could not locate the java launcher under " + javaBin);
  }

  private void awaitHealthy() {
    Await.until(
        () -> {
          try {
            return get("/api/health").statusCode() == 200;
          } catch (RuntimeException e) {
            return false;
          }
        },
        HEALTH_TIMEOUT,
        "the Ivaldi process should come up and answer its own health check");
  }

  /**
   * Runs tier-2 validation against {@code files} through {@code POST /api/validate} -- the real
   * Hilmir/Mimir parsers, not Ivaldi's own tier-1 rules -- returning the raw {@code {findings:
   * [...]}} response.
   */
  Map<String, Object> validate(List<Map<String, String>> files) {
    HttpResponse<String> response = post("/api/validate", Json.write(Map.of("files", files)));
    requireStatus(response, 200);
    return Json.asObject(Json.parse(response.body()));
  }

  /** {@code POST /api/blueprints}, returning the minted blueprint id. */
  String saveBlueprint(String rawJson) {
    HttpResponse<String> response = post("/api/blueprints", rawJson);
    requireStatus(response, 201);
    return String.valueOf(Json.asObject(Json.parse(response.body())).get("id"));
  }

  /** {@code GET /api/blueprints/{id}}, returning the raw stored JSON body. */
  String readBlueprint(String id) {
    HttpResponse<String> response = get("/api/blueprints/" + id);
    requireStatus(response, 200);
    return response.body();
  }

  /**
   * Reserves the ports a fresh single-machine plaintext topology needs, renders it, and saves a
   * cluster connection pointing at it -- returning the pair a run needs: the cluster id, and the
   * topology YAML itself (a run's own {@code files} must declare the identical topology the
   * connection's {@code controlPlaneUrl} actually names).
   */
  record FreshCluster(String clusterId, String topologyYaml, String controlPlaneUrl) {}

  FreshCluster createFreshCluster() {
    try (PortLease lease = PortLease.reserve(7)) {
      Iterator<Integer> ports = lease.ports().iterator();
      int storeRaftPort = ports.next();
      int storeClientPort = ports.next();
      int controlPlanePort = ports.next();
      int fafnirPort = ports.next();
      int muninnPort = ports.next();
      int andvariPort = ports.next();
      int gossipPort = ports.next();
      String topologyYaml =
          """
          name: holmgang-ivaldi-designer
          machines:
            - {name: m1, host: 127.0.0.1}
          runtime:
            dataRoot: %s
          store:
            replicas:
              - {machine: m1, raftPort: %d, clientPort: %d}
          controlPlane:
            replicas:
              - {machine: m1, port: %d}
          fafnir:
            keyFile: %s
            replicas:
              - {machine: m1, port: %d}
          muninn:
            replicas:
              - {machine: m1, port: %d}
          andvari:
            replicas:
              - {machine: m1, port: %d}
          agents:
            - {machine: m1, nodeId: m1-node, gossipPort: %d}
          """
              .formatted(
                  clusterDataRoot,
                  storeRaftPort,
                  storeClientPort,
                  controlPlanePort,
                  clusterDataRoot.resolve("fafnir.key"),
                  fafnirPort,
                  muninnPort,
                  andvariPort,
                  gossipPort);
      String controlPlaneUrl = "127.0.0.1:" + controlPlanePort;
      String body =
          Json.write(
              Map.of("name", "holmgang-ivaldi-designer", "controlPlaneUrl", controlPlaneUrl));
      HttpResponse<String> response = post("/api/clusters", body);
      requireStatus(response, 201);
      String clusterId = String.valueOf(Json.asObject(Json.parse(response.body())).get("id"));
      // Every port MachineLauncher.up itself will bind must actually be free the instant the run
      // starts, not just leased-then-released mid-setup -- released together, immediately before
      // this method returns, mirroring gimle-smoke-tests' own IvaldiRunEngineIT.
      List.of(
              storeRaftPort,
              storeClientPort,
              controlPlanePort,
              fafnirPort,
              muninnPort,
              andvariPort,
              gossipPort)
          .forEach(lease::release);
      return new FreshCluster(clusterId, topologyYaml, controlPlaneUrl);
    }
  }

  /** {@code POST /api/runs}, returning the started run's own synchronous response shape. */
  void startRun(String clusterId, String blueprintId, List<Map<String, String>> files) {
    String body =
        Json.write(Map.of("clusterId", clusterId, "blueprintId", blueprintId, "files", files));
    HttpResponse<String> response = post("/api/runs", body);
    requireStatus(response, 201);
  }

  String runStatus() {
    return String.valueOf(currentSnapshot().get("status"));
  }

  Map<String, Object> currentSnapshot() {
    HttpResponse<String> response = get("/api/runs/current");
    requireStatus(response, 200);
    return Json.asObject(Json.parse(response.body()));
  }

  String runLog() {
    Object id = currentSnapshot().get("id");
    if (id == null) {
      return "(no run id)";
    }
    return get("/api/runs/" + id + "/log").body();
  }

  void stopRun() {
    HttpResponse<String> response = delete("/api/runs/current");
    requireStatus(response, 200);
  }

  /** Whether {@code deploymentName} is ACTIVE on the real control plane this run itself booted. */
  boolean isDeploymentActive(String controlPlaneUrl, String deploymentName) {
    try {
      HttpResponse<String> response =
          HTTP.send(
              HttpRequest.newBuilder(
                      URI.create("http://" + controlPlaneUrl + "/deployments/" + deploymentName))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return false;
      }
      Map<String, Object> status = Json.asObject(Json.parse(response.body()));
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
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  /**
   * Whether the process tree a run booted has actually gone -- {@code MachineLauncher.down}'s own
   * ledger read fails once every process it recorded is torn down and the ledger file itself is
   * removed, so this is the real proof a stop tore the tree down rather than merely reporting idle.
   */
  boolean processTreeGone() {
    return Files.notExists(clusterDataRoot.resolve("hilmir-run-m1.json"));
  }

  Path workDir() {
    return workDir;
  }

  @Override
  public void close() {
    try {
      stopRun();
    } catch (RuntimeException e) {
      // Best-effort: a scenario that already failed before stopping its own run leaves this as
      // the last chance to tear the real process tree down before the Ivaldi process itself dies.
    }
    process.destroyForcibly();
  }

  private static void requireStatus(HttpResponse<String> response, int expected) {
    if (response.statusCode() != expected) {
      throw new HolmgangException(
          "expected HTTP "
              + expected
              + " but got "
              + response.statusCode()
              + ": "
              + response.body());
    }
  }

  private HttpResponse<String> get(String path) {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
  }

  private HttpResponse<String> post(String path, String body) {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
  }

  private HttpResponse<String> delete(String path) {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE());
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HolmgangException("interrupted talking to Ivaldi", e);
    }
  }
}
