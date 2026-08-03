package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.fabric.registry.Greeter;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
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
 * Proves {@code gimle.transport.protocol=tls} actually swaps {@link FabricServer}/{@link
 * FabricClient}'s cross-machine (TCP) path onto real mTLS -- a genuine handshake and invocation
 * round trip, not just "the code compiles" -- while the same-machine Unix domain socket path stays
 * plaintext regardless, per {@code claudedocs/tls-transport-security-design.md} §1's own table
 * ("kernel-mediated, never leaves the machine"). Uses {@link FabricServer}/{@link FabricClient}
 * directly rather than going through {@code FabricServiceRegistry} -- lower-level, and this is a
 * transport-layer property, not a load-balancing or catalog one.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricTransportTlsTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final TraceContext TRACE = new TraceContext(1L, 2L, 3L, (byte) 1);

  @TempDir private Path tempDir;

  private FabricServer server;

  @AfterEach
  void tearDown() {
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
  void cross_machine_invocation_succeeds_over_mtls() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureTls(ca);

    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, name -> "hello:" + name);
    server = new FabricServer(backing, Greeter.class.getClassLoader());
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    FabricFrame response = FabricClient.call(address, invokeGreet("world"));

    FabricFrame.InvokeResponse invokeResponse = (FabricFrame.InvokeResponse) response;
    assertEquals("hello:world", ObjectMarshalling.deserialize(invokeResponse.serializedReturn()));
  }

  @Test
  @Timeout(10)
  void same_machine_unix_domain_socket_path_ignores_tls_config() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    configureTls(ca);

    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, name -> "hello:" + name);
    server = new FabricServer(backing, Greeter.class.getClassLoader());
    UnixDomainSocketAddress udsAddress = UnixDomainSocketAddress.of(tempDir.resolve("fabric.sock"));
    server.listen(udsAddress);

    // If the UDS path incorrectly picked up gimle.transport.protocol=tls, this call would either
    // throw (a plaintext client speaking to a TLS-only listener) or hang; succeeding proves the
    // UDS path stayed plaintext exactly as designed, unaffected by the TLS config this test set.
    FabricFrame response = FabricClient.call(udsAddress, invokeGreet("local"));
    FabricFrame.InvokeResponse invokeResponse = (FabricFrame.InvokeResponse) response;
    assertEquals("hello:local", ObjectMarshalling.deserialize(invokeResponse.serializedReturn()));
  }

  @Test
  @Timeout(10)
  void cross_machine_call_is_rejected_when_client_trusts_a_different_ca() throws Exception {
    CertificateAuthority realCa =
        CertificateAuthority.generateSelfSignedCa(new X500Name("CN=real-ca"), Duration.ofDays(1));
    configureTls(realCa);

    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, name -> "hello:" + name);
    server = new FabricServer(backing, Greeter.class.getClassLoader());
    InetSocketAddress address =
        (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));

    // The server's SSLServerSocketFactory is resolved and baked in at listen() time above;
    // reconfiguring the global CA now only affects new connection attempts, i.e. the client below.
    CertificateAuthority impostorCa =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=impostor-ca"), Duration.ofDays(1));
    configureTls(impostorCa);

    assertThrows(IOException.class, () -> FabricClient.call(address, invokeGreet("world")));
  }

  private static final com.gimle.core.module.ModuleId OWNER =
      new com.gimle.core.module.ModuleId(
          "com.gimle.example.greeter", com.gimle.core.module.Version.parse("1.0.0"));

  private FabricFrame.InvokeRequest invokeGreet(String arg) {
    return new FabricFrame.InvokeRequest(
        1L,
        TRACE,
        Greeter.class.getName(),
        "greet",
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

  private int fileCounter;

  private Path writePem(String fileName, String label, byte[] derBytes) throws IOException {
    String base64 =
        Base64.getMimeEncoder(64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
            .encodeToString(derBytes);
    String pem =
        "-----BEGIN "
            + label
            + "-----"
            + System.lineSeparator()
            + base64
            + System.lineSeparator()
            + "-----END "
            + label
            + "-----"
            + System.lineSeparator();
    Path path = tempDir.resolve((fileCounter++) + "-" + fileName);
    Files.writeString(path, pem);
    return path;
  }

  private static KeyPair generateRsaKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
