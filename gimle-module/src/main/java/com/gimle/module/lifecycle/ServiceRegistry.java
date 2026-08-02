package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import java.util.Optional;

/**
 * Backing store for {@link ModuleContext#registerService}/{@link ModuleContext#lookupService},
 * shared across every module hosted in one worker. Defined here — not in {@code gimle-worker},
 * where the real, richer implementation lives — so {@link SimpleModuleContext} (necessarily
 * per-module, living in {@code gimle-module}) can depend on the contract without {@code
 * gimle-module} depending on {@code gimle-worker}, which would invert the module graph.
 */
public interface ServiceRegistry {

  <T> void register(ModuleId owner, Class<T> iface, T instance);

  /**
   * Selects among every ready, registered instance of {@code iface} — round-robin, not
   * least-outstanding-requests. A same-worker lookup returns a direct reference for a plain virtual
   * method dispatch, by design: no proxy, no call-site instrumentation, so outstanding call counts
   * genuinely can't be measured without undermining that zero-overhead dispatch.
   * Least-outstanding-requests remains the right algorithm once a call crosses a real network
   * boundary with its own dispatch layer to instrument — {@code gimle-fabric}'s job, not this
   * same-JVM case.
   */
  <T> Optional<T> lookup(Class<T> iface);

  /**
   * Same selection as {@link #lookup}, keyed by interface name instead of a resolved {@code
   * Class<T>} -- needed by {@code FabricServer}'s inbound dispatch, which only has an interface
   * name off the wire and, without a shared platform-layer service-contract module ({@code
   * gimle-api} doesn't exist yet), has no single classloader that's guaranteed to be able to
   * resolve every hosted module's own service interfaces. The registry, however, already holds the
   * real registered instance keyed by the exact {@code Class} its owning module registered it
   * under, so it can answer a name-based query without needing to resolve the interface itself at
   * all.
   */
  Optional<Object> lookupByInterfaceName(String interfaceName);

  /**
   * Stops handing out new references to {@code owner}'s services; existing callers are unaffected.
   */
  void markUnready(ModuleId owner);

  /** Removes everything {@code owner} registered. */
  void remove(ModuleId owner);
}
