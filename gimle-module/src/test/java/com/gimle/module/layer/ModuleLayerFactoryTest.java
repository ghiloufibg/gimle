package com.gimle.module.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.exception.GimleResolutionException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.Version;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class ModuleLayerFactoryTest {

  // See TestModuleBuilderTest: loading a jar as a module keeps its file handle open on Windows
  // with no public API to force-close it, so cleanup is left to the OS temp-dir sweep.
  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void builds_a_dependency_free_module_layer() {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.leaf {
                  exports com.gimle.fixture.leaf;
                }
                """)
            .withClass(
                "com.gimle.fixture.leaf.Leaf",
                """
                package com.gimle.fixture.leaf;
                public class Leaf {
                  public String value() { return "leaf"; }
                }
                """)
            .withDescriptor(TestModuleBuilder.minimalDescriptor("com.gimle.fixture.leaf", "1.0.0"))
            .build(tempDir, "leaf.jar");

    ModuleInstanceId id =
        ModuleInstanceId.unattached(new ModuleId("com.gimle.fixture.leaf", Version.parse("1.0.0")));
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    ModuleLayerHandle handle =
        ModuleLayerFactory.create(id, jar, List.of(platform), ClassLoader.getSystemClassLoader());

    assertNotEquals(platform, handle.layer());
    assertEquals(handle.loader(), handle.layer().findLoader("com.gimle.fixture.leaf"));
  }

  @Test
  void dependent_layer_can_call_into_dependency_exported_api() throws Exception {
    Path depJar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.catalog {
                  exports com.gimle.fixture.catalog;
                }
                """)
            .withClass(
                "com.gimle.fixture.catalog.Catalog",
                """
                package com.gimle.fixture.catalog;
                public class Catalog {
                  public String sku() { return "SKU-42"; }
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.catalog", "1.0.0"))
            .build(tempDir, "catalog.jar");

    ModuleInstanceId depId =
        ModuleInstanceId.unattached(
            new ModuleId("com.gimle.fixture.catalog", Version.parse("1.0.0")));
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    ModuleLayerHandle depHandle =
        ModuleLayerFactory.create(
            depId, depJar, List.of(platform), ClassLoader.getSystemClassLoader());

    Path orderJar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.orders {
                  requires com.gimle.fixture.catalog;
                  exports com.gimle.fixture.orders;
                }
                """)
            .withClass(
                "com.gimle.fixture.orders.Orders",
                """
                package com.gimle.fixture.orders;
                import com.gimle.fixture.catalog.Catalog;
                public class Orders {
                  public String describe() { return "order for " + new Catalog().sku(); }
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.orders", "1.0.0"))
            .dependsOn(depJar)
            .build(tempDir, "orders.jar");

    ModuleInstanceId orderId =
        ModuleInstanceId.unattached(
            new ModuleId("com.gimle.fixture.orders", Version.parse("1.0.0")));
    ModuleLayerHandle orderHandle =
        ModuleLayerFactory.create(
            orderId,
            orderJar,
            List.of(platform, depHandle.layer()),
            ClassLoader.getSystemClassLoader());

    Class<?> ordersClass = orderHandle.loader().loadClass("com.gimle.fixture.orders.Orders");
    Object instance = ordersClass.getDeclaredConstructor().newInstance();
    Object result = ordersClass.getMethod("describe").invoke(instance);
    assertEquals("order for SKU-42", result);
  }

  @Test
  void two_versions_of_the_same_module_get_distinct_layers_and_loaders() {
    String moduleInfo =
        """
        module com.gimle.fixture.versioned {
          exports com.gimle.fixture.versioned;
        }
        """;
    String classSource =
        """
        package com.gimle.fixture.versioned;
        public class Marker {}
        """;
    ModuleLayer platform = PlatformLayer.bootOnly().layer();

    Path jarV1 =
        TestModuleBuilder.module(moduleInfo)
            .withClass("com.gimle.fixture.versioned.Marker", classSource)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.versioned", "1.0.0"))
            .build(tempDir, "versioned-1.jar");
    Path jarV2 =
        TestModuleBuilder.module(moduleInfo)
            .withClass("com.gimle.fixture.versioned.Marker", classSource)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.versioned", "2.0.0"))
            .build(tempDir, "versioned-2.jar");

    ModuleLayerHandle v1 =
        ModuleLayerFactory.create(
            ModuleInstanceId.unattached(
                new ModuleId("com.gimle.fixture.versioned", Version.parse("1.0.0"))),
            jarV1,
            List.of(platform),
            ClassLoader.getSystemClassLoader());
    ModuleLayerHandle v2 =
        ModuleLayerFactory.create(
            ModuleInstanceId.unattached(
                new ModuleId("com.gimle.fixture.versioned", Version.parse("2.0.0"))),
            jarV2,
            List.of(platform),
            ClassLoader.getSystemClassLoader());

    assertNotSame(v1.loader(), v2.loader());
    assertNotEquals(v1.layer(), v2.layer());
  }

  @Test
  void missing_parent_layer_for_a_real_requirement_fails_with_gimle_resolution_exception() {
    // "needed" must be a real, compilable module (javac itself resolves `requires` at compile
    // time) — the failure this test targets is a *runtime* one: ModuleLayerFactory.create is
    // called without the dependency's layer among the parents, which Configuration.resolve
    // only discovers when actually building the layer.
    Path neededJar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.needed {
                  exports com.gimle.fixture.needed;
                }
                """)
            .withClass(
                "com.gimle.fixture.needed.Needed",
                """
                package com.gimle.fixture.needed;
                public class Needed {}
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.needed", "1.0.0"))
            .build(tempDir, "needed.jar");

    Path brokenJar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.broken {
                  requires com.gimle.fixture.needed;
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.broken", "1.0.0"))
            .dependsOn(neededJar)
            .build(tempDir, "broken.jar");

    ModuleInstanceId id =
        ModuleInstanceId.unattached(
            new ModuleId("com.gimle.fixture.broken", Version.parse("1.0.0")));
    ModuleLayer platform = PlatformLayer.bootOnly().layer();

    // Deliberately omit the "needed" layer from the parents.
    assertThrows(
        GimleResolutionException.class,
        () ->
            ModuleLayerFactory.create(
                id, brokenJar, List.of(platform), ClassLoader.getSystemClassLoader()));
  }
}
