/**
 * {@code com.gimle.module}/{@code org.slf4j} are declared {@code requires static}, not {@code
 * requires} -- see any sibling module's own module-info.java in this app for why. No Spring here
 * and nothing shaded in: this module has no third-party dependency at all, so unlike its three
 * siblings its own {@code pom.xml} needs no maven-shade-plugin execution. {@code jdk.httpserver}
 * is the one requires unique to this module, the same reason {@code com.example.webui} needs it:
 * {@code com.sun.net.httpserver.HttpServer} is what this module's own {@code /call} bridge listens
 * on.
 */
module com.example.ordersload {
  requires static com.gimle.module;
  requires static org.slf4j;
  requires jdk.httpserver;

  // Its own literal copy of the contract it looks up over the fabric -- see OrderCatalog.java's
  // own javadoc for why every module in this app keeps one rather than sharing a compile-time jar.
  exports com.example.orders;
  // OrdersLoadGeneratorHooks/LivenessProbe/ReadinessProbe: ModuleController instantiates each of
  // these reflectively from outside the module.
  exports com.example.ordersload;
}
