package com.example.orders.provider;

/** One placed order, held in memory only -- this is a manual-validation demo, not a real ledger. */
public record Order(String id, String sku, int quantity) {}
