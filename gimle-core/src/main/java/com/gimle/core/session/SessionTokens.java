package com.gimle.core.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless, HMAC-SHA256-signed console session tokens. Format: {@code payload || HMAC-SHA256(key,
 * payload)}, where {@code payload} is the username plus its issued-at and expiry timestamps,
 * mirroring {@code SecretCipher}'s self-contained {@code iv || ciphertext} shape (here, {@code
 * payload || tag}) so a single opaque string round-trips through {@link #verify} without a caller
 * tracking anything separately.
 *
 * <p>Deliberately not a lookup table: unlike {@code BootstrapTokenRegistry}'s single-use tokens
 * (checked once, against one control-plane node's memory, at issuance), a session token must keep
 * verifying on every request, potentially against whichever node happens to receive it. A signed,
 * stateless token needs no replication or shared table to make that work -- any node holding the
 * same signing key verifies independently. The issued-at timestamp carried in {@link
 * VerifiedSession} exists so a caller can layer a coarse, store-backed revocation check on top (see
 * {@code ApiServer#isSessionRevoked}) without turning the token itself into a lookup table: the
 * store holds only a per-username "revoked before" watermark, never the tokens themselves.
 *
 * <p>Lives in {@code gimle-core}, not {@code gimle-controlplane}, so {@code gimle-fafnir}'s own web
 * console can issue and verify its own session cookies too -- each process signs with its own key
 * (see {@link SessionKeyFileManager}), never a shared one, so this class carries no assumption
 * about which process is calling it.
 */
public final class SessionTokens {

  private static final String MAC_ALGORITHM = "HmacSHA256";

  private SessionTokens() {}

  /**
   * A token's verified identity: the username it was issued for, and the moment it was issued.
   * {@code issuedAtEpochMilli} is what a store-backed revocation check compares against a
   * per-username "revoked before" watermark -- a token issued before the watermark is rejected even
   * though its HMAC signature still verifies and it has not yet hit its ordinary expiry.
   */
  public record VerifiedSession(String username, long issuedAtEpochMilli) {}

  public static String issue(String username, SecretKey signingKey, Duration ttl) {
    return issue(username, signingKey, ttl, Clock.systemUTC());
  }

  /**
   * Injectable-clock variant, paired with {@link #verify(String, SecretKey, Clock)}. The instants
   * this class compares -- the issued-at/expiry stamped into the token and "now" at verification --
   * are the only time it reads, so supplying both lets a test expire a token issued with its real
   * production TTL instead of a millisecond one raced by a sleep.
   */
  public static String issue(String username, SecretKey signingKey, Duration ttl, Clock clock) {
    long issuedAtEpochMilli = clock.instant().toEpochMilli();
    long expiresAtEpochMilli = issuedAtEpochMilli + ttl.toMillis();
    byte[] payload = encodePayload(username, issuedAtEpochMilli, expiresAtEpochMilli);
    byte[] tag = hmac(payload, signingKey);
    byte[] token = new byte[payload.length + tag.length];
    System.arraycopy(payload, 0, token, 0, payload.length);
    System.arraycopy(tag, 0, token, payload.length, tag.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  /**
   * The verified session, or empty if {@code token} is malformed, its HMAC tag doesn't match
   * (verified via {@link MessageDigest#isEqual}, never {@link Arrays#equals} -- a hand-rolled
   * comparison here must be constant-time), or it has expired. Does not itself check revocation --
   * that is a separate, store-backed layer a caller applies on top (see {@link VerifiedSession}'s
   * own javadoc), keeping this class's own verification fully stateless.
   */
  public static Optional<VerifiedSession> verify(String token, SecretKey signingKey) {
    return verify(token, signingKey, Clock.systemUTC());
  }

  /** See {@link #issue(String, SecretKey, Duration, Clock)} for why this overload exists. */
  public static Optional<VerifiedSession> verify(String token, SecretKey signingKey, Clock clock) {
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(token);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    int tagLength = macLength();
    if (decoded.length <= tagLength) {
      return Optional.empty();
    }
    byte[] payload = Arrays.copyOfRange(decoded, 0, decoded.length - tagLength);
    byte[] presentedTag = Arrays.copyOfRange(decoded, decoded.length - tagLength, decoded.length);
    if (!MessageDigest.isEqual(hmac(payload, signingKey), presentedTag)) {
      return Optional.empty();
    }
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
      String username = in.readUTF();
      long issuedAtEpochMilli = in.readLong();
      long expiresAtEpochMilli = in.readLong();
      if (clock.instant().toEpochMilli() > expiresAtEpochMilli) {
        return Optional.empty();
      }
      return Optional.of(new VerifiedSession(username, issuedAtEpochMilli));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static byte[] encodePayload(
      String username, long issuedAtEpochMilli, long expiresAtEpochMilli) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      DataOutputStream out = new DataOutputStream(buffer);
      out.writeUTF(username);
      out.writeLong(issuedAtEpochMilli);
      out.writeLong(expiresAtEpochMilli);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  private static byte[] hmac(byte[] payload, SecretKey signingKey) {
    try {
      Mac mac = Mac.getInstance(MAC_ALGORITHM);
      mac.init(new SecretKeySpec(signingKey.getEncoded(), MAC_ALGORITHM));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  private static int macLength() {
    try {
      return Mac.getInstance(MAC_ALGORITHM).getMacLength();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
