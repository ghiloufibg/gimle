package com.example.orders.provider;

import com.example.orders.OrderCatalog;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real Spring bean backing orders-service's fabric-published {@link OrderCatalog} --
 * constructor-injected with a {@link DataSource} and an {@link OrderIdFormatter} by {@link
 * OrdersConfiguration}, backed by a real Postgres table rather than an in-memory map. Unlike every
 * other module in this app, this instance's own state now survives its own redeploy or restart --
 * see ../../README.md's "Surviving redeploy with a real database" section for the manual
 * walkthrough this exists to support. The {@link DataSource} itself is a real connection pool
 * (HikariCP), not a bare {@code DriverManager.getConnection} per call -- built once in {@link
 * OrdersServiceHooks#onStart} and closed in {@link OrdersServiceHooks#onStop}, so repeated
 * redeploy-in-a-loop cycles are exactly what would surface a connection leak if this module's own
 * lifecycle handling of the pool were wrong.
 */
public class OrderBook implements OrderCatalog {

  private static final Logger log = LoggerFactory.getLogger(OrderBook.class);

  private final DataSource dataSource;
  private final OrderIdFormatter idFormatter;

  public OrderBook(DataSource dataSource, OrderIdFormatter idFormatter) {
    this.dataSource = dataSource;
    this.idFormatter = idFormatter;
    createSchemaIfAbsent();
  }

  private void createSchemaIfAbsent() {
    String ddl =
        """
        CREATE TABLE IF NOT EXISTS orders (
          id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          sku TEXT NOT NULL,
          quantity INT NOT NULL
        )""";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(ddl)) {
      stmt.execute();
    } catch (SQLException e) {
      throw new IllegalStateException("orders-service could not create its own orders table", e);
    }
  }

  @Override
  public String placeOrder(String sku, int quantity) {
    String sql = "INSERT INTO orders (sku, quantity) VALUES (?, ?) RETURNING id";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, sku);
      stmt.setInt(2, quantity);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        String orderId = idFormatter.format(rs.getLong(1));
        // Every placement logs the same shape line, whether it came from this instance's own
        // startup seeding, a web-ui request, or orders-load-generator -- see ../../README.md's
        // "Business metrics from structured logs" section for why this is a log line rather than
        // a Micrometer counter (ModuleContext has no metrics accessor a hosted module can reach
        // yet), and how to turn a stream of these into a real count/rate after the fact.
        log.info("order placed: sku={} quantity={} orderId={}", sku, quantity, orderId);
        return orderId;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("orders-service could not place an order for " + sku, e);
    }
  }

  @Override
  public int totalUnitsOrdered(String sku) {
    String sql = "SELECT COALESCE(SUM(quantity), 0) FROM orders WHERE sku = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setString(1, sku);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("orders-service could not total orders for " + sku, e);
    }
  }
}
