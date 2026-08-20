package com.example.frauddetection.scorer;

import com.example.frauddetection.AlertPayload;
import com.example.frauddetection.AlertSink;
import com.example.frauddetection.FraudScorer;
import com.example.frauddetection.ScoreResult;
import com.example.frauddetection.Transaction;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The middle hop: a simple rule-based risk scorer. {@link #score} runs synchronously on whichever
 * thread {@code FabricServer} (gimle-fabric) dispatches the inbound call on -- a per-request
 * dispatch thread, not the worker's own control-channel receive loop, so calling onward to
 * {@link AlertSink} synchronously from inside it (for a HIGH-risk verdict) is a real, safe
 * multi-hop chain, the same way one real microservice calls another inline from its own request
 * handler.
 *
 * <p>Velocity tracking ({@link #transactionCountByAccount}) is deliberately per-replica, not
 * globally consistent across every fraud-scorer instance: a real system would centralize this (a
 * shared store, or a sticky-routing scheme), which is well beyond what this example is about --
 * documented here rather than silently glossed over.
 *
 * <p>Alerting a HIGH-risk transaction never fails the scoring call itself: {@link #tryAlert}
 * retries a bounded number of times against a fresh {@code lookupService} each attempt (so a
 * misbehaving canary replica's own circuit breaker, once open, is naturally avoided by later
 * attempts -- see ../README.md), and simply logs a warning and moves on if every attempt fails.
 * The transaction was already scored; a downstream alerting hiccup must never turn a completed
 * scoring decision into a failure of this call.
 */
public final class FraudScorerHooks implements ModuleLifecycleHooks, FraudScorer {

  private static final Logger log = LoggerFactory.getLogger(FraudScorerHooks.class);
  private static final double HIGH_AMOUNT_THRESHOLD = 3000.0;
  private static final int HIGH_AMOUNT_POINTS = 40;
  private static final int VELOCITY_THRESHOLD = 50;
  private static final int VELOCITY_POINTS = 30;
  private static final int HIGH_RISK_SCORE = 50;
  private static final int MEDIUM_RISK_SCORE = 25;
  private static final int MAX_ALERT_ATTEMPTS = 3;
  private static final Duration ALERT_RETRY_DELAY = Duration.ofMillis(500);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private final Map<String, AtomicInteger> transactionCountByAccount = new ConcurrentHashMap<>();
  private ModuleContext ctx;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    this.ctx = ctx;
    ctx.registerService(FraudScorer.class, this);
    ready.set(true);
    log.info("fraud-scorer registered its FraudScorer service on the fabric");
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  @Override
  public ScoreResult score(Transaction transaction) {
    int score = 0;
    if (transaction.amount() > HIGH_AMOUNT_THRESHOLD) {
      score += HIGH_AMOUNT_POINTS;
    }
    int seenSoFar =
        transactionCountByAccount
            .computeIfAbsent(transaction.accountId(), id -> new AtomicInteger())
            .incrementAndGet();
    if (seenSoFar > VELOCITY_THRESHOLD) {
      score += VELOCITY_POINTS;
    }
    score += ThreadLocalRandom.current().nextInt(10);

    String verdict = score >= HIGH_RISK_SCORE ? "HIGH" : score >= MEDIUM_RISK_SCORE ? "MEDIUM" : "LOW";
    if ("HIGH".equals(verdict)) {
      tryAlert(new AlertPayload(transaction.transactionId(), transaction.accountId(), score));
    }
    return new ScoreResult(score, verdict);
  }

  private void tryAlert(AlertPayload payload) {
    for (int attempt = 1; attempt <= MAX_ALERT_ATTEMPTS; attempt++) {
      Optional<AlertSink> sink = ctx.lookupService(AlertSink.class);
      if (sink.isPresent()) {
        try {
          sink.get().alert(payload);
          return;
        } catch (RuntimeException e) {
          log.warn(
              "alert delivery for transaction {} failed on attempt {}/{}: {}",
              payload.transactionId(),
              attempt,
              MAX_ALERT_ATTEMPTS,
              e.getMessage());
        }
      } else {
        log.warn("no AlertSink available yet on attempt {}/{}", attempt, MAX_ALERT_ATTEMPTS);
      }
      if (attempt < MAX_ALERT_ATTEMPTS) {
        sleep(ALERT_RETRY_DELAY);
      }
    }
    log.warn(
        "giving up on alerting transaction {} after {} attempts -- the score itself still stands",
        payload.transactionId(),
        MAX_ALERT_ATTEMPTS);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
