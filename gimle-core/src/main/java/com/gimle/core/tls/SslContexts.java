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
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds an {@link SSLContext} for mutual TLS from a {@link TlsSettings}: this component's own
 * certificate and private key as the {@code KeyManager} side (what it presents to peers), and the
 * shared cluster CA as the sole {@code TrustManager} entry (what it accepts from peers). Pure
 * public JDK API only -- unlike {@code gimle-pki}'s certificate *generation*, *loading*
 * already-issued material needs no Bouncy Castle, per {@code
 * claudedocs/tls-transport-security-design.md} §3's own point that only issuance was ever the JDK's
 * gap.
 *
 * <p>Returns a context with both key and trust managers initialized; every caller (API server, Raft
 * peer RPC, fabric cross-machine) still owns the decision to set {@code needClientAuth}/ {@code
 * wantClientAuth} on its own server socket or {@code HttpsConfigurator}, since that's a
 * per-transport server-side setting, not something an {@code SSLContext} itself carries.
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

  /** Same certificate material and mTLS posture as {@link #forMutualTls}, for gossip's DTLS. */
  public static SSLContext forMutualDtls(TlsSettings settings) {
    return build(DTLS_PROTOCOL, settings);
  }

  /**
   * Trust-the-server-only, present-nothing-as-a-client -- for the two call sites in {@code
   * claudedocs/tls-transport-security-design.md} §4/§4a that genuinely have no certificate yet: an
   * agent's very first CSR submission, and {@code gimle cert request} for a brand-new human
   * operator. Both still need to verify *the control plane's* identity (it already has a leaf cert
   * signed by the cluster CA by the time either of these ever runs), just not present one of their
   * own.
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
      // The design's leaf certificates are always RSA-signed (SHA256withRSA, see gimle-pki's
      // CertificateAuthority), so the private key being loaded back is always an RSA key too.
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
    } catch (GeneralSecurityException e) {
      throw new IOException("not a valid PKCS#8 RSA private key: " + path, e);
    }
  }
}
