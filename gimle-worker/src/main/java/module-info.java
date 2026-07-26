module com.gimle.worker {
  requires com.gimle.core;
  requires com.gimle.module;
  requires com.gimle.observability;
  requires java.management;
  requires org.slf4j;

  exports com.gimle.worker;
}
