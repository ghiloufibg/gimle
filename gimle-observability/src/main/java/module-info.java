module com.gimle.observability {
  requires com.gimle.core;
  requires micrometer.core;
  requires jdk.jfr;
  requires java.management;

  exports com.gimle.observability;
}
