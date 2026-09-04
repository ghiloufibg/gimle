module com.gimle.cli {
  requires com.gimle.core;
  requires com.gimle.module;
  requires com.gimle.pki;
  requires com.gimle.fafnir;
  requires java.net.http;
  requires org.yaml.snakeyaml;
  requires org.bouncycastle.pkix;
  requires org.bouncycastle.provider;

  exports com.gimle.cli;
  exports com.gimle.cli.spi;

  // Required only when this code is loaded from the module path: ServiceLoader.load from a named
  // module without a matching `uses` throws ServiceConfigurationError. Inert on the classpath,
  // which is how the shipped CLI and every test actually load it.
  uses com.gimle.cli.spi.CliExtension;
}
