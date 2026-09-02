import type { CustomResourceItem, KindDefinitionSummary } from "@/types";
import {
  applicationKey,
  type Application,
  type ApplicationCondition,
  type HealthStatus,
  type SyncStatus,
} from "@/addons/applications/model";

/**
 * Instances of a cluster-defined custom kind.
 *
 * A custom resource runs nothing itself -- an operator elsewhere reconciles it -- so its health is
 * entirely a question of whether that operator has caught up with the spec, read from the same
 * `status.observedGeneration` convention the Custom Resources screen already relies on. Nothing
 * here fabricates a verdict for a resource whose operator has said nothing: that is what Unknown
 * is for.
 */

/** The generation an operator last reported reconciling, or `null` when its status does not carry
 * the convention (or there is no status at all). */
export function observedGenerationOf(resource: CustomResourceItem): number | null {
  const value = resource.status?.["observedGeneration"];
  return typeof value === "number" ? value : null;
}

/** Walks a printColumn's dot-separated path into the resource, the same way the Custom Resources
 * screen resolves its own columns. */
function valueAtPath(resource: CustomResourceItem, path: string): unknown {
  const segments = path.split(".");
  let current: unknown = resource;
  for (const segment of segments) {
    if (current === null || typeof current !== "object") return undefined;
    current = (current as Record<string, unknown>)[segment];
  }
  return current;
}

function cellText(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

export function fromCustomResource(
  resource: CustomResourceItem,
  definition: KindDefinitionSummary | undefined,
): Application {
  const tenantId = resource.tenantId ?? null;
  const observed = observedGenerationOf(resource);
  const conditions: ApplicationCondition[] = [];

  let health: HealthStatus;
  let sync: SyncStatus;
  if (resource.status === null) {
    health = "Unknown";
    sync = "Unknown";
  } else if (observed === null) {
    // A status that does not carry the convention: presence is all it proves, so claim no more.
    health = "Unknown";
    sync = "Unknown";
  } else if (observed >= resource.generation) {
    health = "Healthy";
    sync = "Synced";
  } else {
    health = "Progressing";
    sync = "OutOfSync";
    conditions.push({
      severity: "warn",
      type: "OperatorBehind",
      message: `spec is at generation ${resource.generation}, the operator last reconciled ${observed}`,
    });
  }

  const columns: [string, string][] = (definition?.printColumns ?? []).map((c) => [
    c.name,
    cellText(valueAtPath(resource, c.path)),
  ]);

  return {
    key: applicationKey(resource.kind, resource.name, tenantId),
    kind: "CustomResource",
    kindLabel: resource.kind,
    name: resource.name,
    tenantId,
    // A custom resource names no module of its own: whatever operator reconciles it is a separate
    // workload, and Gimlé has no owner reference to tie the two together.
    moduleId: null,
    artifactPath: null,
    instances: [],
    services: [],
    health,
    sync,
    conditions,
    detail: {
      type: "custom",
      generation: resource.generation,
      observedGeneration: observed,
      columns,
    },
  };
}
