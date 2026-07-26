package com.gimle.module.leak;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Acceptance-test driver for the redeploy-loop flat-metaspace test. Run as a <em>separate
 * process</em> launched with a fixed {@code -XX:MaxMetaspaceSize}: repeatedly
 * install/resolve/start/stop the same module artifact (a fresh {@code ModuleLayer}/classloader each
 * cycle) and print Metaspace usage samples, so the parent test can assert usage plateaus rather
 * than growing unbounded. If metadata genuinely leaked across cycles, this process would eventually
 * die with {@code OutOfMemoryError: Metaspace} before printing {@code DONE} — the parent test
 * checks both that and the sample trend.
 */
public final class RedeployLoopDriver {

  private RedeployLoopDriver() {}

  public static void main(String[] args) throws Exception {
    Path jarPath = Path.of(args[0]);
    int iterations = Integer.parseInt(args[1]);
    int sampleEvery = Integer.parseInt(args[2]);

    MemoryPoolMXBean metaspacePool = findMetaspacePool();
    ModuleLayer platform = PlatformLayer.bootOnly().layer();

    for (int i = 1; i <= iterations; i++) {
      ModuleRegistry registry = new ModuleRegistry();
      ModuleResolver resolver = new ModuleResolver(registry);
      ModuleController controller =
          new ModuleController(
              registry,
              resolver,
              platform,
              ClassLoader.getSystemClassLoader(),
              Duration.ofMillis(10),
              event -> {});

      ModuleArtifact artifact = ModuleArtifactReader.read(jarPath);
      ModuleId id = registry.register(artifact);
      controller.resolve(id);
      controller.start(id);
      controller.stop(id);

      if (i % sampleEvery == 0) {
        System.gc();
        Thread.sleep(20);
        long used = metaspacePool.getUsage().getUsed();
        System.out.println("SAMPLE " + i + " " + used);
      }
    }
    System.out.println("DONE");
  }

  private static MemoryPoolMXBean findMetaspacePool() {
    return ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(pool -> pool.getName().equals("Metaspace"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no Metaspace memory pool found"));
  }
}
