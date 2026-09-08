package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:deploy -Dgimle.deploy.file=<manifest.yaml>} -- applies a deployment manifest to
 * a running control plane via a real {@code GimleCli} subprocess, using {@code gimle-cli}'s own
 * resolved runtime classpath. No-ops in every other reactor module (see {@link AbstractGimleMojo}).
 */
@Mojo(name = "deploy", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class DeployMojo extends AbstractGimleMojo {

  @Parameter(property = "gimle.deploy.file", required = true)
  private String file;

  @Parameter(property = "gimle.deploy.server", defaultValue = "127.0.0.1:8080")
  private String server;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol}, matching {@code
   * ControlPlaneMojo}/{@code StoreMojo} -- unset by default (plaintext), passed through as a {@code
   * -D} JVM flag on the spawned {@code GimleCli} process when set. A control plane running with
   * {@code -Dgimle.transport.protocol=tls} rejects a plaintext client outright, so this and the
   * three TLS file parameters below need to be set together against a TLS-enabled cluster.
   */
  @Parameter(property = "gimle.deploy.transportProtocol")
  private String transportProtocol;

  /**
   * This client's own TLS leaf certificate/key and the cluster CA certificate, forwarded verbatim
   * as {@code -Dgimle.tls.certFile}/{@code keyFile}/{@code caFile} -- the exact system properties
   * {@code GimleCli}'s own {@code ControlPlaneClient} reads, and the same plain (not {@code
   * gimle.deploy}-namespaced) property names {@link BootstrapMojo#addTlsFlags} uses, so a bare
   * {@code -Dgimle.tls.certFile=...} on the {@code mvn} command line binds straight into this
   * parameter with no separate flag to learn. Unset by default like {@link #transportProtocol}:
   * only meaningful once {@code gimle.deploy.transportProtocol=tls} is also set.
   */
  @Parameter(property = "gimle.tls.certFile")
  private String certFile;

  @Parameter(property = "gimle.tls.keyFile")
  private String keyFile;

  @Parameter(property = "gimle.tls.caFile")
  private String caFile;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-cli";
  }

  @Override
  protected List<String> buildCommand() {
    return buildCommand(
        javaExecutable(),
        String.join(File.pathSeparator, runtimeClasspathElements),
        file,
        server,
        transportProtocol,
        certFile,
        keyFile,
        caFile);
  }

  /**
   * Pure command construction, split out from {@link #buildCommand()} so it's unit-testable without
   * Maven's own parameter-injection machinery -- the same seam {@link InitMojo#buildCommand}
   * establishes.
   */
  static List<String> buildCommand(
      String javaExecutable,
      String classpath,
      String file,
      String server,
      String transportProtocol,
      String certFile,
      String keyFile,
      String caFile) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    if (transportProtocol != null && !transportProtocol.isBlank()) {
      command.add("-Dgimle.transport.protocol=" + transportProtocol);
    }
    if (certFile != null && !certFile.isBlank()) {
      command.add("-Dgimle.tls.certFile=" + certFile);
    }
    if (keyFile != null && !keyFile.isBlank()) {
      command.add("-Dgimle.tls.keyFile=" + keyFile);
    }
    if (caFile != null && !caFile.isBlank()) {
      command.add("-Dgimle.tls.caFile=" + caFile);
    }
    command.add("-cp");
    command.add(classpath);
    command.add("com.gimle.cli.GimleCli");
    command.add("apply");
    command.add("-f");
    command.add(file);
    command.add("--server");
    command.add(server);
    return command;
  }
}
