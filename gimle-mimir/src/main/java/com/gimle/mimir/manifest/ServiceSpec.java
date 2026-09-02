package com.gimle.mimir.manifest;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Desired state for one Service: a stable name a caller can address instead of chasing the real,
 * ephemeral addresses {@code ServiceEndpoint}/{@code ServiceCatalog} hold underneath -- the
 * ClusterIP analogue named in the platform's own network-model design. {@code deploymentNames} is
 * the selector: the specific {@link DeploymentSpec}/DaemonSet workload names this Service fronts,
 * matched by name rather than a label-expression system -- deliberately the simplest selector shape
 * that's actually buildable against state the platform already has (a {@code DeploymentSpec}
 * already carries a unique {@code name} within its tenant), not a redesign of workload labeling. A
 * label-expression selector is a natural later extension, not a prerequisite: nothing here
 * forecloses it.
 *
 * <p>{@code port} is what a caller dials the Service on; {@code targetPort} is what the backing
 * instances actually listen on -- the same split Kubernetes' own {@code Service}/{@code
 * containerPort} pair makes. It is genuinely optional here rather than defaulting to {@code port},
 * because instance ports are reported at runtime (a vessel's allocated port, a module's own {@code
 * ModuleContext#reportPort}) and are routinely ephemeral: a declared {@code targetPort} is
 * authoritative -- endpoint resolution picks exactly that port on each backing instance and
 * excludes any instance not reporting it -- while an absent one means "whatever single port the
 * instance reports", the only unambiguous choice when nothing names one. Defaulting it to {@code
 * port} would quietly turn every ephemeral-port workload into an empty endpoint set.
 *
 * <p>{@code tenantId} is optional, matching every other tenant-scoping field in this package
 * ({@code DeploymentSpec#tenantId()}): a Service with no {@code tenantId} is untenanted, consistent
 * with an untenanted Deployment.
 *
 * <p>{@code sessionAffinity} is the {@code sessionAffinity: ClientIP} analogue: {@code true} asks a
 * forwarding proxy ({@code gimle-bifrost}) to pin each caller address to one backend via a
 * consistent hash rather than round-robining -- purely a hint to the proxy layer, with no effect on
 * DNS answers or the fabric's own in-process load balancing.
 *
 * <p>{@code externalName} present makes this the ExternalName analogue: the Service resolves to
 * that external hostname (at {@code targetPort}, or {@code port} when none is declared) instead of
 * selecting in-cluster instances -- useful while migrating a dependency into the cluster. The two
 * shapes are exclusive: an ExternalName Service names no deployments, and a selector Service names
 * no external host.
 *
 * <p>{@code protocol} is the {@code Service.spec.ports[].protocol} analogue and decides what {@code
 * gimle-bifrost} binds for this Service -- a stream listener for {@link ServiceProtocol#TCP}, a
 * datagram listener for {@link ServiceProtocol#UDP}. It has no effect on the fabric's own
 * in-process calls or on the DNS answers {@code gimle-skald} serves, both of which are
 * transport-independent.
 */
public record ServiceSpec(
    String name,
    Optional<String> tenantId,
    Set<String> deploymentNames,
    int port,
    OptionalInt targetPort,
    boolean sessionAffinity,
    Optional<String> externalName,
    ServiceProtocol protocol) {

  public ServiceSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("service name must not be blank");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be Optional.empty(), not null");
    }
    if (deploymentNames == null) {
      throw new IllegalArgumentException("deploymentNames must not be null");
    }
    if (targetPort == null) {
      throw new IllegalArgumentException("targetPort must be OptionalInt.empty(), not null");
    }
    if (externalName == null) {
      throw new IllegalArgumentException("externalName must be Optional.empty(), not null");
    }
    if (protocol == null) {
      throw new IllegalArgumentException("protocol must not be null; use ServiceProtocol.TCP");
    }
    if (externalName.isPresent()) {
      if (externalName.get().isBlank()) {
        throw new IllegalArgumentException("externalName must not be blank when present");
      }
      if (!deploymentNames.isEmpty()) {
        throw new IllegalArgumentException(
            "an ExternalName service must not also name deploymentNames");
      }
    } else if (deploymentNames.isEmpty()) {
      throw new IllegalArgumentException(
          "deploymentNames must not be empty (or declare externalName instead)");
    }
    for (String deploymentName : deploymentNames) {
      if (deploymentName == null || deploymentName.isBlank()) {
        throw new IllegalArgumentException("deploymentNames must not contain a blank entry");
      }
    }
    requirePort(port, "port");
    if (targetPort.isPresent()) {
      requirePort(targetPort.getAsInt(), "targetPort");
    }
    deploymentNames = Set.copyOf(deploymentNames);
  }

  /** Defaults {@code protocol} to {@link ServiceProtocol#TCP}, what declaring none means. */
  public ServiceSpec(
      String name,
      Optional<String> tenantId,
      Set<String> deploymentNames,
      int port,
      OptionalInt targetPort,
      boolean sessionAffinity,
      Optional<String> externalName) {
    this(
        name,
        tenantId,
        deploymentNames,
        port,
        targetPort,
        sessionAffinity,
        externalName,
        ServiceProtocol.TCP);
  }

  private static void requirePort(int value, String fieldName) {
    if (value < 1 || value > 65535) {
      throw new IllegalArgumentException(fieldName + " must be in [1, 65535], got " + value);
    }
  }

  /** Convenience: a selector Service with no affinity and an explicitly declared target port. */
  public ServiceSpec(
      String name,
      Optional<String> tenantId,
      Set<String> deploymentNames,
      int port,
      int targetPort) {
    this(
        name, tenantId, deploymentNames, port, OptionalInt.of(targetPort), false, Optional.empty());
  }

  /** Convenience: a selector Service declaring no target port at all. */
  public ServiceSpec(
      String name, Optional<String> tenantId, Set<String> deploymentNames, int port) {
    this(name, tenantId, deploymentNames, port, OptionalInt.empty(), false, Optional.empty());
  }

  /** Whether this is the ExternalName shape rather than a deployment-selecting one. */
  public boolean isExternalName() {
    return externalName.isPresent();
  }
}
