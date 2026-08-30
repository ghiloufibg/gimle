package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The Galdr custom-kind surface end to end over a real loopback HTTP connection -- {@code
 * /kinddefinitions*} and {@code /resources/*} -- covering the design's admission contract: prefix
 * normalization with its warning, schema-validated-and-defaulted instance puts, the unknown-kind
 * catalog error, scope enforcement both ways, identical-re-apply as a no-op, status puts that never
 * bump generation, definition re-PUT revalidation (409 + violator list) and default backfill,
 * delete-while-instances-exist refusal, the qualified audit rows, and both record kinds surviving a
 * full store restart.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-controlplane-api-server-http")
class ApiServerCustomKindsTest {

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

  private HttpResponse<String> put(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
  }

  private HttpResponse<String> delete(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE().build());
  }

  private static final String GREETING_DEFINITION =
      """
      kind: KindDefinition
      name: Greeting
      scope: Tenant
      description: "A greeting this cluster should keep saying"
      names:
        plural: greetings
        shortNames: [gr]
      schema:
        fields:
          - name: message
            type: string
            required: true
          - name: repeat
            type: int
            default: 1
            min: 1
            max: 100
          - name: tone
            type: enum
            values: [friendly, formal]
            default: friendly
      printColumns:
        - name: MESSAGE
          path: spec.message
      """;

  private static final String HELLO_INSTANCE =
      """
      kind: custom.Greeting
      name: hello-world
      tenantId: team-a
      spec:
        message: "hello"
        repeat: 3
      """;

  private void defineGreeting() throws Exception {
    assertEquals(200, put("/kinddefinitions/Greeting", GREETING_DEFINITION).statusCode());
  }

  // ---- the walkthrough, end to end ----

  @Test
  @Timeout(20)
  void defines_a_kind_with_prefix_normalization_and_applies_a_defaulted_instance()
      throws Exception {
    HttpResponse<String> define = put("/kinddefinitions/Greeting", GREETING_DEFINITION);
    assertEquals(200, define.statusCode());
    List<String> warnings = define.headers().allValues("X-Gimle-Warning");
    assertTrue(
        warnings.stream().anyMatch(w -> w.contains("stored as 'custom.Greeting'")),
        "prefix normalization must be announced back to the submitter: " + warnings);

    List<Map<String, Object>> catalog =
        Json.asObjectList(Json.parse(get("/kinddefinitions").body()));
    assertEquals(1, catalog.size());
    assertEquals("custom.Greeting", catalog.get(0).get("kindName"));
    assertEquals("Tenant", catalog.get(0).get("scope"));

    // The stored, prefixed name and the unprefixed submission both address the definition.
    assertEquals(200, get("/kinddefinitions/custom.Greeting").statusCode());
    assertEquals(200, get("/kinddefinitions/Greeting").statusCode());

    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());
    Map<String, Object> resource =
        Json.asObject(Json.parse(get("/resources/custom.Greeting/hello-world").body()));
    assertEquals("custom.Greeting", resource.get("kind"));
    assertEquals("team-a", resource.get("tenantId"));
    assertEquals(1, ((Number) resource.get("generation")).intValue());
    Map<String, Object> spec = Json.asObject(resource.get("spec"));
    assertEquals("hello", spec.get("message"));
    assertEquals(3, ((Number) spec.get("repeat")).intValue());
    // The defaulted field is persisted into the stored spec, not re-derived at read time.
    assertEquals("friendly", spec.get("tone"));
    assertNull(resource.get("status"));

    List<Map<String, Object>> listed =
        Json.asObjectList(Json.parse(get("/resources/custom.Greeting").body()));
    assertEquals(1, listed.size());
    assertEquals("hello-world", listed.get(0).get("name"));
  }

  @Test
  @Timeout(20)
  void rejects_an_unknown_spec_field_naming_it_rather_than_pruning() throws Exception {
    defineGreeting();
    HttpResponse<String> response =
        put(
            "/resources/custom.Greeting/typo",
            """
            kind: custom.Greeting
            name: typo
            tenantId: team-a
            spec:
              message: "hi"
              mesage: "typo"
            """);
    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("spec.mesage: unknown field"), response.body());
  }

  @Test
  @Timeout(20)
  void enforces_tenant_scope_in_both_directions() throws Exception {
    defineGreeting();
    HttpResponse<String> missingTenant =
        put(
            "/resources/custom.Greeting/untenanted",
            """
            kind: custom.Greeting
            name: untenanted
            spec:
              message: "hi"
            """);
    assertEquals(400, missingTenant.statusCode());
    assertTrue(missingTenant.body().contains("Tenant-scoped"), missingTenant.body());

    assertEquals(
        200,
        put(
                "/kinddefinitions/Flag",
                """
                kind: KindDefinition
                name: Flag
                scope: Cluster
                schema:
                  fields:
                    - name: enabled
                      type: bool
                """)
            .statusCode());
    HttpResponse<String> tenanted =
        put(
            "/resources/custom.Flag/dark-mode",
            """
            kind: custom.Flag
            name: dark-mode
            tenantId: team-a
            spec:
              enabled: true
            """);
    assertEquals(400, tenanted.statusCode());
    assertTrue(tenanted.body().contains("Cluster-scoped"), tenanted.body());
  }

  @Test
  @Timeout(20)
  void an_unknown_kind_is_a_400_carrying_the_catalog_on_every_surface() throws Exception {
    defineGreeting();
    HttpResponse<String> apply =
        put(
            "/resources/custom.Greetng/hello",
            """
            kind: custom.Greetng
            name: hello
            tenantId: team-a
            spec: {}
            """);
    assertEquals(400, apply.statusCode());
    assertTrue(apply.body().contains("unknown kind 'custom.Greetng'"), apply.body());
    assertTrue(apply.body().contains("custom.Greeting"), apply.body());

    HttpResponse<String> read = get("/resources/custom.Greetng/hello");
    assertEquals(400, read.statusCode());
    assertTrue(read.body().contains("defined kinds: custom.Greeting"), read.body());
  }

  @Test
  @Timeout(20)
  void an_identical_re_apply_is_a_no_op_and_a_real_change_bumps_the_generation() throws Exception {
    defineGreeting();
    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());
    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());
    Map<String, Object> afterIdentical =
        Json.asObject(Json.parse(get("/resources/custom.Greeting/hello-world").body()));
    assertEquals(1, ((Number) afterIdentical.get("generation")).intValue());

    assertEquals(
        200,
        put(
                "/resources/custom.Greeting/hello-world",
                HELLO_INSTANCE.replace("repeat: 3", "repeat: 5"))
            .statusCode());
    Map<String, Object> afterChange =
        Json.asObject(Json.parse(get("/resources/custom.Greeting/hello-world").body()));
    assertEquals(2, ((Number) afterChange.get("generation")).intValue());
  }

  @Test
  @Timeout(20)
  void a_status_put_lands_without_bumping_the_generation_and_404s_for_a_missing_instance()
      throws Exception {
    defineGreeting();
    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());

    assertEquals(
        200,
        put(
                "/resources/custom.Greeting/hello-world/status?tenant=team-a",
                "{\"timesSaid\":3,\"observedGeneration\":1}")
            .statusCode());
    Map<String, Object> resource =
        Json.asObject(Json.parse(get("/resources/custom.Greeting/hello-world").body()));
    assertEquals(1, ((Number) resource.get("generation")).intValue());
    Map<String, Object> status = Json.asObject(resource.get("status"));
    assertEquals(3, ((Number) status.get("timesSaid")).intValue());

    HttpResponse<String> missing =
        put("/resources/custom.Greeting/nobody/status?tenant=team-a", "{\"timesSaid\":1}");
    assertEquals(404, missing.statusCode());
  }

  @Test
  @Timeout(20)
  void a_breaking_definition_re_put_is_refused_with_the_violator_list() throws Exception {
    defineGreeting();
    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());

    HttpResponse<String> breaking =
        put("/kinddefinitions/Greeting", GREETING_DEFINITION.replace("max: 100", "max: 2"));
    assertEquals(409, breaking.statusCode());
    assertTrue(breaking.body().contains("hello-world"), breaking.body());

    // The old schema still governs -- a new instance at repeat 50 (legal under the old max)
    // still admits, proving the refused update never partially landed.
    assertEquals(
        200,
        put(
                "/resources/custom.Greeting/second",
                HELLO_INSTANCE.replace("hello-world", "second").replace("repeat: 3", "repeat: 50"))
            .statusCode());
  }

  @Test
  @Timeout(20)
  void a_compatible_definition_re_put_backfills_new_defaults_into_stored_instances()
      throws Exception {
    defineGreeting();
    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());

    String withSuffix =
        GREETING_DEFINITION.replace(
            "printColumns:",
            """
                - name: suffix
                  type: string
                  default: "!"
            printColumns:""");
    assertEquals(200, put("/kinddefinitions/Greeting", withSuffix).statusCode());

    Map<String, Object> resource =
        Json.asObject(Json.parse(get("/resources/custom.Greeting/hello-world").body()));
    Map<String, Object> spec = Json.asObject(resource.get("spec"));
    assertEquals("!", spec.get("suffix"));
    // A backfill is a real spec change: the generation moves, so an operator's
    // observedGeneration comparison notices the new shape.
    assertEquals(2, ((Number) resource.get("generation")).intValue());

    Map<String, Object> definition =
        Json.asObject(Json.parse(get("/kinddefinitions/custom.Greeting").body()));
    assertEquals(2, ((Number) definition.get("generation")).intValue());
  }

  @Test
  @Timeout(20)
  void an_identical_definition_re_put_is_a_no_op() throws Exception {
    defineGreeting();
    assertEquals(200, put("/kinddefinitions/Greeting", GREETING_DEFINITION).statusCode());
    Map<String, Object> definition =
        Json.asObject(Json.parse(get("/kinddefinitions/custom.Greeting").body()));
    assertEquals(1, ((Number) definition.get("generation")).intValue());
  }

  @Test
  @Timeout(20)
  void deleting_a_definition_is_refused_while_instances_exist_and_clean_afterwards()
      throws Exception {
    defineGreeting();
    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());

    HttpResponse<String> refused = delete("/kinddefinitions/custom.Greeting");
    assertEquals(409, refused.statusCode());
    assertTrue(refused.body().contains("instance"), refused.body());

    assertEquals(200, delete("/resources/custom.Greeting/hello-world?tenant=team-a").statusCode());
    assertEquals(200, delete("/kinddefinitions/custom.Greeting").statusCode());

    HttpResponse<String> orphanApply =
        put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE);
    assertEquals(400, orphanApply.statusCode());
    assertTrue(orphanApply.body().contains("unknown kind 'custom.Greeting'"), orphanApply.body());
  }

  @Test
  @Timeout(20)
  void a_second_definition_may_not_claim_an_already_declared_plural_or_short_name()
      throws Exception {
    defineGreeting();
    HttpResponse<String> collision =
        put(
            "/kinddefinitions/Salutation",
            """
            kind: KindDefinition
            name: Salutation
            scope: Tenant
            names:
              plural: greetings
            """);
    assertEquals(409, collision.statusCode());
    assertTrue(collision.body().contains("greetings"), collision.body());
    assertTrue(collision.body().contains("custom.Greeting"), collision.body());
  }

  @Test
  @Timeout(20)
  void an_oversized_spec_is_rejected_at_admission_naming_the_cap() throws Exception {
    defineGreeting();
    HttpResponse<String> oversized =
        put(
            "/resources/custom.Greeting/huge",
            """
            kind: custom.Greeting
            name: huge
            tenantId: team-a
            spec:
              message: "%s"
            """
                .formatted("x".repeat(300_000)));
    assertEquals(400, oversized.statusCode());
    assertTrue(oversized.body().contains("cap"), oversized.body());
  }

  @Test
  @Timeout(20)
  void audit_rows_carry_the_qualified_custom_resource_kind_string() throws Exception {
    defineGreeting();
    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());

    List<Map<String, Object>> rows =
        Json.asObjectList(Json.parse(get("/audit?resource=CustomResource:custom.Greeting").body()));
    assertFalse(rows.isEmpty(), "the instance put must have produced a qualified audit row");
    assertEquals("WRITE", rows.get(0).get("verb"));
    assertEquals("hello-world", rows.get(0).get("targetId"));

    List<Map<String, Object>> definitionRows =
        Json.asObjectList(Json.parse(get("/audit?resource=KIND_DEFINITION").body()));
    assertFalse(definitionRows.isEmpty(), "the definition put must have been audited too");
  }

  @Test
  @Timeout(30)
  void definitions_instances_and_statuses_survive_a_full_store_restart() throws Exception {
    defineGreeting();
    assertEquals(200, put("/resources/custom.Greeting/hello-world", HELLO_INSTANCE).statusCode());
    assertEquals(
        200,
        put("/resources/custom.Greeting/hello-world/status?tenant=team-a", "{\"timesSaid\":3}")
            .statusCode());

    server.close();
    inProcessFafnir.close();
    inProcessStore.close();
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
    inProcessFafnir =
        InProcessFafnir.start(inProcessStore.client(), tempDir.resolve("keys/secret.key"));
    server = new ApiServer(inProcessStore.client(), 0, inProcessFafnir.client());
    server.start();
    baseUrl = "http://localhost:" + server.port();

    List<Map<String, Object>> catalog =
        Json.asObjectList(Json.parse(get("/kinddefinitions").body()));
    assertEquals(1, catalog.size());
    assertEquals("custom.Greeting", catalog.get(0).get("kindName"));

    Map<String, Object> resource =
        Json.asObject(Json.parse(get("/resources/custom.Greeting/hello-world").body()));
    assertEquals(1, ((Number) resource.get("generation")).intValue());
    assertEquals("hello", Json.asObject(resource.get("spec")).get("message"));
    assertEquals(3, ((Number) Json.asObject(resource.get("status")).get("timesSaid")).intValue());
  }
}
