package com.gimle.mimir.raft;

import java.util.List;

/**
 * What a reconciler proposes mutations to. {@link RaftNode#propose} already matches this shape, so
 * production wiring passes a {@code RaftNode} directly with no adapter class; tests pass {@code
 * mutation -> mutation.applyTo(store)} for a direct-apply path that bypasses replication.
 */
@FunctionalInterface
public interface MutationSink {
  void propose(StateMutation mutation);

  /**
   * Proposes every mutation as one {@link StateMutation.Batch} -- equivalent to calling {@link
   * #propose} on each in order, but paying one consensus round and one WAL fsync for the whole
   * burst instead of one per mutation. An empty list is a no-op; a single mutation is proposed
   * bare, since wrapping it would add batch framing for nothing.
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
