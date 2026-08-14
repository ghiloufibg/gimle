package com.gimle.holmgang.topology;

import com.gimle.core.exception.GimleManifestException;

/** A seeded tenant's quota, mirroring the control plane's {@code /tenants/*} quota body. */
public record QuotaSpec(long maxMemoryBytes, long maxCpuMillicores, int maxInstances) {

  public QuotaSpec {
    if (maxMemoryBytes <= 0 || maxCpuMillicores <= 0 || maxInstances <= 0) {
      throw new GimleManifestException("quota values must all be positive");
    }
  }

  public static QuotaSpec of(
      final long maxMemoryBytes, final long maxCpuMillicores, final int maxInstances) {
    return new QuotaSpec(maxMemoryBytes, maxCpuMillicores, maxInstances);
  }
}
