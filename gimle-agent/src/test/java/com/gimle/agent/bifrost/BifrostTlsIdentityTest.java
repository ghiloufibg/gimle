package com.gimle.agent.bifrost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gimle.agent.networkpolicy.NetworkPolicySnapshot;
import com.gimle.core.tenant.NetworkPolicyRule;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The TLS-terminating identity-verifying mode: with a {@code BifrostSettings#tlsContext} configured
 * the listener demands a cluster-CA-signed client certificate and enforces an applicable {@code
 * NetworkPolicySpec} against the certificate's own {@code O=gimle:tenant:<id>} membership group --
 * the enforcement a plaintext listener can only ever approximate by failing closed.
 */
class BifrostTlsIdentityTest {

  @TempDir Path tempDir;

  private final InMemoryServiceSource source = new InMemoryServiceSource();
  private final List<ServerSocket> backends = new ArrayList<>();
  private BifrostProxy proxy;
  private CertificateAuthority ca;
  private Path caFile;

  @BeforeEach
  void setUp() throws Exception {
    ca = CertificateAuthority.generateSelfSignedCa(new X500Name("CN=test-ca"), Duration.ofDays(1));
    caFile = writePem("ca.pem", Pem.encodeCertificate(ca.certificate()));
  }

  @AfterEach
  void tearDown() throws IOException {
    if (proxy != null) {
      proxy.close();
    }
    for (ServerSocket backend : backends) {
      backend.close();
    }
  }

  @Test
  @Timeout(15)
  void a_caller_with_an_allowed_tenant_certificate_is_proxied_and_a_disallowed_one_refused()
      throws Exception {
    ServiceEndpoint backend = startTaggedBackend("A");
    source.put("orders", Optional.of("acme"), Set.of("orders-service"), 9601, List.of(backend));
    proxy =
        new BifrostProxy(
            source,
            () -> new NetworkPolicySnapshot(List.of(new NetworkPolicyRule("allow-partner", "acme", Set.of("partner-tenant"))), Set.of()),
            new BifrostSettings(
                Duration.ofMinutes(5),
                false,
                Optional.empty(),
                Optional.of(contextFor("O=gimle-bifrost-server,CN=node-a"))));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    SSLContext allowed = contextFor("O=gimle:tenant:partner-tenant,CN=partner-caller");
    SSLContext sameTenant = contextFor("O=gimle:tenant:acme,CN=own-caller");
    SSLContext denied = contextFor("O=gimle:tenant:mallory,CN=mallory-caller");
    SSLContext noTenantClaim = contextFor("O=gimle:operators,CN=untenanted-caller");

    assertEquals("A", readTagOverTls(allowed, clusterAddress));
    assertEquals("A", readTagOverTls(sameTenant, clusterAddress));
    assertNull(readTagOverTls(denied, clusterAddress));
    assertNull(readTagOverTls(noTenantClaim, clusterAddress));
  }

  @Test
  @Timeout(15)
  void an_unrestricted_service_proxies_any_authenticated_caller() throws Exception {
    ServiceEndpoint backend = startTaggedBackend("B");
    source.put("payments", Optional.of("acme"), Set.of("payments-service"), 9602, List.of(backend));
    proxy =
        new BifrostProxy(
            source,
            NetworkPolicySnapshot::empty,
            new BifrostSettings(
                Duration.ofMinutes(5),
                false,
                Optional.empty(),
                Optional.of(contextFor("O=gimle-bifrost-server,CN=node-a"))));
    proxy.pollOnce();

    SSLContext anyCaller = contextFor("O=gimle:tenant:mallory,CN=whoever");
    assertEquals("B", readTagOverTls(anyCaller, proxy.boundAddressFor("payments").orElseThrow()));
  }

  private ServiceEndpoint startTaggedBackend(String tag) throws IOException {
    ServerSocket serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
    backends.add(serverSocket);
    Thread.ofVirtual()
        .name("bifrost-tls-test-backend-" + tag)
        .start(
            () -> {
              while (!serverSocket.isClosed()) {
                try (Socket connection = serverSocket.accept()) {
                  connection.getOutputStream().write((tag + "\n").getBytes(StandardCharsets.UTF_8));
                  connection.getOutputStream().flush();
                } catch (IOException e) {
                  return;
                }
              }
            });
    return new ServiceEndpoint("127.0.0.1", serverSocket.getLocalPort());
  }

  /** Null when the proxy refused the connection (EOF before any payload). */
  private static String readTagOverTls(SSLContext clientContext, InetSocketAddress clusterAddress)
      throws IOException {
    try (SSLSocket socket = (SSLSocket) clientContext.getSocketFactory().createSocket()) {
      socket.connect(clusterAddress, 2000);
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        return reader.readLine();
      }
    }
  }

  private SSLContext contextFor(String subject) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(keyPair, new X500Name(subject));
    String safeName = subject.replaceAll("[^a-zA-Z0-9]", "_");
    Path certFile =
        writePem(
            safeName + "-cert.pem",
            Pem.encodeCertificate(ca.signCertificateRequest(csr, Duration.ofDays(1))));
    Path keyFile = writePem(safeName + "-key.pem", Pem.encodePrivateKey(keyPair.getPrivate()));
    return SslContexts.forMutualTls(new TlsSettings(certFile, keyFile, caFile));
  }

  private Path writePem(String fileName, String pem) throws IOException {
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, pem);
    return path;
  }
}
