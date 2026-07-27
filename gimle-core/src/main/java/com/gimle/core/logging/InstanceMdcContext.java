package com.gimle.core.logging;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * Builds the MDC tag set for one module instance and applies it around either a direct method
 * invocation (via a dynamic proxy, the chosen choke point: wrapping a service reference at
 * registration time covers both same-worker calls and {@code FabricServer}'s local-invoke path,
 * since both ultimately call through that same registered reference) or an arbitrary {@link
 * Callable} (used for {@code BoundedModuleScheduler}'s probe-check dispatch, which never goes
 * through the service registry at all).
 */
public final class InstanceMdcContext {

  private InstanceMdcContext() {}

  public static Map<String, String> tagsFor(
      String deploymentName,
      int instanceIndex,
      String moduleId,
      String moduleVersion,
      String tenantIdOrNull) {
    Map<String, String> tags = new LinkedHashMap<>();
    tags.put(InstanceMdcKeys.DEPLOYMENT_NAME, deploymentName);
    tags.put(InstanceMdcKeys.INSTANCE_INDEX, Integer.toString(instanceIndex));
    tags.put(InstanceMdcKeys.MODULE_ID, moduleId);
    tags.put(InstanceMdcKeys.MODULE_VERSION, moduleVersion);
    if (tenantIdOrNull != null) {
      tags.put(InstanceMdcKeys.TENANT_ID, tenantIdOrNull);
    }
    return tags;
  }

  /**
   * Wraps {@code target} in a dynamic proxy that snapshots/restores MDC around every invocation --
   * the caller's own MDC state (if any) is preserved, not clobbered, matching the propagation
   * pattern already established for OpenTelemetry {@code Context} in {@code
   * BoundedModuleScheduler}.
   */
  public static <T> T tagProxy(Class<T> iface, T target, Map<String, String> tags) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          Map<String, String> previous = MDC.getCopyOfContextMap();
          tags.forEach(MDC::put);
          try {
            return method.invoke(target, args);
          } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
          } finally {
            restore(previous);
          }
        };
    Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
    return iface.cast(proxy);
  }

  /** Same tagging behavior as {@link #tagProxy}, for a plain task rather than an interface call. */
  public static <V> V runTagged(Map<String, String> tags, Callable<V> task) throws Exception {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    tags.forEach(MDC::put);
    try {
      return task.call();
    } finally {
      restore(previous);
    }
  }

  private static void restore(Map<String, String> previous) {
    if (previous != null) {
      MDC.setContextMap(previous);
    } else {
      MDC.clear();
    }
  }
}
