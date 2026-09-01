package com.gimle.agent;

import com.gimle.agent.networkpolicy.NetworkPolicySnapshot;
import com.gimle.agent.networkpolicy.NetworkPolicySource;
import com.gimle.core.protocol.ControlMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls a {@link NetworkPolicySource} (the control plane's own {@code GET /networkpolicies}, in
 * production) on a fixed interval and relays the full current {@code NetworkPolicySpec} set -- both
 * tenant-wide and per-deployment-scoped -- down to every worker this agent currently supervises,
 * over the same per-instance control channel {@link WorkerConnection} already carries {@code
 * CatalogUpdate}/{@code ConfigDelivered} traffic on -- a worker has no outbound network identity of
 * its own to poll the control plane directly (see {@code ModuleContext}'s own javadoc). Deciding
 * which relayed rules actually apply to which locally-hosted module is the receiving {@code
 * FabricServer}'s job, not this relay's -- it always ships the full set unfiltered. Each relayed
 * message also carries the tenants whose declared posture closes them to traffic no rule covers,
 * since a worker can only decide an uncovered call correctly while holding both halves at once. Level-triggered
 * like {@code BifrostProxy}: each poll relays whatever the source reports right now in full, not a
 * diff against a remembered previous poll, so a missed or failed tick self-heals on the next one
 * instead of leaving a worker's cached policy set stale.
 *
 * <p>Lives directly in {@code com.gimle.agent} (unlike Bifrost's own poller, which sits in its own
 * {@code bifrost} subpackage) because relaying requires reading {@link
 * SupervisedInstance#connection}, package-private state {@code AgentMain} itself already reaches
 * into for the identical {@code relayCatalogDelta} purpose -- {@code NetworkPolicySource}/{@code
 * HttpNetworkPolicySource} still get their own subpackage since polling itself needs no such
 * access.
 */
final class NetworkPolicyRelay implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(NetworkPolicyRelay.class);

  private final NetworkPolicySource source;
  private final Duration pollInterval;
  private final Map<String, SupervisedInstance> supervised;
  private volatile ScheduledExecutorService scheduler;

  NetworkPolicyRelay(
      NetworkPolicySource source,
      Duration pollInterval,
      Map<String, SupervisedInstance> supervised) {
    this.source = source;
    this.pollInterval = pollInterval;
    this.supervised = supervised;
  }

  /** Runs one poll immediately, then schedules subsequent polls every {@code pollInterval}. */
  synchronized void start() {
    pollOnce();
    ScheduledExecutorService newScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-networkpolicy-poller").unstarted(r));
    newScheduler.scheduleAtFixedRate(
        this::pollSafely, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    this.scheduler = newScheduler;
  }

  private void pollSafely() {
    try {
      pollOnce();
    } catch (RuntimeException e) {
      log.warn("network policy poll tick failed unexpectedly: {}", e.getMessage(), e);
    }
  }

  /**
   * Fetches the current policy set and relays it, unchanged, to every currently connected
   * supervised worker. Public and callable directly (not only from {@link #start()}'s own
   * scheduler) so tests can drive a poll deterministically instead of racing a wall-clock timer --
   * the same reason {@code BifrostProxy#pollOnce} is public.
   */
  synchronized void pollOnce() {
    NetworkPolicySnapshot snapshot;
    try {
      snapshot = source.fetchPolicies();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("failed to poll network policies: {}", e.getMessage());
      return;
    }
    ControlMessage update =
        new ControlMessage.NetworkPoliciesUpdated(
            snapshot.rules(), snapshot.denyByDefaultTenantIds());
    for (SupervisedInstance instance : supervised.values()) {
      WorkerConnection connection = instance.connection;
      if (connection != null) {
        try {
          connection.send(update);
        } catch (IOException e) {
          log.warn("failed to relay network policies to a supervised worker: {}", e.getMessage());
        }
      }
    }
  }

  @Override
  public synchronized void close() {
    ScheduledExecutorService current = scheduler;
    if (current != null) {
      current.shutdownNow();
    }
  }
}
