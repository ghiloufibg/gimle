package com.gimle.worker;

import com.gimle.core.module.ModuleId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a hosted module's {@link ModuleId} to the {@link InstanceIdentity} the agent reported for it
 * at install time (design §3). Populated by {@code WorkerMain} when handling {@code
 * ControlMessage.InstallModule}; consulted by {@code WorkerRuntime} (probe-loop MDC tagging) and
 * {@link InstanceTaggingServiceRegistry} (request-dispatch MDC tagging); cleared by {@link
 * InstanceTaggingServiceRegistry#remove}, which every uninstall path already calls.
 */
public final class InstanceIdentityRegistry {

  private final Map<ModuleId, InstanceIdentity> identities = new ConcurrentHashMap<>();

  public void register(ModuleId id, InstanceIdentity identity) {
    identities.put(id, identity);
  }

  public Optional<InstanceIdentity> lookup(ModuleId id) {
    return Optional.ofNullable(identities.get(id));
  }

  public void remove(ModuleId id) {
    identities.remove(id);
  }
}
