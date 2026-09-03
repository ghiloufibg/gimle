package com.gimle.pki;

import com.gimle.core.authz.Principal;
import com.gimle.core.tls.CertificateIdentity;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;

/**
 * Small X.500 Subject utilities -- currently just the two operations {@code
 * ApiServer#handleBootstrapCsrSubmit} (and now {@code FafnirServer}) need: replacing whatever
 * {@code O=} a CSR's own Subject may have requested with server-computed values while keeping its
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
    return withOrganizations(original, List.of(organization));
  }

  /**
   * The several-groups form of {@link #withOrganization}: one {@code O=} RDN per entry of {@code
   * organizations}, in that order, ahead of the original's single {@code CN=}. What a worker
   * certificate needs -- {@code gimle:workers} beside the tenant-membership group naming the one
   * tenant the worker hosts -- and what {@link CertificateIdentity#principalFrom} reads back as
   * that principal's groups, in the same order.
   */
  public static X500Name withOrganizations(X500Name original, List<String> organizations) {
    RDN[] commonNames = original.getRDNs(BCStyle.CN);
    if (commonNames.length != 1) {
      throw new IllegalArgumentException(
          "expected exactly one CN= in subject, found " + commonNames.length + ": " + original);
    }
    if (organizations.isEmpty()) {
      throw new IllegalArgumentException("at least one organization is required");
    }
    X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
    for (String organization : organizations) {
      builder.addRDN(BCStyle.O, organization);
    }
    builder.addRDN(BCStyle.CN, commonNames[0].getFirst().getValue().toString());
    return builder.build();
  }

  /**
   * The single {@code CN=} of a CSR's own requested subject, or empty when it has none or several
   * -- what an issuance path checks a requested name against before deciding to sign it at all.
   */
  public static Optional<String> commonNameOf(X500Name subject) {
    RDN[] commonNames = subject.getRDNs(BCStyle.CN);
    if (commonNames.length != 1) {
      return Optional.empty();
    }
    return Optional.of(commonNames[0].getFirst().getValue().toString());
  }

  /**
   * {@code CN=} becomes the principal's name, every {@code O=} an entry in its groups -- the one
   * implementation lives in {@code gimle-core}'s {@link CertificateIdentity} on public JDK APIs, so
   * a process with no Bouncy Castle on its classpath derives the identical {@link Principal}; kept
   * here as the entry point every signing-side caller already uses. {@code O=} is trustworthy here
   * because it is stamped server-side at issuance ({@code ApiServer#handleBootstrapCsrSubmit}),
   * never taken verbatim from a client's own CSR.
   */
  public static Principal principalFrom(X509Certificate certificate) {
    return CertificateIdentity.principalFrom(certificate);
  }
}
