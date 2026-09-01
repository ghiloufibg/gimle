import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useTenantsStore } from "@/stores/useTenantsStore";
import { PageContainer, PageHeader, Panel } from "@/components/page-shell";
import { Bullet } from "@/components/usage-bullet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { fmtBytes, fmtMillicores } from "@/lib/format";
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
import { toast } from "sonner";
import { notifyApiError } from "@/lib/api-error";
import { Trash2 } from "lucide-react";

export const Route = createFileRoute("/tenants/$id")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.id} — Tenant — Gimlé Console` },
      { name: "description", content: `Tenant detail for ${params.id}.` },
      { property: "og:title", content: `${params.id} — Gimlé Console` },
      { property: "og:description", content: `Tenant detail for ${params.id}.` },
    ],
  }),
  component: TenantDetail,
});

function TenantDetail() {
  const { id } = Route.useParams();
  const navigate = useNavigate();
  const items = useTenantsStore((s) => s.items);
  const getOrFetch = useTenantsStore((s) => s.getOrFetch);
  const updateQuota = useTenantsStore((s) => s.updateQuota);
  const remove = useTenantsStore((s) => s.remove);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    getOrFetch(id).catch(() => setNotFound(true));
  }, [id, getOrFetch]);

  const t = items.find((x) => x.id === id);
  const [form, setForm] = useState({ mem: "", cpu: "", inst: "" });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (t) {
      setForm({
        mem: String(t.quota.maxMemoryBytes),
        cpu: String(t.quota.maxCpuMillicores),
        inst: String(t.quota.maxInstances),
      });
    }
  }, [t]);

  if (notFound) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Tenant not found.</p>
      </PageContainer>
    );
  }
  if (!t) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Loading tenant…</p>
      </PageContainer>
    );
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    try {
      await updateQuota(id, {
        maxMemoryBytes: parseInt(form.mem, 10) || 0,
        maxCpuMillicores: parseInt(form.cpu, 10) || 0,
        maxInstances: parseInt(form.inst, 10) || 0,
      });
      toast.success("Quota updated");
    } catch (e) {
      notifyApiError(e);
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    try {
      await remove(id);
      toast.success(`Deleted tenant ${id}`);
      navigate({ to: "/tenants" });
    } catch (e) {
      notifyApiError(e);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title={<span className="font-mono">{t.id}</span>}
        subtitle="Tenant quota configuration"
        actions={
          <>
            <Button variant="outline" size="sm" asChild>
              <Link to="/tenants">Back</Link>
            </Button>
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button variant="destructive" size="sm">
                  <Trash2 className="h-4 w-4" />
                  Delete
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete tenant {t.id}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    Tenant configuration and quota will be removed.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Cancel</AlertDialogCancel>
                  <AlertDialogAction
                    onClick={handleDelete}
                    className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                  >
                    Delete
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          </>
        }
      />
      <Panel
        title="Quota usage"
        className="mb-6 max-w-lg"
        aside={
          t.quotaViolating ? (
            <span className="hud-label text-status-bad">violating</span>
          ) : (
            <span className="hud-label text-muted-foreground">within quota</span>
          )
        }
      >
        <Bullet
          label="memory"
          used={t.usage.memoryBytes}
          max={t.quota.maxMemoryBytes}
          display={`${fmtBytes(t.usage.memoryBytes)} / ${fmtBytes(t.quota.maxMemoryBytes)}`}
        />
        <Bullet
          label="cpu"
          used={t.usage.cpuMillicores}
          max={t.quota.maxCpuMillicores}
          display={`${fmtMillicores(t.usage.cpuMillicores)} / ${fmtMillicores(t.quota.maxCpuMillicores)}`}
        />
        <Bullet
          label="instances"
          used={t.usage.instances}
          max={t.quota.maxInstances}
          display={`${t.usage.instances} / ${t.quota.maxInstances}`}
        />
      </Panel>
      <form
        onSubmit={save}
        className="max-w-lg grid gap-4 rounded border border-border bg-card p-4"
      >
        <div className="grid gap-1.5">
          <Label className="text-xs">Max memory (bytes)</Label>
          <Input
            className="h-9 font-mono text-sm"
            value={form.mem}
            onChange={(e) => setForm({ ...form, mem: e.target.value })}
          />
        </div>
        <div className="grid gap-1.5">
          <Label className="text-xs">Max CPU (millicores)</Label>
          <Input
            className="h-9 font-mono text-sm"
            value={form.cpu}
            onChange={(e) => setForm({ ...form, cpu: e.target.value })}
          />
        </div>
        <div className="grid gap-1.5">
          <Label className="text-xs">Max instances</Label>
          <Input
            className="h-9 font-mono text-sm"
            value={form.inst}
            onChange={(e) => setForm({ ...form, inst: e.target.value })}
          />
        </div>
        <div className="flex justify-end">
          <Button type="submit" size="sm" disabled={saving}>
            {saving ? "Saving…" : "Save quota"}
          </Button>
        </div>
      </form>
    </PageContainer>
  );
}
