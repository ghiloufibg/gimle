module com.gimle.ivaldi {
  requires com.gimle.core;
  requires com.gimle.hilmir;
  requires com.gimle.mimir;
  requires org.slf4j;
  requires jdk.httpserver;
  requires org.yaml.snakeyaml;

  exports com.gimle.ivaldi;
  exports com.gimle.ivaldi.blueprint;
  exports com.gimle.ivaldi.validate;
}
