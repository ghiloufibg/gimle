package com.gimle.pki;

import com.gimle.core.authz.Principal;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;

/**
 * Small X.500 Subject utilities -- currently just the two operations {@code
 * ApiServer#handleBootstrapCsrSubmit} (and now {@code FafnirServer}) need: replacing whatever
 * {@code O=} a CSR's own Subject may have requested with a server-computed value while keeping its
 * {@code CN=} untouched, and going the other direction -- turning an already-signed certificate's
 * Subject back into a {@link Principal}. See {@code
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

  /**
   * {@code CN=} becomes the principal's name, every {@code O=} an entry in its groups -- moved here
   * (originally a private method on {@code ApiServer}) so {@code gimle-fafnir}'s own independent
   * authorization check (design doc §9's corrected defense-in-depth) can derive the identical
   * {@link Principal} from a peer certificate without duplicating the RDN-walking logic. {@code O=}
   * is trustworthy here because it is stamped server-side at issuance ({@code
   * ApiServer#handleBootstrapCsrSubmit}), never taken verbatim from a client's own CSR.
   */
  public static Principal principalFrom(X509Certificate certificate) {
    X500Name subject = new X500Name(certificate.getSubjectX500Principal().getName());
    RDN[] commonNames = subject.getRDNs(BCStyle.CN);
    if (commonNames.length == 0) {
      throw new IllegalStateException("certificate subject carries no CN=: " + subject);
    }
    Set<String> groups = new LinkedHashSet<>();
    for (RDN rdn : subject.getRDNs(BCStyle.O)) {
      groups.add(rdn.getFirst().getValue().toString());
    }
    return new Principal(commonNames[0].getFirst().getValue().toString(), groups);
  }
}
