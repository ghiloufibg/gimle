package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.reconcile.DeploymentReconciler;
import com.gimle.controlplane.reconcile.HealthReconciler;
import com.gimle.controlplane.reconcile.ReplicaCountReconciler;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.protocol.Json;
import com.gimle.module.testsupport.TestModuleBuilder;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real network-fault scenarios for a cross-machine fabric call, modeled directly on {@link
 * FabricCrossProcessIntegrationTest}'s own two-real-node, real-fabric-hop shape. That sibling test
 * only ever kills the provider's whole node outright; nothing proves a caller observes a clean
 * failure -- not a hang -- when the *connection* alone is cut or slowed, without any process dying,
 * which is exactly what a real network partition on the wire looks like.
 *
 * <p>Unlike {@link ControlPlaneAgentNetworkFaultTest}'s control-plane-API scenario, the fabric
 * address a caller connects to is resolved internally (gossiped from the exporting node's own Hello
 * message), not test-constructed -- so routing it through a proxy needs a real, narrow production
 * seam: {@code WorkerMain}'s {@code gimle.fabric.advertisedPort} system property lets a worker
 * advertise a proxy's port instead of its own real bound one, while still binding and listening on
 * its own real ephemeral port unconditionally. Overriding what's advertised means nothing else in
 * the system (not even that worker's own agent) ever learns the real port, so a second, paired seam
 * ({@code gimle.fabric.realPortFile}) has the worker report it back to this test directly, once,
 * right after binding -- the only way to complete the reserve-a-proxy-port,
 * then-point-it-at-the-real-one sequence {@code Proxy.setUpstream}-style flows need.
 */
class FabricCrossProcessNetworkFaultTest {

  private static final String TOXIPROXY_HOST = "127.0.0.1";
  private static final int TOXIPROXY_PORT = 8474;

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private final List<Process> agentProcesses = new ArrayList<>();
  private final List<Proxy> proxies = new ArrayList<>();
  private ApiServer apiServer;
  private Thread reconcileLoop;
  private volatile boolean stopReconcileLoop;

  @AfterEach
  void tearDown() {
    stopReconcileLoop = true;
    if (reconcileLoop != null) {
      reconcileLoop.interrupt();
    }
    for (Process process : agentProcesses) {
      process.descendants().forEach(ProcessHandle::destroy);
      process.destroyForcibly();
    }
    if (apiServer != null) {
      apiServer.close();
    }
    for (Proxy proxy : proxies) {
      try {
        proxy.delete();
      } catch (IOException ignored) {
        // best-effort cleanup; a leftover proxy on a dead test JVM's ephemeral ports is harmless
      }
    }
  }

  private static boolean toxiproxyServerReachable() {
    try (Socket probe = new Socket()) {
      probe.connect(new InetSocketAddress(TOXIPROXY_HOST, TOXIPROXY_PORT), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  @Timeout(value = 4, unit = TimeUnit.MINUTES)
  void a_network_cut_between_the_two_fabric_nodes_is_a_clean_failure_not_a_hang() throws Exception {
    Assumptions.assumeTrue(
        toxiproxyServerReachable(),
        "toxiproxy-server not reachable on "
            + TOXIPROXY_HOST
            + ":"
            + TOXIPROXY_PORT
            + " -- skipping");

    Provisioned p = provisionProviderAndConsumer(17990, 17991, proxy -> {});

    await(
        () -> outputContainsQuietly(p.outputFile(), "OK:hello world from provider"),
        Duration.ofSeconds(90),
        "consumer should successfully call the provider over the (currently transparent) proxy");

    // Full cut of the wire between the two nodes: neither process dies, but every call the
    // consumer makes from here on must fail cleanly -- exactly the case a hard-kill test
    // structurally cannot cover, and only actually clean because of Unit (d)'s FabricClient
    // connect/read timeouts (without them this would hang instead of producing a FAIL: line).
    p.proxy().disable();
    await(
        () -> outputHasALineAfterQuietly(p.outputFile(), "OK:", "FAIL:"),
        Duration.ofSeconds(45),
        "consumer should observe a clean failure once the connection is cut; output so far: "
            + readQuietly(p.outputFile()));

    // Heal it. The consumer's own call loop never crashed on the repeated failures (it already
    // catches and records each one); proving it resumes succeeding is the specific half a killed
    // process could never demonstrate within the same test.
    p.proxy().enable();
    await(
        () -> outputHasALineAfterQuietly(p.outputFile(), "FAIL:", "OK:"),
        Duration.ofSeconds(45),
        "consumer should resume succeeding once the connection heals; output so far: "
            + readQuietly(p.outputFile()));
  }

  /**
   * Scoped like {@code ControlPlaneAgentNetworkFaultTest}'s own latency scenario: proves the real
   * cross-machine call path tolerates realistic added latency without hanging, rather than
   * re-asserting the full deploy-to-ACTIVE cycle (already covered, latency-free, by {@code
   * FabricCrossProcessIntegrationTest}).
   */
  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void the_consumer_keeps_working_correctly_under_realistic_added_latency() throws Exception {
    Assumptions.assumeTrue(
        toxiproxyServerReachable(),
        "toxiproxy-server not reachable on "
            + TOXIPROXY_HOST
            + ":"
            + TOXIPROXY_PORT
            + " -- skipping");

    // A real 300ms delay each way -- comfortably below FabricClient's 10s connect / 30s read
    // timeouts and the consumer hook's own 60s lookup / 45s call-loop windows, but well above real
    // localhost speed, so this actually exercises added latency rather than re-proving the proxy
    // is transparent at zero cost.
    Provisioned p =
        provisionProviderAndConsumer(
            17992,
            17993,
            proxy -> {
              try {
                proxy.toxics().latency("fabric-latency-down", ToxicDirection.DOWNSTREAM, 300);
                proxy.toxics().latency("fabric-latency-up", ToxicDirection.UPSTREAM, 300);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });

    await(
        () -> outputContainsQuietly(p.outputFile(), "OK:hello world from provider"),
        Duration.ofSeconds(90),
        "consumer should successfully call the provider despite the added latency on the wire");
  }

  private record Provisioned(Proxy proxy, Path outputFile) {}

  private Provisioned provisionProviderAndConsumer(
      int nodeAGossipPort, int nodeBGossipPort, Consumer<Proxy> proxyCustomizer) throws Exception {
    Path outputFile = tempDir.resolve("consumer-output-" + nodeAGossipPort + ".txt");
    Path realPortFile = tempDir.resolve("real-fabric-port-" + nodeAGossipPort + ".txt");

    Path providerJar =
        buildFixture(
            tempDir,
            "com.gimle.fixture.fabricfaultprovider" + nodeAGossipPort,
            "com.gimle.agent.FabricGreeterProviderHooks",
            true);
    Path consumerJar =
        buildFixture(
            tempDir,
            "com.gimle.fixture.fabricfaultconsumer" + nodeAGossipPort,
            "com.gimle.agent.FabricGreeterConsumerHooks",
            false);

    StateStore store = new StateStore(tempDir.resolve("cp-state-" + nodeAGossipPort));
    Scheduler scheduler = new Scheduler();
    DeploymentReconciler deploymentReconciler = new DeploymentReconciler(store, scheduler);
    ReplicaCountReconciler replicaCountReconciler =
        new ReplicaCountReconciler(store, Duration.ofSeconds(17), Duration.ofSeconds(20));
    HealthReconciler healthReconciler = new HealthReconciler(store);
    apiServer = new ApiServer(store, 0);
    apiServer.start();
    String baseUrl = "http://localhost:" + apiServer.port();

    reconcileLoop =
        Thread.ofVirtual()
            .name("test-reconcile-loop-" + nodeAGossipPort)
            .start(
                () -> {
                  while (!stopReconcileLoop) {
                    replicaCountReconciler.reconcileOnce();
                    healthReconciler.reconcileOnce();
                    deploymentReconciler.reconcileOnce();
                    try {
                      Thread.sleep(300);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });

    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");
    HttpClient httpClient = HttpClient.newHttpClient();
    ToxiproxyClient toxiproxyClient = new ToxiproxyClient(TOXIPROXY_HOST, TOXIPROXY_PORT);

    int proxyPort = reservePort();
    // The proxy must listen on the same host WorkerMain's own (untouched) resolveAdvertisedHost()
    // will report -- not necessarily 127.0.0.1. A first version hardcoded 127.0.0.1 here and every
    // consumer call failed with a connection-refused-flavored UncheckedIOException: this machine's
    // InetAddress.getLocalHost() resolves to a real LAN address, so the worker advertised
    // <lan-ip>:<proxyPort> while the proxy only ever listened on 127.0.0.1:<proxyPort>.
    String advertisedHost = InetAddress.getLocalHost().getHostAddress();

    // node-a hosts the provider, alone and first, and advertises the not-yet-created proxy's port
    // instead of its own real one. It still binds and listens on its own real ephemeral port
    // unconditionally (WorkerMain's own seam guarantees this); it just also reports that real port
    // back to this test via realPortFile, since overriding what's advertised means nothing else in
    // the system ever learns it. Both -D flags are baked into node-a's agent commandTail now, at
    // spawn time, but only take effect once its worker JVM actually starts -- which AgentMain does
    // lazily, only once a real assignment lands (confirmed directly: AgentMain#startInstance is the
    // only place a worker process is ever created). realPortFile can't be polled until *after* the
    // provider deployment below is submitted and scheduled here; polling for it any earlier, before
    // any assignment exists, waits on a file that will never be written.
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-a",
            baseUrl,
            "127.0.0.1:" + nodeAGossipPort,
            "-",
            List.of(
                "-Dgimle.fabric.advertisedPort=" + proxyPort,
                "-Dgimle.fabric.realPortFile=" + realPortFile.toAbsolutePath()),
            tempDir.resolve("node-a-" + nodeAGossipPort + ".log")));

    submitDeployment(
        httpClient, baseUrl, "fabric-fault-provider-" + nodeAGossipPort, providerJar, 1);

    int realPort =
        awaitRealPort(
            realPortFile,
            Duration.ofSeconds(60),
            "node-a's worker should report its real fabric port once the provider is assigned");

    Proxy proxy =
        toxiproxyClient.createProxy(
            "gimle-fabric-" + UUID.randomUUID(),
            advertisedHost + ":" + proxyPort,
            "127.0.0.1:" + realPort);
    proxies.add(proxy);
    proxyCustomizer.accept(proxy);

    await(
        () ->
            activeInstanceCountQuietly(
                    httpClient, baseUrl, "fabric-fault-provider-" + nodeAGossipPort)
                >= 1,
        Duration.ofSeconds(60),
        "provider should reach ACTIVE");

    // node-b hosts the consumer; by the time it's registered, node-a's free capacity is already
    // (slightly) reduced by the provider instance, so the scheduler deterministically prefers
    // node-b, untouched, for this second deployment -- same reasoning
    // FabricCrossProcessIntegrationTest
    // already relies on.
    agentProcesses.add(
        spawnAgent(
            javaExecutable,
            classpath,
            "node-b",
            baseUrl,
            "127.0.0.1:" + nodeBGossipPort,
            "127.0.0.1:" + nodeAGossipPort,
            List.of("-Dgimle.test.outputFile=" + outputFile.toAbsolutePath()),
            tempDir.resolve("node-b-" + nodeAGossipPort + ".log")));
    await(
        () -> store.getNodeHeartbeat("node-b").isPresent(),
        Duration.ofSeconds(30),
        "node-b should have registered and heartbeated");
    submitDeployment(
        httpClient, baseUrl, "fabric-fault-consumer-" + nodeAGossipPort, consumerJar, 1);

    return new Provisioned(proxy, outputFile);
  }

  private static int awaitRealPort(Path realPortFile, Duration timeout, String description)
      throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      if (Files.exists(realPortFile)) {
        String content = Files.readString(realPortFile).trim();
        if (!content.isEmpty()) {
          return Integer.parseInt(content);
        }
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout + ": " + description);
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for: " + description, e);
      }
    }
  }

  private static int reservePort() throws IOException {
    try (ServerSocketChannel probe = ServerSocketChannel.open()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      return ((InetSocketAddress) probe.getLocalAddress()).getPort();
    }
  }

  private static Path buildFixture(
      Path tempDir, String moduleName, String hooksClassName, boolean exportsGreeter) {
    String exportsSection =
        exportsGreeter
            ? """
              exports:
                - service: com.gimle.agent.FabricGreeter
                  version: 1.0.0
              """
            : "";
    String descriptor =
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
        %s"""
            .formatted(moduleName, hooksClassName, exportsSection);
    return TestModuleBuilder.module(
            """
            module %s {
            }
            """
                .formatted(moduleName))
        .withDescriptor(descriptor)
        .build(tempDir, moduleName + ".jar");
  }

  private static Process spawnAgent(
      String javaExecutable,
      String classpath,
      String nodeId,
      String baseUrl,
      String gossipBindHostPort,
      String seeds,
      List<String> extraWorkerJvmArgs,
      Path logFile)
      throws IOException {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    command.add("-cp");
    command.add(classpath);
    command.add("com.gimle.agent.AgentMain");
    command.add(nodeId);
    command.add(baseUrl);
    command.add(gossipBindHostPort);
    command.add(seeds);
    command.add(javaExecutable);
    command.addAll(extraWorkerJvmArgs);
    command.add("-cp");
    command.add(classpath);
    command.add("com.gimle.worker.WorkerMain");

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
    return pb.start();
  }

  private static void submitDeployment(
      HttpClient httpClient, String baseUrl, String name, Path jar, int replicas) throws Exception {
    String manifest =
        """
        name: %s
        module:
          name: %s
          version: 1.0.0
        artifactPath: %s
        replicas: %d
        """
            .formatted(
                name,
                jar.getFileName().toString().replace(".jar", ""),
                jar.toAbsolutePath(),
                replicas);
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name))
                .PUT(HttpRequest.BodyPublishers.ofString(manifest, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      fail("deployment submission failed: " + response.statusCode() + " " + response.body());
    }
  }

  private static Map<String, Object> deploymentStatus(
      HttpClient httpClient, String baseUrl, String name) throws Exception {
    HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/deployments/" + name)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      return Map.of("instances", List.of());
    }
    return Json.asObject(Json.parse(response.body()));
  }

  private static long activeInstanceCountQuietly(
      HttpClient httpClient, String baseUrl, String name) {
    try {
      List<Map<String, Object>> instances =
          Json.asObjectList(deploymentStatus(httpClient, baseUrl, name).get("instances"));
      long count = 0;
      for (Map<String, Object> instance : instances) {
        Object observation = instance.get("observation");
        if (observation instanceof Map<?, ?> obsMap
            && "ACTIVE".equals(obsMap.get("lifecycleState"))) {
          count++;
        }
      }
      return count;
    } catch (Exception e) {
      return 0;
    }
  }

  private static boolean outputContainsQuietly(Path file, String needle) {
    try {
      return Files.exists(file) && Files.readString(file).contains(needle);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * True once some line starting with {@code afterPrefix} appears strictly after the first line
   * starting with {@code beforePrefix} -- e.g. a {@code FAIL:} following an already-observed {@code
   * OK:}, or vice versa on recovery. Checking presence alone would trivially pass on stale
   * pre-fault output; this is {@link FabricCrossProcessIntegrationTest}'s own {@code
   * outputHasAFailureAfterASuccessQuietly} generalized to run in both directions.
   */
  private static boolean outputHasALineAfterQuietly(
      Path file, String beforePrefix, String afterPrefix) {
    try {
      if (!Files.exists(file)) {
        return false;
      }
      List<String> lines = Files.readAllLines(file);
      int firstBefore = -1;
      for (int i = 0; i < lines.size(); i++) {
        if (firstBefore < 0 && lines.get(i).startsWith(beforePrefix)) {
          firstBefore = i;
        } else if (firstBefore >= 0 && lines.get(i).startsWith(afterPrefix)) {
          return true;
        }
      }
      return false;
    } catch (IOException e) {
      return false;
    }
  }

  private static String readQuietly(Path file) {
    try {
      return Files.exists(file) ? Files.readString(file) : "(no output file yet)";
    } catch (IOException e) {
      return "(failed to read: " + e.getMessage() + ")";
    }
  }

  private static void await(BooleanSupplier condition, Duration timeout, String description) {
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

  private static String javaExecutable() {
    Optional<String> command = ProcessHandle.current().info().command();
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
