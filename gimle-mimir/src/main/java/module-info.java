module com.gimle.mimir {
  requires com.gimle.core;
  requires com.gimle.pki;
  requires com.gimle.observability;
  requires micrometer.core;
  requires org.yaml.snakeyaml;
  requires org.slf4j;
  requires jdk.httpserver;

  exports com.gimle.mimir.authz;
  exports com.gimle.mimir.cron;
  exports com.gimle.mimir.galdr;
  exports com.gimle.mimir.manifest;
  exports com.gimle.mimir.store;
  exports com.gimle.mimir.raft;
  exports com.gimle.mimir.rpc;
  exports com.gimle.mimir.health;
  exports com.gimle.mimir;
}
