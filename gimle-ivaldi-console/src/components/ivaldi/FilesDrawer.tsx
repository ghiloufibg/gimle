import { Copy, ShieldCheck } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";

import type { Blueprint, Problem } from "@/lib/blueprint";
import { renderFiles } from "@/lib/render";
import { cn } from "@/lib/utils";
import { useValidationStore } from "@/stores/useValidationStore";

function severityClass(severity: Problem["severity"]): string {
  if (severity === "error") return "text-status-bad";
  if (severity === "warning") return "text-status-warn";
  return "text-status-info";
}

export function FilesDrawer({ blueprint }: { blueprint: Blueprint }) {
  const files = useMemo(() => renderFiles(blueprint), [blueprint]);
  const [active, setActive] = useState(0);
  const [filter, setFilter] = useState("");
  const file = files[Math.min(active, files.length - 1)];
  const lines = file ? file.content.split("\n") : [];

  const hilmirProblems = useValidationStore((s) => s.serverProblems);
  const hilmir = useValidationStore((s) => s.hilmir);
  const validateWithHilmir = useValidationStore((s) => s.validateWithHilmir);

  const countFor = (path: string) =>
    hilmirProblems.filter((p) => p.file === path && p.severity !== "info").length;
  const fileFindings = hilmirProblems.filter((p) => p.file === file?.path);

  return (
    <div className="flex h-full">
      <div className="flex w-[240px] shrink-0 flex-col border-r border-border bg-sidebar">
        <div className="shrink-0 p-1">
          <input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={`Filter ${files.length} files`}
            className="h-7 w-full rounded-sm border border-border bg-background px-2 font-mono text-[11px] text-foreground outline-none focus:border-primary"
          />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-1">
          {files.map((f, i) => {
            if (filter.trim() && !f.path.toLowerCase().includes(filter.trim().toLowerCase()))
              return null;
            const count = countFor(f.path);
            return (
              <button
                key={f.path}
                onClick={() => setActive(i)}
                className={cn(
                  "flex w-full items-center justify-between gap-2 rounded-sm px-2 py-1 text-left font-mono text-[11px] hover:bg-accent/50",
                  i === active ? "bg-accent text-accent-foreground" : "text-muted-foreground",
                )}
              >
                <span className="truncate">{f.path}</span>
                {count > 0 && (
                  <span className="num shrink-0 rounded-sm bg-status-bad-bg px-1 text-[10px] text-status-bad">
                    {count}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center justify-between gap-3 border-b border-border px-3 py-1.5">
          <span className="font-mono text-[11px] text-foreground">{file?.path}</span>
          <div className="flex items-center gap-2">
            <span className="font-mono text-[10px] text-muted-foreground">
              {hilmir.error
                ? hilmir.error
                : hilmir.report
                  ? `${hilmir.report.validator}${hilmir.report.version ? ` ${hilmir.report.version}` : ""} · ${hilmir.report.ok ? "ok" : "errors"}`
                  : `hilmir ${hilmir.mode}${hilmir.baseUrl ? ` · ${hilmir.baseUrl}` : ""} · not run`}
            </span>
            <button
              disabled={hilmir.running}
              onClick={() => void validateWithHilmir(blueprint)}
              className="inline-flex h-6 items-center gap-1 rounded-sm border border-border px-2 font-mono text-[10px] text-muted-foreground hover:border-primary hover:text-foreground disabled:opacity-40"
            >
              <ShieldCheck className="size-3" />
              {hilmir.running ? "Validating…" : "Validate with Hilmir"}
            </button>
            <button
              onClick={() => {
                void navigator.clipboard.writeText(file?.content ?? "");
                toast.success("Copied", { description: file?.path });
              }}
              className="inline-flex h-6 items-center gap-1 rounded-sm border border-border px-2 font-mono text-[10px] text-muted-foreground hover:border-primary hover:text-foreground"
            >
              <Copy className="size-3" /> Copy
            </button>
          </div>
        </div>
        {fileFindings.length > 0 && (
          <ul className="max-h-[120px] shrink-0 overflow-auto border-b border-border bg-card">
            {fileFindings.map((p, i) => (
              <li key={`${p.code}-${i}`} className="flex gap-2 px-3 py-1 font-mono text-[11px]">
                <span className={cn("uppercase", severityClass(p.severity))}>{p.severity}</span>
                <span className="text-foreground">{p.code}</span>
                <span className="text-muted-foreground">{p.message}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="flex-1 overflow-auto bg-background">
          <pre className="min-w-full font-mono text-[11px] leading-[1.45]">
            {lines.map((line, i) => (
              <div key={i} className="flex">
                <span className="num w-10 shrink-0 select-none border-r border-border/60 px-2 text-right text-muted-foreground">
                  {i + 1}
                </span>
                <span className="whitespace-pre px-3 text-foreground">{line}</span>
              </div>
            ))}
          </pre>
        </div>
      </div>
    </div>
  );
}
