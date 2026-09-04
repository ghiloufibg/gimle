import { useEffect, useRef } from "react";

import type { RunLogLine } from "@/repositories";
import { cn } from "@/lib/utils";

const LEVEL_CLASS: Record<RunLogLine["level"], string> = {
  info: "text-foreground",
  warn: "text-status-warn",
  error: "text-status-bad",
};

function time(ts: string): string {
  const d = new Date(ts);
  return Number.isNaN(d.getTime()) ? "--:--:--" : d.toLocaleTimeString("en-GB", { hour12: false });
}

export function RunConsole({ log, className }: { log: RunLogLine[]; className?: string }) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    ref.current?.scrollTo({ top: ref.current.scrollHeight });
  }, [log]);

  return (
    <div ref={ref} className={cn("overflow-auto bg-background p-3 font-mono text-[11px]", className)}>
      {log.length === 0 ? (
        <p className="text-[10px] text-muted-foreground">
          No output yet. Start a run to stream the runner console.
        </p>
      ) : (
        <div className="space-y-0.5">
          {log.map((line) => (
            <div key={line.seq} className="flex gap-2 leading-relaxed">
              <span className="shrink-0 text-muted-foreground">{time(line.ts)}</span>
              <span className="w-[92px] shrink-0 truncate text-status-info">{line.source}</span>
              <span className={cn("min-w-0 break-all", LEVEL_CLASS[line.level])}>{line.text}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
