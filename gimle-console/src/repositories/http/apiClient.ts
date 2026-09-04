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

/** What an expired (or absent) session reads as to an operator. Deliberately a whole sentence in
 * plain language rather than a status line: this is the string that reaches any screen which does
 * nothing with a failure but show its message. */
export const SESSION_EXPIRED_MESSAGE = "Your session has expired. Sign in again to continue.";

/**
 * The 401 every request can fail with once a session lapses. It keeps ApiError's status and body
 * (so the `instanceof ApiError && status === 401` checks elsewhere still hold) but replaces the
 * technical message: a lapsed session is not a control-plane malfunction to report verbatim, it is
 * a thing that happens to every operator eventually and has exactly one remedy.
 */
export class SessionExpiredError extends ApiError {
  constructor(body: string) {
    super(401, body);
    this.message = SESSION_EXPIRED_MESSAGE;
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
    // Thrown here rather than left to each wrapper below so the whole app has one 401 error type:
    // notifyApiError() suppresses its toast (the sign-in screen carries the explanation instead),
    // and a call site that knows nothing but how to show `error.message` still shows a sentence an
    // operator can act on rather than a status line.
    throw new SessionExpiredError(await res.text());
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

/**
 * Like {@link requestOk}, but also returns any {@code X-Gimle-Warning} response header the control
 * plane attached (e.g. `ServiceAdvisories`' own Service-overlap warning) -- a 2xx isn't necessarily
 * a clean save, and gimle-cli's own `ManifestFiles#printWarnings` already surfaces this header for
 * exactly that reason. `null` when the header is absent. The Fetch `Headers` object joins repeated
 * same-name headers into one comma-separated string (there is no way to recover the original list
 * from a plain `Response`), so more than one attached warning still reaches the caller, just as a
 * single combined string rather than a list.
 */
export async function requestOkWithWarning(
  method: string,
  path: string,
  body?: unknown,
): Promise<string | null> {
  const init: RequestInit & { method: string } = { method };
  if (body !== undefined) {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(body);
  }
  const res = await send(init, path);
  if (!res.ok) throw new ApiError(res.status, await res.text());
  return res.headers.get("x-gimle-warning");
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

/** `?tenant=<id>` suffix for a by-name GET/DELETE route on a tenant-scoped-by-name resource
 * (Deployment/Job/CronJob/DaemonSet/StatefulSet/Service/NetworkPolicy) -- omitted entirely when no
 * tenantId is known, which resolves to the untenanted namespace exactly as it did before these
 * resources were keyed by (tenantId, name) instead of bare name. */
export function tenantQuery(tenantId?: string | null): string {
  return tenantId ? `?tenant=${encodeURIComponent(tenantId)}` : "";
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
