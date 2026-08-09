package com.gimle.core.exception;

import java.nio.file.Path;

/**
 * Secrets-handling failures with no more specific unchecked type of their own: the control plane's
 * secrets master key file exists but doesn't hold a usable AES key (wrong length -- corrupted,
 * truncated, or simply not a key file -- or empty; thrown at load time, before a bogus {@code
 * SecretKeySpec} ever reaches {@link javax.crypto.Cipher}, where the same corruption would
 * otherwise surface as a generic, path-less encryption failure far from its actual cause), or
 * {@code gimle-controlplane}'s {@code FafnirClient} being unable to reach Fafnir or getting back an
 * unexpected response for a crypto operation it needs to complete a request.
 */
public class GimleSecretsException extends RuntimeException {

  private GimleSecretsException(String message) {
    super(message);
  }

  private GimleSecretsException(String message, Throwable cause) {
    super(message, cause);
  }

  public static GimleSecretsException invalidKeyFile(Path path, int actualLength) {
    return new GimleSecretsException(
        "secrets key file "
            + path
            + " does not hold a valid AES key: expected 16, 24, or 32 bytes, found "
            + actualLength);
  }

  public static GimleSecretsException fafnirUnavailable(String message) {
    return new GimleSecretsException(message);
  }

  public static GimleSecretsException fafnirUnavailable(String message, Throwable cause) {
    return new GimleSecretsException(message, cause);
  }
}
