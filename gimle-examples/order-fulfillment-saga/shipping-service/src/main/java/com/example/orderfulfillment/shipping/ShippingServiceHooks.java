package com.example.orderfulfillment.shipping;

import com.example.orderfulfillment.ShipmentResult;
import com.example.orderfulfillment.ShippingService;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simulated carrier: {@code shipping.failureRate} (tenant config, read once at {@link
 * #onStart}) makes a real fraction of shipments fail on purpose, so a real batch run actually
 * exercises the saga's full refund-and-release compensation chain -- the deepest compensation
 * path this app has, triggered only once both reservation and charge already succeeded.
 */
public final class ShippingServiceHooks implements ModuleLifecycleHooks, ShippingService {

  private static final Logger log = LoggerFactory.getLogger(ShippingServiceHooks.class);
  private static final double DEFAULT_FAILURE_RATE = 0.1;

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private double failureRate = DEFAULT_FAILURE_RATE;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    failureRate =
        ctx.config("shipping.failureRate").map(String::strip).map(Double::parseDouble)
            .orElse(DEFAULT_FAILURE_RATE);
    ctx.registerService(ShippingService.class, this);
    ready.set(true);
    log.info("shipping-service registered its ShippingService on the fabric, failureRate={}",
        failureRate);
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  @Override
  public ShipmentResult ship(String orderId) {
    if (ThreadLocalRandom.current().nextDouble() < failureRate) {
      return new ShipmentResult(false, null, "carrier rejected package for order " + orderId);
    }
    return new ShipmentResult(true, UUID.randomUUID().toString(), null);
  }
}
