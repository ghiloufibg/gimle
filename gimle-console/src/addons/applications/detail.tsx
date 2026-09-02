import { useEffect, useMemo, useState } from "react";
import { Link } from "@tanstack/react-router";
import { toast } from "sonner";

import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import { PageContainer, PageHeader, Panel } from "@/components/page-shell";
import { RevisionHistoryPanel } from "@/components/revision-history";
import { Button } from "@/components/ui/button";
import { fmtRelativeTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { HealthLabel, SyncLabel } from "@/addons/applications/components/status";
import { healthTone } from "@/addons/applications/components/tone";
import { ResourceTree } from "@/addons/applications/components/resource-tree";
import { kindSlug, type Application } from "@/addons/applications/model";
import { isRevisioned, useApplicationsStore } from "@/addons/applications/store";
import { GENERATED_JOBS_SHOWN, treeIsUseful } from "@/addons/applications/tree";

function Stat({
  label,
  children,
  alarm,
}: {
  label: string;
  children: React.ReactNode;
  alarm?: boolean;
}) {
  return (
    <div
      className={cn(
        "border-l-2 p-3",
        alarm ? "border-status-bad bg-status-bad-bg/40" : "border-primary/40 bg-primary/5",
      )}
    >
      <p className="hud-label mb-1">{label}</p>
      <div className="font-mono text-sm">{children}</div>
    </div>
  );
}

/** The facts that exist only for this kind, beside the two verdicts every kind carries. */
function KindStats({ app }: { app: Application }) {
  switch (app.detail.type) {
    case "replicated":
      return (
        <>
          <Stat label={app.detail.desiredReplicas === null ? "instances" : "replicas"}>
            {app.detail.desiredReplicas === null
              ? `${app.instances.length} nodes`
              : `${app.instances.length} / ${app.detail.desiredReplicas}`}
          </Stat>
          <Stat label="unplaced" alarm={app.detail.unplacedCount > 0}>
            {app.detail.unplacedCount}
          </Stat>
          <Stat label="services">{app.services.length}</Stat>
        </>
      );
    case "job":
      return (
        <>
          <Stat label="phase">{app.detail.phase}</Stat>
          <Stat label="attempt">
            {app.detail.attempt === null ? "—" : app.detail.attempt} of {app.detail.backoffLimit}
          </Stat>
          <Stat label="node">{app.instances[0]?.nodeId ?? "—"}</Stat>
        </>
      );
    case "cronjob":
      return (
        <>
          <Stat label="schedule">{app.detail.schedule}</Stat>
          <Stat label="last fired">
            {app.detail.lastScheduleTime === null
              ? "never"
              : fmtRelativeTime(app.detail.lastScheduleTime)}
          </Stat>
          <Stat label="concurrency">{app.detail.concurrencyPolicy}</Stat>
        </>
      );
    case "custom":
      return (
        <>
          <Stat label="generation">{app.detail.generation}</Stat>
          <Stat
            label="observed"
            alarm={
              app.detail.observedGeneration !== null &&
              app.detail.observedGeneration < app.detail.generation
            }
          >
            {app.detail.observedGeneration ?? "none"}
          </Stat>
          <Stat label="columns">{app.detail.columns.length}</Stat>
        </>
      );
  }
}

function ConditionsPanel({ app }: { app: Application }) {
  if (app.conditions.length === 0) return null;
  return (
    <Panel title="Conditions" className="mb-4">
      <div className="divide-y divide-border">
        {app.conditions.map((c, i) => (
          <div key={`${c.type}-${i}`} className="flex items-start gap-3 px-4 py-2">
            <span
              className={cn(
                "mt-0.5 shrink-0 rounded-sm border px-1.5 py-0.5 font-mono text-[9px] font-bold uppercase tracking-wider",
                c.severity === "bad"
                  ? "border-status-bad/30 bg-status-bad-bg text-status-bad"
                  : "border-status-warn/30 bg-status-warn-bg text-status-warn",
              )}
            >
              {c.severity === "bad" ? "blocking" : "warning"}
            </span>
            <span className="shrink-0 font-mono text-[11px] text-foreground">{c.type}</span>
            <span className="font-mono text-[11px] text-muted-foreground">{c.message}</span>
          </div>
        ))}
      </div>
    </Panel>
  );
}

/** The tree's own fallback: a flat list, which is what a wide fan-out is actually readable as. */
function ResourceList({ app }: { app: Application }) {
  return (
    <div className="divide-y divide-border">
      {app.services.map((s) => (
        <div key={s.name} className="flex items-center gap-3 px-4 py-2 font-mono text-[11px]">
          <span className="hud-label w-20 shrink-0">service</span>
          <Link to="/networking" className="text-primary hover:underline">
            {s.name}
          </Link>
          <span className="text-muted-foreground">port {s.port}</span>
        </div>
      ))}
      {app.instances.map((i) => (
        <div key={i.id} className="flex items-center gap-3 px-4 py-2 font-mono text-[11px]">
          <span className="hud-label w-20 shrink-0">instance</span>
          <span className="w-24 shrink-0 text-signal">{i.label}</span>
          <span className={healthTone(i.health)}>{i.observation.lifecycleState}</span>
          <Link
            to="/nodes/$nodeId"
            params={{ nodeId: i.nodeId }}
            className="text-muted-foreground hover:text-primary hover:underline"
          >
            {i.nodeId}
          </Link>
        </div>
      ))}
      {app.detail.type === "cronjob" &&
        app.detail.generatedJobs.map((j) => (
          <div key={j.name} className="flex items-center gap-3 px-4 py-2 font-mono text-[11px]">
            <span className="hud-label w-20 shrink-0">job</span>
            <Link
              to="/apps/$kind/$name"
              params={{ kind: "job", name: j.name }}
              search={app.tenantId ? { tenant: app.tenantId } : {}}
              className="text-primary hover:underline"
            >
              {j.name}
            </Link>
            <span className={healthTone(j.health)}>{j.phase}</span>
          </div>
        ))}
    </div>
  );
}

export function ApplicationDetailPage({
  kind,
  name,
  tenant,
}: {
  kind: string;
  name: string;
  tenant: string | null;
}) {
  const { applications, loaded, loading, error, load, poll, revisions, loadRevisions, rollback } =
    useApplicationsStore();
  const [view, setView] = useState<"tree" | "list" | null>(null);

  useEffect(() => {
    if (!loaded) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useAutoRefresh(poll);

  const app = useMemo(
    () =>
      applications.find(
        (a) => kindSlug(a) === kind && a.name === name && (a.tenantId ?? null) === tenant,
      ),
    [applications, kind, name, tenant],
  );

  useEffect(() => {
    loadRevisions(kind, name, tenant);
  }, [kind, name, tenant, loadRevisions]);

  if (app === undefined) {
    return (
      <PageContainer>
        <PageHeader eyebrow="Gimlé // Applications" title={name} />
        <p className="font-mono text-xs text-muted-foreground">
          {loaded
            ? `No ${kind} named ${name}${tenant ? ` in tenant ${tenant}` : ""} is deployed.`
            : "Loading…"}
        </p>
        {error && <p className="mt-2 font-mono text-xs text-status-bad">{error}</p>}
        <div className="mt-4">
          <Button variant="outline" size="sm" asChild>
            <Link to="/apps">Back to applications</Link>
          </Button>
        </div>
      </PageContainer>
    );
  }

  // A wide fan-out is unreadable as a tree, so the list leads there -- but the toggle stays, and an
  // explicit choice always wins over the default.
  const showTree = view === null ? treeIsUseful(app) : view === "tree";
  const generated = app.detail.type === "cronjob" ? app.detail.generatedJobs.length : 0;

  async function handleRollback(revision: number) {
    try {
      await rollback(kind, name, tenant, revision);
      toast.success(`Rolled back to revision ${revision}`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        eyebrow="Gimlé // Applications"
        title={<span className="font-mono">{app.name}</span>}
        subtitle={
          <span className="font-mono">
            {app.kindLabel}
            {app.tenantId ? ` · ${app.tenantId}` : ""}
            {app.moduleId ? ` · ${app.moduleId.name}@${app.moduleId.version}` : ""}
          </span>
        }
        actions={
          <Button variant="outline" size="sm" asChild>
            <Link to="/apps">Back</Link>
          </Button>
        }
      />

      {error && (
        <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
          {error}
        </div>
      )}

      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-5">
        <Stat label="health" alarm={app.health === "Degraded"}>
          <HealthLabel health={app.health} />
        </Stat>
        <Stat label="sync">
          <SyncLabel sync={app.sync} />
        </Stat>
        <KindStats app={app} />
      </div>

      <ConditionsPanel app={app} />

      <Panel
        title="Resources"
        className="mb-4"
        aside={
          <div className="flex items-center gap-2">
            {generated > 0 && (
              <span className="hud-label text-muted-foreground">
                {generated === 1
                  ? "newest run"
                  : `newest ${Math.min(generated, GENERATED_JOBS_SHOWN)} runs`}
              </span>
            )}
            <div className="flex rounded-sm border border-primary/20 bg-primary/5 p-0.5">
              {(["tree", "list"] as const).map((v) => (
                <button
                  key={v}
                  type="button"
                  onClick={() => setView(v)}
                  aria-pressed={showTree === (v === "tree")}
                  className={cn(
                    "px-2 py-0.5 font-mono text-[10px] uppercase tracking-widest transition-colors",
                    showTree === (v === "tree")
                      ? "bg-primary/20 text-primary"
                      : "text-muted-foreground hover:text-foreground",
                  )}
                >
                  {v}
                </button>
              ))}
            </div>
          </div>
        }
      >
        {showTree ? <ResourceTree app={app} /> : <ResourceList app={app} />}
      </Panel>

      {isRevisioned(kind) && (
        <RevisionHistoryPanel revisions={revisions} onRollback={handleRollback} />
      )}

      {app.detail.type === "custom" && app.detail.columns.length > 0 && (
        <Panel title="Reported fields">
          <div className="divide-y divide-border">
            {app.detail.columns.map(([label, value]) => (
              <div key={label} className="flex gap-4 px-4 py-2 font-mono text-[11px]">
                <span className="w-40 shrink-0 text-muted-foreground">{label}</span>
                <span className="break-all">{value}</span>
              </div>
            ))}
          </div>
        </Panel>
      )}

      {loading && <p className="mt-3 font-mono text-xs text-muted-foreground">syncing…</p>}
    </PageContainer>
  );
}
