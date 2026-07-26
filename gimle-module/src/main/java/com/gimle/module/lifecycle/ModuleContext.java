package com.gimle.module.lifecycle;

import java.util.Optional;

/**
 * A module's view into the platform, passed to its lifecycle hooks. This is a Phase 1/2
 * placeholder: the full platform service API (config, metrics) that hosted modules see belongs to
 * {@code gimle-api}, a later phase. This — and {@link ModuleLifecycleHooks}, which hosted-module
 * authors implement — temporarily lives in {@code gimle-module} rather than the hosted-code-facing
 * module it architecturally belongs in. Migrate both once {@code gimle-api} exists.
 */
public interface ModuleContext {

  int inFlightCount();

  /** A hosted module calls this when it starts handling a request, so drain can wait for it. */
  void beginRequest();

  /** A hosted module calls this when it finishes handling a request. */
  void endRequest();

  /**
   * Publishes {@code instance} for other same-worker modules to find via {@link #lookupService}.
   */
  <T> void registerService(Class<T> iface, T instance);

  /**
   * A same-worker, direct-call instance (round-robin among ready providers) — see {@link
   * ServiceRegistry}.
   */
  <T> Optional<T> lookupService(Class<T> iface);
}
