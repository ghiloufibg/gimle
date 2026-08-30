package com.gimle.mimir.galdr;

/**
 * Whether instances of a custom kind live inside one tenant's namespace or in the cluster-wide
 * untenanted namespace -- the Namespaced/Cluster analogue. A {@code TENANT}-scoped kind requires
 * every instance to carry a {@code tenantId}; a {@code CLUSTER}-scoped kind forbids one.
 */
public enum KindScope {
  TENANT,
  CLUSTER
}
