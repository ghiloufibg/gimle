package com.gimle.module.lifecycle;

/**
 * A module's view into the platform, passed to its lifecycle hooks. This is a Phase 1 placeholder:
 * the full platform service API (service registry, config, metrics) that hosted modules see belongs
 * to {@code gimle-api}, a later phase. Phase 1 only needs enough surface to support the {@code
 * STOPPING} drain wait, so this — and {@link ModuleLifecycleHooks}, which hosted-module authors
 * implement — temporarily lives in {@code gimle-module} rather than the hosted-code-facing module
 * it architecturally belongs in. Migrate both once {@code gimle-api} exists.
 */
public interface ModuleContext {

  int in_flight_count();

  /** A hosted module calls this when it starts handling a request, so drain can wait for it. */
  void begin_request();

  /** A hosted module calls this when it finishes handling a request. */
  void end_request();
}
