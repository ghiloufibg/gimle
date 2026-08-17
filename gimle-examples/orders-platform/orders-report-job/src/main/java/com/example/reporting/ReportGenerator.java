package com.example.reporting;

import com.example.inventory.InventoryLevels;
import com.example.orders.OrderCatalog;
import java.util.List;
import java.util.Optional;

/**
 * The real Spring bean doing this job's actual work -- pure report-formatting logic, taking
 * already-resolved fabric collaborators as plain arguments rather than reaching for the fabric
 * itself. {@link OrdersReportJobHooks} owns all the Gimlé platform plumbing (the
 * {@code ModuleContext} lookups, the retry loop); this class doesn't know Gimlé exists.
 */
public class ReportGenerator {

  private static final List<String> TRACKED_SKUS = List.of("widget", "gadget");

  public String buildReport(Optional<OrderCatalog> orderCatalog, Optional<InventoryLevels> inventoryLevels) {
    StringBuilder report = new StringBuilder("orders report:");
    for (String sku : TRACKED_SKUS) {
      String ordered = orderCatalog.map(c -> String.valueOf(c.totalUnitsOrdered(sku))).orElse("unavailable");
      String stock = inventoryLevels.map(l -> String.valueOf(l.stockLevel(sku))).orElse("unavailable");
      report.append(String.format("%n  %s: ordered=%s, stock=%s", sku, ordered, stock));
    }
    return report.toString();
  }
}
