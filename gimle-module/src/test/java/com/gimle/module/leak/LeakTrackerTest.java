package com.gimle.module.leak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.ModuleLayerFactory;
import com.gimle.module.layer.ModuleLayerHandle;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class LeakTrackerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private ModuleLayerHandle build_and_load(String name) {
    Path jar =
        TestModuleBuilder.module(
                """
                module %s {
                }
                """
                    .formatted(name))
            .with_descriptor(TestModuleBuilder.minimal_descriptor(name, "1.0.0"))
            .build(tempDir, name.replace('.', '_') + ".jar");
    ModuleId id = new ModuleId(name, Version.parse("1.0.0"));
    ModuleLayer platform = PlatformLayer.boot_only().layer();
    return ModuleLayerFactory.create(
        id, jar, List.of(platform), ClassLoader.getSystemClassLoader());
  }

  @Test
  void no_leak_when_loader_is_actually_collected() throws InterruptedException {
    BlockingQueue<ModuleLeakDetected> detections = new LinkedBlockingQueue<>();
    ModuleId id = new ModuleId("com.gimle.fixture.noleak", Version.parse("1.0.0"));

    try (LeakTracker tracker = new LeakTracker(Duration.ofMillis(300), detections::add)) {
      ModuleLayerHandle handle = build_and_load("com.gimle.fixture.noleak");
      tracker.track(id, handle);
      handle = null; // nothing else in the test retains this loader

      // Give the drain thread + a couple of sweeps every chance to (correctly) find nothing.
      ModuleLeakDetected detected = detections.poll(1200, TimeUnit.MILLISECONDS);
      assertNull(detected, "false-positive leak report for a genuinely collectible loader");
    }
  }

  @Test
  void leak_is_reported_when_loader_is_retained() throws InterruptedException {
    BlockingQueue<ModuleLeakDetected> detections = new LinkedBlockingQueue<>();
    String name = "com.gimle.fixture.leaked";
    ModuleId id = new ModuleId(name, Version.parse("1.0.0"));

    // Deliberately kept reachable via this local for the whole try block, simulating a genuine
    // leak (some stray reference outliving the module's disposal).
    ModuleLayerHandle handle = build_and_load(name);
    try (LeakTracker tracker = new LeakTracker(Duration.ofMillis(300), detections::add)) {
      tracker.track(id, handle);

      ModuleLeakDetected detected = detections.poll(3, TimeUnit.SECONDS);
      assertNotNull(detected, "expected a leak to be reported for a retained loader");
      assertEquals(id, detected.id());
      assertFalse(detected.survivalTime().isNegative());
    } finally {
      assertNotNull(handle); // keep the reference alive (and used) through the assertions above
    }
  }

  @Test
  void wired_through_module_controller_reports_no_leak_on_clean_stop() throws InterruptedException {
    BlockingQueue<ModuleLeakDetected> detections = new LinkedBlockingQueue<>();
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.controllerleak {
                }
                """)
            .with_descriptor(
                TestModuleBuilder.minimal_descriptor("com.gimle.fixture.controllerleak", "1.0.0"))
            .build(tempDir, "controllerleak.jar");
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleId id = registry.register(artifact);
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.boot_only().layer();

    try (LeakTracker tracker = new LeakTracker(Duration.ofMillis(300), detections::add)) {
      ModuleController controller =
          new ModuleController(
              registry,
              resolver,
              platform,
              ClassLoader.getSystemClassLoader(),
              Duration.ofMillis(50),
              event -> {},
              tracker::track);

      controller.resolve(id);
      controller.start(id);
      controller.stop(id);

      ModuleLeakDetected detected = detections.poll(1200, TimeUnit.MILLISECONDS);
      assertNull(
          detected, "clean stop() with no retained references should not be reported as a leak");
    }
  }
}
