package com.gimle.module.resolve;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleInstanceId;
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

  // Keyed by instance, not by artifact: two replicas of one deployment sharing a worker install
  // the same artifact, and each needs its own layer, its own lifecycle state and its own wiring.
  private final Map<ModuleInstanceId, Entry> entries = new ConcurrentHashMap<>();

  /** Registers a module with no deployment identity -- see {@link ModuleInstanceId}. */
  public ModuleInstanceId register(ModuleArtifact artifact) {
    return register(artifact, "");
  }

  public synchronized ModuleInstanceId register(ModuleArtifact artifact, String instanceKey) {
    ModuleInstanceId id = new ModuleInstanceId(artifact.id(), instanceKey);
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

  /**
   * Every installed instance whose module is named {@code name}, ordered by version -- the input to
   * dependency resolution, which matches a required range against what this worker actually hosts.
   * Two instances of one version are both listed: resolution picks a version, and either instance
   * of it satisfies the requirement equally.
   */
  public List<ModuleInstanceId> idsByName(String name) {
    return entries.keySet().stream()
        .filter(id -> id.name().equals(name))
        .sorted(Comparator.comparing(ModuleInstanceId::version))
        .toList();
  }

  public boolean contains(ModuleInstanceId id) {
    return entries.containsKey(id);
  }

  public ModuleState state(ModuleInstanceId id) {
    return entry(id).state;
  }

  public ModuleArtifact artifact(ModuleInstanceId id) {
    return entry(id).artifact;
  }

  public Optional<ModuleWiring> wiring(ModuleInstanceId id) {
    return Optional.ofNullable(entry(id).wiring);
  }

  public Optional<ModuleLayerHandle> layerHandle(ModuleInstanceId id) {
    return Optional.ofNullable(entry(id).layerHandle);
  }

  public synchronized void markResolved(
      ModuleInstanceId id, ModuleWiring wiring, ModuleLayerHandle layerHandle) {
    Entry current = entry(id);
    entries.put(id, new Entry(current.artifact, ModuleState.RESOLVED, wiring, layerHandle));
  }

  public synchronized void markStarting(ModuleInstanceId id) {
    replaceState(id, ModuleState.STARTING);
  }

  public synchronized void markActive(ModuleInstanceId id) {
    replaceState(id, ModuleState.ACTIVE);
  }

  public synchronized void markStopping(ModuleInstanceId id) {
    replaceState(id, ModuleState.STOPPING);
  }

  public synchronized void markFailed(ModuleInstanceId id) {
    replaceState(id, ModuleState.FAILED);
  }

  public synchronized void markCompleted(ModuleInstanceId id) {
    replaceState(id, ModuleState.COMPLETED);
  }

  /**
   * Terminal: the module ceases to exist in the registry, per the UNINSTALLED -&gt; [*] transition.
   */
  public synchronized void remove(ModuleInstanceId id) {
    entry(id); // validate it exists, for a clear error on double-removal
    entries.remove(id);
  }

  private void replaceState(ModuleInstanceId id, ModuleState newState) {
    Entry current = entry(id);
    entries.put(id, new Entry(current.artifact, newState, current.wiring, current.layerHandle));
  }

  private Entry entry(ModuleInstanceId id) {
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
