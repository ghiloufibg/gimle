module com.gimle.fafnir {
  requires com.gimle.core;
  requires com.gimle.mimir;
  requires com.gimle.pki;
  requires org.slf4j;
  requires jdk.httpserver;

  exports com.gimle.fafnir.secret;
  exports com.gimle.fafnir;
}
