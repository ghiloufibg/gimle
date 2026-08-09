package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:fafnir} -- launches a real {@code FafnirMain} process using {@code
 * gimle-fafnir}'s own resolved runtime classpath: the secrets service as its own process (design
 * doc), talking to a {@code gimle-mimir} store cluster over the network exactly the way {@code
 * gimle-controlplane} does. {@code gimle.fafnir.storeEndpoints} defaults to {@code gimle:store}'s
 * own default client port, so the goals keep working together with zero extra flags for single-node
 * local dev. No-ops in every other reactor module (see {@link AbstractGimleMojo}).
 */
@Mojo(name = "fafnir", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class FafnirMojo extends AbstractGimleMojo {

  // 9092: distinct from store's raft (9080)/client (9091), the agent's own gossip default (9090),
  // and the control plane's own default (8080).
  @Parameter(property = "gimle.fafnir.port", defaultValue = "9092")
  private String port;

  @Parameter(
      property = "gimle.fafnir.secretKeyPath",
      defaultValue = "${project.build.directory}/gimle-fafnir-state/secret.key")
  private String secretKeyPath;

  // Matches StoreMojo's own gimle.store.clientPort default (9091) -- see that Mojo's javadoc for
  // why 9091, not 9090.
  @Parameter(property = "gimle.fafnir.storeEndpoints", defaultValue = "127.0.0.1:9091")
  private String storeEndpoints;

  @Parameter(property = "gimle.fafnir.csrEndpoint")
  private String csrEndpoint;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol}, matching {@code StoreMojo}/{@code
   * ControlPlaneMojo}.
   */
  @Parameter(property = "gimle.fafnir.transportProtocol")
  private String transportProtocol;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-fafnir";
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
    command.add("com.gimle.fafnir.FafnirMain");
    command.add(port);
    command.add(secretKeyPath);
    command.add("--store-endpoints");
    command.add(storeEndpoints);
    if (csrEndpoint != null && !csrEndpoint.isBlank()) {
      command.add("--csr-endpoint");
      command.add(csrEndpoint);
    }
    return command;
  }
}
