---
sidebar_position: 7
---

# Web console

The operator-facing web UI (`gimle-console`) — real data from a running control plane, no mocks,
no seeded state. Bun/Vite/React/TanStack Router; embedded into `gimle-controlplane`'s own jar and
served at `/console` with no separate deploy step (see
[Project structure](../contributing/project-structure.md)).

## Screens

Nineteen screens, each backed by a real `Http*Repository` hitting the control plane's own API —
the same data the [CLI](../reference/cli-reference.md) reads, not a parallel source of truth.
Seventeen live under the sidebar's "Cluster" group, `Logs` is reached contextually from an
instance/deployment rather than its own top-level nav entry, and `Control plane` sits in its own
"System" nav group, separate from the rest:

| Screen | Shows |
|---|---|
| Overview | Landing dashboard summarizing cluster state at a glance. |
| Deployments | List/create/inspect deployments — the UI equivalent of `gimle get/apply/delete deployment`. Create/detail also expose the optional `autoscale:` policy and `disruption:` budget (below). |
| Jobs | List/create/inspect [`kind: Job`](../reference/manifest-schema.md#job-manifest) run-to-completion workloads — the UI equivalent of `gimle get jobs`. |
| CronJobs | List/create/inspect [`kind: CronJob`](../reference/manifest-schema.md#cronjob-manifest) scheduled generators, including each one's generated Jobs — the UI equivalent of `gimle get cronjobs`. |
| DaemonSets | List/create/inspect [`kind: DaemonSet`](../reference/manifest-schema.md#daemonset-manifest) per-node workloads, surfacing `placement.requiredLabels` as a first-class column since it's the primary way an operator scopes which nodes run one. |
| StatefulSets | List/create/inspect [`kind: StatefulSet`](../reference/manifest-schema.md#statefulset-manifest) workloads, including each index's sticky `nodeId` assignment. |
| Instances | Per-instance detail: lifecycle state, health, resource usage. |
| Nodes | Registered node agents and their reported capacity — the UI equivalent of `gimle get nodes`. |
| Topology | A real-time graph of the cluster's actual placement (which instances landed on which nodes/workers). |
| Metrics | Cluster-wide derived signals (lifecycle mix, placement coverage, node capacity, backpressure, tenant quota pressure) plus a per-process metrics-history time series, below. |
| Traces | Per-process trace-span history, below. |
| Tenants | Tenant list and quota management — see [Multi-tenancy](./multi-tenancy.md). |
| Config | Tenant-scoped, plain (non-secret) config entries — see [Multi-tenancy](./multi-tenancy.md#tenant-scoped-config). |
| Secrets | Versioned, per-tenant secrets served by Fafnir — mask/reveal, a version picker, soft/hard delete, master-key rotation. See [Multi-tenancy](./multi-tenancy.md#secrets). |
| Artifacts | Module jars pushed to the [Andvari](./node-topology.md#andvari) artifact registry — push/list/copy-checksum/delete against the real `/artifacts/*` proxy, the UI equivalent of `gimle artifact push/list/get/delete`. |
| Access Control | `Role`/`RoleBinding`/`Account` management (tabs, below) — the UI equivalent of `gimle get/set/delete role/rolebinding/accounts`. |
| Audit | Filterable audit trail (principal, resource kind, verb, tenant, allow/deny), below. |
| Logs | Live log tailing and crash-dump listing, below. |
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
  shipped data uses (see [Observability](./observability.md)) — no worker-discovery API exists
  either, so the operator supplies the id from elsewhere (a log line, the CLI).
- **Traces** (`GET /traces-history/{processKind}/{processId}`, same envelope and process-picker
  pattern as metrics history): a flat, sortable span table (trace id, span name, kind, status,
  time) — not a flame graph or waterfall, since the wire shape carries no span duration or start
  time today. The only production code that creates a span at all is `gimle-fabric`'s
  `FabricServer` (an inbound cross-worker service call) and its own instance's `WorkerMain`
  relay — so this screen only ever shows data for the `WORKER` process kind, none of the other
  four, an accurate reflection of where spans are actually created today rather than a gap in
  this UI.
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

`Role`/`RoleBinding`/`Account` objects are managed from the Access Control screen (three tabs,
below) as well as the CLI (see the [CLI reference](../reference/cli-reference.md)) — both read and
write the same `/roles`, `/rolebindings`, and `/accounts` API surface, not two independent stores.
