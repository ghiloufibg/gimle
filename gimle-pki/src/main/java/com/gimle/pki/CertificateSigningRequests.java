package com.gimle.pki;

import java.io.IOException;
import java.security.KeyPair;
import java.util.List;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/**
 * Builds a PKCS#10 certificate signing request for an already-generated key pair -- the agent-side
 * (or human-operator-side) half of requesting a leaf certificate from the cluster CA. Deliberately
 * takes the {@link KeyPair} as a parameter rather than generating one itself: key generation is a
 * single {@code KeyPairGenerator} call, fully public JDK API a caller can do without this class at
 * all -- this class's only job is the CSR structure itself.
 */
public final class CertificateSigningRequests {

  private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

  private CertificateSigningRequests() {}

  /** A CSR carrying no requested Subject Alternative Names -- see the overload that takes some. */
  public static PKCS10CertificationRequest generate(KeyPair keyPair, X500Name subject) {
    return generate(keyPair, subject, List.of());
  }

  /**
   * {@code dnsNames} become a requested {@code subjectAltName} extension (the standard PKCS#10
   * {@code extensionRequest} attribute, {@code pkcs_9_at_extensionRequest}), which {@link
   * CertificateAuthority#signCertificateRequest} copies into the issued leaf certificate. Without
   * this, real HTTPS clients that perform hostname verification -- the JDK's own {@code HttpClient}
   * included, not just browsers -- reject the connection outright with {@code CertificateException:
   * No name matching <host> found}, even though the handshake and CA trust chain are otherwise
   * entirely valid; a CN alone (what {@link #generate(KeyPair, X500Name)} alone produces) has not
   * satisfied hostname verification since RFC 6125 deprecated it in favor of SAN.
   */
  public static PKCS10CertificationRequest generate(
      KeyPair keyPair, X500Name subject, List<String> dnsNames) {
    try {
      JcaPKCS10CertificationRequestBuilder builder =
          new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
      if (!dnsNames.isEmpty()) {
        builder.addAttribute(
            PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensionRequest(dnsNames));
      }
      ContentSigner signer =
          new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.getPrivate());
      return builder.build(signer);
    } catch (OperatorCreationException e) {
      throw new IllegalStateException("failed to build certificate signing request", e);
    }
  }

  private static Extensions extensionRequest(List<String> dnsNames) {
    try {
      GeneralName[] names =
          dnsNames.stream()
              .map(name -> new GeneralName(GeneralName.dNSName, name))
              .toArray(GeneralName[]::new);
      ExtensionsGenerator generator = new ExtensionsGenerator();
      generator.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
      return generator.generate();
    } catch (IOException e) {
      throw new IllegalStateException("failed to build subjectAltName extension request", e);
    }
  }
}
