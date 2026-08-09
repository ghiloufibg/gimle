module com.gimle.mimir {
  requires com.gimle.core;
  requires com.gimle.pki;
  requires org.yaml.snakeyaml;
  requires org.slf4j;

  exports com.gimle.mimir.authz;
  exports com.gimle.mimir.manifest;
  exports com.gimle.mimir.store;
  exports com.gimle.mimir.raft;
  exports com.gimle.mimir.rpc;
  exports com.gimle.mimir;
}
