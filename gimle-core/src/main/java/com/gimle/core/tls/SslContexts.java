package com.gimle.core.tls;

import com.gimle.core.exception.GimleTlsException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds an {@link SSLContext} for mutual TLS from a {@link TlsSettings}: this component's own
 * certificate and private key as the {@code KeyManager} side (what it presents to peers), and the
 * shared cluster CA as the sole {@code TrustManager} entry (what it accepts from peers). Pure
 * public JDK API only -- unlike {@code gimle-pki}'s certificate *generation*, *loading*
 * already-issued material needs no Bouncy Castle: the JDK's standard library has always been able
 * to parse existing certificates and PKCS#8 keys, the gap was only ever in generating new ones.
 *
 * <p>Returns a context with both key and trust managers initialized; every caller (API server, Raft
 * peer RPC, fabric cross-machine) still owns the decision to set {@code needClientAuth}/ {@code
 * wantClientAuth} on its own server socket or {@code HttpsConfigurator}, since that's a
 * per-transport server-side setting, not something an {@code SSLContext} itself carries.
 *
 * <p>{@link #forMutualTls(TlsSettings, java.util.List)} additionally serves a listener fronting
 * several virtual hosts on one port, choosing among per-hostname certificates from the client's SNI
 * extension instead of committing to one certificate at startup -- and returns a {@link
 * ReloadableSniContext} so a caller whose per-host bindings are config-driven (see {@code
 * gimle-gateway}'s {@code GatewayHooks}) can swap them in place later without rebuilding the {@code
 * SSLContext} or rebinding the listener.
 */
public final class SslContexts {

  private static final String TLS_PROTOCOL = "TLSv1.3";
  // DTLSv1.3 is not yet available in this JDK's default provider (confirmed empirically, not
  // assumed) -- DTLSv1.2 is the highest version actually supported, for gossip's DTLS transport.
  private static final String DTLS_PROTOCOL = "DTLSv1.2";
  private static final String KEY_STORE_TYPE = "PKCS12";
  private static final char[] IN_MEMORY_KEY_STORE_PASSWORD = "gimle-in-memory".toCharArray();
  private static final String PEM_KEY_BEGIN_MARKER = "-----BEGIN";
  private static final String PEM_KEY_END_MARKER = "-----END";

  private SslContexts() {}

  public static SSLContext forMutualTls(TlsSettings settings) {
    return build(TLS_PROTOCOL, settings);
  }

  /**
   * {@link #forMutualTls(TlsSettings)} for a listener terminating TLS for several virtual hosts on
   * one port: {@code settings} still supplies the trust anchor and the certificate presented by
   * default -- both fixed for the returned context's whole lifetime -- and each {@link
   * HostCertificate} additionally binds one hostname to its own certificate, selected per
   * connection from the client's SNI extension (see {@link SniKeyManager}). Always built on a
   * {@link SniKeyManager} -- even when {@code perHost} starts empty -- rather than delegating to
   * {@link #forMutualTls(TlsSettings)}'s plain {@code KeyManagerFactory} path for that case: an
   * empty starting set still needs to be able to gain bindings later via {@link
   * ReloadableSniContext#reloadHostCertificates}, and a {@link SniKeyManager} with no host bindings
   * at all already behaves identically to the single-certificate path (every handshake resolves the
   * one default alias).
   *
   * <p>Every host certificate must chain to the same cluster CA {@code settings} names -- there is
   * one trust anchor per cluster, and what varies per virtual host is only the identity presented.
   */
  public static ReloadableSniContext forMutualTls(
      TlsSettings settings, List<HostCertificate> perHost) {
    try {
      X509Certificate caCertificate = loadCertificate(settings.caFile());
      SniKeyManager.KeyEntry defaultEntry =
          loadKeyEntry(settings.certFile(), settings.keyFile(), caCertificate);
      SniKeyManager keyManager =
          new SniKeyManager(defaultEntry, loadHostCertificates(perHost, caCertificate));

      KeyStore trustStore = KeyStore.getInstance(KEY_STORE_TYPE);
      trustStore.load(null, null);
      trustStore.setCertificateEntry("cluster-ca", caCertificate);
      TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(trustStore);

      SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
      context.init(new KeyManager[] {keyManager}, trustManagerFactory.getTrustManagers(), null);
      return new ReloadableSniContext(context, keyManager, caCertificate);
    } catch (GeneralSecurityException | IOException e) {
      throw GimleTlsException.invalidMaterial(
          settings.certFile(), settings.keyFile(), settings.caFile(), e);
    }
  }

  private static SniKeyManager.KeyEntry loadKeyEntry(
      Path certFile, Path keyFile, X509Certificate caCertificate) throws IOException {
    return new SniKeyManager.KeyEntry(
        loadPrivateKey(keyFile), new X509Certificate[] {loadCertificate(certFile), caCertificate});
  }

  /**
   * Loads every {@link HostCertificate}'s key material eagerly (not lazily on first handshake), the
   * same "fail before serving traffic" posture {@link #build} already applies to the single-cert
   * path -- a malformed binding is rejected at parse/reload time with the specific file(s) at fault
   * named in the failure, not discovered mid-handshake against a real caller.
   */
  private static Map<String, SniKeyManager.KeyEntry> loadHostCertificates(
      List<HostCertificate> perHost, X509Certificate caCertificate) {
    Map<String, SniKeyManager.KeyEntry> byHostname = new LinkedHashMap<>();
    for (HostCertificate hostCertificate : perHost) {
      try {
        byHostname.put(
            hostCertificate.hostname(),
            loadKeyEntry(hostCertificate.certFile(), hostCertificate.keyFile(), caCertificate));
      } catch (IOException e) {
        throw GimleTlsException.invalidMaterial(
            hostCertificate.certFile(), hostCertificate.keyFile(), null, e);
      }
    }
    return byHostname;
  }

  /**
   * A TLS server context built by {@link #forMutualTls(TlsSettings, List)} together with the means
   * to replace its live per-hostname certificate bindings -- what lets a config-driven caller (see
   * {@code gimle-gateway}'s {@code GatewayHooks}) pick up a changed {@code gateway.tlsCertificates}
   * value on an already-running listener the same way it already picks up a changed {@code
   * gateway.routes} table, with no rebind: SNI selection happens fresh on every new handshake (see
   * {@link SniKeyManager}), so there is nothing about the already-bound socket that needs to
   * change. The cluster-wide default certificate this context was built with is fixed for its whole
   * lifetime -- only the per-hostname bindings are reloadable.
   */
  public static final class ReloadableSniContext {
    private final SSLContext sslContext;
    private final SniKeyManager keyManager;
    private final X509Certificate caCertificate;

    private ReloadableSniContext(
        SSLContext sslContext, SniKeyManager keyManager, X509Certificate caCertificate) {
      this.sslContext = sslContext;
      this.keyManager = keyManager;
      this.caCertificate = caCertificate;
    }

    public SSLContext sslContext() {
      return sslContext;
    }

    /**
     * Replaces the live per-hostname bindings selected by SNI with {@code perHost}, wholesale --
     * not merged with whatever was bound before, the same "new set replaces the old one entirely"
     * semantics {@code GatewayHooks}'s own route-table reload already uses. An already-established
     * connection is unaffected; the next new handshake sees the new set.
     */
    public void reloadHostCertificates(List<HostCertificate> perHost) {
      keyManager.updateHostBindings(loadHostCertificates(perHost, caCertificate));
    }
  }

  /**
   * {@link #forMutualTls} gated on the single cluster-wide {@link TransportProtocol#fromConfig()}
   * switch: empty (plaintext) when {@code gimle.transport.protocol} isn't {@code tls}, otherwise
   * built from {@link TlsSettings#fromConfig()} -- the same "https with full mTLS, or plain http"
   * decision every internal-process client (Fafnir, Muninn, Andvari) makes identically.
   */
  public static Optional<SSLContext> forMutualTlsFromConfig() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return Optional.empty();
    }
    return Optional.of(forMutualTls(TlsSettings.fromConfig()));
  }

  /** Same certificate material and mTLS posture as {@link #forMutualTls}, for gossip's DTLS. */
  public static SSLContext forMutualDtls(TlsSettings settings) {
    return build(DTLS_PROTOCOL, settings);
  }

  /**
   * Trust-the-server-only, present-nothing-as-a-client -- for the two call sites that genuinely
   * have no certificate yet: an agent's very first CSR submission, and {@code gimle cert request}
   * for a brand-new human operator. Both still need to verify *the control plane's* identity (it
   * already has a leaf cert signed by the cluster CA by the time either of these ever runs), just
   * not present one of their own.
   */
  public static SSLContext forServerTrustOnly(Path caFile) {
    try {
      X509Certificate caCertificate = loadCertificate(caFile);

      KeyStore trustStore = KeyStore.getInstance(KEY_STORE_TYPE);
      trustStore.load(null, null);
      trustStore.setCertificateEntry("cluster-ca", caCertificate);

      TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(trustStore);

      SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
      context.init(null, trustManagerFactory.getTrustManagers(), null);
      return context;
    } catch (GeneralSecurityException | IOException e) {
      throw GimleTlsException.invalidMaterial(null, null, caFile, e);
    }
  }

  private static SSLContext build(String protocol, TlsSettings settings) {
    try {
      X509Certificate ownCertificate = loadCertificate(settings.certFile());
      X509Certificate caCertificate = loadCertificate(settings.caFile());
      PrivateKey ownPrivateKey = loadPrivateKey(settings.keyFile());

      KeyStore keyStore = KeyStore.getInstance(KEY_STORE_TYPE);
      keyStore.load(null, null);
      keyStore.setKeyEntry(
          "self",
          ownPrivateKey,
          IN_MEMORY_KEY_STORE_PASSWORD,
          new X509Certificate[] {ownCertificate, caCertificate});

      KeyStore trustStore = KeyStore.getInstance(KEY_STORE_TYPE);
      trustStore.load(null, null);
      trustStore.setCertificateEntry("cluster-ca", caCertificate);

      KeyManagerFactory keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keyStore, IN_MEMORY_KEY_STORE_PASSWORD);

      TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(trustStore);

      SSLContext context = SSLContext.getInstance(protocol);
      context.init(
          keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
      return context;
    } catch (GeneralSecurityException | IOException e) {
      throw GimleTlsException.invalidMaterial(
          settings.certFile(), settings.keyFile(), settings.caFile(), e);
    }
  }

  private static X509Certificate loadCertificate(Path path) throws IOException {
    // CertificateFactory accepts PEM directly -- no manual base64 decoding needed for certs,
    // unlike the private key below, which has no equivalent standard-library PEM reader.
    try (InputStream in = Files.newInputStream(path)) {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      return (X509Certificate) factory.generateCertificate(in);
    } catch (GeneralSecurityException e) {
      throw new IOException("not a valid X.509 certificate: " + path, e);
    }
  }

  private static PrivateKey loadPrivateKey(Path path) throws IOException {
    String pem = Files.readString(path, StandardCharsets.US_ASCII);
    StringBuilder base64 = new StringBuilder();
    for (String line : pem.lines().toList()) {
      if (line.startsWith(PEM_KEY_BEGIN_MARKER) || line.startsWith(PEM_KEY_END_MARKER)) {
        continue;
      }
      base64.append(line.trim());
    }
    byte[] encoded = Base64.getDecoder().decode(base64.toString());
    try {
      // This project's leaf certificates are always RSA-signed (SHA256withRSA, see gimle-pki's
      // CertificateAuthority), so the private key being loaded back is always an RSA key too.
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
    } catch (GeneralSecurityException e) {
      throw new IOException("not a valid PKCS#8 RSA private key: " + path, e);
    }
  }
}
