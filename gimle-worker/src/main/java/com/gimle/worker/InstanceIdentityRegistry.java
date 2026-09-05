package com.gimle.worker;

import com.gimle.core.module.ModuleInstanceId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a hosted module's {@link ModuleInstanceId} to the {@link InstanceIdentity} the agent
 * reported for it at install time. Populated by {@code WorkerMain} when handling {@code
 * ControlMessage.InstallModule}; consulted by {@code WorkerRuntime} (probe-loop MDC tagging) and
 * {@link InstanceTaggingServiceRegistry} (request-dispatch MDC tagging); cleared by {@link
 * InstanceTaggingServiceRegistry#remove}, which every uninstall path already calls.
 */
public final class InstanceIdentityRegistry {

  private final Map<ModuleInstanceId, InstanceIdentity> identities = new ConcurrentHashMap<>();

  public void register(ModuleInstanceId id, InstanceIdentity identity) {
    identities.put(id, identity);
  }

  public Optional<InstanceIdentity> lookup(ModuleInstanceId id) {
    return Optional.ofNullable(identities.get(id));
  }

  public void remove(ModuleInstanceId id) {
    identities.remove(id);
  }
}
