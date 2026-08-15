import { vi } from "vitest";

/**
 * Shared fetch-stubbing helpers for the Http*Repository test suites. Real `Response` objects
 * (Node's built-in `fetch`/`Response`, not a hand-rolled mock) so `apiClient.ts` works exactly
 * like it does against a real Andvari process, without needing to fake anything separately.
 * Mirrors gimle-fafnir-console's own repositories/http/testUtil.ts.
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

export function okResponse(): Response {
  return textResponse("ok", 200);
}

/**
 * Installs `fetch` as a mock returning each given response in order, one per call -- most tests
 * only need one entry, but multi-call paths need a second response queued for the second call.
 * Returns the mock so a test can also assert on `fetch.mock.calls` (method/URL/body actually
 * sent).
 */
export function stubFetchSequence(responses: Array<() => Response>): ReturnType<typeof vi.fn> {
  const fn = vi.fn();
  for (const make of responses) {
    fn.mockImplementationOnce(async () => make());
  }
  vi.stubGlobal("fetch", fn);
  return fn;
}
