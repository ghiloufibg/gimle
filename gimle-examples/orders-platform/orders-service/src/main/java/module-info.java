/**
 * Spring (spring-context, spring-beans, spring-core, spring-expression, spring-aop) is bundled
 * into this module's own jar by maven-shade-plugin at package time (see pom.xml) rather than
 * named here as a "requires" -- once shaded, those classes are physically part of this same
 * module, not a separate one to require. Deliberately not declared even as "requires static": a
 * requires clause naming a module that no longer exists post-shading would leave the compiled
 * module-info.class referencing a module the runtime can never resolve. java.logging and
 * java.desktop ARE named because Spring's own internals reach into real JDK platform modules
 * (java.util.logging's LogRecord via commons-logging's JUL bridge, java.beans.PropertyDescriptor
 * for bean introspection) that stay separate modules regardless of shading -- omit either and
 * Spring's context refresh fails at runtime with IllegalAccessError, not a compile error.
 */
module com.example.orders {
  requires static com.gimle.module;
  requires static org.slf4j;
  requires java.logging;
  requires java.desktop;

  // OrderCatalog: FabricServer reflects into this interface from outside the module to dispatch
  // inbound calls onto the registered OrderBook instance.
  exports com.example.orders;
  // OrdersServiceHooks/OrdersLivenessProbe/OrdersReadinessProbe: ModuleController instantiates
  // each of these reflectively from outside the module.
  exports com.example.orders.provider;
}
