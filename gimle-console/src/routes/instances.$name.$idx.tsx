import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useInstancesStore } from "@/stores/useInstancesStore";
import { useEventsStore } from "@/stores/useEventsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { InstanceEventsPanel } from "@/components/instance-events";
import { LifecycleBadge, StatusDot } from "@/components/status";
import { Button } from "@/components/ui/button";
import { joinWorkerProcessId } from "@/components/process-picker";
import { fmtBytes, fmtMillicores } from "@/lib/format";
import { Activity, FileText, Waypoints } from "lucide-react";

export const Route = createFileRoute("/instances/$name/$idx")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.name}#${params.idx} — Instance — Gimlé Console` },
      { name: "description", content: `Instance detail for ${params.name} index ${params.idx}.` },
      { property: "og:title", content: `${params.name}#${params.idx} — Gimlé Console` },
      { property: "og:description", content: `Instance detail for ${params.name}.` },
    ],
  }),
  component: InstanceDetail,
});

function InstanceDetail() {
  const { name, idx } = Route.useParams();
  const instanceIndex = parseInt(idx, 10);
  const items = useInstancesStore((s) => s.items);
  const getOrFetch = useInstancesStore((s) => s.getOrFetch);
  const events = useEventsStore((s) => s.items);
  const eventsLoading = useEventsStore((s) => s.loading);
  const eventsLoaded = useEventsStore((s) => s.loaded);
  const eventsError = useEventsStore((s) => s.error);
  const loadEvents = useEventsStore((s) => s.load);
  const resetEvents = useEventsStore((s) => s.reset);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    getOrFetch(name, instanceIndex).catch(() => setNotFound(true));
  }, [name, instanceIndex, getOrFetch]);

  const r = items.find((x) => x.deploymentName === name && x.instanceIndex === instanceIndex);

  // The timeline is keyed by the instance's own tenant, so it can only be fetched once the
  // instance row itself has resolved one -- a bare name would address the untenanted namespace.
  const tenantId = r?.tenantId ?? null;
  const instanceResolved = r !== undefined;
  useEffect(() => {
    if (!instanceResolved) return;
    loadEvents(name, instanceIndex, tenantId);
    return resetEvents;
  }, [instanceResolved, name, instanceIndex, tenantId, loadEvents, resetEvents]);

  if (notFound) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Instance not found.</p>
      </PageContainer>
    );
  }
  if (!r) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Loading instance…</p>
      </PageContainer>
    );
  }

  return (
    <PageContainer>
      <PageHeader
        title={
          <span className="font-mono">
            {r.deploymentName} <span className="text-muted-foreground">#{r.instanceIndex}</span>
          </span>
        }
        subtitle={
          <span className="font-mono">
            {r.moduleId.name}@{r.moduleId.version}
          </span>
        }
        actions={
          <>
            <Button variant="outline" size="sm" asChild>
              <Link to="/deployments/$name" params={{ name: r.deploymentName }}>
                Parent deployment
              </Link>
            </Button>
            <Button size="sm" asChild>
              <Link
                to="/logs"
                search={{
                  kind: "instance",
                  deploymentName: r.deploymentName,
                  instanceIndex: r.instanceIndex,
                  category: "APPLICATION" as const,
                }}
              >
                <FileText className="h-4 w-4" />
                View logs
              </Link>
            </Button>
            {r.workerId && (
              <>
                <Button variant="outline" size="sm" asChild>
                  <Link
                    to="/metrics"
                    search={{
                      processKind: "WORKER" as const,
                      processId: joinWorkerProcessId(r.nodeId, r.workerId),
                    }}
                  >
                    <Activity className="h-4 w-4" />
                    Worker metrics
                  </Link>
                </Button>
                <Button variant="outline" size="sm" asChild>
                  <Link
                    to="/traces"
                    search={{
                      processKind: "WORKER" as const,
                      processId: joinWorkerProcessId(r.nodeId, r.workerId),
                    }}
                  >
                    <Waypoints className="h-4 w-4" />
                    Worker traces
                  </Link>
                </Button>
              </>
            )}
          </>
        }
      />

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
        <StatCell label="Lifecycle" value={<LifecycleBadge state={r.lifecycleState} />} />
        <StatCell
          label="Alive / Ready"
          value={
            <div className="flex items-center gap-2">
              <StatusDot variant={r.alive ? "ok" : "bad"} />
              <span className="text-xs">{r.alive ? "alive" : "down"}</span>
              <StatusDot variant={r.ready ? "ok" : r.alive ? "warn" : "bad"} className="ml-2" />
              <span className="text-xs">{r.ready ? "ready" : "not ready"}</span>
            </div>
          }
        />
        <StatCell label="Request rate" value={`${r.requestRatePerSecond.toFixed(1)} req/s`} mono />
        <StatCell
          label="Error rate"
          value={`${r.errorRatePerSecond.toFixed(2)} err/s`}
          mono
          tone={r.errorRatePerSecond > 0 ? "alarm" : "default"}
        />
        <StatCell label="Queue depth" value={String(r.queueDepth)} mono />
        <StatCell label="CPU used" value={fmtMillicores(r.cpuMillicoresUsed)} mono />
        <StatCell label="Memory used" value={fmtBytes(r.memoryBytesUsed)} mono />
        <StatCell
          label="Node"
          value={
            <Link
              to="/nodes/$nodeId"
              params={{ nodeId: r.nodeId }}
              className="text-primary hover:underline font-mono"
            >
              {r.nodeId}
            </Link>
          }
        />
        <StatCell label="Worker" value={r.workerId ?? "—"} mono />
        <StatCell label="Tenant" value={r.tenantId ?? "—"} mono />
      </div>

      <InstanceEventsPanel
        events={events}
        loading={eventsLoading}
        loaded={eventsLoaded}
        error={eventsError}
      />

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Artifact
      </div>
      <div className="rounded border border-border bg-muted/30 p-2 text-xs font-mono break-all">
        {r.artifactPath}
      </div>
    </PageContainer>
  );
}

function StatCell({
  label,
  value,
  mono,
  tone = "default",
}: {
  label: string;
  value: React.ReactNode;
  mono?: boolean;
  tone?: "default" | "alarm";
}) {
  return (
    <div className="rounded border border-border bg-card p-3">
      <div className="text-[10px] uppercase tracking-wider text-muted-foreground">{label}</div>
      <div
        className={`mt-1 text-sm ${mono ? "font-mono" : ""} ${tone === "alarm" ? "text-status-bad" : ""}`}
      >
        {value}
      </div>
    </div>
  );
}
