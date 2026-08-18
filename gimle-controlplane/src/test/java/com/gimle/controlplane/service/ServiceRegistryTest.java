package com.gimle.controlplane.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Backed by a real {@code gimle-mimir} store (via {@link InProcessStore}), the same fixture {@code
 * ApiServerServicesTest} uses -- proves {@link ServiceRegistry} actually persists through {@code
 * StoreClient} rather than an in-memory map, not just that its own facade methods round-trip
 * against themselves.
 */
class ServiceRegistryTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private InProcessStore inProcessStore;

  @BeforeEach
  void startStore() throws IOException {
    inProcessStore = InProcessStore.start(tempDir.resolve("store"));
  }

  @AfterEach
  void stopStore() {
    inProcessStore.close();
  }

  private ServiceRegistry registry() {
    return new ServiceRegistry(inProcessStore.client(), inProcessStore.client());
  }

  @Test
  void put_then_get_round_trips_a_spec_by_name() {
    ServiceRegistry registry = registry();
    ServiceSpec spec = new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    registry.put(spec);

    assertEquals(Optional.of(spec), registry.get("orders"));
  }

  @Test
  void get_of_an_unknown_name_is_empty() {
    ServiceRegistry registry = registry();
    assertEquals(Optional.empty(), registry.get("nope"));
  }

  @Test
  void list_returns_every_registered_spec() {
    ServiceRegistry registry = registry();
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    registry.put(new ServiceSpec("catalog", Optional.empty(), Set.of("catalog-service"), 8081));

    assertEquals(2, registry.list().size());
  }

  @Test
  void putting_the_same_name_again_replaces_the_prior_spec() {
    ServiceRegistry registry = registry();
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service-v2"), 9090));

    assertEquals(1, registry.list().size());
    assertEquals(9090, registry.get("orders").orElseThrow().port());
  }

  @Test
  void removing_a_service_also_removes_its_stored_endpoint_set() {
    ServiceRegistry registry = registry();
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));
    registry.putEndpoints("orders", List.of(new ServiceEndpoint("10.0.0.5", 51234)));

    registry.remove("orders");

    assertEquals(Optional.empty(), registry.get("orders"));
    assertTrue(registry.getEndpoints("orders").isEmpty());
  }

  @Test
  void endpoints_for_a_name_never_reconciled_yet_is_an_empty_list_not_an_error() {
    ServiceRegistry registry = registry();
    registry.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

    assertTrue(registry.getEndpoints("orders").isEmpty());
  }

  /**
   * The actual bug this class exists to fix: a Service put through one {@code ServiceRegistry}
   * (standing in for one control-plane replica) must be visible through a second, independent
   * {@code ServiceRegistry} backed by its own {@code StoreClient} against the same store cluster --
   * not just visible to itself.
   */
  @Test
  void a_service_put_through_one_registry_is_visible_through_a_second_independent_one() {
    ServiceRegistry replicaA = registry();
    try (StoreClient secondClient = inProcessStore.newClient()) {
      ServiceRegistry replicaB = new ServiceRegistry(secondClient, secondClient);

      replicaA.put(new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080));

      assertEquals(8080, replicaB.get("orders").orElseThrow().port());
      assertEquals(1, replicaB.list().size());
    }
  }
}
