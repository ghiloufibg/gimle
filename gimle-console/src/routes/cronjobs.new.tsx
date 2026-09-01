import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useCronJobsStore } from "@/stores/useCronJobsStore";
import { useTenantsStore } from "@/stores/useTenantsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { toast } from "sonner";
import { notifyApiError } from "@/lib/api-error";
import type { ConcurrencyPolicy, CronJobSpecInput } from "@/types";

export const Route = createFileRoute("/cronjobs/new")({
  head: () => ({
    meta: [
      { title: "New cronjob — Gimlé Console" },
      { name: "description", content: "Create a new scheduled job generator." },
      { property: "og:title", content: "New cronjob — Gimlé Console" },
      { property: "og:description", content: "Create a new scheduled job generator." },
    ],
  }),
  component: NewCronJob,
});

export interface CronJobFormState {
  name: string;
  schedule: string;
  moduleName: string;
  moduleVersion: string;
  artifactPath: string;
  backoffLimit: string;
  activeDeadlineSeconds: string;
  startingDeadlineSeconds: string;
  concurrencyPolicy: ConcurrencyPolicy;
  tenantId: string;
}

export const DEFAULT_CRONJOB_FORM: CronJobFormState = {
  name: "",
  schedule: "",
  moduleName: "",
  moduleVersion: "",
  artifactPath: "",
  backoffLimit: "6",
  activeDeadlineSeconds: "",
  startingDeadlineSeconds: "",
  concurrencyPolicy: "ALLOW",
  tenantId: "NONE",
};

/** A cron schedule is exactly five whitespace-separated fields (minute hour dom month dow). */
export function isValidCronSchedule(schedule: string): boolean {
  const fields = schedule
    .trim()
    .split(/\s+/)
    .filter((f) => f.length > 0);
  return fields.length === 5;
}

export function cronJobFormIsValid(form: CronJobFormState): boolean {
  return (
    form.name.trim() !== "" &&
    isValidCronSchedule(form.schedule) &&
    form.moduleName.trim() !== "" &&
    form.moduleVersion.trim() !== ""
  );
}

export function buildCronJobSpec(form: CronJobFormState): CronJobSpecInput {
  const spec: CronJobSpecInput = {
    name: form.name,
    schedule: form.schedule.trim(),
    jobTemplate: {
      moduleId: { name: form.moduleName, version: form.moduleVersion },
      artifactPath: form.artifactPath,
      backoffLimit: Math.max(0, parseInt(form.backoffLimit, 10) || 0),
    },
    concurrencyPolicy: form.concurrencyPolicy,
    tenantId: form.tenantId === "NONE" ? null : form.tenantId,
  };
  const jobDeadline = form.activeDeadlineSeconds.trim();
  if (jobDeadline !== "") spec.jobTemplate.activeDeadlineSeconds = Number(jobDeadline);
  const startingDeadline = form.startingDeadlineSeconds.trim();
  if (startingDeadline !== "") spec.startingDeadlineSeconds = Number(startingDeadline);
  return spec;
}

function NewCronJob() {
  const navigate = useNavigate();
  const create = useCronJobsStore((s) => s.create);
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<CronJobFormState>(DEFAULT_CRONJOB_FORM);

  useEffect(() => {
    if (tenants.length === 0) loadTenants();
  }, [tenants.length, loadTenants]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!cronJobFormIsValid(form)) {
      toast.error(
        form.name.trim() !== "" && !isValidCronSchedule(form.schedule)
          ? "Schedule must be a 5-field cron expression"
          : "Fill required fields",
      );
      return;
    }
    setSaving(true);
    try {
      await create(buildCronJobSpec(form));
      toast.success(`Created ${form.name}`);
      navigate({ to: "/cronjobs/$name", params: { name: form.name } });
    } catch (e) {
      notifyApiError(e);
      setSaving(false);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="New cronjob"
        subtitle="Submit a CronJobSpec to the control plane."
        actions={
          <Button variant="outline" size="sm" asChild>
            <Link to="/cronjobs">Cancel</Link>
          </Button>
        }
      />
      <form
        onSubmit={submit}
        className="max-w-xl grid gap-4 rounded border border-border bg-card p-4"
      >
        <div className="grid gap-1.5">
          <Label htmlFor="name" className="text-xs">
            Name *
          </Label>
          <Input
            id="name"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            className="font-mono text-sm h-9"
            placeholder="nightly-report-01"
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="sched" className="text-xs">
            Schedule *
          </Label>
          <Input
            id="sched"
            value={form.schedule}
            onChange={(e) => setForm({ ...form, schedule: e.target.value })}
            className="font-mono text-sm h-9"
            placeholder="0 2 * * *"
          />
          <p className="text-xs text-muted-foreground">
            Five whitespace-separated cron fields: minute hour day-of-month month day-of-week.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="mn" className="text-xs">
              Module name *
            </Label>
            <Input
              id="mn"
              value={form.moduleName}
              onChange={(e) => setForm({ ...form, moduleName: e.target.value })}
              className="font-mono text-sm h-9"
              placeholder="report-job"
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="mv" className="text-xs">
              Module version *
            </Label>
            <Input
              id="mv"
              value={form.moduleVersion}
              onChange={(e) => setForm({ ...form, moduleVersion: e.target.value })}
              className="font-mono text-sm h-9"
              placeholder="1.0.0"
            />
          </div>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="ap" className="text-xs">
            Artifact path
          </Label>
          <Input
            id="ap"
            value={form.artifactPath}
            onChange={(e) => setForm({ ...form, artifactPath: e.target.value })}
            className="font-mono text-sm h-9"
            placeholder="s3://bucket/path/to.jar — leave blank to pull from Andvari"
          />
          <p className="text-xs text-muted-foreground">
            Leave blank to resolve <span className="font-mono">{form.moduleName || "module"}</span>
            {"@"}
            <span className="font-mono">{form.moduleVersion || "version"}</span> from the Andvari
            artifact registry instead of naming a jar directly.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="bl" className="text-xs">
              Backoff limit
            </Label>
            <Input
              id="bl"
              type="number"
              min={0}
              value={form.backoffLimit}
              onChange={(e) => setForm({ ...form, backoffLimit: e.target.value })}
              className="font-mono text-sm h-9"
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="ads" className="text-xs">
              Job active deadline (s)
            </Label>
            <Input
              id="ads"
              value={form.activeDeadlineSeconds}
              onChange={(e) => setForm({ ...form, activeDeadlineSeconds: e.target.value })}
              className="font-mono text-sm h-9"
              placeholder="optional"
            />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="sds" className="text-xs">
              Starting deadline (s)
            </Label>
            <Input
              id="sds"
              value={form.startingDeadlineSeconds}
              onChange={(e) => setForm({ ...form, startingDeadlineSeconds: e.target.value })}
              className="font-mono text-sm h-9"
              placeholder="optional"
            />
          </div>
          <div className="grid gap-1.5">
            <Label className="text-xs">Concurrency policy</Label>
            <Select
              value={form.concurrencyPolicy}
              onValueChange={(v) => setForm({ ...form, concurrencyPolicy: v as ConcurrencyPolicy })}
            >
              <SelectTrigger className="h-9 text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALLOW">ALLOW</SelectItem>
                <SelectItem value="FORBID">FORBID</SelectItem>
                <SelectItem value="REPLACE">REPLACE</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
        <div className="grid gap-1.5">
          <Label className="text-xs">Tenant</Label>
          <Select value={form.tenantId} onValueChange={(v) => setForm({ ...form, tenantId: v })}>
            <SelectTrigger className="h-9 text-sm">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="NONE">— none —</SelectItem>
              {tenants.map((t) => (
                <SelectItem key={t.id} value={t.id} className="font-mono">
                  {t.id}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="submit" size="sm" disabled={saving}>
            {saving ? "Creating…" : "Create cronjob"}
          </Button>
        </div>
      </form>
    </PageContainer>
  );
}
