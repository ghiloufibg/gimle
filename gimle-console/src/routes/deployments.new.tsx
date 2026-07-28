import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useDeploymentsStore } from "@/stores/useDeploymentsStore";
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

export const Route = createFileRoute("/deployments/new")({
  head: () => ({
    meta: [
      { title: "New deployment — Gimlé Console" },
      { name: "description", content: "Create a new module deployment." },
      { property: "og:title", content: "New deployment — Gimlé Console" },
      { property: "og:description", content: "Create a new module deployment." },
    ],
  }),
  component: NewDeployment,
});

function NewDeployment() {
  const navigate = useNavigate();
  const create = useDeploymentsStore((s) => s.create);
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    name: "",
    moduleName: "",
    moduleVersion: "",
    artifactPath: "",
    replicas: "1",
    tenantId: "NONE",
  });

  useEffect(() => {
    if (tenants.length === 0) loadTenants();
  }, [tenants.length, loadTenants]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.name || !form.moduleName || !form.moduleVersion || !form.artifactPath) {
      toast.error("Fill required fields");
      return;
    }
    setSaving(true);
    try {
      await create({
        name: form.name,
        moduleId: { name: form.moduleName, version: form.moduleVersion },
        artifactPath: form.artifactPath,
        replicas: Math.max(1, parseInt(form.replicas, 10) || 1),
        tenantId: form.tenantId === "NONE" ? null : form.tenantId,
      });
      toast.success(`Created ${form.name}`);
      navigate({ to: "/deployments/$name", params: { name: form.name } });
    } catch (e) {
      toast.error((e as Error).message);
      setSaving(false);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="New deployment"
        subtitle="Submit a DeploymentSpec to the control plane."
        actions={
          <Button variant="outline" size="sm" asChild>
            <Link to="/deployments">Cancel</Link>
          </Button>
        }
      />
      <form onSubmit={submit} className="max-w-xl grid gap-4 rounded border border-border bg-card p-4">
        <div className="grid gap-1.5">
          <Label htmlFor="name" className="text-xs">Name *</Label>
          <Input
            id="name"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            className="font-mono text-sm h-9"
            placeholder="checkout-service-1-2-3-01"
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="mn" className="text-xs">Module name *</Label>
            <Input
              id="mn"
              value={form.moduleName}
              onChange={(e) => setForm({ ...form, moduleName: e.target.value })}
              className="font-mono text-sm h-9"
              placeholder="checkout-service"
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="mv" className="text-xs">Module version *</Label>
            <Input
              id="mv"
              value={form.moduleVersion}
              onChange={(e) => setForm({ ...form, moduleVersion: e.target.value })}
              className="font-mono text-sm h-9"
              placeholder="1.2.3"
            />
          </div>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="ap" className="text-xs">Artifact path *</Label>
          <Input
            id="ap"
            value={form.artifactPath}
            onChange={(e) => setForm({ ...form, artifactPath: e.target.value })}
            className="font-mono text-sm h-9"
            placeholder="s3://bucket/path/to.jar"
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="rep" className="text-xs">Replicas</Label>
            <Input
              id="rep"
              type="number"
              min={1}
              value={form.replicas}
              onChange={(e) => setForm({ ...form, replicas: e.target.value })}
              className="font-mono text-sm h-9"
            />
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
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="submit" size="sm" disabled={saving}>
            {saving ? "Creating…" : "Create deployment"}
          </Button>
        </div>
      </form>
    </PageContainer>
  );
}
