package com.example.orders;

/**
 * inventory-service's own literal, independently-compiled copy of orders-service's fabric
 * contract -- looked up here via {@code ctx.lookupService(OrderCatalog.class)}, never shared as a
 * compile-time jar between the two modules. See orders-service's own copy of this file for the
 * full explanation of why that's the actual contract this platform's fabric relies on.
 */
public interface OrderCatalog {

  String placeOrder(String sku, int quantity);

  int totalUnitsOrdered(String sku);
}
