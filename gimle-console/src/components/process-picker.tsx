import { useEffect } from "react";

import { cn } from "@/lib/utils";
import { useNodesStore } from "@/stores/useNodesStore";
import type { ProcessKind, ProcessTarget } from "@/types";

export const PROCESS_KINDS: ProcessKind[] = ["CONTROLPLANE", "FAFNIR", "STORE", "AGENT"];

/** The three singleton logical processes have well-known ids -- there is no discovery API for
 * this, and each cluster runs exactly one of each. */
export const WELL_KNOWN_PROCESS_ID: Record<Exclude<ProcessKind, "AGENT">, string> = {
  CONTROLPLANE: "controlplane",
  FAFNIR: "fafnir",
  STORE: "store",
};

export function defaultProcessTarget(): ProcessTarget {
  return { processKind: "CONTROLPLANE", processId: WELL_KNOWN_PROCESS_ID.CONTROLPLANE };
}

/** Kind row + (for AGENT) node picker. Node ids come from the already-loaded nodes store. */
export function ProcessPicker({
  value,
  onChange,
}: {
  value: ProcessTarget;
  onChange: (t: ProcessTarget) => void;
}) {
  const nodes = useNodesStore((s) => s.items);
  const loadNodes = useNodesStore((s) => s.loadFirstPage);

  useEffect(() => {
    if (nodes.length === 0) loadNodes();
  }, [nodes.length, loadNodes]);

  function selectKind(kind: ProcessKind) {
    if (kind === "AGENT") {
      const first = nodes[0]?.nodeId;
      if (!first) return;
      onChange({ processKind: "AGENT", processId: first });
    } else {
      onChange({ processKind: kind, processId: WELL_KNOWN_PROCESS_ID[kind] });
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="hud-label text-muted-foreground">process</span>
      <div className="flex flex-wrap gap-px bg-primary/10">
        {PROCESS_KINDS.map((k) => (
          <button
            key={k}
            type="button"
            onClick={() => selectKind(k)}
            disabled={k === "AGENT" && nodes.length === 0}
            className={cn(
              "px-2 py-1 font-mono text-[10px] uppercase tracking-widest transition-colors disabled:opacity-40",
              value.processKind === k
                ? "bg-primary/20 text-primary"
                : "bg-background text-muted-foreground hover:text-foreground",
            )}
          >
            {k.toLowerCase()}
          </button>
        ))}
      </div>

      {value.processKind === "AGENT" && (
        <select
          value={value.processId}
          onChange={(e) => onChange({ processKind: "AGENT", processId: e.target.value })}
          className="rounded-sm border border-primary/20 bg-background px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-foreground"
          aria-label="Agent node"
        >
          {nodes.map((n) => (
            <option key={n.nodeId} value={n.nodeId}>
              {n.nodeId}
            </option>
          ))}
        </select>
      )}

      <span className="font-mono text-[10px] text-muted-foreground">
        {value.processKind.toLowerCase()}/{value.processId}
      </span>
    </div>
  );
}
