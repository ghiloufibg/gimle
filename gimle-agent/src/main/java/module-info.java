module com.gimle.agent {
  requires com.gimle.core;
  requires com.gimle.os;
  requires com.gimle.module;
  requires com.gimle.fabric;
  requires java.management;
  requires jdk.management;
  requires java.net.http;
  requires org.slf4j;

  exports com.gimle.agent;
}
