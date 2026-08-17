package com.example.inventory;

/**
 * web-ui's own literal, independently-compiled copy of inventory-service's fabric contract. See
 * orders-service's OrderCatalog.java for the full explanation of why every consumer bundles its
 * own copy instead of sharing a compile-time jar.
 */
public interface InventoryLevels {

  int stockLevel(String sku);
}
