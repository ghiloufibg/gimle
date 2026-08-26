package com.gimle.agent;

import com.gimle.core.protocol.ControlMessage;
import java.io.IOException;
import java.time.Duration;
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
 * Re-fetches every supervised instance's config and secret values on a fixed interval and re-sends
 * {@code ControlMessage.ConfigDelivered} for any value that changed since this relay last sent it
 * to that instance -- closing the gap where config and secrets were delivered exactly once at
 * instance start and a later change (or a rotated secret version) never reached a running instance.
 * A module observes the update on its very next {@code ModuleContext.config(key)} read, since
 * delivery just overwrites the worker's shared live config map. Same level-triggered poll-and-relay
 * shape as {@link NetworkPolicyRelay}, and in the same package for the same reason: relaying reads
 * {@link SupervisedInstance#connection} directly.
 *
 * <p>Only creates and updates: a key deleted upstream is deliberately never retracted from a
 * running instance, since the control channel has no removal message and a module that already read
 * the value holds it anyway -- the instance's next restart starts from the current set. Change
 * detection is per instance, keyed by the supervised map's own instance key, so two instances of
 * one tenant each get their own delivery bookkeeping (their {@code configMapRefs}/{@code
 * secretMapRefs} can differ). A first tick after this relay starts re-sends every value once per
 * instance -- harmless, delivery is idempotent overwriting.
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
    for (Map.Entry<String, SupervisedInstance> entry : supervised.entrySet()) {
      String instanceKey = entry.getKey();
      liveKeys.add(instanceKey);
      SupervisedInstance instance = entry.getValue();
      WorkerConnection connection = instance.connection;
      if (connection == null) {
        continue;
      }
      List<AgentMain.ConfigValue> current;
      try {
        current = source.fetchFor(instance);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (IOException | RuntimeException e) {
        log.warn("config relay failed to fetch for instance {}: {}", instanceKey, e.getMessage());
        continue;
      }
      Map<String, String> lastDelivered =
          lastDeliveredByInstance.computeIfAbsent(instanceKey, key -> new ConcurrentHashMap<>());
      for (AgentMain.ConfigValue value : current) {
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
          break; // the connection is likely gone; the next tick retries whatever didn't land
        }
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
