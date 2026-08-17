package com.example.orders;

/**
 * orders-report-job's own literal, independently-compiled copy of orders-service's fabric
 * contract. See orders-service's own copy of this file for the full explanation.
 */
public interface OrderCatalog {

  String placeOrder(String sku, int quantity);

  int totalUnitsOrdered(String sku);
}
