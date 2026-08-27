package com.gimle.mimir.raft;

import com.gimle.mimir.store.StateStore;

/**
 * The result of applying one {@link StateMutation} to {@link StateStore}: {@link Accepted} for the
 * overwhelming majority, which have no precondition to fail, or {@link Rejected} for a
 * compare-and-set-style mutation (e.g. {@link StateMutation.PutDeployment}/{@link
 * StateMutation.RemoveDeployment}'s generation guard) whose precondition no longer held against the
 * current store state. Computed identically on every node applying the same committed log entry
 * against the same prior state -- deterministic, not leader-specific -- so a rejection is a real,
 * cluster-wide fact about the committed history, not a guess made only on the leader that happened
 * to answer this proposal.
 */
public sealed interface MutationOutcome {

  MutationOutcome ACCEPTED = new Accepted();

  static MutationOutcome accepted() {
    return ACCEPTED;
  }

  static MutationOutcome rejected(String reason) {
    return new Rejected(reason);
  }

  record Accepted() implements MutationOutcome {}

  record Rejected(String reason) implements MutationOutcome {}
}
