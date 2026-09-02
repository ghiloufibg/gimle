package com.gimle.skald.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link HttpServiceCatalogClient} against a stub HTTP server serving exactly the control plane's
 * documented {@code GET /services} / {@code GET /services/{name}/endpoints} contract -- the same
 * shape {@code gimle-agent}'s {@code HttpServiceSourceTest} verifies for the identical API.
 */
class HttpServiceCatalogClientTest {

  private HttpServer controlPlaneStub;

  @AfterEach
  void tearDown() {
    if (controlPlaneStub != null) {
      controlPlaneStub.stop(0);
    }
  }

  /** The raw query string of the last request the stub answered, or null if it carried none. */
  private volatile String lastQuery;

  private URI startStub(String path, int status, String body) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        path,
        exchange -> {
          lastQuery = exchange.getRequestURI().getQuery();
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
    // baseUri must end in a trailing slash -- HttpServiceCatalogClient's own constructor javadoc.
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  @Test
  @Timeout(15)
  void lists_services_from_the_services_endpoint() throws Exception {
    // GET /services answers an array of Service JSON objects (ApiServer#serviceToJson), not bare
    // name strings -- the exact bug this test exists to catch.
    URI baseUri =
        startStub(
            "/services",
            200,
            """
            [{"name":"orders","deploymentNames":["orders-service"],"port":8080,"targetPort":8080},
             {"name":"payments","tenantId":"acme","deploymentNames":["payments-service"],
              "port":9090,"targetPort":9090}]
            """);
    HttpServiceCatalogClient client =
        new HttpServiceCatalogClient(HttpClient.newHttpClient(), baseUri);

    List<ServiceListing> listings = client.listServices();

    // ADD-6: tenantId travels alongside the bare name -- without it, every tenant-scoped
    // Service's endpoints get cached under a key no DNS query for it can ever derive.
    assertEquals(
        List.of(
            new ServiceListing("orders", Optional.empty()),
            new ServiceListing("payments", Optional.of("acme"))),
        listings);
  }

  @Test
  @Timeout(15)
  void fetches_and_parses_endpoints_for_a_service() throws Exception {
    URI baseUri =
        startStub(
            "/services/orders/endpoints",
            200,
            "{\"name\": \"orders\", \"port\": 8080, \"targetPort\": 8080,"
                + " \"endpoints\": [{\"host\": \"10.0.0.5\", \"port\": 51234}]}");
    HttpServiceCatalogClient client =
        new HttpServiceCatalogClient(HttpClient.newHttpClient(), baseUri);

    Optional<ServiceEndpoints> result = client.fetchEndpoints(untenanted("orders"));

    assertTrue(result.isPresent());
    ServiceEndpoints endpoints = result.get();
    assertEquals("orders", endpoints.name());
    assertEquals(8080, endpoints.port());
    assertEquals(OptionalInt.of(8080), endpoints.targetPort());
    assertEquals(List.of(new HostPort("10.0.0.5", 51234)), endpoints.endpoints());
  }

  @Test
  @Timeout(15)
  void a_404_for_endpoints_is_treated_as_the_service_no_longer_existing() throws Exception {
    URI baseUri = startStub("/services/gone/endpoints", 404, "not found");
    HttpServiceCatalogClient client =
        new HttpServiceCatalogClient(HttpClient.newHttpClient(), baseUri);

    Optional<ServiceEndpoints> result = client.fetchEndpoints(untenanted("gone"));

    assertTrue(result.isEmpty());
  }

  @Test
  @Timeout(15)
  void an_unexpected_status_from_the_services_list_throws() throws Exception {
    URI baseUri = startStub("/services", 500, "boom");
    HttpServiceCatalogClient client =
        new HttpServiceCatalogClient(HttpClient.newHttpClient(), baseUri);

    assertThrows(IOException.class, client::listServices);
  }

  /**
   * The control plane keys a Service by {@code (tenant, name)}, so a tenant-scoped Service asked
   * for by bare name answers 404 -- which this client reports as "gone". Skald reads the tenant off
   * the catalog listing, so it must carry it into the endpoints read rather than dropping it.
   */
  @Test
  @Timeout(15)
  void the_listings_own_tenant_rides_the_endpoints_read() throws Exception {
    URI baseUri =
        startStub(
            "/services/orders/endpoints",
            200,
            "{\"name\": \"orders\", \"port\": 8080, \"endpoints\": []}");
    HttpServiceCatalogClient client =
        new HttpServiceCatalogClient(HttpClient.newHttpClient(), baseUri);

    client.fetchEndpoints(new ServiceListing("orders", Optional.of("acme")));

    assertEquals("tenant=acme", lastQuery);
  }

  @Test
  @Timeout(15)
  void an_untenanted_service_asks_for_no_tenant_at_all() throws Exception {
    URI baseUri =
        startStub(
            "/services/orders/endpoints",
            200,
            "{\"name\": \"orders\", \"port\": 8080, \"endpoints\": []}");
    HttpServiceCatalogClient client =
        new HttpServiceCatalogClient(HttpClient.newHttpClient(), baseUri);

    client.fetchEndpoints(untenanted("orders"));

    assertNull(lastQuery);
  }

  private static ServiceListing untenanted(String name) {
    return new ServiceListing(name, Optional.empty());
  }
}
