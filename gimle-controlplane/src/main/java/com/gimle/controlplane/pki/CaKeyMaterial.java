package com.gimle.controlplane.pki;

import com.gimle.pki.CertificateAuthority;
import com.gimle.pki.Pem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads the cluster CA's own private key -- distinct from {@code gimle.tls.keyFile}, which is this
 * control-plane node's own *leaf* key -- so this node can sign incoming CSRs at {@code
 * /bootstrap/csr}, per {@code claudedocs/tls-transport-security-design.md} §4. Read from a new,
 * control-plane-only system property, {@code gimle.pki.caKeyFile}; never provisioned to an agent or
 * operator, only to whichever control-plane host(s) hold cluster-signing authority.
 *
 * <p>Deliberately optional: a control-plane node started without this property configured simply
 * can't sign CSRs -- {@code /bootstrap/csr} and its sibling contexts aren't registered at all, same
 * "opt-in, no constructor requires it" shape as {@code ApiServer#serveConsole}.
 */
public final class CaKeyMaterial {

  private static final String CA_KEY_FILE_PROPERTY = "gimle.pki.caKeyFile";

  private CaKeyMaterial() {}

  /** {@code caCertFile} is the already-trusted {@code gimle.tls.caFile} this node already loads. */
  public static Optional<CertificateAuthority> loadIfConfigured(Path caCertFile) {
    String caKeyFileProperty = System.getProperty(CA_KEY_FILE_PROPERTY);
    if (caKeyFileProperty == null || caKeyFileProperty.isBlank()) {
      return Optional.empty();
    }
    Path caKeyFile = Path.of(caKeyFileProperty);
    if (!Files.isRegularFile(caKeyFile)) {
      throw new IllegalStateException(
          CA_KEY_FILE_PROPERTY + " points at " + caKeyFile + ", but no file exists there");
    }
    try {
      String caCertPem = Files.readString(caCertFile, StandardCharsets.US_ASCII);
      String caKeyPem = Files.readString(caKeyFile, StandardCharsets.US_ASCII);
      return Optional.of(
          CertificateAuthority.of(
              Pem.decodeCertificate(caCertPem), Pem.decodePrivateKey(caKeyPem)));
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed to read CA certificate/key material for " + CA_KEY_FILE_PROPERTY, e);
    }
  }
}
