module com.gimle.cli {
  requires com.gimle.core;
  requires com.gimle.module;
  requires com.gimle.pki;
  requires java.net.http;
  requires org.yaml.snakeyaml;
  requires org.bouncycastle.pkix;
  requires org.bouncycastle.provider;

  exports com.gimle.cli;
}
