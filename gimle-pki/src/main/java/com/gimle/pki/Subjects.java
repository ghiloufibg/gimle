package com.gimle.pki;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;

/**
 * Small X.500 Subject utilities -- currently just the one operation {@code
 * ApiServer#handleBootstrapCsrSubmit} needs: replacing whatever {@code O=} a CSR's own Subject may
 * have requested with a server-computed value, while keeping its {@code CN=} untouched. See {@code
 * CertificateAuthority#signCertificateRequest(PKCS10CertificationRequest, X500Name, Duration)},
 * which is where the resulting {@link X500Name} actually gets signed into a certificate.
 */
public final class Subjects {

  private Subjects() {}

  /**
   * Rebuilds {@code original} as {@code O=<organization>,CN=<original's CN>} -- discarding any
   * {@code O=} (or other RDN) {@code original} itself carried. Requires {@code original} to have
   * exactly one {@code CN=} RDN, true of every Subject this codebase issues a CSR with ({@code
   * CertificateSigningRequests.generate} always builds a single-CN subject).
   */
  public static X500Name withOrganization(X500Name original, String organization) {
    RDN[] commonNames = original.getRDNs(BCStyle.CN);
    if (commonNames.length != 1) {
      throw new IllegalArgumentException(
          "expected exactly one CN= in subject, found " + commonNames.length + ": " + original);
    }
    X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
    builder.addRDN(BCStyle.O, organization);
    builder.addRDN(BCStyle.CN, commonNames[0].getFirst().getValue().toString());
    return builder.build();
  }
}
