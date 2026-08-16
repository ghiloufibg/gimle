package com.gimle.hilmir.release;

/**
 * A bundle-declared tenant's resource quota -- the same three fields the control plane's own {@code
 * PUT /tenants/{id}} body carries.
 */
public record BundleQuota(long maxMemoryBytes, long maxCpuMillicores, int maxInstances) {}
