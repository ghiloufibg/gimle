import type { InstanceEvent, InstanceEventKind } from "@/types";

/**
 * How many timeline entries the instance detail page renders before an operator explicitly asks
 * for the rest. `GET /events` carries no `limit` parameter -- it returns an instance's whole
 * retained timeline -- so bounding it is entirely the client's job, and a long-lived instance that
 * has restarted repeatedly would otherwise bury its own most recent transitions under scrollback.
 */
export const INSTANCE_EVENT_PAGE = 10;

/** The console's own status vocabulary, so a failed transition never reads like a routine one. */
export type EventTone = "ok" | "warn" | "bad" | "info" | "muted";

/**
 * An entry's tone. `TRANSITION_FAILED` and `LIVENESS_FAILED` are the only two failure kinds an
 * instance timeline records, and they are the whole point of this panel -- they are deliberately
 * the only ones tinted `bad`, so scanning a long timeline for "what went wrong" is a matter of
 * spotting the red rows.
 *
 * `UNINSTALLED` is `muted` rather than `bad` here, unlike the lifecycle-state badge that renders a
 * live instance's current state: in a timeline it is an ordinary terminal transition of a
 * deliberate teardown, not evidence of a problem -- including when it is one step of the restart a
 * `LIVENESS_FAILED` row just above it explains.
 */
export function eventKindTone(kind: InstanceEventKind): EventTone {
  switch (kind) {
    case "TRANSITION_FAILED":
    case "LIVENESS_FAILED":
      return "bad";
    case "ACTIVE":
    case "COMPLETED":
      return "ok";
    case "STARTING":
    case "STOPPING":
      return "warn";
    case "INSTALLED":
    case "RESOLVED":
      return "info";
    case "UNINSTALLED":
      return "muted";
  }
}

/**
 * Newest-first. The control plane already answers in this order, so the sort is a defensive
 * normalization rather than a reordering; it is stable, which matters because two transitions of
 * one instance routinely share a millisecond and must then keep the server's own relative order
 * (the order they were appended in) instead of being shuffled.
 */
export function orderNewestFirst(events: InstanceEvent[]): InstanceEvent[] {
  return [...events].sort((a, b) => b.occurredAtEpochMilli - a.occurredAtEpochMilli);
}

export interface BoundedTimeline {
  visible: InstanceEvent[];
  /** Entries the current bound is holding back; `0` once everything is on screen. */
  hiddenCount: number;
}

/** Applies the page bound to an already-ordered timeline. Expanding shows everything at once. */
export function boundTimeline(
  events: InstanceEvent[],
  expanded: boolean,
  pageSize: number = INSTANCE_EVENT_PAGE,
): BoundedTimeline {
  if (expanded || events.length <= pageSize) {
    return { visible: events, hiddenCount: 0 };
  }
  return { visible: events.slice(0, pageSize), hiddenCount: events.length - pageSize };
}

/**
 * How many failed transitions the whole timeline holds, bound or not -- the panel's headline.
 * `LIVENESS_FAILED` is deliberately not counted: it records the cause of the restart that follows
 * it, not a transition that could not be made.
 */
export function failureCount(events: InstanceEvent[]): number {
  return events.filter((e) => e.kind === "TRANSITION_FAILED").length;
}
