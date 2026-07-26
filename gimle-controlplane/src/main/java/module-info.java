module com.gimle.controlplane {
  requires com.gimle.core;
  requires com.gimle.module;
  requires org.yaml.snakeyaml;
  requires org.slf4j;
  requires jdk.httpserver;
  requires java.net.http;

  exports com.gimle.controlplane.manifest;
  exports com.gimle.controlplane.store;
  exports com.gimle.controlplane.schedule;
  exports com.gimle.controlplane.reconcile;
  exports com.gimle.controlplane.api;
  exports com.gimle.controlplane;
}
