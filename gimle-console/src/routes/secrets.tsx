import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useSecretsStore } from "@/stores/useSecretsStore";
import { useTenantsStore } from "@/stores/useTenantsStore";
import { scopedTenantId, tenantScopeSearch } from "@/lib/tenant-scope";
import { PageContainer, PageHeader, Panel } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { StatusBadge } from "@/components/status";
import { checkRetireTarget, retirementConfirmed } from "./-secrets";
import { Eye, EyeOff, RefreshCw, ShieldAlert, Skull, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { SECRET_TYPES, type SecretType, type SecretVersion } from "@/types";

/** The version picker's own tooltip: everything the row can't fit, on hover. */
function describeVersion(version: SecretVersion): string {
  return `v${version.version} · ${version.type} · written by ${version.author} at ${new Date(
    version.writtenAtEpochMilli,
  ).toLocaleString()}`;
}

/**
 * When the version list has been loaded for a row, the current version's own write timestamp and
 * author; before then, a placeholder -- the list response carries metadata only, and fetching every
 * row's version history up front would be one request per row for information most rows never show.
 */
function lastWritten(versions: SecretVersion[] | undefined, latestVersion: number): string {
  const current = versions?.find((v) => v.version === latestVersion);
  if (!current) return "—";
  return `${new Date(current.writtenAtEpochMilli).toLocaleString()} · ${current.author}`;
}

export const Route = createFileRoute("/secrets")({
  validateSearch: tenantScopeSearch,
  head: () => ({
    meta: [
      { title: "Secrets — Gimlé Console" },
      { name: "description", content: "Versioned per-tenant secrets, served by Fafnir." },
      { property: "og:title", content: "Secrets — Gimlé Console" },
      { property: "og:description", content: "Versioned per-tenant secrets, served by Fafnir." },
    ],
  }),
  component: SecretsPage,
});

function SecretsPage() {
  const { tenant } = Route.useSearch();
  const navigate = useNavigate();
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);
  const {
    tenantId,
    items,
    loading,
    revealed,
    versions,
    setTenant,
    loadFirstPage,
    reveal,
    hide,
    loadVersions,
    upsert,
    remove,
    rotateKey,
    retireKey,
    activeKeyId,
  } = useSecretsStore();
  const [newEntry, setNewEntry] = useState<{ key: string; value: string; type: SecretType }>({
    key: "",
    value: "",
    type: "opaque",
  });
  const [pickedVersion, setPickedVersion] = useState<Record<string, number>>({});
  const [retireInput, setRetireInput] = useState("");
  const [confirmInput, setConfirmInput] = useState("");
  const [confirmOpen, setConfirmOpen] = useState(false);

  const retireTarget = checkRetireTarget(retireInput, activeKeyId);
  const retireConfirmed =
    retireTarget.keyId !== null && retirementConfirmed(confirmInput, retireTarget.keyId);

  useEffect(() => {
    if (tenants.length === 0) loadTenants();
  }, [tenants.length, loadTenants]);

  useEffect(() => {
    if (tenantId) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenantId]);

  // The URL owns which tenant is on screen, so a link to one tenant's view opens on that tenant
  // for whoever follows it rather than on whichever one they last picked here themselves.
  const scopedTenant = scopedTenantId(
    tenant,
    tenants.map((t) => t.id),
  );
  useEffect(() => {
    if (scopedTenant !== null && scopedTenant !== tenantId) setTenant(scopedTenant);
  }, [scopedTenant, tenantId, setTenant]);

  async function toggleReveal(key: string) {
    if (revealed[key]) {
      hide(key);
      return;
    }
    try {
      await reveal(key, pickedVersion[key]);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function openVersionPicker(key: string) {
    if (!versions[key]) {
      try {
        await loadVersions(key);
      } catch (e) {
        toast.error((e as Error).message);
      }
    }
  }

  async function pickVersion(key: string, version: number) {
    setPickedVersion({ ...pickedVersion, [key]: version });
    if (revealed[key]) {
      try {
        await reveal(key, version);
      } catch (e) {
        toast.error((e as Error).message);
      }
    }
  }

  async function addEntry(e: React.FormEvent) {
    e.preventDefault();
    if (!tenantId || !newEntry.key) return;
    try {
      await upsert(newEntry.key, newEntry.value, newEntry.type);
      setNewEntry({ key: "", value: "", type: "opaque" });
      toast.success("Saved");
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function deleteEntry(key: string, destroy: boolean) {
    if (
      destroy &&
      !window.confirm(`Permanently destroy every version of "${key}"? This cannot be undone.`)
    ) {
      return;
    }
    try {
      await remove(key, destroy);
      toast.success(destroy ? "Destroyed" : "Deleted");
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  async function handleRotateKey() {
    try {
      const rotatedTo = await rotateKey();
      toast.success(`Master key rotated (active key id ${rotatedTo})`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  function openRetireConfirm() {
    setConfirmInput("");
    setConfirmOpen(true);
  }

  async function handleRetireKey() {
    if (retireTarget.keyId === null) return;
    try {
      const retired = await retireKey(retireTarget.keyId);
      setRetireInput("");
      toast.success(`Master key ${retired} retired — anything still under it is unrecoverable`);
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  return (
    <PageContainer>
      <PageHeader
        title="Secrets"
        subtitle="Versioned, per-tenant secrets served by Fafnir. Values are masked until revealed."
        actions={
          <div className="flex items-center gap-2">
            <Select
              value={tenantId ?? ""}
              onValueChange={(v) => void navigate({ to: "/secrets", search: { tenant: v } })}
            >
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
            <Button size="sm" variant="outline" onClick={handleRotateKey}>
              <RefreshCw className="mr-1.5 h-3 w-3" />
              Rotate master key
            </Button>
          </div>
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
                type="password"
                className="h-8 font-mono text-xs"
                value={newEntry.value}
                onChange={(e) => setNewEntry({ ...newEntry, value: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">
                Type
              </Label>
              <Select
                value={newEntry.type}
                onValueChange={(v) => setNewEntry({ ...newEntry, type: v as SecretType })}
              >
                <SelectTrigger className="h-8 w-40 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {SECRET_TYPES.map((t) => (
                    <SelectItem key={t} value={t} className="font-mono text-xs">
                      {t}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <Button type="submit" size="sm">
              Add / New version
            </Button>
          </form>

          <div className="overflow-x-auto rounded border border-border bg-card">
            <table className="w-full text-xs">
              <thead className="bg-muted/50 text-muted-foreground">
                <tr className="text-left">
                  <th className="px-2 py-1.5 font-medium">Key</th>
                  <th className="px-2 py-1.5 font-medium">Value</th>
                  <th className="px-2 py-1.5 font-medium">Version</th>
                  <th className="px-2 py-1.5 font-medium">Last written</th>
                  <th className="px-2 py-1.5 font-medium">Status</th>
                  <th className="px-2 py-1.5 font-medium w-20"></th>
                </tr>
              </thead>
              <tbody>
                {items.map((s) => {
                  const shownValue = revealed[s.key];
                  const versionList = versions[s.key];
                  return (
                    <tr key={s.key} className="border-t border-border hover:bg-muted/30">
                      <td className="px-2 py-1.5 font-mono">{s.key}</td>
                      <td className="px-2 py-1.5 font-mono">
                        {shownValue ? (
                          <span className="break-all">{shownValue.value}</span>
                        ) : (
                          <span className="text-muted-foreground">•••••••••••••</span>
                        )}
                        <button
                          onClick={() => toggleReveal(s.key)}
                          className="ml-2 inline-flex items-center text-muted-foreground hover:text-foreground"
                          aria-label={shownValue ? "Hide value" : "Reveal value"}
                        >
                          {shownValue ? (
                            <EyeOff className="h-3 w-3" />
                          ) : (
                            <Eye className="h-3 w-3" />
                          )}
                        </button>
                      </td>
                      <td className="px-2 py-1.5">
                        <Select
                          value={String(pickedVersion[s.key] ?? s.latestVersion)}
                          onOpenChange={(open) => open && openVersionPicker(s.key)}
                          onValueChange={(v) => pickVersion(s.key, Number(v))}
                        >
                          <SelectTrigger className="h-6 w-20 text-[11px]">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {versionList
                              ? versionList.map((v) => (
                                  <SelectItem
                                    key={v.version}
                                    value={String(v.version)}
                                    className="text-[11px]"
                                    title={describeVersion(v)}
                                  >
                                    v{v.version} · {v.author}
                                  </SelectItem>
                                ))
                              : [
                                  <SelectItem
                                    key={s.latestVersion}
                                    value={String(s.latestVersion)}
                                    className="text-[11px]"
                                  >
                                    v{s.latestVersion}
                                  </SelectItem>,
                                ]}
                          </SelectContent>
                        </Select>
                      </td>
                      <td className="px-2 py-1.5 whitespace-nowrap text-muted-foreground">
                        {lastWritten(versionList, s.latestVersion)}
                      </td>
                      <td className="px-2 py-1.5">
                        {s.deleted ? (
                          <StatusBadge variant="warn">deleted</StatusBadge>
                        ) : (
                          <StatusBadge variant="muted">active</StatusBadge>
                        )}
                      </td>
                      <td className="px-2 py-1.5">
                        <div className="flex items-center gap-2">
                          <button
                            onClick={() => deleteEntry(s.key, false)}
                            className="text-muted-foreground hover:text-status-bad"
                            aria-label="Delete (soft)"
                            title="Soft delete"
                          >
                            <Trash2 className="h-3 w-3" />
                          </button>
                          <button
                            onClick={() => deleteEntry(s.key, true)}
                            className="text-muted-foreground hover:text-status-bad"
                            aria-label="Destroy every version"
                            title="Destroy (hard delete)"
                          >
                            <Skull className="h-3 w-3" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
                {items.length === 0 && !loading && (
                  <tr>
                    <td colSpan={6} className="px-4 py-6 text-center text-muted-foreground">
                      No secrets.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      <Panel title="Retire a master key" className="mt-6 border-status-bad/40">
        <div className="p-4">
          <div className="mb-3 flex gap-2 rounded border border-status-bad/40 bg-status-bad-bg/40 p-3 text-xs text-status-bad">
            <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <p>
              Retiring deletes that key from Fafnir's master ring for good. Every secret version in
              every tenant still encrypted under it becomes permanently unreadable — by anyone,
              including Fafnir. There is no undo and no recovery. Rotation alone is safe: it mints a
              new active key and leaves earlier ones on the ring, so older versions still decrypt.
            </p>
          </div>

          <div className="flex flex-wrap items-end gap-2">
            <div className="grid gap-1">
              <Label
                htmlFor="retire-secrets-key-id"
                className="text-[10px] uppercase tracking-wider text-muted-foreground"
              >
                Key id to retire
              </Label>
              <Input
                id="retire-secrets-key-id"
                className="h-8 w-32 font-mono text-xs"
                value={retireInput}
                onChange={(e) => setRetireInput(e.target.value)}
                placeholder="e.g. 2"
                inputMode="numeric"
              />
            </div>
            <Button
              size="sm"
              variant="destructive"
              onClick={openRetireConfirm}
              disabled={retireTarget.keyId === null}
            >
              Retire key…
            </Button>
            {retireInput.trim() !== "" && retireTarget.error && (
              <p className="text-xs text-status-bad">{retireTarget.error}</p>
            )}
          </div>

          <p className="mt-3 text-xs text-muted-foreground">
            Fafnir publishes no listing of the ids still on the master ring, so the id to retire
            comes from your own record of past rotations
            {activeKeyId !== null && ` (this session rotated to key ${activeKeyId})`}.
          </p>
        </div>
      </Panel>

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Retire master key {retireTarget.keyId}?</AlertDialogTitle>
            <AlertDialogDescription>
              This permanently destroys master key {retireTarget.keyId}. Any secret version still
              encrypted under it can never be decrypted again, by anyone, in any tenant. This cannot
              be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="grid gap-1">
            <Label
              htmlFor="retire-secrets-confirm"
              className="text-[10px] uppercase tracking-wider text-muted-foreground"
            >
              Type {retireTarget.keyId} to confirm
            </Label>
            <Input
              id="retire-secrets-confirm"
              className="h-8 w-32 font-mono text-xs"
              value={confirmInput}
              onChange={(e) => setConfirmInput(e.target.value)}
              inputMode="numeric"
              autoComplete="off"
            />
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleRetireKey}
              disabled={!retireConfirmed}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              Retire permanently
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </PageContainer>
  );
}
