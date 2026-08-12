---
sidebar_position: 7
---

# Web console

The operator-facing web UI (`gimle-console`) — real data from a running control plane, no mocks,
no seeded state. Bun/Vite/React/TanStack Router; embedded into `gimle-controlplane`'s own jar and
served at `/console` with no separate deploy step (see
[Project structure](../contributing/project-structure.md)).

## Screens

Twelve routes, each backed by a real `Http*Repository` hitting the control plane's own API — the
same data the [CLI](../reference/cli-reference.md) reads, not a parallel source of truth:

| Screen | Shows |
|---|---|
| Overview | Landing dashboard summarizing cluster state at a glance. |
| Deployments | List/create/inspect deployments — the UI equivalent of `gimle get/apply/delete deployment`. Create/detail also expose the optional `autoscale:` policy (below). |
| Instances | Per-instance detail: lifecycle state, health, resource usage. |
| Nodes | Registered node agents and their reported capacity — the UI equivalent of `gimle get nodes`. |
| Topology | A real-time graph of the cluster's actual placement (which instances landed on which nodes/workers). |
| Metrics | Cluster-wide derived signals (lifecycle mix, placement coverage, node capacity, backpressure, tenant quota pressure) plus a per-process metrics-history time series, below. |
| Traces | Per-process trace-span history, below. |
| Tenants | Tenant list and quota management — see [Multi-tenancy](./multi-tenancy.md). |
| Config | Tenant-scoped, plain (non-secret) config entries — see [Multi-tenancy](./multi-tenancy.md#tenant-scoped-config). |
| Secrets | Versioned, per-tenant secrets served by Fafnir — mask/reveal, a version picker, soft/hard delete, master-key rotation. See [Multi-tenancy](./multi-tenancy.md#secrets). |
| Audit | Filterable audit trail (principal, resource kind, verb, tenant, allow/deny), below. |
| Logs | Live log tailing and crash-dump listing, below. |

## Metrics history, traces, and audit trail

Roadmap item 9's remaining gap — none of the audit logging, observability, or autoscaling work
had a console screen — is closed. Three additions, all backed by real API surfaces that existed
before the UI did:

- **Metrics history** (`GET /metrics-history/{processKind}/{processId}`, proxying to
  [Muninn](./node-topology.md#muninn)): a process picker (`CONTROLPLANE`/`FAFNIR`/`STORE`/`AGENT`)
  plus one time-series chart per meter name present in the fetched window, on the Metrics screen.
  There is no discovery API for which `processId` (a self-reported `host:port` string, e.g. a
  `ControlPlaneMain` replica's own `selfApiAddress`) exists — `CONTROLPLANE` defaults to
  `window.location.host` (accurate whenever the console is served by that same replica, same
  origin), `AGENT` picks from the already-loaded real node list, and `FAFNIR`/`STORE` are a plain
  editable address field since the console has no equivalent same-origin trick for either.
- **Traces** (`GET /traces-history/{processKind}/{processId}`, same envelope and process-picker
  pattern as metrics history): a flat, sortable span table (trace id, span name, kind, status,
  time) — not a flame graph or waterfall, since the wire shape carries no span duration or start
  time today. Built and wired correctly, but worth being precise about: the only production code
  that creates a span at all is `gimle-fabric`'s `FabricServer` (an inbound cross-worker service
  call), and worker-tier trace shipping to Muninn is the separate, still-open gap
  [Observability](./observability.md) already documents — so this screen has no real data to show
  for any of the four addressable process kinds until that gap closes, not a defect in this UI.
- **Audit trail** (`GET /audit?principal=&resource=&tenant=&since=&limit=`): a filterable table,
  most recent first, allowed/denied visually distinguished. Only ever populated in TLS mode — see
  [Authentication and authorization](./authn-authz.md) — since `requireAuthorized` only resolves a
  real principal (and therefore only ever records an audit event) when the transport is mTLS.

Deployment create/detail also gained the `autoscale:` policy (see [Manifest
schema](../reference/manifest-schema.md#deployment-manifest-autoscale)): a read-only panel on the
detail screen when a deployment has one (replica bounds, each configured target signal, weights
when in `weighted` mode), and an optional, collapsible sub-form on the create screen. No backend
change was needed for creation — the console already builds deployment manifests as hand-rolled
YAML and PUTs them, and `DeploymentManifestParser` already accepted an `autoscale:` block — only
`ApiServer.deploymentStatus`'s JSON serialization needed to start including it on the read side.

## Logs: live tailing and crash dumps

The Logs screen tails real output from the control plane, a node agent, or any specific instance,
with a "follow" toggle for genuine live tailing — backed by a real `/logs/*` API
(`AgentLogServer` in `gimle-agent`, proxied through `ApiServer` in `gimle-controlplane`), not
polling a static file. The same data is available from the CLI
(`gimle logs <target> --follow`) — running both side by side against the same target is the real
proof that one backend mechanism serves both consumers identically, not two independent
implementations that happen to agree.

For a crashed instance, the Logs screen also lists any `hs_err_pid*.log` JVM crash dumps it left
behind — the kind of file you'd otherwise have to know to go find on disk by hand.

A gone node or instance's logs are still served transparently: `ApiServer`'s `/logs/*` proxy falls
back to Muninn's own shipped history (see [Node topology](./node-topology.md#muninn)) whenever a
live agent genuinely can't be reached, so the Logs screen keeps working with no console-side code
change — the fallback is invisible from this screen's own point of view.

## Login

See [Authentication and authorization](./authn-authz.md) for the full picture. The console
authenticates over a session cookie rather than mTLS (interactive browser client-cert selection is
poor UX) — a `/login` route, a root-level redirect guard, and a "log out" control in the sidebar
footer. A 401 from any endpoint clears local session state and redirects to `/login`; a 403 surfaces
in place as "you don't have permission" instead, since the user is legitimately logged in and just
lacks that specific permission.

Managing `Role`/`RoleBinding`/`Account` objects themselves is CLI-only for now (see the
[CLI reference](../reference/cli-reference.md)) — no dedicated "Access Control" screen yet. A
natural, explicitly scoped follow-up, not a gap in this design.
