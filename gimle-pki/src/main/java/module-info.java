module com.gimle.pki {
  requires com.gimle.core;
  requires org.bouncycastle.pkix;
  requires org.bouncycastle.provider;
  requires java.net.http;
  requires org.slf4j;

  exports com.gimle.pki;
}
