import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useJobsStore } from "@/stores/useJobsStore";
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
import type { JobSpecInput } from "@/types";

export const Route = createFileRoute("/jobs/new")({
  head: () => ({
    meta: [
      { title: "New job — Gimlé Console" },
      { name: "description", content: "Create a new run-to-completion job." },
      { property: "og:title", content: "New job — Gimlé Console" },
      { property: "og:description", content: "Create a new run-to-completion job." },
    ],
  }),
  component: NewJob,
});

export interface JobFormState {
  name: string;
  moduleName: string;
  moduleVersion: string;
  artifactPath: string;
  backoffLimit: string;
  activeDeadlineSeconds: string;
  tenantId: string;
}

export const DEFAULT_JOB_FORM: JobFormState = {
  name: "",
  moduleName: "",
  moduleVersion: "",
  artifactPath: "",
  backoffLimit: "6",
  activeDeadlineSeconds: "",
  tenantId: "NONE",
};

/** Required-field check ahead of {@link buildJobSpec}; mirrors deployments.new.tsx's own guard. */
export function jobFormIsValid(form: JobFormState): boolean {
  return (
    form.name.trim() !== "" && form.moduleName.trim() !== "" && form.moduleVersion.trim() !== ""
  );
}

export function buildJobSpec(form: JobFormState): JobSpecInput {
  const spec: JobSpecInput = {
    name: form.name,
    moduleId: { name: form.moduleName, version: form.moduleVersion },
    artifactPath: form.artifactPath,
    backoffLimit: Math.max(0, parseInt(form.backoffLimit, 10) || 0),
    tenantId: form.tenantId === "NONE" ? null : form.tenantId,
  };
  const deadline = form.activeDeadlineSeconds.trim();
  if (deadline !== "") spec.activeDeadlineSeconds = Number(deadline);
  return spec;
}

function NewJob() {
  const navigate = useNavigate();
  const create = useJobsStore((s) => s.create);
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<JobFormState>(DEFAULT_JOB_FORM);

  useEffect(() => {
    if (tenants.length === 0) loadTenants();
  }, [tenants.length, loadTenants]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!jobFormIsValid(form)) {
      toast.error("Fill required fields");
      return;
    }
    setSaving(true);
    try {
      await create(buildJobSpec(form));
      toast.success(`Created ${form.name}`);
      navigate({ to: "/jobs/$name", params: { name: form.name } });
    } catch (e) {
      toast.error((e as Error).message);
      setSaving(false);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="New job"
        subtitle="Submit a JobSpec to the control plane."
        actions={
          <Button variant="outline" size="sm" asChild>
            <Link to="/jobs">Cancel</Link>
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
            placeholder="nightly-cleanup-01"
          />
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
              placeholder="cleanup-job"
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
              Active deadline (s)
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
            {saving ? "Creating…" : "Create job"}
          </Button>
        </div>
      </form>
    </PageContainer>
  );
}
