package com.example.orders.provider;

import java.util.concurrent.atomic.AtomicLong;

/** A small collaborator bean, constructor-injected into {@link OrderBook} -- real Spring wiring
 * between two beans, not a single bean standing alone with nothing to demonstrate DI against. */
public class OrderIdGenerator {

  private final AtomicLong sequence = new AtomicLong();

  public String next() {
    return "order-" + sequence.incrementAndGet();
  }
}
