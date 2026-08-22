import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useConfigMapsStore } from "@/stores/useConfigMapsStore";
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
import { Trash2 } from "lucide-react";
import { toast } from "sonner";

export const Route = createFileRoute("/configmaps")({
  head: () => ({
    meta: [
      { title: "ConfigMaps — Gimlé Console" },
      {
        name: "description",
        content: "Named, multi-key config bundles a deployment attaches by reference.",
      },
      { property: "og:title", content: "ConfigMaps — Gimlé Console" },
      {
        property: "og:description",
        content: "Named, multi-key config bundles a deployment attaches by reference.",
      },
    ],
  }),
  component: ConfigMapsPage,
});

function ConfigMapsPage() {
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);
  const {
    tenantId,
    names,
    loading,
    error,
    selected,
    conflict,
    setTenant,
    loadNames,
    select,
    save,
    remove,
    dismissConflict,
  } = useConfigMapsStore();
  const [nameInput, setNameInput] = useState("");
  const [rows, setRows] = useState<{ key: string; value: string }[]>([]);

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
      setRows(Object.entries(selected.data).map(([key, value]) => ({ key, value })));
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
      toast.error("ConfigMap name is required");
      return;
    }
    const data = Object.fromEntries(
      rows.filter((r) => r.key.trim().length > 0).map((r) => [r.key, r.value]),
    );
    await save(nameInput, data);
    if (!useConfigMapsStore.getState().conflict && !useConfigMapsStore.getState().error) {
      toast.success("Saved");
    }
  }

  async function onDelete(name: string) {
    if (!window.confirm(`Delete ConfigMap "${name}"?`)) return;
    try {
      await remove(name);
      toast.success("Deleted");
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  function reloadAfterConflict() {
    dismissConflict();
    if (selected) select(selected.name);
  }

  return (
    <PageContainer>
      <PageHeader
        title="ConfigMaps"
        subtitle="Named, multi-key config bundles a deployment attaches by configMapRefs instead of receiving every flat config key."
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
              New ConfigMap
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
                        aria-label="Delete configmap"
                      >
                        <Trash2 className="h-3 w-3" />
                      </button>
                    </td>
                  </tr>
                ))}
                {names.length === 0 && !loading && (
                  <tr>
                    <td colSpan={2} className="px-4 py-6 text-center text-muted-foreground">
                      No ConfigMaps.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="rounded border border-border bg-card p-3 lg:col-span-2">
            {conflict && (
              <div className="mb-3 rounded border border-status-warn bg-status-warn/10 p-2 text-xs">
                This changed since you opened it -- now at version {conflict.currentVersion}.{" "}
                <button className="underline" onClick={reloadAfterConflict}>
                  Reload
                </button>
              </div>
            )}
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
                placeholder="app-config"
              />
              {selected && (
                <span className="text-[10px] text-muted-foreground">
                  loaded at version {selected.version}
                </span>
              )}
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
          </div>
        </div>
      )}
    </PageContainer>
  );
}
