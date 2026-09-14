package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.protocol.Json;
import com.gimle.testkit.Await;
import com.gimle.testkit.PortLease;
import java.io.IOException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one real-cluster path {@code gimle-ivaldi}'s own unit suite cannot exercise (see {@code
 * RunControllerTest}'s own javadoc): a genuine {@code IvaldiMain} process, driven purely over HTTP
 * the way its console is, actually booting a real single-machine platform process tree via {@code
 * MachineLauncher.up}, deploying a real module onto it, and tearing the whole tree back down --
 * rather than this package's other {@code *IT} classes, which spawn {@code ControlPlaneMain}/{@code
 * AgentMain} themselves and never go through Ivaldi's own run engine at all.
 *
 * <p>Deliberately minimal: {@code hello-module} (the repo's own deliberately-inert fixture module,
 * not {@code greeter-provider}/{@code greeter-consumer}) is enough to prove the whole pipeline --
 * boot, artifact push, bundle deploy, real instance reaching {@code ACTIVE}, stop, real process
 * teardown -- without a fabric call or a probe adding anything this scenario needs to prove.
 */
@Tag("smoke")
class IvaldiRunEngineIT {

  private static final String GIMLE_VERSION = "0.1.0-alpha.2";
  private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(2);
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @TempDir Path tempDir;

  private Process ivaldiProcess;

  @AfterEach
  void tearDown() {
    if (ivaldiProcess != null) {
      ivaldiProcess.destroyForcibly();
    }
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void a_blueprint_run_through_ivaldi_boots_a_real_cluster_deploys_and_tears_down()
      throws Exception {
    Path repoRoot = GreeterSmokeClusterSupport.repoRoot();
    Path helloJar =
        repoRoot.resolve(
            "gimle-examples/hello-module/target/hello-module-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(helloJar), "expected a built jar at " + helloJar);

    try (PortLease lease = PortLease.reserve(8)) {
      Iterator<Integer> ports = lease.ports().iterator();
      int storeRaftPort = ports.next();
      int storeClientPort = ports.next();
      int controlPlanePort = ports.next();
      int fafnirPort = ports.next();
      int muninnPort = ports.next();
      int andvariPort = ports.next();
      int gossipPort = ports.next();
      int ivaldiPort = ports.next();

      Path clusterDataRoot = tempDir.resolve("cluster-data");
      String topologyYaml =
          """
          name: ivaldi-run-engine-it
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
      String bundleYaml =
          """
          kind: Bundle
          name: ivaldi-run-engine-it
          version: 1.0.0
          workloads:
            - file: manifests/01-hello.yaml
          """;
      String manifestYaml =
          """
          apiVersion: v1
          kind: Deployment
          name: hello-deployment
          replicas: 1
          module:
            name: com.gimle.examples.hello
            version: 1.0.0
          """;
      String artifactsYaml =
          """
          artifacts:
            - manifest: manifests/01-hello.yaml
              module: com.gimle.examples.hello
              version: 1.0.0
              path: %s
          """
              .formatted(helloJar);

      lease.release(ivaldiPort);
      ivaldiProcess = spawnIvaldi(ivaldiPort, tempDir.resolve("ivaldi-data"), tempDir);
      String ivaldiUrl = "http://127.0.0.1:" + ivaldiPort;
      awaitHealthy(ivaldiUrl);

      // Every port MachineLauncher.up itself will bind must actually be free the instant it
      // tries, not just leased-then-released by this method -- released together, immediately
      // before the run starts, rather than one at a time throughout the setup above.
      List.of(
              storeRaftPort,
              storeClientPort,
              controlPlanePort,
              fafnirPort,
              muninnPort,
              andvariPort,
              gossipPort)
          .forEach(lease::release);

      String clusterId = createCluster(ivaldiUrl, "127.0.0.1:" + controlPlanePort);
      Map<String, Object> started =
          startRun(
              ivaldiUrl,
              clusterId,
              List.of(
                  Map.of("path", "topology.yaml", "content", topologyYaml),
                  Map.of("path", "bundle.yaml", "content", bundleYaml),
                  Map.of("path", "manifests/01-hello.yaml", "content", manifestYaml),
                  Map.of("path", "ivaldi.artifacts.yaml", "content", artifactsYaml)));
      assertTrue(started.get("id") instanceof String id && !id.isBlank(), started + "");

      AtomicReference<Map<String, Object>> finalSnapshot = new AtomicReference<>();
      Await.until(
          () -> {
            Map<String, Object> snapshot = currentSnapshot(ivaldiUrl);
            finalSnapshot.set(snapshot);
            String status = String.valueOf(snapshot.get("status"));
            return status.equals("running") || status.equals("failed");
          },
          BOOT_TIMEOUT,
          "the run should settle to running or failed");
      if (!"running".equals(finalSnapshot.get().get("status"))) {
        fail("run did not reach running: " + finalSnapshot.get() + "\nlog:\n" + runLog(ivaldiUrl));
      }

      // The real proof this scenario exists for: a real instance, placed by a real agent on a
      // real worker, observed as ACTIVE through the real control plane's own HTTP API -- not
      // just that Ivaldi itself reported "running".
      String controlPlaneUrl = "http://127.0.0.1:" + controlPlanePort;
      Await.until(
          () -> isActive(controlPlaneUrl, "hello-deployment"),
          Duration.ofSeconds(60),
          "hello-deployment should reach ACTIVE on the real control plane this run booted");

      Map<String, Object> stopped = stopRun(ivaldiUrl);
      Await.until(
          () -> "idle".equals(currentSnapshot(ivaldiUrl).get("status")),
          Duration.ofSeconds(30),
          "the run should settle to idle after stop: " + stopped);

      // The process tree this run booted must actually be gone, not merely reported idle --
      // MachineLauncher.down's own ledger read fails once every process it recorded is torn
      // down and the ledger file itself is removed.
      Await.until(
          () -> Files.notExists(clusterDataRoot.resolve("hilmir-run-m1.json")),
          Duration.ofSeconds(30),
          "the run ledger for machine m1 should be gone once the cluster is torn down");
    }
  }

  private static Process spawnIvaldi(int port, Path dataRoot, Path logDir) throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(
            GreeterSmokeClusterSupport.javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            "com.gimle.ivaldi.IvaldiMain",
            "--port",
            String.valueOf(port),
            "--data-root",
            dataRoot.toString());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.to(logDir.resolve("ivaldi.log").toFile()));
    return builder.start();
  }

  private static void awaitHealthy(String ivaldiUrl) {
    Await.until(
        () -> {
          try {
            HttpResponse<String> response = get(ivaldiUrl + "/api/health");
            return response.statusCode() == 200;
          } catch (IOException | RuntimeException e) {
            return false;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
          }
        },
        Duration.ofSeconds(30),
        "the Ivaldi server should come up and answer its own health check");
  }

  private static String createCluster(String ivaldiUrl, String controlPlaneUrl)
      throws IOException, InterruptedException {
    String body = Json.write(Map.of("name", "it-cluster", "controlPlaneUrl", controlPlaneUrl));
    HttpResponse<String> response = post(ivaldiUrl + "/api/clusters", body);
    assertEquals(201, response.statusCode(), response.body());
    return String.valueOf(Json.asObject(Json.parse(response.body())).get("id"));
  }

  private static Map<String, Object> startRun(
      String ivaldiUrl, String clusterId, List<Map<String, String>> files)
      throws IOException, InterruptedException {
    String body = Json.write(Map.of("clusterId", clusterId, "files", files));
    HttpResponse<String> response = post(ivaldiUrl + "/api/runs", body);
    assertEquals(201, response.statusCode(), response.body());
    return Json.asObject(Json.parse(response.body()));
  }

  private static Map<String, Object> currentSnapshot(String ivaldiUrl) {
    try {
      return Json.asObject(Json.parse(get(ivaldiUrl + "/api/runs/current").body()));
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private static Map<String, Object> stopRun(String ivaldiUrl)
      throws IOException, InterruptedException {
    HttpResponse<String> response = delete(ivaldiUrl + "/api/runs/current");
    assertEquals(200, response.statusCode(), response.body());
    return Json.asObject(Json.parse(response.body()));
  }

  private static String runLog(String ivaldiUrl) {
    try {
      Map<String, Object> current = currentSnapshot(ivaldiUrl);
      Object id = current.get("id");
      if (id == null) {
        return "(no run id)";
      }
      HttpResponse<String> response = get(ivaldiUrl + "/api/runs/" + id + "/log");
      return response.body();
    } catch (RuntimeException | IOException | InterruptedException e) {
      return "(failed reading log: " + e.getMessage() + ")";
    }
  }

  private static boolean isActive(String controlPlaneUrl, String deploymentName) {
    try {
      HttpResponse<String> response = get(controlPlaneUrl + "/deployments/" + deploymentName);
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
    } catch (Exception e) {
      return false;
    }
  }

  private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String url, String body)
      throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> delete(String url) throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
