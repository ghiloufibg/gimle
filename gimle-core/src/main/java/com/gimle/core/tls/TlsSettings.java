package com.gimle.core.tls;

import com.gimle.core.exception.GimleTlsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This component's own certificate material, per {@code
 * claudedocs/tls-transport-security-design.md} §1: its own leaf certificate and private key (both
 * PEM), plus the shared cluster CA certificate (PEM) used to verify peers. Every network-exposed
 * transport does mTLS, so a component always needs all three, never just a subset.
 */
public record TlsSettings(Path certFile, Path keyFile, Path caFile) {

  private static final String CERT_FILE_PROPERTY = "gimle.tls.certFile";
  private static final String KEY_FILE_PROPERTY = "gimle.tls.keyFile";
  private static final String CA_FILE_PROPERTY = "gimle.tls.caFile";

  public TlsSettings {
    requireExistingFile(CERT_FILE_PROPERTY, certFile);
    requireExistingFile(KEY_FILE_PROPERTY, keyFile);
    requireExistingFile(CA_FILE_PROPERTY, caFile);
  }

  /**
   * Reads {@code gimle.tls.certFile}/{@code keyFile}/{@code caFile} from system properties, failing
   * fast with {@link GimleTlsException} if any is unset or points at a nonexistent file -- called
   * only once {@link TransportProtocol#fromConfig()} has already resolved to {@link
   * TransportProtocol#TLS}, so all three are genuinely required at this point, not optional.
   */
  public static TlsSettings fromConfig() {
    return new TlsSettings(
        requiredPath(CERT_FILE_PROPERTY),
        requiredPath(KEY_FILE_PROPERTY),
        requiredPath(CA_FILE_PROPERTY));
  }

  private static Path requiredPath(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw GimleTlsException.missingProperty(property);
    }
    return Path.of(value);
  }

  private static void requireExistingFile(String property, Path path) {
    if (path == null) {
      throw GimleTlsException.missingProperty(property);
    }
    if (!Files.isRegularFile(path)) {
      throw GimleTlsException.missingFile(property, path);
    }
  }
}
