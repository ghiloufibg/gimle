import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useMemo, useState } from "react";

import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import { useOverviewStore } from "@/stores/useOverviewStore";
import { PageContainer, PageHeader, Panel } from "@/components/page-shell";
import { TopologyDrawer, type TopologySelection } from "@/components/topology-drawer";
import { fmtRelativeTime, isStale } from "@/lib/format";
import { replicaBadgeSlots } from "@/lib/topology";
import { cn } from "@/lib/utils";
import type { Deployment, Node } from "@/types";

export const Route = createFileRoute("/topology")({
  head: () => ({
    meta: [
      { title: "Topology — Gimlé Console" },
      {
        name: "description",
        content:
          "Placement topology: nodes grouped by tenant and deployment, with placed, unplaced, and stale-node signals.",
      },
      { property: "og:title", content: "Topology — Gimlé Console" },
      {
        property: "og:description",
        content: "Visual placement map of deployments across tenants and cluster nodes.",
      },
    ],
  }),
  component: Topology,
});

type Grouping = "tenant" | "node";

function placementTone(d: Deployment) {
  if (d.quotaViolating) return "bad" as const;
  if (d.unplacedCount > 0 || d.instances.length < d.spec.replicas) return "warn" as const;
  return "ok" as const;
}

interface NodeFocus {
  /** node currently hovered or selected (hover wins) */
  activeNode: string | null;
  selectedNode: string | null;
  onHover(nodeId: string | null): void;
  onSelect(nodeId: string): void;
}

/** Small square per replica: filled = placed, hollow = unplaced. */
function ReplicaGrid({ d, activeNode }: { d: Deployment; activeNode: string | null }) {
  const tone = placementTone(d);
  return (
    <div className="flex flex-wrap gap-[3px]">
      {replicaBadgeSlots(d).map((inst, i) => {
        const onActiveNode = inst !== null && activeNode !== null && inst.nodeId === activeNode;
        return (
          <span
            key={inst ? inst.instanceIndex : `unplaced-${i}`}
            title={inst ? `instance ${inst.instanceIndex} → ${inst.nodeId}` : "unplaced"}
            className={cn(
              "h-2.5 w-2.5 rounded-[1px] border transition-all",
              inst
                ? tone === "bad"
                  ? "border-status-bad bg-status-bad/70"
                  : tone === "warn"
                    ? "border-status-warn bg-status-warn/70"
                    : "border-status-ok bg-status-ok/70"
                : "border-status-bad/50 bg-transparent",
              onActiveNode && "scale-125 ring-2 ring-primary ring-offset-1 ring-offset-background",
              activeNode !== null && inst !== null && !onActiveNode && "opacity-30",
            )}
          />
        );
      })}
    </div>
  );
}

function DeploymentCell({
  d,
  focus,
  onOpen,
}: {
  d: Deployment;
  focus: NodeFocus;
  onOpen(name: string): void;
}) {
  const tone = placementTone(d);
  const nodeIds = Array.from(new Set(d.instances.map((i) => i.nodeId)));
  const related = focus.activeNode !== null && nodeIds.includes(focus.activeNode);
  const dimmed = focus.activeNode !== null && !related;
  return (
    <div
      className={cn(
        "border-l-2 bg-primary/[0.04] p-3 transition-all hover:bg-primary/10",
        tone === "bad"
          ? "border-status-bad"
          : tone === "warn"
            ? "border-status-warn"
            : "border-status-ok",
        related && "bg-primary/15 ring-1 ring-inset ring-primary/50",
        dimmed && "opacity-35",
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <Link
          to="/deployments/$name"
          params={{ name: d.spec.name }}
          className="truncate font-mono text-xs font-bold text-signal hover:text-primary hover:underline"
        >
          {d.spec.name}
        </Link>
        <span
          className={cn(
            "shrink-0 font-mono text-[10px] uppercase tracking-widest",
            tone === "bad"
              ? "text-status-bad"
              : tone === "warn"
                ? "text-status-warn"
                : "text-status-ok",
          )}
        >
          {d.quotaViolating
            ? "quota"
            : d.unplacedCount > 0
              ? `${d.unplacedCount} unplaced`
              : "placed"}
        </span>
      </div>
      <div className="mt-2 flex items-center justify-between gap-2">
        <ReplicaGrid d={d} activeNode={focus.activeNode} />
        <button
          type="button"
          onClick={() => onOpen(d.spec.name)}
          title={`Inspect ${d.spec.name}`}
          className="shrink-0 rounded-sm border border-primary/25 px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-widest text-muted-foreground hover:bg-primary/10 hover:text-primary"
        >
          details
        </button>
      </div>
      <div className="mt-2 flex flex-wrap gap-1">
        {nodeIds.map((nodeId) => {
          const active = focus.activeNode === nodeId;
          const selected = focus.selectedNode === nodeId;
          return (
            <span
              key={nodeId}
              className={cn(
                "flex items-center rounded-sm border transition-colors",
                active ? "border-primary bg-primary/20" : "border-primary/20 bg-primary/5",
              )}
            >
              <button
                type="button"
                aria-pressed={selected}
                title={`Highlight placements on ${nodeId}`}
                onMouseEnter={() => focus.onHover(nodeId)}
                onMouseLeave={() => focus.onHover(null)}
                onFocus={() => focus.onHover(nodeId)}
                onBlur={() => focus.onHover(null)}
                onClick={() => focus.onSelect(nodeId)}
                className={cn(
                  "px-1.5 py-0.5 font-mono text-[10px]",
                  active ? "text-primary" : "text-muted-foreground hover:text-primary",
                )}
              >
                {nodeId}
              </button>
              <Link
                to="/nodes/$nodeId"
                params={{ nodeId }}
                title={`Open ${nodeId}`}
                className="border-l border-primary/20 px-1 py-0.5 font-mono text-[10px] text-muted-foreground hover:text-primary"
              >
                ↗
              </Link>
            </span>
          );
        })}
        {d.instances.length === 0 && (
          <span className="font-mono text-[10px] uppercase tracking-widest text-status-bad">
            no placement
          </span>
        )}
      </div>
    </div>
  );
}

function NodeRail({ node, count }: { node?: Node; count: number }) {
  if (!node) return null;
  const stale = isStale(node.lastHeartbeatAt);
  const load = Math.min(
    100,
    Math.round(
      (node.capacity
        ? node.capacity.assignedCpuMillicores / Math.max(1, node.capacity.totalCpuMillicores)
        : 0) * 100,
    ),
  );
  return (
    <div className="flex items-center gap-3">
      <div className="w-28 shrink-0">
        <div className="flex items-center justify-between font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
          <span>cpu</span>
          <span className="tabular-nums">{stale ? "—" : `${load}%`}</span>
        </div>
        <div className="mt-1 h-1 overflow-hidden rounded-full bg-muted">
          <div
            className={cn("h-full", stale ? "bg-status-bad/50" : "bg-primary")}
            style={{ width: `${stale ? 100 : load}%` }}
          />
        </div>
      </div>
      <span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        {count} inst · {fmtRelativeTime(node.lastHeartbeatAt)}
      </span>
    </div>
  );
}

function Topology() {
  const s = useOverviewStore();
  const [grouping, setGrouping] = useState<Grouping>("tenant");
  const [selectedNode, setSelectedNode] = useState<string | null>(null);
  const [hoverNode, setHoverNode] = useState<string | null>(null);
  const [selection, setSelection] = useState<TopologySelection>(null);
  const openNode = (nodeId: string) => setSelection({ kind: "node", id: nodeId });
  const openDeployment = (name: string) => setSelection({ kind: "deployment", id: name });
  const focus: NodeFocus = {
    activeNode: hoverNode ?? selectedNode,
    selectedNode,
    onHover: setHoverNode,
    onSelect: (nodeId) => {
      setSelectedNode((cur) => (cur === nodeId ? null : nodeId));
      openNode(nodeId);
    },
  };

  useEffect(() => {
    if (!s.loaded) s.load();
    if (!s.allLoaded) s.loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useAutoRefresh(s.poll);

  const nodeById = useMemo(() => new Map(s.allNodes.map((n) => [n.nodeId, n])), [s.allNodes]);

  const byTenant = useMemo(() => {
    const map = new Map<string, Deployment[]>();
    for (const d of s.allDeployments) {
      const key = d.spec.tenantId ?? "unassigned";
      const list = map.get(key);
      if (list) list.push(d);
      else map.set(key, [d]);
    }
    return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [s.allDeployments]);

  const byNode = useMemo(() => {
    const map = new Map<string, { deployments: Set<string>; instances: number }>();
    for (const n of s.allNodes) map.set(n.nodeId, { deployments: new Set(), instances: 0 });
    for (const d of s.allDeployments) {
      for (const inst of d.instances) {
        const entry = map.get(inst.nodeId) ?? { deployments: new Set<string>(), instances: 0 };
        entry.deployments.add(d.spec.name);
        entry.instances += 1;
        map.set(inst.nodeId, entry);
      }
    }
    return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [s.allDeployments, s.allNodes]);

  const deploymentsByName = useMemo(
    () => new Map(s.allDeployments.map((d) => [d.spec.name, d])),
    [s.allDeployments],
  );

  return (
    <PageContainer>
      <PageHeader
        title="Placement Topology"
        eyebrow="Gimlé // Topology"
        subtitle="Deployments mapped across tenants and cluster nodes — same control-plane data as the overview."
        actions={
          <div className="flex items-center gap-2">
            <div className="flex rounded-sm border border-primary/20 bg-primary/5 p-0.5">
              {(["tenant", "node"] as Grouping[]).map((g) => (
                <button
                  key={g}
                  onClick={() => setGrouping(g)}
                  aria-pressed={grouping === g}
                  className={cn(
                    "px-2.5 py-1 font-mono text-[10px] uppercase tracking-widest transition-colors",
                    grouping === g
                      ? "bg-primary/20 text-primary"
                      : "text-muted-foreground hover:text-foreground",
                  )}
                >
                  by {g}
                </button>
              ))}
            </div>
            <button
              onClick={() => {
                s.load();
                s.loadAll();
              }}
              className="rounded-sm border border-primary/20 bg-primary/10 px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-primary transition-colors hover:bg-primary/20"
            >
              {s.loading || s.allLoading ? "syncing…" : "Refresh map"}
            </button>
          </div>
        }
      />

      {/* Legend */}
      <div className="mb-4 flex flex-wrap items-center gap-4 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-[1px] border border-status-ok bg-status-ok/70" />{" "}
          placed
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-[1px] border border-status-warn bg-status-warn/70" />{" "}
          rolling
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-[1px] border border-status-bad/50" /> unplaced
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-[1px] border border-status-bad bg-status-bad/70" />{" "}
          quota violation
        </span>
        {selectedNode && (
          <button
            onClick={() => setSelectedNode(null)}
            className="rounded-sm border border-primary/40 bg-primary/10 px-2 py-0.5 text-[10px] uppercase tracking-widest text-primary hover:bg-primary/20"
          >
            focus: {selectedNode} · clear
          </button>
        )}
        <span className="ml-auto">
          {s.unplacedInstances} unplaced · {s.quotaViolating} quota alarms · {s.nodesStale} stale
          nodes
        </span>
      </div>

      {grouping === "tenant" ? (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
          {byTenant.map(([tenantId, deployments]) => {
            const unplaced = deployments.reduce((a, d) => a + d.unplacedCount, 0);
            const quota = deployments.filter((d) => d.quotaViolating).length;
            return (
              <Panel
                key={tenantId}
                title={tenantId === "unassigned" ? "Unassigned (no tenant)" : tenantId}
                aside={
                  <span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    {deployments.length} dep ·{" "}
                    <span className={unplaced > 0 ? "text-status-bad" : ""}>
                      {unplaced} unplaced
                    </span>{" "}
                    · <span className={quota > 0 ? "text-status-bad" : ""}>{quota} quota</span>
                  </span>
                }
              >
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-px bg-primary/5">
                  {deployments.map((d) => (
                    <DeploymentCell key={d.spec.name} d={d} focus={focus} onOpen={openDeployment} />
                  ))}
                </div>
              </Panel>
            );
          })}
          {!s.allLoading && byTenant.length === 0 && (
            <p className="font-mono text-xs text-muted-foreground">no placement data</p>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
          {byNode.map(([nodeId, entry]) => {
            const node = nodeById.get(nodeId);
            const stale = node ? isStale(node.lastHeartbeatAt) : false;
            const active = focus.activeNode === nodeId;
            return (
              <div
                key={nodeId}
                onMouseEnter={() => focus.onHover(nodeId)}
                onMouseLeave={() => focus.onHover(null)}
                onClick={() => focus.onSelect(nodeId)}
                className={cn(
                  "transition-opacity",
                  focus.activeNode !== null && !active && "opacity-50",
                )}
              >
                <Panel
                  title={nodeId}
                  className={cn(
                    stale && "border-status-bad/40",
                    active && "ring-1 ring-primary/60",
                  )}
                  aside={<NodeRail node={node} count={entry.instances} />}
                >
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-px bg-primary/5">
                    {[...entry.deployments].map((name) => {
                      const d = deploymentsByName.get(name);
                      return d ? (
                        <DeploymentCell key={name} d={d} focus={focus} onOpen={openDeployment} />
                      ) : null;
                    })}
                    {entry.deployments.size === 0 && (
                      <div className="p-3 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                        idle — no instances placed
                      </div>
                    )}
                  </div>
                </Panel>
              </div>
            );
          })}
        </div>
      )}

      {s.allLoading && s.allDeployments.length === 0 && (
        <p className="mt-4 font-mono text-xs text-muted-foreground">loading topology…</p>
      )}

      <TopologyDrawer
        selection={selection}
        deployments={s.allDeployments}
        nodes={s.allNodes}
        onClose={() => setSelection(null)}
        onSelectNode={(nodeId) => {
          setSelectedNode(nodeId);
          openNode(nodeId);
        }}
        onSelectDeployment={openDeployment}
      />
    </PageContainer>
  );
}
