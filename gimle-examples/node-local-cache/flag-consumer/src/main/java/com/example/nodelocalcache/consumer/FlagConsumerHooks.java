package com.example.nodelocalcache.consumer;

import com.example.nodelocalcache.FeatureFlagCache;
import com.example.nodelocalcache.FlagAnswer;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Looks up and calls local-flag-cache's {@link FeatureFlagCache} on a background virtual thread,
 * re-resolving it fresh every call -- the same reasoning gimle-examples/greeter-consumer's own
 * javadoc gives (never getting stuck on a stale endpoint), which here is also exactly what lets
 * {@code gimle-fabric}'s own same-machine-first locality preference actually apply on every call
 * rather than just the first one.
 *
 * <p>The whole point of this class: it tracks the {@code replicaId} the previous call's answer
 * carried in {@link #lastReplicaId}, and logs a {@code WARN} the moment a call's answer carries a
 * *different* one -- meaning this call crossed to another node's cache replica instead of landing
 * on this instance's own co-located one. In steady state on a real multi-node cluster, that WARN
 * should never fire: every call should keep seeing the same replicaId, logged at INFO as
 * confirmation. See ../README.md for what it means if it does fire, and why that's an honestly
 * reported possibility rather than something this app hides.
 */
public final class FlagConsumerHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(FlagConsumerHooks.class);
  private static final Duration CALL_INTERVAL = Duration.ofSeconds(5);
  private static final String FLAG_NAME = "dark-mode";

  static final AtomicBoolean everSucceeded = new AtomicBoolean(false);
  private final AtomicReference<String> lastReplicaId = new AtomicReference<>();
  private volatile boolean stopped;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    Map<String, String> mdcTags = MDC.getCopyOfContextMap();
    Thread.ofVirtual().name("flag-consumer-caller").start(() -> callLoop(ctx, mdcTags));
  }

  @Override
  public void onStop(ModuleContext ctx) {
    stopped = true;
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  private void callLoop(ModuleContext ctx, Map<String, String> mdcTags) {
    if (mdcTags != null) {
      MDC.setContextMap(mdcTags);
    }
    while (!stopped) {
      callOnce(ctx);
      sleep(CALL_INTERVAL);
    }
  }

  private void callOnce(ModuleContext ctx) {
    try {
      Optional<FeatureFlagCache> cache = ctx.lookupService(FeatureFlagCache.class);
      if (cache.isEmpty()) {
        log.warn("no FeatureFlagCache available yet; local-flag-cache may not have started yet");
        return;
      }
      FlagAnswer answer = cache.get().get(FLAG_NAME);
      everSucceeded.set(true);
      String previous = lastReplicaId.getAndSet(answer.replicaId());
      if (previous == null || previous.equals(answer.replicaId())) {
        log.info(
            "{}={} (answered by replica {}, same as last call)", FLAG_NAME, answer.value(),
            answer.replicaId());
      } else {
        log.warn(
            "{}={} answered by replica {}, DIFFERENT from the previous call's {} -- this call did"
                + " not land on this consumer's own co-located cache replica",
            FLAG_NAME,
            answer.value(),
            answer.replicaId(),
            previous);
      }
    } catch (RuntimeException e) {
      // No member currently exports FeatureFlagCache (a fresh cluster boot race), or the resolved
      // endpoint failed (redeployed/restarted since) -- both expected, transient conditions that
      // clear up on their own once retried against a fresh lookup next cycle. On this instance's
      // very first call, the boot race is the likely, expected cause -- local-flag-cache reaching
      // ACTIVE and this consumer's own membership view of that export catching up are two
      // separate events, and there's no guarantee the second has happened by the time this
      // consumer's own first call fires. Logging that at WARN reads as something broken to a
      // first-time reader when it's actually routine, so it's INFO until a call has ever
      // succeeded; a failure *after* a previous success is a real regression worth WARN.
      if (everSucceeded.get()) {
        log.warn("call to FeatureFlagCache failed: {}", e.getMessage());
      } else {
        log.info(
            "no FeatureFlagCache reachable yet on this consumer's first call -- expected right"
                + " after startup, before this node's membership view of local-flag-cache's own"
                + " export has caught up; retrying in {}: {}",
            CALL_INTERVAL,
            e.getMessage());
      }
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
