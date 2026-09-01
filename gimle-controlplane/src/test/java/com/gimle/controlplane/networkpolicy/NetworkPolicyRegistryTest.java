package com.gimle.controlplane.networkpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.testsupport.InProcessStore;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.rpc.StoreClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Backed by a real {@code gimle-mimir} store (via {@link InProcessStore}), the same fixture {@code
 * ApiServerNetworkPoliciesTest} uses -- proves {@link NetworkPolicyRegistry} actually persists
 * through {@code StoreClient} rather than an in-memory map, not just that its own facade methods
 * round-trip against themselves. {@code
 * concurrent_writers_each_adding_one_caller_tenant_never_lose_an_addition} deliberately drives
 * several threads at a single-node store simultaneously; isolated for the same
 * CPU-contention-under-class-level-concurrency reason {@code ConfigMapStoreTest} is.
 */
@Isolated
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
    return new NetworkPolicyRegistry(inProcessStore.client());
  }

  @Test
  void put_then_get_round_trips_a_spec_by_name() {
    NetworkPolicyRegistry registry = registry();
    NetworkPolicySpec spec =
        new NetworkPolicySpec("deny-by-default", "acme", Set.of("partner-tenant"));

    registry.put(spec, OptionalInt.empty());

    assertEquals(Optional.of(spec.withVersion(1)), registry.get("acme", "deny-by-default"));
  }

  @Test
  void get_of_an_unknown_name_is_empty() {
    NetworkPolicyRegistry registry = registry();
    assertEquals(Optional.empty(), registry.get("acme", "nope"));
  }

  @Test
  void list_returns_every_registered_spec() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("policy-a", "acme", Set.of()), OptionalInt.empty());
    registry.put(new NetworkPolicySpec("policy-b", "globex", Set.of()), OptionalInt.empty());

    assertEquals(2, registry.list().size());
  }

  @Test
  void putting_the_same_name_again_replaces_the_prior_spec() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("deny-by-default", "acme", Set.of("partner-a")), OptionalInt.empty());
    registry.put(new NetworkPolicySpec("deny-by-default", "acme", Set.of("partner-b")), OptionalInt.empty());

    assertEquals(1, registry.list().size());
    assertEquals(
        Optional.of(Set.of("partner-b")),
        registry.get("acme", "deny-by-default").orElseThrow().allowedCallerTenantIds());
  }

  @Test
  void removing_a_network_policy_removes_it() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("deny-by-default", "acme", Set.of()), OptionalInt.empty());

    registry.remove("acme", "deny-by-default");

    assertEquals(Optional.empty(), registry.get("acme", "deny-by-default"));
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
      NetworkPolicyRegistry replicaB = new NetworkPolicyRegistry(secondClient);

      replicaA.put(new NetworkPolicySpec("deny-by-default", "acme", Set.of("partner-tenant")), OptionalInt.empty());

      assertEquals(
          Optional.of(Set.of("partner-tenant")),
          replicaB.get("acme", "deny-by-default").orElseThrow().allowedCallerTenantIds());
      assertEquals(1, replicaB.list().size());
    }
  }

  @Test
  void a_first_write_stamps_version_one_and_each_later_write_counts_up() {
    NetworkPolicyRegistry registry = registry();

    NetworkPolicyWriteResult first =
        registry.put(
            new NetworkPolicySpec("policy", "acme", Set.of("partner-a")), OptionalInt.empty());
    NetworkPolicyWriteResult second =
        registry.put(
            new NetworkPolicySpec("policy", "acme", Set.of("partner-b")), OptionalInt.of(1));

    assertEquals(1, ((NetworkPolicyWriteResult.Written) first).spec().version());
    assertEquals(2, ((NetworkPolicyWriteResult.Written) second).spec().version());
  }

  @Test
  void a_write_guarded_by_a_stale_version_is_rejected_and_leaves_the_stored_policy_untouched() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("policy", "acme", Set.of("partner-a")), OptionalInt.empty());
    registry.put(new NetworkPolicySpec("policy", "acme", Set.of("partner-b")), OptionalInt.of(1));

    NetworkPolicyWriteResult stale =
        registry.put(
            new NetworkPolicySpec("policy", "acme", Set.of("partner-c")), OptionalInt.of(1));

    NetworkPolicyWriteResult.VersionConflict conflict =
        assertInstanceOf(NetworkPolicyWriteResult.VersionConflict.class, stale);
    assertEquals(2, conflict.currentVersion());
    assertEquals(
        Optional.of(Set.of("partner-b")),
        registry.get("acme", "policy").orElseThrow().allowedCallerTenantIds());
  }

  /**
   * The actual lost update the version guard exists to stop: two operators each read the same
   * policy, each add a different caller tenant, and both write. Without a guard the second write
   * silently discards the first operator's addition; with one, the loser is told its version is
   * stale and its retry lands on top of the winner's state instead of erasing it.
   */
  @Test
  void two_operators_adding_different_caller_tenants_never_lose_one_of_the_additions() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("policy", "acme", Set.of()), OptionalInt.empty());
    int sharedStartingVersion = registry.get("acme", "policy").orElseThrow().version();

    NetworkPolicyWriteResult firstOperator =
        registry.patch("acme", "policy", sharedStartingVersion, addCaller("partner-a"));
    NetworkPolicyWriteResult secondOperator =
        registry.patch("acme", "policy", sharedStartingVersion, addCaller("partner-b"));

    assertInstanceOf(NetworkPolicyWriteResult.Written.class, firstOperator);
    assertInstanceOf(NetworkPolicyWriteResult.VersionConflict.class, secondOperator);
    assertEquals(
        Optional.of(Set.of("partner-a")),
        registry.get("acme", "policy").orElseThrow().allowedCallerTenantIds());

    NetworkPolicyWriteResult retry =
        registry.patch(
            "acme",
            "policy",
            ((NetworkPolicyWriteResult.VersionConflict) secondOperator).currentVersion(),
            addCaller("partner-b"));

    assertInstanceOf(NetworkPolicyWriteResult.Written.class, retry);
    assertEquals(
        Optional.of(Set.of("partner-a", "partner-b")),
        registry.get("acme", "policy").orElseThrow().allowedCallerTenantIds());
  }

  @Test
  void concurrent_writers_each_adding_one_caller_tenant_never_lose_an_addition() throws Exception {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("policy", "acme", Set.of()), OptionalInt.empty());
    int writers = 5;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch ready = new CountDownLatch(writers);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Void>> futures =
          IntStream.range(0, writers)
              .mapToObj(
                  i ->
                      pool.<Void>submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            addCallerWithRetry(registry, "partner-" + i);
                            return null;
                          }))
              .toList();
      ready.await();
      go.countDown();
      for (Future<Void> future : futures) {
        future.get();
      }

      Set<String> allowed =
          registry.get("acme", "policy").orElseThrow().allowedCallerTenantIds().orElseThrow();
      for (int i = 0; i < writers; i++) {
        assertTrue(allowed.contains("partner-" + i), "lost the addition of partner-" + i);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void a_partial_update_edits_one_allow_list_entry_and_leaves_every_other_field_alone() {
    NetworkPolicyRegistry registry = registry();
    registry.put(
        new NetworkPolicySpec(
            "policy",
            "acme",
            Optional.of(Set.of("orders-service")),
            Optional.of(Set.of("com.example.Orders")),
            Optional.of(Set.of("partner-a", "partner-b")),
            Optional.of(Set.of("downstream"))),
        OptionalInt.empty());

    NetworkPolicyWriteResult result =
        registry.patch(
            "acme",
            "policy",
            1,
            new NetworkPolicyPatch(
                Set.of("partner-c"),
                Set.of("partner-a"),
                Set.of(),
                Set.of(),
                Optional.empty(),
                Optional.empty()));

    assertInstanceOf(NetworkPolicyWriteResult.Written.class, result);
    NetworkPolicySpec saved = registry.get("acme", "policy").orElseThrow();
    assertEquals(Optional.of(Set.of("partner-b", "partner-c")), saved.allowedCallerTenantIds());
    assertEquals(Optional.of(Set.of("downstream")), saved.allowedCalleeTenantIds());
    assertEquals(Optional.of(Set.of("orders-service")), saved.deploymentNames());
    assertEquals(Optional.of(Set.of("com.example.Orders")), saved.serviceInterfaceNames());
    assertEquals(2, saved.version());
  }

  @Test
  void removing_the_last_allowed_caller_leaves_the_direction_restricted_and_empty() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("policy", "acme", Set.of("partner-a")), OptionalInt.empty());

    registry.patch(
        "acme",
        "policy",
        1,
        new NetworkPolicyPatch(
            Set.of(), Set.of("partner-a"), Set.of(), Set.of(), Optional.empty(), Optional.empty()));

    assertEquals(
        Optional.of(Set.of()),
        registry.get("acme", "policy").orElseThrow().allowedCallerTenantIds());
  }

  @Test
  void a_partial_update_of_a_policy_that_does_not_exist_is_not_a_create() {
    NetworkPolicyRegistry registry = registry();

    NetworkPolicyWriteResult result = registry.patch("acme", "nope", 0, addCaller("partner-a"));

    assertInstanceOf(NetworkPolicyWriteResult.NotFound.class, result);
    assertEquals(Optional.empty(), registry.get("acme", "nope"));
  }

  @Test
  void a_partial_update_cannot_start_restricting_a_direction_the_policy_left_unrestricted() {
    NetworkPolicyRegistry registry = registry();
    registry.put(new NetworkPolicySpec("policy", "acme", Set.of("partner-a")), OptionalInt.empty());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.patch(
                "acme",
                "policy",
                1,
                new NetworkPolicyPatch(
                    Set.of(),
                    Set.of(),
                    Set.of("downstream"),
                    Set.of(),
                    Optional.empty(),
                    Optional.empty())));
  }

  private static NetworkPolicyPatch addCaller(String tenantId) {
    return new NetworkPolicyPatch(
        Set.of(tenantId), Set.of(), Set.of(), Set.of(), Optional.empty(), Optional.empty());
  }

  /** The caller-side read-patch-retry-on-conflict loop the version guard pushes onto callers. */
  private static void addCallerWithRetry(NetworkPolicyRegistry registry, String tenantId) {
    for (int attempt = 0; attempt < 50; attempt++) {
      int currentVersion =
          registry.get("acme", "policy").map(NetworkPolicySpec::version).orElse(0);
      if (registry.patch("acme", "policy", currentVersion, addCaller(tenantId))
          instanceof NetworkPolicyWriteResult.Written) {
        return;
      }
    }
    throw new AssertionError("could not add " + tenantId + " under contention");
  }
}
