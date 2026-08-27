package com.gimle.ragnarok.target;

/**
 * The network-fault primitives a {@link ClusterTarget} can offer: severing a control-plane
 * replica's links to the store cluster, or silently partitioning one store replica from its peers'
 * raft traffic. Only a target with boot-time interposition over its own topology (a harness-owned
 * cluster) can implement this meaningfully -- see {@link ClusterTarget#faults()}'s javadoc.
 */
public interface NetworkFaultInjector {

  /** Cuts control-plane replica {@code controlPlaneIndex}'s links to every store, immediately. */
  Partition cutControlPlaneFromStores(int controlPlaneIndex);

  /** Silently drops store replica {@code storeIndex}'s traffic to and from every peer. */
  Partition cutStoreFromPeers(int storeIndex);

  /** An open fault; {@link #heal()} restores the link. */
  interface Partition extends AutoCloseable {

    void heal();

    @Override
    default void close() {
      heal();
    }
  }
}
