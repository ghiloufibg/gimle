package com.example.webui.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.inventory.InventoryLevels;
import com.example.orders.OrderCatalog;
import com.gimle.core.exception.GimleFabricAuthorizationException;
import com.gimle.module.lifecycle.ModuleContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link WebUiHooks.InventoryHandler} over a real ephemeral-port {@link HttpServer},
 * standing in for the real fabric collaborators with fakes that either behave normally or throw
 * the exact exception a network-policy-denied fabric call throws in production.
 */
class WebUiHooksTest {

  @Test
  void get_api_inventory_returns_stock_and_ordered_when_fabric_calls_succeed() throws Exception {
    FakeModuleContext ctx =
        new FakeModuleContext(new FixedOrderCatalog(3), new FixedInventoryLevels(7), "tenant-a");

    HttpResponse<String> response = getInventory(ctx);

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"stock\":7"));
    assertTrue(response.body().contains("\"ordered\":3"));
  }

  @Test
  void get_api_inventory_returns_503_when_fabric_call_denied_by_network_policy() throws Exception {
    FakeModuleContext ctx =
        new FakeModuleContext(new DenyingOrderCatalog(), new FixedInventoryLevels(7), "tenant-a");

    HttpResponse<String> response = getInventory(ctx);

    assertEquals(503, response.statusCode());
    assertTrue(response.body().contains("\"error\""));
    assertTrue(response.body().contains("network policy"));
  }

  private static final class FixedOrderCatalog implements OrderCatalog {
    private final int totalUnitsOrdered;

    FixedOrderCatalog(int totalUnitsOrdered) {
      this.totalUnitsOrdered = totalUnitsOrdered;
    }

    @Override
    public String placeOrder(String sku, int quantity) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int totalUnitsOrdered(String sku) {
      return totalUnitsOrdered;
    }
  }

  /** Throws the exact exception a real network-policy-denied fabric call throws once a lookup
   * has already resolved a candidate -- see {@code FabricServer#checkNetworkPolicyPermitted}. */
  private static final class DenyingOrderCatalog implements OrderCatalog {
    @Override
    public String placeOrder(String sku, int quantity) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int totalUnitsOrdered(String sku) {
      throw GimleFabricAuthorizationException.tenantNotPermitted(
          OrderCatalog.class.getName(), "tenant web-ui");
    }
  }

  private static final class FixedInventoryLevels implements InventoryLevels {
    private final int stockLevel;

    FixedInventoryLevels(int stockLevel) {
      this.stockLevel = stockLevel;
    }

    @Override
    public int stockLevel(String sku) {
      return stockLevel;
    }
  }

  private static HttpResponse<String> getInventory(ModuleContext ctx) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/api/inventory", new WebUiHooks.InventoryHandler(ctx, new WebUiService()));
    server.start();
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/inventory"))
              .GET()
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } finally {
      server.stop(0);
    }
  }

  /** Only implements what {@link WebUiHooks.InventoryHandler} actually calls; everything else
   * throws so an accidental new dependency on this fake fails loudly instead of silently no-op. */
  private static final class FakeModuleContext implements ModuleContext {
    private final OrderCatalog orderCatalog;
    private final InventoryLevels inventoryLevels;
    private final String tenantId;

    FakeModuleContext(OrderCatalog orderCatalog, InventoryLevels inventoryLevels, String tenantId) {
      this.orderCatalog = orderCatalog;
      this.inventoryLevels = inventoryLevels;
      this.tenantId = tenantId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> lookupService(Class<T> iface) {
      if (iface == OrderCatalog.class) {
        return Optional.of((T) orderCatalog);
      }
      if (iface == InventoryLevels.class) {
        return Optional.of((T) inventoryLevels);
      }
      throw new UnsupportedOperationException("unexpected lookup: " + iface);
    }

    @Override
    public Optional<InstanceInfo> instanceInfo() {
      return Optional.of(new InstanceInfo("web-ui", 0, "node-1", Optional.of(tenantId)));
    }

    @Override
    public int inFlightCount() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void beginRequest() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void endRequest() {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> void registerService(Class<T> iface, T instance) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Object> invokeServiceByName(
        String interfaceName,
        int majorVersion,
        String methodName,
        String[] paramTypeNames,
        Object[] args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<String> config(String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> configKeys() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ConfigSubscription onConfigChange(Consumer<ConfigChange> listener) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<java.nio.file.Path> dataDirectory() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<java.nio.file.Path> dataDirectory(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RelayResult relayControlPlaneRead(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RelayResult reportResourceStatus(
        String kindName, Optional<String> resourceTenantId, String name, String statusJson) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void reportPort(String name, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Integer> reportedPorts() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SSLContext> clientSslContext() {
      return Optional.empty();
    }
  }
}
