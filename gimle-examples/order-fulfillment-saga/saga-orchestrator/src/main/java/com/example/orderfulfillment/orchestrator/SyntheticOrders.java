package com.example.orderfulfillment.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a deterministic (fixed-seed) batch of synthetic orders -- reproducible input, and
 * therefore a reproducible mix of outcomes, without a bundled data file (the same reasoning
 * gimle-examples/mapreduce-wordcount's own {@code SyntheticCorpus} documents).
 *
 * <p>{@code gadget} is deliberately the rarer sku here (20% of orders, against
 * inventory-reservation's own deliberately scarce 200-unit seed stock for it) so a real batch of
 * any meaningful size genuinely exhausts that stock and produces real {@code REJECTED} outcomes,
 * not just charge/shipping-triggered {@code COMPENSATED} ones.
 */
final class SyntheticOrders {

  private static final long SEED = 42L;
  private static final int GADGET_PERCENT = 20;

  private SyntheticOrders() {}

  static List<SyntheticOrder> generate(int count) {
    Random random = new Random(SEED);
    List<SyntheticOrder> orders = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String orderId = "order-" + i;
      String sku = random.nextInt(100) < GADGET_PERCENT ? "gadget" : "widget";
      int quantity = 1 + random.nextInt(3);
      double amount = 20 + random.nextDouble(480);
      orders.add(new SyntheticOrder(orderId, sku, quantity, amount));
    }
    return List.copyOf(orders);
  }
}
