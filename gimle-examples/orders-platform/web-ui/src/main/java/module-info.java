/**
 * See orders-service's own module-info.java for why Spring is never named in a "requires" clause
 * here -- it's shaded into this module's own jar at package time instead (pom.xml), and
 * java.logging/java.desktop are the two real JDK platform modules Spring's internals still need
 * regardless of shading. {@code jdk.httpserver} is the one requires unique to this module:
 * {@code com.sun.net.httpserver.HttpServer} is what serves this module's own small static page and
 * REST API -- the same JDK-only "hand-roll it, it's small" posture gimle-controlplane's own
 * ApiServer already uses, no servlet container, no web framework.
 */
module com.example.webui {
  requires static com.gimle.module;
  requires static org.slf4j;
  requires java.logging;
  requires java.desktop;
  requires jdk.httpserver;

  // Its own copies of the two contracts it looks up over the fabric.
  exports com.example.orders;
  exports com.example.inventory;
  // WebUiHooks/WebUiLivenessProbe/WebUiReadinessProbe: ModuleController instantiates each of
  // these reflectively from outside the module.
  exports com.example.webui.provider;
}
