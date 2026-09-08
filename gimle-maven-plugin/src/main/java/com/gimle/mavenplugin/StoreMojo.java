package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:store} -- launches a real {@code StoreMain} process using {@code gimle-mimir}'s
 * own resolved runtime classpath: the Raft-replicated state store as its own process, what {@code
 * mvn gimle:controlplane} used to embed directly before the split. No-ops in every other reactor
 * module (see {@link AbstractGimleMojo}).
 */
@Mojo(name = "store", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
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

  /**
   * The store's own TLS leaf certificate/key and the cluster CA certificate, forwarded verbatim as
   * {@code -Dgimle.tls.certFile}/{@code keyFile}/{@code caFile} -- the exact system properties
   * {@code StoreMain} itself reads, and the same plain (not {@code gimle.store}-namespaced)
   * property names {@link BootstrapMojo#addTlsFlags} uses, so a bare {@code
   * -Dgimle.tls.certFile=...} on the {@code mvn} command line binds straight into this parameter
   * with no separate flag to learn. Unset by default like {@link #transportProtocol}: only
   * meaningful once {@code gimle.store.transportProtocol=tls} is also set.
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
    return "gimle-mimir";
  }

  @Override
  protected List<String> buildCommand() {
    return buildCommand(
        javaExecutable(),
        String.join(File.pathSeparator, runtimeClasspathElements),
        stateDir,
        raftPort,
        clientPort,
        peers,
        csrEndpoint,
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
      String stateDir,
      String raftPort,
      String clientPort,
      String peers,
      String csrEndpoint,
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
