package com.gimle.hilmir.release;

import java.util.Optional;

/**
 * One {@code tenants[]} entry: a tenant a release creates before applying anything scoped to it.
 * Not itself subject to {@code ${values.*}} substitution -- only {@code config[].value}, {@code
 * secrets[].value}, and workload manifest text are.
 *
 * <p>An absent {@code isolationPosture} leaves whatever posture the tenant already has, which is
 * what the control plane does with the field omitted -- so a bundle that does not mention it never
 * silently reopens a tenant that was closed by hand.
 */
public record BundleTenant(String id, BundleQuota quota, Optional<String> isolationPosture) {}
