module com.gimle.core {
  requires org.slf4j;
  requires ch.qos.logback.classic;
  requires ch.qos.logback.core;
  requires jdk.httpserver;
  requires java.net.http;

  exports com.gimle.core.manifest;
  exports com.gimle.core.module;
  exports com.gimle.core.vessel;
  exports com.gimle.core.exception;
  exports com.gimle.core.protocol;
  exports com.gimle.core.restart;
  exports com.gimle.core.tenant;
  exports com.gimle.core.config;
  exports com.gimle.core.authz;
  exports com.gimle.core.logging;
  exports com.gimle.core.tls;
  exports com.gimle.core.throttle;
  exports com.gimle.core.banner;
  exports com.gimle.core.session;
  exports com.gimle.core.web;
  exports com.gimle.core.codec;
  exports com.gimle.core.saga;
  exports com.gimle.core.io;
  exports com.gimle.core.hash;
  exports com.gimle.core.net;
}
