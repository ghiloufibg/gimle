module com.gimle.hilmir {
  requires com.gimle.core;
  requires java.net.http;
  requires org.yaml.snakeyaml;

  exports com.gimle.hilmir;
  exports com.gimle.hilmir.topology;
  exports com.gimle.hilmir.validate;
  exports com.gimle.hilmir.plan;
  exports com.gimle.hilmir.launch;
  exports com.gimle.hilmir.release;
  exports com.gimle.hilmir.analyze;
  exports com.gimle.hilmir.doctor;
  exports com.gimle.hilmir.init;
}
