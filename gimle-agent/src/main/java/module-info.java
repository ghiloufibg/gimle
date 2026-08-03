module com.gimle.agent {
  requires com.gimle.core;
  requires com.gimle.os;
  requires com.gimle.module;
  requires com.gimle.fabric;
  requires com.gimle.pki;
  requires java.management;
  requires jdk.management;
  requires java.net.http;
  requires jdk.httpserver;
  requires org.slf4j;
  requires org.bouncycastle.pkix;
  requires org.bouncycastle.provider;

  exports com.gimle.agent;
}
