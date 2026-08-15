package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:andvari} -- launches a real {@code AndvariMain} process using {@code
 * gimle-andvari}'s own resolved runtime classpath: the module artifact registry as its own process,
 * holding an immutable content-addressed store of module jars and talking to a {@code gimle-mimir}
 * store cluster over the network for its own independent {@code Authorizer} re-check, the same way
 * {@code gimle-muninn} and {@code gimle-fafnir} do. {@code gimle.andvari.storeEndpoints} defaults
 * to {@code gimle:store}'s own default client port, so the goals keep working together with zero
 * extra flags for single-node local dev. No-ops in every other reactor module (see {@link
 * AbstractGimleMojo}).
 */
@Mojo(name = "andvari", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class AndvariMojo extends AbstractGimleMojo {

  // 9094: next free port after store's raft (9080)/client (9091), the agent's own gossip default
  // (9090), fafnir (9092), muninn (9093), and the control plane's own default (8080).
  @Parameter(property = "gimle.andvari.port", defaultValue = "9094")
  private String port;

  @Parameter(
      property = "gimle.andvari.dataRoot",
      defaultValue = "${project.build.directory}/gimle-andvari-data")
  private String dataRoot;

  // Matches StoreMojo's own gimle.store.clientPort default (9091) -- see that Mojo's javadoc for
  // why 9091, not 9090.
  @Parameter(property = "gimle.andvari.storeEndpoints", defaultValue = "127.0.0.1:9091")
  private String storeEndpoints;

  @Parameter(property = "gimle.andvari.csrEndpoint")
  private String csrEndpoint;

  /**
   * {@code host:port,...} of every *other* Andvari replica this one peer-syncs its catalog against
   * -- unset (the default) is a single-replica registry with no peer sync at all, exactly this
   * Mojo's previous behavior.
   */
  @Parameter(property = "gimle.andvari.peerEndpoints")
  private String peerEndpoints;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol}, matching {@code MuninnMojo}/{@code
   * FafnirMojo}/{@code StoreMojo}/{@code ControlPlaneMojo}.
   */
  @Parameter(property = "gimle.andvari.transportProtocol")
  private String transportProtocol;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-andvari";
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
    command.add("com.gimle.andvari.AndvariMain");
    command.add(port);
    command.add("--store-endpoints");
    command.add(storeEndpoints);
    command.add("--data-root");
    command.add(dataRoot);
    if (csrEndpoint != null && !csrEndpoint.isBlank()) {
      command.add("--csr-endpoint");
      command.add(csrEndpoint);
    }
    if (peerEndpoints != null && !peerEndpoints.isBlank()) {
      command.add("--peer-endpoints");
      command.add(peerEndpoints);
    }
    return command;
  }
}
