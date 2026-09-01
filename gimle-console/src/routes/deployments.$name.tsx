import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useDeploymentsStore } from "@/stores/useDeploymentsStore";
import { PageContainer, PageHeader, Panel } from "@/components/page-shell";
import { RevisionHistoryPanel } from "@/components/revision-history";
import { StatusBadge } from "@/components/status";
import { InstancesTable } from "@/components/instances-table";
import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import {
  WORKLOAD_INSTANCE_PAGE,
  deploymentInstanceRows,
  instanceWindow,
} from "@/lib/workload-instances";
import { Trash2 } from "lucide-react";
import { toast } from "sonner";
import type { AutoscalePolicy, DisruptionBudget } from "@/types";

export const Route = createFileRoute("/deployments/$name")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.name} — Deployments — Gimlé Console` },
      { name: "description", content: `Deployment detail for ${params.name}.` },
      { property: "og:title", content: `${params.name} — Gimlé Console` },
      { property: "og:description", content: `Deployment detail for ${params.name}.` },
    ],
  }),
  component: DeploymentDetail,
});

function DeploymentDetail() {
  const { name } = Route.useParams();
  const navigate = useNavigate();
  const items = useDeploymentsStore((s) => s.items);
  const getOrFetch = useDeploymentsStore((s) => s.getOrFetch);
  const remove = useDeploymentsStore((s) => s.remove);
  const revisions = useDeploymentsStore((s) => s.revisions);
  const loadRevisions = useDeploymentsStore((s) => s.loadRevisions);
  const rollback = useDeploymentsStore((s) => s.rollback);
  const [deleting, setDeleting] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const [visibleInstances, setVisibleInstances] = useState(WORKLOAD_INSTANCE_PAGE);

  useEffect(() => {
    getOrFetch(name).catch(() => setNotFound(true));
  }, [name, getOrFetch]);

  useEffect(() => {
    loadRevisions(name);
  }, [name, loadRevisions]);

  const d = items.find((x) => x.spec.name === name);

  if (notFound) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Deployment not found.</p>
      </PageContainer>
    );
  }
  if (!d) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Loading deployment…</p>
      </PageContainer>
    );
  }

  async function handleDelete() {
    setDeleting(true);
    try {
      await remove(name);
      toast.success(`Deleted ${name}`);
      navigate({ to: "/deployments" });
    } catch (e) {
      toast.error((e as Error).message);
      setDeleting(false);
    }
  }

  async function handleRollback(revision: number) {
    await rollback(name, revision);
    const err = useDeploymentsStore.getState().error;
    if (err) toast.error(err);
    else toast.success(`Rolled back to revision ${revision}`);
  }

  const { visible: rows, hasMore } = instanceWindow(deploymentInstanceRows(d), visibleInstances);

  return (
    <PageContainer>
      <PageHeader
        title={<span className="font-mono">{d.spec.name}</span>}
        subtitle={
          <span className="font-mono">
            {d.spec.moduleId.name}@{d.spec.moduleId.version}
          </span>
        }
        actions={
          <>
            <Button variant="outline" size="sm" asChild>
              <Link to="/deployments">Back</Link>
            </Button>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button variant="destructive" size="sm">
                  <Trash2 className="h-4 w-4" />
                  Delete deployment
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete {d.spec.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    This will remove the deployment and stop all its instances. This action cannot
                    be undone.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel disabled={deleting}>Cancel</AlertDialogCancel>
                  <AlertDialogAction
                    onClick={handleDelete}
                    disabled={deleting}
                    className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                  >
                    {deleting ? "Deleting…" : "Delete"}
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          </>
        }
      />

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
        <Field label="Replicas" value={`${d.instances.length} / ${d.spec.replicas}`} />
        <Field
          label="Unplaced"
          value={String(d.unplacedCount)}
          tone={d.unplacedCount > 0 ? "bad" : "ok"}
        />
        <Field label="Tenant" value={d.spec.tenantId ?? "—"} mono />
        <Field
          label="Quota"
          value={d.quotaViolating ? "VIOLATING" : "OK"}
          tone={d.quotaViolating ? "bad" : "ok"}
        />
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Artifact
      </div>
      <div className="mb-6 rounded border border-border bg-muted/30 p-2 text-xs font-mono break-all">
        {d.spec.artifactPath}
      </div>

      {d.spec.autoscale && <AutoscalePanel policy={d.spec.autoscale} />}
      {d.spec.disruption && <DisruptionPanel budget={d.spec.disruption} />}
      <RevisionHistoryPanel revisions={revisions} onRollback={handleRollback} />

      <div className="mb-2 flex items-center justify-between">
        <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          Instances ({d.instances.length})
        </div>
        {d.unplacedCount > 0 && <StatusBadge variant="bad">{d.unplacedCount} unplaced</StatusBadge>}
      </div>
      <InstancesTable
        rows={rows}
        filters={{}}
        onFiltersChange={() => {}}
        showFilters={false}
        hasMore={hasMore}
        loading={false}
        onLoadMore={() => setVisibleInstances((n) => n + WORKLOAD_INSTANCE_PAGE)}
      />
    </PageContainer>
  );
}

function Field({
  label,
  value,
  mono,
  tone,
}: {
  label: string;
  value: string;
  mono?: boolean;
  tone?: "ok" | "warn" | "bad";
}) {
  const toneClass =
    tone === "ok"
      ? "text-status-ok"
      : tone === "warn"
        ? "text-status-warn"
        : tone === "bad"
          ? "text-status-bad"
          : "";
  return (
    <div className="rounded border border-border bg-card p-3">
      <div className="text-[10px] uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className={`mt-1 text-sm ${mono ? "font-mono" : ""} ${toneClass}`}>{value}</div>
    </div>
  );
}

/** Read-only view of the deployment's autoscale policy (absent on a deployment with a fixed
 * replica count -- the common case). */
function AutoscalePanel({ policy }: { policy: AutoscalePolicy }) {
  const targets: Array<[string, string]> = [
    ["cpu utilization", `${policy.targetCpuUtilizationPercent}%`],
  ];
  if (policy.targetRequestRatePerSecond !== undefined)
    targets.push(["request rate", `${policy.targetRequestRatePerSecond} req/s`]);
  if (policy.targetErrorRatePercent !== undefined)
    targets.push(["error rate", `${policy.targetErrorRatePercent}%`]);
  if (policy.targetQueueDepth !== undefined)
    targets.push(["queue depth", String(policy.targetQueueDepth)]);

  const weights: Array<[string, string]> =
    policy.combinationMode === "WEIGHTED"
      ? (
          [
            ["cpu", policy.cpuWeight],
            ["request rate", policy.requestRateWeight],
            ["error rate", policy.errorRateWeight],
            ["queue depth", policy.queueDepthWeight],
          ] as Array<[string, number | undefined]>
        )
          .filter(([, v]) => v !== undefined)
          .map(([k, v]) => [k, String(v)] as [string, string])
      : [];

  return (
    <Panel
      title="Autoscale"
      className="mb-6"
      aside={
        <span className="hud-label text-muted-foreground">
          {policy.combinationMode === "WEIGHTED" ? "weighted" : "worst signal"}
        </span>
      }
    >
      <div className="grid grid-cols-1 gap-px bg-primary/10 md:grid-cols-3">
        <div className="bg-background p-3">
          <p className="hud-label mb-1 text-muted-foreground">replica bounds</p>
          <p className="font-mono text-sm text-signal tabular-nums">
            {policy.minReplicas} — {policy.maxReplicas}
          </p>
        </div>
        <div className="bg-background p-3">
          <p className="hud-label mb-1 text-muted-foreground">target signals</p>
          <ul className="space-y-0.5">
            {targets.map(([k, v]) => (
              <li key={k} className="flex justify-between gap-3 font-mono text-[11px]">
                <span className="text-muted-foreground">{k}</span>
                <span className="tabular-nums text-foreground">{v}</span>
              </li>
            ))}
          </ul>
        </div>
        <div className="bg-background p-3">
          <p className="hud-label mb-1 text-muted-foreground">weights</p>
          {weights.length === 0 ? (
            <p className="font-mono text-[11px] text-muted-foreground">n/a — worst-signal mode</p>
          ) : (
            <ul className="space-y-0.5">
              {weights.map(([k, v]) => (
                <li key={k} className="flex justify-between gap-3 font-mono text-[11px]">
                  <span className="text-muted-foreground">{k}</span>
                  <span className="tabular-nums text-foreground">{v}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </Panel>
  );
}

/** Read-only view of the deployment's disruption budget (absent on a deployment relying on the
 * default one-at-a-time rolling update -- the common case). */
function DisruptionPanel({ budget }: { budget: DisruptionBudget }) {
  return (
    <Panel title="Disruption budget" className="mb-6">
      <div className="grid grid-cols-1 gap-px bg-primary/10 md:grid-cols-2">
        <div className="bg-background p-3">
          <p className="hud-label mb-1 text-muted-foreground">max unavailable</p>
          <p className="font-mono text-sm text-signal tabular-nums">{budget.maxUnavailable}</p>
        </div>
        <div className="bg-background p-3">
          <p className="hud-label mb-1 text-muted-foreground">max surge</p>
          <p className="font-mono text-sm text-signal tabular-nums">{budget.maxSurge}</p>
        </div>
      </div>
    </Panel>
  );
}
