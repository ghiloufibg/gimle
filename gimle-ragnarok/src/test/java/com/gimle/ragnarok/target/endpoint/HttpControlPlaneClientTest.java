package com.gimle.ragnarok.target.endpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ragnarok.target.ControlPlaneClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HttpControlPlaneClient#trySubmitDeployment} against a fake control plane: the module jar
 * must be pushed to the artifact registry proxy under the deployment's own coordinate, and the
 * deployment manifest that follows must never embed a local filesystem path.
 */
final class HttpControlPlaneClientTest {

  @TempDir private Path tempDir;

  private HttpServer server;
  private String baseUrl;
  private final List<String> requestedPaths = new ArrayList<>();
  private final List<String> requestedMethods = new ArrayList<>();
  private byte[] pushedArtifactBody;
  private String pushedArtifactTenantHeader;
  private String deploymentManifestBody;
  private int artifactResponseStatus = 200;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/artifacts",
        exchange -> {
          requestedPaths.add(exchange.getRequestURI().getPath());
          requestedMethods.add(exchange.getRequestMethod());
          pushedArtifactBody = exchange.getRequestBody().readAllBytes();
          pushedArtifactTenantHeader =
              exchange.getRequestHeaders().getFirst("X-Gimle-Artifact-Tenant");
          byte[] response = "{\"created\":true}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(artifactResponseStatus, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.createContext(
        "/deployments",
        exchange -> {
          requestedPaths.add(exchange.getRequestURI().getPath());
          requestedMethods.add(exchange.getRequestMethod());
          deploymentManifestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private ControlPlaneClient client() {
    return new HttpControlPlaneClient(HttpClient.newHttpClient(), baseUrl);
  }

  @Test
  void submitting_a_deployment_pushes_the_jar_and_writes_no_local_path() throws IOException {
    byte[] jarBytes = "fake-module-jar-bytes".getBytes(StandardCharsets.UTF_8);
    Path jar = tempDir.resolve("pause.jar");
    Files.write(jar, jarBytes);

    int status =
        client()
            .trySubmitDeployment(
                "my-deployment", "com.gimle.ragnarok.pause", "1.0.0", jar, 2, Optional.empty());

    assertEquals(200, status);
    assertEquals(
        List.of("/artifacts/com.gimle.ragnarok.pause/1.0.0", "/deployments/my-deployment"),
        requestedPaths);
    assertEquals(List.of("PUT", "PUT"), requestedMethods);
    assertArrayEquals(jarBytes, pushedArtifactBody);

    // The old, broken behavior embedded the jar's own absolute local path directly in the
    // manifest -- meaningless to a real remote control plane/node agent. The fix must never write
    // that field at all, and must instead let the module resolve from the registry coordinate
    // just pushed above.
    assertFalse(
        deploymentManifestBody.contains("artifactPath"),
        "manifest must not embed a local artifactPath: " + deploymentManifestBody);
    assertTrue(deploymentManifestBody.contains("name: com.gimle.ragnarok.pause"));
    assertTrue(deploymentManifestBody.contains("version: 1.0.0"));
  }

  @Test
  void submitting_a_deployment_with_a_tenant_scopes_the_artifact_push_to_it() throws IOException {
    Path jar = tempDir.resolve("pause.jar");
    Files.write(jar, "bytes".getBytes(StandardCharsets.UTF_8));

    client()
        .trySubmitDeployment(
            "my-deployment", "com.gimle.ragnarok.pause", "1.0.0", jar, 1, Optional.of("acme"));

    assertEquals("acme", pushedArtifactTenantHeader);
    assertTrue(deploymentManifestBody.contains("tenantId: acme"));
  }

  @Test
  void a_failed_artifact_push_short_circuits_before_submitting_the_deployment() throws IOException {
    artifactResponseStatus = 500;
    Path jar = tempDir.resolve("pause.jar");
    Files.write(jar, "bytes".getBytes(StandardCharsets.UTF_8));

    int status =
        client()
            .trySubmitDeployment(
                "my-deployment", "com.gimle.ragnarok.pause", "1.0.0", jar, 1, Optional.empty());

    assertEquals(500, status);
    assertEquals(List.of("/artifacts/com.gimle.ragnarok.pause/1.0.0"), requestedPaths);
  }
}
