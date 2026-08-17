/**
 * See orders-service's own module-info.java for why Spring is never named in a "requires" clause
 * here -- it's shaded into this module's own jar at package time instead (pom.xml), and
 * java.logging/java.desktop are the two real JDK platform modules Spring's internals still need
 * regardless of shading.
 */
module com.example.inventory {
  requires static com.gimle.module;
  requires static org.slf4j;
  requires java.logging;
  requires java.desktop;

  // Its own copy of orders-service's contract, looked up via the fabric.
  exports com.example.orders;
  // InventoryLevels: FabricServer reflects into this from outside the module to dispatch inbound
  // calls onto the registered StockLedger instance.
  exports com.example.inventory;
  // InventoryServiceHooks/InventoryLivenessProbe/InventoryReadinessProbe: ModuleController
  // instantiates each of these reflectively from outside the module.
  exports com.example.inventory.provider;
}
