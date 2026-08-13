import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useDaemonSetsStore } from "@/stores/useDaemonSetsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { LifecycleBadge, StatusDot } from "@/components/status";
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
import { Trash2, FileText } from "lucide-react";
import { toast } from "sonner";

export const Route = createFileRoute("/daemonsets/$name")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.name} — DaemonSets — Gimlé Console` },
      { name: "description", content: `DaemonSet detail for ${params.name}.` },
      { property: "og:title", content: `${params.name} — Gimlé Console` },
      { property: "og:description", content: `DaemonSet detail for ${params.name}.` },
    ],
  }),
  component: DaemonSetDetail,
});

function DaemonSetDetail() {
  const { name } = Route.useParams();
  const navigate = useNavigate();
  const items = useDaemonSetsStore((s) => s.items);
  const getOrFetch = useDaemonSetsStore((s) => s.getOrFetch);
  const remove = useDaemonSetsStore((s) => s.remove);
  const [deleting, setDeleting] = useState(false);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    getOrFetch(name).catch(() => setNotFound(true));
  }, [name, getOrFetch]);

  const d = items.find((x) => x.spec.name === name);

  if (notFound) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">DaemonSet not found.</p>
      </PageContainer>
    );
  }
  if (!d) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Loading daemonset…</p>
      </PageContainer>
    );
  }

  async function handleDelete() {
    setDeleting(true);
    try {
      await remove(name);
      toast.success(`Deleted ${name}`);
      navigate({ to: "/daemonsets" });
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
              <Link to="/daemonsets">Back</Link>
            </Button>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button variant="destructive" size="sm">
                  <Trash2 className="h-4 w-4" />
                  Delete daemonset
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete {d.spec.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    This will remove the daemonset and stop its instance on every node. This action
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
        <Field label="Nodes running" value={String(d.instances.length)} />
        <Field
          label="Required labels"
          value={
            d.spec.placement.requiredNodeLabels.length > 0
              ? d.spec.placement.requiredNodeLabels.join(", ")
              : "none (all nodes)"
          }
          mono
        />
        <Field label="Tenant" value={d.spec.tenantId ?? "—"} mono />
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Artifact
      </div>
      <div className="mb-6 rounded border border-border bg-muted/30 p-2 text-xs font-mono break-all">
        {d.spec.artifactPath}
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Instances ({d.instances.length})
      </div>
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Node</th>
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
              <tr key={i.nodeId} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/nodes/$nodeId"
                    params={{ nodeId: i.nodeId }}
                    className="text-primary hover:underline"
                  >
                    {i.nodeId}
                  </Link>
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
                  <Link
                    to="/logs"
                    search={{
                      kind: "instance",
                      deploymentName: d.spec.name,
                      instanceIndex: 0,
                      category: "APPLICATION" as const,
                    }}
                    className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1"
                  >
                    <FileText className="h-3 w-3" />
                    logs
                  </Link>
                </td>
              </tr>
            ))}
            {d.instances.length === 0 && (
              <tr>
                <td colSpan={8} className="px-4 py-6 text-center text-muted-foreground">
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

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="rounded border border-border bg-card p-3">
      <div className="text-[10px] uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className={`mt-1 text-sm ${mono ? "font-mono" : ""}`}>{value}</div>
    </div>
  );
}
