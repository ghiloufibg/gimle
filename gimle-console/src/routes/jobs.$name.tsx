import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useJobsStore } from "@/stores/useJobsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
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
import { FileText, Trash2 } from "lucide-react";
import { toast } from "sonner";

export const Route = createFileRoute("/jobs/$name")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.name} — Jobs — Gimlé Console` },
      { name: "description", content: `Job detail for ${params.name}.` },
      { property: "og:title", content: `${params.name} — Gimlé Console` },
      { property: "og:description", content: `Job detail for ${params.name}.` },
    ],
  }),
  component: JobDetail,
});

function JobDetail() {
  const { name } = Route.useParams();
  const navigate = useNavigate();
  const items = useJobsStore((s) => s.items);
  const getOrFetch = useJobsStore((s) => s.getOrFetch);
  const remove = useJobsStore((s) => s.remove);
  const [deleting, setDeleting] = useState(false);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    getOrFetch(name).catch(() => setNotFound(true));
  }, [name, getOrFetch]);

  const j = items.find((x) => x.spec.name === name);

  if (notFound) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Job not found.</p>
      </PageContainer>
    );
  }
  if (!j) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Loading job…</p>
      </PageContainer>
    );
  }

  async function handleDelete() {
    setDeleting(true);
    try {
      await remove(name);
      toast.success(`Deleted ${name}`);
      navigate({ to: "/jobs" });
    } catch (e) {
      toast.error((e as Error).message);
      setDeleting(false);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title={<span className="font-mono">{j.spec.name}</span>}
        subtitle={
          <span className="font-mono">
            {j.spec.moduleId.name}@{j.spec.moduleId.version}
          </span>
        }
        actions={
          <>
            <Button variant="outline" size="sm" asChild>
              <Link to="/jobs">Back</Link>
            </Button>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button variant="destructive" size="sm">
                  <Trash2 className="h-4 w-4" />
                  Delete job
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete {j.spec.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    This will remove the job and stop its current attempt, if any. This action
                    cannot be undone.
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
        <Field
          label="Phase"
          value={j.phase}
          tone={j.phase === "SUCCEEDED" ? "ok" : j.phase === "FAILED" ? "bad" : undefined}
        />
        <Field label="Backoff limit" value={String(j.spec.backoffLimit)} />
        <Field
          label="Active deadline"
          value={
            j.spec.activeDeadlineSeconds !== undefined ? `${j.spec.activeDeadlineSeconds}s` : "none"
          }
        />
        <Field label="Tenant" value={j.spec.tenantId ?? "—"} mono />
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Artifact
      </div>
      <div className="mb-6 rounded border border-border bg-muted/30 p-2 text-xs font-mono break-all">
        {j.spec.artifactPath}
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Current attempt
      </div>
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Attempt</th>
              <th className="px-2 py-1.5 font-medium">Node</th>
              <th className="px-2 py-1.5 font-medium">Lifecycle</th>
              <th className="px-2 py-1.5 font-medium">A/R</th>
              <th className="px-2 py-1.5 font-medium text-right">cpu</th>
              <th className="px-2 py-1.5 font-medium text-right">mem</th>
              <th className="px-2 py-1.5 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {j.currentRun ? (
              <tr className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">{j.currentRun.attempt}</td>
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/nodes/$nodeId"
                    params={{ nodeId: j.currentRun.nodeId }}
                    className="text-primary hover:underline"
                  >
                    {j.currentRun.nodeId}
                  </Link>
                </td>
                {j.currentRun.observation ? (
                  <>
                    <td className="px-2 py-1.5">
                      <LifecycleBadge state={j.currentRun.observation.lifecycleState} />
                    </td>
                    <td className="px-2 py-1.5">
                      <div className="flex items-center gap-1">
                        <StatusDot variant={j.currentRun.observation.alive ? "ok" : "bad"} />
                        <StatusDot
                          variant={
                            j.currentRun.observation.ready
                              ? "ok"
                              : j.currentRun.observation.alive
                                ? "warn"
                                : "bad"
                          }
                        />
                      </div>
                    </td>
                    <td className="px-2 py-1.5 font-mono text-right">
                      {fmtMillicores(j.currentRun.observation.cpuMillicoresUsed)}
                    </td>
                    <td className="px-2 py-1.5 font-mono text-right">
                      {fmtBytes(j.currentRun.observation.memoryBytesUsed)}
                    </td>
                  </>
                ) : (
                  <td colSpan={4} className="px-2 py-1.5 text-muted-foreground">
                    not yet heartbeated
                  </td>
                )}
                <td className="px-2 py-1.5">
                  <Link
                    to="/logs"
                    search={{
                      kind: "instance",
                      deploymentName: j.spec.name,
                      instanceIndex: j.currentRun.attempt,
                      category: "APPLICATION" as const,
                    }}
                    className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1"
                  >
                    <FileText className="h-3 w-3" />
                    logs
                  </Link>
                </td>
              </tr>
            ) : (
              <tr>
                <td colSpan={7} className="px-4 py-6 text-center text-muted-foreground">
                  {j.phase === "RUNNING"
                    ? "No attempt placed yet."
                    : `No current attempt — job ${j.phase.toLowerCase()}.`}
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
