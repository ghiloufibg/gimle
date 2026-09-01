import type { ModuleInstance, ProcessTarget, TraceSpanLine } from "@/types";
import type { TracesHistoryRepository } from "@/repositories/tracesHistory";

/**
 * Following one trace across processes, without a backend trace-search API.
 *
 * GET /traces-history/{processKind}/{processId} only ever answers for one process, so "show me
 * every span of trace X" has to be assembled client-side: query each process this console can
 * actually name, keep the spans carrying that trace id, and be explicit about which processes were
 * asked and which of them failed. What the result cannot claim is completeness -- see
 * {@link TraceFollowResult.searched} and the caveats the UI renders beside it.
 */

/** One span plus the process whose history it was found in. */
export interface LocatedSpan {
  span: TraceSpanLine;
  target: ProcessTarget;
}

export interface TraceFollowResult {
  traceId: string;
  spans: LocatedSpan[];
  /** Every process actually queried, in the order they were queried. */
  searched: ProcessTarget[];
  /** Processes whose history could not be read at all, with the reason. */
  failures: Array<{ target: ProcessTarget; message: string }>;
  /**
   * Spans naming a parent span that is not among {@link spans}. Non-empty means the trace is
   * provably incomplete here -- the missing hop ran somewhere this search could not reach, or
   * scrolled out of that process' loaded window.
   */
  danglingParentSpanIds: string[];
}

export function processTargetKey(target: ProcessTarget): string {
  return `${target.processKind}:${target.processId}`;
}

/**
 * The WORKER process targets this console can name right now, derived from the instance list it
 * already loads. Workers are the only span producers in the cluster today (the fabric server is
 * the sole place spans are created), so this is the whole searchable surface -- an instance whose
 * worker has not finished its handshake yet reports no workerId and simply isn't searchable.
 */
export function workerTargetsFromInstances(instances: ModuleInstance[]): ProcessTarget[] {
  const byKey = new Map<string, ProcessTarget>();
  for (const instance of instances) {
    if (!instance.workerId) continue;
    const target: ProcessTarget = {
      processKind: "WORKER",
      processId: `${instance.nodeId}:${instance.workerId}`,
    };
    byKey.set(processTargetKey(target), target);
  }
  return [...byKey.values()].sort((a, b) => a.processId.localeCompare(b.processId));
}

export interface FollowTraceOptions {
  /** Spans requested per page from each process. */
  pageSize?: number;
  /**
   * How many pages deep to walk back through each process' history. Bounded on purpose: this is N
   * sequential requests per process with no server-side filter, so an unbounded walk would hammer
   * the proxy to answer one operator's click.
   */
  maxPagesPerTarget?: number;
}

/**
 * Collects every span of {@code traceId} the given processes can be made to reveal. One process
 * failing never fails the whole search -- its reason is reported alongside the spans that were
 * found, because a partial trace an operator knows is partial beats no answer at all.
 */
export async function followTraceAcrossProcesses(
  repo: TracesHistoryRepository,
  traceId: string,
  targets: ProcessTarget[],
  options: FollowTraceOptions = {},
): Promise<TraceFollowResult> {
  const pageSize = options.pageSize ?? 200;
  const maxPages = options.maxPagesPerTarget ?? 3;
  const spans: LocatedSpan[] = [];
  const searched: ProcessTarget[] = [];
  const failures: Array<{ target: ProcessTarget; message: string }> = [];

  for (const target of targets) {
    searched.push(target);
    let cursor: string | null = null;
    for (let page = 0; page < maxPages; page++) {
      try {
        const envelope = await repo.fetchPage({ target, cursor, limit: pageSize });
        for (const span of envelope.lines) {
          if (span.traceId === traceId) spans.push({ span, target });
        }
        if (envelope.olderCursor === null || envelope.lines.length === 0) break;
        cursor = envelope.olderCursor;
      } catch (e) {
        failures.push({ target, message: (e as Error).message });
        break;
      }
    }
  }

  spans.sort((a, b) => a.span.timestamp.localeCompare(b.span.timestamp));
  return {
    traceId,
    spans,
    searched,
    failures,
    danglingParentSpanIds: danglingParents(spans),
  };
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
  const reachable = result.searched.length - result.failures.length;
  const parts = [
    `${result.spans.length} span${result.spans.length === 1 ? "" : "s"} found`,
    `${reachable}/${result.searched.length} worker process${result.searched.length === 1 ? "" : "es"} read`,
  ];
  if (result.failures.length > 0) {
    parts.push(`${result.failures.length} unreachable`);
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
