package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:muninn} -- launches a real {@code MuninnMain} process using {@code
 * gimle-muninn}'s own resolved runtime classpath: the unified logs/metrics/traces sink as its own
 * process, talking to a {@code gimle-mimir} store cluster over the network for its own read-only
 * {@code Authorizer} check, the same way {@code gimle-fafnir} does. {@code
 * gimle.muninn.storeEndpoints} defaults to {@code gimle:store}'s own default client port, so the
 * goals keep working together with zero extra flags for single-node local dev. No-ops in every
 * other reactor module (see {@link AbstractGimleMojo}).
 */
@Mojo(name = "muninn", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class MuninnMojo extends AbstractGimleMojo {

  // 9093: next free port after store's raft (9080)/client (9091), the agent's own gossip default
  // (9090), fafnir (9092), and the control plane's own default (8080).
  @Parameter(property = "gimle.muninn.port", defaultValue = "9093")
  private String port;

  @Parameter(
      property = "gimle.muninn.dataRoot",
      defaultValue = "${project.build.directory}/gimle-muninn-data")
  private String dataRoot;

  // Matches StoreMojo's own gimle.store.clientPort default (9091) -- see that Mojo's javadoc for
  // why 9091, not 9090.
  @Parameter(property = "gimle.muninn.storeEndpoints", defaultValue = "127.0.0.1:9091")
  private String storeEndpoints;

  @Parameter(property = "gimle.muninn.csrEndpoint")
  private String csrEndpoint;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol}, matching {@code FafnirMojo}/{@code
   * StoreMojo}/{@code ControlPlaneMojo}.
   */
  @Parameter(property = "gimle.muninn.transportProtocol")
  private String transportProtocol;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-muninn";
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
    command.add("com.gimle.muninn.MuninnMain");
    command.add(port);
    command.add("--store-endpoints");
    command.add(storeEndpoints);
    command.add("--data-root");
    command.add(dataRoot);
    if (csrEndpoint != null && !csrEndpoint.isBlank()) {
      command.add("--csr-endpoint");
      command.add(csrEndpoint);
    }
    return command;
  }
}
