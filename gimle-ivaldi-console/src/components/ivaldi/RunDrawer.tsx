import { Link } from "@tanstack/react-router";
import { ExternalLink, Play, Square } from "lucide-react";

import type { Blueprint } from "@/lib/blueprint";
import { cn } from "@/lib/utils";
import { useRunStore } from "@/stores/useRunStore";
import { useValidationStore } from "@/stores/useValidationStore";

import { ClusterPicker } from "./ClusterPicker";
import { RunArtifacts } from "./RunArtifacts";
import { RunConsole } from "./RunConsole";
import { RunSteps } from "./RunSteps";

export const RUN_STATUS_CLASS: Record<string, string> = {
  idle: "bg-status-muted/20 text-status-muted",
  validating: "bg-status-info-bg text-status-info",
  booting: "bg-status-info-bg text-status-info",
  seeding: "bg-status-warn-bg text-status-warn",
  deploying: "bg-status-info-bg text-status-info",
  running: "bg-status-ok-bg text-status-ok",
  stopping: "bg-status-warn-bg text-status-warn",
  failed: "bg-status-bad-bg text-status-bad",
};

export function RunDrawer({ blueprint }: { blueprint: Blueprint }) {
  const { status, log, steps, endpoints, artifacts, reason, busy, start, stop } = useRunStore();
  const ivaldiProblems = useValidationStore((s) => s.problems);
  const hilmirProblems = useValidationStore((s) => s.serverProblems);
  const errorCount = [...ivaldiProblems, ...hilmirProblems].filter(
    (p) => p.severity === "error",
  ).length;

  const active = status !== "idle" && status !== "failed";

  return (
    <div className="flex h-full">
      <div className="w-[300px] shrink-0 space-y-3 overflow-auto border-r border-border bg-sidebar p-3">
        <div className="flex items-center justify-between">
          <div>
            <div className="hud-label">Status</div>
            <span
              className={cn(
                "mt-1 inline-block rounded-sm px-2 py-0.5 font-mono text-[11px] uppercase tracking-widest",
                RUN_STATUS_CLASS[status],
              )}
            >
              {status}
            </span>
          </div>
          <Link
            to="/runner/$blueprintId"
            params={{ blueprintId: blueprint.id }}
            className="inline-flex items-center gap-1 font-mono text-[10px] text-primary hover:underline"
          >
            Runner page <ExternalLink className="size-3" />
          </Link>
        </div>
        <ClusterPicker />
        <div className="flex gap-2">

          <button
            disabled={errorCount > 0 || active || busy}
            onClick={() => void start(blueprint)}
            className="inline-flex h-7 flex-1 items-center justify-center gap-1.5 rounded-sm border border-primary bg-primary px-2 font-mono text-[11px] text-primary-foreground disabled:opacity-40"
          >
            <Play className="size-3" /> Start
          </button>
          <button
            disabled={!active || busy}
            onClick={() => void stop()}
            className="inline-flex h-7 flex-1 items-center justify-center gap-1.5 rounded-sm border border-border px-2 font-mono text-[11px] text-foreground disabled:opacity-40"
          >
            <Square className="size-3" /> Stop
          </button>
        </div>
        {(errorCount > 0 || reason) && (
          <p className="font-mono text-[10px] text-status-bad">
            {reason ??
              `${errorCount} error${errorCount === 1 ? "" : "s"} must be fixed before running.`}
          </p>
        )}
        <div>
          <div className="hud-label">Steps</div>
          <div className="mt-1">
            <RunSteps steps={steps} />
          </div>
        </div>
        <div>
          <div className="hud-label">Artifacts</div>
          <RunArtifacts artifacts={artifacts} />
        </div>
        <div>
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
        </div>
      </div>
      <RunConsole log={log} className="flex-1" />
    </div>
  );
}
