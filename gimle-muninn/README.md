# Gimle Muninn

Muninn is the unified observability sink for the Gimlé cluster: its own process (`MuninnMain`),
one durable destination for the logs, metrics, and traces shipped from every other Gimlé process.
It deliberately holds no `gimle-observability` dependency of its own — it never ships anything
itself, it only ever receives. Every other process (`gimle-agent` on behalf of itself and its
supervised workers, plus `gimle-controlplane`, `gimle-fafnir`, `gimle-mimir`, and `gimle-andvari`
directly) pushes to Muninn via a `MuninnShipper` instance; the relationship never runs the other
way.

## Storage

All three data kinds share one mechanism, `MuninnDayFileStore`: one day-bucketed (UTC),
JSON-lines-per-line file per subtree, e.g. `logs/nodes/<nodeId>/<category>/2026-08-10.log`. It
reuses the JSON-line shape, the `LogPage` result type, and the oldest-first/cursor-by-timestamp
paging semantics that `gimle-core`'s own `LogFileReader` already established for a single node's
local log files — not a new storage engine, and not a literal reuse either: `LogFileReader` tails
one active file plus a small, count-rotated set of `.1..N` copies on one node's own disk, whereas
Muninn accumulates an unbounded, ever-growing history from every shipper in the cluster, which
needs age-based retention instead of count-based. That retention is `RetentionSweeper`: a
background sweep (configurable via `-Dgimle.muninn.retentionDays`, default 30, and
`-Dgimle.muninn.retentionSweepIntervalSeconds`, default one hour) that deletes day files older
than the window.

Ingest itself is best-effort NDJSON over an in-memory shipping cursor on the sending side — not
persisted on Muninn's end, and not on the shipper's either. A shipper process restart re-ships from
"nothing shipped yet," a documented, accepted small-duplicate-window tradeoff rather than the
complexity of a persisted cursor file.

## HTTP surface (`MuninnServer`)

Plain `com.sun.net.httpserver.HttpServer`, the same minimal JDK-bundled stack `ApiServer` and
`FafnirServer` already use — no framework dependency for something this small. Log routes are
structurally the multi-node extension of `gimle-agent`'s own `AgentLogServer`: that class serves
exactly one node's own live files with no `nodeId` in its paths ("this node" is implicit), while
Muninn serves many nodes' and instances' shipped history, so `nodeId`/`deploymentName`/
`instanceIndex` are explicit path segments here.

```
GET  /status                                                     process uptime/transport, no gate
POST /ingest/logs/nodes/{nodeId}/{category}                      NDJSON batch append
POST /ingest/logs/instances/{deploymentName}/{instanceIndex}/{category}
GET  /logs/nodes/{nodeId}/{category}                              paged read (since= or cursor=)
GET  /logs/instances/{deploymentName}/{instanceIndex}/{category}
POST /ingest/metrics/{processKind}/{processId}
GET  /metrics/{processKind}/{processId}
POST /ingest/traces/{processKind}/{processId}
GET  /traces/{processKind}/{processId}
```

A read never accepts `follow=true` — Muninn only ever serves shipped history, never a live tail;
there's nothing here that could still be growing between polls in a way a client couldn't already
get by re-issuing `since`. `gimle-controlplane`'s `ApiServer` proxies `/logs/*` to Muninn as a
fallback when a node or instance is gone, and exposes `GET /metrics-history/*` and
`GET /traces-history/*` as thin proxies over Muninn's own `/metrics/*`/`/traces/*` — gated by the
control plane's own RBAC check (`ResourceKind.LOGS`/`Verb.READ`) before it ever forwards the
request.

## Identity checks on ingest

Plaintext mode (the default) accepts every request unauthenticated, matching every other Gimlé
surface's plaintext posture — `MuninnMain` logs a loud warning at startup naming exactly that
exposure. Under mTLS, ingest carries real, process-specific identity checks:

- **Node log ingest** (`/ingest/logs/nodes/{nodeId}/...`): the caller's peer certificate common
  name must equal `nodeId` — an agent's certificate CN is its own `nodeId`, so this is a direct
  equality check, defense-in-depth beyond bare mTLS so one compromised node can't overwrite
  another's log stream.
- **Instance log ingest** (`/ingest/logs/instances/{deploymentName}/{instanceIndex}/...`): logs
  for an instance are shipped by the *agent* supervising it (a worker has no outbound network
  identity of its own), so the check is that the calling node currently holds a live assignment
  for exactly that `deploymentName`/`instanceIndex`, walked against `StoreClient#listAssignments`.
- **Metrics/traces ingest**: the `processId` path segment is every non-agent process's own
  self-reported `host:port`, not a fixed per-role certificate CN, so a strict equality check
  doesn't generalize the way it does for node/instance logs — the check available here is only
  that the mTLS handshake completed with some verified peer certificate present at all.

The `StoreClient` this process holds is read-only and backs those ingest-side identity checks
(`listAssignments`); the RBAC gate on the *read* side — `ResourceKind.LOGS`/`Verb.READ` — is
enforced once, in `gimle-controlplane`'s `ApiServer`, before it proxies a request to Muninn.

## Entry point (`MuninnMain`)

```
MuninnMain <port> --store-endpoints host1:clientPort1,... --data-root <path>
           [--host <hostname>] [--csr-endpoint <host:port>]
```

`--store-endpoints` is how it reaches `gimle-mimir` (for the assignment lookups backing instance
log ingest identity checks), `--data-root` is where day files live, `--csr-endpoint` is this
process's own certificate-rotation ticker's target — the same shape and ticker-ordering
`FafnirMain` already establishes. Muninn has no leader election (stateless, N replicas each with
their own on-disk data), so the rotation ticker runs unconditionally, not leader-gated.

## Package layout

- `MuninnMain` — entry point, argument parsing, wiring, shutdown hook.
- `MuninnServer` — the HTTP surface above, including TLS material hot-reload on rotation.
- `MuninnDayFileStore` — the day-bucketed JSON-lines store shared by logs, metrics, and traces.
- `RetentionSweeper` — the age-based day-file deletion sweep.
