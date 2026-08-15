module com.gimle.hilmir {
  requires com.gimle.core;
  requires org.yaml.snakeyaml;

  exports com.gimle.hilmir;
  exports com.gimle.hilmir.topology;
  exports com.gimle.hilmir.validate;
  exports com.gimle.hilmir.plan;
  exports com.gimle.hilmir.launch;
}
