import { vi } from "vitest";

/**
 * Shared fetch-stubbing helpers for the Http*Repository test suites. Real `Response` objects
 * (Node's built-in `fetch`/`Response`), so `apiClient.ts`'s streaming read path (`response.body`)
 * works exactly like it does against a real Saga server.
 */

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function textResponse(text: string, status = 200): Response {
  return new Response(text, { status });
}

/** A chunked NDJSON response, one `ReadableStream` push per line -- exercises the same
 * read-in-pieces path a real chunked-transfer live tail delivers over. */
export function ndjsonStreamResponse(lines: string[]): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const encoder = new TextEncoder();
      for (const line of lines) {
        controller.enqueue(encoder.encode(line + "\n"));
      }
      controller.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "Content-Type": "application/x-ndjson; charset=utf-8" },
  });
}

/**
 * Installs `fetch` as a mock returning each given response in order, one per call -- most tests
 * only need one entry, but multi-call paths (e.g. `getRun`'s parallel meta+events fetch) need one
 * queued per call. Returns the mock so a test can also assert on `fetch.mock.calls` (the exact
 * URL requested).
 */
export function stubFetchSequence(responses: Array<() => Response>): ReturnType<typeof vi.fn> {
  const fn = vi.fn();
  for (const make of responses) {
    fn.mockImplementationOnce(async () => make());
  }
  vi.stubGlobal("fetch", fn);
  return fn;
}
