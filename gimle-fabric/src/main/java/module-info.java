module com.gimle.fabric {
  requires com.gimle.core;
  requires com.gimle.module; // ServiceRegistry, ModuleId, ServiceExport
  requires org.slf4j;
  requires io.opentelemetry.api;
  requires io.opentelemetry.context;

  exports com.gimle.fabric.cluster;
  exports com.gimle.fabric.catalog;
  exports com.gimle.fabric.registry;
  exports com.gimle.fabric.transport;
  exports com.gimle.fabric.balance;
  exports com.gimle.fabric.breaker;
  exports com.gimle.fabric.trace;
}
