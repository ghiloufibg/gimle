// The one place that knows the wire format (bare JSON/text, no envelope) and the Raft leader
// redirect (a 307 with {"error":"not-leader","leaderRaftId":?,"leaderApiAddress":?} instead of a
// Location header round-trip fetch would follow correctly on its own).

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

/** PUT with a raw YAML body -- only used for deployment manifests (http/deployments.ts), which the
 * control plane parses with SnakeYAML, not JSON. */
export async function requestOkYaml(path: string, yaml: string): Promise<void> {
  const res = await send(
    { method: "PUT", headers: { "Content-Type": "application/x-yaml" }, body: yaml },
    path,
  );
  if (!res.ok) throw new ApiError(res.status, await res.text());
}
