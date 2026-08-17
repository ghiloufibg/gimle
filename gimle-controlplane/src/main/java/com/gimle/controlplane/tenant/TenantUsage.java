package com.gimle.controlplane.tenant;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.store.StoreReader;
import java.util.Optional;

/**
 * Shared quota-summation logic, used both at admission (the API server, before a deployment is
 * durably stored) and continuously ({@code QuotaReconciler}, every tick) -- one calculation, not
 * two copies that could drift. Reads each tenant deployment's module descriptor from its artifact
 * through the same {@link ArtifactResolver} every reconciler already uses (local path or
 * registry-coordinate alike -- a blank {@code artifactPath} resolved through Andvari is a
 * documented, fully-wired deployment shape, not a corner case); an unreadable/unresolvable artifact
 * is skipped (not a resource this calculation can charge against a tenant) the same way {@code
 * DeploymentReconciler} itself tolerates one and simply retries next tick.
 */
public final class TenantUsage {

  private TenantUsage() {}

  public record Usage(long memoryBytes, long cpuMillicores, int instances) {

    public Usage plus(long moreMemoryBytes, long moreCpuMillicores, int moreInstances) {
      return new Usage(
          memoryBytes + moreMemoryBytes,
          cpuMillicores + moreCpuMillicores,
          instances + moreInstances);
    }

    public boolean exceeds(ResourceQuota quota) {
      return memoryBytes > quota.maxMemoryBytes()
          || cpuMillicores > quota.maxCpuMillicores()
          || instances > quota.maxInstances();
    }
  }

  /**
   * Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. See
   * {@link #currentlyAssigned(StoreReader, ArtifactResolver, String, Optional)} for real wiring
   * through a control plane's shared resolver.
   */
  public static Usage currentlyAssigned(
      StoreReader store, String tenantId, Optional<String> excludingDeploymentName) {
    return currentlyAssigned(
        store, ArtifactResolver.localOnly(), tenantId, excludingDeploymentName);
  }

  /**
   * Currently-assigned usage for {@code tenantId}, summed across every deployment sharing it
   * *except* {@code excludingDeploymentName} (pass {@code Optional.empty()} to include everything)
   * -- the exclusion lets admission compute "what would usage be after this PUT replaces its own
   * prior spec" without double-counting the deployment being submitted.
   */
  public static Usage currentlyAssigned(
      StoreReader store,
      ArtifactResolver artifactResolver,
      String tenantId,
      Optional<String> excludingDeploymentName) {
    long memoryBytes = 0;
    long cpuMillicores = 0;
    int instances = 0;
    for (DeploymentSpec spec : store.listDeployments()) {
      if (excludingDeploymentName.filter(spec.name()::equals).isPresent()) {
        continue;
      }
      if (spec.tenantId().filter(tenantId::equals).isEmpty()) {
        continue;
      }
      Usage contribution = contributionOf(store, artifactResolver, spec);
      memoryBytes += contribution.memoryBytes();
      cpuMillicores += contribution.cpuMillicores();
      instances += contribution.instances();
    }
    return new Usage(memoryBytes, cpuMillicores, instances);
  }

  /**
   * Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. See
   * {@link #contributionOf(StoreReader, ArtifactResolver, DeploymentSpec)} for real wiring through
   * a control plane's shared resolver.
   */
  public static Usage contributionOf(StoreReader store, DeploymentSpec spec) {
    return contributionOf(store, ArtifactResolver.localOnly(), spec);
  }

  /** One deployment's own contribution: {@code resourceRequest * effective replicas}. */
  public static Usage contributionOf(
      StoreReader store, ArtifactResolver artifactResolver, DeploymentSpec spec) {
    ModuleDescriptor descriptor;
    try {
      // A registry-coordinate deployment (blank artifactPath, resolved through Andvari) must be
      // charged the same resource request DeploymentReconciler actually schedules against, not
      // silently read as zero usage because a direct ModuleArtifactReader/VesselArtifacts read
      // can't resolve it -- see this class's own javadoc.
      descriptor =
          artifactResolver
              .resolve(spec.artifactPath(), spec.moduleId(), spec.vessel())
              .descriptor();
    } catch (RuntimeException e) {
      return new Usage(0, 0, 0);
    }
    int replicas = store.getEffectiveReplicas(spec.name()).orElse(spec.replicas());
    return new Usage(
        descriptor.resourceRequest().memoryBytes() * replicas,
        descriptor.resourceRequest().cpuMillicores() * replicas,
        replicas);
  }
}
