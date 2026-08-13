package com.gimle.mimir.manifest;

import java.util.Optional;

/**
 * Sealed common supertype for every workload kind's own manifest-derived desired-state spec. Every
 * manifest now carries a required top-level {@code kind:} field (no default -- see {@link
 * ManifestParser}'s own javadoc for why); this is the type that field ultimately resolves to.
 * Deliberately minimal -- {@code name()}/{@code tenantId()} are the only two things every
 * reconciler-facing consumer of a workload spec needs regardless of kind (RBAC tenant scoping,
 * store keying); everything else genuinely differs per kind and belongs on that kind's own record,
 * not forced into a shared shape here.
 *
 * <p>{@link DeploymentSpec}, {@link JobSpec}, {@link CronJobSpec}, {@link DaemonSetSpec}, and
 * {@link StatefulSetSpec} complete this permits clause.
 */
public sealed interface WorkloadSpec
    permits DeploymentSpec, JobSpec, CronJobSpec, DaemonSetSpec, StatefulSetSpec {

  String name();

  Optional<String> tenantId();
}
