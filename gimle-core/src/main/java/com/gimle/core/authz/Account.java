package com.gimle.core.authz;

/**
 * A console-login-only identity -- {@code username} plus a {@link PasswordHashes}-produced hash,
 * never the password itself. Not used by the CLI or node agents, which authenticate exclusively via
 * mTLS client certificates; this exists specifically because a browser session has no certificate
 * to prove itself with. {@code passwordHash} follows {@code SecretCipher}'s existing
 * "self-contained {@code byte[]}" convention (defensively cloned in and out, matching {@link
 * com.gimle.core.config.ConfigEntry#value()}).
 */
public record Account(String username, byte[] passwordHash) {

  public Account {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (passwordHash == null || passwordHash.length == 0) {
      throw new IllegalArgumentException("passwordHash must not be empty");
    }
    passwordHash = passwordHash.clone();
  }

  @Override
  public byte[] passwordHash() {
    return passwordHash.clone();
  }
}
