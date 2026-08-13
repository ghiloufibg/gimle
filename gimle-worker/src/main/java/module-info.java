module com.gimle.worker {
  requires com.gimle.core;
  requires com.gimle.module;
  requires com.gimle.observability;
  requires com.gimle.fabric;
  requires io.opentelemetry.context;
  requires io.opentelemetry.sdk.trace;
  requires io.opentelemetry.sdk.common;
  requires java.management;
  requires jdk.management; // com.sun.management.OperatingSystemMXBean#getProcessCpuLoad
  requires org.slf4j;

  exports com.gimle.worker;
}
