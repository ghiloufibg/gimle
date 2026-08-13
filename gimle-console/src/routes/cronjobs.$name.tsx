import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useCronJobsStore } from "@/stores/useCronJobsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
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
import { Trash2, Zap } from "lucide-react";
import { toast } from "sonner";

export const Route = createFileRoute("/cronjobs/$name")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.name} — CronJobs — Gimlé Console` },
      { name: "description", content: `CronJob detail for ${params.name}.` },
      { property: "og:title", content: `${params.name} — Gimlé Console` },
      { property: "og:description", content: `CronJob detail for ${params.name}.` },
    ],
  }),
  component: CronJobDetail,
});

function CronJobDetail() {
  const { name } = Route.useParams();
  const navigate = useNavigate();
  const items = useCronJobsStore((s) => s.items);
  const getOrFetch = useCronJobsStore((s) => s.getOrFetch);
  const remove = useCronJobsStore((s) => s.remove);
  const trigger = useCronJobsStore((s) => s.trigger);
  const [deleting, setDeleting] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    getOrFetch(name).catch(() => setNotFound(true));
  }, [name, getOrFetch]);

  const c = items.find((x) => x.spec.name === name);

  if (notFound) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">CronJob not found.</p>
      </PageContainer>
    );
  }
  if (!c) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Loading cronjob…</p>
      </PageContainer>
    );
  }

  async function handleDelete() {
    setDeleting(true);
    try {
      await remove(name);
      toast.success(`Deleted ${name}`);
      navigate({ to: "/cronjobs" });
    } catch (e) {
      toast.error((e as Error).message);
      setDeleting(false);
    }
  }

  async function handleTrigger() {
    setTriggering(true);
    try {
      const jobName = await trigger(name);
      toast.success(`Triggered ${name} -> job ${jobName}`);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setTriggering(false);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title={<span className="font-mono">{c.spec.name}</span>}
        subtitle={
          <span className="font-mono">
            {c.spec.jobTemplate.moduleId.name}@{c.spec.jobTemplate.moduleId.version}
          </span>
        }
        actions={
          <>
            <Button variant="outline" size="sm" asChild>
              <Link to="/cronjobs">Back</Link>
            </Button>
            <Button variant="outline" size="sm" onClick={handleTrigger} disabled={triggering}>
              <Zap className="h-4 w-4" />
              {triggering ? "Triggering…" : "Trigger now"}
            </Button>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button variant="destructive" size="sm">
                  <Trash2 className="h-4 w-4" />
                  Delete cronjob
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete {c.spec.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    This removes the schedule. Jobs it already generated are left running
                    independently. This action cannot be undone.
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
        <Field label="Schedule" value={c.spec.schedule} mono />
        <Field label="Concurrency policy" value={c.spec.concurrencyPolicy} />
        <Field
          label="Starting deadline"
          value={
            c.spec.startingDeadlineSeconds !== undefined
              ? `${c.spec.startingDeadlineSeconds}s`
              : "none"
          }
        />
        <Field label="Tenant" value={c.spec.tenantId ?? "—"} mono />
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
        <Field label="Backoff limit" value={String(c.spec.jobTemplate.backoffLimit)} />
        <Field
          label="Job active deadline"
          value={
            c.spec.jobTemplate.activeDeadlineSeconds !== undefined
              ? `${c.spec.jobTemplate.activeDeadlineSeconds}s`
              : "none"
          }
        />
        <Field label="Last fired" value={c.lastScheduleTime ?? "never"} mono />
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Artifact
      </div>
      <div className="mb-6 rounded border border-border bg-muted/30 p-2 text-xs font-mono break-all">
        {c.spec.jobTemplate.artifactPath}
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Generated jobs
      </div>
      <div className="rounded border border-border bg-muted/30 p-3 text-xs text-muted-foreground">
        Every firing of this schedule generates a Job named{" "}
        <span className="font-mono">{c.spec.name}-&lt;epochSeconds&gt;</span>, visible on the{" "}
        <Link to="/jobs" className="text-primary hover:underline">
          Jobs
        </Link>{" "}
        screen.
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
