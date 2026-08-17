package com.example.inventory.provider;

import com.example.inventory.InventoryLevels;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real Spring bean backing inventory-service's fabric-published {@link InventoryLevels}.
 * Seeded with fixed starting stock for the same two demo SKUs orders-service seeds orders
 * against, so a freshly deployed cluster already has meaningful numbers to reconcile against
 * without any manual setup.
 */
public class StockLedger implements InventoryLevels {

  private final Map<String, Integer> stockBySku = new ConcurrentHashMap<>(Map.of("widget", 100, "gadget", 40));

  @Override
  public int stockLevel(String sku) {
    return stockBySku.getOrDefault(sku, 0);
  }

  /** Every SKU this ledger tracks -- used by {@link InventoryServiceHooks}' own reconcile loop,
   * not part of the fabric-published {@link InventoryLevels} contract. */
  Iterable<String> trackedSkus() {
    return stockBySku.keySet();
  }
}
