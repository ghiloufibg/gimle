module com.gimle.core {
  requires org.slf4j;
  requires ch.qos.logback.classic;
  requires ch.qos.logback.core;

  exports com.gimle.core.module;
  exports com.gimle.core.exception;
  exports com.gimle.core.protocol;
  exports com.gimle.core.restart;
  exports com.gimle.core.tenant;
  exports com.gimle.core.config;
  exports com.gimle.core.logging;
  exports com.gimle.core.tls;
}
