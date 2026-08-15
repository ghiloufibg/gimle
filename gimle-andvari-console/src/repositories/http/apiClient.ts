export class ApiError extends Error {
  readonly status: number;
  readonly retryAfterSeconds?: number | undefined;

  constructor(status: number, message: string, retryAfterSeconds?: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

// Empty by default -- same-origin relative paths, the console being served straight off
// AndvariServer's own /console (see SpaStaticHandler). Only ever set for a dev/test setup that
// points the SPA at a different host than the one serving it.
const API_BASE: string = (import.meta.env["VITE_API_BASE_URL"] as string | undefined) ?? "";

let unauthorizedHandler: (() => void) | null = null;

/** Injected by the auth store; apiClient must never import a store (circular graph). */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

export function apiUrl(path: string): string {
  return `${API_BASE}${path}`;
}

function parseRetryAfter(response: Response): number | undefined {
  const raw = response.headers.get("Retry-After");
  if (!raw) return undefined;
  const seconds = Number.parseInt(raw, 10);
  return Number.isFinite(seconds) ? seconds : undefined;
}

async function safeText(response: Response): Promise<string> {
  try {
    return (await response.text()).trim();
  } catch {
    return "";
  }
}

async function toError(response: Response): Promise<ApiError> {
  const body = await safeText(response);
  const retryAfter = parseRetryAfter(response);
  let message = body;
  // Never surface an HTML error document (proxy/gateway pages) as an operator message.
  if (message.startsWith("<") || message.length > 300) message = "";
  if (response.status === 429) {
    message = retryAfter
      ? `too many attempts, try again in ${retryAfter}s`
      : "too many attempts, slow down";
  } else if (response.status === 403) {
    message = body || "not permitted";
  } else if (!message) {
    message = response.statusText || `request failed (${response.status})`;
  }
  return new ApiError(response.status, message, retryAfter);
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const response = await fetch(apiUrl(path), { credentials: "include", ...init });
  if (response.ok) return response;
  const error = await toError(response);
  if (response.status === 401) unauthorizedHandler?.();
  throw error;
}

export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await send(path, { method: "GET", ...init });
  return (await response.json()) as T;
}

export async function requestJsonWithBody<T>(
  path: string,
  method: string,
  body: unknown,
): Promise<T> {
  const response = await send(path, {
    method,
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return (await response.json()) as T;
}

export async function requestOk(path: string, method: string): Promise<void> {
  await send(path, { method });
}

export async function requestBinaryUpload<T>(
  path: string,
  method: string,
  body: Blob | ArrayBuffer,
): Promise<T> {
  const response = await send(path, {
    method,
    headers: { "content-type": "application/java-archive" },
    body,
  });
  return (await response.json()) as T;
}

export async function requestBinaryDownload(path: string): Promise<Blob> {
  const response = await send(path, { method: "GET" });
  return await response.blob();
}
