package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:store} -- launches a real {@code StoreMain} process using {@code gimle-mimir}'s
 * own resolved runtime classpath: the Raft-replicated state store as its own process
 * (etcd-store-extraction design doc), what {@code mvn gimle:controlplane} used to embed directly
 * before the split. No-ops in every other reactor module (see {@link AbstractGimleMojo}).
 */
@Mojo(name = "store", requiresDependencyResolution = ResolutionScope.RUNTIME)
public final class StoreMojo extends AbstractGimleMojo {

  @Parameter(
      property = "gimle.store.stateDir",
      defaultValue = "${project.build.directory}/gimle-mimir-state")
  private String stateDir;

  @Parameter(property = "gimle.store.raftPort", defaultValue = "9080")
  private String raftPort;

  // 9091, not 9090: gimle:agent's own gossip address already defaults to 127.0.0.1:9090 --
  // running the full local-dev stack (store + control plane + agent) on one machine needs these
  // to not collide.
  @Parameter(property = "gimle.store.clientPort", defaultValue = "9091")
  private String clientPort;

  @Parameter(property = "gimle.store.peers")
  private String peers;

  @Parameter(property = "gimle.store.csrEndpoint")
  private String csrEndpoint;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol}, matching {@code ControlPlaneMojo}.
   */
  @Parameter(property = "gimle.store.transportProtocol")
  private String transportProtocol;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-mimir";
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
    command.add("com.gimle.mimir.StoreMain");
    command.add(stateDir);
    command.add(raftPort);
    command.add(clientPort);
    if (peers != null && !peers.isBlank()) {
      command.add("--peers");
      command.add(peers);
    }
    if (csrEndpoint != null && !csrEndpoint.isBlank()) {
      command.add("--csr-endpoint");
      command.add(csrEndpoint);
    }
    return command;
  }
}
