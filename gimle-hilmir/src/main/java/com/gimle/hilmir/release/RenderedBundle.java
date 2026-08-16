package com.gimle.hilmir.release;

import java.util.List;

/**
 * A {@link Bundle} with every {@code ${values.*}} reference resolved and every workload's raw text
 * loaded and kind/name-parsed -- what {@link BundleApplier} actually applies and what a revision
 * snapshot actually records. {@code rollback} builds one of these straight from a stored {@link
 * ReleaseRevision} rather than from a freshly rendered {@link Bundle}, since the whole point of a
 * revision snapshot is to be able to re-apply it without the original bundle file.
 */
record RenderedBundle(
    String name,
    String version,
    List<BundleTenant> tenants,
    List<RenderedConfigEntry> config,
    List<RenderedSecretEntry> secrets,
    List<RenderedWorkload> workloads) {}
