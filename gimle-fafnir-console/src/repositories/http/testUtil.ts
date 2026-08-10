import { vi } from "vitest";

/**
 * Shared fetch-stubbing helpers for the Http*Repository test suites. Real `Response` objects
 * (Node's built-in `fetch`/`Response`, not a hand-rolled mock) so `apiClient.ts`'s 307-redirect
 * handling -- which calls `res.clone().json()` -- works exactly like it does against a real
 * control plane, without needing to fake that method separately.
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
 * only need one entry, but multi-call paths (e.g. a repository's "not in cache, force a refetch"
 * fallback) need a second response queued for the second call. Returns the mock so a test can also
 * assert on `fetch.mock.calls` (method/URL/body actually sent).
 */
export function stubFetchSequence(responses: Array<() => Response>): ReturnType<typeof vi.fn> {
  const fn = vi.fn();
  for (const make of responses) {
    fn.mockImplementationOnce(async () => make());
  }
  vi.stubGlobal("fetch", fn);
  return fn;
}
