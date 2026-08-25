package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.andvari.AndvariServer;
import com.gimle.controlplane.andvari.AndvariClient;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.FafnirServer;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.artifact.ArtifactPullCache;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code gimle apply -f <artifactset.yaml>} end to end -- a real {@link AndvariServer} behind
 * {@link ApiServer}'s own {@code /artifacts/*} proxy, exercised through {@link GimleCli} exactly
 * the way an operator would run it: real jars (built in-process via {@link TestModuleBuilder}),
 * real HTTP, real tenant tagging.
 */
class ArtifactSetCommandTest {

  @TempDir Path tempDir;

  private RaftNode storeRaftNode;
  private StoreTransport storeTransport;
  private StoreClient storeClient;
  private FafnirServer fafnirServer;
  private FafnirClient fafnirClient;
  private AndvariServer andvariServer;
  private AndvariClient andvariClient;
  private ApiServer server;
  private String serverAddress;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;

  @BeforeEach
  void startServer() throws IOException {
    StateStore store = new StateStore(tempDir.resolve("store"));
    RaftLog raftLog = new RaftLog(tempDir.resolve("raft"));
    storeRaftNode = new RaftNode("self", Map.of(), raftLog, store);
    storeRaftNode.start();
    StoreNode storeNode = new StoreNode(storeRaftNode, store, Map.of());
    storeTransport = new StoreTransport(storeNode);
    SocketAddress storeAddress = storeTransport.listen(new InetSocketAddress("127.0.0.1", 0));
    storeClient = new StoreClient(List.of(storeAddress));

    FafnirCrypto fafnirCrypto = new FafnirCrypto(storeClient, tempDir.resolve("keys/secret.key"));
    fafnirServer = new FafnirServer(fafnirCrypto, 0);
    fafnirServer.start();
    fafnirClient = new FafnirClient("localhost:" + fafnirServer.port());

    andvariServer = new AndvariServer(storeClient, 0, tempDir.resolve("andvari-data"));
    andvariServer.start();
    andvariClient = new AndvariClient("localhost:" + andvariServer.port());
    ArtifactPullCache cache = new ArtifactPullCache(tempDir.resolve("andvari-cache"));

    server =
        new ApiServer(
            storeClient,
            0,
            tempDir.resolve("session.key"),
            fafnirClient,
            null,
            ArtifactResolver.withRegistry(andvariClient, cache));
    server.start();
    serverAddress = "localhost:" + server.port();
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
  }

  @AfterEach
  void stopServer() {
    server.close();
    andvariClient.close();
    andvariServer.close();
    fafnirClient.close();
    fafnirServer.close();
    storeClient.close();
    storeTransport.close();
  }

  private Path buildModule(String moduleId, String version, Path outputDir) {
    return buildModule(moduleId, version, outputDir, "v1");
  }

  /**
   * {@code variant} only changes the compiled class body -- never the declared {@code moduleId}/
   * {@code version} -- so two calls with the same coordinate but different variants produce two
   * genuinely different, independently valid jars under that one coordinate: exactly the "someone
   * changed the code but forgot to bump the version" shape a real digest conflict looks like.
   */
  private Path buildModule(String moduleId, String version, Path outputDir, String variant) {
    String pkg = moduleId.replace('.', '/');
    String className = "Greeter";
    return TestModuleBuilder.module("module " + moduleId + " {\n  exports " + moduleId + ";\n}\n")
        .withClass(
            moduleId + "." + className,
            "package "
                + moduleId
                + ";\npublic class "
                + className
                + " {\n  public String variant() { return \""
                + variant
                + "\"; }\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(moduleId, version))
        .build(outputDir, pkg.replace('/', '-') + "-" + version + "-" + variant + ".jar");
  }

  private int run(String... args) {
    return GimleCli.run(args, new PrintStream(outBuffer), new PrintStream(errBuffer));
  }

  @Test
  void applying_a_set_pushes_every_member_tagged_with_its_tenant() throws Exception {
    Path jarsDir = tempDir.resolve("jars");
    Path ordersJar = buildModule("com.example.orders", "1.0.0", jarsDir);
    Path inventoryJar = buildModule("com.example.inventory", "1.0.0", jarsDir);
    Path sharedJar = buildModule("com.example.shared", "1.0.0", jarsDir);

    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest,
        """
        kind: ArtifactSet
        tenant:
          orders-platform:
            - %s
            - %s
        modules:
          - %s
        """
            .formatted(ordersJar, inventoryJar, sharedJar));

    int exitCode = run("apply", "-f", manifest.toString(), "--server", serverAddress);

    assertEquals(0, exitCode, errBuffer.toString(StandardCharsets.UTF_8));
    String out = outBuffer.toString(StandardCharsets.UTF_8);
    assertTrue(out.contains("com.example.orders"));
    assertTrue(out.contains("com.example.inventory"));
    assertTrue(out.contains("com.example.shared"));

    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    assertEquals(
        "orders-platform",
        client.head("/artifacts/com.example.orders/1.0.0").tenantId().orElseThrow());
    assertEquals(
        "orders-platform",
        client.head("/artifacts/com.example.inventory/1.0.0").tenantId().orElseThrow());
    assertTrue(client.head("/artifacts/com.example.shared/1.0.0").tenantId().isEmpty());
  }

  @Test
  void a_preflight_conflict_aborts_the_whole_set_before_any_push() throws Exception {
    Path jarsDir = tempDir.resolve("jars");
    Path ordersJarV1 = buildModule("com.example.orders", "1.0.0", jarsDir, "already-stored");

    // Seed a conflicting coordinate directly, bypassing the set entirely.
    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    client.expectSuccess(client.putFile("/artifacts/com.example.orders/1.0.0", ordersJarV1));

    // Same coordinate, genuinely different bytes -- the manifest below tries to claim it too.
    Path conflictingOrdersJar =
        buildModule("com.example.orders", "1.0.0", jarsDir, "locally-changed");
    Path untouchedJar = buildModule("com.example.untouched", "1.0.0", jarsDir);

    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest,
        """
        kind: ArtifactSet
        modules:
          - %s
          - %s
        """
            .formatted(conflictingOrdersJar, untouchedJar));

    int exitCode = run("apply", "-f", manifest.toString(), "--server", serverAddress);

    assertEquals(1, exitCode);
    assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("pre-flight"));
    // The set never reached the second, non-conflicting member -- nothing was pushed at all.
    assertEquals(404, client.head("/artifacts/com.example.untouched/1.0.0").statusCode());
  }

  @Test
  void re_applying_an_already_pushed_set_reports_every_member_identical() throws Exception {
    Path jarsDir = tempDir.resolve("jars");
    Path jar = buildModule("com.example.orders", "1.0.0", jarsDir);
    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(manifest, "kind: ArtifactSet\nmodules:\n  - " + jar + "\n");

    assertEquals(0, run("apply", "-f", manifest.toString(), "--server", serverAddress));
    outBuffer.reset();

    int secondExitCode = run("apply", "-f", manifest.toString(), "--server", serverAddress);

    assertEquals(0, secondExitCode);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("already-present"));
  }

  // ---- admission-time tenant cross-check ----

  private static String registryDeploymentYaml(
      String name, String moduleName, String version, String tenantId) {
    return """
        kind: Deployment
        name: %s
        module:
          name: %s
          version: %s
        replicas: 1
        tenantId: %s
        """
        .formatted(name, moduleName, version, tenantId);
  }

  @Test
  void deploying_from_a_registry_coordinate_under_a_disagreeing_tenant_is_rejected()
      throws Exception {
    Path jarsDir = tempDir.resolve("jars");
    Path jar = buildModule("com.example.billing", "1.0.0", jarsDir);
    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest, "kind: ArtifactSet\ntenant:\n  orders-platform:\n    - " + jar + "\n");
    assertEquals(0, run("apply", "-f", manifest.toString(), "--server", serverAddress));

    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    ApiResponse response =
        client.put(
            "/deployments/billing-deployment",
            registryDeploymentYaml(
                "billing-deployment", "com.example.billing", "1.0.0", "some-other-tenant"));

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("orders-platform"));
    assertTrue(response.body().contains("some-other-tenant"));
  }

  @Test
  void deploying_from_a_registry_coordinate_under_the_matching_tenant_is_admitted()
      throws Exception {
    Path jarsDir = tempDir.resolve("jars");
    Path jar = buildModule("com.example.billing", "1.0.0", jarsDir);
    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest, "kind: ArtifactSet\ntenant:\n  orders-platform:\n    - " + jar + "\n");
    assertEquals(0, run("apply", "-f", manifest.toString(), "--server", serverAddress));

    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    client.expectSuccess(
        client.put(
            "/tenants/orders-platform",
            "{\"quota\":{\"maxMemoryBytes\":1000000000,\"maxCpuMillicores\":4000,"
                + "\"maxInstances\":10}}"));
    ApiResponse response =
        client.put(
            "/deployments/billing-deployment",
            registryDeploymentYaml(
                "billing-deployment", "com.example.billing", "1.0.0", "orders-platform"));

    assertEquals(200, response.statusCode());
  }

  @Test
  void deploying_an_untenanted_workload_from_a_tenanted_coordinate_skips_the_check()
      throws Exception {
    Path jarsDir = tempDir.resolve("jars");
    Path jar = buildModule("com.example.billing", "1.0.0", jarsDir);
    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest, "kind: ArtifactSet\ntenant:\n  orders-platform:\n    - " + jar + "\n");
    assertEquals(0, run("apply", "-f", manifest.toString(), "--server", serverAddress));

    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    ApiResponse response =
        client.put(
            "/deployments/billing-deployment",
            """
            kind: Deployment
            name: billing-deployment
            module:
              name: com.example.billing
              version: 1.0.0
            replicas: 1
            """);

    assertEquals(200, response.statusCode());
  }

  // ---- vessel and bundle entries ----

  @Test
  void deploying_a_bundle_coordinate_without_a_vessel_block_is_rejected() throws Exception {
    Path appDir = tempDir.resolve("quarkus-app-admission");
    Files.createDirectories(appDir);
    Files.writeString(appDir.resolve("quarkus-run.jar"), "pretend-run-jar");
    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest,
        """
        kind: ArtifactSet
        modules:
          - artifact: quarkus-app-admission
            kind: bundle
            name: com.example.report
            version: 5.0.0
            command: [java, -jar, quarkus-run.jar]
        """);
    assertEquals(0, run("apply", "-f", manifest.toString(), "--server", serverAddress));

    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    ApiResponse response =
        client.put(
            "/deployments/report-deployment",
            """
            kind: Deployment
            name: report-deployment
            module:
              name: com.example.report
              version: 5.0.0
            replicas: 1
            """);

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("vessel"));
  }

  @Test
  void a_vessel_entry_pushes_under_its_declared_coordinate() throws Exception {
    Path vesselJar = tempDir.resolve("billing-vessel.jar");
    Files.writeString(vesselJar, "pretend-plain-runnable-jar");
    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest,
        """
        kind: ArtifactSet
        tenant:
          billing:
            - artifact: billing-vessel.jar
              kind: vessel
              name: com.example.billing-vessel
              version: 1.0.0
        """);

    int exitCode = run("apply", "-f", manifest.toString(), "--server", serverAddress);

    assertEquals(0, exitCode, errBuffer.toString(StandardCharsets.UTF_8));
    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    ControlPlaneClient.HeadResult head = client.head("/artifacts/com.example.billing-vessel/1.0.0");
    assertEquals(200, head.statusCode());
    assertEquals("JAR", head.kind().orElseThrow());
    assertEquals("billing", head.tenantId().orElseThrow());
  }

  @Test
  void a_bundle_entry_zips_the_directory_and_pushes_it_as_a_bundle() throws Exception {
    Path appDir = tempDir.resolve("quarkus-app");
    Files.createDirectories(appDir.resolve("lib"));
    Files.writeString(appDir.resolve("quarkus-run.jar"), "pretend-run-jar");
    Files.writeString(appDir.resolve("lib/dep.jar"), "pretend-dep-jar");
    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest,
        """
        kind: ArtifactSet
        modules:
          - artifact: quarkus-app
            kind: bundle
            name: com.example.report
            version: 2.0.0
            command: [java, -jar, quarkus-run.jar]
        """);

    int exitCode = run("apply", "-f", manifest.toString(), "--server", serverAddress);

    assertEquals(0, exitCode, errBuffer.toString(StandardCharsets.UTF_8));
    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    ControlPlaneClient.HeadResult head = client.head("/artifacts/com.example.report/2.0.0");
    assertEquals(200, head.statusCode());
    assertEquals("BUNDLE", head.kind().orElseThrow());

    // Re-applying the identical manifest must reproduce the identical zip digest -- the
    // deterministic-zip guarantee -- and land as the idempotent already-present outcome.
    outBuffer.reset();
    assertEquals(0, run("apply", "-f", manifest.toString(), "--server", serverAddress));
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("already-present"));
  }

  @Test
  void a_bundle_entry_conflicting_with_a_stored_jar_kind_fails_preflight() throws Exception {
    Path jarsDir = tempDir.resolve("jars");
    Path jar = buildModule("com.example.report", "3.0.0", jarsDir);
    ControlPlaneClient client = new ControlPlaneClient(serverAddress);
    client.expectSuccess(client.putFile("/artifacts/com.example.report/3.0.0", jar));

    Path appDir = tempDir.resolve("quarkus-app-conflict");
    Files.createDirectories(appDir);
    Files.writeString(appDir.resolve("quarkus-run.jar"), "pretend-run-jar");
    Path manifest = tempDir.resolve("artifactset.yaml");
    Files.writeString(
        manifest,
        """
        kind: ArtifactSet
        modules:
          - artifact: quarkus-app-conflict
            kind: bundle
            name: com.example.report
            version: 3.0.0
            command: [java, -jar, quarkus-run.jar]
        """);

    int exitCode = run("apply", "-f", manifest.toString(), "--server", serverAddress);

    assertEquals(1, exitCode);
    assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("kind"));
  }
}
