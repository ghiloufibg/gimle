package com.gimle.module.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gimle.core.module.ModuleId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ModuleState;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hot redeploy is deliberately not a distinct state-machine path (see the design notes): it's two
 * ordinary lifecycles overlapping. This proves that end to end through {@link ModuleController} —
 * old and new dependency versions active at once, dependents pinned to whichever version they
 * resolved against, draining the old side afterward.
 */
class HotRedeployTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static final String CATALOG_MODULE_INFO =
      """
      module com.gimle.fixture.catalog {
        exports com.gimle.fixture.catalog;
      }
      """;
  private static final String CATALOG_CLASS =
      """
      package com.gimle.fixture.catalog;
      public class Catalog {
        public String sku() { return "SKU"; }
      }
      """;
  private static final String ORDERS_MODULE_INFO =
      """
      module com.gimle.fixture.orders {
        requires com.gimle.fixture.catalog;
        exports com.gimle.fixture.orders;
      }
      """;
  private static final String ORDERS_CLASS =
      """
      package com.gimle.fixture.orders;
      import com.gimle.fixture.catalog.Catalog;
      public class Orders {
        public String describe() { return "order for " + new Catalog().sku(); }
      }
      """;

  @Test
  void old_and_new_versions_coexist_with_dependents_pinned_to_their_own_wiring() {
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            e -> {});

    Path catalogV1Jar =
        TestModuleBuilder.module(CATALOG_MODULE_INFO)
            .withClass("com.gimle.fixture.catalog.Catalog", CATALOG_CLASS)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.catalog", "1.0.0"))
            .build(tempDir, "catalog-1.0.0.jar");
    ModuleId catalogV1 = registry.register(ModuleArtifactReader.read(catalogV1Jar));
    controller.resolve(catalogV1);
    controller.start(catalogV1);

    Path ordersV1Jar =
        TestModuleBuilder.module(ORDERS_MODULE_INFO)
            .withClass("com.gimle.fixture.orders.Orders", ORDERS_CLASS)
            .withDescriptor(ordersDescriptor("1.0.0"))
            .dependsOn(catalogV1Jar)
            .build(tempDir, "orders-1.0.0.jar");
    ModuleId ordersV1 = registry.register(ModuleArtifactReader.read(ordersV1Jar));
    controller.resolve(ordersV1);
    controller.start(ordersV1);

    // A newer catalog installs and activates; ordersV1 is NOT rewired.
    Path catalogV2Jar =
        TestModuleBuilder.module(CATALOG_MODULE_INFO)
            .withClass("com.gimle.fixture.catalog.Catalog", CATALOG_CLASS)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.catalog", "1.1.0"))
            .build(tempDir, "catalog-1.1.0.jar");
    ModuleId catalogV2 = registry.register(ModuleArtifactReader.read(catalogV2Jar));
    controller.resolve(catalogV2);
    controller.start(catalogV2);

    Path ordersV2Jar =
        TestModuleBuilder.module(ORDERS_MODULE_INFO)
            .withClass("com.gimle.fixture.orders.Orders", ORDERS_CLASS)
            .withDescriptor(ordersDescriptor("1.1.0"))
            .dependsOn(catalogV1Jar)
            .build(tempDir, "orders-1.1.0.jar");
    ModuleId ordersV2 = registry.register(ModuleArtifactReader.read(ordersV2Jar));
    var ordersV2Wiring = controller.resolve(ordersV2);
    controller.start(ordersV2);

    assertEquals(catalogV2, ordersV2Wiring.wiredDependencies().values().iterator().next());
    assertEquals(ModuleState.ACTIVE, registry.state(ordersV1));
    assertEquals(ModuleState.ACTIVE, registry.state(catalogV1));
    assertEquals(ModuleState.ACTIVE, registry.state(ordersV2));
    assertEquals(ModuleState.ACTIVE, registry.state(catalogV2));

    // Drain the old side; the new side is unaffected.
    controller.stop(ordersV1);
    controller.stop(catalogV1);
    assertFalse(registry.contains(ordersV1));
    assertFalse(registry.contains(catalogV1));
    assertEquals(ModuleState.ACTIVE, registry.state(ordersV2));
    assertEquals(ModuleState.ACTIVE, registry.state(catalogV2));
  }

  private static String ordersDescriptor(String version) {
    return """
        name: com.gimle.fixture.orders
        version: %s
        requires:
          - module: com.gimle.fixture.catalog
            version: "[1.0.0,2.0.0)"
        isolation:
          tier: TIER_1
        resources:
          request:
            memory: 16Mi
            cpu: 10m
          limit:
            memory: 32Mi
            cpu: 50m
        """
        .formatted(version);
  }
}
