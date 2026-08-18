package com.gimle.controlplane.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.mimir.manifest.ServiceSpec;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServiceRegistryTest {

  @Test
  void put_then_get_round_trips_a_spec_by_name() {
    ServiceRegistry registry = new ServiceRegistry();
    ServiceSpec spec = new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    registry.put(spec);

    assertEquals(Optional.of(spec), registry.get("orders"));
  }

  @Test
  void get_of_an_unknown_name_is_empty() {
    ServiceRegistry registry = new ServiceRegistry();
    assertEquals(Optional.empty(), registry.get("nope"));
  }

  @Test
  void list_returns_every_registered_spec() {
    ServiceRegistry registry = new ServiceRegistry();
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    registry.put(new ServiceSpec("catalog", Optional.empty(), Set.of("catalog-service"), 8081));

    assertEquals(2, registry.list().size());
  }

  @Test
  void putting_the_same_name_again_replaces_the_prior_spec() {
    ServiceRegistry registry = new ServiceRegistry();
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service-v2"), 9090));

    assertEquals(1, registry.list().size());
    assertEquals(9090, registry.get("orders").orElseThrow().port());
  }

  @Test
  void removing_a_service_also_removes_its_stored_endpoint_set() {
    ServiceRegistry registry = new ServiceRegistry();
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    registry.putEndpoints("orders", List.of(new ServiceEndpoint("10.0.0.5", 51234)));

    registry.remove("orders");

    assertEquals(Optional.empty(), registry.get("orders"));
    assertTrue(registry.getEndpoints("orders").isEmpty());
  }

  @Test
  void endpoints_for_a_name_never_reconciled_yet_is_an_empty_list_not_an_error() {
    ServiceRegistry registry = new ServiceRegistry();
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    assertTrue(registry.getEndpoints("orders").isEmpty());
  }
}
