package com.gimle.core.tls;

import com.gimle.core.exception.GimleTlsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * One hostname bound to its own certificate and private key (both PEM), for a listener that
 * terminates TLS for more than one virtual host on a single port and must therefore pick a
 * certificate per connection from the client's SNI extension rather than once at startup.
 *
 * <p>No CA file of its own: a component's trust anchor is cluster-wide and already carried by the
 * {@link TlsSettings} these bindings sit alongside -- what varies per virtual host is only which
 * identity the listener presents, never which peers it accepts.
 *
 * <p>The hostname is normalized to lower case, matching the case-insensitive comparison a TLS
 * server name gets.
 */
public record HostCertificate(String hostname, Path certFile, Path keyFile) {

  public HostCertificate {
    if (hostname == null || hostname.isBlank()) {
      throw new IllegalArgumentException("a host certificate needs a non-blank hostname");
    }
    hostname = hostname.strip().toLowerCase(Locale.ROOT);
    requireExistingFile("the certificate file for host '" + hostname + "'", certFile);
    requireExistingFile("the private key file for host '" + hostname + "'", keyFile);
  }

  private static void requireExistingFile(String description, Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      throw GimleTlsException.missingFile(description, path);
    }
  }
}
