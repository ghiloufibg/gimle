package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.protocol.Json;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared real-cluster fixture for every {@code gimle-smoke-tests} scenario in this package: spawns
 * a genuine {@code gimle-mimir} store cluster ({@link #STORE_COUNT} {@code StoreMain} processes)
 * plus multiple {@code ControlPlaneMain} replicas ({@link #CONTROLPLANE_COUNT}) sharing that same
 * store cluster, one {@code AgentMain} (+ its own {@code WorkerMain} child), {@link #FAFNIR_COUNT}
 * {@code FafnirMain} replicas, and one {@code MuninnMain} -- real separate OS processes via {@code
 * ProcessBuilder}, never mocked or in-process (unlike {@code
 * ControlPlaneAgentWorkerIntegrationTest} in gimle-agent, which constructs {@code ApiServer}
 * in-JVM). Every concrete {@code *IT} class in this package extends this fixture and adds only the
 * {@code @Test} methods for its own topic -- see each subclass's own javadoc for what it covers.
 * Splitting the original single, 2500-line {@code GreeterSmokeTestIT} into topic-grouped classes
 * sharing this common base is a pure refactor: no behavior change, same real subprocess cluster,
 * same helpers, just organized for clarity and separation of concerns as the number of real-cluster
 * scenarios grew.
 *
 * <p>Uses ports distinct from {@code gimle-console/LOCAL_DEV.md}'s manual walkthrough (8080/9080)
 * so this can run alongside a developer's own manually-started cluster without colliding.
 *
 * <p>Not part of the default {@code mvn verify} -- opt in with {@code -Psmoke}. Assumes {@code mvn
 * install} has already produced every jar this launches, the same precondition LOCAL_DEV.md's own
 * manual flow has. Every concrete subclass carries its own {@code @Tag("smoke")} plus this module's
 * own {@code excludedGroups} surefire configuration -- belt-and-suspenders against a real,
 * previously-observed build-wiring gap (see FLAKY_TESTS.md): an unqualified {@code
 * -Dtest='!A,!B,...'} (exclusions only, no positive pattern) has been seen to broaden Surefire's
 * own default class discovery enough to pick a class up under plain {@code mvn verify} anyway --
 * JUnit 5's own tag-based filtering is a separate mechanism from Surefire's class-name-pattern
 * selection, so it isn't affected the same way.
 */
abstract class GreeterSmokeClusterSupport {

  static final int STORE_COUNT = 3;

  static final int CONTROLPLANE_COUNT = 2;

  static final int FAFNIR_COUNT = 2;

  static final int STORE_RAFT_PORT_BASE = 19080;

  static final int STORE_CLIENT_PORT_BASE = 19091;

  static final int CONTROLPLANE_PORT_BASE = 18080;

  static final int FAFNIR_PORT_BASE = 19060;

  static final int MUNINN_PORT = 19070;

  static final String GOSSIP_ADDRESS = "127.0.0.1:19090";

  static final String GIMLE_VERSION = "0.1.0-SNAPSHOT";

  // The one tenant this suite exercises the real secret round trip for: an untenanted deployment
  // never has config/secrets delivered at all (AgentMain#deliverConfig returns immediately when
  // AssignedInstance#tenantId is empty), so a real end-to-end check needs a genuine registered
  // Tenant, not just a bare deployment.
  static final String SECRET_TENANT_ID = "smoke-tenant";

  static final String SECRET_KEY = "some-secret-key";

  static final String SECRET_VALUE = "smoke-test-secret-value";

  // A real *plain* (non-encrypted) config entry for the same tenant -- distinct from SECRET_KEY
  // above, which is masked by design. Exercises the console's Config screen (gimle-console/src/
  // routes/config.tsx), which reads /config/* directly rather than the masked /secrets/* surface
  // the Secrets screen already covers.
  static final String PLAIN_CONFIG_KEY = "greeting.locale";

  static final String PLAIN_CONFIG_VALUE = "en-US";

  // Matches gimle-console/e2e/greeter-smoke.spec.ts's own login credentials -- that suite logs in
  // as this account before touching any /console page (RBAC/session auth, commit 05af65d, gates
  // every console route client-side regardless of transport). Created via an unauthenticated PUT
  // below: plaintext mode's ApiServer#requireAuthorized bypasses auth entirely (there is nothing
  // real to protect without TLS), so no prior identity is needed to create the first account.
  static final String SMOKE_OPERATOR_USERNAME = "smoke-operator";

  static final String SMOKE_OPERATOR_PASSWORD = "smoke-operator-password";

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  final List<Process> processes = new ArrayList<>();

  final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void tearDown() {
    // Reverse of spawn order (store nodes, then control-plane replicas, then the agent), so
    // nothing still-running races a peer it depends on already being gone. Killing an
    // already-dead process (e.g. one a test killed itself mid-run) is a harmless no-op.
    for (int i = processes.size() - 1; i >= 0; i--) {
      killWithDescendants(processes.get(i));
    }
  }

  static void killWithDescendants(Process process) {
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroyForcibly();
  }

  /**
   * The single {@code WorkerMain} child process descendant of a supervising {@code AgentMain}, if
   * one currently exists. Matched on {@code -Dgimle.log.root=.../workers/...} ({@code
   * AgentMain#buildWorkerCommand}'s own worker-only flag, scoping that worker's default log root to
   * a subdirectory named after it) rather than the trailing {@code com.gimle.worker.WorkerMain}
   * main-class argument: {@code ProcessHandle.Info#commandLine()} truncates a very long command
   * line (this repo's own many-module classpath easily exceeds whatever internal buffer it uses)
   * before reaching an argument that far into the command, a real, previously-observed gap between
   * what {@code ProcessBuilder} was actually given and what comes back out through {@code
   * ProcessHandle}. This flag sits early enough to survive that.
   */
  static Optional<ProcessHandle> findWorkerDescendant(Process agentProcess) {
    return agentProcess
        .descendants()
        .filter(
            handle ->
                handle
                    .info()
                    .commandLine()
                    .map(line -> line.contains("-Dgimle.log.root=") && line.contains("/workers/"))
                    .orElse(false))
        .findFirst();
  }

  /**
   * Every live worker descendant, not just the first -- the same match predicate as {@link
   * #findWorkerDescendant}, needed by anything asserting how many real worker JVMs the agent
   * currently supervises (e.g. Tier 1 density packing, where several module instances sharing one
   * worker should collapse to a single match here).
   */
  static List<ProcessHandle> findWorkerDescendants(Process agentProcess) {
    return agentProcess
        .descendants()
        .filter(
            handle ->
                handle
                    .info()
                    .commandLine()
                    .map(line -> line.contains("-Dgimle.log.root=") && line.contains("/workers/"))
                    .orElse(false))
        .toList();
  }

  /** What a test needs to talk to a freshly-started store-cluster + control-plane-replica set. */
  record SmokeCluster(
      List<Process> storeProcesses,
      List<String> controlPlaneBaseUrls,
      Process agentProcess,
      String muninnEndpoint) {}

  void putTenantQuota(
      String baseUrl, String tenantId, long maxMemoryBytes, long maxCpuMillicores, int maxInstances)
      throws Exception {
    String quotaBody =
        Json.write(
            Map.of(
                "quota",
                Map.of(
                    "maxMemoryBytes",
                    maxMemoryBytes,
                    "maxCpuMillicores",
                    maxCpuMillicores,
                    "maxInstances",
                    maxInstances)));
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + tenantId))
                .PUT(HttpRequest.BodyPublishers.ofString(quotaBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("tenant quota update failed: " + response.statusCode() + " " + response.body());
    }
  }

  boolean isQuotaViolating(String baseUrl, String deploymentName) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      return Boolean.TRUE.equals(status.get("quotaViolating"));
    } catch (Exception e) {
      return false;
    }
  }

  void cordonNode(String baseUrl, String nodeId) throws Exception {
    setNodeCordon(baseUrl, nodeId, "cordon");
  }

  void uncordonNode(String baseUrl, String nodeId) throws Exception {
    setNodeCordon(baseUrl, nodeId, "uncordon");
  }

  private void setNodeCordon(String baseUrl, String nodeId, String action) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/nodes/" + nodeId + "/" + action))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("node " + action + " failed: " + response.statusCode() + " " + response.body());
    }
  }

  /**
   * True once {@code GET /nodes} reports {@code nodeId}'s own {@code "cordoned"} field ({@code
   * ApiServer}'s own node-listing response) matching {@code expected} -- a direct read of the API's
   * own reported state, distinct from {@link #isActive}/{@link #deploymentStatus}'s
   * deployment-scoped checks, since cordoning is a node-scoped flag with no deployment of its own.
   */
  boolean nodeCordonedIs(String baseUrl, String nodeId, boolean expected) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/nodes")).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return false;
      }
      List<Object> nodes = Json.asArray(Json.parse(response.body()));
      for (Object entry : nodes) {
        Map<String, Object> node = Json.asObject(entry);
        if (nodeId.equals(node.get("nodeId"))) {
          return Boolean.valueOf(expected).equals(node.get("cordoned"));
        }
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Compiles a real v1.1.0 {@code greeter-provider} at test run time via {@link TestModuleBuilder},
   * same module name as the committed v1.0.0 example (so {@code DeploymentReconciler} recognizes it
   * as a version bump of the same deployment, not a different module), different version and
   * greeting text so a running instance's reported {@code moduleId.version} unambiguously proves
   * which build is actually serving.
   */
  Path buildProviderV2Jar() {
    List<Path> compileJars = findCompileModulePathJars();
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
            "com.gimle.examples.greeter.provider.GreeterProviderV2Hooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class GreeterProviderV2Hooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> "Hello, " + name + "! (from provider v2)");
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderV2Liveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderV2Liveness implements LivenessProbe {
              public boolean isAlive() { return true; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderV2Readiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderV2Readiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.1.0
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
              hooks: com.gimle.examples.greeter.provider.GreeterProviderV2Hooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderV2Liveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderV2Readiness
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "greeter-provider-v2.jar");
  }

  /**
   * A real {@code greeter-provider} whose {@code greet} deterministically fails half the time
   * (every other call, via a static counter -- deterministic rather than random so the real failure
   * rate this produces is exactly predictable: ~50%) -- the fabric server-side dispatch that
   * invokes it records each thrown exception as a real error against this instance's own {@code
   * WorkerMetrics} (see {@code FabricServer#dispatch}), which is exactly the {@code
   * errorRatePerSecond} signal {@code AutoscaleReconciler}'s {@code targetErrorRatePercent} reads.
   * Built via {@link TestModuleBuilder} rather than modifying the real, committed example module,
   * same reasoning as {@link #buildProviderV2Jar()}.
   */
  Path buildFaultyProviderJar() {
    List<Path> compileJars = findCompileModulePathJars();
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
            "com.gimle.examples.greeter.provider.GreeterProviderFaultyHooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            import java.util.concurrent.atomic.AtomicLong;
            public final class GreeterProviderFaultyHooks implements ModuleLifecycleHooks {
              private static final AtomicLong CALLS = new AtomicLong();
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> {
                  if (CALLS.incrementAndGet() % 2 == 0) {
                    throw new IllegalStateException("synthetic QA fault");
                  }
                  return "Hello, " + name + "! (from faulty provider)";
                });
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderFaultyLiveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderFaultyLiveness implements LivenessProbe {
              public boolean isAlive() { return true; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderFaultyReadiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderFaultyReadiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.0.0-faulty
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
              hooks: com.gimle.examples.greeter.provider.GreeterProviderFaultyHooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderFaultyLiveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderFaultyReadiness
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "greeter-provider-faulty.jar");
  }

  /**
   * A real {@code greeter-provider} whose {@code greet} hangs well past {@code FabricClient
   * #DEFAULT_TIMEOUT} (5s) on <em>every</em> call, rather than throwing. Deliberately not the shape
   * {@link #buildFaultyProviderJar()} uses: {@code FabricServiceRegistry#invokeRemote}'s own P2-6
   * app-error-vs-transport-error split (see its javadoc) scores a method that merely *throws* as
   * {@code breaker.recordSuccess()} -- "proof the endpoint was reachable and answered, not a
   * transport failure" -- so it can never open the breaker no matter how many calls fail that way.
   * Only the {@link java.io.IOException} branch (a genuine dispatch failure -- here, the caller's
   * own read timeout firing on a response that never comes) reaches {@code recordFailure()}, so
   * this fixture has to actually cause one. Exports the same {@code Greeter} service/version as the
   * real committed provider so both are genuine, simultaneously eligible candidates for the same
   * lookup -- the point being to prove the breaker excludes this one specifically, in a real
   * separate worker process, not a same-JVM mock.
   *
   * <p>The sleep only needs to outlast {@code FabricClient#DEFAULT_TIMEOUT} (5s), not run for a
   * long time -- an earlier version slept a full 60s, which (with the real consumer calling every
   * 5s) let several calls pile up concurrently inside {@code BoundedModuleScheduler}'s own
   * concurrency bound, each one occupying a permit and a blocked virtual thread for a full minute.
   * That pileup was enough to destabilize the instance and repeatedly trigger a real dispose +
   * redeploy cycle unrelated to the circuit breaker -- confirmed via the module's own
   * Stopping/Uninstalled/Resolved/Starting/Active churn in {@code worker-platform.log}, well
   * outside anything either {@code LivenessProbe} or {@code ReadinessProbe} here ever reports (both
   * always {@code true}) -- which reset the endpoint's {@code ServiceEndpoint} identity (and
   * therefore its {@code CircuitBreaker}'s accumulated state) on every cycle. A few seconds past
   * the timeout gets the same genuine {@link java.io.IOException} without the pileup.
   */
  Path buildAlwaysBrokenProviderJar() {
    List<Path> compileJars = findCompileModulePathJars();
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
            "com.gimle.examples.greeter.provider.GreeterProviderBrokenHooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class GreeterProviderBrokenHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(
                    Greeter.class,
                    name -> {
                      try {
                        Thread.sleep(8_000);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      throw new IllegalStateException("unreachable: caller times out first");
                    });
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderBrokenLiveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderBrokenLiveness implements LivenessProbe {
              public boolean isAlive() { return true; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderBrokenReadiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderBrokenReadiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.0.0-broken
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
              hooks: com.gimle.examples.greeter.provider.GreeterProviderBrokenHooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderBrokenLiveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderBrokenReadiness
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "greeter-provider-broken.jar");
  }

  /**
   * A real {@code greeter-provider} whose {@code LivenessProbe} always reports {@code isAlive() ==
   * false} -- unlike {@link #buildFaultyProviderJar()} (which fails at *call* time), this module
   * starts and serves normally but never passes its own liveness check, so {@code
   * WorkerRuntime#onLivenessResult} accumulates consecutive failures past {@code
   * livenessFailureThreshold} and drives its own module-tier {@code RestartTracker} (dispose +
   * reinstantiate, {@code WorkerRuntime#newRestartTracker}) through repeated backoff-delayed
   * restart attempts -- the module tier of the same escalation chain {@link
   * #buildFaultyProviderJar()}'s worker-JVM-kill sibling test exercises one tier up. Once that
   * tracker's budget (5 attempts / 60s window) is exhausted, {@code ModuleController#forceFailed}
   * flips the instance's {@code lifecycleState} to {@code FAILED} for good -- there is no path back
   * to {@code ACTIVE} without a fresh deploy. Built via {@link TestModuleBuilder}, same reasoning
   * as {@link #buildProviderV2Jar()}.
   */
  Path buildAlwaysUnhealthyProviderJar() {
    List<Path> compileJars = findCompileModulePathJars();
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
            "com.gimle.examples.greeter.provider.GreeterProviderUnhealthyHooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class GreeterProviderUnhealthyHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(
                    Greeter.class, name -> "Hello, " + name + "! (from unhealthy provider)");
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderAlwaysDeadLiveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderAlwaysDeadLiveness implements LivenessProbe {
              public boolean isAlive() { return false; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderUnhealthyReadiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderUnhealthyReadiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.0.0-unhealthy
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
              hooks: com.gimle.examples.greeter.provider.GreeterProviderUnhealthyHooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderAlwaysDeadLiveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderUnhealthyReadiness
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "greeter-provider-unhealthy.jar");
  }

  /**
   * A minimal {@code TIER_1} module whose {@code onStart} deliberately leaves a platform thread
   * running that {@code onStop} never interrupts -- the classic real-world classloader-leak bug
   * pattern (a background thread that outlives the module that started it). The thread's own {@code
   * Runnable} is a class loaded by this module's own {@link ModuleLayer}, so as long as the thread
   * itself keeps running, that classloader stays reachable -- and therefore un-collectable -- on
   * purpose, exactly what {@code WorkerRuntime}'s {@code LeakTracker} (wired into a real worker via
   * {@code WorkerMain}) exists to catch. {@code TIER_1} rather than {@code TIER_2} deliberately:
   * undeploying this module must not tear down its own worker process before {@code LeakTracker}'s
   * detection window has a chance to fire, which only Tier 1 density's "worker survives as long as
   * another instance shares it" guarantee provides -- see the caller's own anchor-module reasoning.
   */
  Path buildLeakyProviderJar(String version) {
    List<Path> compileJars = findCompileModulePathJars();
    return TestModuleBuilder.module(
            """
            module com.gimle.fixture.leaky {
              requires static com.gimle.module;
              exports com.gimle.fixture.leaky;
            }
            """)
        .withClass(
            "com.gimle.fixture.leaky.LeakyHooks",
            """
            package com.gimle.fixture.leaky;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class LeakyHooks implements ModuleLifecycleHooks {
              static final class LeakyRunnable implements Runnable {
                public void run() {
                  while (!Thread.currentThread().isInterrupted()) {
                    try {
                      Thread.sleep(1000);
                    } catch (InterruptedException e) {
                      return;
                    }
                  }
                }
              }
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                Thread.ofPlatform().name("leaky-module-thread").start(new LeakyRunnable());
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.fixture.leaky
            version: %s
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
              hooks: com.gimle.fixture.leaky.LeakyHooks
            """
                .formatted(version))
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "leaky-provider-" + version + ".jar");
  }

  /**
   * A minimal, deliberately inert real {@code TIER_1} module, parameterized by module name so it
   * can be built several times as genuinely distinct modules -- {@code AgentMain
   * #findReusableTier1Worker}'s own {@code noModuleConflict} check unconditionally refuses to pack
   * two replicas of the *same* module onto one worker, so proving density packing for real needs
   * several different module identities, not several deployments of one. {@code moduleName} doubles
   * as the Java module name and the sole package it exports, matching every other fixture in this
   * class.
   */
  Path buildInertTier1ModuleJar(String moduleName, String version) {
    List<Path> compileJars = findCompileModulePathJars();
    return TestModuleBuilder.module(
            """
            module %s {
              requires static com.gimle.module;
              exports %s;
            }
            """
                .formatted(moduleName, moduleName))
        .withClass(
            moduleName + ".InertHooks",
            """
            package %s;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class InertHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {}
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """
                .formatted(moduleName))
        .withDescriptor(
            """
            name: %s
            version: %s
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
              hooks: %s.InertHooks
            """
                .formatted(moduleName, version, moduleName))
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, moduleName + "-" + version + ".jar");
  }

  /**
   * A real {@code greeter-provider} whose {@code greet} always sleeps ~300ms before returning --
   * paired with sustained concurrent (not just rate-limited) load, this is what actually builds a
   * real backlog on {@code WorkerRuntime}'s per-module {@code BoundedModuleScheduler} (concurrency
   * bound 4): a fixed request rate alone says nothing about how many requests are in flight at
   * once, but enough slow, concurrent calls queue behind that bound the same way a real slow
   * downstream dependency would. {@code queueDepth} is reported straight off that scheduler (see
   * {@code WorkerMain#metricsReportLoop}), which is exactly the signal {@code
   * AutoscaleReconciler}'s {@code targetQueueDepth} reads. Built via {@link TestModuleBuilder},
   * same reasoning as {@link #buildProviderV2Jar()}.
   */
  Path buildSlowProviderJar() {
    List<Path> compileJars = findCompileModulePathJars();
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
            "com.gimle.examples.greeter.provider.GreeterProviderSlowHooks",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.examples.greeter.Greeter;
            import com.gimle.module.lifecycle.ModuleContext;
            import com.gimle.module.lifecycle.ModuleLifecycleHooks;
            public final class GreeterProviderSlowHooks implements ModuleLifecycleHooks {
              public void onInstall(ModuleContext ctx) {}
              public void onStart(ModuleContext ctx) {
                ctx.registerService(Greeter.class, name -> {
                  try {
                    Thread.sleep(300);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return "Hello, " + name + "! (from slow provider)";
                });
              }
              public void onStop(ModuleContext ctx) {}
              public void onUninstall(ModuleContext ctx) {}
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderSlowLiveness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.LivenessProbe;
            public final class GreeterProviderSlowLiveness implements LivenessProbe {
              public boolean isAlive() { return true; }
            }
            """)
        .withClass(
            "com.gimle.examples.greeter.provider.GreeterProviderSlowReadiness",
            """
            package com.gimle.examples.greeter.provider;
            import com.gimle.module.probe.ReadinessProbe;
            public final class GreeterProviderSlowReadiness implements ReadinessProbe {
              public boolean isReady() { return true; }
            }
            """)
        .withDescriptor(
            """
            name: com.gimle.examples.greeter.provider
            version: 1.0.0-slow
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
              hooks: com.gimle.examples.greeter.provider.GreeterProviderSlowHooks
            health:
              liveness: com.gimle.examples.greeter.provider.GreeterProviderSlowLiveness
              readiness: com.gimle.examples.greeter.provider.GreeterProviderSlowReadiness
            """)
        .dependsOn(compileJars.toArray(Path[]::new))
        .build(tempDir, "greeter-provider-slow.jar");
  }

  /**
   * Scans this test JVM's own classpath for the jars {@link #buildProviderV2Jar()}'s compilation
   * needs on its module path ({@code com.gimle.module}, {@code org.slf4j}) -- mirrors {@code
   * RealBundledHookAndProbeInvocationTest#findPlatformJars} (gimle-worker), matching either an
   * installed jar in the local repo or a reactor-build {@code target/classes} directory, since
   * which shape is on the classpath depends on whether this module is built standalone or as part
   * of a multi-module reactor build.
   */
  static List<Path> findCompileModulePathJars() {
    String[] jarNeedles = {"slf4j-api-"};
    String[] moduleArtifacts = {"gimle-module"};
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
        boolean reactorClasses = normalized.endsWith("/" + artifact + "/target/classes");
        if ((installedJar && !fileName.contains("tests")) || reactorClasses) {
          result.add(Path.of(entry));
        }
      }
    }
    return result;
  }

  /**
   * Like {@link #submitDeployment}, but with a caller-chosen replica count and module version
   * (rather than the hardcoded {@code replicas: 1}/{@code version: 1.0.0} that helper writes) --
   * {@link com.gimle.mimir.manifest.DeploymentManifestParser#parseModuleId} reads the manifest's
   * own declared {@code module.version} field verbatim as {@code spec.moduleId()}, never re-derived
   * from the artifact's real descriptor inside the jar, so a version-bump rollout must state the
   * new version here explicitly or {@code DeploymentReconciler} would never see a {@code moduleId}
   * mismatch to trigger a rolling migration on.
   */
  void submitDeploymentWithReplicas(
      String baseUrl,
      String deploymentName,
      String moduleName,
      String version,
      Path jar,
      int replicas)
      throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        """
            .formatted(deploymentName, moduleName, version, jar.toAbsolutePath(), replicas);
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("deployment submission failed: " + response.statusCode() + " " + response.body());
    }
  }

  /**
   * Like {@link #submitDeploymentWithRetry}, but for {@link #submitDeploymentWithReplicas} -- the
   * store cluster can transiently reject a write with a 503 mid-election (see {@code
   * submitDeploymentWithRetry}'s own call sites for the same real, previously-observed behavior),
   * so every submission in this suite that isn't guaranteed to land against an
   * already-proven-stable cluster retries rather than failing outright on the first transient
   * rejection.
   */
  void submitDeploymentWithReplicasWithRetry(
      String baseUrl,
      String deploymentName,
      String moduleName,
      String version,
      Path jar,
      int replicas,
      Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        submitDeploymentWithReplicas(baseUrl, deploymentName, moduleName, version, jar, replicas);
        return;
      } catch (AssertionError e) {
        if (System.nanoTime() > deadline) {
          throw e;
        }
        Thread.sleep(500);
      }
    }
  }

  /**
   * True once exactly {@code expectedCount} of {@code deploymentName}'s real placed instances
   * report both {@code lifecycleState == ACTIVE} and {@code observation.moduleId.version ==
   * version} -- unlike {@link #isActive}, this also checks the *version* actually running, which is
   * the whole point of a rolling-update assertion (reaching ACTIVE again proves nothing about which
   * build is serving).
   */
  boolean allInstancesOnVersion(
      String baseUrl, String deploymentName, String version, int expectedCount) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
      if (instances.size() != expectedCount) {
        return false;
      }
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (!(observation instanceof Map<?, ?> obsMap)
            || !"ACTIVE".equals(obsMap.get("lifecycleState"))) {
          return false;
        }
        Object moduleId = obsMap.get("moduleId");
        if (!(moduleId instanceof Map<?, ?> moduleIdMap)
            || !version.equals(moduleIdMap.get("version"))) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * {@code moduleName}/{@code moduleVersion} are explicit rather than hardcoded to greeter-
   * provider's own committed 1.0.0 -- error-rate and queue-depth scenarios deploy a purpose-built
   * chaos variant compiled on the fly by {@link TestModuleBuilder} (see {@link
   * #buildFaultyProviderJar()}/{@link #buildSlowProviderJar()}) rather than modifying the real,
   * committed example module just to inject synthetic faults/latency into it. {@code
   * targetRequestRatePerSecond}/{@code targetErrorRatePercent}/{@code targetQueueDepth} are each
   * independently optional, matching {@code AutoscalePolicy}'s own shape -- an absent one is simply
   * omitted from the manifest, never evaluated by {@code AutoscaleReconciler}.
   */
  void submitAutoscaleDeployment(
      String baseUrl,
      String deploymentName,
      String moduleName,
      String moduleVersion,
      Path jar,
      int minReplicas,
      int maxReplicas,
      int targetCpuUtilizationPercent,
      Optional<Double> targetRequestRatePerSecond,
      Optional<Double> targetErrorRatePercent,
      Optional<Integer> targetQueueDepth)
      throws Exception {
    StringBuilder autoscaleBlock =
        new StringBuilder("autoscale:\n")
            .append("  minReplicas: ")
            .append(minReplicas)
            .append('\n')
            .append("  maxReplicas: ")
            .append(maxReplicas)
            .append('\n')
            .append("  targetCpuUtilizationPercent: ")
            .append(targetCpuUtilizationPercent)
            .append('\n');
    targetRequestRatePerSecond.ifPresent(
        value ->
            autoscaleBlock.append("  targetRequestRatePerSecond: ").append(value).append('\n'));
    targetErrorRatePercent.ifPresent(
        value -> autoscaleBlock.append("  targetErrorRatePercent: ").append(value).append('\n'));
    targetQueueDepth.ifPresent(
        value -> autoscaleBlock.append("  targetQueueDepth: ").append(value).append('\n'));

    String manifest =
        """
        name: %s
        module:
          name: %s
          version: %s
        artifactPath: %s
        replicas: %d
        %s"""
            .formatted(
                deploymentName,
                moduleName,
                moduleVersion,
                jar.toAbsolutePath(),
                minReplicas,
                autoscaleBlock);
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail(
          "autoscale deployment submission failed: "
              + response.statusCode()
              + " "
              + response.body());
    }
  }

  /** How many of {@code deploymentName}'s real placed instances currently report ACTIVE. */
  int activeInstanceCount(String baseUrl, String deploymentName) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
      int active = 0;
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (observation instanceof Map<?, ?> obsMap
            && "ACTIVE".equals(obsMap.get("lifecycleState"))) {
          active++;
        }
      }
      return active;
    } catch (Exception e) {
      return 0;
    }
  }

  /**
   * Spawns {@code GreeterAutoscaleSimulation} as {@code io.gatling.app.Gatling}'s own CLI, the same
   * shape every other cluster component in this suite is spawned in (Playwright's own {@code bun
   * run test:e2e} is the closest precedent: a separate process, a separate toolchain, never
   * embedded in this JVM). {@code --add-opens java.base/java.lang=ALL-UNNAMED} is a real,
   * documented Gatling requirement on modern JDKs (its stats writer reflects into {@code
   * java.lang.String} internals for a fast-path encoder) -- omitting it fails the run immediately
   * with an {@code IllegalAccessException}, not a silent degradation. {@code -nr} skips HTML report
   * generation: this test cares about the real side effect (greeter-provider's own worker-reported
   * request rate rising and the reconciler scaling up in response), not Gatling's own charts.
   */
  Process spawnGatling(
      String javaExecutable,
      String classpath,
      int requestsPerSecond,
      int durationSeconds,
      Path logFile)
      throws IOException {
    return spawnGatling(
        javaExecutable,
        classpath,
        requestsPerSecond,
        durationSeconds,
        /* concurrentUsers= */ 0,
        logFile);
  }

  /**
   * Like {@link #spawnGatling(String, String, int, int, Path)}, but with a caller-chosen closed-
   * model concurrency instead of the open-model {@code requestsPerSecond} rate -- see {@code
   * GreeterAutoscaleSimulation}'s own javadoc on why a fixed request rate alone can't build a real
   * queue-depth backlog, only a sustained concurrent-in-flight count can. {@code concurrentUsers <=
   * 0} keeps the existing open-model behavior; {@code requestsPerSecond} is simply ignored in that
   * case, the same shape the two-arg overload above already implies.
   */
  Process spawnGatling(
      String javaExecutable,
      String classpath,
      int requestsPerSecond,
      int durationSeconds,
      int concurrentUsers,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "--add-opens",
            "java.base/java.lang=ALL-UNNAMED",
            "-cp",
            classpath,
            "-Dgimle.load.baseUrl=http://127.0.0.1:19077",
            "-Dgimle.load.requestsPerSecond=" + requestsPerSecond,
            "-Dgimle.load.durationSeconds=" + durationSeconds,
            "-Dgimle.load.concurrentUsers=" + concurrentUsers,
            "io.gatling.app.Gatling",
            "-s",
            "com.gimle.smoketests.load.GreeterAutoscaleSimulation",
            "-rf",
            tempDir.resolve("gatling-results").toString(),
            "-nr");
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  boolean metricsHistoryShowsDeploymentsRequestCount(String baseUrl, String processId) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/metrics-history/CONTROLPLANE/" + processId))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200
          && response.body().contains("gimle.controlplane.request.count")
          && response.body().contains("deployments");
    } catch (Exception e) {
      return false;
    }
  }

  SmokeCluster startCluster(Path repoRoot, String javaExecutable, String classpath)
      throws IOException {
    List<Process> storeProcesses = new ArrayList<>();
    List<String> storeClientEndpoints = new ArrayList<>();
    for (int i = 0; i < STORE_COUNT; i++) {
      storeClientEndpoints.add("127.0.0.1:" + (STORE_CLIENT_PORT_BASE + i));
    }
    for (int i = 0; i < STORE_COUNT; i++) {
      int raftPort = STORE_RAFT_PORT_BASE + i;
      int clientPort = STORE_CLIENT_PORT_BASE + i;
      Process store =
          spawnStore(
              javaExecutable,
              classpath,
              raftPort,
              clientPort,
              storePeersSpecExcluding(i),
              tempDir.resolve("store-" + i + ".log"));
      processes.add(store);
      storeProcesses.add(store);
    }
    for (int i = 0; i < STORE_COUNT; i++) {
      awaitPortOpen("127.0.0.1", STORE_CLIENT_PORT_BASE + i, Duration.ofSeconds(30));
    }
    String storeEndpointsSpec = String.join(",", storeClientEndpoints);

    // Before Fafnir/control-plane, not after: Muninn only needs the store (its own read-only
    // Authorizer check), so bringing it up this early means it's already reachable to receive
    // shipped data from every process started after it, matching BootstrapMojo's own ordering
    // (design doc Part B/O-14).
    String muninnEndpoint = "127.0.0.1:" + MUNINN_PORT;
    processes.add(
        spawnMuninn(
            javaExecutable,
            classpath,
            MUNINN_PORT,
            storeEndpointsSpec,
            tempDir.resolve("muninn.log")));
    awaitPortOpen("127.0.0.1", MUNINN_PORT, Duration.ofSeconds(30));

    // A single key file shared across every Fafnir replica -- the design doc §8 multi-replica
    // provisioning requirement -- unlike spawnControlPlane's own controlplane-secret-<port>.key
    // below, which is deliberately still per-replica-distinct (that key only ever guards plain,
    // legacy /config/* entries this suite doesn't exercise, so its own multi-replica-consistency
    // gap is a separate, already-flagged issue outside this item's scope).
    Path fafnirSecretKeyPath = tempDir.resolve("fafnir-secret.key");
    List<String> fafnirEndpoints = new ArrayList<>();
    for (int i = 0; i < FAFNIR_COUNT; i++) {
      int port = FAFNIR_PORT_BASE + i;
      processes.add(
          spawnFafnir(
              javaExecutable,
              classpath,
              port,
              storeEndpointsSpec,
              fafnirSecretKeyPath,
              muninnEndpoint,
              tempDir.resolve("fafnir-" + i + ".log")));
      fafnirEndpoints.add("127.0.0.1:" + port);
    }
    for (String fafnirEndpoint : fafnirEndpoints) {
      String[] hostPort = fafnirEndpoint.split(":");
      awaitPortOpen(hostPort[0], Integer.parseInt(hostPort[1]), Duration.ofSeconds(30));
    }

    List<String> controlPlaneBaseUrls = new ArrayList<>();
    for (int i = 0; i < CONTROLPLANE_COUNT; i++) {
      int port = CONTROLPLANE_PORT_BASE + i;
      String baseUrl = "http://127.0.0.1:" + port;
      // Round-robin across the Fafnir replicas -- FafnirClient talks to exactly one address, so
      // this is what proves every replica is independently usable (not just replica #0), matching
      // the store cluster's own "read through a different replica than you wrote through" proof
      // pattern above.
      String fafnirEndpoint = fafnirEndpoints.get(i % fafnirEndpoints.size());
      processes.add(
          spawnControlPlane(
              javaExecutable,
              classpath,
              port,
              storeEndpointsSpec,
              fafnirEndpoint,
              muninnEndpoint,
              tempDir.resolve("controlplane-" + i + ".log")));
      final String awaitUrl = baseUrl;
      final int replicaIndex = i;
      await(
          () -> httpRespondsQuietly(awaitUrl + "/deployments"),
          Duration.ofSeconds(30),
          "control-plane replica #" + replicaIndex + " should start accepting requests");
      controlPlaneBaseUrls.add(baseUrl);
    }

    Process agentProcess =
        spawnAgent(
            javaExecutable,
            classpath,
            GOSSIP_ADDRESS,
            controlPlaneBaseUrls.get(0),
            fafnirEndpoints.get(0),
            muninnEndpoint,
            tempDir.resolve("agent.log"));
    processes.add(agentProcess);

    return new SmokeCluster(storeProcesses, controlPlaneBaseUrls, agentProcess, muninnEndpoint);
  }

  static String storePeersSpecExcluding(int excludeIndex) {
    List<String> peers = new ArrayList<>();
    for (int i = 0; i < STORE_COUNT; i++) {
      if (i == excludeIndex) {
        continue;
      }
      peers.add("127.0.0.1:" + (STORE_RAFT_PORT_BASE + i) + ":" + (STORE_CLIENT_PORT_BASE + i));
    }
    return String.join(",", peers);
  }

  void runPlaywrightSuite(Path repoRoot, String baseUrl) throws IOException, InterruptedException {
    Path consoleDir = repoRoot.resolve("gimle-console");
    Path logFile = tempDir.resolve("playwright.log");
    ProcessBuilder pb =
        new ProcessBuilder(bunExecutable(), "run", "test:e2e").directory(consoleDir.toFile());
    pb.environment().put("CONSOLE_BASE_URL", baseUrl);
    // Not inheritIO(): Surefire/Failsafe's forked-JVM protocol talks to the parent Maven process
    // over this same JVM's own stdout, and a child process writing to it directly (inheritIO
    // shares the file descriptor, not just mirrors it) corrupts that channel. Redirect to a file
    // like every other subprocess this test spawns, and surface it on failure instead.
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    Process playwright = pb.start();
    int exitCode = playwright.waitFor();
    if (exitCode != 0) {
      fail(
          "gimle-console's Playwright suite failed with exit code "
              + exitCode
              + "; see "
              + logFile);
    }
  }

  static String bunExecutable() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
        ? "bun.exe"
        : "bun";
  }

  Process spawnStore(
      String javaExecutable,
      String classpath,
      int raftPort,
      int clientPort,
      String peersSpec,
      Path logFile)
      throws IOException {
    List<String> command =
        new ArrayList<>(
            List.of(
                javaExecutable,
                // MUNINN_PORT is a known constant even though Muninn itself starts after every
                // store node does (see #startCluster's own comment) -- matches BootstrapMojo's own
                // spawnStore wiring (design doc Part B/O-10).
                "-Dgimle.store.muninnEndpoint=127.0.0.1:" + MUNINN_PORT,
                "-cp",
                classpath,
                "com.gimle.mimir.StoreMain",
                tempDir.resolve("store-state-" + raftPort).toString(),
                String.valueOf(raftPort),
                String.valueOf(clientPort)));
    if (!peersSpec.isBlank()) {
      command.add("--peers");
      command.add(peersSpec);
    }
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  Process spawnMuninn(
      String javaExecutable, String classpath, int port, String storeEndpointsSpec, Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.muninn.MuninnMain",
            String.valueOf(port),
            "--store-endpoints",
            storeEndpointsSpec,
            "--data-root",
            tempDir.resolve("muninn-data").toString());
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  Process spawnFafnir(
      String javaExecutable,
      String classpath,
      int port,
      String storeEndpointsSpec,
      Path secretKeyPath,
      String muninnEndpoint,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-Dgimle.fafnir.muninnEndpoint=" + muninnEndpoint,
            "-cp",
            classpath,
            "com.gimle.fafnir.FafnirMain",
            String.valueOf(port),
            secretKeyPath.toString(),
            "--store-endpoints",
            storeEndpointsSpec);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  Process spawnControlPlane(
      String javaExecutable,
      String classpath,
      int port,
      String storeEndpointsSpec,
      String fafnirEndpoint,
      String muninnEndpoint,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.controlplane.ControlPlaneMain",
            String.valueOf(port),
            tempDir.resolve("controlplane-secret-" + port + ".key").toString(),
            "--store-endpoints",
            storeEndpointsSpec,
            "--fafnir-endpoint",
            fafnirEndpoint,
            "--muninn-endpoint",
            muninnEndpoint);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  Process spawnAgent(
      String javaExecutable,
      String classpath,
      String gossipAddress,
      String controlPlaneBaseUrl,
      String fafnirEndpoint,
      String muninnEndpoint,
      Path logFile)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(
            javaExecutable,
            "-Dgimle.agent.fafnirEndpoint=" + fafnirEndpoint,
            "-Dgimle.agent.muninnEndpoint=" + muninnEndpoint,
            // Every other process this fixture spawns (store, fafnir, muninn, control plane) is
            // already given a tempDir-scoped path for its own state/logs -- the agent (and, through
            // it, every worker it supervises: buildWorkerCommand's own -Dgimle.log.root scopes a
            // worker under the agent's own root) was the one exception, silently defaulting to
            // "gimle-logs" relative to whatever the forked test JVM's own CWD happens to be (this
            // module's own root directory, not per-test). That left real log files physically
            // accumulating on disk across every separate `mvn verify` invocation in the same
            // checkout, keyed only by deployment name/instance index with no per-run boundary --
            // found via a genuinely corrupted assertion in ServiceFabricIT reading 24 stale lines
            // from an earlier run mixed into what should have been a fresh log.
            "-Dgimle.log.root=" + tempDir.resolve("gimle-logs"),
            "-cp",
            classpath,
            "com.gimle.agent.AgentMain",
            "smoke-node-1",
            controlPlaneBaseUrl,
            gossipAddress,
            "-",
            javaExecutable,
            "-cp",
            classpath,
            "com.gimle.worker.WorkerMain");
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  void submitDeployment(String baseUrl, String deploymentName, String moduleName, Path jar)
      throws Exception {
    submitDeployment(baseUrl, deploymentName, moduleName, jar, Optional.empty());
  }

  void submitDeployment(
      String baseUrl, String deploymentName, String moduleName, Path jar, Optional<String> tenantId)
      throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        %s
        """
            .formatted(
                deploymentName,
                moduleName,
                jar.toAbsolutePath(),
                tenantId.map(id -> "tenantId: " + id).orElse(""));
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("deployment submission failed: " + response.statusCode() + " " + response.body());
    }
  }

  /**
   * Like {@link #submitDeployment(String, String, String, Path, Optional)}, but for a submission
   * expected to be rejected at admission -- returns the raw response instead of failing on a
   * non-200, so the caller can assert on the rejection itself ({@code ApiServer#checkTenantQuota}'s
   * own 409, in this suite's case).
   */
  HttpResponse<String> submitDeploymentExpectingRejection(
      String baseUrl, String deploymentName, String moduleName, Path jar, String tenantId)
      throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: 1
        tenantId: %s
        """
            .formatted(deploymentName, moduleName, jar.toAbsolutePath(), tenantId);
    return httpClient.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
            .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * Registers {@link #SECRET_TENANT_ID} with a generous quota -- large enough that {@code
   * greeter-provider}'s own {@code 64Mi}/{@code 100m} limit (see its {@code gimle-module.yaml})
   * never brushes it -- and writes {@link #SECRET_KEY} through the real {@code /secrets/*} proxy
   * (design doc §6e) before any deployment references the tenant. A deployment's {@code tenantId}
   * must already name a registered {@code Tenant} at admission time (see {@code DeploymentSpec}'s
   * own javadoc), so this must run before {@link #submitDeployment}.
   */
  void provisionTenantAndSecret(String baseUrl) throws Exception {
    String quotaBody =
        Json.write(
            Map.of(
                "quota",
                Map.of(
                    "maxMemoryBytes",
                    256L * 1024 * 1024,
                    "maxCpuMillicores",
                    1000L,
                    "maxInstances",
                    10)));
    HttpResponse<String> tenantResponse =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/tenants/" + SECRET_TENANT_ID))
                .PUT(HttpRequest.BodyPublishers.ofString(quotaBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (tenantResponse.statusCode() != 200) {
      fail("tenant creation failed: " + tenantResponse.statusCode() + " " + tenantResponse.body());
    }

    String secretBody =
        Json.write(
            Map.of(
                "value",
                Base64.getEncoder().encodeToString(SECRET_VALUE.getBytes(StandardCharsets.UTF_8))));
    HttpResponse<String> secretResponse =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/secrets/" + SECRET_TENANT_ID + "/" + SECRET_KEY))
                .PUT(HttpRequest.BodyPublishers.ofString(secretBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (secretResponse.statusCode() != 200) {
      fail("secret write failed: " + secretResponse.statusCode() + " " + secretResponse.body());
    }

    String configBody = Json.write(Map.of("value", PLAIN_CONFIG_VALUE, "encrypted", false));
    HttpResponse<String> configResponse =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/config/" + SECRET_TENANT_ID + "/" + PLAIN_CONFIG_KEY))
                .PUT(HttpRequest.BodyPublishers.ofString(configBody, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (configResponse.statusCode() != 200) {
      fail("config write failed: " + configResponse.statusCode() + " " + configResponse.body());
    }
  }

  void submitDeploymentWithRetry(
      String baseUrl, String deploymentName, String moduleName, Path jar, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        submitDeployment(baseUrl, deploymentName, moduleName, jar);
        return;
      } catch (AssertionError e) {
        if (System.nanoTime() > deadline) {
          throw e;
        }
        Thread.sleep(500);
      }
    }
  }

  void createLoginAccount(String baseUrl, String username, String password) throws Exception {
    String body = Json.write(Map.of("password", password));
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/accounts/" + username))
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("account creation failed: " + response.statusCode() + " " + response.body());
    }
  }

  boolean isActive(String baseUrl, String deploymentName) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
      if (instances.isEmpty()) {
        return false;
      }
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (!(observation instanceof Map<?, ?> obsMap)
            || !"ACTIVE".equals(obsMap.get("lifecycleState"))) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Unlike {@link #isActive}, which requires every instance to agree, this only needs one: {@code
   * ModuleController#forceFailed}'s {@code lifecycleState == FAILED} is a permanent, terminal
   * escalation (no path back to {@code ACTIVE} without a fresh deploy), so a single instance
   * reaching it is already the whole answer.
   */
  boolean hasFailedInstance(String baseUrl, String deploymentName) {
    try {
      Map<String, Object> status = deploymentStatus(baseUrl, deploymentName);
      List<Map<String, Object>> instances = Json.asObjectList(status.get("instances"));
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (observation instanceof Map<?, ?> obsMap
            && "FAILED".equals(obsMap.get("lifecycleState"))) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  Map<String, Object> deploymentStatus(String baseUrl, String deploymentName) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + deploymentName))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      return Map.of("instances", List.of());
    }
    return Json.asObject(Json.parse(response.body()));
  }

  boolean consumerLogShowsAGreeting(String baseUrl) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          baseUrl
                              + "/logs/instances/greeter-consumer-deployment/0"
                              + "?category=APPLICATION"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 && response.body().contains("Hello, Gimlé!");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * The real secret round trip's own assertion point: {@link #SECRET_VALUE} written through the API
   * in {@link #provisionTenantAndSecret}, fetched by the real node agent straight from a real
   * Fafnir replica (design doc §9/§11 Phase C), delivered down to the worker, and read back out by
   * {@code GreeterProviderHooks#onStart}'s own {@code ctx.config(...)} call -- observed here purely
   * through this instance's own application log, the same way {@link #consumerLogShowsAGreeting}
   * observes the cross-worker fabric call.
   */
  boolean providerLogShowsTheSecret(String baseUrl) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          baseUrl
                              + "/logs/instances/greeter-provider-deployment/0"
                              + "?category=APPLICATION"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 && response.body().contains(SECRET_VALUE);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * The generic shape {@link #consumerLogShowsAGreeting}/{@link #providerLogShowsTheSecret} are
   * each a hardcoded special case of -- pulled out separately here rather than rewriting those two
   * in terms of it, since neither is in this refactor's own path and both read fine as they are.
   */
  boolean instanceLogContains(
      String baseUrl, String deploymentName, int instanceIndex, String category, String text) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          baseUrl
                              + "/logs/instances/"
                              + deploymentName
                              + "/"
                              + instanceIndex
                              + "?category="
                              + category))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 && response.body().contains(text);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Like {@link #instanceLogContains}, but returns the raw body for a caller that needs more than a
   * single substring check -- e.g. counting or ordering matches across the whole log, the way a
   * circuit-breaker-exclusion assertion needs to (see {@code ServiceFabricIT}) -- rather than
   * adding another narrow, single-purpose helper per caller.
   */
  String fetchInstanceLog(
      String baseUrl, String deploymentName, int instanceIndex, String category) {
    try {
      HttpResponse<String> response =
          httpClient.send(
              HttpRequest.newBuilder(
                      URI.create(
                          baseUrl
                              + "/logs/instances/"
                              + deploymentName
                              + "/"
                              + instanceIndex
                              + "?category="
                              + category))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return response.statusCode() == 200 ? response.body() : "";
    } catch (Exception e) {
      return "";
    }
  }

  static boolean httpRespondsQuietly(String url) {
    try {
      HttpResponse<Void> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).GET().build(),
                  HttpResponse.BodyHandlers.discarding());
      return response.statusCode() < 500;
    } catch (Exception e) {
      return false;
    }
  }

  static void awaitPortOpen(String host, int port, Duration timeout) {
    await(
        () -> isPortOpen(host, port),
        timeout,
        "port " + host + ":" + port + " should start listening");
  }

  static boolean isPortOpen(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  static void await(BooleanSupplier condition, Duration timeout, String description) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout + ": " + description);
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for: " + description, e);
      }
    }
  }

  static Path repoRoot() {
    return Path.of("").toAbsolutePath().getParent();
  }

  static String javaExecutable() {
    java.util.Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent()) {
      return command.get();
    }
    Path javaBin = Path.of(System.getProperty("java.home"), "bin");
    for (String candidate : List.of("java", "java.exe")) {
      Path path = javaBin.resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toString();
      }
    }
    throw new IllegalStateException("could not locate the java launcher under " + javaBin);
  }
}
