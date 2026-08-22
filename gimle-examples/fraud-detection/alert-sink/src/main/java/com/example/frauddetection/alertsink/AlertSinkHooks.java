package com.example.frauddetection.alertsink;

import com.example.frauddetection.AlertPayload;
import com.example.frauddetection.AlertSink;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives alerts for HIGH-risk transactions. The exact same jar and {@code gimle-module.yaml}
 * are deployed twice against this one interface/version (see ../deployment.yaml and
 * ../canary-deployment.yaml) -- a healthy deployment with {@code alertsink.failureRate} left at
 * its default {@code 0.0}, and a "canary" deployment under a second tenant with a deliberately
 * high failure rate. Both export {@code AlertSink} the same way, so fraud-scorer's own
 * {@code lookupService(AlertSink.class)} sees every instance from both deployments as candidates
 * for the same service -- exactly the scenario a bad canary rollout creates in a real system, and
 * exactly what lets {@code gimle-fabric}'s own {@code CircuitBreaker} organically isolate the
 * misbehaving replica without this app writing any resilience logic of its own. See
 * ../README.md.
 *
 * <p>{@code alertsink.failureRate} is read once at {@link #onStart}, not per call: this is a
 * fixed simulated failure *rate* for the life of the instance, not a dynamically reconfigurable
 * value -- reading it once keeps the failure behavior stable for the whole run, which is what
 * makes the circuit breaker's error-rate window meaningful to observe.
 */
public final class AlertSinkHooks implements ModuleLifecycleHooks, AlertSink {

  private static final Logger log = LoggerFactory.getLogger(AlertSinkHooks.class);
  private static final double DEFAULT_FAILURE_RATE = 0.0;

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private final AtomicLong received = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();
  private double failureRate = DEFAULT_FAILURE_RATE;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    failureRate =
        ctx.config("alertsink.failureRate").map(String::strip).map(Double::parseDouble)
            .orElse(DEFAULT_FAILURE_RATE);
    ctx.registerService(AlertSink.class, this);
    ready.set(true);
    log.info("alert-sink registered its AlertSink service, failureRate={}", failureRate);
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  @Override
  public void alert(AlertPayload payload) {
    long total = received.incrementAndGet();
    if (failureRate > 0.0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
      long failedSoFar = rejected.incrementAndGet();
      throw new AlertDeliveryException(
          "simulated alert delivery failure (this replica has now rejected "
              + failedSoFar
              + " of "
              + total
              + " alerts it received)");
    }
    log.info(
        "ALERT delivered: transaction {} account {} riskScore {} (this replica has now handled {})",
        payload.transactionId(),
        payload.accountId(),
        payload.riskScore(),
        total);
  }
}
