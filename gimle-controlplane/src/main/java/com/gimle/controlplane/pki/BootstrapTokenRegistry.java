package com.gimle.controlplane.pki;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Short-lived, single-use bootstrap tokens: a plain random secret an operator hands to a new node,
 * scoped to exactly one {@code POST /bootstrap/csr} call. In-memory only, on purpose, not
 * replicated through Raft -- the same reasoning {@code StateMutation}'s own javadoc gives for
 * skipping heartbeats: short-lived, high-churn, and only the leader's own process ever needs to see
 * them within their lifetime. In a multi-node HA control plane, an operator must issue a token and
 * have the joining node submit its CSR against the same node.
 */
public final class BootstrapTokenRegistry {

  private static final int TOKEN_BYTES = 32;

  private final ConcurrentMap<String, Instant> tokenExpiry = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;

  public BootstrapTokenRegistry() {
    this(Clock.systemUTC());
  }

  /**
   * Injectable-clock variant. Both instants this class compares -- a token's expiry and "now" at
   * consumption -- come from here, so a test can expire a token issued with its real production TTL
   * rather than issuing one with a millisecond TTL and racing it with a sleep.
   */
  public BootstrapTokenRegistry(Clock clock) {
    this.clock = clock;
  }

  /** Issues a new token valid for {@code ttl}, encoded URL-safe so it's easy to copy/paste. */
  public String issue(Duration ttl) {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tokenExpiry.put(token, clock.instant().plus(ttl));
    return token;
  }

  /**
   * Atomically checks {@code token} is currently valid (issued, unexpired, not already consumed)
   * and removes it if so -- single-use enforcement requires this: a leaked token replayed a second
   * time must fail, not just eventually expire.
   */
  public boolean tryConsume(String token) {
    if (token == null || token.isBlank()) {
      return false;
    }
    Instant[] removed = new Instant[1];
    tokenExpiry.computeIfPresent(
        token,
        (key, expiry) -> {
          if (expiry.isAfter(clock.instant())) {
            removed[0] = expiry;
          }
          return null;
        });
    return removed[0] != null;
  }
}
