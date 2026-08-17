package com.example.webui.provider;

import com.example.inventory.InventoryLevels;
import com.example.orders.OrderCatalog;
import java.util.List;
import java.util.Optional;

/**
 * Pure HTTP-response-shaping logic for the web UI's small REST API -- takes already-resolved
 * fabric collaborators as plain arguments rather than reaching for the fabric itself, the same
 * split {@code OrdersReportJobHooks}/{@code ReportGenerator} already establish: {@link
 * WebUiHooks} owns all the Gimlé platform plumbing (the HTTP server, the {@code ModuleContext}
 * lookups), this class doesn't know Gimlé exists. No JSON library -- both payload shapes here are
 * fixed and tiny (a two-SKU inventory array, a one-order request/response), so hand-writing them
 * is simpler and lighter than pulling in a dependency for it.
 */
public final class WebUiService {

  static final List<String> TRACKED_SKUS = List.of("widget", "gadget");

  /** {@code -1} for a SKU whose collaborator is unavailable -- the page renders that as
   * "unavailable" rather than a misleading zero. */
  public String inventoryJson(Optional<OrderCatalog> orderCatalog, Optional<InventoryLevels> inventoryLevels) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < TRACKED_SKUS.size(); i++) {
      String sku = TRACKED_SKUS.get(i);
      int stock = inventoryLevels.map(levels -> levels.stockLevel(sku)).orElse(-1);
      int ordered = orderCatalog.map(catalog -> catalog.totalUnitsOrdered(sku)).orElse(-1);
      if (i > 0) {
        json.append(',');
      }
      json
          .append("{\"sku\":\"")
          .append(sku)
          .append("\",\"stock\":")
          .append(stock)
          .append(",\"ordered\":")
          .append(ordered)
          .append('}');
    }
    return json.append(']').toString();
  }

  /** Empty when {@code sku}/{@code quantity} are fit to place; the validation failure message
   * otherwise -- checked before ever touching the fabric, so a bad request never costs a lookup. */
  public Optional<String> validateOrderRequest(String sku, int quantity) {
    if (sku == null || !TRACKED_SKUS.contains(sku)) {
      return Optional.of("unknown sku: " + sku);
    }
    if (quantity <= 0) {
      return Optional.of("quantity must be positive");
    }
    return Optional.empty();
  }
}
