package com.gimle.controlplane.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.controlplane.testsupport.InProcessFafnir;
import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.core.tenant.Tenant;
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

/**
 * {@code hilmir}'s own release-ledger tenant ({@link Tenant#HILMIR_BOOKKEEPING_TENANT_ID}) must be
 * creatable under plaintext transport even once a cluster already has a real, operator-created
 * tenant of its own -- every {@code hilmir} release verb bootstraps it on first use, and it is a
 * platform-reserved bookkeeping tenant, not the kind of second tenant plaintext's own
 * single-real-tenant rule exists to refuse. That rule itself must still refuse a genuine second
 * real tenant exactly as before.
 */
class ApiServerHilmirBookkeepingTenantTest {

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

  private static String tenantJson() {
    return """
        {"quota":{"maxMemoryBytes":0,"maxCpuMillicores":0,"maxInstances":0}}
        """;
  }

  private HttpResponse<String> putTenant(String id) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + id))
            .PUT(HttpRequest.BodyPublishers.ofString(tenantJson()))
            .build());
  }

  @Test
  void creating_the_hilmir_bookkeeping_tenant_succeeds_even_with_a_real_tenant_already_present()
      throws Exception {
    // A cluster already carrying one real, operator-created tenant -- reproducing every hilmir
    // release verb's own M37 repro: an already-multi-tenant plaintext cluster, before hilmir's own
    // bookkeeping tenant has ever been bootstrapped on it.
    assertEquals(200, putTenant("acme").statusCode());

    HttpResponse<String> bookkeepingTenantPut = putTenant(Tenant.HILMIR_BOOKKEEPING_TENANT_ID);
    assertEquals(
        200,
        bookkeepingTenantPut.statusCode(),
        "hilmir's own reserved bookkeeping tenant must not trip plaintext's single-real-tenant"
            + " rule");

    HttpResponse<String> confirmCreated =
        send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/tenants/" + Tenant.HILMIR_BOOKKEEPING_TENANT_ID))
                .build());
    assertEquals(200, confirmCreated.statusCode());
  }

  @Test
  void re_bootstrapping_the_already_present_hilmir_bookkeeping_tenant_still_succeeds()
      throws Exception {
    // Every later hilmir release verb re-issues the identical bootstrap PUT -- must remain a no-op
    // success, not merely a one-time exemption for the very first creation.
    assertEquals(200, putTenant("acme").statusCode());
    assertEquals(200, putTenant(Tenant.HILMIR_BOOKKEEPING_TENANT_ID).statusCode());
    assertEquals(200, putTenant(Tenant.HILMIR_BOOKKEEPING_TENANT_ID).statusCode());
  }

  @Test
  void a_genuine_second_real_tenant_is_still_refused_under_plaintext() throws Exception {
    // The exemption is specific to the reserved bookkeeping tenant -- an actual second
    // operator-created tenant must still be refused, proving the fix didn't weaken the rule
    // itself.
    assertEquals(200, putTenant("acme").statusCode());
    assertEquals(200, putTenant(Tenant.HILMIR_BOOKKEEPING_TENANT_ID).statusCode());

    HttpResponse<String> refused = putTenant("widgets");
    assertEquals(403, refused.statusCode());

    HttpResponse<String> confirmNeverCreated =
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/widgets")).build());
    assertEquals(404, confirmNeverCreated.statusCode());
  }

  @Test
  void the_hilmir_bookkeeping_tenant_does_not_itself_count_as_a_real_tenant_toward_the_limit()
      throws Exception {
    // Bootstrapping hilmir's own tenant first must not itself use up plaintext's "one real
    // tenant" allowance -- an operator's own first real tenant must still succeed afterward.
    assertEquals(200, putTenant(Tenant.HILMIR_BOOKKEEPING_TENANT_ID).statusCode());
    assertEquals(200, putTenant("acme").statusCode());
  }
}
