module com.gimle.module {
  requires com.gimle.core;
  requires org.yaml.snakeyaml;
  requires jdk.jfr;
  requires org.slf4j;
  requires java.net.http;
  // No src/test/java/module-info.java exists, so Maven compiles test sources as part of this
  // module (--patch-module) rather than as an unnamed module; production code doesn't need
  // either of these, but test-only code does (TestModuleBuilder's in-process javac, and the
  // redeploy-loop acceptance test driver's MemoryPoolMXBean sampling).
  requires java.compiler;
  requires java.management;

  exports com.gimle.module.descriptor;
  exports com.gimle.module.artifact;
  exports com.gimle.module.resolve;
  exports com.gimle.module.layer;
  exports com.gimle.module.lifecycle;
  exports com.gimle.module.leak;
  exports com.gimle.module.probe;
}
