import type { Blueprint, Problem } from "@/lib/blueprint";
import { cn } from "@/lib/utils";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useValidationStore } from "@/stores/useValidationStore";

function severityClass(severity: Problem["severity"]): string {
  if (severity === "error") return "text-status-bad";
  if (severity === "warning") return "text-status-warn";
  return "text-status-info";
}

function targetLabel(blueprint: Blueprint, problem: Problem): string {
  if (!problem.nodeId) return "blueprint";
  const node = blueprint.nodes.find((n) => n.id === problem.nodeId);
  if (!node) return problem.nodeId;
  const d = node.data as unknown as Record<string, unknown>;
  const name = (d.name ?? d.id ?? d.nodeId ?? d.key ?? node.kind) as string;
  return `${node.kind}/${name}`;
}

export function ProblemsDrawer({ blueprint }: { blueprint: Blueprint }) {
  const ivaldiProblems = useValidationStore((s) => s.problems);
  const hilmirProblems = useValidationStore((s) => s.serverProblems);
  const hilmir = useValidationStore((s) => s.hilmir);
  const validateWithHilmir = useValidationStore((s) => s.validateWithHilmir);
  const select = useBlueprintStore((s) => s.select);
  const selectedId = useBlueprintStore((s) => s.selectedId);

  const rows = [
    ...ivaldiProblems.map((p) => ({ p, source: "ivaldi" as const })),
    ...hilmirProblems.map((p) => ({ p, source: "hilmir" as const })),
  ];

  const header = (
    <div className="flex shrink-0 items-center justify-between border-b border-border px-3 py-1.5">
      <span className="hud-label">
        {rows.length} problem{rows.length === 1 ? "" : "s"} — ivaldi {ivaldiProblems.length} / hilmir{" "}
        {hilmirProblems.length}
      </span>
      <div className="flex items-center gap-2">
        <span className="font-mono text-[10px] text-muted-foreground">
          {hilmir.error
            ? hilmir.error
            : hilmir.report
              ? `${hilmir.report.validator}${hilmir.report.version ? ` ${hilmir.report.version}` : ""} · ${new Date(hilmir.report.checkedAt).toLocaleTimeString()}`
              : `hilmir ${hilmir.mode}${hilmir.baseUrl ? ` · ${hilmir.baseUrl}` : ""} · not run`}
        </span>
        <button
          disabled={hilmir.running}
          onClick={() => void validateWithHilmir(blueprint)}
          className="inline-flex h-6 items-center gap-1 rounded-sm border border-border px-2 font-mono text-[10px] text-foreground hover:border-primary disabled:opacity-40"
        >
          {hilmir.running ? "Validating…" : "Validate with Hilmir"}
        </button>
      </div>
    </div>
  );

  return (
    <div className="flex h-full flex-col">
      {header}
      {rows.length === 0 ? (
        <div className="flex flex-1 items-center justify-center">
          <div className="text-center">
            <div className="hud-label">No problems</div>
            <p className="mt-1 text-xs text-muted-foreground">This blueprint validates clean.</p>
          </div>
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-auto">
          <table className="w-full border-collapse text-[12px]">
            <thead className="sticky top-0 bg-card">
              <tr className="border-b border-border text-left">
                <th className="hud-label px-3 py-1.5">Severity</th>
                <th className="hud-label px-3 py-1.5">Source</th>
                <th className="hud-label px-3 py-1.5">Code</th>
                <th className="hud-label px-3 py-1.5">Message</th>
                <th className="hud-label px-3 py-1.5">Target</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ p, source }, i) => (
                <tr
                  key={`${source}-${p.code}-${p.nodeId ?? "bp"}-${i}`}
                  onClick={() => p.nodeId && select(p.nodeId)}
                  className={cn(
                    "cursor-pointer border-b border-border/60 hover:bg-accent/40",
                    selectedId && p.nodeId === selectedId && "bg-accent/60",
                  )}
                >
                  <td className={cn("px-3 py-1 font-mono text-[11px] uppercase", severityClass(p.severity))}>
                    {p.severity}
                  </td>
                  <td className="px-3 py-1 font-mono text-[11px] uppercase tracking-widest text-muted-foreground">
                    {source}
                  </td>
                  <td className="px-3 py-1 font-mono text-[11px] text-foreground">{p.code}</td>
                  <td className="px-3 py-1 text-muted-foreground">{p.message}</td>
                  <td className="px-3 py-1 font-mono text-[11px] text-muted-foreground">
                    {p.file ?? targetLabel(blueprint, p)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

