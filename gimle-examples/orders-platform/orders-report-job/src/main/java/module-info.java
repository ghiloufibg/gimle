/**
 * See orders-service's own module-info.java for why Spring is never named in a "requires" clause
 * here -- it's shaded into this module's own jar at package time instead (pom.xml).
 */
module com.example.reporting {
  requires static com.gimle.module;
  requires static org.slf4j;
  requires java.logging;
  requires java.desktop;

  // Its own copies of the two contracts it looks up over the fabric.
  exports com.example.orders;
  exports com.example.inventory;
  // OrdersReportJobHooks: ModuleController instantiates this reflectively from outside the
  // module, the same way it does with a long-running module's own ModuleLifecycleHooks.
  exports com.example.reporting;
}
