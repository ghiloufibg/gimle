package com.gimle.mimir.raft;

/**
 * What a reconciler proposes mutations to. {@link RaftNode#propose} already matches this shape, so
 * production wiring passes a {@code RaftNode} directly with no adapter class; tests pass {@code
 * mutation -> mutation.applyTo(store)} for a direct-apply path that bypasses replication.
 */
@FunctionalInterface
public interface MutationSink {
  void propose(StateMutation mutation);
}
