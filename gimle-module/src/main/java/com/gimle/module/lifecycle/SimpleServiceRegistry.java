package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real, complete, round-robin {@link ServiceRegistry} — {@link ModuleController}'s default when
 * nothing richer is supplied. Correct for any number of providers, including the common
 * single-provider case; {@code gimle-worker} supplies a shared instance across every module it
 * hosts, but nothing here is a stub — it's simply this class's whole job description.
 */
public final class SimpleServiceRegistry implements ServiceRegistry {

  private final Map<Class<?>, List<Entry>> entriesByInterface = new ConcurrentHashMap<>();
  private final Map<Class<?>, AtomicInteger> cursors = new ConcurrentHashMap<>();

  @Override
  public synchronized <T> void register(ModuleId owner, Class<T> iface, T instance) {
    List<Entry> entries =
        entriesByInterface.computeIfAbsent(iface, key -> new CopyOnWriteArrayList<>());
    entries.removeIf(entry -> entry.owner().equals(owner));
    entries.add(new Entry(owner, instance, new AtomicBoolean(true)));
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Optional<T> lookup(Class<T> iface) {
    return (Optional<T>) select(iface);
  }

  @Override
  public Optional<Object> lookupByInterfaceName(String interfaceName) {
    return findInterface(interfaceName).flatMap(this::select);
  }

  @Override
  public Optional<OwnedInstance> lookupOwnedByInterfaceName(String interfaceName) {
    return findInterface(interfaceName)
        .flatMap(this::selectEntry)
        .map(entry -> new OwnedInstance(entry.owner(), entry.instance()));
  }

  /**
   * {@code entriesByInterface.keySet()} iteration order is unspecified ({@link ConcurrentHashMap}
   * gives no ordering guarantee) -- with two owners registered under distinct {@link Class} objects
   * for the "same" interface name (the case during a redeploy that reloads the interface class in a
   * fresh {@code ModuleLayer}), returning the first match iterated was nondeterministic. Break ties
   * by the highest {@link ModuleId#version()} among that {@link Class}'s registered owners,
   * matching {@link #selectEntry}'s own highest-version-first preference below.
   */
  private Optional<Class<?>> findInterface(String interfaceName) {
    Class<?> best = null;
    Version bestVersion = null;
    for (Map.Entry<Class<?>, List<Entry>> candidate : entriesByInterface.entrySet()) {
      Class<?> iface = candidate.getKey();
      if (!iface.getName().equals(interfaceName)) {
        continue;
      }
      Version highest = highestOwnerVersion(candidate.getValue());
      if (best == null
          || (highest != null && (bestVersion == null || highest.compareTo(bestVersion) > 0))) {
        best = iface;
        bestVersion = highest;
      }
    }
    return Optional.ofNullable(best);
  }

  private static Version highestOwnerVersion(List<Entry> entries) {
    Version highest = null;
    for (Entry entry : entries) {
      Version candidate = entry.owner().version();
      if (highest == null || candidate.compareTo(highest) > 0) {
        highest = candidate;
      }
    }
    return highest;
  }

  private Optional<Object> select(Class<?> iface) {
    return selectEntry(iface).map(Entry::instance);
  }

  /**
   * Hot redeploy can leave both the old and new version of a module registered under the same
   * interface at once, deliberately -- see {@code HotRedeployTest}. A blended round-robin across
   * both versions would send a fraction of fresh lookups to the version being drained. Instead,
   * cutover is atomic per lookup: prefer the highest version that currently has at least one ready
   * entry, round-robining only within that version's entries; fall back to the next highest version
   * only when the top one has none ready (e.g. still starting up).
   */
  private Optional<Entry> selectEntry(Class<?> iface) {
    List<Entry> entries = entriesByInterface.get(iface);
    if (entries == null || entries.isEmpty()) {
      return Optional.empty();
    }
    Version preferred = null;
    for (Entry entry : entries) {
      if (!entry.ready().get()) {
        continue;
      }
      Version candidate = entry.owner().version();
      if (preferred == null || candidate.compareTo(preferred) > 0) {
        preferred = candidate;
      }
    }
    if (preferred == null) {
      return Optional.empty();
    }
    Version selectedVersion = preferred;
    List<Entry> ready =
        entries.stream()
            .filter(entry -> entry.ready().get())
            .filter(entry -> entry.owner().version().equals(selectedVersion))
            .toList();
    AtomicInteger cursor = cursors.computeIfAbsent(iface, key -> new AtomicInteger());
    int index = Math.floorMod(cursor.getAndIncrement(), ready.size());
    return Optional.of(ready.get(index));
  }

  @Override
  public synchronized void markUnready(ModuleId owner) {
    for (List<Entry> entries : entriesByInterface.values()) {
      for (Entry entry : entries) {
        if (entry.owner().equals(owner)) {
          entry.ready().set(false);
        }
      }
    }
  }

  @Override
  public synchronized void markReady(ModuleId owner) {
    for (List<Entry> entries : entriesByInterface.values()) {
      for (Entry entry : entries) {
        if (entry.owner().equals(owner)) {
          entry.ready().set(true);
        }
      }
    }
  }

  @Override
  public synchronized void remove(ModuleId owner) {
    for (List<Entry> entries : entriesByInterface.values()) {
      entries.removeIf(entry -> entry.owner().equals(owner));
    }
    // An interface Class emptied of entries is still a strong key in entriesByInterface; for an
    // interface loaded by the just-disposed module's own ModuleLayer, that key would pin the
    // classloader forever and defeat hot-redeploy leak detection. Drop empty lists (and their
    // now-orphaned round-robin cursors) so nothing keeps the Class -- and its loader -- reachable.
    entriesByInterface.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    cursors.keySet().removeIf(iface -> !entriesByInterface.containsKey(iface));
  }

  // invokeByName: no override needed here -- ServiceRegistry's own default method (a plain
  // reflective invoke against whatever lookupByInterfaceName resolves) is already exactly this
  // class's same-worker-only behavior; see that default's own javadoc.

  private record Entry(ModuleId owner, Object instance, AtomicBoolean ready) {}
}
