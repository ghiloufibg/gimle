package com.example.inventory.provider;

import com.example.inventory.InventoryLevels;
import com.example.orders.OrderCatalog;
import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.exception.GimleFabricAuthorizationException;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Boots a real Spring context, publishes {@link StockLedger} on the fabric as
 * {@link InventoryLevels}, then reconciles stock against orders-service's own
 * {@link OrderCatalog} on a background virtual thread -- never inline from {@link #onStart},
 * same reasoning as gimle-examples/greeter-consumer's {@code GreeterConsumerHooks} (see that
 * class's own javadoc): {@code onStart} runs on the worker's single control-channel receive loop,
 * the same loop delivering the {@code CatalogUpdate} messages service discovery depends on.
 * MDC is captured here and restored at the top of the loop for the same reason too -- a spawned
 * thread doesn't inherit thread-local MDC state, so without this the loop's own logging would
 * come out as untagged PLATFORM output instead of this instance's own APPLICATION log.
 */
public final class InventoryServiceHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(InventoryServiceHooks.class);
  private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(20);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private AnnotationConfigApplicationContext springContext;
  private volatile boolean stopped;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    springContext = new AnnotationConfigApplicationContext(InventoryConfiguration.class);
    StockLedger stockLedger = springContext.getBean(StockLedger.class);

    ctx.registerService(InventoryLevels.class, stockLedger);
    ready.set(true);
    log.info("inventory-service registered its InventoryLevels on the fabric");

    Map<String, String> mdcTags = MDC.getCopyOfContextMap();
    Thread.ofVirtual().name("inventory-reconciler").start(() -> reconcileLoop(ctx, stockLedger, mdcTags));
  }

  @Override
  public void onStop(ModuleContext ctx) {
    stopped = true;
    ready.set(false);
    if (springContext != null) {
      springContext.close();
      springContext = null;
    }
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  private void reconcileLoop(ModuleContext ctx, StockLedger stockLedger, Map<String, String> mdcTags) {
    if (mdcTags != null) {
      MDC.setContextMap(mdcTags);
    }
    while (!stopped) {
      // A background loop must survive a single bad tick, not die from it: lookupService throws
      // GimleClusterException rather than returning empty when literally nobody in the cluster has
      // ever exported OrderCatalog yet (see FabricServiceRegistry#lookup) -- an ordinary state
      // right after a fresh cluster boot, before orders-service's own registration has propagated
      // through the gossip catalog. GimleFabricAuthorizationException is the other real,
      // non-transient case: a NetworkPolicySpec now scoped to orders-service's own tenant rejects
      // this module's calls outright, since this module stays deliberately untenanted (see
      // ../../README.md's "Restricting cross-tenant access" section) -- an untenanted caller is
      // never permitted once any such policy exists, platform-wide, not something specific to this
      // app. Both are caught the same way: uncaught, either would terminate this virtual thread for
      // good on its very first unlucky tick, silently ending reconciliation forever.
      try {
        reconcileOnce(ctx, stockLedger);
      } catch (GimleClusterException e) {
        log.warn("no OrderCatalog available yet; orders-service may not have registered it yet");
      } catch (GimleFabricAuthorizationException e) {
        log.warn("OrderCatalog call rejected by a network policy: {}", e.getMessage());
      }
      sleep(RECONCILE_INTERVAL);
    }
  }

  private void reconcileOnce(ModuleContext ctx, StockLedger stockLedger) {
    Optional<OrderCatalog> orderCatalog = ctx.lookupService(OrderCatalog.class);
    if (orderCatalog.isEmpty()) {
      log.warn("no OrderCatalog available yet; orders-service may not have registered it yet");
      return;
    }
    for (String sku : stockLedger.trackedSkus()) {
      int stock = stockLedger.stockLevel(sku);
      int ordered = orderCatalog.get().totalUnitsOrdered(sku);
      log.info("reconcile {}: stock={}, ordered={}, remaining={}", sku, stock, ordered, stock - ordered);
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
