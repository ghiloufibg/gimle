package com.gimle.pki;

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

/**
 * A loaded (or freshly generated) certificate authority: its own certificate plus the private key
 * needed to sign other certificates with it. Per {@code
 * claudedocs/tls-transport-security-design.md} §3/§4/§4a/§4b, {@link #signCertificateRequest} is
 * the single signing code path shared by initial cluster bootstrap, a node joining, a newly
 * approved human operator, and certificate rotation -- those four cases are differentiated entirely
 * by who is allowed to call it and under what authentication, never by different signing code.
 */
public final class CertificateAuthority {

  private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
  private static final int KEY_SIZE_BITS = 2048;

  private final X509Certificate certificate;
  private final PrivateKey privateKey;

  private CertificateAuthority(X509Certificate certificate, PrivateKey privateKey) {
    this.certificate = certificate;
    this.privateKey = privateKey;
  }

  /**
   * Wraps an already-issued CA certificate and its private key -- e.g. loaded from {@code
   * gimle.tls.*} files at control-plane startup, rather than generated fresh.
   */
  public static CertificateAuthority of(X509Certificate certificate, PrivateKey privateKey) {
    return new CertificateAuthority(certificate, privateKey);
  }

  public X509Certificate certificate() {
    return certificate;
  }

  /**
   * The CA's own private key -- needed only by {@code PkiBootstrapMain} to persist it to {@code
   * ca.key} at cluster bootstrap, and by whatever later reloads it via {@link #of}. Every other
   * caller only ever needs {@link #certificate()} or {@link #signCertificateRequest}.
   */
  public PrivateKey privateKey() {
    return privateKey;
  }

  /**
   * Generates a fresh, self-signed cluster CA: {@code subject} is both issuer and subject, with
   * {@code BasicConstraints: CA=true} and {@code KeyUsage: keyCertSign, cRLSign} (both critical) --
   * the extensions that make a real, spec-compliant validator recognize this as an issuing
   * authority, not just another leaf certificate.
   */
  public static CertificateAuthority generateSelfSignedCa(X500Name subject, Duration validity) {
    try {
      KeyPair keyPair = generateKeyPair();
      Instant now = Instant.now();

      X509v3CertificateBuilder builder =
          new JcaX509v3CertificateBuilder(
              subject,
              randomSerial(),
              Date.from(now),
              Date.from(now.plus(validity)),
              subject,
              keyPair.getPublic());
      builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
      builder.addExtension(
          Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

      ContentSigner signer =
          new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.getPrivate());
      X509CertificateHolder holder = builder.build(signer);
      X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);
      return new CertificateAuthority(certificate, keyPair.getPrivate());
    } catch (OperatorCreationException | CertIOException | CertificateException e) {
      throw new IllegalStateException("failed to generate self-signed CA", e);
    }
  }

  /**
   * Signs {@code request} as a leaf certificate under this CA. {@code KeyUsage: digitalSignature}
   * (critical) and {@code ExtendedKeyUsage: serverAuth, clientAuth} -- every Gimlé component does
   * mTLS both as client and server, so one leaf cert covers both roles rather than splitting them
   * the way Kubernetes' {@code kubelet-serving} vs. client certs do (a deliberate simplification,
   * see the design doc's own note on it).
   *
   * <p>Verifies the CSR's own signature before trusting the public key it carries -- proof the
   * requester actually holds the matching private key, not just an unverified assertion.
   */
  public X509Certificate signCertificateRequest(
      PKCS10CertificationRequest request, Duration validity) {
    return signCertificateRequest(request, request.getSubject(), validity);
  }

  /**
   * Signs {@code request} exactly as the two-argument overload does, except the issued
   * certificate's Subject is {@code subjectOverride} rather than whatever {@code request} itself
   * asked for. The CSR's self-signature is still validated against its own original subject/public
   * key pair (proof the requester holds the matching private key) -- only the *Subject written into
   * the resulting certificate* is substituted, never the signature check. This is what lets a
   * caller (see {@code ApiServer#handleBootstrapCsrSubmit}) stamp an {@code O=} organization/group
   * server-side without trusting whatever {@code O=} the CSR itself may have requested -- a client
   * CSR could otherwise self-declare a privileged group and have it signed verbatim.
   */
  public X509Certificate signCertificateRequest(
      PKCS10CertificationRequest request, X500Name subjectOverride, Duration validity) {
    try {
      JcaPKCS10CertificationRequest jcaRequest = new JcaPKCS10CertificationRequest(request);
      PublicKey requestedPublicKey = jcaRequest.getPublicKey();
      ContentVerifierProvider verifierProvider =
          new JcaContentVerifierProviderBuilder().build(requestedPublicKey);
      if (!request.isSignatureValid(verifierProvider)) {
        throw new IllegalArgumentException(
            "certificate signing request signature does not match its own public key");
      }

      Instant now = Instant.now();
      X500Name issuer = new X500Name(certificate.getSubjectX500Principal().getName());
      X509v3CertificateBuilder builder =
          new JcaX509v3CertificateBuilder(
              issuer,
              randomSerial(),
              Date.from(now),
              Date.from(now.plus(validity)),
              subjectOverride,
              requestedPublicKey);
      builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
      Optional<Extension> requestedSan = requestedSubjectAlternativeName(request);
      if (requestedSan.isPresent()) {
        builder.addExtension(requestedSan.get());
      }

      ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(privateKey);
      X509CertificateHolder holder = builder.build(signer);
      return new JcaX509CertificateConverter().getCertificate(holder);
    } catch (OperatorCreationException
        | CertIOException
        | CertificateException
        | PKCSException
        | NoSuchAlgorithmException
        | InvalidKeyException e) {
      throw new IllegalStateException("failed to sign certificate request", e);
    }
  }

  /**
   * A CSR built via {@link CertificateSigningRequests#generate(KeyPair, X500Name, java.util.List)}
   * carries its requested {@code subjectAltName} inside the standard PKCS#10 {@code
   * extensionRequest} attribute -- absent for a CSR built via the no-SAN overload, or for any
   * external CSR that never requested one, both legitimate cases this returns empty for rather than
   * failing.
   */
  private static Optional<Extension> requestedSubjectAlternativeName(
      PKCS10CertificationRequest request) {
    Attribute[] extensionAttributes =
        request.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest);
    if (extensionAttributes.length == 0) {
      return Optional.empty();
    }
    Extensions requestedExtensions =
        Extensions.getInstance(extensionAttributes[0].getAttributeValues()[0]);
    return Optional.ofNullable(requestedExtensions.getExtension(Extension.subjectAlternativeName));
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(KEY_SIZE_BITS);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key pair generation unavailable", e);
    }
  }

  private static BigInteger randomSerial() {
    return new BigInteger(159, new SecureRandom());
  }
}
