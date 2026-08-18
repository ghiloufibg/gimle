package com.example.orders.provider;

/**
 * Formats the numeric id Postgres's own {@code GENERATED ALWAYS AS IDENTITY} column produces into
 * this app's public order id shape. A second real Spring-wired collaborator alongside {@link
 * OrderBook} -- constructor injection between two beans is still demonstrated here even though the
 * id itself no longer needs generating in-process (a per-instance counter, the previous approach,
 * would collide the moment a second replica writes to the same table).
 */
public class OrderIdFormatter {

  public String format(long id) {
    return "order-" + id;
  }
}
