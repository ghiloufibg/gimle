import { Link } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { HealthLabel, SyncLabel } from "@/addons/applications/components/status";
import { HEALTH_RAIL } from "@/addons/applications/components/tone";
import { kindSlug, type Application } from "@/addons/applications/model";

/** Filled = placed and healthy, hollow = a replica the manifest asked for and nothing carries. */
function ReplicaGrid({ app }: { app: Application }) {
  if (app.detail.type !== "replicated") return null;
  const placed = app.instances.length;
  const desired = app.detail.desiredReplicas;
  const total = Math.max(placed, desired ?? placed);
  if (total === 0) return <span className="font-mono text-[10px] text-muted-foreground">none</span>;
  return (
    <span className="flex flex-wrap gap-[3px]">
      {Array.from({ length: total }).map((_, i) => {
        const instance = app.instances[i];
        return (
          <span
            key={i}
            title={instance ? `${instance.label} → ${instance.nodeId}` : "unplaced"}
            className={cn(
              "h-2.5 w-2.5 rounded-[1px] border",
              instance === undefined && "border-status-bad/50 bg-transparent",
              instance?.health === "Healthy" && "border-status-ok bg-status-ok/70",
              instance?.health === "Progressing" && "border-status-warn bg-status-warn/70",
              instance?.health === "Degraded" && "border-status-bad bg-status-bad/70",
              instance?.health === "Unknown" && "border-status-muted bg-status-muted/70",
            )}
          />
        );
      })}
    </span>
  );
}

/** The kind-specific third row: what an operator of *this* kind checks first. */
function KindSignal({ app }: { app: Application }) {
  switch (app.detail.type) {
    case "replicated":
      return (
        <>
          <span className="hud-label">
            {app.detail.desiredReplicas === null ? "nodes" : "replicas"}
          </span>
          <ReplicaGrid app={app} />
        </>
      );
    case "job":
      return (
        <>
          <span className="hud-label">phase</span>
          <span className="font-mono text-[11px]">
            {app.detail.phase}
            {app.detail.attempt !== null && (
              <span className="text-muted-foreground">
                {" "}
                · attempt {app.detail.attempt} of {app.detail.backoffLimit}
              </span>
            )}
          </span>
        </>
      );
    case "cronjob": {
      const newest = app.detail.generatedJobs[0];
      return (
        <>
          <span className="hud-label">last run</span>
          <span className="font-mono text-[11px]">
            {newest ? newest.phase : "never fired"}
            <span className="text-muted-foreground"> · {app.detail.schedule}</span>
          </span>
        </>
      );
    }
    case "custom":
      return (
        <>
          <span className="hud-label">generation</span>
          <span className="font-mono text-[11px]">
            {app.detail.observedGeneration === null
              ? `${app.detail.generation} · none observed`
              : `${app.detail.observedGeneration} of ${app.detail.generation} observed`}
          </span>
        </>
      );
  }
}

function subtitle(app: Application): string {
  const module = app.moduleId ? ` · ${app.moduleId.name}@${app.moduleId.version}` : "";
  return `${app.kindLabel}${app.tenantId ? ` · ${app.tenantId}` : ""}${module}`;
}

export function ApplicationTile({ app }: { app: Application }) {
  const worst = app.conditions.find((c) => c.severity === "bad") ?? app.conditions[0];
  return (
    <Link
      to="/apps/$kind/$name"
      params={{ kind: kindSlug(app), name: app.name }}
      search={app.tenantId ? { tenant: app.tenantId } : {}}
      className={cn(
        "flex flex-col gap-2 border border-l-[3px] border-border bg-card p-3 transition-colors hover:bg-muted/40",
        HEALTH_RAIL[app.health],
      )}
    >
      <div className="min-w-0">
        <div className="truncate font-mono text-xs font-bold text-signal">{app.name}</div>
        <div className="truncate font-mono text-[10px] text-muted-foreground">{subtitle(app)}</div>
      </div>
      <div className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1 text-[11px]">
        <span className="hud-label">health</span>
        <HealthLabel health={app.health} />
        <span className="hud-label">sync</span>
        <SyncLabel sync={app.sync} />
        <KindSignal app={app} />
      </div>
      {worst !== undefined && (
        <div
          className={cn(
            "truncate font-mono text-[10px]",
            worst.severity === "bad" ? "text-status-bad" : "text-status-warn",
          )}
          title={worst.message}
        >
          {app.conditions.length} condition{app.conditions.length === 1 ? "" : "s"} ·{" "}
          {worst.message}
        </div>
      )}
      {worst === undefined && app.services.length > 0 && (
        <div className="truncate font-mono text-[10px] text-muted-foreground">
          {app.services.length} service{app.services.length === 1 ? "" : "s"} ·{" "}
          {app.services.map((s) => s.name).join(", ")}
        </div>
      )}
    </Link>
  );
}
