import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useDeploymentsStore } from "@/stores/useDeploymentsStore";
import { PageContainer, PageHeader, Panel } from "@/components/page-shell";
import { LifecycleBadge, StatusBadge, StatusDot } from "@/components/status";
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
import { fmtBytes, fmtMillicores } from "@/lib/format";
import { Trash2, FileText, Activity } from "lucide-react";
import { joinWorkerProcessId } from "@/components/process-picker";
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
  const [deleting, setDeleting] = useState(false);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    getOrFetch(name).catch(() => setNotFound(true));
  }, [name, getOrFetch]);

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

      <div className="mb-2 flex items-center justify-between">
        <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          Instances ({d.instances.length})
        </div>
        {d.unplacedCount > 0 && <StatusBadge variant="bad">{d.unplacedCount} unplaced</StatusBadge>}
      </div>
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Idx</th>
              <th className="px-2 py-1.5 font-medium">Node</th>
              <th className="px-2 py-1.5 font-medium">Worker</th>
              <th className="px-2 py-1.5 font-medium">Lifecycle</th>
              <th className="px-2 py-1.5 font-medium">A/R</th>
              <th className="px-2 py-1.5 font-medium text-right">req/s</th>
              <th className="px-2 py-1.5 font-medium text-right">queue</th>
              <th className="px-2 py-1.5 font-medium text-right">cpu</th>
              <th className="px-2 py-1.5 font-medium text-right">mem</th>
              <th className="px-2 py-1.5 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {d.instances.map((i) => (
              <tr key={i.instanceIndex} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/instances/$name/$idx"
                    params={{ name: d.spec.name, idx: String(i.instanceIndex) }}
                    className="text-primary hover:underline"
                  >
                    {i.instanceIndex}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/nodes/$nodeId"
                    params={{ nodeId: i.nodeId }}
                    className="text-primary hover:underline"
                  >
                    {i.nodeId}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {i.observation.workerId ?? "—"}
                </td>
                <td className="px-2 py-1.5">
                  <LifecycleBadge state={i.observation.lifecycleState} />
                </td>
                <td className="px-2 py-1.5">
                  <div className="flex items-center gap-1">
                    <StatusDot variant={i.observation.alive ? "ok" : "bad"} />
                    <StatusDot
                      variant={i.observation.ready ? "ok" : i.observation.alive ? "warn" : "bad"}
                    />
                  </div>
                </td>
                <td className="px-2 py-1.5 font-mono text-right">
                  {i.observation.requestRatePerSecond.toFixed(1)}
                </td>
                <td className="px-2 py-1.5 font-mono text-right">{i.observation.queueDepth}</td>
                <td className="px-2 py-1.5 font-mono text-right">
                  {fmtMillicores(i.observation.cpuMillicoresUsed)}
                </td>
                <td className="px-2 py-1.5 font-mono text-right">
                  {fmtBytes(i.observation.memoryBytesUsed)}
                </td>
                <td className="px-2 py-1.5">
                  <div className="flex items-center gap-2">
                    <Link
                      to="/logs"
                      search={{
                        kind: "instance",
                        deploymentName: d.spec.name,
                        instanceIndex: i.instanceIndex,
                        category: "APPLICATION" as const,
                      }}
                      className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1"
                    >
                      <FileText className="h-3 w-3" />
                      logs
                    </Link>
                    {i.observation.workerId && (
                      <Link
                        to="/metrics"
                        search={{
                          processKind: "WORKER" as const,
                          processId: joinWorkerProcessId(i.nodeId, i.observation.workerId),
                        }}
                        className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1"
                      >
                        <Activity className="h-3 w-3" />
                        metrics
                      </Link>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {d.instances.length === 0 && (
              <tr>
                <td colSpan={10} className="px-4 py-6 text-center text-muted-foreground">
                  No placed instances.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
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
