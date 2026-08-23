import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useSecretMapsStore } from "@/stores/useSecretMapsStore";
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
import { StatusBadge } from "@/components/status";
import { Trash2 } from "lucide-react";
import { toast } from "sonner";

export const Route = createFileRoute("/secretmaps")({
  head: () => ({
    meta: [
      { title: "SecretMaps — Gimlé Console" },
      {
        name: "description",
        content: "Named, multi-key secret bundles a deployment attaches by reference.",
      },
      { property: "og:title", content: "SecretMaps — Gimlé Console" },
      {
        property: "og:description",
        content: "Named, multi-key secret bundles a deployment attaches by reference.",
      },
    ],
  }),
  component: SecretMapsPage,
});

function SecretMapsPage() {
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);
  const {
    tenantId,
    names,
    loading,
    error,
    selected,
    lastSetResults,
    setTenant,
    loadNames,
    select,
    save,
    remove,
  } = useSecretMapsStore();
  const [nameInput, setNameInput] = useState("");
  const [rows, setRows] = useState<{ key: string; value: string }[]>([{ key: "", value: "" }]);

  useEffect(() => {
    if (tenants.length === 0) loadTenants();
  }, [tenants.length, loadTenants]);

  useEffect(() => {
    if (tenantId) loadNames();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId]);

  useEffect(() => {
    if (!tenantId && tenants.length > 0) setTenant(tenants[0].id);
  }, [tenantId, tenants, setTenant]);

  useEffect(() => {
    if (selected) {
      setNameInput(selected.name);
      setRows([{ key: "", value: "" }]);
    }
  }, [selected]);

  function openNew() {
    select(null);
    setNameInput("");
    setRows([{ key: "", value: "" }]);
  }

  function updateRow(i: number, field: "key" | "value", value: string) {
    setRows(rows.map((r, ix) => (ix === i ? { ...r, [field]: value } : r)));
  }

  function addRow() {
    setRows([...rows, { key: "", value: "" }]);
  }

  function removeRow(i: number) {
    setRows(rows.filter((_, ix) => ix !== i));
  }

  async function onSave() {
    if (!nameInput.trim()) {
      toast.error("SecretMap name is required");
      return;
    }
    const data = Object.fromEntries(
      rows.filter((r) => r.key.trim().length > 0).map((r) => [r.key, r.value]),
    );
    if (Object.keys(data).length === 0) {
      toast.error("At least one key is required");
      return;
    }
    await save(nameInput, data);
    const results = useSecretMapsStore.getState().lastSetResults;
    const failed = results?.filter((r) => r.error) ?? [];
    if (!useSecretMapsStore.getState().error) {
      if (failed.length === 0) {
        toast.success("Saved");
      } else {
        toast.error(`${failed.length} key(s) failed: ${failed.map((f) => f.key).join(", ")}`);
      }
    }
  }

  async function onDelete(name: string) {
    if (!window.confirm(`Delete SecretMap "${name}" (every one of its keys)?`)) return;
    try {
      await remove(name);
      toast.success("Deleted");
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="SecretMaps"
        subtitle="Named, multi-key secret bundles a deployment attaches by secretMapRefs instead of receiving every secret the tenant owns."
        actions={
          <div className="flex items-center gap-2">
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
            <Button size="sm" variant="outline" onClick={openNew}>
              New SecretMap
            </Button>
          </div>
        }
      />

      {tenantId && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <div className="overflow-x-auto rounded border border-border bg-card lg:col-span-1">
            <table className="w-full text-xs">
              <thead className="bg-muted/50 text-muted-foreground">
                <tr className="text-left">
                  <th className="px-2 py-1.5 font-medium">Name</th>
                  <th className="px-2 py-1.5 font-medium w-10"></th>
                </tr>
              </thead>
              <tbody>
                {names.map((name) => (
                  <tr
                    key={name}
                    className={`cursor-pointer border-t border-border hover:bg-muted/30 ${
                      selected?.name === name ? "bg-muted/40" : ""
                    }`}
                    onClick={() => select(name)}
                  >
                    <td className="px-2 py-1.5 font-mono">{name}</td>
                    <td className="px-2 py-1.5">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          onDelete(name);
                        }}
                        className="text-muted-foreground hover:text-status-bad"
                        aria-label="Delete secretmap"
                      >
                        <Trash2 className="h-3 w-3" />
                      </button>
                    </td>
                  </tr>
                ))}
                {names.length === 0 && !loading && (
                  <tr>
                    <td colSpan={2} className="px-4 py-6 text-center text-muted-foreground">
                      No SecretMaps.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="rounded border border-border bg-card p-3 lg:col-span-2">
            {error && <div className="mb-3 text-xs text-status-bad">{error}</div>}
            <div className="mb-3 grid gap-1">
              <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
                Name
              </Label>
              <Input
                className="h-8 w-64 font-mono text-xs"
                value={nameInput}
                disabled={selected !== null}
                onChange={(e) => setNameInput(e.target.value)}
                placeholder="db-creds"
              />
            </div>

            {selected && selected.keys.length > 0 && (
              <div className="mb-3 overflow-x-auto rounded border border-border">
                <table className="w-full text-[11px]">
                  <thead className="bg-muted/50 text-muted-foreground">
                    <tr className="text-left">
                      <th className="px-2 py-1 font-medium">Key</th>
                      <th className="px-2 py-1 font-medium">Version</th>
                      <th className="px-2 py-1 font-medium">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selected.keys.map((k) => (
                      <tr key={k.key} className="border-t border-border">
                        <td className="px-2 py-1 font-mono">{k.key}</td>
                        <td className="px-2 py-1">v{k.latestVersion}</td>
                        <td className="px-2 py-1">
                          {k.deleted ? (
                            <StatusBadge variant="warn">deleted</StatusBadge>
                          ) : (
                            <StatusBadge variant="muted">active</StatusBadge>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <div className="mb-1 text-[10px] uppercase tracking-wider text-muted-foreground">
              Add / update keys
            </div>
            <div className="mb-3 space-y-2">
              {rows.map((row, i) => (
                <div key={i} className="flex items-center gap-2">
                  <Input
                    className="h-8 w-40 font-mono text-xs"
                    value={row.key}
                    onChange={(e) => updateRow(i, "key", e.target.value)}
                    placeholder="key"
                  />
                  <Input
                    type="password"
                    className="h-8 flex-1 font-mono text-xs"
                    value={row.value}
                    onChange={(e) => updateRow(i, "value", e.target.value)}
                    placeholder="value"
                  />
                  <button
                    onClick={() => removeRow(i)}
                    className="text-muted-foreground hover:text-status-bad"
                    aria-label="Remove key"
                  >
                    <Trash2 className="h-3 w-3" />
                  </button>
                </div>
              ))}
              <Button size="sm" variant="outline" onClick={addRow}>
                Add key
              </Button>
            </div>
            <Button size="sm" onClick={onSave}>
              Save
            </Button>
            {lastSetResults && lastSetResults.some((r) => r.error) && (
              <div className="mt-2 text-[11px] text-status-bad">
                {lastSetResults
                  .filter((r) => r.error)
                  .map((r) => `${r.key}: ${r.error}`)
                  .join("; ")}
              </div>
            )}
          </div>
        </div>
      )}
    </PageContainer>
  );
}
