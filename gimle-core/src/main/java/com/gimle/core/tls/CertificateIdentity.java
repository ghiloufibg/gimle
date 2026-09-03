package com.gimle.core.tls;

import com.gimle.core.authz.Principal;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

/**
 * Turns a verified peer certificate's Subject into the {@link Principal} every Gimlé process
 * authorizes against: {@code CN=} becomes the principal's name, every {@code O=} an entry in its
 * groups. Lives here, on public JDK APIs only ({@link X500Principal}'s RFC 2253 rendering parsed
 * back through {@link LdapName}), so a process with no Bouncy Castle on its classpath -- a worker
 * JVM above all, which never issues or signs anything -- can still read the identity a certificate
 * carries. {@code O=} is trustworthy here because it is stamped server-side at issuance, never
 * taken verbatim from a client's own CSR.
 */
public final class CertificateIdentity {

  private CertificateIdentity() {}

  public static Principal principalFrom(X509Certificate certificate) {
    return principalFrom(certificate.getSubjectX500Principal());
  }

  public static Principal principalFrom(X500Principal subject) {
    LdapName name;
    try {
      name = new LdapName(subject.getName(X500Principal.RFC2253));
    } catch (InvalidNameException e) {
      throw new IllegalStateException("certificate subject is not a parseable DN: " + subject, e);
    }
    String commonName = null;
    Set<String> groups = new LinkedHashSet<>();
    // RFC 2253 renders the most-specific RDN first while LdapName indexes from the root, so walking
    // the indexes downwards visits RDNs in the certificate's own encoding order -- the first CN=
    // encoded is the name, and multiple O= keep the order they were stamped in.
    for (int i = name.size() - 1; i >= 0; i--) {
      Rdn rdn = name.getRdn(i);
      String value = String.valueOf(rdn.getValue());
      if (rdn.getType().equalsIgnoreCase("CN")) {
        if (commonName == null) {
          commonName = value;
        }
      } else if (rdn.getType().equalsIgnoreCase("O")) {
        groups.add(value);
      }
    }
    if (commonName == null) {
      throw new IllegalStateException("certificate subject carries no CN=: " + subject);
    }
    return new Principal(commonName, groups);
  }
}
