package com.example.nodelocalcache;

/**
 * The fabric service contract shared by local-flag-cache and flag-consumer. Each module bundles
 * its own literal copy of this interface (same fully-qualified name, same signature) rather than
 * depending on a shared compile-time API jar -- the fabric's service catalog resolves lookups by
 * interface name and dispatches through a proxy built from the caller's own {@code Class} object,
 * so two independently compiled, structurally identical copies interoperate correctly across the
 * wire. The same "structural contract, not a shared jar" demonstration
 * gimle-examples/greeter-provider and greeter-consumer already establish.
 */
public interface FeatureFlagCache {

  /**
   * The current value of {@code flagName} (a fixed, unknown-defaults-to-false vocabulary this
   * cache seeds at startup), plus the answering replica's own {@link FlagAnswer#replicaId()} --
   * the latter is the whole point of this app: a caller that keeps seeing the *same* replicaId
   * across repeated calls is proof its fabric calls kept landing on the one DaemonSet replica
   * co-located with it, never crossing to another node's.
   */
  FlagAnswer get(String flagName);
}
