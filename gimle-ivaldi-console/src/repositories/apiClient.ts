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

async function request(path: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(path, {
    ...init,
    headers: {
      accept: "application/json",
      ...(init?.body ? { "content-type": "application/json" } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ApiError(res.status, text || `${init?.method ?? "GET"} ${path} failed (${res.status})`);
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
