package com.gimle.agent;

import com.gimle.core.protocol.ControlMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-fetches every supervised instance's config and secret values on a fixed interval and keeps
 * each running instance's view of them current -- closing the gap where config and secrets were
 * delivered exactly once at instance start and a later change (or a rotated secret version) never
 * reached a running instance. A module observes the update on its very next {@code
 * ModuleContext.config(key)} read, or immediately through a {@code ModuleContext.onConfigChange}
 * listener. Same level-triggered poll-and-relay shape as {@link NetworkPolicyRelay}, and in the
 * same package for the same reason: relaying reads {@link SupervisedInstance#connection} directly.
 *
 * <p>Two messages per tick, together covering both directions a value can move. A changed value
 * goes out as {@code ControlMessage.ConfigDelivered}, tracked per instance against what this relay
 * last sent so an unchanged value isn't re-sent; deletions ride the second message, {@code
 * ControlMessage.ConfigKeysRetained}, which names the full set of keys that still exist and which
 * the worker applies by dropping everything else. That set is re-asserted every tick rather than
 * being a one-shot "key X was removed" event: a removal event a disconnected worker missed would
 * leave a revoked value readable forever, whereas re-asserting the whole set converges from any
 * starting state, including a worker that reconnected mid-deletion.
 *
 * <p>Retention is computed per <em>connection</em>, not per instance, because a Tier 1 worker hosts
 * several density-packed instances behind one channel and one worker-wide config map -- asserting a
 * single instance's key set there would wrongly retract the keys of every other instance sharing
 * that worker. A connection whose instances didn't all fetch cleanly this tick is skipped entirely
 * rather than being sent a set missing the keys of whichever fetch failed, which would retract live
 * values over a transient control-plane blip; the next successful tick asserts it again.
 */
final class ConfigRelay implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ConfigRelay.class);

  /**
   * One instance's current config+secret fetch, exactly what initial delivery fetches for it --
   * abstracted so tests can drive {@link #pollOnce()} without a live control plane or Fafnir.
   */
  interface ConfigEntrySource {
    List<AgentMain.ConfigValue> fetchFor(SupervisedInstance instance)
        throws IOException, InterruptedException;
  }

  /** What one worker connection's instances collectively still hold, accumulated over a tick. */
  private static final class ConnectionKeys {
    private final Set<String> keys = new LinkedHashSet<>();
    private boolean everyFetchSucceeded = true;
  }

  private final ConfigEntrySource source;
  private final Duration pollInterval;
  private final Map<String, SupervisedInstance> supervised;
  private final Map<String, Map<String, String>> lastDeliveredByInstance =
      new ConcurrentHashMap<>();
  private volatile ScheduledExecutorService scheduler;

  ConfigRelay(
      ConfigEntrySource source, Duration pollInterval, Map<String, SupervisedInstance> supervised) {
    this.source = source;
    this.pollInterval = pollInterval;
    this.supervised = supervised;
  }

  /**
   * Schedules polls every {@code pollInterval}, starting one interval from now -- initial delivery
   * has already happened synchronously during each instance's own install sequence, so there is
   * nothing for an immediate first tick to add.
   */
  synchronized void start() {
    ScheduledExecutorService newScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("gimle-config-relay").unstarted(r));
    newScheduler.scheduleAtFixedRate(
        this::pollSafely, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    this.scheduler = newScheduler;
  }

  private void pollSafely() {
    try {
      pollOnce();
    } catch (RuntimeException e) {
      log.warn("config relay tick failed unexpectedly: {}", e.getMessage(), e);
    }
  }

  /**
   * One relay pass over every currently supervised, currently connected instance. Callable directly
   * so tests drive it deterministically -- the same reason {@link NetworkPolicyRelay#pollOnce} is.
   * A fetch failure for one instance skips only that instance this tick; bookkeeping for instances
   * that no longer exist is dropped so a torn-down deployment's map entries don't accumulate
   * forever.
   */
  synchronized void pollOnce() {
    Set<String> liveKeys = new LinkedHashSet<>();
    // Identity-keyed: WorkerConnection has no value equality, and identity is exactly the grouping
    // wanted -- two instances share a retention assertion precisely when they share one channel.
    Map<WorkerConnection, ConnectionKeys> retentionByConnection = new IdentityHashMap<>();
    for (Map.Entry<String, SupervisedInstance> entry : supervised.entrySet()) {
      String instanceKey = entry.getKey();
      liveKeys.add(instanceKey);
      SupervisedInstance instance = entry.getValue();
      WorkerConnection connection = instance.connection;
      if (connection == null) {
        continue;
      }
      ConnectionKeys retention =
          retentionByConnection.computeIfAbsent(connection, c -> new ConnectionKeys());
      List<AgentMain.ConfigValue> current;
      try {
        current = source.fetchFor(instance);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (IOException | RuntimeException e) {
        log.warn("config relay failed to fetch for instance {}: {}", instanceKey, e.getMessage());
        retention.everyFetchSucceeded = false;
        continue;
      }
      Map<String, String> lastDelivered =
          lastDeliveredByInstance.computeIfAbsent(instanceKey, key -> new ConcurrentHashMap<>());
      Set<String> currentKeys = new LinkedHashSet<>();
      for (AgentMain.ConfigValue value : current) {
        currentKeys.add(value.key());
        retention.keys.add(value.key());
        if (Objects.equals(lastDelivered.get(value.key()), value.value())) {
          continue;
        }
        try {
          connection.send(
              new ControlMessage.ConfigDelivered(value.key(), value.value(), value.wasEncrypted()));
          lastDelivered.put(value.key(), value.value());
        } catch (IOException e) {
          log.warn(
              "config relay failed to deliver {} to instance {}: {}",
              value.key(),
              instanceKey,
              e.getMessage());
          retention.everyFetchSucceeded = false;
          break; // the connection is likely gone; the next tick retries whatever didn't land
        }
      }
      // Forgetting a vanished key here is what makes a delete-then-recreate cycle deliver again:
      // otherwise the recreated value would look unchanged against stale bookkeeping and never be
      // re-sent, leaving the worker permanently missing a key that does exist upstream.
      lastDelivered.keySet().retainAll(currentKeys);
    }
    for (Map.Entry<WorkerConnection, ConnectionKeys> entry : retentionByConnection.entrySet()) {
      ConnectionKeys retention = entry.getValue();
      if (!retention.everyFetchSucceeded) {
        continue;
      }
      try {
        entry.getKey().send(new ControlMessage.ConfigKeysRetained(new ArrayList<>(retention.keys)));
      } catch (IOException e) {
        log.warn("config relay failed to assert retained keys to a worker: {}", e.getMessage());
      }
    }
    lastDeliveredByInstance.keySet().retainAll(liveKeys);
  }

  @Override
  public synchronized void close() {
    ScheduledExecutorService current = scheduler;
    if (current != null) {
      current.shutdownNow();
    }
  }
}
