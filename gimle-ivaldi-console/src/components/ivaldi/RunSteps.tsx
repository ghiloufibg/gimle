import { Check, Circle, Loader2, Minus, X } from "lucide-react";

import type { RunStep } from "@/repositories";
import { cn } from "@/lib/utils";

const ICON = {
  pending: Circle,
  running: Loader2,
  ok: Check,
  failed: X,
  skipped: Minus,
} as const;

const CLASS: Record<RunStep["status"], string> = {
  pending: "text-muted-foreground",
  running: "text-status-info",
  ok: "text-status-ok",
  failed: "text-status-bad",
  skipped: "text-status-muted",
};

export function RunSteps({ steps }: { steps: RunStep[] }) {
  if (steps.length === 0)
    return <p className="text-[10px] text-muted-foreground">Steps appear when a run is created.</p>;

  return (
    <ul className="space-y-1">
      {steps.map((step) => {
        const Icon = ICON[step.status];
        return (
          <li key={step.id} className="flex items-start gap-2 font-mono text-[11px]">
            <Icon
              className={cn("mt-0.5 size-3 shrink-0", CLASS[step.status], step.status === "running" && "animate-spin")}
            />
            <span className="min-w-0 flex-1">
              <span className={cn("break-words", CLASS[step.status])}>{step.label}</span>
              {step.detail && <span className="ml-1 text-muted-foreground">{step.detail}</span>}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
