package com.gimle.pki;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

/**
 * PEM encode/decode for the three artifact types that cross a wire or a file boundary in the CSR
 * issuance flow: a leaf certificate, a CSR, and a private key. Deliberately hand-rolled (base64
 * wrap/unwrap around a {@code BEGIN}/{@code END} marker pair) rather than pulling in Bouncy
 * Castle's own {@code PEMParser}/{@code JcaPEMWriter} -- the same manual approach {@code
 * com.gimle.core.tls.SslContexts} already uses for reading a private key back out of a PEM file,
 * now shared instead of the four test classes that each currently reimplement the encoding half of
 * this privately.
 */
public final class Pem {

  private static final String CERTIFICATE_LABEL = "CERTIFICATE";
  private static final String CSR_LABEL = "CERTIFICATE REQUEST";
  private static final String PRIVATE_KEY_LABEL = "PRIVATE KEY";
  private static final int LINE_LENGTH = 64;
  private static final Base64.Encoder BODY_ENCODER =
      Base64.getMimeEncoder(LINE_LENGTH, "\n".getBytes(StandardCharsets.US_ASCII));

  private Pem() {}

  public static String encodeCertificate(X509Certificate certificate) {
    try {
      return wrap(CERTIFICATE_LABEL, certificate.getEncoded());
    } catch (CertificateEncodingException e) {
      throw new IllegalStateException("failed to encode certificate", e);
    }
  }

  public static String encodeCsr(PKCS10CertificationRequest csr) {
    try {
      return wrap(CSR_LABEL, csr.getEncoded());
    } catch (IOException e) {
      throw new UncheckedIOException("failed to encode certificate signing request", e);
    }
  }

  public static String encodePrivateKey(PrivateKey privateKey) {
    return wrap(PRIVATE_KEY_LABEL, privateKey.getEncoded());
  }

  public static PKCS10CertificationRequest decodeCsr(String pem) {
    try {
      return new PKCS10CertificationRequest(unwrap(pem));
    } catch (IOException e) {
      throw new UncheckedIOException("not a valid PKCS#10 certificate signing request", e);
    }
  }

  public static X509Certificate decodeCertificate(String pem) {
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      return (X509Certificate)
          factory.generateCertificate(
              new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not a valid X.509 certificate", e);
    }
  }

  /** The design's leaf and CA certificates are always RSA-signed, so this only ever loads RSA. */
  public static PrivateKey decodePrivateKey(String pem) {
    try {
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(unwrap(pem)));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not a valid PKCS#8 RSA private key", e);
    }
  }

  private static String wrap(String label, byte[] derBytes) {
    return "-----BEGIN "
        + label
        + "-----\n"
        + BODY_ENCODER.encodeToString(derBytes)
        + "\n-----END "
        + label
        + "-----\n";
  }

  private static byte[] unwrap(String pem) {
    StringBuilder base64 = new StringBuilder();
    for (String line : pem.lines().toList()) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("-----")) {
        continue;
      }
      base64.append(trimmed);
    }
    return Base64.getDecoder().decode(base64.toString());
  }
}
