package com.example.orders.provider;

import com.example.orders.OrderCatalog;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Boots a real Spring {@link AnnotationConfigApplicationContext}, publishes its {@link OrderBook}
 * bean on Gimlé's own fabric as {@link OrderCatalog}, then seeds a couple of demo orders so a
 * freshly deployed cluster already has something visible to inspect (via logs, or
 * orders-report-job's own report) without a separate client needing to be written first.
 */
public final class OrdersServiceHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(OrdersServiceHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private AnnotationConfigApplicationContext springContext;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    springContext = new AnnotationConfigApplicationContext(OrdersConfiguration.class);
    OrderBook orderBook = springContext.getBean(OrderBook.class);

    ctx.registerService(OrderCatalog.class, orderBook);
    ready.set(true);
    log.info("orders-service registered its OrderCatalog on the fabric");

    String widgetOrder = orderBook.placeOrder("widget", 5);
    String gadgetOrder = orderBook.placeOrder("gadget", 3);
    log.info(
        "seeded demo orders: {} (widget x5), {} (gadget x3) -- place more via the fabric, or just"
            + " watch orders-report-job pick these up",
        widgetOrder,
        gadgetOrder);
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
    if (springContext != null) {
      springContext.close();
      springContext = null;
    }
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}
}
