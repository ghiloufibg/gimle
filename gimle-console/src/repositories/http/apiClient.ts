// The one place that knows the wire format (bare JSON/text, no envelope) and the Raft leader
// redirect (a 307 with {"error":"not-leader","leaderRaftId":?,"leaderApiAddress":?} instead of a
// Location header round-trip fetch would follow correctly on its own).

let unauthorizedHandler: (() => void) | null = null;

/** Registered once by useAuthStore.ts, not imported here directly -- importing the store from
 * this file would create a circular module graph (apiClient -> useAuthStore -> repositories'
 * composition root -> every Http*Repository, including this file, before its own exports finish
 * evaluating), which breaks at runtime with "X is not a constructor". A plain callback avoids the
 * cycle: this file has no dependency on the store at all, only an injection point. */
export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: string,
  ) {
    super(`control plane responded ${status}${body ? `: ${body}` : ""}`);
  }
}

interface NotLeaderBody {
  error?: string;
  leaderRaftId?: string | null;
  leaderApiAddress?: string | null;
}

async function send(init: RequestInit & { method: string }, path: string): Promise<Response> {
  const res = await fetch(path, init);
  if (res.status === 401) {
    // Centralized here so every existing repository call site gets this for free with zero
    // changes of its own: a session expiring mid-use (or never having existed) clears local auth
    // state, and the root route guard reacts to that status change by redirecting to /login. 403
    // needs no equivalent hook -- it already surfaces correctly through each store's existing
    // catch-and-set-error pattern, since the caller is legitimately logged in, just lacks that
    // permission.
    unauthorizedHandler?.();
  }
  if (res.status !== 307) return res;

  let redirectBody: NotLeaderBody | null = null;
  try {
    redirectBody = (await res.clone().json()) as NotLeaderBody;
  } catch {
    // fall through: no leaderApiAddress to retry against
  }
  if (!redirectBody?.leaderApiAddress) return res;

  // One retry, no loop guard needed: a second 307 means the cluster is between elections, and
  // that should surface as an error, not retry forever.
  const redirectUrl = new URL(path, `http://${redirectBody.leaderApiAddress}`).toString();
  return fetch(redirectUrl, init);
}

/** For GET endpoints, all of which return a bare JSON object or array (no envelope). */
export async function requestJson<T>(method: string, path: string): Promise<T> {
  const res = await send({ method }, path);
  if (!res.ok) throw new ApiError(res.status, await res.text());
  return res.json() as Promise<T>;
}

/** For JSON-bodied write endpoints (PUT/DELETE), which return the literal text "ok" on success --
 * the caller only cares whether the request succeeded, not the body. */
export async function requestOk(method: string, path: string, body?: unknown): Promise<void> {
  const init: RequestInit & { method: string } = { method };
  if (body !== undefined) {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(body);
  }
  const res = await send(init, path);
  if (!res.ok) throw new ApiError(res.status, await res.text());
}

/** For JSON-bodied endpoints that themselves return a JSON body, not the literal "ok" every write
 * endpoint above returns -- currently only /auth/login, which returns the logged-in Principal. */
export async function requestJsonWithBody<T>(
  method: string,
  path: string,
  body: unknown,
): Promise<T> {
  const res = await send(
    { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) },
    path,
  );
  if (!res.ok) throw new ApiError(res.status, await res.text());
  return res.json() as Promise<T>;
}

/** PUT with a raw YAML body -- only used for deployment manifests (http/deployments.ts), which the
 * control plane parses with SnakeYAML, not JSON. */
export async function requestOkYaml(path: string, yaml: string): Promise<void> {
  const res = await send(
    { method: "PUT", headers: { "Content-Type": "application/x-yaml" }, body: yaml },
    path,
  );
  if (!res.ok) throw new ApiError(res.status, await res.text());
}
