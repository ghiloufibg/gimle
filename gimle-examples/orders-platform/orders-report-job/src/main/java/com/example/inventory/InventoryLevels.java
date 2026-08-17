package com.example.inventory;

/**
 * orders-report-job's own literal, independently-compiled copy of inventory-service's fabric
 * contract. See orders-service's OrderCatalog.java for the full explanation of why every consumer
 * bundles its own copy instead of sharing a compile-time jar.
 */
public interface InventoryLevels {

  int stockLevel(String sku);
}
