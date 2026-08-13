import { Link } from "@tanstack/react-router";
import { useMemo, useState } from "react";

import { fmtBytes, fmtMillicores, fmtRelativeTime, isStale } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Deployment, Node } from "@/types";

export type TopologySelection =
  | { kind: "node"; id: string }
  | { kind: "deployment"; id: string }
  | null;

/** Deterministic pseudo-random from a string seed — keeps the mock timeline stable per target. */
function seeded(seed: string) {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return () => {
    h = Math.imul(h ^ (h >>> 15), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    return ((h ^= h >>> 16) >>> 0) / 4294967296;
  };
}

type Tone = "ok" | "warn" | "bad" | "muted";

export type TimelineTrack = "placement" | "quota";

interface TimelineEvent {
  at: string;
  label: string;
  detail: string;
  tone: Tone;
  /** extra key/value context revealed when the event row is expanded */
  meta: Array<[string, string]>;
}

type EventSeed = [string, string, Tone];

const NODE_PLACEMENT_EVENTS: EventSeed[] = [
  ["placement accepted", "scheduler bound a new instance to this node", "ok"],
  ["heartbeat ok", "control plane received registry heartbeat", "ok"],
  ["capacity recalculated", "assigned cpu/memory re-evaluated after placement", "muted"],
  ["heartbeat late", "heartbeat arrived outside the expected window", "warn"],
  ["drain requested", "operator marked node for maintenance drain", "warn"],
  ["node registered", "node joined the cluster registry", "ok"],
];

const NODE_QUOTA_EVENTS: EventSeed[] = [
  ["tenant quota checked", "aggregate tenant usage re-evaluated on this node", "muted"],
  ["quota headroom low", "tenant is within 10% of its cpu allowance", "warn"],
  ["quota exceeded", "scheduler refused a replica for the tenant", "bad"],
  ["quota released", "instance stopped, allowance returned to the tenant", "ok"],
  ["quota raised", "operator increased the tenant allowance", "ok"],
];

const DEPLOYMENT_PLACEMENT_EVENTS: EventSeed[] = [
  ["replica ACTIVE", "instance reached ACTIVE lifecycle state", "ok"],
  ["rolling update", "replicas cycling to the new artifact revision", "warn"],
  ["placement pending", "scheduler could not place a replica", "bad"],
  ["spec applied", "deployment spec accepted by the control plane", "ok"],
  ["replica STOPPING", "instance draining before reschedule", "warn"],
  ["replica rescheduled", "instance moved to a node with more headroom", "muted"],
];

const DEPLOYMENT_QUOTA_EVENTS: EventSeed[] = [
  ["quota checked", "tenant quota re-evaluated against current usage", "muted"],
  ["quota violation", "requested replicas exceed the tenant allowance", "bad"],
  ["quota warning", "usage crossed 80% of the tenant memory allowance", "warn"],
  ["usage normalised", "usage dropped back under the tenant allowance", "ok"],
  ["allowance applied", "new tenant quota propagated to the scheduler", "ok"],
];

function pool(kind: "node" | "deployment", track: TimelineTrack) {
  if (kind === "node") return track === "placement" ? NODE_PLACEMENT_EVENTS : NODE_QUOTA_EVENTS;
  return track === "placement" ? DEPLOYMENT_PLACEMENT_EVENTS : DEPLOYMENT_QUOTA_EVENTS;
}

function buildTimeline(
  seed: string,
  kind: "node" | "deployment",
  track: TimelineTrack,
  count = 12,
): TimelineEvent[] {
  const rnd = seeded(`${track}:${seed}`);
  const seeds = pool(kind, track);
  let t = Date.now();
  return Array.from({ length: count }).map(() => {
    const [label, detail, tone] = seeds[Math.floor(rnd() * seeds.length)];
    t -= Math.floor(rnd() * 9 * 60_000) + 45_000;
    const meta: Array<[string, string]> =
      track === "placement"
        ? [
            ["actor", "scheduler"],
            ["replica", `#${Math.floor(rnd() * 6)}`],
            ["duration", `${Math.floor(rnd() * 900) + 40}ms`],
          ]
        : [
            ["actor", "quota-controller"],
            ["cpu Δ", `${Math.floor(rnd() * 400) - 200}m`],
            ["mem Δ", `${Math.floor(rnd() * 512) - 256}Mi`],
          ];
    return { at: new Date(t).toISOString(), label, detail, tone, meta };
  });
}

function toneText(tone: Tone) {
  return tone === "bad"
    ? "text-status-bad"
    : tone === "warn"
      ? "text-status-warn"
      : tone === "ok"
        ? "text-status-ok"
        : "text-muted-foreground";
}

function toneBorder(tone: Tone) {
  return tone === "bad"
    ? "border-status-bad bg-status-bad/70"
    : tone === "warn"
      ? "border-status-warn bg-status-warn/70"
      : tone === "ok"
        ? "border-status-ok bg-status-ok/70"
        : "border-status-muted bg-muted";
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="border-t border-primary/15 px-4 py-3">
      <h4 className="hud-label mb-2 text-primary">{title}</h4>
      {children}
    </section>
  );
}

function Bar({ label, used, total }: { label: string; used: number; total: number }) {
  const pct = Math.min(100, Math.round((used / Math.max(1, total)) * 100));
  return (
    <div className="mb-2 last:mb-0">
      <div className="flex items-center justify-between font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        <span>{label}</span>
        <span className="tabular-nums">{pct}%</span>
      </div>
      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
        <div
          className={cn(
            "h-full",
            pct >= 90 ? "bg-status-bad" : pct >= 70 ? "bg-status-warn" : "bg-primary",
          )}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

function TimelineRow({ e }: { e: TimelineEvent }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="relative pb-3 last:pb-0">
      <span
        className={cn(
          "absolute -left-[21px] top-1 h-2.5 w-2.5 rounded-[1px] border",
          toneBorder(e.tone),
        )}
      />
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="w-full text-left"
      >
        <div className="flex items-baseline justify-between gap-2">
          <span className={cn("font-mono text-[11px]", toneText(e.tone))}>
            <span className="mr-1 text-muted-foreground">{open ? "▾" : "▸"}</span>
            {e.label}
          </span>
          <span className="shrink-0 font-mono text-[10px] tabular-nums text-muted-foreground">
            {fmtRelativeTime(e.at)}
          </span>
        </div>
        <p className="mt-0.5 text-[11px] leading-snug text-muted-foreground">{e.detail}</p>
      </button>
      {open && (
        <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 border-l-2 border-primary/30 bg-primary/[0.05] px-2 py-1.5 font-mono text-[10px]">
          <dt className="uppercase tracking-widest text-muted-foreground">at</dt>
          <dd className="tabular-nums text-signal">{new Date(e.at).toLocaleTimeString()}</dd>
          {e.meta.map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="uppercase tracking-widest text-muted-foreground">{k}</dt>
              <dd className="text-signal">{v}</dd>
            </div>
          ))}
        </dl>
      )}
    </li>
  );
}

function TimelinePanel({ seed, kind }: { seed: string; kind: "node" | "deployment" }) {
  const [track, setTrack] = useState<TimelineTrack>("placement");
  const [expanded, setExpanded] = useState(false);
  const events = useMemo(() => buildTimeline(seed, kind, track), [seed, kind, track]);
  const shown = expanded ? events : events.slice(0, 5);
  return (
    <>
      <div className="mb-2 flex items-center gap-2">
        <div className="flex rounded-sm border border-primary/20 bg-primary/5 p-0.5">
          {(["placement", "quota"] as TimelineTrack[]).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => setTrack(t)}
              aria-pressed={track === t}
              className={cn(
                "px-2 py-0.5 font-mono text-[10px] uppercase tracking-widest transition-colors",
                track === t
                  ? "bg-primary/20 text-primary"
                  : "text-muted-foreground hover:text-foreground",
              )}
            >
              {t}
            </button>
          ))}
        </div>
        <span className="ml-auto font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
          {shown.length}/{events.length}
        </span>
      </div>
      <ol className="relative ml-1 border-l border-primary/20 pl-4">
        {shown.map((e, i) => (
          <TimelineRow key={`${track}-${i}`} e={e} />
        ))}
      </ol>
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="mt-2 w-full rounded-sm border border-primary/25 px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-muted-foreground hover:bg-primary/10 hover:text-primary"
      >
        {expanded ? "collapse timeline" : `expand timeline (+${events.length - shown.length})`}
      </button>
    </>
  );
}

export function TopologyDrawer({
  selection,
  deployments,
  nodes,
  onClose,
  onSelectNode,
  onSelectDeployment,
}: {
  selection: TopologySelection;
  deployments: Deployment[];
  nodes: Node[];
  onClose(): void;
  onSelectNode(nodeId: string): void;
  onSelectDeployment(name: string): void;
}) {
  const open = selection !== null;

  const seed = selection ? `${selection.kind}:${selection.id}` : "";

  const node =
    selection?.kind === "node" ? nodes.find((n) => n.nodeId === selection.id) : undefined;
  const deployment =
    selection?.kind === "deployment"
      ? deployments.find((d) => d.spec.name === selection.id)
      : undefined;

  const nodeDeployments =
    selection?.kind === "node"
      ? deployments.filter((d) => d.instances.some((i) => i.nodeId === selection.id))
      : [];

  return (
    <>
      {open && (
        <div
          className="fixed inset-0 z-40 bg-background/60 backdrop-blur-[1px] lg:bg-transparent lg:backdrop-blur-0"
          onClick={onClose}
          aria-hidden
        />
      )}
      <aside
        aria-hidden={!open}
        className={cn(
          "hud-panel fixed right-0 top-0 z-50 flex h-dvh w-full max-w-[380px] flex-col overflow-y-auto border-l border-primary/25 bg-background transition-transform duration-200",
          open ? "translate-x-0" : "pointer-events-none translate-x-full",
        )}
      >
        <header className="sticky top-0 z-10 flex items-start justify-between gap-2 border-b border-primary/20 bg-primary/10 px-4 py-3 backdrop-blur">
          <div className="min-w-0">
            <div className="hud-label text-primary">
              {selection?.kind === "node" ? "Node detail" : "Deployment detail"}
            </div>
            <p className="truncate font-mono text-sm text-signal">{selection?.id}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="shrink-0 rounded-sm border border-primary/25 px-2 py-0.5 font-mono text-[10px] uppercase tracking-widest text-muted-foreground hover:bg-primary/10 hover:text-primary"
          >
            close
          </button>
        </header>

        {selection?.kind === "node" && (
          <>
            <Section title="Capacity & heartbeat">
              {node ? (
                <>
                  <Bar
                    label="cpu"
                    used={node.capacity.assignedCpuMillicores}
                    total={node.capacity.totalCpuMillicores}
                  />
                  <Bar
                    label="memory"
                    used={node.capacity.assignedMemoryBytes}
                    total={node.capacity.totalMemoryBytes}
                  />
                  <div className="mt-2 grid grid-cols-2 gap-2 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    <span>
                      cpu {fmtMillicores(node.capacity.assignedCpuMillicores)} /{" "}
                      {fmtMillicores(node.capacity.totalCpuMillicores)}
                    </span>
                    <span>
                      mem {fmtBytes(node.capacity.assignedMemoryBytes)} /{" "}
                      {fmtBytes(node.capacity.totalMemoryBytes)}
                    </span>
                    <span
                      className={cn(
                        isStale(node.lastHeartbeatAt) ? "text-status-bad" : "text-status-ok",
                      )}
                    >
                      hb {fmtRelativeTime(node.lastHeartbeatAt)}
                    </span>
                    <span>tiers {node.capabilities.supportedTiers.join(" ")}</span>
                  </div>
                </>
              ) : (
                <p className="font-mono text-[11px] text-muted-foreground">
                  node metadata unavailable
                </p>
              )}
            </Section>

            <Section
              title={`Replica placement · ${nodeDeployments.reduce(
                (a, d) => a + d.instances.filter((i) => i.nodeId === selection.id).length,
                0,
              )} instances`}
            >
              {nodeDeployments.length === 0 ? (
                <p className="font-mono text-[11px] text-muted-foreground">
                  no instances placed here
                </p>
              ) : (
                <ul className="space-y-1.5">
                  {nodeDeployments.map((d) => {
                    const here = d.instances.filter((i) => i.nodeId === selection.id);
                    return (
                      <li
                        key={d.spec.name}
                        className="flex items-center justify-between gap-2 border-l-2 border-primary/40 bg-primary/[0.05] px-2 py-1.5"
                      >
                        <button
                          type="button"
                          onClick={() => onSelectDeployment(d.spec.name)}
                          className="truncate font-mono text-[11px] text-signal hover:text-primary hover:underline"
                        >
                          {d.spec.name}
                        </button>
                        <span className="shrink-0 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                          {here.length}/{d.spec.replicas} ·{" "}
                          {here.map((i) => `#${i.instanceIndex}`).join(" ")}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Section>

            <Section title="Quota impact">
              <p className="text-[11px] leading-relaxed text-muted-foreground">
                {nodeDeployments.filter((d) => d.quotaViolating).length > 0 ? (
                  <>
                    <span className="text-status-bad">
                      {nodeDeployments.filter((d) => d.quotaViolating).length} deployment(s)
                    </span>{" "}
                    placed here are quota-violating for their tenant.
                  </>
                ) : (
                  "No tenant quota violations among deployments placed on this node."
                )}
              </p>
              <div className="mt-2 flex flex-wrap gap-1">
                {Array.from(
                  new Set(nodeDeployments.map((d) => d.spec.tenantId ?? "unassigned")),
                ).map((t) => (
                  <span
                    key={t}
                    className="rounded-sm border border-primary/20 bg-primary/5 px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground"
                  >
                    {t}
                  </span>
                ))}
              </div>
            </Section>

            <Section title="Status timeline (mock)">
              <TimelinePanel seed={seed} kind="node" />
            </Section>

            <div className="mt-auto border-t border-primary/15 p-4">
              <Link
                to="/nodes/$nodeId"
                params={{ nodeId: selection.id }}
                className="block rounded-sm border border-primary/30 bg-primary/10 px-3 py-1.5 text-center font-mono text-[10px] uppercase tracking-widest text-primary hover:bg-primary/20"
              >
                Open full node page ↗
              </Link>
            </div>
          </>
        )}

        {selection?.kind === "deployment" && (
          <>
            <Section title="Replica placement">
              {deployment ? (
                <>
                  <div className="mb-2 grid grid-cols-2 gap-2 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    <span>
                      placed{" "}
                      <span className="text-signal tabular-nums">
                        {deployment.instances.length}/{deployment.spec.replicas}
                      </span>
                    </span>
                    <span className={deployment.unplacedCount > 0 ? "text-status-bad" : ""}>
                      unplaced <span className="tabular-nums">{deployment.unplacedCount}</span>
                    </span>
                    <span>
                      module {deployment.spec.moduleId.name}@{deployment.spec.moduleId.version}
                    </span>
                    <span>tenant {deployment.spec.tenantId ?? "unassigned"}</span>
                  </div>
                  <ul className="space-y-1">
                    {deployment.instances.map((i) => (
                      <li
                        key={i.instanceIndex}
                        className="flex items-center justify-between gap-2 border-l-2 border-status-ok/60 bg-primary/[0.05] px-2 py-1"
                      >
                        <span className="font-mono text-[11px] text-signal">
                          #{i.instanceIndex}
                        </span>
                        <button
                          type="button"
                          onClick={() => onSelectNode(i.nodeId)}
                          className="font-mono text-[10px] text-muted-foreground hover:text-primary hover:underline"
                        >
                          {i.nodeId}
                        </button>
                        <span
                          className={cn(
                            "font-mono text-[10px] uppercase tracking-widest",
                            i.observation.ready ? "text-status-ok" : "text-status-warn",
                          )}
                        >
                          {i.observation.lifecycleState}
                        </span>
                      </li>
                    ))}
                    {Array.from({
                      length: Math.max(0, deployment.spec.replicas - deployment.instances.length),
                    }).map((_, i) => (
                      <li
                        key={`u${i}`}
                        className="border-l-2 border-status-bad/60 bg-status-bad-bg/30 px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-status-bad"
                      >
                        unplaced replica
                      </li>
                    ))}
                  </ul>
                </>
              ) : (
                <p className="font-mono text-[11px] text-muted-foreground">
                  deployment unavailable
                </p>
              )}
            </Section>

            {deployment && (
              <Section title="Quota impact">
                <div className="mb-2 grid grid-cols-2 gap-2 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                  <span>
                    cpu used{" "}
                    <span className="text-signal tabular-nums">
                      {fmtMillicores(
                        deployment.instances.reduce(
                          (a, i) => a + i.observation.cpuMillicoresUsed,
                          0,
                        ),
                      )}
                    </span>
                  </span>
                  <span>
                    mem used{" "}
                    <span className="text-signal tabular-nums">
                      {fmtBytes(
                        deployment.instances.reduce((a, i) => a + i.observation.memoryBytesUsed, 0),
                      )}
                    </span>
                  </span>
                </div>
                <p
                  className={cn(
                    "text-[11px] leading-relaxed",
                    deployment.quotaViolating ? "text-status-bad" : "text-muted-foreground",
                  )}
                >
                  {deployment.quotaViolating
                    ? `Tenant ${deployment.spec.tenantId ?? "unassigned"} quota exceeded — scheduler will refuse new replicas until usage drops.`
                    : `Within tenant ${deployment.spec.tenantId ?? "unassigned"} quota. Headroom available for the remaining replicas.`}
                </p>
              </Section>
            )}

            <Section title="Status timeline (mock)">
              <TimelinePanel seed={seed} kind="deployment" />
            </Section>

            <div className="mt-auto border-t border-primary/15 p-4">
              <Link
                to="/deployments/$name"
                params={{ name: selection.id }}
                className="block rounded-sm border border-primary/30 bg-primary/10 px-3 py-1.5 text-center font-mono text-[10px] uppercase tracking-widest text-primary hover:bg-primary/20"
              >
                Open full deployment page ↗
              </Link>
            </div>
          </>
        )}
      </aside>
    </>
  );
}
