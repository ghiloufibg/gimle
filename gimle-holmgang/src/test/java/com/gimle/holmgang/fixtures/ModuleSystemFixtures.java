package com.gimle.holmgang.fixtures;

import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.util.List;

/**
 * Real, deployable module jars compiled at scenario run time exercising module-system mechanics
 * that {@code greeter-provider}/{@code greeter-consumer} don't reach on their own: cross-deployment
 * {@code requires:} dependency resolution and version-range gating, name-driven same-worker service
 * invocation and its version cutover, a hook that deliberately fails, a hook that never releases
 * its own in-flight counter, and a manifest that fails to parse. Every module here is {@code
 * TIER_1} so a single node's node agent packs multiple of them onto one shared worker JVM (see
 * {@code AgentMain#findReusableTier1Worker}) -- the same density mechanism that makes
 * cross-deployment {@code requires:} wiring reachable at all outside a unit test, since {@link
 * com.gimle.module.resolve.ModuleResolver} only ever looks for a dependency within its own worker's
 * registry.
 */
public final class ModuleSystemFixtures {

  private static final String LIB_NAME = "com.gimle.holmgang.fixtures.libgreeter";
  private static final String GREETER_INTERFACE = LIB_NAME + ".Greeter";

  private ModuleSystemFixtures() {}

  /**
   * Exports a {@code Greeter} service whose {@code greet} reply embeds {@code greetingSuffix} --
   * letting a caller tell which deployed version actually answered. Registered from {@code
   * onStart}, matching every other hook fixture's timing: a dependent wired to this module only
   * ever resolves once this is at least {@code RESOLVED}, but the service itself isn't published
   * until {@code ACTIVE}.
   */
  public static Path libGreeterJar(Path outputDir, String version, String greetingSuffix) {
    String jarName = "libgreeter-" + version + ".jar";
    return TestModuleBuilder.module(
            """
            module com.gimle.holmgang.fixtures.libgreeter {
              requires static com.gimle.module;
              requires static org.slf4j;
              exports com.gimle.holmgang.fixtures.libgreeter;
            }
            """)
        .withClass(
            GREETER_INTERFACE,
            """
            package com.gimle.holmgang.fixtures.libgreeter;
            public interface Greeter {
              String greet(String name);
            }
            """)
        .withClass(
            LIB_NAME + ".LibGreeterHooks",
            """
            package com.gimle.holmgang.fixtures.libgreeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            public final class LibGreeterHooks implements ModuleLifecycleHooks {
              private static final Logger log = LoggerFactory.getLogger(LibGreeterHooks.class);
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> "hello " + name + " %1$s");
                log.info("LIB-ACTIVE %1$s");
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """
                .formatted(greetingSuffix))
        .withDescriptor(
            """
            name: %1$s
            version: %2$s
            isolation:
              tier: TIER_1
            resources:
              request:
                memory: 16Mi
                cpu: 10m
              limit:
                memory: 32Mi
                cpu: 50m
            exports:
              - service: %3$s
                version: 1.0.0
            lifecycle:
              hooks: %1$s.LibGreeterHooks
            """
                .formatted(LIB_NAME, version, GREETER_INTERFACE))
        .dependsOn(ModuleFixtures.findCompileModulePathJars().toArray(Path[]::new))
        .build(outputDir, jarName);
  }

  /**
   * Declares {@code requires: [{module: libgreeter, version: requiredRange}]} and, once resolved,
   * invokes the wired dependency's {@code Greeter} once by name (no compile-time reference to its
   * interface at all -- {@link com.gimle.module.lifecycle.ModuleContext#invokeServiceByName} takes
   * only the interface name as a string) and logs the reply. Resolution itself is the thing under
   * test: an out-of-range {@code requiredRange} means {@code resolve()} throws before {@code
   * onStart} ever runs, so this module never logs anything and never reaches {@code ACTIVE}.
   */
  public static Path dependentInvokerJar(Path outputDir, String variant, String requiredRange) {
    String moduleName = "com.gimle.holmgang.fixtures.dependent" + variant;
    return TestModuleBuilder.module(
            """
            module %1$s {
              requires static com.gimle.module;
              requires static org.slf4j;
              exports %1$s;
            }
            """
                .formatted(moduleName))
        .withClass(
            moduleName + ".DependentHooks",
            """
            package %1$s;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            public final class DependentHooks implements ModuleLifecycleHooks {
              private static final Logger log = LoggerFactory.getLogger(DependentHooks.class);
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                try {
                  java.util.Optional<Object> reply = ctx.invokeServiceByName(
                      "%2$s", 1, "greet", new String[] {"java.lang.String"}, new Object[] {"world"});
                  log.info("GREETING: {}", reply.orElse("<empty>"));
                } catch (Throwable t) {
                  log.info("GREETING-ERROR: {}", t.toString());
                }
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """
                .formatted(moduleName, GREETER_INTERFACE))
        .withDescriptor(
            """
            name: %1$s
            version: 1.0.0
            requires:
              - module: %2$s
                version: "%3$s"
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
              hooks: %1$s.DependentHooks
            """
                .formatted(moduleName, LIB_NAME, requiredRange))
        .dependsOn(ModuleFixtures.findCompileModulePathJars().toArray(Path[]::new))
        .build(outputDir, "dependent-" + variant + ".jar");
  }

  /**
   * Declares no {@code requires:} at all -- a same-worker {@code invokeServiceByName} lookup needs
   * none, only a shared worker's {@link com.gimle.module.lifecycle.ServiceRegistry} -- and instead
   * repeatedly invokes the named {@code Greeter} on a background thread (its own MDC copy captured
   * and restored the same way {@code GreeterConsumerHooks} does, so the loop's own logging lands as
   * this instance's APPLICATION log rather than untagged PLATFORM output), so a scenario can
   * observe the registry's version-preference cutover: once a higher, ready version of the same
   * interface registers, later invocations start answering from it instead. Bounded to a couple of
   * minutes of iterations so a leftover worker in a pooled cluster doesn't spin forever.
   */
  public static Path serviceCutoverInvokerJar(Path outputDir) {
    String moduleName = "com.gimle.holmgang.fixtures.cutoverconsumer";
    return TestModuleBuilder.module(
            """
            module %1$s {
              requires static com.gimle.module;
              requires static org.slf4j;
              exports %1$s;
            }
            """
                .formatted(moduleName))
        .withClass(
            moduleName + ".CutoverConsumerHooks",
            """
            package %1$s;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            import java.util.Map;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.slf4j.MDC;
            public final class CutoverConsumerHooks implements ModuleLifecycleHooks {
              private static final Logger log = LoggerFactory.getLogger(CutoverConsumerHooks.class);
              private static final int MAX_ITERATIONS = 240;
              private volatile boolean stopped;
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                Map<String, String> mdcTags = MDC.getCopyOfContextMap();
                Thread.ofVirtual().name("cutover-consumer-caller").start(() -> loop(ctx, mdcTags));
              }
              public void onStop(ModuleContext ctx) {
                stopped = true;
              }
              public void onUninstall(ModuleContext ctx) {}
              private void loop(ModuleContext ctx, Map<String, String> mdcTags) {
                if (mdcTags != null) {
                  MDC.setContextMap(mdcTags);
                }
                for (int i = 0; i < MAX_ITERATIONS && !stopped; i++) {
                  try {
                    java.util.Optional<Object> reply = ctx.invokeServiceByName(
                        "%2$s", 1, "greet", new String[] {"java.lang.String"}, new Object[] {"world"});
                    log.info("GREETING: {}", reply.orElse("<empty>"));
                  } catch (Throwable t) {
                    log.info("GREETING-ERROR: {}", t.toString());
                  }
                  java.util.concurrent.locks.LockSupport.parkNanos(500_000_000L);
                  if (Thread.currentThread().isInterrupted()) {
                    return;
                  }
                }
              }
            }
            """
                .formatted(moduleName, GREETER_INTERFACE))
        .withDescriptor(
            """
            name: %1$s
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
              hooks: %1$s.CutoverConsumerHooks
            """
                .formatted(moduleName))
        .dependsOn(ModuleFixtures.findCompileModulePathJars().toArray(Path[]::new))
        .build(outputDir, "cutover-consumer.jar");
  }

  /** A module whose {@code onStart} hook always throws, so it never reaches {@code ACTIVE}. */
  public static Path onStartThrowsJar(Path outputDir) {
    String moduleName = "com.gimle.holmgang.fixtures.brokenstart";
    return TestModuleBuilder.module(
            """
            module %1$s {
              requires static com.gimle.module;
              exports %1$s;
            }
            """
                .formatted(moduleName))
        .withClass(
            moduleName + ".BrokenStartHooks",
            """
            package %1$s;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class BrokenStartHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                throw new IllegalStateException("deliberate onStart failure for a Holmgang scenario");
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """
                .formatted(moduleName))
        .withDescriptor(
            """
            name: %1$s
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
              hooks: %1$s.BrokenStartHooks
            """
                .formatted(moduleName))
        .dependsOn(ModuleFixtures.findCompileModulePathJars().toArray(Path[]::new))
        .build(outputDir, "broken-start.jar");
  }

  /**
   * Calls {@code ctx.beginRequest()} from {@code onStart} and never {@code endRequest()} -- a
   * permanently in-flight request, so deleting this deployment exercises {@link
   * com.gimle.module.lifecycle.ModuleController#stop}'s drain-then-dispose-anyway path instead of
   * the drain wait finishing early because nothing was ever in flight.
   */
  public static Path perpetualInFlightJar(Path outputDir) {
    String moduleName = "com.gimle.holmgang.fixtures.stuckrequest";
    return TestModuleBuilder.module(
            """
            module %1$s {
              requires static com.gimle.module;
              requires static org.slf4j;
              exports %1$s;
            }
            """
                .formatted(moduleName))
        .withClass(
            moduleName + ".StuckRequestHooks",
            """
            package %1$s;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            public final class StuckRequestHooks implements ModuleLifecycleHooks {
              private static final Logger log = LoggerFactory.getLogger(StuckRequestHooks.class);
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.beginRequest();
                log.info("STUCK-IN-FLIGHT");
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """
                .formatted(moduleName))
        .withDescriptor(
            """
            name: %1$s
            version: 1.0.0
            isolation:
              tier: TIER_2
            resources:
              request:
                memory: 16Mi
                cpu: 10m
              limit:
                memory: 32Mi
                cpu: 50m
            lifecycle:
              hooks: %1$s.StuckRequestHooks
            """
                .formatted(moduleName))
        .dependsOn(ModuleFixtures.findCompileModulePathJars().toArray(Path[]::new))
        .build(outputDir, "perpetual-in-flight.jar");
  }

  /**
   * A structurally real JPMS module jar (real {@code module-info.class}, no hooks referenced) whose
   * {@code gimle-module.yaml} fails {@link com.gimle.module.descriptor.ModuleDescriptorParser}'s
   * own validation -- {@code variant} selects which invariant it breaks: {@code "bad-tier"} names
   * an {@code isolation.tier} outside {@code TIER_1}/{@code TIER_2}/{@code TIER_3}, {@code
   * "request-exceeds-limit"} declares a resource request above its own limit.
   */
  public static Path malformedManifestJar(Path outputDir, String variant) {
    String moduleName = "com.gimle.holmgang.fixtures.malformed" + variant.replace("-", "");
    String descriptor =
        switch (variant) {
          case "bad-tier" ->
              """
              name: %1$s
              version: 1.0.0
              isolation:
                tier: BOGUS_TIER
              resources:
                request:
                  memory: 16Mi
                  cpu: 10m
                limit:
                  memory: 32Mi
                  cpu: 50m
              """
                  .formatted(moduleName);
          case "request-exceeds-limit" ->
              """
              name: %1$s
              version: 1.0.0
              isolation:
                tier: TIER_1
              resources:
                request:
                  memory: 128Mi
                  cpu: 100m
                limit:
                  memory: 32Mi
                  cpu: 50m
              """
                  .formatted(moduleName);
          default ->
              throw new IllegalArgumentException("unknown malformed manifest variant: " + variant);
        };
    return TestModuleBuilder.module(
            """
            module %1$s {
              requires static com.gimle.module;
            }
            """
                .formatted(moduleName))
        .withDescriptor(descriptor)
        .dependsOn(ModuleFixtures.findCompileModulePathJars().toArray(Path[]::new))
        .build(outputDir, "malformed-" + variant + ".jar");
  }

  /**
   * The same always-fails-liveness provider {@link ModuleFixtures#alwaysUnhealthyProviderJar}
   * builds, but with an explicit {@code health.initialDelaySeconds} -- proving the worker's probe
   * loop honors {@link com.gimle.core.module.HealthProbes#initialDelay()} rather than ticking the
   * very first probe immediately after {@code ACTIVE}.
   */
  public static Path unhealthyProviderWithInitialDelayJar(Path outputDir, int initialDelaySeconds) {
    List<Path> compileJars = ModuleFixtures.findCompileModulePathJars();
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
            "com.gimle.examples.greeter.provider.GreeterProviderDelayedHooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class GreeterProviderDelayedHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> "Hello, " + name + "! (delayed)");
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderDelayedLiveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderDelayedLiveness implements LivenessProbe {
              public boolean isAlive() { return false; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderDelayedReadiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderDelayedReadiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.0.0-delayed
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
              hooks: com.gimle.examples.greeter.provider.GreeterProviderDelayedHooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderDelayedLiveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderDelayedReadiness
              initialDelaySeconds: %1$d
            """
                .formatted(initialDelaySeconds))
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(outputDir, "greeter-provider-delayed.jar");
  }
}
