package com.gimle.module.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module whose {@code on_start} begins a request and never ends it must still get disposed by
 * {@code stop()} — the drain deadline is a ceiling, not a promise the module actually finished.
 */
class DrainDeadlineTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void stop_completes_after_deadline_despite_perpetual_in_flight_work() {
    ModuleLayer platform = PlatformLayer.boot_only().layer();

    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.neverdrains {
                }
                """)
            .with_descriptor(
                """
                name: com.gimle.fixture.neverdrains
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
                  hooks: com.gimle.module.integration.NeverDrainsHooks
                """)
            .build(tempDir, "neverdrains.jar");

    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleId id = registry.register(artifact);
    ModuleResolver resolver = new ModuleResolver(registry);
    Duration drainTimeout = Duration.ofMillis(150);
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            drainTimeout,
            e -> {});

    controller.resolve(id);
    controller.start(id);

    Instant before = Instant.now();
    controller.stop(id);
    Duration elapsed = Duration.between(before, Instant.now());

    assertFalse(
        registry.contains(id),
        "module must still be disposed despite in-flight work never draining");
    assertTrue(
        elapsed.compareTo(drainTimeout) >= 0,
        "stop() should have waited out the full drain timeout");
    assertTrue(
        elapsed.compareTo(drainTimeout.multipliedBy(10)) < 0, "stop() must not hang indefinitely");
  }
}
