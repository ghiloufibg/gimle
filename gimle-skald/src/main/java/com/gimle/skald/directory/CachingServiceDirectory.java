package com.gimle.skald.directory;

import java.util.List;
import java.util.Map;

/**
 * The in-memory cache {@link ControlPlaneServicePoller} refreshes and {@link
 * com.gimle.skald.SkaldServer} reads on every query. A single {@code volatile} map swap on refresh
 * means a query never blocks on (or observes a half-updated view of) a poll in progress. No
 * per-name rotation state: every answer carries the full endpoint set (see {@link
 * ServiceDirectory#resolveAll}), so there is nothing to rotate.
 */
public final class CachingServiceDirectory implements ServiceDirectory {

  private volatile Map<String, List<HostPort>> endpointsByName = Map.of();

  /**
   * Replaces the entire cache with {@code next} (qualified service name to its live endpoints).
   * Called once per successful poll cycle, never merged incrementally -- the control plane's own
   * listing is always treated as the complete, current set of services.
   */
  public void replaceAll(Map<String, List<HostPort>> next) {
    this.endpointsByName = Map.copyOf(next);
  }

  @Override
  public List<HostPort> resolveAll(String qualifiedServiceName) {
    List<HostPort> endpoints = endpointsByName.get(qualifiedServiceName);
    return endpoints == null ? List.of() : endpoints;
  }
}
