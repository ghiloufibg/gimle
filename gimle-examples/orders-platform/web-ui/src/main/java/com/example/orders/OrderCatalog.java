package com.example.orders;

/**
 * web-ui's own literal, independently-compiled copy of orders-service's fabric contract. See
 * orders-service's own copy of this file for the full explanation of why every consumer bundles
 * its own copy instead of sharing a compile-time jar.
 */
public interface OrderCatalog {

  String placeOrder(String sku, int quantity);

  int totalUnitsOrdered(String sku);
}
