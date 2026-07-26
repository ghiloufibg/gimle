package com.gimle.module.probe;

/**
 * Implemented by a hosted module to answer "is this instance ready for traffic," called directly by
 * the worker's probe loop. Unlike {@link LivenessProbe}, a failing readiness probe never triggers a
 * restart — it only flips the module's tracked readiness state, consulted by the service registry
 * when selecting among ready providers.
 */
public interface ReadinessProbe {

  boolean isReady();
}
