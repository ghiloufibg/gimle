module com.gimle.hugin {
  requires com.gimle.cli;
  requires com.gimle.core;
  requires org.jline.terminal;

  // Declared for the module path; META-INF/services is what actually resolves this provider today,
  // since the shipped CLI and every test load from the classpath, where provides directives are
  // ignored outright. Both declarations are kept so the seam stays correct either way.
  provides com.gimle.cli.spi.CliExtension with
      com.gimle.hugin.HuginExtension;
}
