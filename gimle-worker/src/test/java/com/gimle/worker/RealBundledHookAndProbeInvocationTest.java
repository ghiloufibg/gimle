package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.probe.LivenessProbe;
import com.gimle.module.probe.ReadinessProbe;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.File;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves that a hosted module can bundle its own {@code ModuleLifecycleHooks}/probe implementations
 * inside its own jar (real deploy shape) rather than relying on the parent- classloader fallback
 * every other dynamically-loaded fixture in this codebase uses (see {@code RealHookInvocationTest},
 * whose hooks class lives on the *test's own* classpath, outside the fixture jar entirely).
 *
 * <p>{@code gimle-worker} (like every gimle-* process) runs unnamed itself — launched via {@code
 * java -cp}, not {@code --module-path}, since {@code gimle-api} doesn't exist yet to be a real
 * platform module ({@link PlatformLayer#bootOnly()}). So a bundled hook class can't {@code requires
 * com.gimle.module} outright (that module isn't resolvable in the boot-only platform layer,
 * ResolutionException) — it declares {@code requires static com.gimle.module} instead (resolution
 * succeeds with the optional dependency simply unsatisfied), and {@link
 * com.gimle.module.layer.ModuleLayerFactory} grants the loaded module readability to the parent
 * loader's own unnamed module, where the platform's real {@code ModuleLifecycleHooks}/probe classes
 * actually live. Without that {@code addReads}, this either can't load the classes at all, or (if a
 * *second* copy of {@code com.gimle.module} were separately put on a module path) casts against a
 * different {@code Class} instance than this test's own — verified in the failure of both wrong
 * approaches during design.
 */
class RealBundledHookAndProbeInvocationTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  @Test
  void bundled_hooks_and_probes_load_and_cast_against_this_jvms_own_platform_types() {
    List<Path> compileJars = findPlatformJars();

    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.bundled {
                  requires static com.gimle.module;
                  exports com.gimle.fixture.bundled;
                }
                """)
            .withClass(
                "com.gimle.fixture.bundled.BundledHooks",
                """
                package com.gimle.fixture.bundled;
                import com.gimle.module.lifecycle.ModuleContext;
                import com.gimle.module.lifecycle.ModuleLifecycleHooks;
                public final class BundledHooks implements ModuleLifecycleHooks {
                  public void onInstall(ModuleContext ctx) {}
                  public void onStart(ModuleContext ctx) {}
                  public void onStop(ModuleContext ctx) {}
                  public void onUninstall(ModuleContext ctx) {}
                }
                """)
            .withClass(
                "com.gimle.fixture.bundled.BundledLiveness",
                """
                package com.gimle.fixture.bundled;
                import com.gimle.module.probe.LivenessProbe;
                public final class BundledLiveness implements LivenessProbe {
                  public boolean isAlive() { return true; }
                }
                """)
            .withClass(
                "com.gimle.fixture.bundled.BundledReadiness",
                """
                package com.gimle.fixture.bundled;
                import com.gimle.module.probe.ReadinessProbe;
                public final class BundledReadiness implements ReadinessProbe {
                  public boolean isReady() { return true; }
                }
                """)
            .withDescriptor(
                """
                name: com.gimle.fixture.bundled
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
                  hooks: com.gimle.fixture.bundled.BundledHooks
                health:
                  liveness: com.gimle.fixture.bundled.BundledLiveness
                  readiness: com.gimle.fixture.bundled.BundledReadiness
                """)
            .dependsOn(compileJars.toArray(Path[]::new))
            .build(tempDir, "bundled.jar");

    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleInstanceId id = registry.register(artifact);
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    List<LifecycleEvent> events = new CopyOnWriteArrayList<>();
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            events::add);

    controller.resolve(id);
    controller.start(id);
    controller.stop(id);

    List<String> stateNames = events.stream().map(e -> e.getClass().getSimpleName()).toList();
    assertTrue(
        stateNames.contains("Active"),
        "module should reach Active with a bundled hooks class; got " + stateNames);
    assertTrue(
        stateNames.stream().noneMatch("TransitionFailed"::equals),
        "no lifecycle transition should fail; got " + stateNames);
  }

  @Test
  void bundled_probes_instantiate_and_cast_cleanly() throws Exception {
    List<Path> compileJars = findPlatformJars();
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.bundledprobes {
                  requires static com.gimle.module;
                  exports com.gimle.fixture.bundledprobes;
                }
                """)
            .withClass(
                "com.gimle.fixture.bundledprobes.P",
                """
                package com.gimle.fixture.bundledprobes;
                import com.gimle.module.probe.LivenessProbe;
                import com.gimle.module.probe.ReadinessProbe;
                public final class P implements LivenessProbe, ReadinessProbe {
                  public boolean isAlive() { return true; }
                  public boolean isReady() { return true; }
                }
                """)
            .withDescriptor(
                TestModuleBuilder.minimalDescriptor("com.gimle.fixture.bundledprobes", "1.0.0"))
            .dependsOn(compileJars.toArray(Path[]::new))
            .build(tempDir, "bundledprobes.jar");

    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    ClassLoader parentLoader = ClassLoader.getSystemClassLoader();
    Configuration cf =
        Configuration.resolve(
            ModuleFinder.of(jar),
            List.of(platform.configuration()),
            ModuleFinder.of(),
            List.of("com.gimle.fixture.bundledprobes"));
    ModuleLayer.Controller controller =
        ModuleLayer.defineModulesWithOneLoader(cf, List.of(platform), parentLoader);
    Module hostedModule =
        controller.layer().findModule("com.gimle.fixture.bundledprobes").orElseThrow();
    controller.addReads(hostedModule, parentLoader.getUnnamedModule());

    ClassLoader hostedLoader = controller.layer().findLoader("com.gimle.fixture.bundledprobes");
    Class<?> probeClass = Class.forName("com.gimle.fixture.bundledprobes.P", true, hostedLoader);
    Object instance = probeClass.getDeclaredConstructor().newInstance();

    assertTrue(instance instanceof LivenessProbe);
    assertTrue(instance instanceof ReadinessProbe);
    assertTrue(((LivenessProbe) instance).isAlive());
    assertTrue(((ReadinessProbe) instance).isReady());
  }

  private static List<Path> findPlatformJars() {
    String[] jarNeedles = {"slf4j-api-", "logback-classic-", "logback-core-", "snakeyaml-"};
    // gimle-module/gimle-core resolve as installed jars when this test runs in isolation, but as
    // exploded target/classes directories when the reactor builds gimle-module and gimle-worker
    // together (Maven then points sibling modules at each other's build output, not the local
    // repo) -- both are valid JPMS module-path entries, so match either shape.
    String[] moduleArtifacts = {"gimle-module", "gimle-core"};
    List<Path> result = new ArrayList<>();
    String cp = System.getProperty("java.class.path");
    for (String entry : cp.split(File.pathSeparator)) {
      String fileName = Path.of(entry).getFileName().toString();
      String normalized = entry.replace('\\', '/');
      for (String needle : jarNeedles) {
        if (fileName.startsWith(needle)
            && fileName.endsWith(".jar")
            && !fileName.contains("tests")) {
          result.add(Path.of(entry));
        }
      }
      for (String artifact : moduleArtifacts) {
        boolean installedJar = fileName.startsWith(artifact + "-") && fileName.endsWith(".jar");
        boolean reactorClasses =
            normalized.endsWith("/" + artifact + "/target/classes")
                || normalized.endsWith("/" + artifact + "/target/test-classes");
        if ((installedJar && !fileName.contains("tests")) || reactorClasses) {
          result.add(Path.of(entry));
        }
      }
    }
    return result;
  }
}
