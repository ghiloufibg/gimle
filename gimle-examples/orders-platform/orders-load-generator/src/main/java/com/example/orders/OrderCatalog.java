package com.example.orders;

/**
 * The fabric-published contract orders-service exports and every consumer in this app (
 * inventory-service, orders-report-job, web-ui, this module) looks up. Each module bundles its own
 * literal, independently-compiled copy of this file rather than sharing a compile-time API jar --
 * see orders-service's own copy for the full explanation.
 */
public interface OrderCatalog {

  /** Records an order for {@code quantity} units of {@code sku}, returning a new order id. */
  String placeOrder(String sku, int quantity);

  /** The running total of units ever ordered for {@code sku} (0 if none have been placed). */
  int totalUnitsOrdered(String sku);
}
