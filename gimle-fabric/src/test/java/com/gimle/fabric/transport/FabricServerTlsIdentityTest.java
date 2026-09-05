package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.BuiltinRoles;
import com.gimle.core.exception.GimleFabricAuthorizationException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.fabric.registry.Greeter;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import com.gimle.pki.Subjects;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * On a TLS hop the receiving worker decides a caller's tenant from the connection's verified client
 * certificate ({@code O=gimle:tenant:<id>}, stamped by the control plane at issuance), never from
 * the tenant the caller wrote into the frame: that field is the caller's own claim, and a worker
 * able to open a raw connection to the listener can write anything into it. Each test below mints a
 * client certificate carrying a specific tenant group, dials the listener directly with it, and
 * shows which of the two -- claim or certificate -- the listener actually believed. The listener's
 * own {@code SSLServerSocketFactory} is fixed at {@code listen()} time, so the TLS properties can
 * be re-pointed at a different client identity per call without touching the server.
 */
// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricServerTlsIdentityTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";
  private static final TraceContext TRACE = new TraceContext(1L, 2L, 3L, (byte) 1);
  private static final AtomicLong CORRELATION_IDS = new AtomicLong();
  private static final ModuleInstanceId OWNER =
      ModuleInstanceId.unattached(
          new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")));

  @TempDir private Path tempDir;

  private CertificateAuthority ca;
  private Path caFile;
  private FabricServer server;
  private InetSocketAddress tcpAddress;
  private UnixDomainSocketAddress udsAddress;
  private int fileCounter;

  /**
   * A listener whose one export is restricted to {@code tenant-a}, presenting an untenanted cert.
   */
  @BeforeEach
  void startRestrictedListener() throws Exception {
    ca = CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    caFile = writePem("ca.pem", Pem.encodeCertificate(ca.certificate()));
    presentIdentity("fabric-node", List.of(BuiltinRoles.GROUP_NODES));

    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, name -> "hello:" + name);
    ServiceExport restricted =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));
    server =
        new FabricServer(
            backing,
            Greeter.class.getClassLoader(),
            id -> Optional.empty(),
            id -> Optional.empty(),
            Optional.empty(),
            owner -> owner.equals(OWNER) ? List.of(restricted) : List.of());
    tcpAddress = (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));
    udsAddress = UnixDomainSocketAddress.of(tempDir.resolve("fabric.sock"));
    server.listen(udsAddress);
  }

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

  /**
   * The forgery the certificate check exists to catch: a real worker of {@code tenant-b} claims to
   * be {@code tenant-a} -- the one tenant the export admits -- in the frame it writes. Before the
   * listener read the certificate, that claim alone was enough to be let through.
   */
  @Test
  @Timeout(10)
  void a_claim_that_disagrees_with_the_callers_certificate_is_refused() throws Exception {
    presentIdentity(
        "node-2:orders#0",
        List.of(BuiltinRoles.GROUP_WORKERS, BuiltinRoles.tenantGroup("tenant-b")));

    FabricFrame response = FabricClient.call(tcpAddress, invokeGreet(Optional.of("tenant-a")));

    Throwable thrown = errorOf(response);
    assertInstanceOf(GimleFabricAuthorizationException.class, thrown);
    assertTrue(
        thrown.getMessage().contains("certifies tenant tenant-b"),
        "expected the refusal to name the certified tenant; message=" + thrown.getMessage());
  }

  @Test
  @Timeout(10)
  void a_claim_matching_the_callers_certificate_is_served() throws Exception {
    presentIdentity(
        "node-2:orders#0",
        List.of(BuiltinRoles.GROUP_WORKERS, BuiltinRoles.tenantGroup("tenant-a")));

    FabricFrame response = FabricClient.call(tcpAddress, invokeGreet(Optional.of("tenant-a")));

    assertEquals("hello:world", returnOf(response));
  }

  /** The certificate alone identifies the caller: no claim is needed, and none is missed. */
  @Test
  @Timeout(10)
  void a_caller_writing_no_claim_is_identified_by_its_certificate_alone() throws Exception {
    presentIdentity(
        "node-2:orders#0",
        List.of(BuiltinRoles.GROUP_WORKERS, BuiltinRoles.tenantGroup("tenant-a")));

    FabricFrame response = FabricClient.call(tcpAddress, invokeGreet(Optional.empty()));

    assertEquals("hello:world", returnOf(response));
  }

  /**
   * The mirror of the first case: the certificate names {@code tenant-b}, the caller claims
   * nothing, and the export's own re-check runs against the certified tenant -- the export admits
   * only {@code tenant-a}, so the call is refused for the tenant the caller actually is.
   */
  @Test
  @Timeout(10)
  void the_exports_tenant_re_check_runs_against_the_certified_tenant() throws Exception {
    presentIdentity(
        "node-2:orders#0",
        List.of(BuiltinRoles.GROUP_WORKERS, BuiltinRoles.tenantGroup("tenant-b")));

    FabricFrame response = FabricClient.call(tcpAddress, invokeGreet(Optional.empty()));

    Throwable thrown = errorOf(response);
    assertInstanceOf(GimleFabricAuthorizationException.class, thrown);
    assertTrue(
        thrown.getMessage().startsWith("tenant tenant-b is not permitted"),
        "expected the export re-check to name tenant-b; message=" + thrown.getMessage());
  }

  /**
   * A certificate with no tenant group at all -- a node's own, or any other non-worker identity --
   * certifies no tenant, so claiming one is a mismatch too, not a claim quietly taken on trust.
   */
  @Test
  @Timeout(10)
  void a_certificate_carrying_no_tenant_cannot_claim_one() throws Exception {
    presentIdentity("node-2", List.of(BuiltinRoles.GROUP_NODES));

    FabricFrame response = FabricClient.call(tcpAddress, invokeGreet(Optional.of("tenant-a")));

    Throwable thrown = errorOf(response);
    assertInstanceOf(GimleFabricAuthorizationException.class, thrown);
    assertTrue(
        thrown.getMessage().contains("certifies no tenant at all"),
        "expected the refusal to say the certificate carries no tenant; message="
            + thrown.getMessage());
  }

  /**
   * The documented limit: the same-machine Unix-domain-socket hop is always plaintext and carries
   * no certificate, so there the frame's own claim is still what the listener has to go on.
   */
  @Test
  @Timeout(10)
  void the_same_machine_unix_socket_hop_still_serves_the_frames_own_claim() throws Exception {
    FabricFrame response = FabricClient.call(udsAddress, invokeGreet(Optional.of("tenant-a")));

    assertEquals("hello:world", returnOf(response));
  }

  private FabricFrame.InvokeRequest invokeGreet(Optional<String> claimedTenantId) {
    return new FabricFrame.InvokeRequest(
        CORRELATION_IDS.incrementAndGet(),
        TRACE,
        Greeter.class.getName(),
        "greet",
        new String[] {"java.lang.String"},
        ObjectMarshalling.serialize(new Object[] {"world"}),
        claimedTenantId);
  }

  private Object returnOf(FabricFrame response) {
    FabricFrame.InvokeResponse invokeResponse =
        assertInstanceOf(FabricFrame.InvokeResponse.class, response);
    return ObjectMarshalling.deserialize(
        invokeResponse.serializedReturn(), getClass().getClassLoader());
  }

  private Throwable errorOf(FabricFrame response) {
    FabricFrame.InvokeError error = assertInstanceOf(FabricFrame.InvokeError.class, response);
    return (Throwable)
        ObjectMarshalling.deserialize(error.serializedThrowable(), getClass().getClassLoader());
  }

  /**
   * Points {@code gimle.tls.*} at a freshly minted, CA-signed identity carrying exactly {@code
   * groups} as its {@code O=} RDNs -- what both {@link FabricClient} (per call) and {@link
   * FabricServer} (at {@code listen()}) read their own material from.
   */
  private void presentIdentity(String commonName, List<String> groups) throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name("CN=" + commonName));
    X509Certificate leaf =
        ca.signCertificateRequest(
            csr, Subjects.withOrganizations(csr.getSubject(), groups), Duration.ofDays(1));
    Path certFile = writePem("cert.pem", Pem.encodeCertificate(leaf));
    Path keyFile = writePem("key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
    System.setProperty(CA_FILE_PROPERTY, caFile.toString());
  }

  private Path writePem(String fileName, String pem) throws IOException {
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
