package com.example.frauddetection.ingest;

import com.example.frauddetection.FraudScorer;
import com.example.frauddetection.ScoreResult;
import com.example.frauddetection.Transaction;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Generates a steady stream of synthetic transactions and scores each one over the real fabric --
 * the first hop of this app's ingest -&gt; score -&gt; alert pipeline. Runs on a background
 * virtual thread, never inline from {@link #onStart}, the same reasoning
 * gimle-examples/greeter-consumer's own javadoc already gives.
 *
 * <p>A small, fixed pool of account/merchant ids (rather than a fresh random id every time) is
 * deliberate: fraud-scorer's own per-account velocity check only means something if the same
 * account genuinely transacts more than once.
 */
public final class TransactionIngestHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(TransactionIngestHooks.class);
  private static final Duration TRANSACTION_INTERVAL = Duration.ofMillis(500);
  private static final String[] ACCOUNT_POOL = {
    "acct-001", "acct-002", "acct-003", "acct-004", "acct-005",
    "acct-006", "acct-007", "acct-008", "acct-009", "acct-010"
  };
  private static final String[] MERCHANT_POOL = {"acme-retail", "globex-electronics", "initech-travel"};

  static final AtomicBoolean everSucceeded = new AtomicBoolean(false);
  private final AtomicLong sequence = new AtomicLong();
  private volatile boolean stopped;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    Map<String, String> mdcTags = MDC.getCopyOfContextMap();
    Thread.ofVirtual().name("transaction-ingest-caller").start(() -> callLoop(ctx, mdcTags));
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
      sleep(TRANSACTION_INTERVAL);
    }
  }

  private void callOnce(ModuleContext ctx) {
    Transaction transaction = generateTransaction();
    try {
      Optional<FraudScorer> scorer = ctx.lookupService(FraudScorer.class);
      if (scorer.isEmpty()) {
        log.warn("no FraudScorer available yet; fraud-scorer may not have started yet");
        return;
      }
      ScoreResult result = scorer.get().score(transaction);
      everSucceeded.set(true);
      log.info(
          "transaction {} ({} for {}, account {}) scored {} ({})",
          transaction.transactionId(),
          transaction.merchantId(),
          transaction.amount(),
          transaction.accountId(),
          result.riskScore(),
          result.verdict());
    } catch (RuntimeException e) {
      // No member currently exports FraudScorer (a fresh cluster boot race), or the resolved
      // endpoint failed (redeployed/restarted since) -- both expected, transient conditions that
      // clear up on their own once retried against a fresh lookup next cycle.
      log.warn("call to FraudScorer failed for transaction {}: {}", transaction.transactionId(),
          e.getMessage());
    }
  }

  private Transaction generateTransaction() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    String transactionId = "txn-" + sequence.incrementAndGet();
    String accountId = ACCOUNT_POOL[random.nextInt(ACCOUNT_POOL.length)];
    String merchantId = MERCHANT_POOL[random.nextInt(MERCHANT_POOL.length)];
    double amount = 1 + random.nextDouble(4999);
    return new Transaction(transactionId, accountId, merchantId, amount);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
