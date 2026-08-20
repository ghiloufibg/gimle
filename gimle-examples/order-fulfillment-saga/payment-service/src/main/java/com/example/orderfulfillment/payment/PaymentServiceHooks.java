package com.example.orderfulfillment.payment;

import com.example.orderfulfillment.ChargeResult;
import com.example.orderfulfillment.PaymentService;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simulated payment processor: no real money moves, but {@code payment.failureRate} (tenant
 * config, read once at {@link #onStart}) makes a real, non-trivial fraction of charges fail on
 * purpose, so a real batch run actually exercises the saga's reservation-release compensation
 * path rather than only ever succeeding.
 */
public final class PaymentServiceHooks implements ModuleLifecycleHooks, PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentServiceHooks.class);
  private static final double DEFAULT_FAILURE_RATE = 0.15;

  static final AtomicBoolean ready = new AtomicBoolean(false);

  // orderId isn't tracked for its own sake -- only chargeId -> amount, for refund bookkeeping/
  // logging (no real ledger to reconcile against, this is a demo).
  private final Map<String, Double> chargedAmountById = new ConcurrentHashMap<>();
  private double failureRate = DEFAULT_FAILURE_RATE;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    failureRate =
        ctx.config("payment.failureRate").map(String::strip).map(Double::parseDouble)
            .orElse(DEFAULT_FAILURE_RATE);
    ctx.registerService(PaymentService.class, this);
    ready.set(true);
    log.info("payment-service registered its PaymentService on the fabric, failureRate={}",
        failureRate);
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  @Override
  public ChargeResult charge(String orderId, double amount) {
    if (ThreadLocalRandom.current().nextDouble() < failureRate) {
      return new ChargeResult(false, null, "card declined for order " + orderId);
    }
    String chargeId = UUID.randomUUID().toString();
    chargedAmountById.put(chargeId, amount);
    return new ChargeResult(true, chargeId, null);
  }

  @Override
  public void refund(String chargeId) {
    Double amount = chargedAmountById.remove(chargeId);
    if (amount == null) {
      log.warn("refund called for unknown chargeId {} -- ignoring", chargeId);
      return;
    }
    log.info("refunded charge {} ({})", chargeId, amount);
  }
}
