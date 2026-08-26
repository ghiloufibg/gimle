package com.gimle.mimir.store;

import java.util.Optional;

/**
 * One deployment's live workload-identity token on one node, as the store replicates it: the
 * SHA-256 of the opaque token the minting {@code ApiServer} handed that node's agent (never the
 * token itself -- a store compromise reveals no usable credential), the tenant and deployment the
 * token asserts, and its expiry. Keyed by {@code key} ({@code deploymentName#nodeId}), so each
 * node's agent holds its own token per deployment and a re-mint replaces only that node's entry --
 * two nodes hosting instances of one deployment never invalidate each other's tokens.
 *
 * <p>Store-backed rather than HMAC-signed like console session tokens deliberately: a session
 * cookie sticks to the one replica that issued it, but a workload token must verify on whichever
 * control-plane replica a relayed request lands on, and replicas share no signing key -- they share
 * the store. A lookup per verification is the same per-request store read the {@code Authorizer}
 * already makes, and it buys immediate revocability for free: removing the record kills the token.
 */
public record WorkloadTokenRecord(
    String key,
    String tokenSha256Hex,
    Optional<String> tenantId,
    String deploymentName,
    long expiresAtEpochMilli) {

  public WorkloadTokenRecord {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (tokenSha256Hex == null || tokenSha256Hex.isBlank()) {
      throw new IllegalArgumentException("tokenSha256Hex must not be blank");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (deploymentName == null || deploymentName.isBlank()) {
      throw new IllegalArgumentException("deploymentName must not be blank");
    }
    if (expiresAtEpochMilli <= 0) {
      throw new IllegalArgumentException(
          "expiresAtEpochMilli must be positive: " + expiresAtEpochMilli);
    }
  }
}
