package com.gimle.module.lifecycle;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A module's view into the platform, passed to its lifecycle hooks. This is a placeholder: the full
 * platform service API (config, metrics) that hosted modules see belongs in {@code gimle-api},
 * which doesn't exist yet. This — and {@link ModuleLifecycleHooks}, which hosted-module authors
 * implement — temporarily lives in {@code gimle-module} rather than the hosted-code-facing module
 * it architecturally belongs in, and should migrate once {@code gimle-api} exists.
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

  /**
   * Looks up a tenant-scoped configuration or secret value the agent has relayed down for this
   * instance — always plaintext by the time a module sees it, whether or not it was encrypted at
   * rest. Absent if the key was never delivered.
   */
  Optional<String> config(String key);

  /**
   * The host path this instance's persistent volume was allocated at, present only if the module's
   * own descriptor declares {@code volume:} -- absent for every ordinary (non-{@code StatefulSet})
   * instance, the only shape every pre-existing hook has ever seen. Already populated by the time
   * {@code onInstall} runs, not just {@code onStart}: the agent resolves and delivers it before
   * this context is even created.
   */
  Optional<Path> dataDirectory();
}
