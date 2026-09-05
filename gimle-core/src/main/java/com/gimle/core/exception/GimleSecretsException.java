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

  /**
   * A one-time cluster bootstrap password was generated with nowhere safe to deliver it: the
   * process's standard output is not a terminal, so printing it would write the plaintext into
   * whatever log or file that output was redirected to, and no destination file was named either.
   */
  public static GimleSecretsException undeliverableBootstrapPassword(String message) {
    return new GimleSecretsException(message);
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
   * An {@code undelete} call named a version number with no corresponding {@code key@N} entry --
   * out of range (never written, or negative/zero), or once written but since wiped by a whole-key
   * {@code hardDelete}, whose data is genuinely, permanently gone. Distinct from {@link
   * #secretNotFound}, which covers the key itself having no {@code @meta} pointer at all; this
   * covers a real key naming a version it simply can't stand behind.
   */
  public static GimleSecretsException versionNotRecoverable(
      String tenantId, String key, int version) {
    return new GimleSecretsException(
        "secret "
            + tenantId
            + "/"
            + key
            + " version "
            + version
            + " no longer exists to undelete -- it was likely destroyed by a hard delete");
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

  /**
   * A {@code retire} call named the ring's own {@code activeKeyId} -- retiring the key currently
   * used for new writes would break all future encryption/sealing, so the caller must rotate to a
   * new active key first, then retire the one being stepped away from.
   */
  public static GimleSecretsException cannotRetireActiveKey(String keyKind, int keyId) {
    return new GimleSecretsException(
        "cannot retire "
            + keyKind
            + " key id "
            + keyId
            + ": it is the active key -- rotate to a new active key first");
  }

  /**
   * A {@code retire} call (or, for a sealing key, an {@code unseal} lookup) named a key id this
   * ring has never held or has already retired -- thrown rather than silently no-oping, since a
   * caller retiring a key expects to know whether anything actually happened.
   */
  public static GimleSecretsException unknownKeyId(String keyKind, int keyId) {
    return new GimleSecretsException("no " + keyKind + " key with id " + keyId);
  }

  /**
   * A {@code retire} call named key id 0 -- structurally special in {@code KeyFileManager}/{@code
   * SealingKeyFileManager}'s file layout, since {@code loadAllOrCreate} always regenerates a fresh
   * id-0 key if its file is absent (the "first run" case every other id relies on that behavior
   * never triggering for it). Deleting id 0's file would silently resurrect a *different* key under
   * the same id on the next load rather than actually closing it off, so retiring id 0 is rejected
   * outright rather than producing that surprising, non-obvious corruption.
   */
  public static GimleSecretsException cannotRetireBaseKey(String keyKind) {
    return new GimleSecretsException(
        "cannot retire "
            + keyKind
            + " key id 0: it is this file layout's base identity, always recreated if its file"
            + " is absent -- rotate away from it, but it can never be retired");
  }

  /**
   * A decrypt attempt named a key id this ring durably remembers retiring -- distinct from {@link
   * #unknownKeyId}, which covers an id this ring never held at all, this is the specific,
   * diagnosable case a caller can act on: the value simply can't be recovered any more, not that
   * something is malformed.
   */
  /**
   * Retirement destroys a key's material, so anything still encrypted under it would become
   * permanently unreadable. Refused while such data exists, naming how much, rather than silently
   * discarding it -- the caller re-encrypts under the active key first.
   */
  public static GimleSecretsException keyStillEncryptsData(
      String keyKind, int keyId, int encryptedCount) {
    return new GimleSecretsException(
        "cannot retire "
            + keyKind
            + " key id "
            + keyId
            + ": "
            + encryptedCount
            + (encryptedCount == 1 ? " stored value is" : " stored values are")
            + " still encrypted under it and would become permanently unreadable -- re-encrypt"
            + " them under the active key first (gimle secret rewrap)");
  }

  public static GimleSecretsException keyRetired(String keyKind, int keyId) {
    return new GimleSecretsException(
        keyKind + " key id " + keyId + " has been retired and can no longer decrypt data");
  }

  /**
   * A secret write declared a type ({@code com.gimle.fafnir.SecretType}) whose shape the plaintext
   * doesn't have -- a truncated PEM, a wrong encoding. Rejected at the write that caused it rather
   * than accepted, encrypted, replicated, and left to fail much later at module launch, where
   * nothing points back at the {@code secret set} call responsible.
   */
  public static GimleSecretsException malformedSecretValue(
      String tenantId, String key, String type, String problem) {
    return new GimleSecretsException(
        "secret " + tenantId + "/" + key + " is not a valid " + type + ": " + problem);
  }

  /**
   * A secret's plaintext exceeded the per-value ceiling. Refused outright rather than encrypted and
   * replicated through Raft consensus like any small entry -- every store replica would hold it in
   * memory and write it into every snapshot.
   */
  public static GimleSecretsException valueTooLarge(
      String tenantId, String key, int actualBytes, int maxBytes) {
    return new GimleSecretsException(
        "secret "
            + tenantId
            + "/"
            + key
            + " is "
            + actualBytes
            + " bytes, exceeding the maximum of "
            + maxBytes);
  }

  /**
   * A write against {@code tenantId} was attempted, but no {@link com.gimle.core.tenant.Tenant}
   * with that id was ever registered -- unlike a read, which correctly answers empty/404 for a
   * bogus tenant, a write has no such natural "not found" outcome, so it must be rejected outright
   * rather than silently persisting data under a tenant id nothing else in the system recognizes.
   */
  public static GimleSecretsException unknownTenant(String tenantId) {
    return new GimleSecretsException(
        "tenant "
            + tenantId
            + " does not exist -- create it with 'gimle tenant set' before writing secrets under"
            + " it");
  }
}
