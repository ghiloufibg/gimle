module com.gimle.hilmir {
  requires com.gimle.core;
  requires com.gimle.mimir;
  requires java.net.http;
  requires org.yaml.snakeyaml;
  // No src/test/java/module-info.java exists, so Maven compiles test sources as part of this
  // module (--patch-module) rather than as an unnamed module; production code doesn't need this,
  // but the test-only in-process javac fixture builder does.
  requires java.compiler;

  exports com.gimle.hilmir;
  exports com.gimle.hilmir.topology;
  exports com.gimle.hilmir.validate;
  exports com.gimle.hilmir.plan;
  exports com.gimle.hilmir.launch;
  exports com.gimle.hilmir.release;
  exports com.gimle.hilmir.analyze;
  exports com.gimle.hilmir.doctor;
  exports com.gimle.hilmir.init;
  exports com.gimle.hilmir.store;
  exports com.gimle.hilmir.extension;
  exports com.gimle.hilmir.remote;
}
