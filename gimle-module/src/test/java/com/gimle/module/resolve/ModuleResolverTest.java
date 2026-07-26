package com.gimle.module.resolve;

import static com.gimle.module.resolve.TestArtifacts.artifact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleResolutionException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Requirement;
import com.gimle.core.module.VersionRange;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModuleResolverTest {

  private static Requirement req(String name, String range) {
    return new Requirement(name, VersionRange.parse(range));
  }

  private static void install_and_resolve(
      ModuleRegistry registry, ModuleResolver resolver, ModuleId id) {
    ModuleWiring wiring = resolver.resolve(id);
    registry.mark_resolved(id, wiring, dummy_handle());
  }

  private static com.gimle.module.layer.ModuleLayerHandle dummy_handle() {
    return new com.gimle.module.layer.ModuleLayerHandle(
        ModuleLayer.boot(), ClassLoader.getSystemClassLoader());
  }

  @Test
  void wires_to_the_only_satisfying_candidate() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    ModuleArtifact catalog = artifact("com.gimle.catalog", "1.0.0");
    ModuleId catalogId = registry.register(catalog);
    install_and_resolve(registry, resolver, catalogId);

    ModuleArtifact orders =
        artifact("com.gimle.orders", "1.0.0", req("com.gimle.catalog", "[1.0.0,2.0.0)"));
    ModuleId ordersId = registry.register(orders);

    ModuleWiring wiring = resolver.resolve(ordersId);
    assertEquals(
        Map.of(req("com.gimle.catalog", "[1.0.0,2.0.0)"), catalogId), wiring.wiredDependencies());
  }

  @Test
  void wires_to_highest_satisfying_version() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    ModuleId low = registry.register(artifact("com.gimle.catalog", "1.0.0"));
    ModuleId high = registry.register(artifact("com.gimle.catalog", "1.5.0"));
    install_and_resolve(registry, resolver, low);
    install_and_resolve(registry, resolver, high);

    ModuleId ordersId =
        registry.register(
            artifact("com.gimle.orders", "1.0.0", req("com.gimle.catalog", "[1.0.0,2.0.0)")));

    ModuleWiring wiring = resolver.resolve(ordersId);
    assertEquals(high, wiring.wiredDependencies().values().iterator().next());
  }

  @Test
  void candidate_must_already_be_resolved_or_active() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    // catalog is registered (INSTALLED) but never resolved.
    registry.register(artifact("com.gimle.catalog", "1.0.0"));
    ModuleId ordersId =
        registry.register(
            artifact("com.gimle.orders", "1.0.0", req("com.gimle.catalog", "[1.0.0,2.0.0)")));

    assertThrows(GimleResolutionException.class, () -> resolver.resolve(ordersId));
  }

  @Test
  void unsatisfied_requirement_is_reported() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    ModuleId ordersId =
        registry.register(
            artifact("com.gimle.orders", "1.0.0", req("com.gimle.catalog", "[1.0.0,2.0.0)")));

    GimleResolutionException ex =
        assertThrows(GimleResolutionException.class, () -> resolver.resolve(ordersId));
    assertTrue(ex.getMessage().contains("com.gimle.catalog"));
  }

  @Test
  void two_length_cycle_is_detected() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    registry.register(artifact("com.gimle.a", "1.0.0", req("com.gimle.b", "[1.0.0,)")));
    ModuleId bId =
        registry.register(artifact("com.gimle.b", "1.0.0", req("com.gimle.a", "[1.0.0,)")));

    assertThrows(GimleResolutionException.class, () -> resolver.resolve(bId));
  }

  @Test
  void three_length_cycle_is_detected() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    registry.register(artifact("com.gimle.a", "1.0.0", req("com.gimle.b", "[1.0.0,)")));
    registry.register(artifact("com.gimle.b", "1.0.0", req("com.gimle.c", "[1.0.0,)")));
    ModuleId cId =
        registry.register(artifact("com.gimle.c", "1.0.0", req("com.gimle.a", "[1.0.0,)")));

    assertThrows(GimleResolutionException.class, () -> resolver.resolve(cId));
  }

  @Test
  void diamond_dependency_is_not_a_cycle() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    ModuleId sharedId = registry.register(artifact("com.gimle.shared", "1.0.0"));
    install_and_resolve(registry, resolver, sharedId);

    ModuleId aId =
        registry.register(artifact("com.gimle.a", "1.0.0", req("com.gimle.shared", "[1.0.0,)")));
    install_and_resolve(registry, resolver, aId);

    ModuleId bId =
        registry.register(artifact("com.gimle.b", "1.0.0", req("com.gimle.shared", "[1.0.0,)")));
    install_and_resolve(registry, resolver, bId);

    ModuleId topId =
        registry.register(
            artifact(
                "com.gimle.top",
                "1.0.0",
                req("com.gimle.a", "[1.0.0,)"),
                req("com.gimle.b", "[1.0.0,)")));

    ModuleWiring wiring = resolver.resolve(topId);
    assertEquals(2, wiring.wiredDependencies().size());
  }

  @Test
  void independent_dependents_can_wire_to_different_versions_of_the_same_dependency() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    ModuleId catalogV1 = registry.register(artifact("com.gimle.catalog", "1.0.0"));
    install_and_resolve(registry, resolver, catalogV1);

    ModuleId oldDependentId =
        registry.register(
            artifact("com.gimle.orders", "1.0.0", req("com.gimle.catalog", "[1.0.0,2.0.0)")));
    ModuleWiring oldWiring = resolver.resolve(oldDependentId);
    registry.mark_resolved(oldDependentId, oldWiring, dummy_handle());

    // A newer catalog version installs; the already-resolved dependent is NOT rewired.
    ModuleId catalogV2 = registry.register(artifact("com.gimle.catalog", "1.1.0"));
    install_and_resolve(registry, resolver, catalogV2);

    ModuleId newDependentId =
        registry.register(
            artifact("com.gimle.orders", "1.1.0", req("com.gimle.catalog", "[1.0.0,2.0.0)")));
    ModuleWiring newWiring = resolver.resolve(newDependentId);

    assertEquals(catalogV1, oldWiring.wiredDependencies().values().iterator().next());
    assertEquals(catalogV2, newWiring.wiredDependencies().values().iterator().next());
  }

  @Test
  void version_within_range_but_wrong_bound_is_unsatisfied() {
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);

    ModuleId tooNew = registry.register(artifact("com.gimle.catalog", "2.0.0"));
    install_and_resolve(registry, resolver, tooNew);

    ModuleId ordersId =
        registry.register(
            artifact("com.gimle.orders", "1.0.0", req("com.gimle.catalog", "[1.0.0,2.0.0)")));

    assertThrows(GimleResolutionException.class, () -> resolver.resolve(ordersId));
  }
}
