package com.gimle.controlplane.reconcile;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link StoreReader} view of a real {@link StateStore} in which chosen reads fail the way a
 * {@code StoreClient} fails when the store is briefly unreachable or has no leader: {@link
 * GimleRaftException#storeUnreachable}. Everything else forwards to the store unchanged.
 *
 * <p>Reflection-backed rather than a hand-written delegate, because {@code StoreReader} has upwards
 * of a hundred methods and a test only ever cares about one of them -- writing the other
 * ninety-nine out by hand would bury the one line that matters.
 */
final class UnreachableStoreReads {

  private UnreachableStoreReads() {}

  /**
   * Fails every read named in {@code failingMethodNames} while {@code failing} is set, so a test
   * can break one tick mid-flight and then let the next one succeed against the very same store.
   */
  static StoreReader over(StateStore store, AtomicBoolean failing, Set<String> failingMethodNames) {
    return (StoreReader)
        Proxy.newProxyInstance(
            StoreReader.class.getClassLoader(),
            new Class<?>[] {StoreReader.class},
            (proxy, method, args) -> {
              if (failing.get() && failingMethodNames.contains(method.getName())) {
                throw GimleRaftException.storeUnreachable(method.getName());
              }
              try {
                return method.invoke(store, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }
}
