package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Real {@link ApiServer} + real {@code java.net.http.HttpClient} on a loopback ephemeral port --
 * the same harness {@link ApiServerTest} already establishes -- covering the {@code /accounts},
 * {@code /roles}, and {@code /rolebindings} routes end to end: none of the three had a dedicated
 * HTTP-level test before. Plaintext mode (no TLS configured), matching {@link ApiServerTest}'s own
 * default posture, so every write here is attributed to the synthetic {@code anonymous} principal
 * and audited unconditionally regardless of RBAC.
 */
class ApiServerAccountManagementTest {

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

  private HttpResponse<String> putAccount(String username, String password) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/" + username))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"password\":\"" + password + "\"}"))
            .build());
  }

  private HttpResponse<String> deleteAccount(String username) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/" + username)).DELETE().build());
  }

  private HttpResponse<String> putRole(String name) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/roles/" + name))
            .PUT(HttpRequest.BodyPublishers.ofString("{\"permissions\":[]}"))
            .build());
  }

  private HttpResponse<String> putRoleBinding(String id, String subject, String roleName)
      throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/rolebindings/" + id))
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"subject\":\"" + subject + "\",\"roleName\":\"" + roleName + "\"}"))
            .build());
  }

  private List<AuditEvent> auditEvents() {
    return inProcessStore
        .client()
        .listAuditEvents(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  // ---- GOV-5: dangling rolebinding on account delete ----

  @Test
  void deleting_an_account_still_referenced_by_a_rolebinding_is_refused_with_a_named_conflict()
      throws Exception {
    assertEquals(200, putAccount("alice", "s3cret-password").statusCode());
    assertEquals(200, putRole("viewer").statusCode());
    assertEquals(200, putRoleBinding("b1", "user:alice", "viewer").statusCode());

    HttpResponse<String> delete = deleteAccount("alice");

    assertEquals(409, delete.statusCode());
    assertTrue(
        delete.body().contains("alice") && delete.body().contains("b1"),
        "conflict message should name both the account and the blocking rolebinding: "
            + delete.body());
    // Refused, not silently no-op'd: the account is still there.
    assertEquals(
        200,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/alice")).GET().build())
            .statusCode());
  }

  @Test
  void deleting_an_account_with_no_rolebinding_succeeds() throws Exception {
    assertEquals(200, putAccount("bob", "s3cret-password").statusCode());

    assertEquals(200, deleteAccount("bob").statusCode());

    assertEquals(
        404,
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/bob")).GET().build())
            .statusCode());
  }

  // ---- GOV-7: ACCOUNT/ROLE/ROLE_BINDING audit entries carry targetId ----

  @Test
  void account_write_and_delete_audit_entries_carry_the_username_as_target_id() throws Exception {
    putAccount("carol", "s3cret-password");
    deleteAccount("carol");

    List<AuditEvent> events = auditEvents();
    List<AuditEvent> accountEvents =
        events.stream().filter(e -> e.resourceKind().equals("ACCOUNT")).toList();
    assertEquals(2, accountEvents.size());
    for (AuditEvent event : accountEvents) {
      assertEquals(Optional.of("carol"), event.targetId());
    }
  }

  @Test
  void role_write_audit_entry_carries_the_role_name_as_target_id() throws Exception {
    putRole("editor");

    List<AuditEvent> roleEvents =
        auditEvents().stream().filter(e -> e.resourceKind().equals("ROLE")).toList();
    assertEquals(1, roleEvents.size());
    assertEquals(Optional.of("editor"), roleEvents.get(0).targetId());
  }

  @Test
  void rolebinding_write_audit_entry_carries_the_binding_id_as_target_id() throws Exception {
    putRole("editor");
    putRoleBinding("binding-1", "user:dave", "editor");

    List<AuditEvent> bindingEvents =
        auditEvents().stream().filter(e -> e.resourceKind().equals("ROLE_BINDING")).toList();
    assertEquals(1, bindingEvents.size());
    assertEquals(Optional.of("binding-1"), bindingEvents.get(0).targetId());
  }
}
