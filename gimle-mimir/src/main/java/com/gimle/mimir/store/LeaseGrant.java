package com.gimle.mimir.store;

import java.time.Instant;

/**
 * The result of {@link StateStore#tryAcquireOrRenewLease} -- {@code granted == false} means {@code
 * holderId}/{@code expiresAt} describe whoever currently holds the lease instead (mirroring how
 * {@link com.gimle.mimir.raft.RaftNode#propose} returns a leader hint on rejection rather than just
 * failing silently), so a denied caller can at least log who's currently elected without a second
 * round trip.
 */
public record LeaseGrant(boolean granted, String holderId, Instant expiresAt) {

  public LeaseGrant {
    if (holderId == null) {
      throw new IllegalArgumentException("holderId must not be null");
    }
    if (expiresAt == null) {
      throw new IllegalArgumentException("expiresAt must not be null");
    }
  }
}
