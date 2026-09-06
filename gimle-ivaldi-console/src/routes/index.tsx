import { Link, createFileRoute, useNavigate } from "@tanstack/react-router";
import { Copy, Download, Moon, Pencil, Plus, Server, Sun, Trash2, Upload } from "lucide-react";
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
import { renderFiles } from "@/lib/render";
import { validate } from "@/lib/rules";
import { downloadZip } from "@/lib/zip";
import { cn } from "@/lib/utils";
import { RUN_STATUS_CLASS } from "@/components/ivaldi/RunDrawer";
import { ACTIVE_RUNS_POLL_MS, useActiveRunsStore } from "@/stores/useActiveRunsStore";
import { useBlueprintsListStore } from "@/stores/useBlueprintsListStore";
import { useClustersStore } from "@/stores/useClustersStore";
import { useRunStore } from "@/stores/useRunStore";
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
  const {
    blueprints,
    details,
    error,
    errorTitle,
    refresh,
    create,
    remove,
    rename,
    duplicate,
    importBlueprint,
  } = useBlueprintsListStore();
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

  // A list that silently stayed empty when the backend was unreachable read as "no blueprints".
  useEffect(() => {
    if (error) toast.error(errorTitle ?? "Couldn't load blueprints", { description: error });
  }, [error, errorTitle]);

  // Which blueprints own a live cluster. Polled rather than read once: a run this list started
  // reaches ACTIVE, and later dies, entirely outside this screen.
  const refreshRuns = useActiveRunsStore((s) => s.refresh);
  // Subscribed to the list itself, not to the lookup helper: the helper's identity never changes,
  // so selecting it alone would leave this table frozen on whatever was running when it mounted.
  const activeRuns = useActiveRunsStore((s) => s.runs);
  const runFor = (blueprintId: string) => activeRuns.find((r) => r.blueprintId === blueprintId);
  useEffect(() => {
    void refreshRuns();
    const id = window.setInterval(() => void refreshRuns(), ACTIVE_RUNS_POLL_MS);
    return () => window.clearInterval(id);
  }, [refreshRuns]);

  async function createAndOpen(clusterId?: string) {
    setAskCluster(false);
    if (clusterId) selectCluster(clusterId);
    const bp = await create(`blueprint-${blueprints.length + 1}`, { empty: !clusterId });
    void navigate({ to: "/designer/$blueprintId", params: { blueprintId: bp.id } });
  }

  return (
    <main className="min-h-screen bg-background">
      {
        // Same fix as the Designer's own header: none of these buttons shrink or wrap, so below a
        // phone-width viewport they forced the whole page wider than it, not just this row --
        // overflow-x-auto contains it to this strip instead.
      }
      <header className="flex items-center justify-between overflow-x-auto border-b border-border px-4 py-2.5">
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
        {
          // overflow-hidden clipped whichever columns (Run, Updated, the row-action icons) didn't
          // fit at phone width, with nothing on screen suggesting there was more to see -- an
          // x-scrolling wrapper keeps every column reachable instead.
        }
        <div className="overflow-x-auto rounded-sm border border-border">
          <table className="w-full min-w-[640px] border-collapse text-[12px]">
            <thead className="bg-card">
              <tr className="border-b border-border text-left">
                {[
                  "Name",
                  "Version",
                  "Machines",
                  "Roles",
                  "Workloads",
                  "Problems",
                  "Run",
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
                const run = runFor(bp.id);
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
                    <td className="px-3 py-1.5">
                      {run ? (
                        <Link
                          to="/runner/$blueprintId"
                          params={{ blueprintId: bp.id }}
                          onClick={(e) => e.stopPropagation()}
                          className={cn(
                            "rounded-sm px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-widest",
                            RUN_STATUS_CLASS[run.status],
                          )}
                        >
                          {run.status}
                        </Link>
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
                          title="Rename"
                          onClick={(e) => {
                            e.stopPropagation();
                            const next = window.prompt("Rename blueprint", bp.name);
                            if (next && next.trim() && next.trim() !== bp.name)
                              void rename(bp.id, next.trim());
                          }}
                          className="rounded-sm border border-border p-1 text-muted-foreground hover:border-primary hover:text-foreground"
                        >
                          <Pencil className="size-3" />
                        </button>
                        <button
                          title="Download zip"
                          onClick={(e) => {
                            e.stopPropagation();
                            const full = details[bp.id];
                            if (!full) {
                              toast.error("Still loading", {
                                description: "Open the blueprint and download from the designer.",
                              });
                              return;
                            }
                            try {
                              downloadZip(full.name, renderFiles(full));
                              toast.success("Zip downloaded", { description: full.name });
                            } catch (error) {
                              toast.error("Could not build the zip", {
                                description: (error as Error).message,
                              });
                            }
                          }}
                          className="rounded-sm border border-border p-1 text-muted-foreground hover:border-primary hover:text-foreground"
                        >
                          <Download className="size-3" />
                        </button>
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
                            const run = useRunStore.getState();
                            if (
                              run.runId &&
                              run.status !== "idle" &&
                              run.request?.blueprintId === bp.id
                            ) {
                              toast.error("Still running", {
                                description: `${bp.name} owns the current run. Stop it on the runner screen first.`,
                              });
                              return;
                            }
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
                  <td colSpan={9} className="px-3 py-6 text-center text-xs text-muted-foreground">
                    No blueprints yet. Press New blueprint to start from scratch or from a saved
                    cluster, or import a zip or JSON file you already have.
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
