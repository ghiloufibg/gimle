package com.gimle.mimir.store;

import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import java.util.OptionalInt;

/**
 * One immutable, point-in-time snapshot of a Deployment/StatefulSet/DaemonSet's admitted spec --
 * mirrors Kubernetes' own {@code ControllerRevision} (used natively by {@code StatefulSet}/{@code
 * DaemonSet}, not the heavier Pod-owning {@code ReplicaSet}, which has no equivalent need here:
 * {@code DeploymentReconciler} already owns instances directly by {@code (deploymentName, index)},
 * never through a label selector). Job/CronJob are deliberately excluded -- run-to-completion
 * workloads have no "roll back to an earlier desired state" concept.
 *
 * <p>{@code workloadKind} is one of the exact literal {@code kind:} strings every manifest parser
 * already uses ({@code "Deployment"}, {@code "StatefulSet"}, {@code "DaemonSet"}) -- kept as a
 * plain field (the same posture {@code AuditEvent#resourceKind()} already takes) rather than
 * re-derived from {@code spec}'s own runtime type on every read, and used to build the composite
 * {@link #revisionKey} this revision is stored/looked-up under: Deployment/StatefulSet/DaemonSet
 * names are not unique across kinds (each kind lives in its own {@link StateStore} map), so {@code
 * name} alone would collide.
 *
 * <p>{@code revision} is a positive, monotonically increasing integer scoped to one {@code
 * (workloadKind, name)} pair, starting at 1 -- assigned by whoever proposes the {@code
 * StateMutation.AppendControllerRevision} mutation (the control plane's own admission handlers),
 * never by {@link StateStore} itself: applying an already-decided mutation during Raft log replay
 * or snapshot install must stay a pure function of the mutation and the store's current content, it
 * can't re-derive "was this a fresh apply or a deliberate rollback."
 *
 * <p>{@code rollbackOfRevision} is present only when this revision's content was restored from an
 * earlier one via a rollback -- the same forward-only "restore = new revision, never rewrite
 * history" semantics {@code SecretMapStore#rollback} and {@code gimle-hilmir}'s own release
 * rollback already establish, recorded the same {@code OptionalInt} way {@code
 * SecretMapGroupVersion#rollbackOfGroupVersion} already does.
 */
public record ControllerRevision(
    String workloadKind,
    String name,
    int revision,
    WorkloadSpec spec,
    long createdAtEpochMilli,
    OptionalInt rollbackOfRevision) {

  public ControllerRevision {
    if (workloadKind == null
        || !(workloadKind.equals("Deployment")
            || workloadKind.equals("StatefulSet")
            || workloadKind.equals("DaemonSet"))) {
      throw new IllegalArgumentException(
          "workloadKind must be Deployment, StatefulSet, or DaemonSet, not: " + workloadKind);
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (revision < 1) {
      throw new IllegalArgumentException("revision must be positive: " + revision);
    }
    if (spec == null) {
      throw new IllegalArgumentException("spec must not be null");
    }
    if (!specMatchesKind(workloadKind, spec)) {
      throw new IllegalArgumentException(
          "spec is a " + spec.getClass().getSimpleName() + ", not a " + workloadKind);
    }
    if (!spec.name().equals(name)) {
      throw new IllegalArgumentException(
          "spec.name() '" + spec.name() + "' does not match name '" + name + "'");
    }
    if (rollbackOfRevision == null) {
      throw new IllegalArgumentException("rollbackOfRevision must be OptionalInt, not null");
    }
    if (rollbackOfRevision.isPresent() && rollbackOfRevision.getAsInt() < 1) {
      throw new IllegalArgumentException("rollbackOfRevision must be positive when present");
    }
  }

  private static boolean specMatchesKind(String workloadKind, WorkloadSpec spec) {
    return switch (spec) {
      case DeploymentSpec ignored -> workloadKind.equals("Deployment");
      case StatefulSetSpec ignored -> workloadKind.equals("StatefulSet");
      case DaemonSetSpec ignored -> workloadKind.equals("DaemonSet");
      case JobSpec ignored -> false;
      case CronJobSpec ignored -> false;
    };
  }

  /** The composite {@link StateStore}/wire key one workload's revision history is grouped under. */
  public static String revisionKey(String workloadKind, String name) {
    return workloadKind + "#" + name;
  }
}
