package com.gimle.agent.bifrost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.agent.networkpolicy.NetworkPolicySnapshot;
import com.gimle.agent.networkpolicy.NetworkPolicySource;
import com.gimle.core.tenant.NetworkPolicyRule;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import com.gimle.pki.Pem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
 * {@link ServiceListener#forward} used to snapshot the applicable {@code NetworkPolicyRule}s once,
 * at connection-accept time -- once its two byte-pump threads started, a policy change during an
 * already-open, long-lived connection (chunked HTTP, a WebSocket, a gRPC stream) never reached it,
 * even though {@link BifrostProxy} itself refreshed the rule set every poll tick. These tests drive
 * a real TLS-terminating listener against a backend that streams continuously (never closing on its
 * own), so a connection that outlives a policy change is directly observable: bytes keep flowing
 * across a {@code pollOnce()} that leaves the policy unchanged, and stop within that same call once
 * a policy change revokes the caller's permission.
 */
class BifrostLiveConnectionPolicyTest {

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
  void removing_a_callers_tenant_from_the_allow_list_closes_its_already_open_connection()
      throws Exception {
    ServiceEndpoint backend = startStreamingBackend("A");
    source.put("orders", Optional.of("acme"), Set.of("orders-service"), 9701, List.of(backend));
    MutableNetworkPolicySource policies = new MutableNetworkPolicySource();
    policies.set(List.of(new NetworkPolicyRule("allow-partner", "acme", Set.of("partner-tenant"))));
    proxy =
        new BifrostProxy(
            source,
            policies,
            new BifrostSettings(
                Duration.ofMinutes(5),
                false,
                Optional.empty(),
                Optional.of(contextFor("O=gimle-bifrost-server,CN=node-a"))));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    SSLContext caller = contextFor("O=gimle:tenant:partner-tenant,CN=partner-caller");
    try (SSLSocket socket = (SSLSocket) caller.getSocketFactory().createSocket()) {
      socket.connect(clusterAddress, 2000);
      socket.setSoTimeout(3000);
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      assertEquals("A", reader.readLine(), "bytes must flow while the policy still permits it");

      // The tenant that was permitted a moment ago is now removed from the allow list entirely --
      // the connection it already opened must not be allowed to keep streaming.
      policies.set(List.of(new NetworkPolicyRule("allow-partner", "acme", Set.of())));
      proxy.pollOnce();

      assertConnectionClosed(reader);
    }
  }

  @Test
  @Timeout(15)
  void
      a_brand_new_deny_policy_closes_an_already_open_connection_to_a_previously_unrestricted_service()
          throws Exception {
    ServiceEndpoint backend = startStreamingBackend("B");
    source.put("payments", Optional.of("acme"), Set.of("payments-service"), 9702, List.of(backend));
    MutableNetworkPolicySource policies = new MutableNetworkPolicySource();
    policies.set(List.of()); // unrestricted at first
    proxy =
        new BifrostProxy(
            source,
            policies,
            new BifrostSettings(
                Duration.ofMinutes(5),
                false,
                Optional.empty(),
                Optional.of(contextFor("O=gimle-bifrost-server,CN=node-a"))));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("payments").orElseThrow();

    SSLContext caller = contextFor("O=gimle:tenant:mallory,CN=mallory-caller");
    try (SSLSocket socket = (SSLSocket) caller.getSocketFactory().createSocket()) {
      socket.connect(clusterAddress, 2000);
      socket.setSoTimeout(3000);
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      assertEquals(
          "B", reader.readLine(), "bytes must flow before any policy restricts the tenant");

      // A NetworkPolicySpec is created for this tenant mid-stream, where none applied before.
      policies.set(List.of(new NetworkPolicyRule("deny-by-default", "acme", Set.of())));
      proxy.pollOnce();

      assertConnectionClosed(reader);
    }
  }

  @Test
  @Timeout(15)
  void an_open_connection_is_never_closed_across_poll_ticks_that_leave_the_policy_unchanged()
      throws Exception {
    ServiceEndpoint backend = startStreamingBackend("C");
    source.put("orders", Optional.of("acme"), Set.of("orders-service"), 9703, List.of(backend));
    MutableNetworkPolicySource policies = new MutableNetworkPolicySource();
    policies.set(List.of(new NetworkPolicyRule("allow-partner", "acme", Set.of("partner-tenant"))));
    proxy =
        new BifrostProxy(
            source,
            policies,
            new BifrostSettings(
                Duration.ofMinutes(5),
                false,
                Optional.empty(),
                Optional.of(contextFor("O=gimle-bifrost-server,CN=node-a"))));
    proxy.pollOnce();
    InetSocketAddress clusterAddress = proxy.boundAddressFor("orders").orElseThrow();

    SSLContext caller = contextFor("O=gimle:tenant:partner-tenant,CN=partner-caller");
    try (SSLSocket socket = (SSLSocket) caller.getSocketFactory().createSocket()) {
      socket.connect(clusterAddress, 2000);
      socket.setSoTimeout(3000);
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

      assertEquals("C", reader.readLine());
      proxy.pollOnce(); // re-applies the identical, still-permitting rule set
      assertEquals("C", reader.readLine());
      proxy.pollOnce();
      assertEquals("C", reader.readLine());
    }
  }

  /**
   * Proof the connection no longer carries traffic: a clean TLS peer close surfaces as EOF ({@code
   * readLine()} returning {@code null}), but a socket the far end tore down with a bare {@code
   * close()} rather than a graceful {@code close_notify} can instead surface as an {@link
   * IOException} on some JDKs -- both shapes prove the same thing, so either is accepted.
   *
   * <p>The streaming backend keeps writing a new line every 20ms independently of when {@code
   * enforceCurrentPolicy} actually closes the connection, so one or more lines already pumped
   * through and sitting in the client's own socket/reader buffers before that close can still be
   * read afterward -- that's buffering, not the policy failing to apply. This drains lines
   * (bounded, so a genuine failure to close still fails the test rather than looping forever) until
   * the connection actually goes away.
   */
  private static void assertConnectionClosed(BufferedReader reader) throws IOException {
    try {
      for (int i = 0; i < 200; i++) {
        if (reader.readLine() == null) {
          return;
        }
      }
      fail("connection should have been closed once the policy stopped permitting it");
    } catch (IOException expectedResetOrClosed) {
      // Also proves the connection is gone.
    }
  }

  /**
   * Accepts exactly one connection and streams {@code tag} followed by a newline every 20ms
   * forever, until the proxy or the test closes it -- unlike {@code startTaggedBackend} elsewhere
   * in this package, which writes once and closes, this simulates a long-lived stream (chunked
   * HTTP, a WebSocket, a gRPC call) that a policy change must be able to interrupt mid-flight.
   */
  private ServiceEndpoint startStreamingBackend(String tag) throws IOException {
    ServerSocket serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
    backends.add(serverSocket);
    Thread.ofVirtual()
        .name("bifrost-streaming-backend-" + tag)
        .start(
            () -> {
              try (Socket connection = serverSocket.accept()) {
                OutputStream out = connection.getOutputStream();
                while (!connection.isClosed()) {
                  out.write((tag + "\n").getBytes(StandardCharsets.UTF_8));
                  out.flush();
                  Thread.sleep(20);
                }
              } catch (IOException | InterruptedException e) {
                // The proxy (or the test) closed the connection -- nothing further to stream.
              }
            });
    return new ServiceEndpoint("127.0.0.1", serverSocket.getLocalPort());
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

  /** A mutable {@code NetworkPolicySource} fake, matching the one in {@code BifrostProxyTest}. */
  private static final class MutableNetworkPolicySource implements NetworkPolicySource {
    private volatile List<NetworkPolicyRule> rules = List.of();
    private volatile Set<String> denyByDefaultTenantIds = Set.of();

    void set(List<NetworkPolicyRule> newRules) {
      this.rules = newRules;
    }

    void setDenyByDefaultTenantIds(Set<String> newTenantIds) {
      this.denyByDefaultTenantIds = newTenantIds;
    }

    @Override
    public NetworkPolicySnapshot fetchPolicies() {
      return new NetworkPolicySnapshot(rules, denyByDefaultTenantIds);
    }
  }
}
