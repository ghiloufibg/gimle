package com.gimle.controlplane.raft;

/**
 * What a reconciler proposes mutations to -- {@link RaftNode#propose} already matches this shape,
 * so the production wiring passes a {@code RaftNode} directly with no adapter class; tests pass
 * {@code mutation -> mutation.applyTo(store)} for a direct-apply path that bypasses replication
 * (design §2, "MutationSink seam").
 */
@FunctionalInterface
public interface MutationSink {
  void propose(StateMutation mutation);
}
