package com.gimle.hilmir.release;

import java.util.List;
import java.util.Map;

/**
 * A parsed {@code kind: Bundle} manifest: a named, versioned collection of tenants, plain config
 * entries, secrets, and workload manifests deployed together as one release. {@code values} holds
 * the bundle's own default substitution values, later merged with {@code --values}/{@code --set}
 * overrides by {@link ValueOverrides} before {@link BundleRenderer} resolves every {@code
 * ${values.*}} reference.
 */
public record Bundle(
    String name,
    String version,
    Map<String, String> values,
    List<BundleTenant> tenants,
    List<BundleConfigEntry> config,
    List<BundleSecretEntry> secrets,
    List<BundleWorkload> workloads) {}
