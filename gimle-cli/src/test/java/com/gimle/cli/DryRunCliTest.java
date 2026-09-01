package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.fafnir.FafnirCrypto;
import com.gimle.fafnir.FafnirServer;
import com.gimle.mimir.raft.RaftLog;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.rpc.StoreClient;
import com.gimle.mimir.rpc.StoreNode;
import com.gimle.mimir.rpc.StoreTransport;
import com.gimle.mimir.store.StateStore;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@code gimle apply --dry-run -f <manifest>} against a real {@link ApiServer}, the same in-process
 * store/Fafnir wiring {@code GimleCliTest} establishes.
 *
 * <p>Two properties are asserted throughout: the exit code a predicted rejection produces is the
 * one the real apply produces (a preview that a pipeline cannot gate on is of no use), and nothing
 * reaches the store on the way there.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class DryRunCliTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private StateStore store;
  private RaftNode storeRaftNode;
  private StoreTransport storeTransport;
  private StoreClient storeClient;
  private FafnirServer fafnirServer;
  private FafnirClient fafnirClient;
  private ApiServer server;
  private String serverAddress;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;

  @BeforeEach
  void startServer() throws IOException {
    store = new StateStore();
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

    server = new ApiServer(storeClient, 0, fafnirClient);
    server.start();
    serverAddress = "localhost:" + server.port();
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
  }

  @AfterEach
  void stopServer() {
    server.close();
    fafnirClient.close();
    fafnirServer.close();
    storeClient.close();
    storeTransport.close();
    storeRaftNode.close();
  }

  @Test
  void a_dry_run_that_would_be_applied_exits_zero_and_writes_nothing() throws Exception {
    registerNode("roomy", 512L * 1024 * 1024, 4000L);
    Path manifest = deploymentManifest("orders", 2, Optional.empty());

    assertEquals(0, run("apply", "-f", manifest.toString(), "--dry-run"), errBuffer::toString);
    String out = stdout();
    assertTrue(out.contains("dry run: Deployment/orders"), out);
    assertTrue(out.contains("verdict: would be applied"), out);
    assertTrue(out.contains("PASSED\tplacement"), out);
    assertTrue(store.listDeployments().isEmpty(), "a dry run must not create the deployment");

    // The identical apply without --dry-run does create it -- so the assertion above is not
    // passing merely because nothing works.
    assertEquals(0, run("apply", "-f", manifest.toString()), errBuffer::toString);
    assertEquals(1, store.listDeployments().size());
  }

  @Test
  void a_dry_run_predicting_a_quota_rejection_exits_with_the_real_applys_own_exit_code()
      throws Exception {
    store.putTenant(new Tenant("tight", new ResourceQuota(1, 1, 1)));
    Path manifest = deploymentManifest("over-quota", 1, Optional.of("tight"));

    assertEquals(
        CliExitCode.CONFLICT.code(),
        run("apply", "-f", manifest.toString(), "--dry-run"),
        errBuffer::toString);
    assertTrue(stdout().contains("verdict: would be rejected"), stdout());
    assertTrue(stderr().contains("past its resource quota"), stderr());
    assertTrue(store.listDeployments().isEmpty());

    // The same manifest, applied for real, fails the same way with the same exit code.
    errBuffer.reset();
    assertEquals(CliExitCode.CONFLICT.code(), run("apply", "-f", manifest.toString()));
    assertTrue(stderr().contains("past its resource quota"), stderr());
    assertTrue(store.listDeployments().isEmpty());
  }

  @Test
  void a_dry_run_under_o_json_emits_the_structured_verdict() throws Exception {
    store.putTenant(new Tenant("tight", new ResourceQuota(1, 1, 1)));
    Path manifest = deploymentManifest("over-quota", 1, Optional.of("tight"));

    assertEquals(
        CliExitCode.CONFLICT.code(),
        run("apply", "-f", manifest.toString(), "--dry-run", "-o", "json"));
    Map<String, Object> verdict = Json.asObject(Json.parse(stdout()));
    assertEquals(Boolean.TRUE, verdict.get("dryRun"));
    assertEquals("Deployment", verdict.get("kind"));
    assertEquals("over-quota", verdict.get("name"));
    assertEquals(Boolean.FALSE, verdict.get("admitted"));
    assertEquals(409L, ((Number) verdict.get("wouldRespondStatus")).longValue());
    List<Map<String, Object>> checks = Json.asObjectList(verdict.get("checks"));
    assertTrue(
        checks.stream()
            .anyMatch(c -> "admission".equals(c.get("name")) && "FAILED".equals(c.get("outcome"))),
        String.valueOf(checks));
  }

  @Test
  void an_unplaceable_replica_is_warned_about_but_never_fails_the_dry_run() throws Exception {
    // Registered, heartbeating, and far too small for the fixture's own 16Mi request.
    registerNode("cramped", 1024L, 4000L);
    Path manifest = deploymentManifest("no-room", 1, Optional.empty());

    assertEquals(0, run("apply", "-f", manifest.toString(), "--dry-run"), errBuffer::toString);
    assertTrue(stdout().contains("verdict: would be applied"), stdout());
    assertTrue(stderr().contains("would remain unplaced"), stderr());
    assertTrue(stderr().contains("memory is short by"), stderr());
    assertTrue(store.listDeployments().isEmpty());
  }

  @Test
  void every_previewable_workload_kind_accepts_the_flag_and_writes_nothing() throws Exception {
    registerNode("roomy", 512L * 1024 * 1024, 4000L);
    for (Path manifest :
        List.of(
            deploymentManifest("d1", 1, Optional.empty()),
            workloadManifest("j1", "Job", ""),
            cronJobManifest("c1"),
            workloadManifest("ds1", "DaemonSet", ""),
            workloadManifest("ss1", "StatefulSet", "replicas: 1\n"))) {
      outBuffer.reset();
      assertEquals(0, run("apply", "-f", manifest.toString(), "--dry-run"), errBuffer::toString);
      assertTrue(stdout().contains("verdict: would be applied"), stdout());
    }
    assertTrue(store.listDeployments().isEmpty());
    assertTrue(store.listJobSpecs().isEmpty());
    assertTrue(store.listCronJobSpecs().isEmpty());
    assertTrue(store.listDaemonSetSpecs().isEmpty());
    assertTrue(store.listStatefulSetSpecs().isEmpty());
  }

  @Test
  void a_dry_run_of_a_kind_with_no_preview_is_refused_rather_than_silently_ignored()
      throws Exception {
    Path manifest = tempDir.resolve("tenant.yaml");
    Files.writeString(
        manifest,
        """
        kind: Tenant
        name: acme
        quota:
          maxMemoryBytes: 1000000000
          maxCpuMillicores: 4000
          maxInstances: 10
        """);

    assertEquals(CliExitCode.GENERIC.code(), run("apply", "-f", manifest.toString(), "--dry-run"));
    assertTrue(stderr().contains("--dry-run is not supported for kind Tenant"), stderr());
    assertTrue(store.listTenants().stream().noneMatch(t -> t.id().equals("acme")));
  }

  // ---- helpers ----

  private int run(String... args) {
    String[] withServer = new String[args.length + 2];
    System.arraycopy(args, 0, withServer, 0, args.length);
    withServer[args.length] = "--server";
    withServer[args.length + 1] = serverAddress;
    return GimleCli.run(
        withServer,
        new PrintStream(outBuffer, true, StandardCharsets.UTF_8),
        new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private void registerNode(String nodeId, long memoryBytes, long cpuMillicores) {
    store.putNodeRegistration(
        new NodeRegistration(
            nodeId,
            new NodeCapabilities(
                Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2), Set.<String>of()),
            Optional.empty()));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId, new ResourceUsageSnapshot(memoryBytes, 0, cpuMillicores, 0), List.of()));
  }

  /** {@code TestModuleBuilder.minimalDescriptor} fixes this at 16Mi memory / 10m cpu request. */
  private Path fixtureJar() {
    return TestModuleBuilder.module("module com.gimle.fixture.dryrun {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor("com.gimle.fixture.dryrun", "1.0.0"))
        .build(tempDir.resolve("jars"), "fixture.jar");
  }

  private Path deploymentManifest(String name, int replicas, Optional<String> tenantId)
      throws IOException {
    return workloadManifest(
        name,
        "Deployment",
        "replicas: " + replicas + "\n" + tenantId.map(id -> "tenantId: " + id + "\n").orElse(""));
  }

  private Path workloadManifest(String name, String kind, String extraFields) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    Files.writeString(
        file,
        """
        kind: %s
        name: %s
        module:
          name: com.gimle.fixture.dryrun
          version: 1.0.0
        artifactPath: %s
        %s"""
            .formatted(kind, name, fixtureJar().toAbsolutePath(), extraFields));
    return file;
  }

  private Path cronJobManifest(String name) throws IOException {
    Path file = tempDir.resolve(name + ".yaml");
    Files.writeString(
        file,
        """
        kind: CronJob
        name: %s
        schedule: "*/5 * * * *"
        jobTemplate:
          module:
            name: com.gimle.fixture.dryrun
            version: 1.0.0
          artifactPath: %s
        """
            .formatted(name, fixtureJar().toAbsolutePath()));
    return file;
  }
}
