module com.gimle.observability {
  requires com.gimle.core;
  requires micrometer.core;
  requires jdk.jfr;
  requires java.management;
  requires io.opentelemetry.api;
  requires io.opentelemetry.context;
  requires io.opentelemetry.sdk;
  requires io.opentelemetry.sdk.trace;
  requires io.opentelemetry.sdk.common;
  requires io.opentelemetry.exporter.logging;

  exports com.gimle.observability;
}
