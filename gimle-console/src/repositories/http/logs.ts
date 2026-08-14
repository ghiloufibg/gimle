import type { CrashDump, LogLine, LogTarget, Page } from "@/types";
import type { LogsRepository } from "../logs";
import { ApiError } from "./apiClient";

function pathFor(target: LogTarget): string {
  if (target.kind === "instance") {
    return `/logs/instances/${target.deploymentName}/${target.instanceIndex}`;
  }
  if (target.kind === "node") {
    return `/logs/nodes/${target.nodeId}`;
  }
  return "/logs/controlplane";
}

interface RawLogPage {
  lines: LogLine[];
  olderCursor: string | null;
  newerCursor: string | null;
}

/**
 * fetchPage() is a plain GET against the real /logs/... routes.
 * openFollow() polls the same non-streaming route on a backoff interval rather than consuming the
 * backend's chunked follow=true stream directly: java.net.http.HttpClient-based Java consumers
 * (gimle-cli, the control plane's own agent-proxy hop) read that stream via a raw Socket instead of
 * fetch()/HttpClient (see AgentLogServerTest's javadoc for why) -- browsers don't have that
 * problem, but polling here keeps this repository's implementation uniform with every other
 * Http*Repository in this app (plain request/response, no streaming-body handling) rather than
 * being the one exception. Polling uses `since` (not `cursor`): the plain GET route's `cursor`
 * parameter means "page backward from here" (readOlder, what "Load older" needs), which is a
 * different operation from "give me what's new since here" (readAfter) -- confirmed the hard way
 * in a real browser session, where reusing `cursor` here re-fetched (and re-appended) close to the
 * same batch every 1500ms instead of only genuinely new lines. `since` is the backend's distinct,
 * correctly-wired parameter for this.
 */
export class HttpLogsRepository implements LogsRepository {
  async fetchPage({
    target,
    cursor,
    pageSize,
  }: {
    target: LogTarget;
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<LogLine>> {
    const params = new URLSearchParams({ category: target.category, limit: String(pageSize) });
    if (cursor !== null) params.set("cursor", cursor);
    const res = await fetch(`${pathFor(target)}?${params.toString()}`);
    if (!res.ok) throw new ApiError(res.status, await res.text());
    const body = (await res.json()) as RawLogPage;
    return { items: body.lines, nextCursor: body.olderCursor };
  }

  openFollow(target: LogTarget, onLine: (line: LogLine) => void): () => void {
    let stopped = false;
    let cursor: string | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const poll = async () => {
      if (stopped) return;
      try {
        const params = new URLSearchParams({ category: target.category, limit: "200" });
        if (cursor !== null) params.set("since", cursor);
        const res = await fetch(`${pathFor(target)}?${params.toString()}`);
        if (res.ok) {
          const body = (await res.json()) as RawLogPage;
          for (const line of body.lines) {
            onLine(line);
            cursor = (line as { timestamp?: string }).timestamp ?? cursor;
          }
        }
      } catch {
        // transient fetch failure -- just retry on the next tick
      }
      if (!stopped) {
        timer = setTimeout(poll, 1500);
      }
    };

    // Seed the cursor at "now" (the newest line already on disk) so the first poll only ever
    // emits genuinely new lines, matching follow=true's "stream starting from now" semantics --
    // one throwaway fetchPage call, not a second code path.
    this.fetchPage({ target, cursor: null, pageSize: 1 })
      .then((page) => {
        if (page.items.length > 0) {
          cursor = (page.items[page.items.length - 1] as { timestamp?: string }).timestamp ?? null;
        }
      })
      .finally(() => {
        if (!stopped) poll();
      });

    return () => {
      stopped = true;
      if (timer !== null) clearTimeout(timer);
    };
  }

  async listCrashDumps(target: LogTarget): Promise<CrashDump[]> {
    // Only an instance target has a worker JVM to crash -- no backend route exists for node or
    // controlplane targets.
    if (target.kind !== "instance") return [];
    const res = await fetch(`${pathFor(target)}/crashdumps`);
    if (!res.ok) throw new ApiError(res.status, await res.text());
    return (await res.json()) as CrashDump[];
  }
}
