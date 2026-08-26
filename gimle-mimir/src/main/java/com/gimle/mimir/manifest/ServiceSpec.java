package com.gimle.mimir.manifest;

import java.util.Optional;
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
 * containerPort} pair makes, kept even though today's only two "instance listens on a real port"
 * cases ({@code VesselEnvValue.PortAllocation} and a module binding a server socket directly) both
 * already know their own port, because a Service consumer (the reconciler, {@code gimle-bifrost})
 * still needs to know which port to forward *to* without re-deriving it from those per-workload
 * shapes each time.
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
 * that external hostname (at {@code targetPort}) instead of selecting in-cluster instances --
 * useful while migrating a dependency into the cluster. The two shapes are exclusive: an
 * ExternalName Service names no deployments, and a selector Service names no external host.
 */
public record ServiceSpec(
    String name,
    Optional<String> tenantId,
    Set<String> deploymentNames,
    int port,
    int targetPort,
    boolean sessionAffinity,
    Optional<String> externalName) {

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
    if (externalName == null) {
      throw new IllegalArgumentException("externalName must be Optional.empty(), not null");
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
    requirePort(targetPort, "targetPort");
    deploymentNames = Set.copyOf(deploymentNames);
  }

  private static void requirePort(int value, String fieldName) {
    if (value < 1 || value > 65535) {
      throw new IllegalArgumentException(fieldName + " must be in [1, 65535], got " + value);
    }
  }

  /** Convenience: a selector Service with no affinity, distinct {@code port}/{@code targetPort}. */
  public ServiceSpec(
      String name,
      Optional<String> tenantId,
      Set<String> deploymentNames,
      int port,
      int targetPort) {
    this(name, tenantId, deploymentNames, port, targetPort, false, Optional.empty());
  }

  /** Convenience: a Service whose {@code targetPort} equals its own {@code port}. */
  public ServiceSpec(
      String name, Optional<String> tenantId, Set<String> deploymentNames, int port) {
    this(name, tenantId, deploymentNames, port, port);
  }

  /** Whether this is the ExternalName shape rather than a deployment-selecting one. */
  public boolean isExternalName() {
    return externalName.isPresent();
  }
}
