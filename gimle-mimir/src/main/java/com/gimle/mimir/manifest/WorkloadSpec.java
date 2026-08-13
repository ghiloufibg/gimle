package com.gimle.mimir.manifest;

import java.util.Optional;

/**
 * Sealed common supertype for every workload kind's own manifest-derived desired-state spec
 * (priority-3 design doc §2). Every manifest now carries a required top-level {@code kind:} field
 * (no default -- see {@link ManifestParser}'s own javadoc for why); this is the type that field
 * ultimately resolves to. Deliberately minimal -- {@code name()}/{@code tenantId()} are the only
 * two things every reconciler-facing consumer of a workload spec needs regardless of kind (RBAC
 * tenant scoping, store keying); everything else genuinely differs per kind and belongs on that
 * kind's own record, not forced into a shared shape here.
 *
 * <p>Only {@link DeploymentSpec}, {@link JobSpec}, {@link CronJobSpec}, and {@link DaemonSetSpec}
 * exist yet -- {@code StatefulSetSpec} is the last priority-3 step, adding itself to the {@code
 * permits} clause when it lands, not speculatively reserved here ahead of time.
 */
public sealed interface WorkloadSpec permits DeploymentSpec, JobSpec, CronJobSpec, DaemonSetSpec {

  String name();

  Optional<String> tenantId();
}
