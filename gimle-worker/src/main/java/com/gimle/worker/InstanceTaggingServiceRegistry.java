package com.gimle.worker;

import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.module.ModuleId;
import com.gimle.module.lifecycle.ServiceRegistry;
import java.util.Map;
import java.util.Optional;

/**
 * Wraps a delegate {@link ServiceRegistry}, tagging each registered service instance with an MDC
 * dynamic proxy (log-explorer-design.md §3) at the moment it's registered -- the single choke point
 * that covers both same-worker direct calls (a plain reference returned from {@code lookup()}) and
 * {@code FabricServer}'s local-invoke path, since both ultimately call through this same registered
 * reference. A module whose identity isn't known yet (the agent hasn't wired instance identity
 * through, or {@code identities} has no entry for its {@link ModuleId}) is registered untagged
 * rather than failing -- matches this codebase's "degrade, don't fail, over instrumentation"
 * posture elsewhere (e.g. {@code ThreadNameJfrAttributor}'s JFR-unavailable fallback).
 */
public final class InstanceTaggingServiceRegistry implements ServiceRegistry {

  private final ServiceRegistry delegate;
  private final InstanceIdentityRegistry identities;

  public InstanceTaggingServiceRegistry(
      ServiceRegistry delegate, InstanceIdentityRegistry identities) {
    this.delegate = delegate;
    this.identities = identities;
  }

  @Override
  public <T> void register(ModuleId owner, Class<T> iface, T instance) {
    T toRegister =
        identities
            .lookup(owner)
            .map(identity -> tag(owner, iface, instance, identity))
            .orElse(instance);
    delegate.register(owner, iface, toRegister);
  }

  private <T> T tag(ModuleId owner, Class<T> iface, T instance, InstanceIdentity identity) {
    Map<String, String> tags =
        InstanceMdcContext.tagsFor(
            identity.deploymentName(),
            identity.instanceIndex(),
            owner.name(),
            owner.version().toString(),
            identity.tenantId().orElse(null));
    return InstanceMdcContext.tagProxy(iface, instance, tags);
  }

  @Override
  public <T> Optional<T> lookup(Class<T> iface) {
    return delegate.lookup(iface);
  }

  @Override
  public void markUnready(ModuleId owner) {
    delegate.markUnready(owner);
  }

  @Override
  public void remove(ModuleId owner) {
    delegate.remove(owner);
    identities.remove(owner);
  }
}
