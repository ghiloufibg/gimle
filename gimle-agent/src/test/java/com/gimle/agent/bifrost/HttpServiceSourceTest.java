package com.gimle.agent.bifrost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleClusterException;
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
 * {@link HttpServiceSource} against a stub HTTP server serving exactly the control plane's
 * documented {@code GET /services} / {@code GET /services/{name}/endpoints} contract.
 */
class HttpServiceSourceTest {

  private HttpServer controlPlaneStub;

  @AfterEach
  void tearDown() {
    if (controlPlaneStub != null) {
      controlPlaneStub.stop(0);
    }
  }

  private URI startStub(String path, int status, String body) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        path,
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
          }
          byte[] response = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    controlPlaneStub = server;
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Test
  @Timeout(15)
  void lists_services_with_their_tenant_and_deployment_names_from_the_services_endpoint()
      throws Exception {
    // GET /services answers an array of Service JSON objects (ApiServer#serviceToJson): "name"
    // always present, "tenantId" present only for a tenant-scoped Service, "deploymentNames"
    // always a present (possibly empty) array.
    URI baseUrl =
        startStub(
            "/services",
            200,
            """
            [{"name":"orders","tenantId":"acme","deploymentNames":["orders-service"],
              "port":8080,"targetPort":8080},
             {"name":"payments","deploymentNames":[],"port":9090,"targetPort":9090}]
            """);
    HttpServiceSource source = new HttpServiceSource(HttpClient.newHttpClient(), baseUrl);

    List<ServiceSummary> services = source.listServices();

    assertEquals(2, services.size());
    ServiceSummary orders = services.get(0);
    assertEquals("orders", orders.name());
    assertEquals(Optional.of("acme"), orders.tenantId());
    assertEquals(Set.of("orders-service"), orders.deploymentNames());
    ServiceSummary payments = services.get(1);
    assertEquals("payments", payments.name());
    assertEquals(Optional.empty(), payments.tenantId());
    assertEquals(Set.of(), payments.deploymentNames());
  }

  @Test
  @Timeout(15)
  void fetches_and_parses_endpoints_for_a_service() throws Exception {
    URI baseUrl =
        startStub(
            "/services/orders/endpoints",
            200,
            "{\"name\": \"orders\", \"port\": 8080, \"targetPort\": 8080,"
                + " \"endpoints\": [{\"host\": \"10.0.0.5\", \"port\": 51234}]}");
    HttpServiceSource source = new HttpServiceSource(HttpClient.newHttpClient(), baseUrl);

    Optional<ServiceEndpoints> result = source.fetchEndpoints("orders");

    assertTrue(result.isPresent());
    ServiceEndpoints endpoints = result.get();
    assertEquals("orders", endpoints.name());
    assertEquals(8080, endpoints.port());
    assertEquals(8080, endpoints.targetPort());
    assertEquals(List.of(new ServiceEndpoint("10.0.0.5", 51234)), endpoints.endpoints());
  }

  @Test
  @Timeout(15)
  void a_404_for_endpoints_is_treated_as_the_service_no_longer_existing() throws Exception {
    URI baseUrl = startStub("/services/gone/endpoints", 404, "not found");
    HttpServiceSource source = new HttpServiceSource(HttpClient.newHttpClient(), baseUrl);

    Optional<ServiceEndpoints> result = source.fetchEndpoints("gone");

    assertTrue(result.isEmpty());
  }

  @Test
  @Timeout(15)
  void an_unexpected_status_from_the_services_list_throws() throws Exception {
    URI baseUrl = startStub("/services", 500, "boom");
    HttpServiceSource source = new HttpServiceSource(HttpClient.newHttpClient(), baseUrl);

    assertThrows(GimleClusterException.class, source::listServices);
  }
}
