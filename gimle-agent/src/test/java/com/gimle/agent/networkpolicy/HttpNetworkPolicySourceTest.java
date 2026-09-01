package com.gimle.agent.networkpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.tenant.NetworkPolicyRule;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link HttpNetworkPolicySource} against a stub HTTP server serving both halves of the control
 * plane's documented contract: {@code GET /networkpolicies} ({@code
 * ApiServer#networkPolicyToJson}'s shape, an always-present possibly-empty {@code deploymentNames}
 * array) and {@code GET /networkpostures} ({@code {"tenantId","isolationPosture"}} per tenant).
 * Both are stubbed on every server this class starts, because one poll fetches both.
 */
class HttpNetworkPolicySourceTest {

  private HttpServer controlPlaneStub;

  @AfterEach
  void tearDown() {
    if (controlPlaneStub != null) {
      controlPlaneStub.stop(0);
    }
  }

  private URI startStub(int status, String policiesBody) throws IOException {
    return startStub(status, policiesBody, "[]");
  }

  private URI startStub(int status, String policiesBody, String posturesBody) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/networkpolicies", respondWith(status, policiesBody));
    // A poll fetches the declared postures alongside the policies, so a stub that only answered
    // /networkpolicies would fail every scenario here on the second request rather than on
    // whatever the scenario is actually about. Always 200: the non-200 scenario below drives its
    // failure through /networkpolicies, which is fetched first.
    server.createContext("/networkpostures", respondWith(200, posturesBody));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    controlPlaneStub = server;
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static HttpHandler respondWith(int status, String body) {
    return exchange -> {
      try (InputStream in = exchange.getRequestBody()) {
        in.readAllBytes();
      }
      byte[] response = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    };
  }

  @Test
  @Timeout(15)
  void an_empty_deployment_names_array_is_parsed_as_tenant_wide() throws Exception {
    URI baseUrl =
        startStub(
            200,
            """
            [{"name":"deny-by-default","tenantId":"acme","deploymentNames":[],
              "allowedCallerTenantIds":[]}]
            """);
    HttpNetworkPolicySource source =
        new HttpNetworkPolicySource(HttpClient.newHttpClient(), baseUrl);

    List<NetworkPolicyRule> rules = source.fetchPolicies().rules();

    assertEquals(1, rules.size());
    assertEquals(Optional.empty(), rules.get(0).deploymentNames());
  }

  @Test
  @Timeout(15)
  void a_non_empty_deployment_names_array_is_parsed_as_a_deployment_scoped_rule() throws Exception {
    URI baseUrl =
        startStub(
            200,
            """
            [{"name":"deny-by-default","tenantId":"acme","deploymentNames":["orders"],
              "allowedCallerTenantIds":["partner"]}]
            """);
    HttpNetworkPolicySource source =
        new HttpNetworkPolicySource(HttpClient.newHttpClient(), baseUrl);

    List<NetworkPolicyRule> rules = source.fetchPolicies().rules();

    assertEquals(1, rules.size());
    NetworkPolicyRule rule = rules.get(0);
    assertEquals("deny-by-default", rule.name());
    assertEquals("acme", rule.tenantId());
    assertEquals(Optional.of(Set.of("orders")), rule.deploymentNames());
    assertEquals(Optional.of(Set.of("partner")), rule.allowedCallerTenantIds());
  }

  @Test
  @Timeout(15)
  void only_tenants_declaring_deny_by_default_are_reported_as_closed() throws Exception {
    URI baseUrl =
        startStub(
            200,
            "[]",
            """
            [{"tenantId":"acme","isolationPosture":"DENY_BY_DEFAULT"},
             {"tenantId":"globex","isolationPosture":"OPEN"}]
            """);
    HttpNetworkPolicySource source =
        new HttpNetworkPolicySource(HttpClient.newHttpClient(), baseUrl);

    assertEquals(Set.of("acme"), source.fetchPolicies().denyByDefaultTenantIds());
  }

  @Test
  @Timeout(15)
  void an_unexpected_status_throws() throws Exception {
    URI baseUrl = startStub(500, "boom");
    HttpNetworkPolicySource source =
        new HttpNetworkPolicySource(HttpClient.newHttpClient(), baseUrl);

    assertThrows(GimleClusterException.class, source::fetchPolicies);
  }
}
