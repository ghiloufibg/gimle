package com.gimle.module.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the reflection-based hook loading in {@link ModuleController} actually works against a
 * real, independently-compiled, dynamically-loaded module — not a hand-instantiated hooks object
 * standing in for it. See {@link RecordingHooks} for why the hooks class itself lives on the test
 * classpath rather than inside the fixture jar.
 */
class RealHookInvocationTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void hooks_fire_in_order_with_a_dynamically_loaded_module() {
    RecordingHooks.CALLS.clear();
    ModuleLayer platform = PlatformLayer.boot_only().layer();

    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.hooked {
                }
                """)
            .with_descriptor(
                """
                name: com.gimle.fixture.hooked
                version: 1.0.0
                isolation:
                  tier: TIER_1
                resources:
                  request:
                    memory: 16Mi
                    cpu: 10m
                  limit:
                    memory: 32Mi
                    cpu: 50m
                lifecycle:
                  hooks: com.gimle.module.integration.RecordingHooks
                """)
            .build(tempDir, "hooked.jar");

    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleId id = registry.register(artifact);
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            e -> {});

    controller.resolve(id);
    controller.start(id);
    controller.stop(id);

    assertEquals(List.of("install", "start", "stop", "uninstall"), RecordingHooks.CALLS);
  }
}
