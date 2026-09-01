import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useStatefulSetsStore } from "@/stores/useStatefulSetsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
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
  instanceWindow,
  statefulSetInstanceRows,
} from "@/lib/workload-instances";
import { Trash2 } from "lucide-react";
import { toast } from "sonner";

export const Route = createFileRoute("/statefulsets/$name")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.name} — StatefulSets — Gimlé Console` },
      { name: "description", content: `StatefulSet detail for ${params.name}.` },
      { property: "og:title", content: `${params.name} — Gimlé Console` },
      { property: "og:description", content: `StatefulSet detail for ${params.name}.` },
    ],
  }),
  component: StatefulSetDetail,
});

function StatefulSetDetail() {
  const { name } = Route.useParams();
  const navigate = useNavigate();
  const items = useStatefulSetsStore((s) => s.items);
  const getOrFetch = useStatefulSetsStore((s) => s.getOrFetch);
  const remove = useStatefulSetsStore((s) => s.remove);
  const revisions = useStatefulSetsStore((s) => s.revisions);
  const loadRevisions = useStatefulSetsStore((s) => s.loadRevisions);
  const rollback = useStatefulSetsStore((s) => s.rollback);
  const [deleting, setDeleting] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const [visibleInstances, setVisibleInstances] = useState(WORKLOAD_INSTANCE_PAGE);

  useEffect(() => {
    getOrFetch(name).catch(() => setNotFound(true));
  }, [name, getOrFetch]);

  useEffect(() => {
    loadRevisions(name);
  }, [name, loadRevisions]);

  const s = items.find((x) => x.spec.name === name);

  if (notFound) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">StatefulSet not found.</p>
      </PageContainer>
    );
  }
  if (!s) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Loading statefulset…</p>
      </PageContainer>
    );
  }

  async function handleDelete() {
    setDeleting(true);
    try {
      await remove(name);
      toast.success(`Deleted ${name}`);
      navigate({ to: "/statefulsets" });
    } catch (e) {
      toast.error((e as Error).message);
      setDeleting(false);
    }
  }

  async function handleRollback(revision: number) {
    await rollback(name, revision);
    const err = useStatefulSetsStore.getState().error;
    if (err) toast.error(err);
    else toast.success(`Rolled back to revision ${revision}`);
  }

  const { visible: rows, hasMore } = instanceWindow(statefulSetInstanceRows(s), visibleInstances);

  return (
    <PageContainer>
      <PageHeader
        title={<span className="font-mono">{s.spec.name}</span>}
        subtitle={
          <span className="font-mono">
            {s.spec.moduleId.name}@{s.spec.moduleId.version}
          </span>
        }
        actions={
          <>
            <Button variant="outline" size="sm" asChild>
              <Link to="/statefulsets">Back</Link>
            </Button>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button variant="destructive" size="sm">
                  <Trash2 className="h-4 w-4" />
                  Delete statefulset
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete {s.spec.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    This removes the statefulset and stops every index. A local-disk volume left
                    behind by any index that had one is not deleted from its node automatically.
                    This action cannot be undone.
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
        <Field label="Replicas" value={`${s.instances.length} / ${s.spec.replicas}`} />
        <Field
          label="Unplaced"
          value={String(s.unplacedCount)}
          tone={s.unplacedCount > 0 ? "bad" : "ok"}
        />
        <Field label="Tenant" value={s.spec.tenantId ?? "—"} mono />
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Artifact
      </div>
      <div className="mb-6 rounded border border-border bg-muted/30 p-2 text-xs font-mono break-all">
        {s.spec.artifactPath}
      </div>

      <RevisionHistoryPanel revisions={revisions} onRollback={handleRollback} />

      <div className="mb-2 flex items-center justify-between">
        <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          Instances ({s.instances.length})
        </div>
        {s.unplacedCount > 0 && <StatusBadge variant="bad">{s.unplacedCount} unplaced</StatusBadge>}
      </div>
      <InstancesTable
        rows={rows}
        filters={{}}
        onFiltersChange={() => {}}
        showFilters={false}
        workloadKind="statefulset"
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
