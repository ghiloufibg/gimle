package com.example.orderfulfillment.orchestrator;

/** One synthetic order this batch's saga processes. */
record SyntheticOrder(String orderId, String sku, int quantity, double amount) {}
