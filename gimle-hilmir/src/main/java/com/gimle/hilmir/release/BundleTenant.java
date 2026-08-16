package com.gimle.hilmir.release;

/**
 * One {@code tenants[]} entry: a tenant a release creates before applying anything scoped to it.
 * Not itself subject to {@code ${values.*}} substitution -- only {@code config[].value}, {@code
 * secrets[].value}, and workload manifest text are.
 */
public record BundleTenant(String id, BundleQuota quota) {}
