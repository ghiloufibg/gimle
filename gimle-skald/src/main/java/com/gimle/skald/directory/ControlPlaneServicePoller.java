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

  /**
   * Below this many consecutive failures, a failed poll is still just a blip -- log it at {@code
   * WARN} the same as before. At or beyond it, escalate to {@code ERROR}: half of {@link
   * com.gimle.skald.SkaldServer}'s own default six-poll SERVFAIL threshold, so an operator watching
   * logs gets a louder signal partway through the grace period, before Skald actually starts
   * refusing queries rather than only once it does.
   */
  private static final int ERROR_ESCALATION_FAILURE_COUNT = 3;

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
    List<ServiceListing> listings;
    try {
      listings = client.listServices();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      // Leave the existing cache exactly as it was on a transient control-plane failure --
      // flipping every cached name to NXDOMAIN because one poll tick failed would make Skald
      // strictly less available than just answering with the stale-but-still-correct data it
      // already had. The failure is still recorded, though: consecutive-failure count and
      // time-since-last-success are what let SkaldServer eventually tell "one missed poll" apart
      // from a sustained outage and react to it.
      directory.recordPollFailure();
      int consecutiveFailures = directory.consecutiveFailures();
      if (consecutiveFailures >= ERROR_ESCALATION_FAILURE_COUNT) {
        log.error(
            "failed to list services from control plane ({} consecutive failure(s)): {}",
            consecutiveFailures,
            e.getMessage());
      } else {
        log.warn(
            "failed to list services from control plane ({} consecutive failure(s)): {}",
            consecutiveFailures,
            e.getMessage());
      }
      return 0;
    }
    Map<String, List<HostPort>> next = new LinkedHashMap<>();
    for (ServiceListing listing : listings) {
      try {
        // fetchEndpoints is given the whole listing, tenant included -- the control plane keys a
        // Service by (tenant, name) -- while the directory cache is keyed by the qualified name a
        // DNS query resolves to. All three spellings differ for a tenant-scoped Service.
        //
        // A Service whose endpoint list came back empty is cached as an empty entry rather than
        // dropped: it was in the catalog listing, so it genuinely exists, and dropping it would
        // make a Service that is merely mid-rollout or scaled to zero indistinguishable from a
        // name nobody ever declared. Only a Service that has disappeared from the catalog between
        // the listing and this fetch (an absent Optional -- the control plane answered 404) is
        // left out, which is exactly right: by then it really is gone.
        client
            .fetchEndpoints(listing)
            .ifPresent(endpoints -> next.put(listing.qualifiedName(), endpoints.endpoints()));
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        log.warn("failed to fetch endpoints for service {}: {}", listing.name(), e.getMessage());
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
