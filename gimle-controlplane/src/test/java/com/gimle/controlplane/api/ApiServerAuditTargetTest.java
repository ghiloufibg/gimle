package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.AuditEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code V3-M5}: a Deployment write/delete's own audit row must carry the deployment's own name as
 * its {@code targetId}, not just the enclosing tenant -- the schema already supports this (a
 * bootstrap-token node join's own {@code APPROVE} row already records the joining CSR's subject as
 * its target), but {@code dispatchResourceRequest}'s PUT/DELETE branches omitted it, leaving two
 * independently-created, differently-named deployments in the same tenant indistinguishable from
 * the audit trail alone. See {@code HumanOperatorCsrTest} for the identical gap on the certificate-
 * approval side.
 */
class ApiServerAuditTargetTest {

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

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString(deploymentYaml(name)))
            .build());
  }

  private List<AuditEvent> deploymentAuditEventsFor(String name, String verb) {
    return inProcessStore
        .client()
        .listAuditEvents(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
        .stream()
        .filter(
            e ->
                e.resourceKind().equals("DEPLOYMENT")
                    && e.verb().equals(verb)
                    && e.targetId().equals(Optional.of(name)))
        .toList();
  }

  /**
   * {@code V3-M5}: a Deployment PUT's own audit row must carry the deployment's own name as its
   * {@code targetId} -- before the fix, every Deployment {@code WRITE} row carried only the tenant,
   * so two independently-created, differently-named deployments in the same tenant produced audit
   * rows indistinguishable from each other.
   */
  @Test
  void a_deployment_put_records_its_own_name_as_the_audit_target() throws Exception {
    assertEquals(200, putDeployment("audit-target-put-deployment").statusCode());

    List<AuditEvent> events = deploymentAuditEventsFor("audit-target-put-deployment", "WRITE");
    assertEquals(1, events.size());
    assertEquals(Optional.of("audit-target-put-deployment"), events.get(0).targetId());
  }

  /**
   * {@code V3-M5}: the identical gap on the delete side -- a Deployment DELETE's own audit row must
   * also carry the deployment's own name, not just its tenant.
   */
  @Test
  void a_deployment_delete_records_its_own_name_as_the_audit_target() throws Exception {
    assertEquals(200, putDeployment("audit-target-delete-deployment").statusCode());

    HttpResponse<String> delete =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/deployments/audit-target-delete-deployment"))
                .DELETE()
                .build());
    assertEquals(200, delete.statusCode());

    List<AuditEvent> events = deploymentAuditEventsFor("audit-target-delete-deployment", "DELETE");
    assertEquals(1, events.size());
    assertEquals(Optional.of("audit-target-delete-deployment"), events.get(0).targetId());
  }
}
