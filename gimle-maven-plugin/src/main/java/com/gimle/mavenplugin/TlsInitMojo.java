package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:tls-init} -- generates the cluster CA, the control plane's own leaf certificate,
 * and the first human operator's leaf certificate via a real {@code com.gimle.pki.PkiBootstrapMain}
 * subprocess. Unlike {@code AgentMojo}, this needs no cross-module Aether classpath resolution:
 * {@code PkiBootstrapMain} lives *in* {@code gimle-pki} itself, so this module's own {@code
 * ${project.runtimeClasspathElements}} is already everything the spawned process needs -- the same
 * simple shape {@code DeployMojo} uses for {@code gimle-cli}.
 */
@Mojo(name = "tls-init", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class TlsInitMojo extends AbstractGimleMojo {

  @Parameter(property = "gimle.tlsInit.outputDir", defaultValue = "./gimle-tls")
  private String outputDir;

  @Parameter(property = "gimle.tlsInit.caCommonName", defaultValue = "gimle-cluster-ca")
  private String caCommonName;

  @Parameter(property = "gimle.tlsInit.hostname", defaultValue = "localhost")
  private String hostname;

  // Empty by default, so an interactive `mvn gimle:tls-init` still prints the one-time bootstrap
  // password straight to the developer's terminal. A run whose output is redirected or captured (a
  // pipeline) has no terminal to print to, and PkiBootstrapMain refuses to generate anything rather
  // than write the plaintext password into a build log -- such a run must name a file here instead.
  @Parameter(property = "gimle.tlsInit.passwordFile")
  private String passwordFile;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-pki";
  }

  @Override
  protected List<String> buildCommand() {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-cp");
    command.add(String.join(File.pathSeparator, runtimeClasspathElements));
    command.add("com.gimle.pki.PkiBootstrapMain");
    if (passwordFile != null && !passwordFile.isBlank()) {
      command.add("--password-file");
      command.add(passwordFile);
    }
    command.add(outputDir);
    command.add(caCommonName);
    command.add(hostname);
    return command;
  }
}
