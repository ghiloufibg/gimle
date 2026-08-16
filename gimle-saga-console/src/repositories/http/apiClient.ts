export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function toError(response: Response): Promise<ApiError> {
  let body = "";
  try {
    body = (await response.text()).trim();
  } catch {
    body = "";
  }
  // Never surface an HTML error document (proxy/gateway pages) as an operator message; Saga's
  // own error bodies are always plain text (see SagaServer#respond).
  if (body.startsWith("<") || body.length > 200) body = "";
  return new ApiError(response.status, body || `request failed (${response.status})`);
}

async function send(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(path, init);
  if (response.ok) return response;
  throw await toError(response);
}

export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await send(path, init);
  return (await response.json()) as T;
}

/**
 * Reads a chunked NDJSON response body line by line, calling `onLine` for each complete line as
 * it arrives -- the client side of {@code SagaServer#streamFollow}'s live tail. Resolves once the
 * stream ends (the server closed it, terminal run drained) or `signal` aborts it.
 */
export async function streamNdjsonLines(
  path: string,
  onLine: (line: string) => void,
  signal: AbortSignal,
): Promise<void> {
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    const response = await send(path, { signal });
    const body = response.body;
    if (!body) return;
    const reader = body.getReader();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let newline = buffer.indexOf("\n");
      while (newline !== -1) {
        const line = buffer.slice(0, newline).trim();
        buffer = buffer.slice(newline + 1);
        if (line) onLine(line);
        newline = buffer.indexOf("\n");
      }
    }
  } catch (e) {
    // An aborted fetch (unfollow, or navigating away) rejects the read loop -- the normal way a
    // live tail on a still-live run ends from the client side; anything else propagates.
    if (signal.aborted) return;
    throw e;
  }
}
