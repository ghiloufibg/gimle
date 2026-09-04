import { Link, createFileRoute, useParams } from "@tanstack/react-router";
import { ArrowLeft, Play, RotateCcw, Square } from "lucide-react";
import { useEffect } from "react";

import { IvaldiWordmark } from "@/components/ivaldi/IvaldiEmblem";
import { ClusterPicker } from "@/components/ivaldi/ClusterPicker";
import { RunArtifacts } from "@/components/ivaldi/RunArtifacts";
import { RunConsole } from "@/components/ivaldi/RunConsole";
import { RUN_STATUS_CLASS } from "@/components/ivaldi/RunDrawer";
import { RunSteps } from "@/components/ivaldi/RunSteps";
import { cn } from "@/lib/utils";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useRunStore } from "@/stores/useRunStore";
import { applyTheme, storedTheme } from "@/stores/useUiStore";
import { useValidationStore } from "@/stores/useValidationStore";

export const Route = createFileRoute("/runner/$blueprintId")({
  head: () => ({
    meta: [
      { title: "Cluster runner — Ivaldi" },
      {
        name: "description",
        content:
          "Run a Gimlé blueprint: topology.yaml and manifests are sent to the same-origin Ivaldi backend and the console streams back live.",
      },
      { property: "og:title", content: "Cluster runner — Ivaldi" },
      {
        property: "og:description",
        content: "Stream the live console of a local Gimlé cluster run.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: RunnerPage,
});

function RunnerPage() {
  const { blueprintId } = useParams({ from: "/runner/$blueprintId" });
  const blueprint = useBlueprintStore((s) => s.blueprint);
  const load = useBlueprintStore((s) => s.load);
  const ivaldiProblems = useValidationStore((s) => s.problems);
  const hilmirProblems = useValidationStore((s) => s.serverProblems);
  const problems = [...ivaldiProblems, ...hilmirProblems];
  const {
    status,
    steps,
    endpoints,
    artifacts,
    log,
    request,
    health,
    reason,
    busy,
    runId,
    transport,
    checkHealth,
    attach,
    start,
    stop,
    clearLog,
  } = useRunStore();

  useEffect(() => {
    applyTheme(storedTheme());
  }, []);

  useEffect(() => {
    load(blueprintId);
  }, [blueprintId, load]);

  useEffect(() => {
    void checkHealth();
    // A run outlives this page: pick up whatever the backend is still holding.
    void attach();
  }, [checkHealth, attach]);

  const errorCount = problems.filter((p) => p.severity === "error").length;
  const active = status !== "idle" && status !== "failed";
  // Not `active`: a failed run can still own a live process tree, and Stop is the only way
  // to tear it down from here.
  const canStop = Boolean(runId) && status !== "idle";

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
            <span className="font-mono text-[13px] font-semibold text-foreground">
              {blueprint.name}
            </span>
            <span className="num text-[11px] text-muted-foreground">{blueprint.version}</span>
            <span
              className={cn(
                "rounded-sm px-2 py-0.5 font-mono text-[11px] uppercase tracking-widest",
                RUN_STATUS_CLASS[status],
              )}
            >
              {status}
            </span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Link
            to="/designer/$blueprintId"
            params={{ blueprintId: blueprint.id }}
            className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2 font-mono text-[11px] text-foreground hover:bg-accent"
          >
            <ArrowLeft className="size-3" /> Designer
          </Link>
          <button
            onClick={clearLog}
            className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2 font-mono text-[11px] text-foreground hover:bg-accent"
          >
            <RotateCcw className="size-3" /> Clear console
          </button>
          <button
            disabled={errorCount > 0 || active || busy}
            onClick={() => void start(blueprint)}
            className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-primary bg-primary px-2 font-mono text-[11px] text-primary-foreground disabled:opacity-40"
          >
            <Play className="size-3" /> Run
          </button>
          <button
            disabled={!canStop || busy}
            onClick={() => void stop()}
            className="inline-flex h-7 items-center gap-1.5 rounded-sm border border-border px-2 font-mono text-[11px] text-foreground disabled:opacity-40"
          >
            <Square className="size-3" /> Stop
          </button>
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        <aside className="w-[320px] shrink-0 space-y-4 overflow-auto border-r border-border bg-sidebar p-3">
          <section>
            <ClusterPicker />
          </section>

          <section>
            <div className="hud-label">Runner</div>
            <dl className="mt-1 space-y-0.5 font-mono text-[11px]">
              <div className="flex justify-between gap-2">
                <dt className="text-muted-foreground">transport</dt>
                <dd>{transport.mode}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-muted-foreground">endpoint</dt>
                <dd className="truncate">{transport.baseUrl ?? "in-browser"}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-muted-foreground">health</dt>
                <dd className={health?.ok ? "text-status-ok" : "text-status-bad"}>
                  {health ? (health.ok ? `ok ${health.version ?? ""}` : "unreachable") : "checking"}
                </dd>
              </div>
            </dl>
            {health?.message && (
              <p className="mt-1 text-[10px] text-muted-foreground">{health.message}</p>
            )}
            {transport.mode === "mock" && (
              <p className="mt-1 text-[10px] text-status-warn">
                Set VITE_RUNNER_API_URL to point Ivaldi at a real local runner daemon.
              </p>
            )}
          </section>

          <section>
            <div className="hud-label">Payload</div>
            {request ? (
              <ul className="mt-1 space-y-0.5 font-mono text-[11px]">
                {request.files.map((file) => (
                  <li key={file.path} className="flex justify-between gap-2">
                    <span className="truncate">{file.path}</span>
                    <span className="num shrink-0 text-muted-foreground">
                      {file.content.split("\n").length}L
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-1 text-[10px] text-muted-foreground">
                topology.yaml and manifests are sent when the run starts.
              </p>
            )}
          </section>

          <section>
            <div className="hud-label">Steps</div>
            <div className="mt-1">
              <RunSteps steps={steps} />
            </div>
          </section>

          <section>
            <div className="hud-label">Artifacts</div>
            <RunArtifacts artifacts={artifacts} />
          </section>

          <section>
            <div className="hud-label">Endpoints</div>
            {endpoints.length === 0 ? (
              <p className="mt-1 text-[10px] text-muted-foreground">Available once running.</p>
            ) : (
              <ul className="mt-1 space-y-0.5">
                {endpoints.map((e) => (
                  <li key={e.url}>
                    <a
                      href={e.url}
                      target="_blank"
                      rel="noreferrer"
                      className="font-mono text-[11px] text-primary underline-offset-2 hover:underline"
                    >
                      {e.label}: {e.url}
                    </a>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {(errorCount > 0 || reason) && (
            <p className="font-mono text-[10px] text-status-bad">
              {reason ??
                `${errorCount} error${errorCount === 1 ? "" : "s"} must be fixed before running.`}
            </p>
          )}
        </aside>

        <main className="flex min-w-0 flex-1 flex-col">
          <div className="flex items-center justify-between border-b border-border px-3 py-1">
            <span className="hud-label">Console</span>
            <span className="num text-[10px] text-muted-foreground">{log.length} lines</span>
          </div>
          <RunConsole log={log} className="min-h-0 flex-1" />
        </main>
      </div>
    </div>
  );
}
