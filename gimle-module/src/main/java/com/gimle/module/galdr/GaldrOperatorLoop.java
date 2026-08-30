package com.gimle.module.galdr;

import com.gimle.core.protocol.Json;
import com.gimle.module.lifecycle.ModuleContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The operator half of the custom-kind pattern, as a for-loop rather than a framework: a virtual
 * thread that polls the full current set of one kind's resources through {@link
 * ModuleContext#relayControlPlaneRead} every {@code pollInterval} and hands it to the {@link
 * GaldrReconciler} -- full recompute per tick, never a delta, so convergence from any starting
 * state is inherited, not engineered. A failed poll (the agent unreachable, the operator's grant
 * revoked, the control plane restarting) backs the interval off exponentially, up to a small
 * multiple, and resets on the next success; a reconciler-thrown exception fails only that tick.
 * Typically started from a module's {@code onStart} hook and closed from {@code onStop}.
 */
public final class GaldrOperatorLoop implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(GaldrOperatorLoop.class);

  /** A failed poll's interval never grows past this multiple of the configured one. */
  private static final int MAX_BACKOFF_MULTIPLIER = 8;

  private final Thread thread;
  private volatile boolean closed;

  /** Completed reconcile passes (successful poll + reconciler invoked) -- test visibility. */
  private final AtomicLong completedTicks = new AtomicLong();

  private GaldrOperatorLoop(
      ModuleContext context, String kindName, Duration pollInterval, GaldrReconciler reconciler) {
    this.thread =
        Thread.ofVirtual()
            .name("galdr-operator-" + kindName)
            .start(() -> run(context, kindName, pollInterval, reconciler));
  }

  public static GaldrOperatorLoop start(
      ModuleContext context, String kindName, Duration pollInterval, GaldrReconciler reconciler) {
    return new GaldrOperatorLoop(context, kindName, pollInterval, reconciler);
  }

  private void run(
      ModuleContext context, String kindName, Duration pollInterval, GaldrReconciler reconciler) {
    int backoffMultiplier = 1;
    while (!closed) {
      ModuleContext.RelayResult result = context.relayControlPlaneRead("/resources/" + kindName);
      if (result.status() == 200) {
        backoffMultiplier = 1;
        try {
          reconciler.reconcile(parseResources(context, kindName, result.body()));
          completedTicks.incrementAndGet();
        } catch (RuntimeException e) {
          // This tick failed; the next one re-reads the full set and retries everything --
          // level-triggered, so nothing is lost by not tracking what this tick got through.
          log.warn("operator tick for {} failed: {}", kindName, e.getMessage());
        }
      } else {
        log.warn("polling /resources/{} answered {}: {}", kindName, result.status(), result.body());
        backoffMultiplier = Math.min(backoffMultiplier * 2, MAX_BACKOFF_MULTIPLIER);
      }
      try {
        Thread.sleep(pollInterval.toMillis() * backoffMultiplier);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static List<GaldrResource> parseResources(
      ModuleContext context, String kindName, String body) {
    List<GaldrResource> resources = new ArrayList<>();
    for (Map<String, Object> item : Json.asObjectList(Json.parse(body))) {
      Optional<String> tenantId =
          item.get("tenantId") instanceof String tenant ? Optional.of(tenant) : Optional.empty();
      Optional<Map<String, Object>> status =
          item.get("status") instanceof Map
              ? Optional.of(Json.asObject(item.get("status")))
              : Optional.empty();
      resources.add(
          new GaldrResource(
              context,
              kindName,
              String.valueOf(item.get("name")),
              tenantId,
              ((Number) item.get("generation")).longValue(),
              Json.asObject(item.get("spec")),
              status));
    }
    return List.copyOf(resources);
  }

  /** Completed reconcile passes so far -- lets a test await "at least one more tick ran". */
  public long completedTicks() {
    return completedTicks.get();
  }

  @Override
  public void close() {
    closed = true;
    thread.interrupt();
    try {
      thread.join(Duration.ofSeconds(5).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
