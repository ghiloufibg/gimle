import type {
  CreateRunRequest,
  RunSnapshot,
  RunnerClient,
  RunnerEvent,
  RunnerHealth,
} from "./contracts";

/**
 * Talks to a local runner daemon over plain HTTP + SSE.
 * Wire format (v1):
 *   GET  {base}/v1/health              -> { version }
 *   POST {base}/v1/runs                -> RunSnapshot
 *   GET  {base}/v1/runs/:id/events     -> text/event-stream of RunnerEvent
 *   POST {base}/v1/runs/:id/stop       -> RunSnapshot
 */
export class HttpRunnerClient implements RunnerClient {
  readonly mode = "http" as const;

  constructor(readonly baseUrl: string) {}

  private async json<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: {
        "content-type": "application/json",

        ...(init?.headers ?? {}),
      },
    });
    if (!res.ok) throw new Error(`runner ${res.status}: ${await res.text()}`);
    return (await res.json()) as T;
  }

  async health(): Promise<RunnerHealth> {
    try {
      const body = await this.json<{ version?: string }>("/v1/health");
      return { ok: true, mode: this.mode, version: body.version ?? null, message: null };
    } catch (error) {
      return {
        ok: false,
        mode: this.mode,
        version: null,
        message: error instanceof Error ? error.message : "runner unreachable",
      };
    }
  }

  createRun(request: CreateRunRequest): Promise<RunSnapshot> {
    return this.json<RunSnapshot>("/v1/runs", {
      method: "POST",
      body: JSON.stringify(request),
    });
  }

  subscribe(runId: string, onEvent: (event: RunnerEvent) => void): () => void {
    const source = new EventSource(`${this.baseUrl}/v1/runs/${runId}/events`);
    source.onmessage = (message) => {
      try {
        onEvent(JSON.parse(message.data) as RunnerEvent);
      } catch {
        onEvent({ type: "error", message: "malformed runner event" });
      }
    };
    source.onerror = () => onEvent({ type: "error", message: "runner stream lost" });
    return () => source.close();
  }

  stopRun(runId: string): Promise<RunSnapshot> {
    return this.json<RunSnapshot>(`/v1/runs/${runId}/stop`, { method: "POST" });
  }
}
