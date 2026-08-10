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

type UnauthorizedHandler = () => void;

let unauthorizedHandler: UnauthorizedHandler | null = null;

/** Injected by the auth store; apiClient must never import a store (circular graph). */
export function setUnauthorizedHandler(handler: UnauthorizedHandler) {
  unauthorizedHandler = handler;
}

function parseRetryAfter(response: Response): number | undefined {
  const raw = response.headers.get("Retry-After");
  if (!raw) return undefined;
  const seconds = Number.parseInt(raw, 10);
  return Number.isFinite(seconds) ? seconds : undefined;
}

async function toError(response: Response): Promise<ApiError> {
  let body = "";
  try {
    body = (await response.text()).trim();
  } catch {
    body = "";
  }
  const retryAfter = parseRetryAfter(response);
  let message = body;
  // Never surface an HTML error document (proxy/gateway pages) as an operator message.
  if (message.startsWith("<") || message.length > 200) message = "";
  if (response.status === 429) {
    message = retryAfter
      ? `too many attempts, try again in ${retryAfter}s`
      : "too many attempts, slow down";
  } else if (response.status === 403) {
    message = body || "not permitted";
  } else if (!message) {
    message = `request failed (${response.status})`;
  }
  return new ApiError(response.status, message, retryAfter);
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const response = await fetch(path, { credentials: "include", ...init });
  if (response.ok) return response;
  const error = await toError(response);
  if (response.status === 401) unauthorizedHandler?.();
  throw error;
}

export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await send(path, init);
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
    body: JSON.stringify(body ?? {}),
  });
  return (await response.json()) as T;
}

export async function requestOk(path: string, init: RequestInit = {}): Promise<void> {
  await send(path, init);
}
