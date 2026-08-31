package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code GET /backup}/{@code PUT /restore} over a real loopback HTTP connection -- the operator
 * surface {@link ApiServer#handleBackup}/{@link ApiServer#handleRestore} proxy to {@code
 * StoreClient#getSnapshot()}/{@code #restore(byte[])}. Proves the full round trip a real {@code
 * gimle backup create}/{@code restore} invocation would make: bytes taken from a live cluster
 * restore back into the exact prior state, discarding whatever was written afterward.
 */
@ResourceLock("gimle-controlplane-api-server-http")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ApiServerBackupTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;
  private InProcessFafnir inProcessFafnir;
  private ApiServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
  }

  private static String deploymentYaml(String name) {
    return """
        kind: Deployment
        name: %s
        module:
          name: com.gimle.example.orders
          version: 1.0.0
        artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
        replicas: 1
        """
        .formatted(name);
  }

  private HttpResponse<String> putDeployment(String name) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml(name)))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private int getDeploymentStatus(String name) throws Exception {
    return client
        .send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name)).GET().build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void a_restored_backup_discards_every_write_made_after_it_was_taken() throws Exception {
    assertEquals(200, putDeployment("pre-backup").statusCode());

    HttpResponse<byte[]> backup =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/backup")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, backup.statusCode());
    assertTrue(backup.body().length > 0, "backup body must not be empty");

    assertEquals(200, putDeployment("post-backup").statusCode());
    assertEquals(200, getDeploymentStatus("post-backup"));

    HttpResponse<String> restore =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/restore"))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(backup.body()))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, restore.statusCode());

    assertEquals(200, getDeploymentStatus("pre-backup"));
    assertEquals(404, getDeploymentStatus("post-backup"));
  }

  @Test
  void restore_rejects_a_body_that_is_not_a_real_backup() throws Exception {
    HttpResponse<String> restore =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/restore"))
                .PUT(HttpRequest.BodyPublishers.ofString("not a backup"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(400, restore.statusCode());
  }

  @Test
  void backup_rejects_a_non_get_method() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/backup"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(405, response.statusCode());
  }
}
