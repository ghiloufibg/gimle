package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.Version;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.CertificateSigningRequests;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Proves {@code gimle.transport.protocol=tls} actually swaps {@link GatewayHooks} onto a real
 * {@code HttpsServer} terminating a genuine TLS handshake -- mirroring {@code
 * ApiServerTlsTest}/{@code MuninnServerTlsTest}'s own shape, the same real-CA-signed-certificate
 * pattern every other {@code com.sun.net.httpserver}-based TLS listener in this codebase is proven
 * with -- plus a companion test proving the plaintext path {@link GatewayHooks} always took is
 * completely untouched when TLS isn't configured (this test file's own back-compat obligation,
 * separate from every pre-existing {@link GatewayDispatcherTest}/{@link GatewayRouteConfigTest}
 * continuing to pass unchanged).
 *
 * <p>Also covers per-virtual-host certificate selection: which certificate a real handshake is
 * actually served depends on the SNI hostname the client asked for, with the cluster-wide
 * certificate answering both a client that sends no SNI and one naming a hostname with no binding.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class GatewayHooksTlsTest {

  private static final String PROTOCOL_PROPERTY = "gimle.transport.protocol";
  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  // Mirrors GatewayHooksRouteReloadTest's own reload-interval seam: shrinks the background reload
  // tick to milliseconds so a live gateway.tlsCertificates change is observed in test time.
  private static final Duration RELOAD_INTERVAL = Duration.ofMillis(20);
  private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(2);

  @TempDir(cleanup = CleanupMode.NEVER)
  private Path tempDir;

  private GatewayHooks hooks;

  private final List<StubIngressControlPlane> stubControlPlanes = new ArrayList<>();

  @AfterEach
  void stopGatewayAndClearTransportProperties() {
    if (hooks != null) {
      hooks.onStop(null);
    }
    stubControlPlanes.forEach(StubIngressControlPlane::close);
    stubControlPlanes.clear();
    System.clearProperty(PROTOCOL_PROPERTY);
    System.clearProperty(CERT_FILE_PROPERTY);
    System.clearProperty(KEY_FILE_PROPERTY);
    System.clearProperty(CA_FILE_PROPERTY);
  }

  @Test
  void https_request_with_a_valid_client_cert_reaches_the_dispatched_route() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings clientSettings = issueLeaf(ca, "caller");

    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithGreeterRoute());

    SSLContext clientContext = SslContexts.forMutualTls(clientSettings);
    HttpClient client = HttpClient.newBuilder().sslContext(clientContext).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://localhost:" + hooks.port() + "/greet"))
            .POST(HttpRequest.BodyPublishers.ofString("Freya"))
            .build();

    HttpResponse<String> response = sendOnceRouteIsLive(client, request);

    assertEquals(200, response.statusCode());
    assertEquals("hello, Freya", response.body());
  }

  @Test
  void each_sni_hostname_selects_its_own_certificate() throws Exception {
    // The gateway routes by the inbound Host header, so one instance legitimately fronts several
    // hostnames -- with a single certificate, every hostname outside its SAN fails the client's
    // own hostname verification before its (perfectly functional) route is ever consulted.
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings orders = issueLeaf(ca, "orders.example.com", List.of("orders.example.com"));
    TlsSettings shop = issueLeaf(ca, "shop.example.com", List.of("shop.example.com"));
    TlsSettings client = issueLeaf(ca, "caller", List.of());

    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(
        contextWithGreeterRoute(Map.of("gateway.tlsCertificates", bindings(orders, shop))));

    assertEquals(
        "CN=orders.example.com",
        presentedCertificateSubject(Optional.of("orders.example.com"), client));
    assertEquals(
        "CN=shop.example.com",
        presentedCertificateSubject(Optional.of("shop.example.com"), client));
  }

  @Test
  void a_client_sending_no_sni_gets_the_cluster_wide_default_certificate() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings orders = issueLeaf(ca, "orders.example.com", List.of("orders.example.com"));
    TlsSettings client = issueLeaf(ca, "caller", List.of());

    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithGreeterRoute(Map.of("gateway.tlsCertificates", bindings(orders))));

    assertEquals("CN=gimle-gateway", presentedCertificateSubject(Optional.empty(), client));
  }

  @Test
  void an_unknown_sni_hostname_falls_back_to_the_default_certificate() throws Exception {
    // Not a refused handshake: a host-unconstrained route still serves a hostname no certificate
    // binding names, so failing the connection closed would take that fallback routing down too.
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings orders = issueLeaf(ca, "orders.example.com", List.of("orders.example.com"));
    TlsSettings client = issueLeaf(ca, "caller", List.of());

    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithGreeterRoute(Map.of("gateway.tlsCertificates", bindings(orders))));

    assertEquals(
        "CN=gimle-gateway",
        presentedCertificateSubject(Optional.of("unbound.example.com"), client));
  }

  @Test
  void a_gateway_tlscertificates_update_is_picked_up_without_a_restart() throws Exception {
    // M55 regression: gateway.tlsCertificates used to be parsed exactly once at onStart, so a
    // config change reaching an already-running instance (the same live-delivery path
    // the route table already reloads through) had no effect at all -- every hostname kept getting
    // whichever certificate set (or lack of one) happened to be in place at boot.
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings orders = issueLeaf(ca, "orders.example.com", List.of("orders.example.com"));
    TlsSettings shop = issueLeaf(ca, "shop.example.com", List.of("shop.example.com"));
    TlsSettings client = issueLeaf(ca, "caller", List.of());

    ConcurrentHashMap<String, String> configValues = configWithTlsCertificates(bindings(orders));
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithLiveConfig(configValues));

    assertEquals(
        "CN=orders.example.com",
        presentedCertificateSubject(Optional.of("orders.example.com"), client));
    // shop.example.com isn't bound yet -- falls back to the cluster-wide default certificate.
    assertEquals(
        "CN=gimle-gateway", presentedCertificateSubject(Optional.of("shop.example.com"), client));

    configValues.put("gateway.tlsCertificates", bindings(orders, shop));

    awaitCertificateSubject(client, "shop.example.com", "CN=shop.example.com");
    // The pre-existing binding keeps working across the swap too.
    assertEquals(
        "CN=orders.example.com",
        presentedCertificateSubject(Optional.of("orders.example.com"), client));
  }

  @Test
  void a_malformed_tlscertificates_update_is_rejected_and_the_previous_bindings_keep_serving()
      throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings orders = issueLeaf(ca, "orders.example.com", List.of("orders.example.com"));
    TlsSettings client = issueLeaf(ca, "caller", List.of());

    ConcurrentHashMap<String, String> configValues = configWithTlsCertificates(bindings(orders));
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithLiveConfig(configValues));
    assertEquals(
        "CN=orders.example.com",
        presentedCertificateSubject(Optional.of("orders.example.com"), client));

    configValues.put("gateway.tlsCertificates", "not a valid binding line");
    // Give the reload task several ticks to (fail to) apply the bad config.
    Thread.sleep(RELOAD_INTERVAL.toMillis() * 10);

    assertEquals(
        "CN=orders.example.com",
        presentedCertificateSubject(Optional.of("orders.example.com"), client));
  }

  @Test
  void a_route_still_serves_normally_with_per_host_certificates_configured() throws Exception {
    CertificateAuthority ca =
        CertificateAuthority.generateSelfSignedCa(
            new X500Name("CN=test-cluster-ca"), Duration.ofDays(1));
    configureServerTls(ca);
    TlsSettings orders = issueLeaf(ca, "orders.example.com", List.of("orders.example.com"));
    TlsSettings clientSettings = issueLeaf(ca, "caller", List.of());

    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithGreeterRoute(Map.of("gateway.tlsCertificates", bindings(orders))));

    HttpClient client =
        HttpClient.newBuilder().sslContext(SslContexts.forMutualTls(clientSettings)).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://localhost:" + hooks.port() + "/greet"))
            .POST(HttpRequest.BodyPublishers.ofString("Frigg"))
            .build();

    HttpResponse<String> response = sendOnceRouteIsLive(client, request);

    assertEquals(200, response.statusCode());
    assertEquals("hello, Frigg", response.body());
  }

  @Test
  void plaintext_gateway_still_works_when_tls_is_not_configured() throws Exception {
    hooks = new GatewayHooks(RELOAD_INTERVAL);
    hooks.onStart(contextWithGreeterRoute());

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + hooks.port() + "/greet"))
            .POST(HttpRequest.BodyPublishers.ofString("Odin"))
            .build();

    HttpResponse<String> response = sendOnceRouteIsLive(client, request);

    assertEquals(200, response.statusCode());
    assertEquals("hello, Odin", response.body());
  }

  private SimpleModuleContext contextWithGreeterRoute() {
    return contextWithGreeterRoute(Map.of());
  }

  private SimpleModuleContext contextWithGreeterRoute(Map<String, String> extraConfig) {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleInstanceId gatewayId =
        ModuleInstanceId.unattached(new ModuleId("com.gimle.gateway", Version.parse("1.0.0")));
    registry.register(gatewayId, TestGreeter.class, name -> "hello, " + name);
    Map<String, String> config = new ConcurrentHashMap<>(extraConfig);
    config.put("gateway.port", "0");
    config.put("gateway.controlPlaneEndpoint", startStubControlPlane().endpoint());
    return new SimpleModuleContext(gatewayId, registry, new ConcurrentHashMap<>(config));
  }

  /**
   * A live, mutable config map -- unlike {@link #contextWithGreeterRoute(Map)}, which copies
   * whatever's handed to it twice over before it ever reaches {@link SimpleModuleContext}, this
   * hands the module context the exact same {@link ConcurrentHashMap} instance the caller keeps a
   * reference to, so a later {@code configValues.put(...)} is exactly what {@code ConfigRelay}'s
   * own delivery does to a real running instance's shared config map.
   */
  private ConcurrentHashMap<String, String> configWithTlsCertificates(
      String tlsCertificatesConfig) {
    ConcurrentHashMap<String, String> configValues = new ConcurrentHashMap<>();
    configValues.put("gateway.port", "0");
    configValues.put("gateway.controlPlaneEndpoint", startStubControlPlane().endpoint());
    configValues.put("gateway.tlsCertificates", tlsCertificatesConfig);
    return configValues;
  }

  /**
   * Sends {@code request}, retrying while the gateway still answers 404. A route reaches a gateway
   * on its first reload tick rather than at {@code onStart}, so a request sent the instant the
   * module starts can legitimately arrive before the route table does -- which is a race about test
   * timing, not about the certificate selection these tests are actually asserting.
   */
  private HttpResponse<String> sendOnceRouteIsLive(HttpClient client, HttpRequest request)
      throws Exception {
    long deadlineNanos = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
    HttpResponse<String> response;
    do {
      response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 404) {
        return response;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadlineNanos);
    return response;
  }

  /**
   * Routes reach a gateway only as declared Ingresses, so every test here needs a control plane to
   * read one from -- these tests are about certificate selection, and the single {@code /greet}
   * route is just something for a request to land on.
   */
  private StubIngressControlPlane startStubControlPlane() {
    StubIngressControlPlane controlPlane = new StubIngressControlPlane(List.of("/greet"));
    stubControlPlanes.add(controlPlane);
    return controlPlane;
  }

  private static SimpleModuleContext contextWithLiveConfig(
      ConcurrentHashMap<String, String> configValues) {
    SimpleServiceRegistry registry = new SimpleServiceRegistry();
    ModuleInstanceId gatewayId =
        ModuleInstanceId.unattached(new ModuleId("com.gimle.gateway", Version.parse("1.0.0")));
    registry.register(gatewayId, TestGreeter.class, name -> "hello, " + name);
    return new SimpleModuleContext(gatewayId, registry, configValues);
  }

  /**
   * Polls {@link #presentedCertificateSubject} until {@code sniHostname} resolves to {@code
   * expectedSubject} or {@link #AWAIT_TIMEOUT} elapses -- the certificate-selection analogue of
   * {@code GatewayHooksRouteReloadTest#awaitStatus}, needed for the same reason: the reload tick
   * that applies a config change runs on a background schedule, not synchronously with the test's
   * own {@code configValues.put(...)}.
   */
  private void awaitCertificateSubject(
      TlsSettings clientSettings, String sniHostname, String expectedSubject) throws Exception {
    long deadlineNanos = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
    String lastSubject = null;
    while (System.nanoTime() < deadlineNanos) {
      lastSubject = presentedCertificateSubject(Optional.of(sniHostname), clientSettings);
      if (expectedSubject.equals(lastSubject)) {
        return;
      }
      Thread.sleep(10);
    }
    fail(
        "expected SNI hostname "
            + sniHostname
            + " to resolve to certificate subject "
            + expectedSubject
            + " within "
            + AWAIT_TIMEOUT
            + ", last saw "
            + lastSubject);
  }

  /** A {@code gateway.tlsCertificates} value binding each leaf's own CN to its own key pair. */
  private static String bindings(TlsSettings... perHost) {
    StringBuilder text = new StringBuilder();
    for (TlsSettings settings : perHost) {
      String hostname = settings.certFile().getFileName().toString().replace("-cert.pem", "");
      text.append(hostname)
          .append(' ')
          .append(settings.certFile())
          .append(' ')
          .append(settings.keyFile())
          .append('\n');
    }
    return text.toString();
  }

  /**
   * Handshakes with the running gateway over a raw {@link SSLSocket} and reports the subject of the
   * certificate it presented. A raw socket rather than {@code HttpClient} on purpose: the SNI
   * hostname has to be set independently of the address actually dialled (only loopback resolves
   * here), and a socket obtained this way does no endpoint identification, so the assertion is
   * about which certificate was *selected*, not about hostname verification on top of it.
   */
  private String presentedCertificateSubject(
      Optional<String> sniHostname, TlsSettings clientSettings) throws Exception {
    SSLContext clientContext = SslContexts.forMutualTls(clientSettings);
    try (SSLSocket socket =
        (SSLSocket)
            clientContext
                .getSocketFactory()
                .createSocket(InetAddress.getLoopbackAddress(), hooks.port())) {
      socket.setSoTimeout(5_000);
      SSLParameters parameters = socket.getSSLParameters();
      // An empty server-name list means the extension is not sent at all -- the no-SNI case.
      parameters.setServerNames(
          sniHostname
              .<List<SNIServerName>>map(host -> List.of(new SNIHostName(host)))
              .orElse(List.of()));
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      X509Certificate presented = (X509Certificate) socket.getSession().getPeerCertificates()[0];
      return presented.getSubjectX500Principal().getName();
    }
  }

  private void configureServerTls(CertificateAuthority ca) throws Exception {
    // The client connects to "https://localhost:...", so the server's own leaf cert needs
    // "localhost" as a Subject Alternative Name -- real HTTPS clients do hostname verification
    // against SAN, not just the CA trust chain.
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=gimle-gateway"), List.of("localhost"));
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));
    Path certFile = writePem("gateway-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile = writePem("gateway-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem("gateway-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());
    System.setProperty(PROTOCOL_PROPERTY, "tls");
    System.setProperty(CERT_FILE_PROPERTY, certFile.toString());
    System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
    System.setProperty(CA_FILE_PROPERTY, caFile.toString());
  }

  private TlsSettings issueLeaf(CertificateAuthority ca, String commonName) throws Exception {
    return issueLeaf(ca, commonName, List.of());
  }

  private TlsSettings issueLeaf(
      CertificateAuthority ca, String commonName, List<String> subjectAlternativeNames)
      throws Exception {
    KeyPair keyPair = generateRsaKeyPair();
    PKCS10CertificationRequest csr =
        CertificateSigningRequests.generate(
            keyPair, new X500Name("CN=" + commonName), subjectAlternativeNames);
    X509Certificate leaf = ca.signCertificateRequest(csr, Duration.ofDays(1));

    Path certFile = writePem(commonName + "-cert.pem", "CERTIFICATE", leaf.getEncoded());
    Path keyFile =
        writePem(commonName + "-key.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    Path caFile = writePem(commonName + "-ca.pem", "CERTIFICATE", ca.certificate().getEncoded());

    return new TlsSettings(certFile, keyFile, caFile);
  }

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
    Path path = tempDir.resolve(fileName);
    Files.writeString(path, pem);
    return path;
  }

  private static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
