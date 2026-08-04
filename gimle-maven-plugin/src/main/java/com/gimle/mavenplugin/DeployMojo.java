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

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-cli";
  }

  @Override
  protected List<String> buildCommand() {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-cp");
    command.add(String.join(File.pathSeparator, runtimeClasspathElements));
    command.add("com.gimle.cli.GimleCli");
    command.add("apply");
    command.add("-f");
    command.add(file);
    command.add("--server");
    command.add(server);
    return command;
  }
}
