package com.gimle.module.resolve;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.module.layer.ModuleLayerHandle;
import com.gimle.module.lifecycle.ModuleState;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single source of truth for what's installed, its lifecycle state, its wiring once resolved,
 * and its runtime {@link ModuleLayerHandle} once resolved. Named per-transition methods (rather
 * than a generic {@code set_state}) so the registry's public surface documents the state machine's
 * real transitions instead of an arbitrary setter — {@link
 * com.gimle.module.lifecycle.ModuleController} is the intended sole caller of the mutating methods;
 * it owns validating whether a transition is legal before calling here.
 */
public final class ModuleRegistry {

  private final Map<ModuleId, Entry> entries = new ConcurrentHashMap<>();

  public synchronized ModuleId register(ModuleArtifact artifact) {
    ModuleId id = artifact.id();
    Entry existing = entries.get(id);
    if (existing != null) {
      if (existing.artifact.sha256().equals(artifact.sha256())) {
        return id; // idempotent re-install of the identical artifact
      }
      throw new GimleManifestException(
          "module " + id + " is already installed with different content (sha256 mismatch)");
    }
    entries.put(id, new Entry(artifact, ModuleState.INSTALLED, null, null));
    return id;
  }

  public List<ModuleId> idsByName(String name) {
    return entries.keySet().stream()
        .filter(id -> id.name().equals(name))
        .sorted(Comparator.comparing(ModuleId::version))
        .toList();
  }

  public boolean contains(ModuleId id) {
    return entries.containsKey(id);
  }

  public ModuleState state(ModuleId id) {
    return entry(id).state;
  }

  public ModuleArtifact artifact(ModuleId id) {
    return entry(id).artifact;
  }

  public Optional<ModuleWiring> wiring(ModuleId id) {
    return Optional.ofNullable(entry(id).wiring);
  }

  public Optional<ModuleLayerHandle> layerHandle(ModuleId id) {
    return Optional.ofNullable(entry(id).layerHandle);
  }

  public synchronized void markResolved(
      ModuleId id, ModuleWiring wiring, ModuleLayerHandle layerHandle) {
    Entry current = entry(id);
    entries.put(id, new Entry(current.artifact, ModuleState.RESOLVED, wiring, layerHandle));
  }

  public synchronized void markStarting(ModuleId id) {
    replaceState(id, ModuleState.STARTING);
  }

  public synchronized void markActive(ModuleId id) {
    replaceState(id, ModuleState.ACTIVE);
  }

  public synchronized void markStopping(ModuleId id) {
    replaceState(id, ModuleState.STOPPING);
  }

  public synchronized void markFailed(ModuleId id) {
    replaceState(id, ModuleState.FAILED);
  }

  /**
   * Terminal: the module ceases to exist in the registry, per the UNINSTALLED -&gt; [*] transition.
   */
  public synchronized void remove(ModuleId id) {
    entry(id); // validate it exists, for a clear error on double-removal
    entries.remove(id);
  }

  private void replaceState(ModuleId id, ModuleState newState) {
    Entry current = entry(id);
    entries.put(id, new Entry(current.artifact, newState, current.wiring, current.layerHandle));
  }

  private Entry entry(ModuleId id) {
    Entry entry = entries.get(id);
    if (entry == null) {
      throw new NoSuchElementException("module not registered: " + id);
    }
    return entry;
  }

  private record Entry(
      ModuleArtifact artifact,
      ModuleState state,
      ModuleWiring wiring,
      ModuleLayerHandle layerHandle) {}
}
