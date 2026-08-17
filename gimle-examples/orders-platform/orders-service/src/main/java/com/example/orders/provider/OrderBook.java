package com.example.orders.provider;

import com.example.orders.OrderCatalog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The real Spring bean backing orders-service's fabric-published {@link OrderCatalog} --
 * constructor-injected with {@link OrderIdGenerator} by {@link OrdersConfiguration}, exactly the
 * dependency injection this app exists to demonstrate is real, not a single hand-wired object.
 * State is in-memory only and lost on redeploy; that's fine for a manual-validation app whose
 * whole point is watching Gimlé actually redeploy/restart it.
 */
public class OrderBook implements OrderCatalog {

  private final OrderIdGenerator idGenerator;
  private final Map<String, List<Order>> ordersBySku = new ConcurrentHashMap<>();

  public OrderBook(OrderIdGenerator idGenerator) {
    this.idGenerator = idGenerator;
  }

  @Override
  public String placeOrder(String sku, int quantity) {
    String id = idGenerator.next();
    ordersBySku.computeIfAbsent(sku, ignored -> new CopyOnWriteArrayList<>()).add(new Order(id, sku, quantity));
    return id;
  }

  @Override
  public int totalUnitsOrdered(String sku) {
    return ordersBySku.getOrDefault(sku, List.of()).stream().mapToInt(Order::quantity).sum();
  }
}
