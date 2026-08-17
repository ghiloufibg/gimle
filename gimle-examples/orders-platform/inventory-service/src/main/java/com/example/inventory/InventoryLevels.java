package com.example.inventory;

/**
 * inventory-service's own fabric-published contract, looked up by orders-report-job the same way
 * this module looks up orders-service's {@link com.example.orders.OrderCatalog}. See that
 * interface's own javadoc for why every consumer bundles a literal copy rather than sharing a jar.
 */
public interface InventoryLevels {

  /** Units of {@code sku} currently in stock (0 for an unknown sku, never negative). */
  int stockLevel(String sku);
}
