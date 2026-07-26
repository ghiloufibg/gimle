package com.gimle.module.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@code ModuleContext#register_service}/{@code lookup_service} actually connects two real,
 * independently-installed, dynamically-loaded modules through one {@code ModuleController}
 * instance's shared registry — not a hand-wired stand-in for it.
 */
class ServiceRegistryIntegrationTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static Path build_fixture(Path tempDir, String moduleName, String hooksClassName) {
    return TestModuleBuilder.module(
            """
            module %s {
            }
            """
                .formatted(moduleName))
        .with_descriptor(
            """
            name: %s
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
              hooks: %s
            """
                .formatted(moduleName, hooksClassName))
        .build(tempDir, moduleName.replace('.', '_') + ".jar");
  }

  @Test
  void consumer_finds_the_service_the_provider_registered() {
    ModuleLayer platform = PlatformLayer.boot_only().layer();
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

    Path providerJar =
        build_fixture(
            tempDir,
            "com.gimle.fixture.provider",
            "com.gimle.module.integration.ServiceProviderHooks");
    ModuleArtifact providerArtifact = ModuleArtifactReader.read(providerJar);
    ModuleId providerId = registry.register(providerArtifact);
    controller.resolve(providerId);
    controller.start(providerId); // registers the Greeter here

    ServiceConsumerHooks.LOOKED_UP_GREETING.set(Optional.empty());
    Path consumerJar =
        build_fixture(
            tempDir,
            "com.gimle.fixture.consumer",
            "com.gimle.module.integration.ServiceConsumerHooks");
    ModuleArtifact consumerArtifact = ModuleArtifactReader.read(consumerJar);
    ModuleId consumerId = registry.register(consumerArtifact);
    controller.resolve(consumerId);
    controller.start(consumerId); // looks it up here

    assertEquals(Optional.of("hello from provider"), ServiceConsumerHooks.LOOKED_UP_GREETING.get());
  }

  @Test
  void consumer_finds_nothing_when_no_provider_has_registered_yet() {
    ModuleLayer platform = PlatformLayer.boot_only().layer();
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

    ServiceConsumerHooks.LOOKED_UP_GREETING.set(Optional.empty());
    Path consumerJar =
        build_fixture(
            tempDir,
            "com.gimle.fixture.lonelyconsumer",
            "com.gimle.module.integration.ServiceConsumerHooks");
    ModuleArtifact consumerArtifact = ModuleArtifactReader.read(consumerJar);
    ModuleId consumerId = registry.register(consumerArtifact);
    controller.resolve(consumerId);
    controller.start(consumerId);

    assertTrue(ServiceConsumerHooks.LOOKED_UP_GREETING.get().isEmpty());
  }
}
