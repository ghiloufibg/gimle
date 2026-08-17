package com.gimle.core.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared SHA-256 digest/hex-encoding boilerplate: every process that needs to verify or fingerprint
 * bytes -- Andvari's artifact store, the module/vessel artifact readers, Fafnir's key ring, Saga's
 * run-id derivation, Hilmir's gateway-enablement command -- otherwise reimplements the identical
 * {@code MessageDigest.getInstance("SHA-256")}/{@code NoSuchAlgorithmException} handling on its own.
 */
public final class Sha256 {

  private Sha256() {}

  /**
   * A fresh SHA-256 {@link MessageDigest}. {@code SHA-256} is a mandatory algorithm for every
   * conforming JCA provider, so {@link NoSuchAlgorithmException} here means a broken JVM, not a
   * caller error -- wrapped unchecked rather than forcing every caller to handle an unreachable
   * checked exception.
   */
  public static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
    }
  }

  /** The hex-encoded SHA-256 digest of {@code bytes}. */
  public static String sha256Hex(byte[] bytes) {
    return HexFormat.of().formatHex(sha256Digest().digest(bytes));
  }

  /**
   * The hex-encoded SHA-256 digest of {@code file}'s contents, streamed rather than buffered
   * whole.
   */
  public static String sha256Hex(Path file) throws IOException {
    MessageDigest digest = sha256Digest();
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
