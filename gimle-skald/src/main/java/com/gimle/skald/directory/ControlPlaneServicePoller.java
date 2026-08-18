package com.gimle.skald.directory;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically rebuilds a {@link CachingServiceDirectory} from a {@link ServiceCatalogClient} on a
 * fixed interval -- the one thing that turns the control plane's Service/endpoint API into
 * something {@link com.gimle.skald.SkaldServer} can answer queries from without a network call on
 * every request. Runs on its own virtual thread, the same lightweight-background-work idiom {@code
 * AndvariPeerSync} already establishes for this shape of periodic sync.
 */
public final class ControlPlaneServicePoller implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ControlPlaneServicePoller.class);

  private final ServiceCatalogClient client;
  private final CachingServiceDirectory directory;
  private final ScheduledExecutorService scheduler;

  public ControlPlaneServicePoller(
      ServiceCatalogClient client, CachingServiceDirectory directory, Duration pollInterval) {
    this.client = client;
    this.directory = directory;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-skald-poll").unstarted(r));
    scheduler.scheduleAtFixedRate(
        this::pollQuietly, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void pollQuietly() {
    try {
      poll();
    } catch (RuntimeException e) {
      log.warn("service catalog poll failed: {}", e.getMessage());
    }
  }

  /**
   * Package-visible for direct testing without waiting on the scheduler's own interval. Returns the
   * number of services resolved into the cache this pass.
   */
  int poll() {
    List<String> names;
    try {
      names = client.listServiceNames();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      // Leave the existing cache exactly as it was on a transient control-plane failure --
      // flipping every cached name to NXDOMAIN because one poll tick failed would make Skald
      // strictly less available than just answering with the stale-but-still-correct data it
      // already had.
      log.warn("failed to list services from control plane: {}", e.getMessage());
      return 0;
    }
    Map<String, List<String>> next = new LinkedHashMap<>();
    for (String name : names) {
      try {
        client
            .fetchEndpoints(name)
            .filter(endpoints -> !endpoints.endpointHosts().isEmpty())
            .ifPresent(endpoints -> next.put(name, endpoints.endpointHosts()));
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        log.warn("failed to fetch endpoints for service {}: {}", name, e.getMessage());
      }
    }
    directory.replaceAll(next);
    return next.size();
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
