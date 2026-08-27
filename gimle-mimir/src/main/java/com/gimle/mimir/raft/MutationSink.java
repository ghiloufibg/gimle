package com.gimle.mimir.raft;

import java.util.List;

/**
 * What a reconciler proposes mutations to. {@link RaftNode#propose} already matches this shape, so
 * production wiring passes a {@code RaftNode} directly with no adapter class; tests pass {@code
 * mutation -> mutation.applyTo(store)} for a direct-apply path that bypasses replication. Returns
 * the {@link MutationOutcome} the mutation's own {@link StateMutation#applyTo} computed -- {@link
 * MutationOutcome#accepted()} for the overwhelming majority, which have no precondition to reject;
 * only a caller proposing a CAS-guarded mutation (today, only {@code ApiServer}'s deployment
 * apply/delete/rollback handlers) has any reason to inspect it.
 */
@FunctionalInterface
public interface MutationSink {
  MutationOutcome propose(StateMutation mutation);

  /**
   * Proposes every mutation as one {@link StateMutation.Batch} -- equivalent to calling {@link
   * #propose} on each in order, but paying one consensus round and one WAL fsync for the whole
   * burst instead of one per mutation. An empty list is a no-op; a single mutation is proposed
   * bare, since wrapping it would add batch framing for nothing. {@link StateMutation.Batch} itself
   * always reports {@link MutationOutcome#accepted()} (see its own javadoc), so this default method
   * discards the result the same way every existing {@code proposeAll} caller already did before
   * {@link #propose} returned anything meaningful.
   */
  default void proposeAll(List<StateMutation> mutations) {
    if (mutations.isEmpty()) {
      return;
    }
    if (mutations.size() == 1) {
      propose(mutations.getFirst());
      return;
    }
    propose(new StateMutation.Batch(mutations));
  }
}
