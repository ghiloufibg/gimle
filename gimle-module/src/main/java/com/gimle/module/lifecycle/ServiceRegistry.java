package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Backing store for {@link ModuleContext#registerService}/{@link ModuleContext#lookupService},
 * shared across every module hosted in one worker. Defined here — not in {@code gimle-worker},
 * where the real, richer implementation lives — so {@link SimpleModuleContext} (necessarily
 * per-module, living in {@code gimle-module}) can depend on the contract without {@code
 * gimle-module} depending on {@code gimle-worker}, which would invert the module graph.
 */
public interface ServiceRegistry {

  /**
   * {@code owner} is the registering <em>instance</em>, not its artifact: two replicas of one
   * deployment sharing a worker register the same interface from the same artifact, and everything
   * below that removes or marks an owner's entries has to reach exactly one of them.
   */
  <T> void register(ModuleInstanceId owner, Class<T> iface, T instance);

  /** For a module with no deployment identity -- see {@link ModuleInstanceId}'s own javadoc. */
  default <T> void register(ModuleId owner, Class<T> iface, T instance) {
    register(ModuleInstanceId.unattached(owner), iface, instance);
  }

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
   * Same selection as {@link #lookupByInterfaceName}, but also returns which module owns the
   * selected instance -- needed by {@code FabricServer} to look up that specific module's own
   * {@link ModuleContext}/concurrency-bounding scheduler for an inbound call, which a bare instance
   * reference alone can't provide. Selects in the same single round-robin step as the plain lookup
   * so the returned owner and instance are always for the very same picked provider, never two
   * independent picks that could land on different providers under concurrent registration changes.
   */
  Optional<OwnedInstance> lookupOwnedByInterfaceName(String interfaceName);

  /** One round-robin pick from {@link #lookupOwnedByInterfaceName}, paired with its owner. */
  record OwnedInstance(ModuleInstanceId owner, Object instance) {}

  /**
   * Stops handing out new references to {@code owner}'s services; existing callers are unaffected.
   */
  void markUnready(ModuleInstanceId owner);

  /** For a module with no deployment identity -- see {@link ModuleInstanceId}'s own javadoc. */
  default void markUnready(ModuleId owner) {
    markUnready(ModuleInstanceId.unattached(owner));
  }

  /**
   * Reverses {@link #markUnready}: {@code owner}'s services become eligible for {@link #lookup}
   * again. A no-op for an {@code owner} that never registered anything (or was already removed) --
   * the readiness probe loop keeps ticking after a module's own teardown starts, and a late
   * in-flight tick calling this on a gone owner must not resurrect a phantom entry.
   */
  void markReady(ModuleInstanceId owner);

  /** For a module with no deployment identity -- see {@link ModuleInstanceId}'s own javadoc. */
  default void markReady(ModuleId owner) {
    markReady(ModuleInstanceId.unattached(owner));
  }

  /** Removes everything {@code owner} registered. */
  void remove(ModuleInstanceId owner);

  /** For a module with no deployment identity -- see {@link ModuleInstanceId}'s own javadoc. */
  default void remove(ModuleId owner) {
    remove(ModuleInstanceId.unattached(owner));
  }

  /**
   * Cross-tier, name-driven invocation for a caller that only has a service's identity as plain
   * runtime strings -- an interface name, its major version, and a method signature -- not a
   * compile-time {@code Class<T>} a caller could reflect against directly. A route-config-driven
   * caller (the gateway module) is the motivating case: its routes name a target service at
   * runtime, never at compile time, so {@link #lookup}/{@link #lookupByInterfaceName} (which both
   * hand back a typed or bare instance for the caller's own code to invoke) don't fit -- this
   * method does the invocation itself and hands back only the result.
   *
   * <p>{@code paramTypeNames} names each argument's declared type (not the runtime type of {@code
   * args}' contents), the same convention the fabric wire protocol uses, so the exact overload is
   * resolved deterministically rather than guessed from the arguments alone.
   *
   * <p>Returns {@link Optional#empty()} both when nothing this registry knows about exports {@code
   * interfaceName} at {@code majorVersion} (consistent with {@link #lookupByInterfaceName}'s own
   * "nothing registered" convention -- an unresolvable route is reported the same way as "not
   * registered yet," never as an exception) and when a found method's real return value is
   * legitimately {@code null} or {@code void}; this method has no way to distinguish those two
   * outcomes from the return value alone. An unresolvable method name or parameter-type list is a
   * genuine failure, by contrast: it throws rather than silently matching the wrong overload.
   *
   * <p>{@code default}, not abstract: the implementation here is the same-worker-only behavior
   * every implementation gets for free (a plain reflective invoke against whatever {@link
   * #lookupByInterfaceName} already resolves, ignoring {@code majorVersion} -- this registry tracks
   * no per-registration export-version metadata to filter by, the same reason {@link
   * #lookupByInterfaceName} itself carries no version parameter). {@code majorVersion} is still
   * part of the signature so a name-driven caller never has to special-case "this registry ignores
   * version" -- a richer registry that spans more than one tier (e.g. {@code gimle-fabric}'s own)
   * overrides this to add same-machine/remote tiers with real version filtering, without changing
   * the contract every simpler, same-worker-only implementation (including a wrapper that merely
   * delegates every other method, like {@code gimle-worker}'s own instance-tagging wrapper) has to
   * satisfy.
   */
  default Optional<Object> invokeByName(
      String interfaceName,
      int majorVersion,
      String methodName,
      String[] paramTypeNames,
      Object[] args)
      throws Throwable {
    Optional<Object> instance = lookupByInterfaceName(interfaceName);
    if (instance.isEmpty()) {
      return Optional.empty();
    }
    Class<?> iface = findInterface(instance.get().getClass(), interfaceName);
    Class<?>[] paramTypes = resolveParamTypes(paramTypeNames, iface.getClassLoader());
    Method method = iface.getMethod(methodName, paramTypes);
    try {
      return Optional.ofNullable(
          method.invoke(instance.get(), args == null ? new Object[0] : args));
    } catch (InvocationTargetException e) {
      throw e.getCause() != null ? e.getCause() : e;
    }
  }

  /**
   * The public interface {@code instanceClass} declares matching {@code interfaceName} --
   * reflecting through this, not {@code instanceClass} itself, matters when the instance is a
   * lambda or a dynamic-proxy wrapper (both package-private/synthetic): {@link Method#invoke}
   * checks the *declaring class*'s accessibility, not just the method's own public modifier.
   */
  private static Class<?> findInterface(Class<?> instanceClass, String interfaceName) {
    for (Class<?> candidate : instanceClass.getInterfaces()) {
      if (candidate.getName().equals(interfaceName)) {
        return candidate;
      }
    }
    throw new NoSuchElementException(interfaceName + " is not implemented by " + instanceClass);
  }

  private static Class<?>[] resolveParamTypes(String[] paramTypeNames, ClassLoader loader)
      throws ClassNotFoundException {
    Class<?>[] paramTypes = new Class<?>[paramTypeNames.length];
    for (int i = 0; i < paramTypes.length; i++) {
      paramTypes[i] = resolveParamType(paramTypeNames[i], loader);
    }
    return paramTypes;
  }

  /** Mirrors the wire protocol's own primitive-name convention (see {@code FabricFrame}). */
  private static Class<?> resolveParamType(String name, ClassLoader loader)
      throws ClassNotFoundException {
    return switch (name) {
      case "boolean" -> boolean.class;
      case "byte" -> byte.class;
      case "short" -> short.class;
      case "char" -> char.class;
      case "int" -> int.class;
      case "long" -> long.class;
      case "float" -> float.class;
      case "double" -> double.class;
      case "void" -> void.class;
      default -> Class.forName(name, false, loader);
    };
  }
}
