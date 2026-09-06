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

  // Matches FafnirMojo's own gimle.fafnir.port default (9092), same convention as
  // ControlPlaneMojo#fafnirEndpoint. Never null here (unlike AgentMain's own optional system
  // property) since this convenience goal always has a sensible local-dev default to fall back on.
  @Parameter(property = "gimle.agent.fafnirEndpoint", defaultValue = "127.0.0.1:9092")
  private String fafnirEndpoint;

  // Unset by default, unlike fafnirEndpoint above: an agent whose assignments all carry an
  // explicit artifactPath never resolves a registry coordinate at all, and a local-dev session
  // running no registry process shouldn't have its agent pointed at a dead port.
  @Parameter(property = "gimle.agent.andvariEndpoint")
  private String andvariEndpoint;

  /**
   * {@code host:port,...} of every Muninn replica this agent ships its own platform log to, plus
   * every supervised worker's logs, metrics and traces (a worker has no outbound network identity
   * of its own, so its agent forwards for it). Unset by default for the same reason {@code
   * andvariEndpoint} above is: shipping to an address where nothing is listening buys a local-dev
   * session nothing but a retry every interval. Point it at {@code gimle:muninn}'s own default port
   * ({@code 127.0.0.1:9093}) whenever that goal is running too.
   */
  @Parameter(property = "gimle.agent.muninnEndpoint")
  private String muninnEndpoint;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol} -- same shape as {@code
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
    command.add("-Dgimle.agent.fafnirEndpoint=" + fafnirEndpoint);
    if (andvariEndpoint != null && !andvariEndpoint.isBlank()) {
      command.add("-Dgimle.agent.andvariEndpoint=" + andvariEndpoint);
    }
    if (muninnEndpoint != null && !muninnEndpoint.isBlank()) {
      command.add("-Dgimle.agent.muninnEndpoint=" + muninnEndpoint);
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
