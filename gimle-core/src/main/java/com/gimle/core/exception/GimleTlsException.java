package com.gimle.core.exception;

import java.nio.file.Path;

/**
 * {@code gimle.transport.protocol=tls} was requested but the certificate material it depends on
 * (own certificate, own private key, or the shared cluster CA certificate) is missing or
 * unreadable. Thrown at process startup, before an {@code SSLContext} is ever built from it -- the
 * same "fail fast at load time, not at the first connection attempt" posture {@link
 * GimleSecretsException} already applies to the control plane's own secrets key file.
 */
public class GimleTlsException extends RuntimeException {

  private GimleTlsException(String message) {
    super(message);
  }

  private GimleTlsException(String message, Throwable cause) {
    super(message, cause);
  }

  public static GimleTlsException missingProperty(String property) {
    return new GimleTlsException(
        "gimle.transport.protocol=tls requires " + property + " to be set, but it is not");
  }

  public static GimleTlsException missingFile(String property, Path path) {
    return new GimleTlsException(property + " points at " + path + ", but no file exists there");
  }

  public static GimleTlsException unreadable(String property, Path path, Throwable cause) {
    return new GimleTlsException(
        property + "'s file at " + path + " could not be read as certificate material", cause);
  }

  public static GimleTlsException invalidMaterial(
      Path certFile, Path keyFile, Path caFile, Throwable cause) {
    return new GimleTlsException(
        "certificate material from certFile="
            + certFile
            + ", keyFile="
            + keyFile
            + ", caFile="
            + caFile
            + " could not be assembled into an SSLContext",
        cause);
  }
}
