package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.time.TestClock;
import com.gimle.core.time.TestScheduler;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.fabric.transport.FabricClient;
import com.gimle.fabric.transport.FabricFrame;
import com.gimle.fabric.transport.FabricServer;
import com.gimle.fabric.transport.ObjectMarshalling;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Proves {@link FabricServerTlsWatcher} actually detects a certificate file changed on disk --
 * overwritten in place the same way an agent-managed worker sees its own rotation happen out from
 * under it -- and calls {@link FabricServer#reloadTlsMaterial()}, per {@code
 * claudedocs/tls-transport-security-design.md} §6.2/§6.4 item 2. Uses a fast poll interval so the
 * test doesn't have to wait out a real rotation cadence.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricServerTlsWatcherTest {

  /** Exactly the interval {@code WorkerMain} starts its own watcher with. */
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

  public interface Ping {
    String ping(String name);
  }

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final TraceContext TRACE = new TraceContext(1L, 2L, 3L, (byte) 1);
  private static final ModuleId OWNER =
      new ModuleId("com.gimle.example.watcher-test", Version.parse("1.0.0"));

  @TempDir private Path tempDir;

  private FabricServer server;
  private FabricServerTlsWatcher watcher;
  private int fileCounter;

  @AfterEach
  void tearDown() {
    if (watcher != null) {
      watcher.close();
    }
    if (server != null) {
      server.close();
    }
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  @Test
  @Timeout(10)
  void detects_a_rotated_certificate_file_and_reloads_the_fabric_server() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureTls(ca);
    Path certFile = Path.of(System.getProperty(CERT_FILE_PROPERTY));
    Path keyFile = Path.of(System.getProperty(KEY_FILE_PROPERTY));

    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Ping.class, name -> "pong:" + name);
    server = new FabricServer(backing, Ping.class.getClassLoader());
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    // Baseline: the original material works before any rotation or watching starts.
    assertEquals("pong:before", callPing(address, "before"));

    // The real poll interval WorkerMain uses, driven tick by tick rather than waited out.
    TestScheduler scheduler = new TestScheduler(new TestClock());
    watcher = new FabricServerTlsWatcher(scheduler);
    watcher.start(server, POLL_INTERVAL);

    assertEquals(
        1,
        scheduler.pendingTaskCount(),
        "the watcher should have scheduled exactly one periodic poll");

    // Rotate: a fresh CA-signed leaf written over the *same* cert/key file paths -- exactly what
    // §4b's own rotation does, and what an agent-managed worker sees happen underneath it. No
    // channel tells the watcher this happened; it must notice the mtime move on its own.
    KeyPair rotatedKeyPair = generateRsaKeyPair();
    PKCS10CertificationRequest rotatedCsr =
        CertificateSigningRequests.generate(rotatedKeyPair, new X500Name("CN=fabric-node"));
    X509Certificate rotatedLeaf = ca.signCertificateRequest(rotatedCsr, Duration.ofDays(1));
    // Key before cert, matching AgentMain's own write order (§6.2): the watcher polls only
    // certFile's mtime, so the key must already be in place by the time that's observed.
    overwritePem(keyFile, "PRIVATE KEY", rotatedKeyPair.getPrivate().getEncoded());
    overwritePem(certFile, "CERTIFICATE", rotatedLeaf.getEncoded());

    // Exactly one poll. The tick runs on this thread and reloads synchronously, so by the time
    // advance() returns the rebind has already happened -- no window to wait out.
    assertEquals(1, scheduler.advance(POLL_INTERVAL));
    assertEquals(
        "pong:during",
        callPing(address, "during"),
        "the listener must keep serving on the rotated material");

    // The reload-every-tick guard, now asserted rather than inferred: with mtime unchanged, five
    // further polls must rebind nothing at all. Previously this could only be approximated by
    // pinging repeatedly and hoping a rebind would have been caught in the act.
    for (int i = 0; i < 5; i++) {
      assertEquals(1, scheduler.advance(POLL_INTERVAL), "poll " + i + " should have fired once");
      assertEquals(
          "pong:steady-" + i,
          callPing(address, "steady-" + i),
          "an unchanged certificate file must not trigger a rebind");
    }
  }

  private static String callPing(InetSocketAddress address, String arg) throws IOException {
    FabricFrame response = FabricClient.call(address, invokePing(arg));
    FabricFrame.InvokeResponse invokeResponse = (FabricFrame.InvokeResponse) response;
    return (String) ObjectMarshalling.deserialize(invokeResponse.serializedReturn());
  }

  private static FabricFrame.InvokeRequest invokePing(String arg) {
    return new FabricFrame.InvokeRequest(
        1L,
        TRACE,
        Ping.class.getName(),
        "ping",
        new String[] {"java.lang.String"},
        ObjectMarshalling.serialize(new Object[] {arg}));
  }

  private void configureTls(CertificateAuthority ca) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=fabric-node"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    Path certFile = writePem("cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem("key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem("ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
    System.setProperty(CA_FILE_PROPERTY, caFile.toString());
  }

  private Path writePem(String fileName, String label, byte[] derBytes) throws IOException {
    Path path = tempDir.resolve((fileCounter++) + "-" + fileName);
    Files.writeString(path, pem(label, derBytes));
    return path;
  }

  private void overwritePem(Path path, String label, byte[] derBytes) throws IOException {
    Files.writeString(path, pem(label, derBytes));
  }

  private static String pem(String label, byte[] derBytes) {
    String base64 =
        Base64.getMimeEncoder(64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
            .encodeToString(derBytes);
    return "-----BEGIN "
        + label
        + "-----"
        + System.lineSeparator()
        + base64
        + System.lineSeparator()
        + "-----END "
        + label
        + "-----"
        + System.lineSeparator();
  }

  private static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
