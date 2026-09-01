import { useMemo, useState } from "react";
import { Panel } from "@/components/page-shell";
import { StatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { fmtRelativeTime } from "@/lib/format";
import {
  boundTimeline,
  eventKindTone,
  failureCount,
  INSTANCE_EVENT_PAGE,
  orderNewestFirst,
} from "@/lib/instance-events";
import type { InstanceEvent } from "@/types";

/**
 * An instance's own lifecycle timeline -- the "why did this instance restart" panel on the
 * instance detail page. Newest-first, bounded to {@link INSTANCE_EVENT_PAGE} rows until an
 * operator expands it: `GET /events` answers with the instance's whole retained timeline and takes
 * no limit of its own, so an instance that has been crash-looping for days would otherwise render
 * as an unbounded wall of rows with its most recent transition somewhere at the top of it.
 */
export function InstanceEventsPanel({
  events,
  loading,
  loaded,
  error,
}: {
  events: InstanceEvent[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
}) {
  const [expanded, setExpanded] = useState(false);
  const ordered = useMemo(() => orderNewestFirst(events), [events]);
  const { visible, hiddenCount } = boundTimeline(ordered, expanded);
  const failures = failureCount(ordered);

  return (
    <Panel
      title="Lifecycle events"
      className="mb-6"
      aside={
        loaded && ordered.length > 0 ? (
          <span className="text-[10px] text-muted-foreground">
            {ordered.length} event{ordered.length === 1 ? "" : "s"}
            {failures > 0 && (
              <span className="text-status-bad">
                {" "}
                · {failures} failed transition{failures === 1 ? "" : "s"}
              </span>
            )}
          </span>
        ) : undefined
      }
    >
      {error ? (
        <p className="p-3 text-xs text-status-bad">Could not load lifecycle events: {error}</p>
      ) : loading ? (
        <p className="p-3 text-xs text-muted-foreground">Loading lifecycle events…</p>
      ) : ordered.length === 0 ? (
        <p className="p-3 text-xs text-muted-foreground">
          No lifecycle events recorded for this instance.
        </p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="bg-muted/50 text-muted-foreground">
                <tr className="text-left">
                  <th className="px-2 py-1.5 font-medium w-40">Transition</th>
                  <th className="px-2 py-1.5 font-medium">What happened</th>
                  <th className="px-2 py-1.5 font-medium w-56">When</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((e) => (
                  <tr key={e.id} className="border-t border-border align-top">
                    <td className="px-2 py-1.5">
                      <StatusBadge variant={eventKindTone(e.kind)}>{e.kind}</StatusBadge>
                    </td>
                    <td className="px-2 py-1.5">
                      <div>{e.message}</div>
                      {e.causeSummary && (
                        <div className="mt-1 rounded border border-status-bad/30 bg-status-bad-bg px-2 py-1 font-mono text-[10px] text-status-bad break-all">
                          {e.causeSummary}
                        </div>
                      )}
                    </td>
                    <td className="px-2 py-1.5 font-mono text-muted-foreground whitespace-nowrap">
                      {new Date(e.occurredAtEpochMilli).toLocaleString()}
                      <span className="ml-2 text-[10px]">
                        {fmtRelativeTime(new Date(e.occurredAtEpochMilli).toISOString())}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {(hiddenCount > 0 || expanded) && (
            <div className="border-t border-border p-2">
              <Button
                size="sm"
                variant="outline"
                className="h-6 px-2 text-[10px]"
                onClick={() => setExpanded(!expanded)}
              >
                {expanded
                  ? `Show only the latest ${INSTANCE_EVENT_PAGE}`
                  : `Show ${hiddenCount} older`}
              </Button>
            </div>
          )}
          <p className="border-t border-border px-2 py-1.5 text-[10px] text-muted-foreground">
            The control plane retains a bounded window of transitions per instance -- transitions
            older than that window are no longer available here.
          </p>
        </>
      )}
    </Panel>
  );
}
