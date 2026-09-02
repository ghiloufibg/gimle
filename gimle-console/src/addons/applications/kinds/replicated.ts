import type { DaemonSet, Deployment, InstanceObservation, Service, StatefulSet } from "@/types";
import {
  applicationKey,
  instanceHealth,
  servicesFronting,
  worstHealth,
  type Application,
  type ApplicationCondition,
  type ApplicationInstance,
  type ApplicationKind,
  type HealthStatus,
  type SyncStatus,
} from "@/addons/applications/model";

/**
 * The three kinds that run forever and carry placed instances: Deployment, StatefulSet, DaemonSet.
 * They share one set of rules because they differ only in how many instances are wanted -- a
 * manifest number for two of them, "one per eligible node" for the third.
 */

/** What the three kinds have in common once their own shapes are read. */
interface ReplicatedFacts {
  kind: ApplicationKind;
  name: string;
  tenantId: string | null;
  moduleId: { name: string; version: string };
  artifactPath: string;
  /** `null` for a DaemonSet, which is never short of a number it was asked for. */
  desiredReplicas: number | null;
  unplacedCount: number;
  requiredNodeLabels: string[];
  quotaViolating: boolean;
  limitRangeViolating: boolean;
  limitRangeViolationReason?: string;
  instances: ApplicationInstance[];
}

function conditionsOf(w: ReplicatedFacts): ApplicationCondition[] {
  const out: ApplicationCondition[] = [];
  if (w.quotaViolating) {
    out.push({
      severity: "bad",
      type: "QuotaViolation",
      message: "the tenant's quota is exceeded; no further replica will be placed until it fits",
    });
  }
  if (w.limitRangeViolating) {
    out.push({
      severity: "bad",
      type: "LimitRangeViolation",
      message:
        w.limitRangeViolationReason ??
        "the module's resources fall outside the tenant's LimitRange",
    });
  }
  if (w.unplacedCount > 0) {
    const plural = w.unplacedCount === 1 ? "replica has" : "replicas have";
    out.push({
      severity: "bad",
      type: "Unplaced",
      message: `${w.unplacedCount} ${plural} no feasible placement on any node`,
    });
  }
  if (w.desiredReplicas !== null && w.unplacedCount === 0) {
    const placed = w.instances.length;
    if (placed < w.desiredReplicas) {
      out.push({
        severity: "warn",
        type: "ScalingUp",
        message: `${placed} of ${w.desiredReplicas} desired replicas placed`,
      });
    } else if (placed > w.desiredReplicas) {
      out.push({
        severity: "warn",
        type: "ScalingDown",
        message: `${placed} placed for ${w.desiredReplicas} desired; the surplus is draining`,
      });
    }
  }
  if (w.desiredReplicas === null && w.instances.length === 0) {
    out.push({
      severity: "warn",
      type: "NoEligibleNode",
      message:
        w.requiredNodeLabels.length > 0
          ? `no node carries the required labels: ${w.requiredNodeLabels.join(", ")}`
          : "no node currently runs an instance of this DaemonSet",
    });
  }
  for (const i of w.instances) {
    const o = i.observation;
    if (i.health === "Degraded") {
      const why = o.lifecycleState === "ACTIVE" ? "ACTIVE but not alive" : o.lifecycleState;
      out.push({
        severity: "bad",
        type: "InstanceDegraded",
        message: `${i.label} on ${i.nodeId} is ${why}`,
      });
    } else if (o.lifecycleState === "ACTIVE" && !o.ready) {
      out.push({
        severity: "warn",
        type: "InstanceNotReady",
        message: `${i.label} on ${i.nodeId} is ACTIVE but not ready`,
      });
    }
  }
  return out;
}

function healthOf(w: ReplicatedFacts, conditions: readonly ApplicationCondition[]): HealthStatus {
  if (conditions.some((c) => c.severity === "bad")) return "Degraded";
  // Nothing placed and nothing wanted: a scaled-to-zero deployment and a DaemonSet no node matches
  // are both "there is nothing to be healthy about", not a green verdict over an empty set.
  if (w.instances.length === 0) return "Unknown";
  if (w.desiredReplicas !== null && w.instances.length < w.desiredReplicas) return "Progressing";
  return worstHealth(w.instances.map((i) => i.health)) ?? "Progressing";
}

/**
 * Sync is about placement, not lifecycle: the manifest asks for N replicas and the cluster either
 * carries N placed ones with nothing blocking admission, or it does not. A placed replica that is
 * still starting or has since crashed is the health axis's business, so an application stays
 * Synced through a crash loop -- the desired state was reached, it just is not healthy.
 */
function syncOf(w: ReplicatedFacts): SyncStatus {
  if (w.quotaViolating || w.limitRangeViolating) return "OutOfSync";
  if (w.unplacedCount > 0) return "OutOfSync";
  if (w.desiredReplicas !== null && w.instances.length !== w.desiredReplicas) return "OutOfSync";
  return "Synced";
}

function indexedInstance(
  instanceIndex: number,
  nodeId: string,
  observation: InstanceObservation,
): ApplicationInstance {
  return {
    id: `#${instanceIndex}`,
    label: `#${instanceIndex}`,
    instanceIndex,
    nodeId,
    observation,
    health: instanceHealth(observation),
  };
}

function build(w: ReplicatedFacts, services: readonly Service[]): Application {
  const conditions = conditionsOf(w);
  const slug = w.kind.toLowerCase();
  return {
    key: applicationKey(slug, w.name, w.tenantId),
    kind: w.kind,
    kindLabel: w.kind,
    name: w.name,
    tenantId: w.tenantId,
    moduleId: w.moduleId,
    artifactPath: w.artifactPath,
    instances: w.instances,
    services: servicesFronting(services, w.name, w.tenantId),
    health: healthOf(w, conditions),
    sync: syncOf(w),
    conditions,
    detail: {
      type: "replicated",
      desiredReplicas: w.desiredReplicas,
      unplacedCount: w.unplacedCount,
      requiredNodeLabels: w.requiredNodeLabels,
    },
  };
}

export function fromDeployment(d: Deployment, services: readonly Service[]): Application {
  return build(
    {
      kind: "Deployment",
      name: d.spec.name,
      tenantId: d.spec.tenantId,
      moduleId: d.spec.moduleId,
      artifactPath: d.spec.artifactPath,
      desiredReplicas: d.spec.replicas,
      unplacedCount: d.unplacedCount,
      requiredNodeLabels: [],
      quotaViolating: d.quotaViolating,
      limitRangeViolating: d.limitRangeViolating,
      limitRangeViolationReason: d.limitRangeViolationReason,
      instances: d.instances.map((i) => indexedInstance(i.instanceIndex, i.nodeId, i.observation)),
    },
    services,
  );
}

export function fromStatefulSet(s: StatefulSet, services: readonly Service[]): Application {
  return build(
    {
      kind: "StatefulSet",
      name: s.spec.name,
      tenantId: s.spec.tenantId,
      moduleId: s.spec.moduleId,
      artifactPath: s.spec.artifactPath,
      desiredReplicas: s.spec.replicas,
      unplacedCount: s.unplacedCount,
      requiredNodeLabels: [],
      // A StatefulSet's own status carries neither flag; admission still enforces both, so this is
      // "not reported here", and the Deployments/Tenants screens remain where a violation shows.
      quotaViolating: false,
      limitRangeViolating: false,
      instances: s.instances.map((i) => indexedInstance(i.instanceIndex, i.nodeId, i.observation)),
    },
    services,
  );
}

export function fromDaemonSet(d: DaemonSet, services: readonly Service[]): Application {
  return build(
    {
      kind: "DaemonSet",
      name: d.spec.name,
      tenantId: d.spec.tenantId,
      moduleId: d.spec.moduleId,
      artifactPath: d.spec.artifactPath,
      desiredReplicas: null,
      unplacedCount: 0,
      requiredNodeLabels: d.spec.placement.requiredNodeLabels,
      quotaViolating: false,
      limitRangeViolating: false,
      // One instance per node, so the node is the identity -- there is no index to tell two apart,
      // and the logs API addresses a DaemonSet instance as index 0 on its node.
      instances: d.instances.map((i) => ({
        id: i.nodeId,
        label: i.nodeId,
        instanceIndex: 0,
        nodeId: i.nodeId,
        observation: i.observation,
        health: instanceHealth(i.observation),
      })),
    },
    services,
  );
}
