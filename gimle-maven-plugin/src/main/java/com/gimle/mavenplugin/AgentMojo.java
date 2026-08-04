package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * {@code mvn gimle:agent} -- launches a real {@code AgentMain} process using {@code gimle-agent}'s
 * own resolved runtime classpath, plus a worker command-tail whose classpath is resolved
 * separately: the worker is a genuinely different reactor module and OS process ({@code
 * AgentMain}'s own production code never imports {@code com.gimle.worker.*}, by design -- see
 * CLAUDE.md's "Node Agent ... never runs hosted-module code itself"), so its classpath can't come
 * from this module's own {@code ${project.runtimeClasspathElements}}. It's resolved directly
 * against the already-{@code mvn install}ed {@code com.gimle:gimle-worker} artifact via Maven's own
 * dependency resolver, independent of reactor build order.
 */
@Mojo(name = "agent", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class AgentMojo extends AbstractGimleMojo {

  @Parameter(property = "gimle.agent.nodeId", defaultValue = "node-1")
  private String nodeId;

  @Parameter(property = "gimle.agent.controlPlaneUrl", defaultValue = "http://127.0.0.1:8080")
  private String controlPlaneUrl;

  @Parameter(property = "gimle.agent.gossipAddress", defaultValue = "127.0.0.1:9090")
  private String gossipAddress;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol}, per {@code
   * claudedocs/tls-transport-security-design.md} §1 -- same shape as {@code
   * ControlPlaneMojo#transportProtocol}, unset by default.
   */
  @Parameter(property = "gimle.agent.transportProtocol")
  private String transportProtocol;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Parameter(defaultValue = "${project.version}", readonly = true, required = true)
  private String projectVersion;

  @Parameter(
      defaultValue = "${project.remoteProjectRepositories}",
      readonly = true,
      required = true)
  private List<RemoteRepository> remoteRepositories;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
  private RepositorySystemSession repositorySystemSession;

  @Component private RepositorySystem repositorySystem;

  @Override
  protected String targetArtifactId() {
    return "gimle-agent";
  }

  @Override
  protected List<String> buildCommand() throws MojoExecutionException {
    String workerClasspath =
        GimleProcesses.resolveRuntimeClasspath(
            "gimle-worker",
            projectVersion,
            remoteRepositories,
            repositorySystemSession,
            repositorySystem);

    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    if (transportProtocol != null && !transportProtocol.isBlank()) {
      command.add("-Dgimle.transport.protocol=" + transportProtocol);
    }
    command.add("-cp");
    command.add(String.join(File.pathSeparator, runtimeClasspathElements));
    command.add("com.gimle.agent.AgentMain");
    command.add(nodeId);
    command.add(controlPlaneUrl);
    command.add(gossipAddress);
    command.add("-");
    command.add(javaExecutable());
    command.add("-cp");
    command.add(workerClasspath);
    command.add("com.gimle.worker.WorkerMain");
    return command;
  }
}
