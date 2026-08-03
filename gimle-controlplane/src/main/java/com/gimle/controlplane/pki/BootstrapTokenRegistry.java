package com.gimle.controlplane.pki;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Short-lived, single-use bootstrap tokens for §4 step 1 of {@code
 * claudedocs/tls-transport-security-design.md}: a plain random secret an operator hands to a new
 * node, scoped to exactly one {@code POST /bootstrap/csr} call. In-memory only, on purpose, not
 * replicated through Raft -- the same reasoning {@code StateMutation}'s own javadoc gives for
 * skipping heartbeats: short-lived, high-churn, and only the leader's own process ever needs to see
 * them within their lifetime. In a multi-node HA control plane, an operator must issue a token and
 * have the joining node submit its CSR against the same node.
 */
public final class BootstrapTokenRegistry {

  private static final int TOKEN_BYTES = 32;

  private final ConcurrentMap<String, Instant> tokenExpiry = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  /** Issues a new token valid for {@code ttl}, encoded URL-safe so it's easy to copy/paste. */
  public String issue(Duration ttl) {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tokenExpiry.put(token, Instant.now().plus(ttl));
    return token;
  }

  /**
   * Atomically checks {@code token} is currently valid (issued, unexpired, not already consumed)
   * and removes it if so -- the single-use enforcement §4 step 1 requires: a leaked token replayed
   * a second time must fail, not just eventually expire.
   */
  public boolean tryConsume(String token) {
    if (token == null || token.isBlank()) {
      return false;
    }
    Instant[] removed = new Instant[1];
    tokenExpiry.computeIfPresent(
        token,
        (key, expiry) -> {
          if (expiry.isAfter(Instant.now())) {
            removed[0] = expiry;
          }
          return null;
        });
    return removed[0] != null;
  }
}
