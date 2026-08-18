package com.gimle.holmgang.fixtures;

import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Real, deployable variants of {@code greeter-provider} compiled at scenario run time -- a genuine
 * v1.1.0 for rolling updates, an always-failing-liveness build for self-healing escalation, and a
 * build that reports its own listening port via {@code ModuleContext.reportPort} for Service
 * endpoint resolution -- via {@link TestModuleBuilder}, rather than committing near-duplicate
 * example modules just to change a version number, a probe result, or add a runtime call. Same
 * module name as the committed example, so the platform recognizes a version bump of the same
 * deployment.
 */
public final class ModuleFixtures {

  /** The fixed port {@link #portReportingProviderJar} reports via {@code ctx.reportPort}. */
  public static final int PORT_REPORTING_PROVIDER_PORT = 9500;

  private ModuleFixtures() {}

  /** A real v1.1.0 whose greeting text proves which build is actually serving. */
  public static Path providerV2Jar(final Path outputDir) {
    return providerVariant(
        outputDir,
        "V2",
        "1.1.0",
        "return \"Hello, \" + name + \"! (from provider v2)\";",
        /* alive= */ true,
        /* extraOnStartBody= */ "");
  }

  /**
   * Starts and serves normally but never passes its own liveness probe, so the worker's module-tier
   * restart budget is exhausted and the instance is escalated to a terminal {@code FAILED}.
   */
  public static Path alwaysUnhealthyProviderJar(final Path outputDir) {
    return providerVariant(
        outputDir,
        "Unhealthy",
        "1.0.0-unhealthy",
        "return \"Hello, \" + name + \"! (from unhealthy provider)\";",
        /* alive= */ false,
        /* extraOnStartBody= */ "");
  }

  /**
   * A hosted (non-Vessel) module that reports its own port at runtime, the mechanism {@code
   * ServiceEndpointResolver.solePort()} needs to resolve a Service endpoint for an ordinary
   * fabric-hosted module instead of only ever a Vessel workload's allocated port.
   */
  public static Path portReportingProviderJar(final Path outputDir) {
    return providerVariant(
        outputDir,
        "PortReporting",
        "1.0.0-portreporting",
        "return \"Hello, \" + name + \"! (from port-reporting provider)\";",
        /* alive= */ true,
        /* extraOnStartBody= */ "ctx.reportPort(\"http\", " + PORT_REPORTING_PROVIDER_PORT + ");");
  }

  private static Path providerVariant(
      final Path outputDir,
      final String variant,
      final String version,
      final String greetBody,
      final boolean alive,
      final String extraOnStartBody) {
    final List<Path> compileJars = findCompileModulePathJars();
    return TestModuleBuilder.module(
            """
            module com.gimle.examples.greeter.provider {
              requires static com.gimle.module;
              requires static org.slf4j;
              exports com.gimle.examples.greeter.provider;
              exports com.gimle.examples.greeter;
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.Greeter",
            """
            package com.gimle.examples.greeter;
            public interface Greeter {
              String greet(String name);
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProvider%1$sHooks".formatted(variant),
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class GreeterProvider%1$sHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> {
                  %2$s
                });
                %3$s
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """
                .formatted(variant, greetBody, extraOnStartBody))
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProvider%1$sLiveness".formatted(variant),
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProvider%1$sLiveness implements LivenessProbe {
              public boolean isAlive() { return %2$s; }
            }
            """
                .formatted(variant, alive))
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProvider%1$sReadiness".formatted(variant),
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProvider%1$sReadiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """
                .formatted(variant))
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: %1$s
            isolation:
              tier: TIER_2
            resources:
              request:
                memory: 32Mi
                cpu: 20m
              limit:
                memory: 64Mi
                cpu: 100m
            exports:
              - service: com.gimle.examples.greeter.Greeter
                version: 1.0.0
            lifecycle:
              hooks: com.gimle.examples.greeter.provider.GreeterProvider%2$sHooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProvider%2$sLiveness
              readiness: com.gimle.examples.greeter.provider.GreeterProvider%2$sReadiness
            """
                .formatted(version, variant))
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(outputDir, "greeter-provider-" + variant.toLowerCase(Locale.ROOT) + ".jar");
  }

  /**
   * The jars the in-process compilation needs on its module path ({@code com.gimle.module}, {@code
   * org.slf4j}), scanned off this test JVM's own classpath -- matching either an installed jar or a
   * reactor-build {@code target/classes} directory, since which shape is present depends on how
   * this module was built. Package-private (not {@code private}) so {@link ModuleSystemFixtures}, a
   * sibling fixture-building class in this same package, can reuse it rather than re-scanning the
   * classpath a second way.
   */
  static List<Path> findCompileModulePathJars() {
    final List<Path> result = new ArrayList<>();
    final String classpath = System.getProperty("java.class.path");
    for (final String entry : classpath.split(File.pathSeparator)) {
      final String fileName = Path.of(entry).getFileName().toString();
      final String normalized = entry.replace('\\', '/');
      if (fileName.startsWith("slf4j-api-")
          && fileName.endsWith(".jar")
          && !fileName.contains("tests")) {
        result.add(Path.of(entry));
      }
      final boolean installedJar =
          fileName.startsWith("gimle-module-")
              && fileName.endsWith(".jar")
              && !fileName.contains("tests");
      final boolean reactorClasses = normalized.endsWith("/gimle-module/target/classes");
      if (installedJar || reactorClasses) {
        result.add(Path.of(entry));
      }
    }
    return result;
  }
}
