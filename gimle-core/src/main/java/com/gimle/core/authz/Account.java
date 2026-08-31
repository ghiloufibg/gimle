package com.gimle.core.authz;

import java.util.Set;

/**
 * A console-login-only identity -- {@code username} plus a {@link PasswordHashes}-produced hash,
 * never the password itself. Not used by the CLI or node agents, which authenticate exclusively via
 * mTLS client certificates; this exists specifically because a browser session has no certificate
 * to prove itself with. {@code passwordHash} follows {@code SecretCipher}'s existing
 * "self-contained {@code byte[]}" convention (defensively cloned in and out, matching {@link
 * com.gimle.core.config.ConfigEntry#value()}). {@code groups} is what lets a {@code group:} {@link
 * RoleBinding} subject actually match a console-login principal at authorize-time -- a
 * certificate-authenticated principal's groups come from its {@code O=} RDN, but a session-cookie
 * principal has no certificate to read one from, so this is that identity's own durable group
 * membership instead.
 */
public record Account(String username, byte[] passwordHash, Set<String> groups) {

  public Account {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (passwordHash == null || passwordHash.length == 0) {
      throw new IllegalArgumentException("passwordHash must not be empty");
    }
    passwordHash = passwordHash.clone();
    groups = groups == null ? Set.of() : Set.copyOf(groups);
  }

  /** No group membership -- the common case for an account with no {@code group:} binding. */
  public Account(String username, byte[] passwordHash) {
    this(username, passwordHash, Set.of());
  }

  @Override
  public byte[] passwordHash() {
    return passwordHash.clone();
  }
}
