import { useVirtualizer } from "@tanstack/react-virtual";
import { useRef } from "react";
import type { TestResultEvent } from "@/domain/types";
import { formatDuration } from "@/lib/format";
import { Chip, OutcomeDot, TestIdLabel } from "./primitives";

export interface FeedRow {
  event: TestResultEvent;
  flakyRecovery: boolean;
  key: string;
}

export function RunFeed({ rows }: { rows: FeedRow[] }) {
  const parentRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 34,
    overscan: 12,
  });

  return (
    <div ref={parentRef} className="h-[28rem] overflow-y-auto">
      <div style={{ height: virtualizer.getTotalSize(), position: "relative" }}>
        {virtualizer.getVirtualItems().map((item) => {
          const row = rows[item.index]!;
          const { event } = row;
          return (
            <div
              key={row.key}
              style={{
                position: "absolute",
                top: 0,
                left: 0,
                width: "100%",
                height: item.size,
                transform: `translateY(${item.start}px)`,
              }}
              className="flex items-center gap-2 border-b border-border/50 px-3"
            >
              <OutcomeDot outcome={event.outcome} />
              <div className="min-w-0 flex-1 truncate">
                <TestIdLabel testId={event.testId} />
              </div>
              {event.attempt > 1 ? <Chip tone="info">retry {event.attempt}</Chip> : null}
              {row.flakyRecovery ? <Chip tone="warn">FLAKY</Chip> : null}
              <span className="num w-16 shrink-0 text-right text-[11px] text-muted-foreground">
                {formatDuration(event.durationMillis)}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
