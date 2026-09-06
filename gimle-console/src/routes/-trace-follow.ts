import type { ProcessTarget, TraceSpanLine } from "@/types";
import type { TracesHistoryRepository } from "@/repositories/tracesHistory";

/**
 * Following one trace across the processes it ran in.
 *
 * The control plane answers this in one request, searching every process that has ever shipped
 * traces -- including a worker that has since been replaced and no longer appears in any instance
 * listing. This module turns that answer into the shape the screen renders: located spans, grouped
 * by process and indented into a call tree.
 */

/** One span plus the process whose history it was found in. */
export interface LocatedSpan {
  span: TraceSpanLine;
  target: ProcessTarget;
}

export interface TraceFollowResult {
  traceId: string;
  spans: LocatedSpan[];
  /** The search stopped at its limit, so the trace may carry more spans than these. */
  truncated: boolean;
  /** Why the search could not run at all, or `null` when it ran. */
  failure: string | null;
  /**
   * Spans naming a parent span that is not among {@link spans}. Non-empty means the trace really
   * is incomplete: the search covers every process that has shipped, so a parent missing here was
   * never recorded rather than merely out of reach.
   */
  danglingParentSpanIds: string[];
}

export function processTargetKey(target: ProcessTarget): string {
  return `${target.processKind}:${target.processId}`;
}

export interface TraceFollowResult {
  traceId: string;
  spans: LocatedSpan[];
  /** The search stopped at its limit, so the trace may carry more spans than these. */
  truncated: boolean;
  /** Why the search could not run at all, or `null` when it ran. */
  failure: string | null;
  /**
   * Spans naming a parent span that is not among {@link spans}. Non-empty means the trace really
   * is incomplete: the search covers every process that has shipped, so a parent missing here was
   * never recorded rather than merely out of reach.
   */
  danglingParentSpanIds: string[];
}

/**
 * Collects every span of {@code traceId}. A failure is reported rather than thrown: a search that
 * could not run at all is a different answer from a trace with no spans, and the screen says which.
 */
export async function searchTrace(
  repo: TracesHistoryRepository,
  traceId: string,
): Promise<TraceFollowResult> {
  try {
    const found = await repo.searchByTraceId(traceId);
    const spans: LocatedSpan[] = found.spans.map((hit) => ({
      span: hit.span,
      target: { processKind: hit.processKind, processId: hit.processId },
    }));
    spans.sort((a, b) => a.span.timestamp.localeCompare(b.span.timestamp));
    return {
      traceId,
      spans,
      truncated: found.truncated,
      failure: null,
      danglingParentSpanIds: danglingParents(spans),
    };
  } catch (e) {
    return {
      traceId,
      spans: [],
      truncated: false,
      failure: (e as Error).message,
      danglingParentSpanIds: [],
    };
  }
}

function danglingParents(spans: LocatedSpan[]): string[] {
  const present = new Set(spans.map((s) => s.span.spanId));
  const dangling = new Set<string>();
  for (const { span } of spans) {
    if (span.parentSpanId && !present.has(span.parentSpanId)) dangling.add(span.parentSpanId);
  }
  return [...dangling];
}

/** One process' slice of a followed trace, for rendering the result grouped by where it ran. */
export interface TraceProcessGroup {
  target: ProcessTarget;
  spans: TraceSpanLine[];
}

export function groupSpansByProcess(spans: LocatedSpan[]): TraceProcessGroup[] {
  const groups = new Map<string, TraceProcessGroup>();
  for (const { span, target } of spans) {
    const key = processTargetKey(target);
    const existing = groups.get(key);
    if (existing) existing.spans.push(span);
    else groups.set(key, { target, spans: [span] });
  }
  return [...groups.values()];
}

/**
 * Nesting depth of each span within the followed trace, so the UI can indent a call tree rather
 * than showing a flat list. A span whose parent wasn't found sits at depth 0: it is a root as far
 * as this (knowingly partial) view can tell, and pretending otherwise would invent structure the
 * data doesn't support. Cycles cannot occur in real span data, but the walk is depth-capped anyway
 * so a malformed record can't hang the render.
 */
export function spanDepths(spans: LocatedSpan[]): Map<string, number> {
  const parentOf = new Map<string, string>();
  for (const { span } of spans) {
    if (span.parentSpanId) parentOf.set(span.spanId, span.parentSpanId);
  }
  const present = new Set(spans.map((s) => s.span.spanId));
  const depths = new Map<string, number>();
  for (const { span } of spans) {
    let depth = 0;
    let current = parentOf.get(span.spanId);
    while (current && present.has(current) && depth < spans.length) {
      depth++;
      current = parentOf.get(current);
    }
    depths.set(span.spanId, depth);
  }
  return depths;
}

/**
 * The honest one-line reading of a follow result: what was covered, and what it cannot promise.
 * Rendered verbatim in the UI rather than left to the operator to infer from an empty table.
 */
export function coverageSummary(result: TraceFollowResult): string {
  if (result.failure !== null) {
    return `trace search failed: ${result.failure}`;
  }
  const parts = [`${result.spans.length} span${result.spans.length === 1 ? "" : "s"} found`];
  if (result.truncated) {
    parts.push("truncated at the search limit");
  }
  if (result.danglingParentSpanIds.length > 0) {
    parts.push(
      `${result.danglingParentSpanIds.length} parent span${
        result.danglingParentSpanIds.length === 1 ? "" : "s"
      } not found — this trace is incomplete`,
    );
  }
  return parts.join(" · ");
}
