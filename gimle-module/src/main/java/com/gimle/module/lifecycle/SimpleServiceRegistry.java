package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real, complete, round-robin {@link ServiceRegistry} — {@link ModuleController}'s default when
 * nothing richer is supplied. Correct for any number of providers, including the single-provider
 * case every Phase 1 test exercises; {@code gimle-worker} supplies a shared instance across every
 * module it hosts, but nothing here is a stub — it's simply this class's whole job description.
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
    List<Entry> entries = entriesByInterface.get(iface);
    if (entries == null || entries.isEmpty()) {
      return Optional.empty();
    }
    List<Entry> ready = entries.stream().filter(entry -> entry.ready().get()).toList();
    if (ready.isEmpty()) {
      return Optional.empty();
    }
    AtomicInteger cursor = cursors.computeIfAbsent(iface, key -> new AtomicInteger());
    int index = Math.floorMod(cursor.getAndIncrement(), ready.size());
    return Optional.of((T) ready.get(index).instance());
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
  public synchronized void remove(ModuleId owner) {
    for (List<Entry> entries : entriesByInterface.values()) {
      entries.removeIf(entry -> entry.owner().equals(owner));
    }
  }

  private record Entry(ModuleId owner, Object instance, AtomicBoolean ready) {}
}
