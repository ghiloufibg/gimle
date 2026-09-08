/**
 * Tiny fetch wrapper for the gimle-ivaldi backend.
 * Same-origin relative paths only: no base URL, no hardcoded host.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** Long enough for a real slow-but-working validate/deploy call, short enough that a black-holed
 * request still surfaces as a clear error rather than hanging the UI forever. */
export const REQUEST_TIMEOUT_MS = 30_000;

/**
 * Aborts `fetch` itself after {@link REQUEST_TIMEOUT_MS} -- a dropped connection otherwise never
 * resolves or rejects at all, so nothing downstream (a catch block, a `busy` flag) ever runs. A
 * caller-supplied `signal` is respected as-is rather than overridden.
 */
export async function fetchWithTimeout(input: string, init?: RequestInit): Promise<Response> {
  if (init?.signal) return fetch(input, init);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function request(path: string, init?: RequestInit): Promise<Response> {
  let res: Response;
  try {
    res = await fetchWithTimeout(path, {
      ...init,
      headers: {
        accept: "application/json",
        ...(init?.body ? { "content-type": "application/json" } : {}),
        ...(init?.headers ?? {}),
      },
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      throw new ApiError(
        0,
        `${init?.method ?? "GET"} ${path} timed out after ${REQUEST_TIMEOUT_MS}ms`,
      );
    }
    throw e;
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ApiError(
      res.status,
      text || `${init?.method ?? "GET"} ${path} failed (${res.status})`,
    );
  }
  return res;
}

/** Performs a request and parses a JSON body. */
export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await request(path, init);
  return (await res.json()) as T;
}

/** Performs a request that only has to succeed (no body of interest). */
export async function requestOk(path: string, init?: RequestInit): Promise<void> {
  await request(path, init);
}

/** Serializes a JSON body for POST/PUT calls. */
export function jsonBody(value: unknown): string {
  return JSON.stringify(value);
}

/** Connectivity probe against GET /api/health. */
export async function apiHealth(): Promise<boolean> {
  try {
    const body = await requestJson<{ status?: string }>("/api/health");
    return body.status === "ok";
  } catch {
    return false;
  }
}
