package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.api.ApiServer;
import com.gimle.controlplane.fafnir.FafnirClient;
import com.gimle.core.protocol.Json;
import com.gimle.core.tenant.Tenant;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
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
 * {@code gimle secret}'s typed writes, its version listing, and the bulk export/import pair, driven
 * end to end through {@link GimleCli#run} against a real store + Fafnir + {@link ApiServer} -- the
 * same in-process wiring {@code SecretMapCommandTest} establishes.
 *
 * <p>Every server started here reads {@code gimle.transport.protocol} (and the TLS paths that go
 * with it) from JVM-global system properties, so this class must not run alongside a class that
 * mutates them -- read access is enough, since nothing here writes any.
 */
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class SecretCommandTest {

  private static final String CERTIFICATE_PEM =
      "-----BEGIN CERTIFICATE-----\nMIIBkTCB+wIJAKl\n-----END CERTIFICATE-----\n";

  /**
   * The destination of every export/import round trip below. This server is plaintext, and
   * plaintext transport resolves no caller identity, so {@link ApiServer} deliberately refuses to
   * create a second real tenant there -- the seeded default tenant is the one other tenant a
   * plaintext deployment genuinely has, and moving secrets into it exercises the same cross-tenant
   * path a second operator-created tenant would.
   */
  private static final String DESTINATION_TENANT = Tenant.DEFAULT_TENANT_ID;

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
  void versions_reports_each_versions_author_write_time_and_type() {
    createTenant("acme");
    assertEquals(0, setSecret("acme", "db-password", "hunter2"), errBuffer::toString);
    assertEquals(0, setSecret("acme", "db-password", "hunter3"), errBuffer::toString);
    outBuffer.reset();

    int exitCode = run("secret", "versions", "acme", "db-password", "--server", serverAddress);

    assertEquals(0, exitCode, errBuffer::toString);
    String out = outBuffer.toString(StandardCharsets.UTF_8);
    assertTrue(out.contains("author"), out);
    assertTrue(out.contains("writtenAt"), out);
    assertTrue(out.contains("opaque"), out);
  }

  @Test
  void a_typed_write_reads_a_multi_line_value_from_a_file_and_reports_its_type() throws Exception {
    createTenant("acme");
    Path pem = Files.writeString(tempDir.resolve("tls.pem"), CERTIFICATE_PEM);

    int exitCode =
        run(
            "secret",
            "set",
            "acme",
            "tls-cert",
            "--from-file",
            pem.toString(),
            "--type",
            "pem-certificate",
            "--server",
            serverAddress);

    assertEquals(0, exitCode, errBuffer::toString);
    outBuffer.reset();
    assertEquals(0, run("secret", "versions", "acme", "tls-cert", "--server", serverAddress));
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("pem-certificate"));
  }

  @Test
  void a_typed_write_whose_value_is_malformed_exits_nonzero_and_stores_nothing() {
    createTenant("acme");

    int exitCode =
        run(
            "secret",
            "set",
            "acme",
            "tls-cert",
            "--value",
            "-----BEGIN CERTIFICATE-----",
            "--type",
            "pem-certificate",
            "--server",
            serverAddress);

    assertNotEquals(0, exitCode);
    outBuffer.reset();
    assertNotEquals(0, run("secret", "get", "acme", "tls-cert", "--server", serverAddress));
  }

  @Test
  void set_requires_exactly_one_of_value_or_from_file() {
    createTenant("acme");

    assertNotEquals(0, run("secret", "set", "acme", "k", "--server", serverAddress));
    assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("--from-file"));
  }

  @Test
  void export_writes_every_live_secret_and_import_restores_them_into_another_tenant()
      throws Exception {
    createTenant("acme");
    assertEquals(0, setSecret("acme", "db-password", "hunter2"), errBuffer::toString);
    assertEquals(0, setSecret("acme", "api-key", "abc123"), errBuffer::toString);
    Path exportFile = tempDir.resolve("acme-secrets.json");

    assertEquals(
        0,
        run("secret", "export", "acme", "--out", exportFile.toString(), "--server", serverAddress),
        errBuffer::toString);

    Map<String, Object> document =
        Json.asObject(Json.parse(Files.readString(exportFile, StandardCharsets.UTF_8)));
    Map<String, Object> exported = Json.asObject(document.get("secrets"));
    assertEquals(Set.of("db-password", "api-key"), exported.keySet());
    assertEquals("acme", document.get("tenantId"));

    assertEquals(
        0,
        run(
            "secret",
            "import",
            DESTINATION_TENANT,
            "--in",
            exportFile.toString(),
            "--server",
            serverAddress),
        errBuffer::toString);
    outBuffer.reset();
    assertEquals(
        0,
        run("secret", "get", DESTINATION_TENANT, "db-password", "--server", serverAddress),
        errBuffer::toString);
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("hunter2"));
  }

  @Test
  void an_exported_secrets_declared_type_survives_the_round_trip() throws Exception {
    createTenant("acme");
    Path pem = Files.writeString(tempDir.resolve("tls.pem"), CERTIFICATE_PEM);
    assertEquals(
        0,
        run(
            "secret",
            "set",
            "acme",
            "tls-cert",
            "--from-file",
            pem.toString(),
            "--type",
            "pem-certificate",
            "--server",
            serverAddress),
        errBuffer::toString);
    Path exportFile = tempDir.resolve("typed-export.json");

    assertEquals(
        0,
        run("secret", "export", "acme", "--out", exportFile.toString(), "--server", serverAddress),
        errBuffer::toString);
    assertEquals(
        0,
        run(
            "secret",
            "import",
            DESTINATION_TENANT,
            "--in",
            exportFile.toString(),
            "--server",
            serverAddress),
        errBuffer::toString);

    outBuffer.reset();
    assertEquals(
        0, run("secret", "versions", DESTINATION_TENANT, "tls-cert", "--server", serverAddress));
    assertTrue(outBuffer.toString(StandardCharsets.UTF_8).contains("pem-certificate"));
  }

  @Test
  void export_omits_a_soft_deleted_secret() throws Exception {
    createTenant("acme");
    assertEquals(0, setSecret("acme", "retired", "old"), errBuffer::toString);
    assertEquals(
        0,
        run("secret", "delete", "acme", "retired", "--server", serverAddress),
        errBuffer::toString);
    Path exportFile = tempDir.resolve("after-delete.json");

    assertEquals(
        0,
        run("secret", "export", "acme", "--out", exportFile.toString(), "--server", serverAddress),
        errBuffer::toString);

    Map<String, Object> document =
        Json.asObject(Json.parse(Files.readString(exportFile, StandardCharsets.UTF_8)));
    assertTrue(Json.asObject(document.get("secrets")).isEmpty());
  }

  /**
   * The export file is plaintext secret material by necessity -- ciphertext under the source
   * cluster's master key would be useless at a destination with a different one -- so the two
   * protections it does carry (owner-only permissions, never silently replacing an existing file)
   * are load-bearing, not cosmetic.
   */
  @Test
  void an_export_file_is_created_owner_only_and_an_existing_path_is_never_overwritten()
      throws Exception {
    createTenant("acme");
    assertEquals(0, setSecret("acme", "db-password", "hunter2"), errBuffer::toString);
    Path exportFile = tempDir.resolve("perms.json");

    assertEquals(
        0,
        run("secret", "export", "acme", "--out", exportFile.toString(), "--server", serverAddress),
        errBuffer::toString);

    if (exportFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals(
          PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(exportFile));
    }
    assertNotEquals(
        0,
        run("secret", "export", "acme", "--out", exportFile.toString(), "--server", serverAddress));
    assertTrue(errBuffer.toString(StandardCharsets.UTF_8).contains("refusing to overwrite"));
  }

  @Test
  void importing_from_a_missing_file_exits_nonzero_rather_than_silently_doing_nothing() {
    createTenant("acme");

    assertNotEquals(
        0,
        run(
            "secret",
            "import",
            "acme",
            "--in",
            tempDir.resolve("no-such-file.json").toString(),
            "--server",
            serverAddress));
  }

  @Test
  void an_oversized_secret_value_exits_nonzero_and_stores_nothing() {
    createTenant("acme");

    int exitCode = setSecret("acme", "too-big", "x".repeat(600 * 1024));

    assertNotEquals(0, exitCode);
    outBuffer.reset();
    assertNotEquals(0, run("secret", "get", "acme", "too-big", "--server", serverAddress));
    assertFalse(outBuffer.toString(StandardCharsets.UTF_8).contains("xxxx"));
  }

  private int setSecret(String tenantId, String key, String value) {
    return run("secret", "set", tenantId, key, "--value", value, "--server", serverAddress);
  }

  private int run(String... args) {
    return GimleCli.run(args, new PrintStream(outBuffer), new PrintStream(errBuffer));
  }

  private void createTenant(String tenantId) {
    assertEquals(
        0,
        run(
            "set",
            "tenant",
            tenantId,
            "--max-memory-bytes",
            "1000000000",
            "--max-cpu-millicores",
            "4000",
            "--max-instances",
            "10",
            "--server",
            serverAddress),
        errBuffer::toString);
  }
}
