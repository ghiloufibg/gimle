import { createFileRoute, Link, useNavigate, useParams, useRouter } from "@tanstack/react-router";
import {
  CheckCircle2,
  Crosshair,
  Download,
  FileCode2,
  GripHorizontal,
  ListChecks,
  Maximize2,
  Minimize2,
  Moon,
  Play,
  Redo2,
  Save,
  Sun,
  Undo2,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";

import { DesignerCanvas } from "@/components/ivaldi/DesignerCanvas";
import { FilesDrawer } from "@/components/ivaldi/FilesDrawer";
import { Inspector } from "@/components/ivaldi/Inspector";
import { IvaldiWordmark } from "@/components/ivaldi/IvaldiEmblem";
import { Palette } from "@/components/ivaldi/Palette";
import { ProblemsDrawer } from "@/components/ivaldi/ProblemsDrawer";
import { RunDrawer } from "@/components/ivaldi/RunDrawer";
import { RUN_STATUS_CLASS } from "@/components/ivaldi/RunDrawer";
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
import { canvasBridge } from "@/lib/canvasBridge";
import { collapseProblems, tally } from "@/lib/problemView";
import { renderFiles } from "@/lib/render";
import { secretKeys } from "@/lib/runArtifacts";
import { downloadZip } from "@/lib/zip";
import { cn } from "@/lib/utils";
import { ACTIVE_RUNS_POLL_MS, useActiveRunsStore } from "@/stores/useActiveRunsStore";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useRunStore } from "@/stores/useRunStore";
import { applyTheme, storedTheme, useUiStore } from "@/stores/useUiStore";
import { useValidationStore } from "@/stores/useValidationStore";

export const Route = createFileRoute("/designer/$blueprintId")({
  head: () => ({
    meta: [
      { title: "Designer — Ivaldi" },
      {
        name: "description",
        content:
          "Lay out machines, platform roles, tenants and workloads on a canvas and export the Gimlé YAML bundle.",
      },
      { property: "og:title", content: "Designer — Ivaldi" },
      {
        property: "og:description",
        content: "Lay out a Gimlé cluster on a canvas and export its YAML bundle.",
      },
    ],
  }),
  component: Designer,
  errorComponent: DesignerError,
});

/**
 * A blueprint the designer cannot open used to strand the user here for good: the default error
 * screen's Retry re-rendered the same failing component with no way back to the list.
 */
function DesignerError({ error, reset }: { error: Error; reset: () => void }) {
  const router = useRouter();
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <div className="hud-label">Designer error</div>
        <h1 className="mt-2 text-sm font-semibold text-foreground">
          This blueprint could not be opened
        </h1>
        <p className="mt-2 font-mono text-[11px] text-muted-foreground">{error.message}</p>
        <div className="mt-4 flex justify-center gap-2">
          <button
            onClick={() => {
              void router.invalidate();
              reset();
            }}
            className="inline-flex h-7 items-center rounded-sm border border-primary bg-primary px-2.5 font-mono text-[11px] text-primary-foreground"
          >
            Retry
          </button>
          <Link
            to="/"
            className="inline-flex h-7 items-center rounded-sm border border-border px-2.5 font-mono text-[11px] text-foreground hover:border-primary"
          >
            Back to blueprints
          </Link>
        </div>
      </div>
    </div>
  );
}

function Counter({
  count,
  tone,
  onClick,
  title,
}: {
  count: number;
  tone: "bad" | "warn" | "info";
  onClick: () => void;
  title: string;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={cn(
        "num rounded-sm border px-1.5 py-0.5 text-[11px]",
        tone === "bad" && "border-status-bad/40 bg-status-bad-bg text-status-bad",
        tone === "warn" && "border-status-warn/40 bg-status-warn-bg text-status-warn",
        tone === "info" && "border-status-info/40 bg-status-info-bg text-status-info",
      )}
    >
      {count}
    </button>
  );
}

function ToolbarButton({
  icon: Icon,
  label,
  onClick,
  active,
  primary,
}: {
  icon: typeof Play;
  label: string;
  onClick: () => void;
  active?: boolean;
  primary?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "inline-flex h-7 items-center gap-1.5 rounded-sm border px-2.5 font-mono text-[11px]",
        primary
          ? "border-primary bg-primary text-primary-foreground"
          : "border-border text-foreground hover:border-primary",
        active && !primary && "border-primary bg-accent text-accent-foreground",
      )}
    >
      <Icon className="size-3" /> {label}
    </button>
  );
}

function ResizeHandle({
  containerRef,
  onHeight,
}: {
  containerRef: React.RefObject<HTMLDivElement | null>;
  onHeight: (height: number) => void;
}) {
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    if (!dragging) return;

    const onMove = (e: MouseEvent) => {
      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) return;
      const height = rect.bottom - e.clientY;
      onHeight(height);
    };

    const onUp = () => setDragging(false);
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [dragging, containerRef, onHeight]);

  return (
    <div
      onMouseDown={() => setDragging(true)}
      className={cn(
        "group flex h-4 shrink-0 cursor-ns-resize items-center justify-center border-y border-border bg-background transition-colors hover:bg-accent",
        dragging && "bg-accent",
      )}
    >
      <GripHorizontal className="size-3 text-muted-foreground group-hover:text-foreground" />
    </div>
  );
}

function Designer() {
  const { blueprintId } = useParams({ from: "/designer/$blueprintId" });
  const blueprint = useBlueprintStore((s) => s.blueprint);
  const dirty = useBlueprintStore((s) => s.dirty);
  const load = useBlueprintStore((s) => s.load);
  const save = useBlueprintStore((s) => s.save);
  const patch = useBlueprintStore((s) => s.patchBlueprint);
  const ivaldiProblems = useValidationStore((s) => s.problems);
  const hilmirProblems = useValidationStore((s) => s.serverProblems);
  const rows = useMemo(
    () => collapseProblems(ivaldiProblems, hilmirProblems),
    [ivaldiProblems, hilmirProblems],
  );
  const { drawer, toggleDrawer, setDrawer, drawerHeight, theme, toggleTheme, setTheme } =
    useUiStore();
  const hilmirRunning = useValidationStore((s) => s.hilmir.running);
  const startRun = useRunStore((s) => s.start);
  const navigate = useNavigate();
  const [isFullscreen, setIsFullscreen] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);
  const middleRef = useRef<HTMLDivElement>(null);
  const setDrawerHeight = useUiStore((s) => s.setDrawerHeight);
  const undo = useBlueprintStore((s) => s.undo);
  const redo = useBlueprintStore((s) => s.redo);
  const canUndo = useBlueprintStore((s) => s.past.length > 0);
  const canRedo = useBlueprintStore((s) => s.future.length > 0);
  const recoverableDraft = useBlueprintStore((s) => s.recoverableDraft);
  const restoreDraft = useBlueprintStore((s) => s.restoreDraft);
  const discardDraft = useBlueprintStore((s) => s.discardDraft);

  const exitFullscreen = () => {
    if (document.fullscreenElement) void document.exitFullscreen();
    setIsFullscreen(false);
  };

  useEffect(() => {
    applyTheme(storedTheme());
    setTheme(storedTheme());
  }, [setTheme]);

  useEffect(() => {
    const onChange = () => {
      setIsFullscreen(Boolean(document.fullscreenElement));
      // The canvas box just changed size: refit so nothing sits off-screen.
      setTimeout(() => canvasBridge.fit(), 120);
    };
    document.addEventListener("fullscreenchange", onChange);
    return () => document.removeEventListener("fullscreenchange", onChange);
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && document.fullscreenElement) {
        void document.exitFullscreen();
        return;
      }
      // Every other dismissible surface here (the delete-confirmation dialog, most native
      // pickers) already closes on Escape; the Problems/Files/Run drawer didn't, only via its own
      // header toggle or re-pressing the same toolbar button -- read live off the store, not the
      // `drawer` in this component's own closure, since this effect's deps array never re-runs it.
      if (e.key === "Escape" && useUiStore.getState().drawer) {
        useUiStore.getState().setDrawer(null);
        return;
      }
      const target = e.target as HTMLElement | null;
      const typing =
        target?.tagName === "INPUT" || target?.tagName === "TEXTAREA" || target?.isContentEditable;
      if (typing || !(e.ctrlKey || e.metaKey)) return;
      const key = e.key.toLowerCase();
      if (key === "z" && !e.shiftKey) {
        e.preventDefault();
        useBlueprintStore.getState().undo();
      } else if ((key === "z" && e.shiftKey) || key === "y") {
        e.preventDefault();
        useBlueprintStore.getState().redo();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    void load(blueprintId);
  }, [blueprintId, load]);

  const pendingSave = useRef(false);
  const saveRef = useRef(save);
  saveRef.current = save;
  const persistDraftNow = useBlueprintStore((s) => s.persistDraftNow);

  useEffect(() => {
    if (!blueprint || !dirty) return;
    // Synchronous, immediate localStorage snapshot -- covers the crash/force-kill window between
    // now and the debounced network save below (or the network request itself) actually landing.
    persistDraftNow();
    pendingSave.current = true;
    const t = setTimeout(() => {
      pendingSave.current = false;
      void saveRef.current();
    }, 600);
    // Flush rather than cancel: unmounting inside the debounce window used to discard the edit
    // outright, with "UNSAVED" looking identical whether a save was 50ms away or already gone.
    return () => {
      clearTimeout(t);
      if (pendingSave.current) {
        pendingSave.current = false;
        void saveRef.current();
      }
    };
  }, [blueprint, dirty, persistDraftNow]);

  useEffect(() => {
    const flush = () => {
      if (!pendingSave.current) return;
      pendingSave.current = false;
      void saveRef.current();
    };
    window.addEventListener("beforeunload", flush);
    return () => window.removeEventListener("beforeunload", flush);
  }, []);

  const tallies = useMemo(() => tally(rows), [rows]);

  // A blueprint can be edited at length while the cluster it describes is up; without this the
  // designer gave no sign of it, and no hint that the next run is what would apply the edits.
  const refreshRuns = useActiveRunsStore((s) => s.refresh);
  const activeRuns = useActiveRunsStore((s) => s.runs);
  const activeRun = activeRuns.find((r) => r.blueprintId === blueprintId);
  useEffect(() => {
    void refreshRuns();
    const id = window.setInterval(() => void refreshRuns(), ACTIVE_RUNS_POLL_MS);
    return () => window.clearInterval(id);
  }, [refreshRuns]);

  if (!blueprint)
    return (
      <div className="flex min-h-screen items-center justify-center bg-background">
        <div className="text-center">
          <div className="hud-label">Not found</div>
          <p className="mt-1 text-xs text-muted-foreground">
            This blueprint no longer exists.{" "}
            <Link to="/" className="text-primary hover:underline">
              Back to blueprints
            </Link>
          </p>
        </div>
      </div>
    );

  return (
    <div className="flex h-screen flex-col bg-background">
      {/*
        None of this row's children (the name/version inputs, the run-status badge, the dozen
        toolbar buttons) ever shrink or wrap -- below a certain viewport width, `justify-between`
        has no slack left to distribute and used to push the header (and with it the whole page,
        since nothing here was ever contained) wider than the viewport, hiding real controls (Full,
        the theme toggle) off the right edge with no on-screen sign anything was off-screen.
        overflow-x-auto contains that overflow to this one strip, scrollable in place, rather than
        letting it propagate to <body> -- every control stays reachable at any width instead of a
        wider structural rework of the toolbar itself.
      */}
      <header className="flex shrink-0 items-center justify-between gap-4 overflow-x-auto border-b border-border px-3 py-2">
        <div className="flex items-center gap-4">
          <Link to="/">
            <IvaldiWordmark compact />
          </Link>
          <div className="flex items-center gap-2">
            <input
              value={blueprint.name}
              onChange={(e) => patch({ name: e.target.value })}
              className="h-7 w-[240px] rounded-sm border border-transparent bg-transparent px-1.5 font-mono text-[13px] font-semibold text-foreground outline-none hover:border-border focus:border-primary"
            />
            <input
              value={blueprint.version}
              onChange={(e) => patch({ version: e.target.value })}
              className="num h-7 w-[80px] rounded-sm border border-transparent bg-transparent px-1.5 text-[11px] text-muted-foreground outline-none hover:border-border focus:border-primary"
            />
            <span className="hud-label">{dirty ? "unsaved" : "saved"}</span>
            {activeRun && (
              <Link
                to="/runner/$blueprintId"
                params={{ blueprintId: blueprint.id }}
                title="This blueprint owns a live cluster. Editing it here changes nothing until the next run."
                className={cn(
                  "rounded-sm px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-widest",
                  RUN_STATUS_CLASS[activeRun.status],
                )}
              >
                {activeRun.status}
              </Link>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1">
            <Counter
              count={tallies.errors}
              tone="bad"
              title={`${tallies.errors} error${tallies.errors === 1 ? "" : "s"}`}
              onClick={() => setDrawer("problems")}
            />
            <Counter
              count={tallies.warnings}
              tone="warn"
              title={`${tallies.warnings} warning${tallies.warnings === 1 ? "" : "s"}`}
              onClick={() => setDrawer("problems")}
            />
            <Counter
              count={tallies.infos}
              tone="info"
              title={`${tallies.infos} note${tallies.infos === 1 ? "" : "s"}`}
              onClick={() => setDrawer("problems")}
            />
          </div>
          <button
            title="Undo (Ctrl+Z)"
            aria-label="Undo"
            disabled={!canUndo}
            onClick={undo}
            className="inline-flex size-7 items-center justify-center rounded-sm border border-border text-foreground hover:border-primary disabled:opacity-40"
          >
            <Undo2 className="size-3.5" />
          </button>
          <button
            title="Redo (Ctrl+Shift+Z)"
            aria-label="Redo"
            disabled={!canRedo}
            onClick={redo}
            className="inline-flex size-7 items-center justify-center rounded-sm border border-border text-foreground hover:border-primary disabled:opacity-40"
          >
            <Redo2 className="size-3.5" />
          </button>
          <ToolbarButton
            icon={CheckCircle2}
            label={hilmirRunning ? "Validating…" : "Validate"}
            onClick={() => {
              if (hilmirRunning) return;
              // setDrawer, not toggleDrawer: validating with the drawer already open used to
              // close it, leaving the next Problems click looking like it did nothing.
              setDrawer("problems");
              void useValidationStore
                .getState()
                .validateWithHilmir(blueprint)
                .then(() => {
                  const state = useValidationStore.getState();
                  const errors = state.errorCount();
                  const warnings = state.warningCount();
                  toast.message("Validated", {
                    description: `${errors} error${errors === 1 ? "" : "s"}, ${warnings} warning${warnings === 1 ? "" : "s"}.`,
                  });
                });
            }}
          />
          <ToolbarButton
            icon={ListChecks}
            label="Problems"
            active={drawer === "problems"}
            onClick={() => toggleDrawer("problems")}
          />
          <ToolbarButton
            icon={FileCode2}
            label="Files"
            active={drawer === "files"}
            onClick={() => toggleDrawer("files")}
          />
          <ToolbarButton
            icon={Download}
            label="Download zip"
            onClick={() => {
              downloadZip(blueprint.name, renderFiles(blueprint));
              toast.success("Zip downloaded");
            }}
          />
          <ToolbarButton
            icon={Play}
            label="Run locally"
            primary
            onClick={() => {
              const secrets = secretKeys(blueprint);
              if (secrets.length > 0)
                toast.message("Secret values needed", {
                  description: `Type ${secrets.length} secret value${secrets.length === 1 ? "" : "s"} on the runner screen, then press Run.`,
                });
              else void startRun(blueprint);
              void navigate({ to: "/runner/$blueprintId", params: { blueprintId: blueprint.id } });
            }}
          />
          <ToolbarButton icon={Save} label="Save" onClick={save} />
          <ToolbarButton icon={Crosshair} label="Fit" onClick={() => canvasBridge.fit()} />
          <ToolbarButton
            icon={isFullscreen ? Minimize2 : Maximize2}
            label={isFullscreen ? "Exit" : "Full"}
            onClick={() => {
              if (document.fullscreenElement) {
                exitFullscreen();
              } else {
                void canvasRef.current?.requestFullscreen();
              }
            }}
          />
          <ToolbarButton
            icon={theme === "dark" ? Sun : Moon}
            label={theme === "dark" ? "Light" : "Dark"}
            onClick={toggleTheme}
          />
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        <Palette />
        <div ref={middleRef} className="flex min-w-0 flex-1 flex-col">
          <div ref={canvasRef} className="relative min-h-0 flex-1 bg-background">
            <DesignerCanvas blueprint={blueprint} />
            {isFullscreen && (
              <button
                onClick={exitFullscreen}
                className="absolute right-3 top-3 z-50 inline-flex h-7 items-center gap-1.5 rounded-sm border border-border bg-card px-2.5 font-mono text-[11px] text-foreground hover:border-primary"
              >
                <X className="size-3" /> Exit full screen (Esc)
              </button>
            )}
          </div>
          {drawer && (
            <>
              <ResizeHandle containerRef={middleRef} onHeight={setDrawerHeight} />
              <div
                className="shrink-0 border-t border-border bg-card"
                style={{ height: drawerHeight }}
              >
                <div className="flex items-center justify-between border-b border-border px-3 py-1">
                  <span className="hud-label">{drawer}</span>
                  <button
                    onClick={() => useUiStore.getState().setDrawer(null)}
                    className="font-mono text-[10px] text-muted-foreground hover:text-foreground"
                  >
                    close
                  </button>
                </div>
                <div className="h-[calc(100%-25px)]">
                  {drawer === "problems" && <ProblemsDrawer blueprint={blueprint} />}
                  {drawer === "files" && <FilesDrawer blueprint={blueprint} />}
                  {drawer === "run" && <RunDrawer blueprint={blueprint} />}
                </div>
              </div>
            </>
          )}
        </div>
        <Inspector blueprint={blueprint} />
      </div>
      <AlertDialog open={recoverableDraft !== null}>
        <AlertDialogContent className="rounded-sm">
          <AlertDialogHeader>
            <AlertDialogTitle className="font-mono text-sm">
              Restore unsaved draft?
            </AlertDialogTitle>
            <AlertDialogDescription className="text-xs">
              A newer edit from a previous session was never saved to the server -- likely a tab or
              process that closed before the save completed. Restore it, or discard it and keep
              what's on the server.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel className="rounded-sm font-mono text-[11px]" onClick={discardDraft}>
              Discard
            </AlertDialogCancel>
            <AlertDialogAction className="rounded-sm font-mono text-[11px]" onClick={restoreDraft}>
              Restore
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
