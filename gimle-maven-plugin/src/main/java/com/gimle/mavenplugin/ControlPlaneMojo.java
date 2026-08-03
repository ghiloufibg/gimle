package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:controlplane} -- launches a real {@code ControlPlaneMain} process using {@code
 * gimle-controlplane}'s own resolved runtime classpath. No-ops in every other reactor module (see
 * {@link AbstractGimleMojo}).
 */
@Mojo(name = "controlplane", requiresDependencyResolution = ResolutionScope.RUNTIME)
public final class ControlPlaneMojo extends AbstractGimleMojo {

  @Parameter(property = "gimle.controlplane.port", defaultValue = "8080")
  private String port;

  @Parameter(
      property = "gimle.controlplane.stateDir",
      defaultValue = "${project.build.directory}/gimle-state")
  private String stateDir;

  @Parameter(property = "gimle.controlplane.raftPort", defaultValue = "9080")
  private String raftPort;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol}, per {@code
   * claudedocs/tls-transport-security-design.md} §1 -- unset by default (plaintext, matching {@code
   * TransportProtocol}'s own default), passed through as a {@code -D} JVM flag on the spawned
   * process when set, matching {@code gimle.tls.certFile}/{@code keyFile}/{@code caFile}'s own
   * plain system-property convention rather than inventing a parallel one just for this Mojo.
   */
  @Parameter(property = "gimle.controlplane.transportProtocol")
  private String transportProtocol;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-controlplane";
  }

  @Override
  protected List<String> buildCommand() {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    if (transportProtocol != null && !transportProtocol.isBlank()) {
      command.add("-Dgimle.transport.protocol=" + transportProtocol);
    }
    command.add("-cp");
    command.add(String.join(File.pathSeparator, runtimeClasspathElements));
    command.add("com.gimle.controlplane.ControlPlaneMain");
    command.add(port);
    command.add(stateDir);
    command.add(raftPort);
    return command;
  }
}
