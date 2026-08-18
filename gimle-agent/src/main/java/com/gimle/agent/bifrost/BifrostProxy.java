package com.gimle.agent.bifrost;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gimlé's per-node service proxy, the kube-proxy analogue: polls a {@link ServiceSource} on a fixed
 * interval and keeps one loopback {@link ServiceListener} bound per currently-known service,
 * closing listeners for services that disappeared and binding new ones for services that appeared.
 * Level-triggered like the control plane's own reconcilers -- each poll recomputes the desired
 * listener set from scratch off whatever {@link ServiceSource} reports right now, rather than
 * diffing against a remembered previous poll, so a missed or failed tick self-heals on the next one
 * instead of leaving stale state behind.
 */
public final class BifrostProxy implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(BifrostProxy.class);

  private final ServiceSource source;
  private final Duration pollInterval;
  private final Map<String, ServiceListener> listeners = new ConcurrentHashMap<>();
  private volatile ScheduledExecutorService scheduler;

  public BifrostProxy(ServiceSource source, Duration pollInterval) {
    this.source = source;
    this.pollInterval = pollInterval;
  }

  /** Runs one poll immediately, then schedules subsequent polls every {@code pollInterval}. */
  public synchronized void start() {
    pollOnce();
    ScheduledExecutorService newScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-bifrost-poller").unstarted(r));
    newScheduler.scheduleAtFixedRate(
        this::pollSafely, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    this.scheduler = newScheduler;
  }

  private void pollSafely() {
    try {
      pollOnce();
    } catch (RuntimeException e) {
      log.warn("bifrost poll tick failed unexpectedly: {}", e.getMessage(), e);
    }
  }

  /**
   * One reconciliation pass: fetches the current service list and endpoint set for each, binds a
   * listener for every service not yet bound, refreshes the endpoint set of every listener that
   * already exists, and closes listeners for services no longer present. Public and callable
   * directly (not only from {@link #start()}'s own scheduler) so tests can drive reconciliation
   * deterministically instead of racing a wall-clock timer.
   */
  public synchronized void pollOnce() {
    List<String> currentNames;
    try {
      currentNames = source.listServiceNames();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("bifrost failed to list services: {}", e.getMessage());
      return;
    }
    Set<String> currentSet = new LinkedHashSet<>(currentNames);

    for (String existingName : List.copyOf(listeners.keySet())) {
      if (!currentSet.contains(existingName)) {
        ServiceListener removed = listeners.remove(existingName);
        if (removed != null) {
          removed.close();
          log.info("bifrost closed listener for removed service {}", existingName);
        }
      }
    }

    for (String name : currentNames) {
      Optional<ServiceEndpoints> spec;
      try {
        spec = source.fetchEndpoints(name);
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        log.warn("bifrost failed to fetch endpoints for service {}: {}", name, e.getMessage());
        continue;
      }
      if (spec.isEmpty()) {
        continue;
      }
      ServiceEndpoints endpoints = spec.get();
      ServiceListener listener = listeners.get(name);
      if (listener == null) {
        try {
          listener =
              new ServiceListener(name, LoopbackAddressAllocator.allocate(name), endpoints.port());
        } catch (IOException e) {
          log.warn("bifrost failed to bind listener for service {}: {}", name, e.getMessage());
          continue;
        }
        listeners.put(name, listener);
        log.info("bifrost bound service {} at {}", name, listener.boundAddress());
      }
      listener.updateEndpoints(endpoints.endpoints());
    }
  }

  /** The synthesized ClusterIP:port a caller dials for {@code serviceName}, if currently bound. */
  public Optional<InetSocketAddress> boundAddressFor(String serviceName) {
    ServiceListener listener = listeners.get(serviceName);
    return listener == null ? Optional.empty() : Optional.of(listener.boundAddress());
  }

  @Override
  public synchronized void close() {
    ScheduledExecutorService current = scheduler;
    if (current != null) {
      current.shutdownNow();
    }
    for (ServiceListener listener : listeners.values()) {
      listener.close();
    }
    listeners.clear();
  }
}
