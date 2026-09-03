package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * What the services view is built out of: the Service table itself, and the live endpoint set each
 * Service currently resolves to. The distinction the whole screen rests on -- a Service with no
 * endpoints against a Service whose endpoints nobody could read -- is pinned down here.
 */
class ServiceReaderTest {

  @Test
  void every_service_becomes_a_row_carrying_its_ports_protocol_backing_and_endpoint_count() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList(
                "/services", List.of(service("greeter", null, 8080, 9090, "greeter-provider")))
            .withObject("/services/greeter/endpoints", endpoints(2));

    List<ServiceRow> rows = new ServiceReader(reader).read().services();

    assertEquals(1, rows.size());
    ServiceRow row = rows.getFirst();
    assertEquals("greeter", row.name());
    assertEquals(Optional.empty(), row.tenantId());
    assertEquals(List.of("greeter-provider"), row.deploymentNames());
    assertEquals(8080, row.port());
    assertEquals(OptionalInt.of(9090), row.targetPort());
    assertEquals("TCP", row.protocol());
    assertEquals(OptionalInt.of(2), row.endpointCount());
    assertEquals("READY", row.state());
    assertFalse(row.unresolved());
  }

  @Test
  void a_service_backing_nothing_reads_as_having_no_endpoints_rather_than_as_unknown() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/services", List.of(service("orphan", null, 80, 80, "deployment-that-went")))
            .withObject("/services/orphan/endpoints", endpoints(0));

    ServiceRow row = new ServiceReader(reader).read().services().getFirst();

    assertEquals(OptionalInt.of(0), row.endpointCount());
    assertTrue(row.unresolved());
    assertEquals("NO ENDPOINTS", row.state());
  }

  @Test
  void an_endpoint_response_carrying_no_endpoint_array_leaves_the_count_unknown_not_zero() {
    // Nothing registered for the endpoints path, which is what a route this build cannot read --
    // an error body, a shape it does not recognise -- amounts to. Reading that as zero would
    // invent the one finding this view exists to report.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/services", List.of(service("greeter", null, 8080, 9090, "greeter")));

    ServiceRow row = new ServiceReader(reader).read().services().getFirst();

    assertEquals(OptionalInt.empty(), row.endpointCount());
    assertFalse(row.unresolved());
    assertEquals("UNKNOWN", row.state());
  }

  @Test
  void a_tenant_scoped_service_asks_for_its_endpoints_under_that_tenant() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/services", List.of(service("greeter", "acme", 8080, 9090, "greeter")))
            .withObject("/services/greeter/endpoints?tenant=acme", endpoints(3));

    ServiceRow row = new ServiceReader(reader).read().services().getFirst();

    assertTrue(
        reader.requestedPaths().contains("/services/greeter/endpoints?tenant=acme"),
        "the owning tenant must travel with the endpoints read: " + reader.requestedPaths());
    assertEquals(Optional.of("acme"), row.tenantId());
    assertEquals(OptionalInt.of(3), row.endpointCount());
  }

  @Test
  void a_service_missing_fields_degrades_only_the_columns_they_feed() {
    Map<String, Object> partial = new LinkedHashMap<>();
    partial.put("name", "half-answered");
    partial.put("port", "not-a-number");
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/services", List.of(partial))
            .withObject("/services/half-answered/endpoints", endpoints(1));

    ServiceRow row = new ServiceReader(reader).read().services().getFirst();

    assertEquals("half-answered", row.name());
    assertEquals(0, row.port());
    assertEquals(OptionalInt.empty(), row.targetPort());
    assertEquals(List.of(), row.deploymentNames());
    assertEquals(
        "TCP", row.protocol(), "an unstated protocol is what declaring none already means");
    assertEquals(OptionalInt.of(1), row.endpointCount());
  }

  @Test
  void a_service_with_no_name_at_all_is_dropped_rather_than_drawn_as_a_nameless_row() {
    FakeClusterReader reader =
        new FakeClusterReader().withList("/services", List.of(Map.of("port", 80)));

    assertEquals(List.of(), new ServiceReader(reader).read().services());
  }

  @Test
  void an_external_name_service_keeps_the_host_it_aliases_and_names_no_deployments() {
    Map<String, Object> external = service("legacy-billing", null, 443, 443);
    external.put("externalName", "billing.example.com");
    FakeClusterReader reader =
        new FakeClusterReader()
            .withList("/services", List.of(external))
            .withObject("/services/legacy-billing/endpoints", endpoints(1));

    ServiceRow row = new ServiceReader(reader).read().services().getFirst();

    assertTrue(row.external());
    assertEquals(Optional.of("billing.example.com"), row.externalName());
    assertEquals(List.of(), row.deploymentNames());
  }

  private static Map<String, Object> service(
      final String name,
      final String tenantId,
      final int port,
      final int targetPort,
      final String... deploymentNames) {
    Map<String, Object> service = new LinkedHashMap<>();
    service.put("name", name);
    if (tenantId != null) {
      service.put("tenantId", tenantId);
    }
    service.put("deploymentNames", List.of(deploymentNames));
    service.put("port", port);
    service.put("targetPort", targetPort);
    service.put("sessionAffinity", false);
    service.put("protocol", "TCP");
    return service;
  }

  private static Map<String, Object> endpoints(final int count) {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("host", "10.0.0." + (index + 1));
      entry.put("port", 9090);
      entry.put("nodeId", "node-" + index);
      entries.add(entry);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("name", "service");
    response.put("port", 8080);
    response.put("targetPort", 9090);
    response.put("sessionAffinity", false);
    response.put("protocol", "TCP");
    response.put("endpoints", entries);
    return response;
  }
}
