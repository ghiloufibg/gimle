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
    command.add("-cp");
    command.add(String.join(File.pathSeparator, runtimeClasspathElements));
    command.add("com.gimle.controlplane.ControlPlaneMain");
    command.add(port);
    command.add(stateDir);
    command.add(raftPort);
    return command;
  }
}
