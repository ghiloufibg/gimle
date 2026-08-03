module com.gimle.controlplane {
  requires com.gimle.core;
  requires com.gimle.module;
  requires com.gimle.pki;
  requires org.yaml.snakeyaml;
  requires org.slf4j;
  requires jdk.httpserver;
  requires java.net.http;
  requires org.bouncycastle.pkix;
  requires org.bouncycastle.provider;

  exports com.gimle.controlplane.manifest;
  exports com.gimle.controlplane.store;
  exports com.gimle.controlplane.schedule;
  exports com.gimle.controlplane.reconcile;
  exports com.gimle.controlplane.api;
  exports com.gimle.controlplane.raft;
  exports com.gimle.controlplane.pki;
  exports com.gimle.controlplane;
}
