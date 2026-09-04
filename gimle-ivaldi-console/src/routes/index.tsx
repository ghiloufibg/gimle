import { Link, createFileRoute, useNavigate } from "@tanstack/react-router";
import { Copy, Moon, Plus, Server, Sun, Trash2, Upload } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { IvaldiWordmark } from "@/components/ivaldi/IvaldiEmblem";
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
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { isWorkload, type Blueprint } from "@/lib/blueprint";
import { readBlueprintFile } from "@/lib/import";
import { validate } from "@/lib/rules";
import { useBlueprintsListStore } from "@/stores/useBlueprintsListStore";
import { useClustersStore } from "@/stores/useClustersStore";
import { applyTheme, storedTheme, useUiStore } from "@/stores/useUiStore";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Blueprints — Ivaldi" },
      {
        name: "description",
        content: "Every Gimlé cluster blueprint you have designed, with live problem counts.",
      },
      { property: "og:title", content: "Blueprints — Ivaldi" },
      {
        property: "og:description",
        content: "Every Gimlé cluster blueprint you have designed, with live problem counts.",
      },
    ],
  }),
  component: BlueprintsList,
});

function counts(bp: Blueprint | undefined) {
  if (!bp) return null;
  const problems = validate(bp);
  return {
    machines: bp.nodes.filter((n) => n.kind === "machine").length,
    roles: bp.nodes.filter((n) =>
      ["store", "controlPlane", "fafnir", "muninn", "andvari", "agent"].includes(n.kind),
    ).length,
    workloads: bp.nodes.filter((n) => isWorkload(n.kind)).length,
    errors: problems.filter((p) => p.severity === "error").length,
    warnings: problems.filter((p) => p.severity === "warning").length,
    infos: problems.filter((p) => p.severity === "info").length,
  };
}

function BlueprintsList() {
  const navigate = useNavigate();
  const { blueprints, details, error, refresh, create, remove, duplicate, importBlueprint } =
    useBlueprintsListStore();
  const { theme, toggleTheme, setTheme } = useUiStore();
  const fileInput = useRef<HTMLInputElement>(null);
  const clusters = useClustersStore((s) => s.clusters);
  const refreshClusters = useClustersStore((s) => s.refresh);
  const selectCluster = useClustersStore((s) => s.select);
  const [askCluster, setAskCluster] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<{ id: string; name: string } | null>(null);

  useEffect(() => {
    applyTheme(storedTheme());
    setTheme(storedTheme());
    void refresh();
    refreshClusters();
  }, [refresh, setTheme, refreshClusters]);

  useEffect(() => {
    if (error) toast.error("Couldn't load blueprints", { description: error });
  }, [error]);

  async function createAndOpen(clusterId?: string) {
    setAskCluster(false);
    if (clusterId) selectCluster(clusterId);
    const bp = await create(`blueprint-${blueprints.length + 1}`, { empty: !clusterId });
    void navigate({ to: "/designer/$blueprintId", params: { blueprintId: bp.id } });
  }

  return (
    <main className="min-h-screen bg-background">
      <header className="flex items-center justify-between border-b border-border px-4 py-2.5">
        <IvaldiWordmark />
        <div className="flex items-center gap-2">
          <input
            ref={fileInput}
            type="file"
            accept=".zip,.json"
            className="hidden"
            onChange={async (e) => {
              const file = e.target.files?.[0];
              e.target.value = "";
              if (!file) return;
              try {
                const bp = await readBlueprintFile(file);
                const saved = await importBlueprint(bp);
                toast.success("Blueprint imported", { description: saved.name });
                void navigate({ to: "/designer/$blueprintId", params: { blueprintId: saved.id } });
              } catch (error) {
                toast.error("Import failed", { description: (error as Error).message });
              }
            }}
          />
          <Link
            to="/clusters"
            className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2.5 font-mono text-[11px] text-foreground hover:border-primary"
          >
            <Server className="size-3" /> Clusters
          </Link>
          <button
            onClick={() => fileInput.current?.click()}
            className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2.5 font-mono text-[11px] text-foreground hover:border-primary"
          >
            <Upload className="size-3" /> Import zip/JSON
          </button>
          <button
            onClick={() => {
              if (clusters.length > 0) setAskCluster(true);
              else void createAndOpen();
            }}
            className="inline-flex h-7 items-center gap-1.5 rounded-sm bg-primary px-2.5 font-mono text-[11px] text-primary-foreground"
          >
            <Plus className="size-3" /> New blueprint
          </button>
          <button
            onClick={toggleTheme}
            className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2.5 font-mono text-[11px] text-foreground hover:border-primary"
          >
            {theme === "dark" ? <Sun className="size-3" /> : <Moon className="size-3" />}
            {theme === "dark" ? "Light" : "Dark"}
          </button>
        </div>
      </header>

      <section className="p-4">
        <div className="hud-label mb-2">Blueprints</div>
        <div className="overflow-hidden rounded-sm border border-border">
          <table className="w-full border-collapse text-[12px]">
            <thead className="bg-card">
              <tr className="border-b border-border text-left">
                {[
                  "Name",
                  "Version",
                  "Machines",
                  "Roles",
                  "Workloads",
                  "Problems",
                  "Updated",
                  "",
                ].map((h) => (
                  <th key={h} className="hud-label px-3 py-1.5">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {blueprints.map((bp) => {
                const c = counts(details[bp.id]);
                return (
                  <tr
                    key={bp.id}
                    className="cursor-pointer border-b border-border/60 last:border-0 hover:bg-accent/40"
                    onClick={() =>
                      navigate({ to: "/designer/$blueprintId", params: { blueprintId: bp.id } })
                    }
                  >
                    <td className="px-3 py-1.5 font-mono text-[12px] text-foreground">{bp.name}</td>
                    <td className="num px-3 py-1.5 text-muted-foreground">{bp.version}</td>
                    <td className="num px-3 py-1.5 text-muted-foreground">
                      {c ? c.machines : "—"}
                    </td>
                    <td className="num px-3 py-1.5 text-muted-foreground">{c ? c.roles : "—"}</td>
                    <td className="num px-3 py-1.5 text-muted-foreground">
                      {c ? c.workloads : "—"}
                    </td>
                    <td className="num px-3 py-1.5">
                      {c ? (
                        <>
                          <span className="text-status-bad">{c.errors}</span>
                          <span className="text-muted-foreground"> / </span>
                          <span className="text-status-warn">{c.warnings}</span>
                          <span className="text-muted-foreground"> / </span>
                          <span className="text-status-info">{c.infos}</span>
                        </>
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="num px-3 py-1.5 text-muted-foreground">
                      {bp.updatedAt
                        ? new Date(bp.updatedAt).toLocaleString("en-GB", { hour12: false })
                        : "—"}
                    </td>
                    <td className="px-3 py-1.5 text-right">
                      <div className="flex justify-end gap-1">
                        <button
                          title="Duplicate"
                          onClick={(e) => {
                            e.stopPropagation();
                            void duplicate(bp.id);
                          }}
                          className="rounded-sm border border-border p-1 text-muted-foreground hover:border-primary hover:text-foreground"
                        >
                          <Copy className="size-3" />
                        </button>
                        <button
                          title="Delete"
                          onClick={(e) => {
                            e.stopPropagation();
                            setPendingDelete({ id: bp.id, name: bp.name });
                          }}
                          className="rounded-sm border border-border p-1 text-muted-foreground hover:border-destructive hover:text-destructive"
                        >
                          <Trash2 className="size-3" />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
              {blueprints.length === 0 && (
                <tr>
                  <td colSpan={8} className="px-3 py-6 text-center text-xs text-muted-foreground">
                    No blueprints yet. Create one or import a zip.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <AlertDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => !open && setPendingDelete(null)}
      >
        <AlertDialogContent className="rounded-sm">
          <AlertDialogHeader>
            <AlertDialogTitle className="font-mono text-sm">
              Delete {pendingDelete?.name}?
            </AlertDialogTitle>
            <AlertDialogDescription className="text-xs">
              This removes the blueprint and its saved layout. It cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel className="rounded-sm font-mono text-[11px]">
              Cancel
            </AlertDialogCancel>
            <AlertDialogAction
              className="rounded-sm bg-destructive font-mono text-[11px] text-destructive-foreground hover:bg-destructive/90"
              onClick={() => {
                const target = pendingDelete;
                setPendingDelete(null);
                if (target) void remove(target.id);
              }}
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Dialog open={askCluster} onOpenChange={setAskCluster}>
        <DialogContent className="max-w-md rounded-sm">
          <DialogHeader>
            <DialogTitle className="font-mono text-sm">New blueprint</DialogTitle>
            <DialogDescription className="text-xs">
              Start blank, or target one of your configured clusters.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <button
              onClick={() => void createAndOpen()}
              className="w-full rounded-sm border border-border px-3 py-2 text-left font-mono text-[12px] text-foreground hover:border-primary"
            >
              Start from scratch
            </button>
            <div>
              <div className="hud-label mb-1">Use an existing cluster</div>
              <div className="space-y-1">
                {clusters.map((c) => (
                  <button
                    key={c.id}
                    onClick={() => void createAndOpen(c.id)}
                    className="flex w-full items-center justify-between gap-2 rounded-sm border border-border px-3 py-2 text-left font-mono text-[12px] text-foreground hover:border-primary"
                  >
                    <span>{c.name}</span>
                    <span className="text-[10px] text-muted-foreground">{c.controlPlaneUrl}</span>
                  </button>
                ))}
              </div>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </main>
  );
}
