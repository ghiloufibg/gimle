package com.example.orders.provider;

import com.example.orders.OrderCatalog;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Boots a real Spring {@link AnnotationConfigApplicationContext}, publishes its {@link OrderBook}
 * bean on Gimlé's own fabric as {@link OrderCatalog}, then seeds a couple of demo orders so a
 * freshly deployed cluster already has something visible to inspect (via logs, or
 * orders-report-job's own report) without a separate client needing to be written first.
 *
 * <p>{@link OrderBook} is backed by a real Postgres table now, via a real HikariCP connection
 * pool built here from delivered config -- the first orders-platform module whose fabric-exported
 * state survives its own redeploy. Connection details come from {@code ctx.config(...)} the same
 * way {@code WebUiHooks} reads its admin token, but exercise the *other* half of config delivery
 * that module never needed: {@link #DB_URL_CONFIG_KEY}/{@link #DB_USER_CONFIG_KEY} are plain,
 * non-secret {@code /config/*} entries (sensible defaults apply if unset), while {@link
 * #DB_PASSWORD_CONFIG_KEY} is a real secret Fafnir must deliver -- there is no default for it, and
 * startup fails loudly rather than silently falling back to a guessable password. This module now
 * needs a real {@code tenantId} purely so that delivery reaches it at all (see {@code
 * AgentMain#deliverConfig}'s own early return for an untenanted instance) -- see
 * ../../README.md's "Surviving redeploy with a real database" section for the {@code gimle set
 * config}/{@code gimle secret set} commands this depends on, and for the manual walkthrough that
 * proves the pool survives a redeploy without leaking connections.
 */
public final class OrdersServiceHooks implements ModuleLifecycleHooks {

  private static final Logger log = LoggerFactory.getLogger(OrdersServiceHooks.class);

  static final String DB_URL_CONFIG_KEY = "orders.db-url";
  static final String DB_USER_CONFIG_KEY = "orders.db-user";
  static final String DB_PASSWORD_CONFIG_KEY = "orders.db-password";

  private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/orders";
  private static final String DEFAULT_DB_USER = "orders";

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private AnnotationConfigApplicationContext springContext;
  private HikariDataSource dataSource;

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    dataSource = buildDataSource(ctx);

    springContext = new AnnotationConfigApplicationContext();
    springContext.registerBean(DataSource.class, () -> dataSource);
    springContext.register(OrdersConfiguration.class);
    springContext.refresh();
    OrderBook orderBook = springContext.getBean(OrderBook.class);

    ctx.registerService(OrderCatalog.class, orderBook);
    ready.set(true);
    log.info("orders-service registered its OrderCatalog on the fabric, backed by Postgres");

    // Seeded unconditionally on every start, on purpose: the running total only ever goes up,
    // never resets, across a redeploy or a self-healing restart alike -- that's the visible proof
    // this instance's state actually lives in Postgres now, not in this JVM's own heap. See
    // ../../README.md's redeploy walkthrough.
    String widgetOrder = orderBook.placeOrder("widget", 5);
    String gadgetOrder = orderBook.placeOrder("gadget", 3);
    log.info(
        "seeded demo orders: {} (widget x5), {} (gadget x3) -- place more via the fabric, or just"
            + " watch orders-report-job pick these up",
        widgetOrder,
        gadgetOrder);
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
    if (springContext != null) {
      springContext.close();
      springContext = null;
    }
    // Closed after the Spring context, not before: OrderBook's own JDBC calls up to this point
    // (including this same shutdown's last fabric dispatch, if one is in flight) still need a
    // live pool. Closing this explicitly, rather than relying on Hikari's own shutdown hook, is
    // exactly the "does this leak across repeated redeploys" behavior this module exists to let
    // someone watch for real.
    if (dataSource != null) {
      dataSource.close();
      dataSource = null;
    }
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  private static HikariDataSource buildDataSource(ModuleContext ctx) {
    String url = ctx.config(DB_URL_CONFIG_KEY).orElse(DEFAULT_DB_URL);
    String user = ctx.config(DB_USER_CONFIG_KEY).orElse(DEFAULT_DB_USER);
    Optional<String> password = ctx.config(DB_PASSWORD_CONFIG_KEY);
    if (password.isEmpty()) {
      throw new IllegalStateException(
          "no "
              + DB_PASSWORD_CONFIG_KEY
              + " delivered (missing tenantId, or the secret was never set) -- see"
              + " ../../README.md's \"Surviving redeploy with a real database\" section");
    }

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password.get());
    config.setPoolName("orders-service");
    // Deliberately small: this is a manual-validation demo talking to one local Postgres
    // instance, not a production sizing decision.
    config.setMaximumPoolSize(4);
    log.info("connecting orders-service's OrderBook to {} as {}", url, user);
    return new HikariDataSource(config);
  }
}
