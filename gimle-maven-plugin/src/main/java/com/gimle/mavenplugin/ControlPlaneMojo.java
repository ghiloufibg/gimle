package com.gimle.mavenplugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn gimle:controlplane} -- launches a real {@code ControlPlaneMain} process using {@code
 * gimle-controlplane}'s own resolved runtime classpath. Talks to a {@code gimle-mimir} store
 * cluster over the network rather than embedding one -- {@code gimle.controlplane.storeEndpoints}
 * defaults to {@code gimle:store}'s own default client port, and {@code
 * gimle.controlplane.fafnirEndpoint} defaults to {@code gimle:fafnir}'s own default port, so the
 * three goals keep working together with zero extra flags for single-node local dev. No-ops in
 * every other reactor module (see {@link AbstractGimleMojo}).
 */
@Mojo(
    name = "controlplane",
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true)
public final class ControlPlaneMojo extends AbstractGimleMojo {

  @Parameter(property = "gimle.controlplane.port", defaultValue = "8080")
  private String port;

  @Parameter(
      property = "gimle.controlplane.secretKeyPath",
      defaultValue = "${project.build.directory}/gimle-state/secret.key")
  private String secretKeyPath;

  // Matches StoreMojo's own gimle.store.clientPort default (9091) -- see that Mojo's javadoc for
  // why 9091, not 9090.
  @Parameter(property = "gimle.controlplane.storeEndpoints", defaultValue = "127.0.0.1:9091")
  private String storeEndpoints;

  // Matches FafnirMojo's own gimle.fafnir.port default (9092).
  @Parameter(property = "gimle.controlplane.fafnirEndpoint", defaultValue = "127.0.0.1:9092")
  private String fafnirEndpoint;

  /**
   * Local-dev convenience for {@code gimle.transport.protocol} -- unset by default (plaintext,
   * matching {@code TransportProtocol}'s own default), passed through as a {@code -D} JVM flag on
   * the spawned process when set, matching {@code gimle.tls.certFile}/{@code keyFile}/{@code
   * caFile}'s own plain system-property convention rather than inventing a parallel one just for
   * this Mojo.
   */
  @Parameter(property = "gimle.controlplane.transportProtocol")
  private String transportProtocol;

  /**
   * Comma-separated {@code ResourceKind} names to opt into READ-decision audit-trail coverage --
   * unset by default, matching {@code ApiServer}'s own pre-existing behavior of only auditing
   * {@code WRITE}/{@code DELETE}. See {@code gimle-docs/docs/architecture/authn-authz.md}'s Audit
   * logging section.
   */
  @Parameter(property = "gimle.controlplane.audit.readResourceKinds")
  private String auditReadResourceKinds;

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
    if (auditReadResourceKinds != null && !auditReadResourceKinds.isBlank()) {
      command.add("-Dgimle.controlplane.audit.readResourceKinds=" + auditReadResourceKinds);
    }
    command.add("-cp");
    command.add(String.join(File.pathSeparator, runtimeClasspathElements));
    command.add("com.gimle.controlplane.ControlPlaneMain");
    command.add(port);
    command.add(secretKeyPath);
    command.add("--store-endpoints");
    command.add(storeEndpoints);
    command.add("--fafnir-endpoint");
    command.add(fafnirEndpoint);
    return command;
  }
}
