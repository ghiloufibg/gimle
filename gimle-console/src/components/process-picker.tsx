import { useEffect, useState } from "react";

import { cn } from "@/lib/utils";
import { useNodesStore } from "@/stores/useNodesStore";
import type { ProcessKind, ProcessTarget } from "@/types";

export const PROCESS_KINDS: ProcessKind[] = ["CONTROLPLANE", "FAFNIR", "STORE", "AGENT", "WORKER"];

/**
 * A worker JVM's processId is `{nodeId}:{workerId}` -- workers have no host:port of their own the
 * way ControlPlane/Fafnir/Mimir/Agent do -- split back into its two parts so the picker can offer
 * a real node dropdown (same source as AGENT's) alongside a free-text workerId field, since no
 * discovery API exists to enumerate a node's own workers.
 */
function splitWorkerProcessId(processId: string): [string, string] {
  const colon = processId.indexOf(":");
  return colon < 0 ? [processId, ""] : [processId.slice(0, colon), processId.slice(colon + 1)];
}

/** The inverse of {@link splitWorkerProcessId} -- what a caller who already knows an instance's
 * own nodeId/workerId (e.g. the instance detail page) builds to link straight into this picker's
 * WORKER target, rather than making the operator retype what the platform already reported. */
export function joinWorkerProcessId(nodeId: string, workerId: string): string {
  return `${nodeId}:${workerId}`;
}

/**
 * There is no discovery API for which processIds exist -- every non-AGENT processId is a
 * self-reported {@code host:port} string chosen at that process's own startup (e.g. {@code
 * ControlPlaneMain}'s {@code selfApiAddress}), not a fixed constant, and a
 * cluster can run multiple replicas of each kind. The one processId this console can genuinely
 * know without asking: the control plane serving this console page IS a CONTROLPLANE replica, and
 * `window.location.host` is exactly the address a browser used to reach it -- the same address
 * that replica's own MuninnShipper reports itself under, in the common case where the control
 * plane isn't behind a reverse proxy that rewrites Host. Fafnir/Store have no equivalent trick
 * (the console never talks to them directly), so those default to empty and the operator types
 * the real address in themselves.
 */
function defaultControlPlaneProcessId(): string {
  return typeof window !== "undefined" ? window.location.host : "";
}

export function defaultProcessTarget(): ProcessTarget {
  return { processKind: "CONTROLPLANE", processId: defaultControlPlaneProcessId() };
}

/** Kind row + a processId field: an editable text input for CONTROLPLANE/FAFNIR/STORE (no
 * discovery API exists for these, see above), or a real node picker for AGENT (node ids come from
 * the already-loaded nodes store, which genuinely does enumerate every real node). */
export function ProcessPicker({
  value,
  onChange,
}: {
  value: ProcessTarget;
  onChange: (t: ProcessTarget) => void;
}) {
  const nodes = useNodesStore((s) => s.items);
  const loadNodes = useNodesStore((s) => s.loadFirstPage);
  const [idInput, setIdInput] = useState(value.processId);

  useEffect(() => {
    if (nodes.length === 0) loadNodes();
  }, [nodes.length, loadNodes]);

  // Keep the text input in sync when the kind changes (or a caller resets `value` externally) --
  // it's local state only so keystrokes don't refetch on every character, see the form's onSubmit.
  useEffect(() => {
    setIdInput(value.processId);
  }, [value.processKind, value.processId]);

  function selectKind(kind: ProcessKind) {
    if (kind === "AGENT") {
      const first = nodes[0]?.nodeId;
      if (!first) return;
      onChange({ processKind: "AGENT", processId: first });
    } else if (kind === "WORKER") {
      const first = nodes[0]?.nodeId;
      if (!first) return;
      onChange({ processKind: "WORKER", processId: `${first}:` });
    } else {
      const processId = kind === "CONTROLPLANE" ? defaultControlPlaneProcessId() : "";
      onChange({ processKind: kind, processId });
    }
  }

  const [workerNodeId, workerId] =
    value.processKind === "WORKER" ? splitWorkerProcessId(value.processId) : ["", ""];

  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="hud-label text-muted-foreground">process</span>
      <div className="flex flex-wrap gap-px bg-primary/10">
        {PROCESS_KINDS.map((k) => (
          <button
            key={k}
            type="button"
            onClick={() => selectKind(k)}
            disabled={(k === "AGENT" || k === "WORKER") && nodes.length === 0}
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

      {value.processKind === "AGENT" ? (
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
      ) : value.processKind === "WORKER" ? (
        <div className="flex items-center gap-1">
          <select
            value={workerNodeId}
            onChange={(e) =>
              onChange({ processKind: "WORKER", processId: `${e.target.value}:${workerId}` })
            }
            className="rounded-sm border border-primary/20 bg-background px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-foreground"
            aria-label="Worker node"
          >
            {nodes.map((n) => (
              <option key={n.nodeId} value={n.nodeId}>
                {n.nodeId}
              </option>
            ))}
          </select>
          <input
            value={workerId}
            onChange={(e) =>
              onChange({ processKind: "WORKER", processId: `${workerNodeId}:${e.target.value}` })
            }
            placeholder="worker-1234"
            aria-label="Worker id"
            className="h-6 w-28 rounded-sm border border-primary/20 bg-background px-2 font-mono text-[10px] text-foreground"
          />
        </div>
      ) : (
        <form
          className="flex items-center gap-1"
          onSubmit={(e) => {
            e.preventDefault();
            if (idInput.trim())
              onChange({ processKind: value.processKind, processId: idInput.trim() });
          }}
        >
          <input
            value={idInput}
            onChange={(e) => setIdInput(e.target.value)}
            placeholder="host:port"
            aria-label={`${value.processKind} address`}
            className="h-6 w-36 rounded-sm border border-primary/20 bg-background px-2 font-mono text-[10px] text-foreground"
          />
          <button
            type="submit"
            className="rounded-sm border border-primary/20 bg-primary/10 px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-primary hover:bg-primary/20"
          >
            go
          </button>
        </form>
      )}

      <span className="font-mono text-[10px] text-muted-foreground">
        {value.processKind === "WORKER"
          ? `worker/${workerNodeId || "…"} / ${workerId || "…"}`
          : `${value.processKind.toLowerCase()}/${value.processId || "…"}`}
      </span>
    </div>
  );
}
