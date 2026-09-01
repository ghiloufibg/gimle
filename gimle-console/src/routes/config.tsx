import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useConfigStore } from "@/stores/useConfigStore";
import { useTenantsStore } from "@/stores/useTenantsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { StatusBadge } from "@/components/status";
import { Eye, EyeOff, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { notifyApiError } from "@/lib/api-error";

export const Route = createFileRoute("/config")({
  head: () => ({
    meta: [
      { title: "Config — Gimlé Console" },
      { name: "description", content: "Tenant configuration entries." },
      { property: "og:title", content: "Config — Gimlé Console" },
      { property: "og:description", content: "Tenant configuration entries." },
    ],
  }),
  component: ConfigPage,
});

function ConfigPage() {
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);
  const { tenantId, items, loading, setTenant, loadFirstPage, upsert, remove } = useConfigStore();
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  const [newEntry, setNewEntry] = useState({ key: "", value: "", encrypted: false });

  useEffect(() => {
    if (tenants.length === 0) loadTenants();
  }, [tenants.length, loadTenants]);

  useEffect(() => {
    if (tenantId) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId]);

  useEffect(() => {
    if (!tenantId && tenants.length > 0) setTenant(tenants[0].id);
  }, [tenantId, tenants, setTenant]);

  function toggleReveal(key: string) {
    const next = new Set(revealed);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    setRevealed(next);
  }

  async function addEntry(e: React.FormEvent) {
    e.preventDefault();
    if (!tenantId || !newEntry.key) return;
    try {
      await upsert({
        tenantId,
        key: newEntry.key,
        value: newEntry.value,
        encrypted: newEntry.encrypted,
      });
      setNewEntry({ key: "", value: "", encrypted: false });
      toast.success("Saved");
    } catch (e) {
      notifyApiError(e);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="Config"
        subtitle="Per-tenant configuration entries. Encrypted values are masked."
        actions={
          <Select value={tenantId ?? ""} onValueChange={(v) => setTenant(v)}>
            <SelectTrigger className="h-8 w-52 text-xs">
              <SelectValue placeholder="Pick tenant" />
            </SelectTrigger>
            <SelectContent>
              {tenants.map((t) => (
                <SelectItem key={t.id} value={t.id} className="font-mono">
                  {t.id}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        }
      />

      {tenantId && (
        <>
          <form
            onSubmit={addEntry}
            className="mb-4 flex flex-wrap items-end gap-2 rounded border border-border bg-card p-3"
          >
            <div className="grid gap-1">
              <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
                Key
              </Label>
              <Input
                className="h-8 w-48 font-mono text-xs"
                value={newEntry.key}
                onChange={(e) => setNewEntry({ ...newEntry, key: e.target.value })}
                placeholder="db.password"
              />
            </div>
            <div className="grid gap-1 flex-1 min-w-[200px]">
              <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
                Value
              </Label>
              <Input
                className="h-8 font-mono text-xs"
                value={newEntry.value}
                onChange={(e) => setNewEntry({ ...newEntry, value: e.target.value })}
              />
            </div>
            <div className="flex items-center gap-2 pb-1">
              <Switch
                id="enc"
                checked={newEntry.encrypted}
                onCheckedChange={(v) => setNewEntry({ ...newEntry, encrypted: v })}
              />
              <Label htmlFor="enc" className="text-xs">
                Encrypted
              </Label>
            </div>
            <Button type="submit" size="sm">
              Add / Update
            </Button>
          </form>

          <div className="overflow-x-auto rounded border border-border bg-card">
            <table className="w-full text-xs">
              <thead className="bg-muted/50 text-muted-foreground">
                <tr className="text-left">
                  <th className="px-2 py-1.5 font-medium">Key</th>
                  <th className="px-2 py-1.5 font-medium">Value</th>
                  <th className="px-2 py-1.5 font-medium">Type</th>
                  <th className="px-2 py-1.5 font-medium w-16"></th>
                </tr>
              </thead>
              <tbody>
                {items.map((e) => {
                  const shown = !e.encrypted || revealed.has(e.key);
                  return (
                    <tr key={e.key} className="border-t border-border hover:bg-muted/30">
                      <td className="px-2 py-1.5 font-mono">{e.key}</td>
                      <td className="px-2 py-1.5 font-mono">
                        {e.encrypted && !shown ? (
                          <span className="text-muted-foreground">•••••••••••••</span>
                        ) : (
                          <span className="break-all">{e.value}</span>
                        )}
                        {e.encrypted && (
                          <button
                            onClick={() => toggleReveal(e.key)}
                            className="ml-2 inline-flex items-center text-muted-foreground hover:text-foreground"
                            aria-label={shown ? "Hide value" : "Reveal value"}
                          >
                            {shown ? <EyeOff className="h-3 w-3" /> : <Eye className="h-3 w-3" />}
                          </button>
                        )}
                      </td>
                      <td className="px-2 py-1.5">
                        {e.encrypted ? (
                          <StatusBadge variant="warn">encrypted</StatusBadge>
                        ) : (
                          <StatusBadge variant="muted">plain</StatusBadge>
                        )}
                      </td>
                      <td className="px-2 py-1.5">
                        <button
                          onClick={() => remove(e.key)}
                          className="text-muted-foreground hover:text-status-bad"
                          aria-label="Delete entry"
                        >
                          <Trash2 className="h-3 w-3" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
                {items.length === 0 && !loading && (
                  <tr>
                    <td colSpan={4} className="px-4 py-6 text-center text-muted-foreground">
                      No entries.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
    </PageContainer>
  );
}
