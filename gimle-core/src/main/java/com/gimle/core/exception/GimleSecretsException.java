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

  /**
   * {@code gimle-fafnir}'s own versioned write path lost the optimistic write-verify-retry race to
   * a concurrent writer on every bounded attempt -- possible only under sustained, unrealistic
   * contention on one key, since each retry only costs a harmless orphaned {@code key@N} entry,
   * never data loss.
   */
  public static GimleSecretsException writeContention(String tenantId, String key, int attempts) {
    return new GimleSecretsException(
        "could not write secret "
            + tenantId
            + "/"
            + key
            + " after "
            + attempts
            + " contended attempts");
  }

  /**
   * A vessel's {@code env:} block named a Fafnir secret key that either doesn't exist for its
   * tenant or was soft-deleted -- thrown at spawn time, before the process is ever started, rather
   * than starting it with a missing environment variable and leaving the failure to surface however
   * that program happens to react to it.
   */
  public static GimleSecretsException secretNotFound(String tenantId, String key) {
    return new GimleSecretsException(
        "no secret " + key + " found for tenant " + tenantId + " (or it has been deleted)");
  }

  /**
   * {@code gimle-fafnir}'s SecretMap bulk-write lease ({@code secretmap-write:tenantId:name})
   * stayed contended across every bounded retry attempt -- possible only under sustained,
   * unrealistic concurrent writers targeting the same SecretMap name.
   */
  public static GimleSecretsException secretMapWriteContention(
      String tenantId, String name, int attempts) {
    return new GimleSecretsException(
        "could not acquire the write lease for SecretMap "
            + tenantId
            + "/"
            + name
            + " after "
            + attempts
            + " contended attempts");
  }

  /**
   * A {@code key@meta} entry's stored JSON doesn't hold the shape {@code SecretStore} expects
   * (missing or non-numeric {@code latestVersion}) -- surfaced as this specific, named type rather
   * than a bare {@code NullPointerException}/{@code ClassCastException} so a caller iterating many
   * secrets (e.g. a tenant's whole listing) can catch it and skip just that one corrupted entry
   * instead of the corruption propagating as an opaque crash.
   */
  public static GimleSecretsException malformedMetaEntry(
      String tenantId, String key, Throwable cause) {
    return new GimleSecretsException(
        "secret metadata for " + tenantId + "/" + key + " is malformed", cause);
  }
}
