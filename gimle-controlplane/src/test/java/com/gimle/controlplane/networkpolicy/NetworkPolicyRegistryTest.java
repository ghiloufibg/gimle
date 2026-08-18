package com.gimle.controlplane.networkpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Backed by a real {@code gimle-mimir} store (via {@link InProcessStore}), the same fixture {@code
 * ApiServerNetworkPoliciesTest} uses -- proves {@link NetworkPolicyRegistry} actually persists
 * through {@code StoreClient} rather than an in-memory map, not just that its own facade methods
 * round-trip against themselves.
 */
class NetworkPolicyRegistryTest {

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

  private NetworkPolicyRegistry registry() {
    return new NetworkPolicyRegistry(inProcessStore.client(), inProcessStore.client());
  }

  @Test
  void put_then_get_round_trips_a_spec_by_name() {
    NetworkPolicyRegistry registry = registry();
    NetworkPolicySpec spec =
        new NetworkPolicySpec("deny-by-default", "acme", Set.of("partner-tenant"));

    registry.put(spec);

    assertEquals(Optional.of(spec), registry.get("deny-by-default"));
  }

  @Test
  void get_of_an_unknown_name_is_empty() {
    NetworkPolicyRegistry registry = registry();
    assertEquals(Optional.empty(), registry.get("nope"));
  }

  @Test
  void list_returns_every_registered_spec() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("policy-a", "acme", Set.of()));
    registry.put(new NetworkPolicySpec("policy-b", "globex", Set.of()));

    assertEquals(2, registry.list().size());
  }

  @Test
  void putting_the_same_name_again_replaces_the_prior_spec() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("deny-by-default", "acme", Set.of("partner-a")));
    registry.put(new NetworkPolicySpec("deny-by-default", "acme", Set.of("partner-b")));

    assertEquals(1, registry.list().size());
    assertEquals(
        Set.of("partner-b"),
        registry.get("deny-by-default").orElseThrow().allowedCallerTenantIds());
  }

  @Test
  void removing_a_network_policy_removes_it() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("deny-by-default", "acme", Set.of()));

    registry.remove("deny-by-default");

    assertEquals(Optional.empty(), registry.get("deny-by-default"));
    assertTrue(registry.list().isEmpty());
  }

  /**
   * The actual bug this class exists to fix: a policy put through one {@code NetworkPolicyRegistry}
   * (standing in for one control-plane replica) must be visible through a second, independent
   * {@code NetworkPolicyRegistry} backed by its own {@code StoreClient} against the same store
   * cluster -- not just visible to itself.
   */
  @Test
  void a_network_policy_put_through_one_registry_is_visible_through_a_second_independent_one() {
    NetworkPolicyRegistry replicaA = registry();
    try (StoreClient secondClient = inProcessStore.newClient()) {
      NetworkPolicyRegistry replicaB = new NetworkPolicyRegistry(secondClient, secondClient);

      replicaA.put(new NetworkPolicySpec("deny-by-default", "acme", Set.of("partner-tenant")));

      assertEquals(
          Set.of("partner-tenant"),
          replicaB.get("deny-by-default").orElseThrow().allowedCallerTenantIds());
      assertEquals(1, replicaB.list().size());
    }
  }
}
