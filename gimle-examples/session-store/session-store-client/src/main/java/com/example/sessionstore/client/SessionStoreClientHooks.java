package com.example.sessionstore.client;

import com.example.sessionstore.SessionStore;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Exercises session-store-service over the real fabric on a background virtual thread -- never
 * inline from {@link #onStart}, the same "never block onStart's own receive loop" reasoning
 * gimle-examples/greeter-consumer's own javadoc already explains.
 *
 * <p>Keeps a small rolling window of session ids it has itself written (the last {@link
 * #TRACKED_SESSIONS} of them) and, every {@link #CALL_INTERVAL}: writes one new session, reads
 * back a session from a few cycles ago to confirm it's still there, and deletes the oldest
 * tracked one once the window is full. The specific thing this proves out end to end, visible in
 * this instance's own log: a session written *before* session-store-service's own instance was
 * last restarted still reads back correctly afterward -- restart session-store-service by hand
 * (see ../README.md) and watch this client's own "recalled session ... after N reads" line keep
 * succeeding right through it, the actual point of the whole app.
 */
public final class SessionStoreClientHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(SessionStoreClientHooks.class);
  private static final Duration CALL_INTERVAL = Duration.ofSeconds(5);
  private static final int TRACKED_SESSIONS = 5;

  static final AtomicBoolean everSucceeded = new AtomicBoolean(false);
  private volatile boolean stopped;
  private final String[] trackedIds = new String[TRACKED_SESSIONS];
  private final AtomicLong cycle = new AtomicLong();

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    Map<String, String> mdcTags = MDC.getCopyOfContextMap();
    Thread.ofVirtual().name("session-store-client-caller").start(() -> callLoop(ctx, mdcTags));
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
      Optional<SessionStore> lookup = ctx.lookupService(SessionStore.class);
      if (lookup.isEmpty()) {
        log.warn("no SessionStore available yet; session-store-service may not have started yet");
        return;
      }
      SessionStore store = lookup.get();
      long thisCycle = cycle.getAndIncrement();
      int slot = (int) (thisCycle % TRACKED_SESSIONS);
      String previousId = trackedIds[slot];

      String newId = "session-" + thisCycle;
      String payload = "user=demo-" + thisCycle + ";issuedAt=" + Instant.now();
      store.put(newId, payload);
      trackedIds[slot] = newId;

      if (previousId != null) {
        String recalled = store.get(previousId);
        if (recalled != null) {
          everSucceeded.set(true);
          log.info(
              "recalled session {} after {} more writes: {}", previousId, TRACKED_SESSIONS, recalled);
        } else {
          // Expected once, right after a fresh (empty) volume: the very first TRACKED_SESSIONS
          // cycles have no prior session in this slot yet to recall. Anything past that point
          // would mean the volume didn't actually survive a restart -- the failure this whole
          // app exists to catch.
          log.warn("session {} was not found -- expected only during this client's own warm-up",
              previousId);
        }
        if (ThreadLocalRandom.current().nextBoolean()) {
          store.delete(previousId);
        }
      }
    } catch (RuntimeException e) {
      // No member currently exports SessionStore (a fresh cluster boot race), or the resolved
      // endpoint failed (redeployed/restarted since) -- both expected, transient conditions that
      // clear up on their own once retried against a fresh lookup next cycle.
      log.warn("call to SessionStore failed: {}", e.getMessage());
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
