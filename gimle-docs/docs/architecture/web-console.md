---
sidebar_position: 7
---

import ZoomableDiagram from '@site/src/components/ZoomableDiagram';

# Web console

The operator-facing web UI (`gimle-console`) — real data from a running control plane, no mocks,
no seeded state. Bun/Vite/React/TanStack Router; embedded into `gimle-controlplane`'s own jar and
served at `/console` with no separate deploy step (see
[Project structure](../contributing/project-structure.md)). The bare control-plane address (`/`)
redirects there too, so pointing a browser at the API server's host with no path lands on the
console rather than a bare `404`.

The console and `gimle-cli` are two clients of one API surface — the control plane proxies out to
the three dedicated services rather than either client talking to them directly (source:
`diagrams/web-console-architecture.d2`):

<ZoomableDiagram
  src="/diagrams/web-console-architecture.svg"
  alt="Both gimle-console (via a login session cookie) and gimle-cli (via mTLS or plaintext) talk only to the Control Plane's ApiServer, which itself talks to gimle-mimir directly and proxies secrets to Fafnir, logs/metrics/traces to Muninn, and artifacts to Andvari"
  width={760}
/>

## Screens

Twenty-two screens, each backed by a real `Http*Repository` hitting the control plane's own API — the
same data the [CLI](../reference/cli-reference.md) reads, not a parallel source of truth. Twenty
live under the sidebar's "Cluster" group, `Logs` is reached contextually from an
instance/deployment rather than its own top-level nav entry, and `Control plane` sits in its own
"System" nav group, separate from the rest:

| Screen | Shows |
|---|---|
| Overview | Landing dashboard summarizing cluster state at a glance. |
| Deployments | List/create/inspect deployments — the UI equivalent of `gimle get/apply/delete deployment`. Create/detail also expose the optional `autoscale:` policy and `disruption:` budget (below), plus a revision-history panel with a per-revision rollback action — the UI equivalent of `gimle deployment revisions/rollback`. |
| Jobs | List/create/inspect [`kind: Job`](../reference/manifest-schema.md#job-manifest) run-to-completion workloads — the UI equivalent of `gimle get/apply jobs`. |
| CronJobs | List/create/inspect [`kind: CronJob`](../reference/manifest-schema.md#cronjob-manifest) scheduled generators, including each one's generated Jobs — the UI equivalent of `gimle get/apply cronjobs`. |
| DaemonSets | List/create/inspect [`kind: DaemonSet`](../reference/manifest-schema.md#daemonset-manifest) per-node workloads, surfacing `placement.requiredLabels` as a first-class column since it's the primary way an operator scopes which nodes run one, plus the same revision-history/rollback panel Deployments has. |
| StatefulSets | List/create/inspect [`kind: StatefulSet`](../reference/manifest-schema.md#statefulset-manifest) workloads, including each index's sticky `nodeId` assignment, plus the same revision-history/rollback panel Deployments has. |
| Instances | Per-instance detail: lifecycle state, health, resource usage. |
| Custom Resources | Instances of cluster-defined [custom kinds](./custom-kinds.md): a kind picker fed by `/kinddefinitions`, an instance table honoring each definition's own `printColumns`, and a detail pane showing spec and status side by side with the generation/observedGeneration pair made visible — the at-a-glance "has the operator caught up" signal. Deliberately read-only; authoring stays in the CLI. |
| Nodes | Registered node agents and their reported capacity, plus cordon/uncordon and per-tenant taint/untaint controls on the detail page — the UI equivalent of `gimle get nodes` and `gimle cordon/uncordon/taint/untaint`. Cordoning/tainting only ever affects future scheduling; neither evicts an already-running instance. |
| Networking | Two tabs: [Services](./service-fabric.md#the-service-abstraction-a-stable-name-in-front-of-a-deployment) (the ClusterIP analogue — create/inspect/delete, plus each row's live backing endpoints) and NetworkPolicies (which other tenants may call a tenant's own Services) — the UI equivalent of `gimle get/set/delete service` and `gimle get/set/delete networkpolicy`. |
| Topology | A real-time graph of the cluster's actual placement (which instances landed on which nodes/workers). |
| Metrics | Cluster-wide derived signals (lifecycle mix, placement coverage, node capacity, backpressure, tenant quota pressure) plus a per-process metrics-history time series, below. |
| Traces | Per-process trace-span history, below. |
| Tenants | Tenant list and quota management — see [Multi-tenancy](./multi-tenancy.md). |
| LimitRanges | Per-tenant [LimitRange](./multi-tenancy.md#limitrange) management — list every tenant that has one, create/edit its four optional `minRequest`/`maxRequest`/`minLimit`/`maxLimit` bounds, delete it. The UI equivalent of `gimle get/set/delete limitrange`. Each bound is a memory + cpu pair, and a bound left blank is written as absent (unbounded) rather than as zero — the same absent-means-unbounded rule the API itself uses. |
| Config | Tenant-scoped, plain (non-secret) config entries — see [Multi-tenancy](./multi-tenancy.md#tenant-scoped-config). |
| Secrets | Versioned, per-tenant secrets served by Fafnir — mask/reveal, a version picker showing each version's author and write time, a declared-type selector on write (`opaque`/`pem-certificate`/`pem-private-key`), soft/hard delete, master-key rotation. See [Multi-tenancy](./multi-tenancy.md#secrets). |
| Artifacts | Module jars pushed to the [Andvari](./node-topology.md#andvari) artifact registry — push/list/copy-checksum/delete against the real `/artifacts/*` proxy, the UI equivalent of `gimle artifact push/list/get/delete`. |
| Access Control | `Role`/`RoleBinding`/`Account` management (tabs, below) — the UI equivalent of `gimle get/set/delete role/rolebinding/accounts`. |
| Audit | Filterable audit trail (principal, resource kind, verb, tenant, allow/deny), below. |
| Logs | Live log tailing, level/text filtering, and crash-dump listing, below. |
| Control plane | Scheduler, quota enforcer, and heartbeat-worker status at a glance, plus a link into the control plane's own log. In its own sidebar group since it reports on the control plane process itself rather than on a workload. |

## Metrics history, traces, and audit trail

Until now, none of the audit logging, observability, or autoscaling work had a console screen of
its own — that gap is closed. Three additions, all backed by real API surfaces that existed
before the UI did:

- **Metrics history** (`GET /metrics-history/{processKind}/{processId}`, proxying to
  [Muninn](./node-topology.md#muninn)): a process picker
  (`CONTROLPLANE`/`FAFNIR`/`STORE`/`AGENT`/`WORKER`) plus one time-series chart per meter name
  present in the fetched window, on the Metrics screen. There is no discovery API for which
  `processId` (a self-reported `host:port` string, e.g. a `ControlPlaneMain` replica's own
  `selfApiAddress`) exists — `CONTROLPLANE` defaults to `window.location.host` (accurate whenever
  the console is served by that same replica, same origin), `AGENT` picks from the already-loaded
  real node list, `FAFNIR`/`STORE` are a plain editable address field since the console has no
  equivalent same-origin trick for either, and `WORKER` combines that same node dropdown with a
  free-text `workerId` field into the `{nodeId}:{workerId}` `processId` shape a worker JVM's own
  shipped data uses (see [Observability](./observability.md)) — still the only way to type one in
  cold, since no API enumerates every `workerId` a node currently hosts. What no longer requires
  typing anything in at all: every instance-carrying screen (Instances, Deployments, an instance's
  own detail page) now surfaces that instance's own `workerId` alongside its node, reported by the
  worker's own `Hello` handshake with its agent and threaded through the same heartbeat path
  `nodeId`/`lifecycleState` already ride — the instance detail page's "Worker metrics"/"Worker
  traces" buttons deep-link straight into this same picker's `WORKER` target pre-filled with that
  instance's real `nodeId:workerId`, so the picker's free-text field stays a fallback for a worker
  with no instance in view, not the only path in.
- **Traces** (`GET /traces-history/{processKind}/{processId}`, same envelope and process-picker
  pattern as metrics history): a flat, sortable span table (trace id, span name, kind, status,
  time) — not a flame graph or waterfall, since the wire shape carries no span duration or start
  time today. The only production code that creates a span at all is `gimle-fabric`'s
  `FabricServer` (an inbound cross-worker service call) and its own instance's `WorkerMain`
  relay — so this screen only ever shows data for the `WORKER` process kind, none of the other
  four, an accurate reflection of where spans are actually created today rather than a gap in
  this UI. Selecting a trace id opens **Follow trace**, which assembles that one trace's spans
  from every worker process the console can currently name, grouped by process and indented into a
  call tree — the cross-worker case (a consumer's client span and a provider's server span sharing
  one trace id across two JVMs) read end to end instead of by opening two views and eyeballing
  truncated id prefixes. There is no server-side trace search, so this fans the same per-process
  history call out client-side, and the panel states its own limits in place rather than implying
  completeness: only `WORKER` processes are searched, searchable workers come from the instance
  list (a torn-down worker's spans may still be in Muninn but cannot be addressed from here), each
  worker's history is walked only a few pages back, any process that failed to answer is named,
  and a parent span that no searched process produced is reported as making the trace provably
  incomplete.
- **Audit trail** (`GET /audit?principal=&resource=&tenant=&since=&limit=`): a filterable table,
  most recent first, allowed/denied visually distinguished. Only ever populated in TLS mode — see
  [Authentication and authorization](./authn-authz.md) — since `requireAuthorized` only resolves a
  real principal (and therefore only ever records an audit event) when the transport is TLS, via
  either a verified mTLS client certificate or a verified console session cookie — plaintext mode
  has neither.
- **Access Control** (`GET/PUT/DELETE /roles/*`, `/rolebindings/*`, `/accounts/*`): three tabs —
  Roles (a repeatable permission-row editor: resource kind, verb, optional tenant scope), Role
  Bindings (a user/group subject toggle plus a role picker sourced from the Roles tab's own store),
  and Accounts (username plus a create-or-reset password form — the API never returns password
  material, so the list view never shows one). `RoleBinding`'s `id` is caller-chosen; the create
  form defaults it to a slug of the subject and role rather than asking the operator to invent one.

Deployment create/detail also gained the `autoscale:` policy (see [Manifest
schema](../reference/manifest-schema.md#deployment-manifest-autoscale)): a read-only panel on the
detail screen when a deployment has one (replica bounds, each configured target signal, weights
when in `weighted` mode), and an optional, collapsible sub-form on the create screen. No backend
change was needed for creation — the console already builds deployment manifests as hand-rolled
YAML and PUTs them, and `DeploymentManifestParser` already accepted an `autoscale:` block — only
`ApiServer.deploymentStatus`'s JSON serialization needed to start including it on the read side.

The `disruption:` budget (see [Manifest schema § Deployment manifest:
disruption](../reference/manifest-schema.md#deployment-manifest-disruption)) followed the exact
same template: an optional, collapsible `maxUnavailable`/`maxSurge` sub-form on the create screen,
and a read-only panel on the detail screen when a deployment has one. Unlike `autoscale:`,
`disruption:` had *never* been on the wire at all before this — `ApiServer.deploymentStatus` gained
its first `disruption` key here, not merely a later addition to an existing one.

## Logs: live tailing and crash dumps

The Logs screen tails real output from the control plane, a node agent, or any specific instance,
with a "follow" toggle for genuine live tailing — backed by a real `/logs/*` API
(`AgentLogServer` in `gimle-agent`, proxied through `ApiServer` in `gimle-controlplane`), not
polling a static file. The same data is available from the CLI
(`gimle logs <target> --follow`) — running both side by side against the same target is the real
proof that one backend mechanism serves both consumers identically, not two independent
implementations that happen to agree.

The screen also carries a content filter, so hunting one line in a high-volume log doesn't mean
paging through raw NDJSON by hand. A level dropdown applies a **threshold** (`WARN` keeps `WARN` and
`ERROR`; a raw, unstructured SYSTEM capture carrying no level at all is never kept by one), and a
text box applies a plain, **case-insensitive substring** — never a regular expression — over a
line's human-readable fields (`message`, `logger`, `stackTrace`, `raw`), not machine identifiers
like `nodeId` or `thread`. Both are applied server-side, travelling as `level`/`contains` query
parameters on the same `/logs/*` routes, so a filtered view never ships the whole stream to the
browser just to discard most of it; both survive the "follow" toggle; and both live in the URL, so
a filtered view is bookmarkable and shareable. A query that legitimately matches nothing says so
and names what it filtered on, rather than showing an empty panel indistinguishable from a broken
request. `gimle logs --level/--contains` are the identical filters over the identical parameters.

For a crashed instance, the Logs screen also lists any `hs_err_pid*.log` JVM crash dumps it left
behind — the kind of file you'd otherwise have to know to go find on disk by hand.

A gone node or instance's logs are still served transparently: `ApiServer`'s `/logs/*` proxy falls
back to Muninn's own shipped history (see [Node topology](./node-topology.md#muninn)) whenever a
live agent genuinely can't be reached, so the Logs screen keeps working with no console-side code
change — the fallback is invisible from this screen's own point of view. The content filter is
invisible across it too: `level`/`contains` are relayed to Muninn unchanged and re-applied there, so
the same filtered query returns the same lines whether or not the owning node is still alive.

## Login

See [Authentication and authorization](./authn-authz.md) for the full picture. The console
authenticates over a session cookie rather than mTLS (interactive browser client-cert selection is
poor UX) — a `/login` route, a root-level redirect guard, and a "log out" control in the sidebar
footer. A 401 from any endpoint clears local session state and redirects to `/login`; a 403 surfaces
in place as "you don't have permission" instead, since the user is legitimately logged in and just
lacks that specific permission.

`Role`/`RoleBinding`/`Account` objects are managed from the Access Control screen (three tabs,
below) as well as the CLI (see the [CLI reference](../reference/cli-reference.md)) — both read and
write the same `/roles`, `/rolebindings`, and `/accounts` API surface, not two independent stores.
