import { useEffect } from "react";
import { createFileRoute, Link } from "@tanstack/react-router";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/status";
import { useControlPlaneHealthStore } from "@/stores/useControlPlaneHealthStore";
import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import type { SubsystemHealth, SubsystemStatus } from "@/types";
import { FileText } from "lucide-react";

export const Route = createFileRoute("/controlplane")({
  head: () => ({
    meta: [
      { title: "Control plane — Gimlé Console" },
      { name: "description", content: "Control plane status and logs." },
      { property: "og:title", content: "Control plane — Gimlé Console" },
      { property: "og:description", content: "Control plane status and logs." },
    ],
  }),
  component: ControlPlane,
});

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="mb-3 rounded border border-status-bad/40 bg-status-bad-bg/40 px-3 py-2 text-xs text-status-bad">
      {message}
    </div>
  );
}

const STATUS_VARIANT: Record<SubsystemStatus, "ok" | "warn" | "bad" | "muted"> = {
  UP: "ok",
  DOWN: "bad",
  STANDBY: "muted",
  UNKNOWN: "warn",
};

const STATUS_LABEL: Record<SubsystemStatus, string> = {
  UP: "running",
  DOWN: "down",
  STANDBY: "standby",
  UNKNOWN: "unknown",
};

function SubsystemTile({ label, health }: { label: string; health: SubsystemHealth | undefined }) {
  const status = health?.status ?? "UNKNOWN";
  const title = health?.detail ?? (health?.lastRunAt ? `last ran ${health.lastRunAt}` : undefined);
  return (
    <div className="rounded border border-border bg-card p-3">
      <div className="text-[10px] uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className="mt-2">
        <StatusBadge variant={STATUS_VARIANT[status]} title={title}>
          {STATUS_LABEL[status]}
        </StatusBadge>
      </div>
    </div>
  );
}

function ControlPlane() {
  const { status, error, load, poll } = useControlPlaneHealthStore();
  useEffect(() => {
    load();
  }, [load]);
  useAutoRefresh(poll);

  return (
    <PageContainer>
      <PageHeader
        title="Control plane"
        subtitle="Cluster-wide scheduler, quota enforcer, and platform services."
      />
      {error && <ErrorBanner message={error} />}
      {status && !status.reconcilerLeader && (
        <div className="mb-3 rounded border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
          This replica is not currently the reconciler leader -- the badges below reflect standby
          state, not the cluster's overall health. Check whichever replica currently holds the
          lease.
        </div>
      )}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <SubsystemTile label="Scheduler" health={status?.scheduler} />
        <SubsystemTile label="Quota enforcer" health={status?.quotaEnforcer} />
        <SubsystemTile label="Heartbeat worker" health={status?.heartbeatWorker} />
        <SubsystemTile label="Artifact resolver" health={status?.artifactResolver} />
      </div>
      <div className="flex gap-2">
        <Button size="sm" asChild>
          <Link to="/logs" search={{ kind: "controlplane", category: "PLATFORM" as const }}>
            <FileText className="h-4 w-4" />
            Platform logs
          </Link>
        </Button>
        <Button variant="outline" size="sm" asChild>
          <Link to="/logs" search={{ kind: "controlplane", category: "SYSTEM" as const }}>
            <FileText className="h-4 w-4" />
            System logs
          </Link>
        </Button>
      </div>
    </PageContainer>
  );
}
