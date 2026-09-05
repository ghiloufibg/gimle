import { Check } from "lucide-react";
import { toast } from "sonner";

import type { Blueprint } from "@/lib/blueprint";
import { artifactsFromLog, hasJarWorkloads } from "@/lib/runArtifacts";
import type { RunLogLine } from "@/repositories";

/**
 * Jar-sourced workloads the backend reported as pushed, derived from the run
 * console rather than assumed: the backend emits no artifact event.
 */
export function RunArtifacts({
  log,
  blueprint,
}: {
  log: RunLogLine[];
  blueprint: Blueprint | null;
}) {
  const artifacts = artifactsFromLog(log);

  if (!artifacts.length)
    return (
      <p className="mt-1 text-[10px] text-muted-foreground">
        {hasJarWorkloads(blueprint)
          ? "Available once running."
          : "No jar-sourced workloads in this blueprint."}
      </p>
    );

  return (
    <ul className="mt-1 space-y-1">
      {artifacts.map((a) => (
        <li
          key={`${a.moduleId}@${a.version}|${a.path}`}
          className="rounded-sm border border-border/70 px-2 py-1"
        >
          <div className="flex items-center gap-1.5">
            <Check className="size-3 text-status-ok" />
            <button
              onClick={() => {
                const id = `${a.moduleId}@${a.version}`;
                void navigator.clipboard.writeText(id);
                toast.success("Artifact copied", { description: id });
              }}
              title={`${a.moduleId}@${a.version}`}
              className="min-w-0 truncate text-left font-mono text-[11px] text-foreground hover:underline"
            >
              {a.moduleId}
              <span className="text-muted-foreground">@{a.version}</span>
            </button>
          </div>
          {a.path && (
            <div className="num mt-0.5 truncate pl-[18px] text-[10px] text-muted-foreground">
              {a.path}
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
