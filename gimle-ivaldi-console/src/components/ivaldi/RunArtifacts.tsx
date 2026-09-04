import { Check, CircleDashed, Loader2, TriangleAlert } from "lucide-react";
import { toast } from "sonner";

import { cn } from "@/lib/utils";
import type { RunArtifact } from "@/repositories";

const STATUS_ICON = {
  pending: CircleDashed,
  uploading: Loader2,
  stored: Check,
  failed: TriangleAlert,
} as const;

const STATUS_CLASS: Record<RunArtifact["status"], string> = {
  pending: "text-status-muted",
  uploading: "text-status-info",
  stored: "text-status-ok",
  failed: "text-status-bad",
};

function size(bytes: number | null): string {
  if (bytes === null) return "—";
  return `${(bytes / 1_048_576).toFixed(1)} MiB`;
}

/** Jar-sourced workloads pushed to the control plane, with their Gimlé artifact IDs. */
export function RunArtifacts({ artifacts }: { artifacts: RunArtifact[] }) {
  if (!artifacts.length)
    return (
      <p className="mt-1 text-[10px] text-muted-foreground">
        No jar-sourced workloads in this blueprint.
      </p>
    );

  return (
    <ul className="mt-1 space-y-1">
      {artifacts.map((a) => {
        const Icon = STATUS_ICON[a.status];
        return (
          <li key={a.workload} className="rounded-sm border border-border/70 px-2 py-1">
            <div className="flex items-center gap-1.5">
              <Icon
                className={cn(
                  "size-3",
                  STATUS_CLASS[a.status],
                  a.status === "uploading" && "animate-spin",
                )}
              />
              <span className="truncate font-mono text-[11px] text-foreground">{a.workload}</span>
              <span className="num ml-auto text-[10px] text-muted-foreground">
                {size(a.sizeBytes)}
              </span>
            </div>
            <div className="mt-0.5 space-y-0.5 pl-[18px]">
              <div className="num truncate text-[10px] text-muted-foreground">
                {a.module}@{a.version} · {a.path}
              </div>
              {a.artifactId ? (
                <button
                  onClick={() => {
                    void navigator.clipboard.writeText(a.artifactId ?? "");
                    toast.success("Artifact ID copied", { description: a.artifactId ?? "" });
                  }}
                  title={`${a.artifactId}\n${a.digest ?? ""}`}
                  className="num block max-w-full truncate text-left text-[10px] text-primary hover:underline"
                >
                  {a.artifactId}
                </button>
              ) : (
                <div className="num text-[10px] text-muted-foreground">
                  {a.status === "failed"
                    ? (a.error ?? "push failed")
                    : `pending push → ${a.server}`}
                </div>
              )}
              {a.digest && (
                <div className="num truncate text-[10px] text-muted-foreground">{a.digest}</div>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
