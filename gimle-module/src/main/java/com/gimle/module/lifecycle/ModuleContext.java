package com.gimle.module.lifecycle;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
   * Cross-tier, name-driven service invocation for a caller with no compile-time {@code Class<T>}
   * to look up by — a module whose own routes/config name a target service at runtime, not in Java
   * source. See {@link ServiceRegistry#invokeByName} for the full contract (tiers tried, what an
   * empty {@link Optional} means, what throws instead).
   */
  Optional<Object> invokeServiceByName(
      String interfaceName,
      int majorVersion,
      String methodName,
      String[] paramTypeNames,
      Object[] args)
      throws Throwable;

  /**
   * Looks up a tenant-scoped configuration or secret value the agent has relayed down for this
   * instance — always plaintext by the time a module sees it, whether or not it was encrypted at
   * rest. Absent if the key was never delivered.
   */
  Optional<String> config(String key);

  /**
   * Every key currently visible via {@link #config} — a snapshot, not a live view, so a module can
   * iterate it while config delivery is happening concurrently. Lets a module that treats its
   * config as a namespace (a prefix of related keys, a set whose names it doesn't know at compile
   * time) enumerate what it actually received instead of probing key by key.
   */
  Set<String> configKeys();

  /**
   * This instance's own placement identity as the platform sees it: which deployment it backs,
   * which replica index it is, which node it runs on, and which tenant owns it. Empty when no
   * identity was ever reported for this module — a directly-embedded controller in a test, or an
   * install that carried no deployment name. Looked up live on every call, never cached: an
   * in-place retarget (a surge instance promoted to a new index) changes the answer without the
   * module restarting.
   */
  Optional<InstanceInfo> instanceInfo();

  /** The identity {@link #instanceInfo} answers with. */
  record InstanceInfo(
      String deploymentName, int instanceIndex, String nodeId, Optional<String> tenantId) {}

  /**
   * The host path this instance's persistent volume was allocated at, present only if the module's
   * own descriptor declares {@code volume:} -- absent for every ordinary (non-{@code StatefulSet})
   * instance, the only shape every pre-existing hook has ever seen. Already populated by the time
   * {@code onInstall} runs, not just {@code onStart}: the agent resolves and delivers it before
   * this context is even created.
   */
  Optional<Path> dataDirectory();

  /**
   * A narrow, whitelisted read-back into the control plane's own HTTP API, relayed through this
   * instance's worker and its supervising agent -- a worker JVM has no outbound network identity of
   * its own, only its agent does, so a hosted module can never make this call directly. {@code
   * path} names an HTTP path only (e.g. {@code /endpoints/my-deployment}); the agent alone decides
   * whether it's one of the paths this mechanism is allowed to reach, so this method is generic
   * over any future whitelisted path rather than hard-coded to one route. Never throws for an
   * ordinary relay failure (rejected by the whitelist, or the agent couldn't reach the control
   * plane) -- those come back as an ordinary {@link RelayResult} carrying a synthesized status
   * code, the same "modules see values, not exceptions" posture {@link #config} already has.
   */
  RelayResult relayControlPlaneRead(String path);

  /** The outcome of one {@link #relayControlPlaneRead} call: an HTTP status code and body. */
  record RelayResult(int status, String body) {}

  /**
   * A hosted module declares one of its own runtime-bound listener ports back to the platform --
   * e.g. an {@code onStart} hook that just called {@code HttpServer.create(...)} on some port
   * additionally reports it under a name of its own choosing, so a {@code ServiceSpec} fronting
   * this deployment can resolve a live endpoint the same way it already can for a Vessel's declared
   * ports. {@code name} is this module's own choice, not a fixed vocabulary -- it ends up as the
   * key in {@code InstanceObservation.ports()}, the same env-var-name-keyed shape {@code
   * VesselEnvValue.PortAllocation} already populates that map under, so a {@code ServiceSpec}'s own
   * {@code targetPort} resolution logic doesn't need to know or care which side reported it.
   * Calling this more than once under the same {@code name} replaces the previously reported value
   * -- a module that re-binds is expected to report again, not to have accumulated stale entries.
   * Never required: a module that never calls this reports no ports at all, exactly today's
   * behavior.
   */
  void reportPort(String name, int port);

  /**
   * Every port this instance has reported via {@link #reportPort} so far, keyed by the name it was
   * reported under. Empty for the overwhelming majority of modules, which never call {@link
   * #reportPort} at all.
   */
  Map<String, Integer> reportedPorts();
}
