module com.gimle.agent {
  requires com.gimle.core;
  requires com.gimle.os;
  requires java.management;
  requires jdk.management;
  requires org.slf4j;

  exports com.gimle.agent;
}
