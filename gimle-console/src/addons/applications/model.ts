import type { InstanceObservation, ModuleId, Service } from "@/types";

/**
 * Every deployable thing in the cluster, seen as one application.
 *
 * The screens read a workload the way a GitOps dashboard does: a *health* verdict on what is
 * actually running, kept apart from a *sync* verdict on whether the control plane has brought the
 * cluster to the manifest's desired state. Folding them together loses the distinction that
 * matters most while something is wrong -- a fully placed deployment with one crashed replica is
 * synced but degraded, while one mid-scale-up is out of sync with every placed replica fine.
 *
 * Each kind keeps its own meaning of both verdicts (see `kinds/`), because a Job that has finished
 * running is healthy for the same reason a Deployment that has stopped running is not.
 */

export type ApplicationKind =
  | "Deployment"
  | "StatefulSet"
  | "DaemonSet"
  | "Job"
  | "CronJob"
  | "CustomResource";

export type HealthStatus = "Healthy" | "Progressing" | "Degraded" | "Unknown";

export const HEALTH_STATUSES: readonly HealthStatus[] = [
  "Healthy",
  "Progressing",
  "Degraded",
  "Unknown",
];

/** `Unknown` is for a resource whose desired state nothing has reported on yet, never a guess. */
export type SyncStatus = "Synced" | "OutOfSync" | "Unknown";

export const SYNC_STATUSES: readonly SyncStatus[] = ["Synced", "OutOfSync", "Unknown"];

export interface ApplicationInstance {
  /** Unique within its application: the index for an indexed kind, the node for a DaemonSet. */
  id: string;
  label: string;
  /** What the logs and events APIs address this instance by -- always 0 for a DaemonSet. */
  instanceIndex: number;
  nodeId: string;
  observation: InstanceObservation;
  health: HealthStatus;
}

export type ConditionSeverity = "warn" | "bad";

/** One typed reason behind a verdict that is not green. Never emitted for a healthy application:
 * an empty list is what "nothing to explain" looks like. */
export interface ApplicationCondition {
  severity: ConditionSeverity;
  type: string;
  message: string;
}

/** One Job a CronJob has generated, as its own row under the schedule that produced it. */
export interface GeneratedJobSummary {
  name: string;
  health: HealthStatus;
  phase: string;
  attempt: number | null;
  nodeId: string | null;
}

/** The facts that exist for one kind and have no meaning for the others. */
export type ApplicationDetail =
  | {
      type: "replicated";
      /** `null` for a DaemonSet: one per eligible node is not a number a manifest sets. */
      desiredReplicas: number | null;
      unplacedCount: number;
      requiredNodeLabels: string[];
    }
  | { type: "job"; phase: string; attempt: number | null; backoffLimit: number }
  | {
      type: "cronjob";
      schedule: string;
      lastScheduleTime: string | null;
      concurrencyPolicy: string;
      generatedJobs: GeneratedJobSummary[];
    }
  | {
      type: "custom";
      generation: number;
      /** `null` when no operator has reported a status carrying the convention. */
      observedGeneration: number | null;
      /** Whatever the KindDefinition's own printColumns name, already resolved to strings. */
      columns: [string, string][];
    };

export interface Application {
  /** Unique across kinds and tenants -- see {@link applicationKey}. */
  key: string;
  kind: ApplicationKind;
  /** The kind as an operator reads it: "Deployment", or a custom kind's own name. */
  kindLabel: string;
  name: string;
  tenantId: string | null;
  /** `null` for a custom resource, which names no module of its own. */
  moduleId: ModuleId | null;
  artifactPath: string | null;
  instances: ApplicationInstance[];
  services: Service[];
  health: HealthStatus;
  sync: SyncStatus;
  conditions: ApplicationCondition[];
  detail: ApplicationDetail;
}

/** The URL segment a kind is addressed by. A custom kind uses its own `kindName` verbatim, which
 * is why this is a string rather than a closed union. */
export type ApplicationKindSlug = string;

const BUILT_IN_SLUGS: Record<Exclude<ApplicationKind, "CustomResource">, string> = {
  Deployment: "deployment",
  StatefulSet: "statefulset",
  DaemonSet: "daemonset",
  Job: "job",
  CronJob: "cronjob",
};

/** A custom kind's slug is its own name, so `/apps/custom.Greeting/hello` addresses it directly. */
export function kindSlug(app: Pick<Application, "kind" | "kindLabel">): ApplicationKindSlug {
  return app.kind === "CustomResource" ? app.kindLabel : BUILT_IN_SLUGS[app.kind];
}

export function applicationKey(
  slug: ApplicationKindSlug,
  name: string,
  tenantId: string | null,
): string {
  return `${slug}/${tenantId ?? ""}/${name}`;
}

/** Degraded outranks Progressing outranks Unknown outranks Healthy. */
export function worstHealth(statuses: readonly HealthStatus[]): HealthStatus | null {
  if (statuses.includes("Degraded")) return "Degraded";
  if (statuses.includes("Progressing")) return "Progressing";
  if (statuses.includes("Unknown")) return "Unknown";
  if (statuses.includes("Healthy")) return "Healthy";
  return null;
}

/**
 * One placed instance's own verdict.
 *
 * `ACTIVE` splits three ways because the two probes mean different things: a liveness failure is a
 * broken instance, while a readiness failure is one that has not finished coming up (or has taken
 * itself out of rotation on purpose), which is progress rather than damage.
 */
export function instanceHealth(observation: InstanceObservation): HealthStatus {
  switch (observation.lifecycleState) {
    case "ACTIVE":
      if (!observation.alive) return "Degraded";
      return observation.ready ? "Healthy" : "Progressing";
    case "COMPLETED":
      return "Healthy";
    case "FAILED":
    case "UNINSTALLED":
      return "Degraded";
    case "INSTALLED":
    case "RESOLVED":
    case "STARTING":
    case "STOPPING":
      return "Progressing";
  }
}

/** The Services fronting one workload: matched by name, and by the same tenant -- two tenants may
 * each run a deployment of one name, and a Service never reaches across that boundary. */
export function servicesFronting(
  services: readonly Service[],
  name: string,
  tenantId: string | null,
): Service[] {
  return services.filter(
    (s) => s.deploymentNames.includes(name) && (s.tenantId ?? null) === tenantId,
  );
}

const HEALTH_RANK: Record<HealthStatus, number> = {
  Degraded: 0,
  Progressing: 1,
  Unknown: 2,
  Healthy: 3,
};

/** Worst first, then by name, then by key -- so whatever needs attention leads the list, and the
 * order does not shuffle between two polls that returned the same applications. */
export function compareApplications(a: Application, b: Application): number {
  const byHealth = HEALTH_RANK[a.health] - HEALTH_RANK[b.health];
  if (byHealth !== 0) return byHealth;
  const byName = a.name.localeCompare(b.name);
  if (byName !== 0) return byName;
  return a.key.localeCompare(b.key);
}

/** The tenant filter's value for an untenanted application, distinct from "ALL". */
export const UNTENANTED = "untenanted";

export interface ApplicationFilters {
  search: string;
  health: HealthStatus | "ALL";
  sync: SyncStatus | "ALL";
  kind: string | "ALL";
  tenant: string | "ALL";
}

export const NO_FILTERS: ApplicationFilters = {
  search: "",
  health: "ALL",
  sync: "ALL",
  kind: "ALL",
  tenant: "ALL",
};

export function filterApplications(
  apps: readonly Application[],
  f: ApplicationFilters,
): Application[] {
  const needle = f.search.trim().toLowerCase();
  return apps.filter((a) => {
    if (f.health !== "ALL" && a.health !== f.health) return false;
    if (f.sync !== "ALL" && a.sync !== f.sync) return false;
    if (f.kind !== "ALL" && a.kindLabel !== f.kind) return false;
    if (f.tenant !== "ALL" && (a.tenantId ?? UNTENANTED) !== f.tenant) return false;
    if (needle !== "") {
      const module = a.moduleId ? `${a.moduleId.name} ${a.moduleId.version}` : "";
      const haystack = `${a.name} ${a.kindLabel} ${module} ${a.tenantId ?? ""}`;
      if (!haystack.toLowerCase().includes(needle)) return false;
    }
    return true;
  });
}

export interface ApplicationTotals {
  health: Record<HealthStatus, number>;
  sync: Record<SyncStatus, number>;
}

export function totalsOf(apps: readonly Application[]): ApplicationTotals {
  const health: Record<HealthStatus, number> = {
    Healthy: 0,
    Progressing: 0,
    Degraded: 0,
    Unknown: 0,
  };
  const sync: Record<SyncStatus, number> = { Synced: 0, OutOfSync: 0, Unknown: 0 };
  for (const a of apps) {
    health[a.health] += 1;
    sync[a.sync] += 1;
  }
  return { health, sync };
}

/** Every distinct kind label present, for the kind filter -- built-in kinds keep their declaration
 * order, custom kinds follow alphabetically after them. */
export function kindLabelsOf(apps: readonly Application[]): string[] {
  const builtIn = Object.keys(BUILT_IN_SLUGS);
  const present = new Set(apps.map((a) => a.kindLabel));
  const ordered = builtIn.filter((k) => present.has(k));
  const custom = [...present].filter((k) => !builtIn.includes(k)).sort();
  return [...ordered, ...custom];
}

export function tenantsOf(apps: readonly Application[]): string[] {
  return [...new Set(apps.map((a) => a.tenantId ?? UNTENANTED))].sort();
}
