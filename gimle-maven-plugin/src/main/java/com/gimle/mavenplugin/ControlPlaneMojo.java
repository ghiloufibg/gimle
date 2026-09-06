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
 * defaults to {@code gimle:store}'s own default client port, {@code
 * gimle.controlplane.fafnirEndpoint} defaults to {@code gimle:fafnir}'s own default port, and
 * {@code gimle.controlplane.andvariEndpoint} defaults to {@code gimle:andvari}'s own default port,
 * so the four goals keep working together with zero extra flags for single-node local dev. {@code
 * gimle.controlplane.muninnEndpoint} is the one address left unset by default -- see the parameter
 * itself for why. No-ops in every other reactor module (see {@link AbstractGimleMojo}).
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

  // Matches AndvariMojo's own gimle.andvari.port default (9094). Optional at ControlPlaneMain's
  // own level -- a cluster with no Andvari reachable keeps working on local-artifactPath manifests
  // unchanged -- but defaulted here anyway, the same "zero extra flags for single-node local dev"
  // posture storeEndpoints/fafnirEndpoint above already take, so gimle:publish and a
  // coordinate-only gimle:deploy work against a plain `mvn gimle:controlplane` without the operator
  // having to know this flag exists.
  @Parameter(property = "gimle.controlplane.andvariEndpoint", defaultValue = "127.0.0.1:9094")
  private String andvariEndpoint;

  /**
   * {@code host:port,...} of every Muninn replica this control plane ships its own metrics and
   * traces to, and falls back to when serving {@code /logs/*} for a node or instance that no longer
   * exists. Unset by default, unlike the three addresses above: shipping to an address where
   * nothing is listening buys a local-dev session nothing but a retry every interval, and a session
   * that never starts {@code gimle:muninn} is the common one. Point it at {@code gimle:muninn}'s
   * own default port ({@code 127.0.0.1:9093}) whenever that goal is running too.
   */
  @Parameter(property = "gimle.controlplane.muninnEndpoint")
  private String muninnEndpoint;

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

  /**
   * Comma-separated console addon ids to advertise ({@code none} for no addon at all) -- unset by
   * default, which advertises every addon the bundled console carries. Forwarded as a plain {@code
   * -D} system property, the same way {@code auditReadResourceKinds} above is, rather than as a
   * flag the process would have to learn.
   */
  @Parameter(property = "gimle.controlplane.consoleAddons")
  private String consoleAddons;

  @Parameter(defaultValue = "${project.runtimeClasspathElements}", readonly = true, required = true)
  private List<String> runtimeClasspathElements;

  @Override
  protected String targetArtifactId() {
    return "gimle-controlplane";
  }

  @Override
  protected List<String> buildCommand() {
    return buildCommand(
        javaExecutable(),
        String.join(File.pathSeparator, runtimeClasspathElements),
        port,
        secretKeyPath,
        storeEndpoints,
        fafnirEndpoint,
        andvariEndpoint,
        muninnEndpoint,
        transportProtocol,
        auditReadResourceKinds,
        consoleAddons);
  }

  /**
   * Pure command construction, split out from {@link #buildCommand()} so it's unit-testable without
   * Maven's own parameter-injection machinery -- the same seam {@link InitMojo#buildCommand}
   * establishes.
   */
  static List<String> buildCommand(
      String javaExecutable,
      String classpath,
      String port,
      String secretKeyPath,
      String storeEndpoints,
      String fafnirEndpoint,
      String andvariEndpoint,
      String muninnEndpoint,
      String transportProtocol,
      String auditReadResourceKinds,
      String consoleAddons) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable);
    if (transportProtocol != null && !transportProtocol.isBlank()) {
      command.add("-Dgimle.transport.protocol=" + transportProtocol);
    }
    if (auditReadResourceKinds != null && !auditReadResourceKinds.isBlank()) {
      command.add("-Dgimle.controlplane.audit.readResourceKinds=" + auditReadResourceKinds);
    }
    if (consoleAddons != null && !consoleAddons.isBlank()) {
      command.add("-Dgimle.controlplane.consoleAddons=" + consoleAddons);
    }
    command.add("-cp");
    command.add(classpath);
    command.add("com.gimle.controlplane.ControlPlaneMain");
    command.add(port);
    command.add(secretKeyPath);
    command.add("--store-endpoints");
    command.add(storeEndpoints);
    command.add("--fafnir-endpoint");
    command.add(fafnirEndpoint);
    if (andvariEndpoint != null && !andvariEndpoint.isBlank()) {
      command.add("--andvari-endpoint");
      command.add(andvariEndpoint);
    }
    if (muninnEndpoint != null && !muninnEndpoint.isBlank()) {
      command.add("--muninn-endpoint");
      command.add(muninnEndpoint);
    }
    return command;
  }
}
