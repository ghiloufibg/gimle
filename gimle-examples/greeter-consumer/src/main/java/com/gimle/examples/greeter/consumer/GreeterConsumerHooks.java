package com.gimle.examples.greeter.consumer;

import com.gimle.examples.greeter.Greeter;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Looks up and calls greeter-provider's {@link Greeter} on a background virtual thread, never
 * inline from {@link #onStart}: {@code onStart} runs synchronously on the worker's single
 * control-channel receive loop, the same loop that delivers the {@code CatalogUpdate} messages
 * service discovery depends on -- blocking there while waiting for that same catalog to populate
 * would deadlock against itself.
 *
 * <p>Re-resolves {@link Greeter} on every iteration rather than looking it up once and reusing the
 * resulting proxy: the proxy is bound to one specific {@code ServiceEndpoint} at lookup time, so
 * caching it across a provider redeploy/restart (a new worker, a new fabric address) would leave
 * this consumer permanently stuck calling a dead endpoint instead of naturally picking up the
 * replacement the next time it asks the registry. Retries every {@link #CALL_INTERVAL} regardless
 * of outcome -- both a live, visible heartbeat in the console's Logs screen and a deterministic
 * first-success signal for smoke tests.
 *
 * <p>{@code onStart} runs with this instance's MDC tags already applied (the worker tags every hook
 * invocation -- see {@code WorkerMain#runCommand}), but MDC is thread-local and a spawned thread
 * doesn't inherit it automatically, so the background thread would otherwise log as untagged
 * PLATFORM output instead of this instance's own APPLICATION log. Captured once here and restored
 * at the top of {@link #callLoop}.
 */
public final class GreeterConsumerHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(GreeterConsumerHooks.class);
  private static final Duration CALL_INTERVAL = Duration.ofSeconds(5);

  static final AtomicBoolean everSucceeded = new AtomicBoolean(false);
  private volatile boolean stopped;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    Map<String, String> mdcTags = MDC.getCopyOfContextMap();
    Thread.ofVirtual().name("greeter-consumer-caller").start(() -> callLoop(ctx, mdcTags));
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
      Optional<Greeter> greeter = ctx.lookupService(Greeter.class);
      if (greeter.isEmpty()) {
        log.warn("no Greeter available yet; greeter-provider may not have registered it yet");
        return;
      }
      String reply = greeter.get().greet("Gimlé");
      everSucceeded.set(true);
      log.info("{}", reply);
    } catch (RuntimeException e) {
      // No member currently exports Greeter (GimleClusterException), or the resolved endpoint
      // failed (its provider redeployed/restarted since) -- both expected, transient conditions
      // that clear up on their own once retried against a fresh lookup.
      log.warn("call to Greeter failed: {}", e.getMessage());
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
