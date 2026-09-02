import { GENERATED_JOBS_SHOWN } from "@/addons/applications/kinds/jobs";
import type { Application, HealthStatus } from "@/addons/applications/model";

/**
 * The application's resource tree, laid out in tiers:
 *
 *   application ─┬─ service              (each Service fronting it)
 *                └─ revision / module    ─── instance ─── node
 *
 * Four tiers with a known fan-out, so no graph library is needed and none is on this project's
 * dependency list. The layout is plain numbers -- a tier per column, a row per lane -- computed
 * here so the component only multiplies by a column width and a row height, and so the geometry
 * can be tested without a DOM.
 */

export type TreeNodeKind = "application" | "service" | "revision" | "instance" | "node" | "status";

export interface TreeNode {
  id: string;
  kind: TreeNodeKind;
  /** The small uppercase eyebrow above the title -- what this card *is*. */
  eyebrow: string;
  title: string;
  subtitle: string;
  /** `null` for a resource with no health of its own: a Service, a machine node. */
  health: HealthStatus | null;
  tier: number;
  row: number;
  /** Where clicking the card goes, when the console has a screen for this resource. */
  link: TreeNodeLink | null;
}

export type TreeNodeLink =
  | { to: "service" }
  | { to: "node"; nodeId: string }
  | { to: "instance"; deploymentName: string; instanceIndex: number; tenantId: string | null }
  | { to: "application"; kindSlug: string; name: string; tenantId: string | null }
  | { to: "workload"; kind: Application["kind"]; name: string };

export interface TreeEdge {
  from: string;
  to: string;
  /** The child's own health, so a connector reads the way the card it leads to does. */
  health: HealthStatus | null;
}

export interface TreeLayout {
  nodes: TreeNode[];
  edges: TreeEdge[];
  tiers: number;
  rows: number;
}

export const APPLICATION_NODE_ID = "application";

/** A card in tier 1 or 2 before its lane is known. */
interface Planned {
  id: string;
  kind: TreeNodeKind;
  eyebrow: string;
  title: string;
  subtitle: string;
  health: HealthStatus | null;
  link: TreeNodeLink | null;
  /** The machine this card runs on, when it is an instance -- drives the tier-3 cards. */
  nodeId?: string;
  children: Planned[];
}

function mean(values: readonly number[]): number {
  return values.reduce((a, b) => a + b, 0) / values.length;
}

function instanceSubtitle(o: {
  lifecycleState: string;
  alive: boolean;
  ready: boolean;
  workerId: string | null;
}): string {
  const probes =
    o.lifecycleState === "ACTIVE"
      ? `${o.alive ? "alive" : "not alive"} · ${o.ready ? "ready" : "not ready"}`
      : null;
  return [o.lifecycleState, probes, o.workerId].filter((p) => p !== null && p !== "").join(" · ");
}

/** The tier-1 (and nested tier-2) cards for one application, before lanes are assigned. */
function planChildren(app: Application): Planned[] {
  const planned: Planned[] = app.services.map((s) => ({
    id: `service:${s.name}`,
    kind: "service",
    eyebrow: "service",
    title: s.name,
    subtitle: `port ${s.port}${s.targetPort !== undefined ? ` → ${s.targetPort}` : ""}`,
    health: null,
    link: { to: "service" },
    children: [],
  }));

  const instanceCards = (): Planned[] =>
    app.instances.map((i) => ({
      id: `instance:${i.id}`,
      kind: "instance" as const,
      eyebrow: "instance",
      title: i.label,
      subtitle: instanceSubtitle(i.observation),
      health: i.health,
      nodeId: i.nodeId,
      link:
        app.kind === "Deployment"
          ? {
              to: "instance" as const,
              deploymentName: app.name,
              instanceIndex: i.instanceIndex,
              tenantId: app.tenantId,
            }
          : null,
      children: [],
    }));

  switch (app.detail.type) {
    case "replicated": {
      const { desiredReplicas } = app.detail;
      planned.push({
        id: "revision",
        kind: "revision",
        eyebrow: "revision",
        title: app.moduleId ? `${app.moduleId.name}@${app.moduleId.version}` : app.name,
        subtitle:
          desiredReplicas === null
            ? `${app.instances.length} node${app.instances.length === 1 ? "" : "s"}`
            : `${app.instances.length} / ${desiredReplicas} replicas`,
        health: null,
        link: { to: "workload", kind: app.kind, name: app.name },
        children: instanceCards(),
      });
      break;
    }
    case "job": {
      planned.push({
        id: "revision",
        kind: "revision",
        eyebrow: "module",
        title: app.moduleId ? `${app.moduleId.name}@${app.moduleId.version}` : app.name,
        subtitle: `${app.detail.phase} · backoff limit ${app.detail.backoffLimit}`,
        health: null,
        link: { to: "workload", kind: "Job", name: app.name },
        children: instanceCards(),
      });
      break;
    }
    case "cronjob": {
      for (const job of app.detail.generatedJobs) {
        planned.push({
          id: `job:${job.name}`,
          kind: "revision",
          eyebrow: "generated job",
          title: job.name,
          subtitle: job.phase,
          health: job.health,
          link: {
            to: "application",
            kindSlug: "job",
            name: job.name,
            tenantId: app.tenantId,
          },
          children:
            job.nodeId === null
              ? []
              : [
                  {
                    id: `attempt:${job.name}`,
                    kind: "instance",
                    eyebrow: "attempt",
                    title: job.attempt === null ? "attempt" : `attempt ${job.attempt}`,
                    subtitle: job.phase,
                    health: job.health,
                    nodeId: job.nodeId,
                    link: null,
                    children: [],
                  },
                ],
        });
      }
      break;
    }
    case "custom": {
      const { generation, observedGeneration } = app.detail;
      planned.push({
        id: "status",
        kind: "status",
        eyebrow: "status",
        title:
          observedGeneration === null
            ? "no observed generation"
            : `observed ${observedGeneration} of ${generation}`,
        subtitle:
          observedGeneration === null
            ? "no operator has reported on this spec"
            : observedGeneration >= generation
              ? "the operator has caught up"
              : "the operator has not caught up with the latest spec",
        health: app.health,
        link: null,
        children: [],
      });
      break;
    }
  }
  return planned;
}

export function layoutTree(app: Application): TreeLayout {
  const planned = planChildren(app);
  const nodes: TreeNode[] = [];
  const edges: TreeEdge[] = [];
  let nextRow = 0;

  // Leaves take lanes in order; each parent is then centred over the lanes its children span, so a
  // connector never has to cross a card that is not its own.
  const instanceRows = new Map<string, number>();
  const tier1Rows: number[] = [];
  for (const parent of planned) {
    const childRows: number[] = [];
    for (const child of parent.children) {
      const row = nextRow++;
      childRows.push(row);
      instanceRows.set(child.id, row);
      nodes.push({ ...toNode(child, 2, row) });
      edges.push({ from: parent.id, to: child.id, health: child.health });
    }
    const row = childRows.length === 0 ? nextRow++ : mean(childRows);
    tier1Rows.push(row);
    nodes.push({ ...toNode(parent, 1, row) });
    edges.push({ from: APPLICATION_NODE_ID, to: parent.id, health: parent.health });
  }

  nodes.push({
    id: APPLICATION_NODE_ID,
    kind: "application",
    eyebrow: "application",
    title: app.name,
    subtitle: `${app.kindLabel}${app.tenantId ? ` · ${app.tenantId}` : ""}`,
    health: app.health,
    tier: 0,
    row: tier1Rows.length === 0 ? 0 : mean(tier1Rows),
    link: null,
  });

  // Machine nodes form a DAG under the instances -- several replicas can share one machine -- so
  // each is centred on its own instances' lanes and then pushed down just enough never to overlap
  // the one above it.
  const byNode = new Map<string, Planned[]>();
  for (const parent of planned) {
    for (const child of parent.children) {
      if (child.nodeId === undefined) continue;
      const list = byNode.get(child.nodeId) ?? [];
      list.push(child);
      byNode.set(child.nodeId, list);
    }
  }
  const placed = [...byNode.entries()]
    .map(([nodeId, children]) => ({
      nodeId,
      children,
      row: mean(children.map((c) => instanceRows.get(c.id) ?? 0)),
    }))
    .sort((a, b) => a.row - b.row || a.nodeId.localeCompare(b.nodeId));

  let floor = -Infinity;
  for (const machine of placed) {
    const row = Math.max(machine.row, floor + 1);
    floor = row;
    const id = `node:${machine.nodeId}`;
    nodes.push({
      id,
      kind: "node",
      eyebrow: "node",
      title: machine.nodeId,
      subtitle: `${machine.children.length} instance${machine.children.length === 1 ? "" : "s"}`,
      health: null,
      tier: 3,
      row,
      link: { to: "node", nodeId: machine.nodeId },
    });
    for (const child of machine.children) {
      edges.push({ from: child.id, to: id, health: child.health });
    }
  }

  return {
    nodes,
    edges,
    tiers: 1 + Math.max(...nodes.map((n) => n.tier)),
    rows: 1 + Math.max(...nodes.map((n) => n.row)),
  };
}

function toNode(p: Planned, tier: number, row: number): TreeNode {
  return {
    id: p.id,
    kind: p.kind,
    eyebrow: p.eyebrow,
    title: p.title,
    subtitle: p.subtitle,
    health: p.health,
    tier,
    row,
    link: p.link,
  };
}

/** Above this many instances the tree stops being readable, and the list view leads instead -- the
 * same reason the workload detail pages window their own instance tables. */
export const TREE_INSTANCE_LIMIT = 40;

export function treeIsUseful(app: Application): boolean {
  return app.instances.length <= TREE_INSTANCE_LIMIT;
}

/** Re-exported so the screen's "showing N of M" note and the layout can never disagree. */
export { GENERATED_JOBS_SHOWN };
