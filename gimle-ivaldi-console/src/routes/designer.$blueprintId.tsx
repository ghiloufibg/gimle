import { createFileRoute, Link, useNavigate, useParams } from "@tanstack/react-router";
import {
  CheckCircle2,
  Download,
  FileCode2,
  GripHorizontal,
  ListChecks,
  Maximize2,
  Minimize2,
  Moon,
  Play,
  Save,
  Sun,
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
import { renderFiles } from "@/lib/render";
import { downloadZip } from "@/lib/zip";
import { cn } from "@/lib/utils";
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
});

function Counter({
  count,
  tone,
  onClick,
}: {
  count: number;
  tone: "bad" | "warn" | "info";
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
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
  const problems = useMemo(
    () => [...ivaldiProblems, ...hilmirProblems],
    [ivaldiProblems, hilmirProblems],
  );
  const { drawer, toggleDrawer, drawerHeight, theme, toggleTheme, setTheme } = useUiStore();
  const startRun = useRunStore((s) => s.start);
  const navigate = useNavigate();
  const [isFullscreen, setIsFullscreen] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);
  const middleRef = useRef<HTMLDivElement>(null);
  const setDrawerHeight = useUiStore((s) => s.setDrawerHeight);

  useEffect(() => {
    applyTheme(storedTheme());
    setTheme(storedTheme());
  }, [setTheme]);

  useEffect(() => {
    const onChange = () => setIsFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener("fullscreenchange", onChange);
    return () => document.removeEventListener("fullscreenchange", onChange);
  }, []);

  useEffect(() => {
    void load(blueprintId);
  }, [blueprintId, load]);

  useEffect(() => {
    if (!blueprint || !dirty) return;
    const t = setTimeout(() => { void save(); }, 600);
    return () => clearTimeout(t);
  }, [blueprint, dirty, save]);

  const tallies = useMemo(
    () => ({
      errors: problems.filter((p) => p.severity === "error").length,
      warnings: problems.filter((p) => p.severity === "warning").length,
      infos: problems.filter((p) => p.severity === "info").length,
    }),
    [problems],
  );

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
      <header className="flex shrink-0 items-center justify-between gap-4 border-b border-border px-3 py-2">
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
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1">
            <Counter count={tallies.errors} tone="bad" onClick={() => toggleDrawer("problems")} />
            <Counter count={tallies.warnings} tone="warn" onClick={() => toggleDrawer("problems")} />
            <Counter count={tallies.infos} tone="info" onClick={() => toggleDrawer("problems")} />
          </div>
          <ToolbarButton
            icon={CheckCircle2}
            label="Validate"
            onClick={() => {
              useValidationStore.getState().recompute(blueprint);
              toggleDrawer("problems");
              toast.message("Validated", {
                description: `${tallies.errors} errors, ${tallies.warnings} warnings.`,
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
              void startRun(blueprint);
              void navigate({ to: "/runner/$blueprintId", params: { blueprintId: blueprint.id } });
            }}
          />
          <ToolbarButton icon={Save} label="Save" onClick={save} />
          <ToolbarButton
            icon={isFullscreen ? Minimize2 : Maximize2}
            label={isFullscreen ? "Exit" : "Full"}
            onClick={() => {
              if (document.fullscreenElement) {
                void document.exitFullscreen();
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
          <div ref={canvasRef} className="min-h-0 flex-1 bg-background">
            <DesignerCanvas blueprint={blueprint} />
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
    </div>
  );
}
