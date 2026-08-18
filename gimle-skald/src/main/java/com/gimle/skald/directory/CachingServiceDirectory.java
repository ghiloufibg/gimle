package com.gimle.skald.directory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The in-memory cache {@link ControlPlaneServicePoller} refreshes and {@link
 * com.gimle.skald.SkaldServer} reads on every query. A single {@code volatile} map swap on refresh
 * means a query never blocks on (or observes a half-updated view of) a poll in progress;
 * round-robin cursors live in a separate map so a refresh that leaves a name's endpoint list
 * unchanged doesn't reset that name's rotation back to the start.
 */
public final class CachingServiceDirectory implements ServiceDirectory {

  private volatile Map<String, List<String>> endpointHostsByName = Map.of();
  private final Map<String, AtomicInteger> roundRobinCursors = new ConcurrentHashMap<>();

  /**
   * Replaces the entire cache with {@code next} (qualified service name to its live endpoint
   * hosts). Called once per successful poll cycle, never merged incrementally -- the control
   * plane's own listing is always treated as the complete, current set of services.
   */
  public void replaceAll(Map<String, List<String>> next) {
    this.endpointHostsByName = Map.copyOf(next);
  }

  @Override
  public Optional<String> resolveOne(String qualifiedServiceName) {
    List<String> hosts = endpointHostsByName.get(qualifiedServiceName);
    if (hosts == null || hosts.isEmpty()) {
      return Optional.empty();
    }
    AtomicInteger cursor =
        roundRobinCursors.computeIfAbsent(qualifiedServiceName, name -> new AtomicInteger());
    int index = Math.floorMod(cursor.getAndIncrement(), hosts.size());
    return Optional.of(hosts.get(index));
  }
}
