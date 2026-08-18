package com.example.webui.provider;

import com.example.inventory.InventoryLevels;
import com.example.orders.OrderCatalog;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.List;
import java.util.Optional;

/**
 * Pure HTTP-response-shaping logic for the web UI's small REST API -- takes already-resolved
 * fabric collaborators as plain arguments rather than reaching for the fabric itself, the same
 * split {@code OrdersReportJobHooks}/{@code ReportGenerator} already establish: {@link
 * WebUiHooks} owns all the Gimlé platform plumbing (the HTTP server, the {@code ModuleContext}
 * lookups), this class doesn't know Gimlé exists.
 *
 * <p>Real Gson now, not hand-written JSON strings and regex extraction -- see this module's own
 * {@code pom.xml} for why Gson specifically (a deliberately non-modular dependency, unlike every
 * other shaded jar in this app). {@link #ORDER_REQUEST_TYPE} is a plain mutable class, not a
 * record: Gson 2.8.5 predates Gson's own record support (added in 2.10) and falls back to {@code
 * sun.misc.Unsafe}-based field injection for any target type with no no-arg constructor -- exactly
 * the failure mode a record (canonical constructor only) would hit. A plain bean sidesteps it
 * entirely, Gson's oldest and most reliable code path. The three response-only shapes below stay
 * records: Gson's serialization side just reads a class's own fields by reflection, whether or not
 * they're record components, so nothing about output serialization needs the same workaround.
 */
public final class WebUiService {

  static final List<String> TRACKED_SKUS = List.of("widget", "gadget");

  private static final Gson GSON = new Gson();
  private static final Class<OrderRequest> ORDER_REQUEST_TYPE = OrderRequest.class;

  /** One row of {@code GET /api/inventory}'s JSON array. {@code -1} for a value whose collaborator
   * is unavailable -- the page renders that as "unavailable" rather than a misleading zero. */
  public record InventoryEntry(String sku, int stock, int ordered) {}

  /** {@code POST /api/orders}' 201 response body. */
  public record OrderPlacedResponse(String orderId) {}

  /** Every error response this API sends, {@code 400}-{@code 503} alike -- one shape, everywhere. */
  public record ErrorResponse(String error) {}

  /** {@code POST /api/orders}' request body. See this class's own javadoc for why this is a plain
   * class, not a record, unlike every response type above. */
  public static final class OrderRequest {
    private String sku;
    private int quantity;

    public String sku() {
      return sku;
    }

    public int quantity() {
      return quantity;
    }
  }

  public String inventoryJson(Optional<OrderCatalog> orderCatalog, Optional<InventoryLevels> inventoryLevels) {
    List<InventoryEntry> entries =
        TRACKED_SKUS.stream()
            .map(
                sku ->
                    new InventoryEntry(
                        sku,
                        inventoryLevels.map(levels -> levels.stockLevel(sku)).orElse(-1),
                        orderCatalog.map(catalog -> catalog.totalUnitsOrdered(sku)).orElse(-1)))
            .toList();
    return GSON.toJson(entries);
  }

  /** Parses {@code POST /api/orders}' request body -- empty on anything Gson can't parse as an
   * {@link OrderRequest} at all (malformed JSON, wrong shape), distinct from {@link
   * #validateOrderRequest} below, which only ever runs on a request that parsed successfully. */
  public Optional<OrderRequest> parseOrderRequest(String json) {
    try {
      return Optional.ofNullable(GSON.fromJson(json, ORDER_REQUEST_TYPE));
    } catch (JsonSyntaxException e) {
      return Optional.empty();
    }
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

  public String orderPlacedJson(String orderId) {
    return GSON.toJson(new OrderPlacedResponse(orderId));
  }

  public String errorJson(String message) {
    return GSON.toJson(new ErrorResponse(message));
  }
}
