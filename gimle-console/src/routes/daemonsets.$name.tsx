import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useDaemonSetsStore } from "@/stores/useDaemonSetsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { RevisionHistoryPanel } from "@/components/revision-history";
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
  daemonSetInstanceRows,
  instanceWindow,
} from "@/lib/workload-instances";
import { Trash2 } from "lucide-react";
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
  const revisions = useDaemonSetsStore((s) => s.revisions);
  const loadRevisions = useDaemonSetsStore((s) => s.loadRevisions);
  const rollback = useDaemonSetsStore((s) => s.rollback);
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

  async function handleRollback(revision: number) {
    await rollback(name, revision);
    const err = useDaemonSetsStore.getState().error;
    if (err) toast.error(err);
    else toast.success(`Rolled back to revision ${revision}`);
  }

  const { visible: rows, hasMore } = instanceWindow(daemonSetInstanceRows(d), visibleInstances);

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

      <RevisionHistoryPanel revisions={revisions} onRollback={handleRollback} />

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Instances ({d.instances.length})
      </div>
      <InstancesTable
        rows={rows}
        filters={{}}
        onFiltersChange={() => {}}
        showFilters={false}
        workloadKind="daemonset"
        hasMore={hasMore}
        loading={false}
        onLoadMore={() => setVisibleInstances((n) => n + WORKLOAD_INSTANCE_PAGE)}
      />
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
