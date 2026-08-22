import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { Eye, EyeOff, Pencil, Plus, RefreshCw, Trash2, Flame } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
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
} from "@/components/ui/alert-dialog";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ErrorBanner } from "@/components/vault/ErrorBanner";
import { SecretDialog, type SecretDialogState } from "@/components/vault/SecretDialog";
import { useSecretsStore } from "@/stores/useSecretsStore";
import { useStatusStore } from "@/stores/useStatusStore";

export const Route = createFileRoute("/_shell/secrets")({
  validateSearch: (search: Record<string, unknown>): { tenant?: string | undefined } => ({
    tenant: typeof search["tenant"] === "string" ? (search["tenant"] as string) : undefined,
  }),
  head: () => ({
    meta: [
      { title: "Secrets — Gimlé Fafnir Vault" },
      {
        name: "description",
        content: "Browse, reveal, version, write and destroy secrets held by the Fafnir vault.",
      },
      { property: "og:title", content: "Secrets — Gimlé Fafnir Vault" },
      {
        property: "og:description",
        content: "Browse, reveal, version, write and destroy secrets held by the Fafnir vault.",
      },
    ],
  }),
  component: SecretsPage,
});

type PendingDelete = { key: string; destroy: boolean } | null;

function SecretsPage() {
  const navigate = useNavigate();
  const { tenant } = Route.useSearch();
  const status = useStatusStore((s) => s.status);
  const loadStatus = useStatusStore((s) => s.load);
  const setActiveKeyId = useStatusStore((s) => s.setActiveKeyId);

  const store = useSecretsStore();
  const [tenantInput, setTenantInput] = useState(tenant ?? "");
  const [dialog, setDialog] = useState<SecretDialogState>({ open: false, key: null });
  const [pendingDelete, setPendingDelete] = useState<PendingDelete>(null);
  const [rotateOpen, setRotateOpen] = useState(false);

  useEffect(() => {
    if (!status) void loadStatus();
  }, [status, loadStatus]);

  useEffect(() => {
    const next = tenant ?? status?.tenants[0] ?? "";
    setTenantInput(next);
    void store.loadSecrets(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant, status?.tenants[0]]);

  const applyTenant = (value: string) => {
    const next = value.trim();
    void navigate({ to: "/secrets", search: next ? { tenant: next } : {} });
  };

  const submitSecret = async (key: string, value: string) => {
    const version = await store.save(key, value);
    if (version !== null) {
      toast.success(`${key} written`, { description: `now at version ${version}` });
      setDialog({ open: false, key: null });
    }
  };

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    const { key, destroy } = pendingDelete;
    setPendingDelete(null);
    const ok = await store.remove(key, destroy);
    if (ok) toast.success(destroy ? `${key} destroyed permanently` : `${key} soft-deleted`);
  };

  const confirmRotate = async () => {
    setRotateOpen(false);
    const activeKeyId = await store.rotateKey();
    if (activeKeyId !== null) {
      setActiveKeyId(activeKeyId);
      toast.success("Encryption key rotated", { description: `active key id #${activeKeyId}` });
    }
  };

  return (
    <div className="mx-auto max-w-5xl space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="hud-label">secrets</div>
          <h1 className="text-xl font-semibold tracking-tight">Vault contents</h1>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setRotateOpen(true)}
            className="gap-1.5"
          >
            <RefreshCw className="size-3.5" />
            Rotate encryption key
          </Button>
          <Button
            size="sm"
            className="gap-1.5"
            disabled={!store.tenantId}
            onClick={() => setDialog({ open: true, key: null })}
          >
            <Plus className="size-3.5" />
            New secret
          </Button>
        </div>
      </div>

      <div className="hud-panel flex flex-wrap items-end gap-2 p-3">
        <div className="min-w-56 flex-1 space-y-1">
          <div className="hud-label">tenant id</div>
          <Input
            list="tenant-options"
            className="h-8 font-mono text-xs"
            value={tenantInput}
            placeholder="asgard"
            onChange={(event) => setTenantInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") applyTenant(tenantInput);
            }}
          />
          <datalist id="tenant-options">
            {(status?.tenants ?? []).map((item) => (
              <option key={item} value={item} />
            ))}
          </datalist>
        </div>
        <Button variant="secondary" size="sm" onClick={() => applyTenant(tenantInput)}>
          Load
        </Button>
      </div>

      {store.error && <ErrorBanner message={store.error} />}

      {store.loading ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-10 rounded-sm" />
          ))}
        </div>
      ) : !store.tenantId ? (
        <p className="text-sm text-muted-foreground">Select a tenant to list its secrets.</p>
      ) : store.secrets.length === 0 ? (
        <div className="hud-panel p-8 text-center">
          <div className="hud-label">empty</div>
          <p className="mt-2 text-xs text-muted-foreground">
            No secrets stored for <span className="font-mono">{store.tenantId}</span>.
          </p>
        </div>
      ) : (
        <div className="hud-panel">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="hud-label">key</TableHead>
                <TableHead className="hud-label w-28">version</TableHead>
                <TableHead className="hud-label">value</TableHead>
                <TableHead className="hud-label w-40 text-right">actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {store.secrets.map((secret) => {
                const revealed = store.revealed[secret.key];
                return (
                  <TableRow key={secret.key}>
                    <TableCell className="font-mono text-xs">
                      <div className="flex items-center gap-2">
                        {secret.key}
                        {secret.deleted && (
                          <Badge
                            variant="outline"
                            className="rounded-sm border-status-bad/40 bg-status-bad-bg font-mono text-[10px] text-status-bad"
                          >
                            deleted
                          </Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="font-mono text-xs">v{secret.latestVersion}</TableCell>
                    <TableCell>
                      {!revealed ? (
                        <span className="font-mono text-xs text-muted-foreground">••••••••</span>
                      ) : revealed.loading ? (
                        <Skeleton className="h-4 w-32 rounded-sm" />
                      ) : (
                        <div className="flex flex-wrap items-center gap-2">
                          <code className="border border-border bg-secondary px-1.5 py-0.5 font-mono text-xs break-all">
                            {revealed.value}
                          </code>
                          <select
                            className="h-6 border border-border bg-background px-1 font-mono text-[11px]"
                            value={revealed.version}
                            onChange={(event) =>
                              void store.selectVersion(secret.key, Number(event.target.value))
                            }
                          >
                            {revealed.versions.map((version) => (
                              <option key={version} value={version}>
                                v{version}
                              </option>
                            ))}
                          </select>
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-0.5">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-7"
                          aria-label={revealed ? "Hide value" : "Reveal value"}
                          onClick={() =>
                            revealed ? store.hide(secret.key) : void store.reveal(secret.key)
                          }
                        >
                          {revealed ? (
                            <EyeOff className="size-3.5" />
                          ) : (
                            <Eye className="size-3.5" />
                          )}
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-7"
                          aria-label="Write new version"
                          onClick={() => setDialog({ open: true, key: secret.key })}
                        >
                          <Pencil className="size-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-7"
                          aria-label="Soft delete"
                          onClick={() => setPendingDelete({ key: secret.key, destroy: false })}
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-7 text-status-bad hover:text-status-bad"
                          aria-label="Destroy permanently"
                          onClick={() => setPendingDelete({ key: secret.key, destroy: true })}
                        >
                          <Flame className="size-3.5" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}

      <SecretDialog
        state={dialog}
        tenantId={store.tenantId}
        pending={store.writing}
        onOpenChange={(open) => setDialog({ open, key: open ? dialog.key : null })}
        onSubmit={(key, value) => void submitSecret(key, value)}
      />

      <AlertDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => !open && setPendingDelete(null)}
      >
        <AlertDialogContent className="rounded-sm">
          <AlertDialogHeader>
            <AlertDialogTitle>
              {pendingDelete?.destroy ? "Destroy permanently?" : "Soft-delete secret?"}
            </AlertDialogTitle>
            <AlertDialogDescription className="font-mono text-xs">
              {pendingDelete?.destroy
                ? `${pendingDelete?.key} and every version of it will be erased from the vault. This cannot be undone.`
                : `${pendingDelete?.key} stays listed as deleted and can be re-created with a new version.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => void confirmDelete()}>
              {pendingDelete?.destroy ? "Destroy" : "Delete"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={rotateOpen} onOpenChange={setRotateOpen}>
        <AlertDialogContent className="rounded-sm">
          <AlertDialogHeader>
            <AlertDialogTitle>Rotate encryption key?</AlertDialogTitle>
            <AlertDialogDescription className="font-mono text-xs">
              This is a cluster-wide operation: a new active key id is issued and all future writes
              are sealed with it.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => void confirmRotate()} disabled={store.rotating}>
              {store.rotating ? "Rotating…" : "Rotate"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
