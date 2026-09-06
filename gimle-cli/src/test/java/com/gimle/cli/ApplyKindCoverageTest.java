package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code apply -f} accepts far more manifest kinds than the workload ones, and the help text is the
 * only place an operator ever learns which. This class pins both halves of that: every kind {@code
 * handleApply} dispatches really does apply against a real control plane, and every one of them is
 * named by the CLI's own help surfaces.
 */
class ApplyKindCoverageTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private RaftNode storeRaftNode;
  private StoreTransport storeTransport;
  private StoreClient storeClient;
  private FafnirServer fafnirServer;
  private FafnirClient fafnirClient;
  private ApiServer server;
  private String serverAddress;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;

  @BeforeEach
  void startServer() throws IOException {
    StateStore store = new StateStore();
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
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
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

  private int run(String... args) {
    String[] withServer = new String[args.length + 2];
    System.arraycopy(args, 0, withServer, 0, args.length);
    withServer[args.length] = "--server";
    withServer[args.length + 1] = serverAddress;
    return GimleCli.run(withServer, out, err);
  }

  private String stdout() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private int applyManifest(String fileName, String yaml) throws IOException {
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, yaml);
    return run("apply", "-f", file.toString());
  }

  /**
   * One manifest per non-workload kind the dispatcher names, each applied for real. Grouped into
   * one test because they share a single control plane and several depend on an earlier one (a
   * RoleBinding needs its Role; a LimitRange and a NetworkPolicy name a Tenant).
   */
  @Test
  void every_non_workload_kind_the_dispatcher_names_really_applies() throws Exception {
    assertEquals(
        0,
        applyManifest(
            "tenant.yaml",
            """
            kind: Tenant
            name: acme
            quota:
              maxMemoryBytes: 1000000000
              maxCpuMillicores: 4000
              maxInstances: 10
            """),
        stderr());
    assertTrue(stdout().contains("tenant/acme applied"), stdout());

    outBuffer.reset();
    assertEquals(
        0,
        applyManifest(
            "service.yaml",
            """
            kind: Service
            name: orders
            tenantId: acme
            deploymentNames: [orders-api]
            port: 8080
            targetPort: 8080
            """),
        stderr());
    assertTrue(stdout().contains("service/orders applied"), stdout());

    outBuffer.reset();
    assertEquals(
        0,
        applyManifest(
            "networkpolicy.yaml",
            """
            kind: NetworkPolicy
            name: acme-ingress
            tenantId: acme
            allowedCallerTenantIds: [acme]
            """),
        stderr());
    assertTrue(stdout().contains("networkpolicy/acme-ingress applied"), stdout());

    outBuffer.reset();
    assertEquals(
        0,
        applyManifest(
            "ingress.yaml",
            """
            kind: Ingress
            name: public
            tenantId: acme
            routes:
              - {kind: SERVICE, path: /orders, serviceName: orders}
            """),
        stderr());
    assertTrue(stdout().contains("ingress/public configured"), stdout());

    outBuffer.reset();
    assertEquals(
        0,
        applyManifest(
            "limitrange.yaml",
            """
            kind: LimitRange
            name: acme
            minRequest: {memory: 64Mi, cpu: 100m}
            maxLimit: {memory: 512Mi, cpu: 2000m}
            """),
        stderr());
    assertTrue(stdout().contains("limitrange/acme applied"), stdout());

    outBuffer.reset();
    assertEquals(
        0,
        applyManifest(
            "role.yaml",
            """
            kind: Role
            name: orders-reader
            permissions:
              - {resource: DEPLOYMENT, verb: READ, tenantScope: acme}
            """),
        stderr());
    assertTrue(stdout().contains("role/orders-reader applied"), stdout());

    outBuffer.reset();
    assertEquals(
        0,
        applyManifest(
            "account.yaml",
            """
            kind: Account
            name: dana
            password: correct-horse-battery
            groups: [operators]
            """),
        stderr());
    assertTrue(stdout().contains("account/dana applied"), stdout());

    outBuffer.reset();
    assertEquals(
        0,
        applyManifest(
            "rolebinding.yaml",
            """
            kind: RoleBinding
            name: dana-orders-reader
            subject: "user:dana"
            roleName: orders-reader
            """),
        stderr());
    assertTrue(stdout().contains("rolebinding/dana-orders-reader applied"), stdout());
  }

  /**
   * The help text is the only discoverability surface for what {@code apply -f} takes; a kind the
   * dispatcher accepts but no help surface names is invisible to every operator who has not read
   * the source.
   */
  @Test
  void the_help_text_names_every_kind_apply_accepts() {
    assertEquals(0, GimleCli.run(new String[] {"-h"}, out, err), stderr());
    String help = stdout();
    for (String kind : GimleCli.BUILT_IN_APPLY_KINDS) {
      assertTrue(help.contains(kind), "gimle -h never mentions apply kind " + kind + ":\n" + help);
    }
  }

  @Test
  void the_apply_usage_names_every_kind_apply_accepts() {
    assertEquals(0, run("apply", "--help"), stderr());
    String usage = stdout() + stderr();
    for (String kind : GimleCli.BUILT_IN_APPLY_KINDS) {
      assertTrue(
          usage.contains(kind), "gimle apply's usage never mentions kind " + kind + ":\n" + usage);
    }
  }
}
