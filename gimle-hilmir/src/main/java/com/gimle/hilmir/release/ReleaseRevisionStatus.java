package com.gimle.hilmir.release;

/**
 * Whether a {@link ReleaseRevision} fully applied, or was cut short by an apply/prune failure
 * partway through -- Helm's own real revision-status split (a failed {@code install}/{@code
 * upgrade} still becomes a revision in {@code helm history}, marked failed, rather than vanishing
 * without a trace). No automatic rollback is implied by {@link #FAILED} -- that's Helm's own opt-in
 * {@code --atomic} behavior, deliberately not implemented here.
 */
enum ReleaseRevisionStatus {
  SUCCEEDED,
  FAILED
}
