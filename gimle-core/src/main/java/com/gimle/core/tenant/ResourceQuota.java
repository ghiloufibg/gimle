package com.gimle.core.tenant;

/**
 * A tenant's resource ceiling (Phase 5 design §5.2): the sum of {@code resourceRequest * replicas}
 * across every deployment sharing a {@code tenantId} must not exceed these values. Checked at
 * admission (control-plane API) and continuously ({@code QuotaReconciler}) -- the same "reject
 * early, reject again where it actually matters" shape {@code ModuleDescriptor}'s request-vs-limit
 * check and {@code Scheduler.place}'s capacity check already establish elsewhere.
 */
public record ResourceQuota(long maxMemoryBytes, long maxCpuMillicores, int maxInstances) {

  public ResourceQuota {
    if (maxMemoryBytes < 0) {
      throw new IllegalArgumentException("maxMemoryBytes must not be negative: " + maxMemoryBytes);
    }
    if (maxCpuMillicores < 0) {
      throw new IllegalArgumentException(
          "maxCpuMillicores must not be negative: " + maxCpuMillicores);
    }
    if (maxInstances < 0) {
      throw new IllegalArgumentException("maxInstances must not be negative: " + maxInstances);
    }
  }
}
