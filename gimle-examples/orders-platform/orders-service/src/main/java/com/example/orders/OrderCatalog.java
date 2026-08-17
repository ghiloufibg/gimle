package com.example.orders;

/**
 * The fabric-published contract orders-service exports and every consumer in this app (
 * inventory-service, orders-report-job) looks up. Each module bundles its own literal,
 * independently-compiled copy of this file rather than sharing a compile-time API jar -- the
 * fabric's service catalog resolves lookups by interface name and dispatches through a proxy
 * built off the caller's own {@code Class} object, so two structurally identical copies
 * interoperate across the wire without ever sharing a classloader. Same package, same simple
 * name, same method signatures in every copy -- that's the actual contract, not a shared jar.
 */
public interface OrderCatalog {

  /** Records an order for {@code quantity} units of {@code sku}, returning a new order id. */
  String placeOrder(String sku, int quantity);

  /** The running total of units ever ordered for {@code sku} (0 if none have been placed). */
  int totalUnitsOrdered(String sku);
}
