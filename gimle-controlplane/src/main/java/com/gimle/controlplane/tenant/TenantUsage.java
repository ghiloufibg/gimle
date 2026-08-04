package com.gimle.controlplane.tenant;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.store.StoreReader;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;

/**
 * Shared quota-summation logic, used both at admission (the API server, before a deployment is
 * durably stored) and continuously ({@code QuotaReconciler}, every tick) -- one calculation, not
 * two copies that could drift. Reads each tenant deployment's module descriptor from its artifact
 * the same way {@code DeploymentReconciler} already does control-plane-side to learn a resource
 * request before any node has resolved anything; an unreadable artifact is skipped (not a resource
 * this calculation can charge against a tenant) the same way {@code DeploymentReconciler} itself
 * tolerates one and simply retries next tick.
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
   * Currently-assigned usage for {@code tenantId}, summed across every deployment sharing it
   * *except* {@code excludingDeploymentName} (pass {@code ""} to include everything) -- the
   * exclusion lets admission compute "what would usage be after this PUT replaces its own prior
   * spec" without double-counting the deployment being submitted.
   */
  public static Usage currentlyAssigned(
      StoreReader store, String tenantId, String excludingDeploymentName) {
    long memoryBytes = 0;
    long cpuMillicores = 0;
    int instances = 0;
    for (DeploymentSpec spec : store.listDeployments()) {
      if (spec.name().equals(excludingDeploymentName)) {
        continue;
      }
      if (spec.tenantId().filter(tenantId::equals).isEmpty()) {
        continue;
      }
      Usage contribution = contributionOf(store, spec);
      memoryBytes += contribution.memoryBytes();
      cpuMillicores += contribution.cpuMillicores();
      instances += contribution.instances();
    }
    return new Usage(memoryBytes, cpuMillicores, instances);
  }

  /** One deployment's own contribution: {@code resourceRequest * effective replicas}. */
  public static Usage contributionOf(StoreReader store, DeploymentSpec spec) {
    ModuleDescriptor descriptor;
    try {
      descriptor = ModuleArtifactReader.read(Path.of(spec.artifactPath())).descriptor();
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
