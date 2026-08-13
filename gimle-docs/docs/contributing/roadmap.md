---
sidebar_position: 3
---

# Roadmap: closing the gap with a real-world cluster

Gimlé's goal is not to compete with Kubernetes — it's to learn how a cluster is actually built by
building one. This page tracks the gap between what exists today and what a production cluster
(Kubernetes-with-etcd, in practice) provides, ordered by priority and by how much building each one
actually teaches, not by a feature-parity checklist.

## Priority 1: the trust boundary

Nothing else matters until this exists.

1. ~~**Authentication and authorization.**~~ **Done** — see
   [Authentication and authorization](../architecture/authn-authz.md). Real identity (mTLS
   certificate or console session) and real `Role`/`RoleBinding` authorization now sit in front of
   every `ApiServer` route; a node certificate is restricted to its own self-service endpoints, an
   operator certificate defaults to full access via a built-in `cluster-admin` binding. Explicitly
   left for a follow-up: an "Access Control" console screen (CLI-only for now).
2. ~~**Audit logging.**~~ **Done** — see [Authentication and
   authorization](../architecture/authn-authz.md#audit-logging). Every `WRITE`/`DELETE` decision
   `requireAuthorized` (and Fafnir's own `/secrets/*` equivalent) makes, allowed and denied alike,
   lands in a durable, queryable, cluster-wide trail — who submitted a given manifest, who deleted a
   tenant, when — distinct from the application logging this codebase already has.

## Priority 2: operational maturity

Instrumentation nobody consumes is decoration, not observability.

3. ~~**Real metrics/tracing export.**~~ **Done** — see
   [Observability](../architecture/observability.md) and [Node
   topology](../architecture/node-topology.md#muninn). `gimle-controlplane`, `gimle-fafnir`, and
   `gimle-mimir` each ship their own request/RPC metrics and traces to Muninn, a dedicated sink
   process, via a periodic `MuninnShipper`/`MuninnSpanExporter` when a Muninn endpoint is
   configured — readable back through `GET /metrics-history/*` and `GET /traces-history/*`.
   Worker-tier shipping (`WorkerMetrics`, and every worker's exported spans) closed the same gap
   this item's own caveat once flagged — see item 6's own note below.
4. ~~**Centralized log aggregation.**~~ **Done** — see [Node
   topology](../architecture/node-topology.md#muninn). `gimle-agent` ships its own platform log and
   every supervised worker's logs to Muninn; `ApiServer`'s `/logs/*` proxy falls back to Muninn's
   shipped history whenever a live agent genuinely can't be reached, so a gone node or instance's
   history is still searchable, not just what's currently on disk.
5. ~~**Multi-metric autoscaling.**~~ **Done** — each worker JVM self-reports its own process CPU
   load and heap usage (portable `java.lang.management`, no cgroups) to its agent every few
   seconds, which is what `AutoscaleReconciler`'s CPU-utilization math reads; `AutoscalePolicy` now
   also accepts optional request-rate, error-rate, and queue-depth targets, each folded in
   alongside CPU as an independently-computed candidate replica count, with the highest one
   ("worst signal wins," matching Kubernetes' own HPA) driving the scaling decision — see
   [Control plane](../architecture/control-plane.md#reconcilers).

6. ~~**p99/latency-histogram shipping.**~~ **Done** — see
   [Observability](../architecture/observability.md). `ApiServerMetrics`/`FafnirMetrics`/
   `StoreMetrics`' request-latency `Timer`s now publish p50/p95/p99, and `MeterSnapshotCodec` (the
   pure NDJSON serializer `MuninnShipper` now delegates to) ships each as a `"percentiles"` map
   alongside its existing `"measurements"`, readable back through the same `GET /metrics-history/*`
   route unchanged. `WorkerMetrics`' own `Timer` gained the same percentile config, and — closing the
   gap items 3/6/9 of this list each once flagged — now ships too: `WorkerMain` relays a periodic
   `MeterSnapshotCodec` snapshot (and every exported span batch, via `RelayingSpanExporter`) to its
   agent over the existing control channel (`ControlMessage.MetricsSnapshot`/`TracesSnapshot`,
   design doc §6), since a worker JVM has no outbound network identity of its own to ship with
   directly; the agent relays the payload byte-for-byte to Muninn under the new `WORKER` processKind
   (`{nodeId}:{workerId}`, no `gimle-mimir` changes needed) — see
   [Observability](../architecture/observability.md).
7. ~~**Audit trail coverage for read-only (`GET`) requests.**~~ **Done** — see [Authentication and
   authorization](../architecture/authn-authz.md#audit-logging).
   `-Dgimle.controlplane.audit.readResourceKinds` opts specific resource kinds into READ-decision
   auditing, both allowed and denied, alongside the always-audited `WRITE`/`DELETE` — unset (the
   default) reproduces Kubernetes' own default `Metadata`-level audit policy exactly, since a `GET
   /deployments` from every console page-load would otherwise dwarf the mutating-action volume worth
   capturing. `SECRET` reads on Fafnir's own `/secrets/*` surface were already audited
   unconditionally before this item; the opt-in is what lets the control plane's general RBAC
   surface reach the same bar for whichever other resource kind a deployment actually needs it for.
8. ~~**Console UI for audit trail, Muninn's logs/metrics/traces, and autoscale policy.**~~ **Done**
   — see [Web console](../architecture/web-console.md#metrics-history-traces-and-audit-trail). A
   process-scoped metrics-history time series on the Metrics screen, a new Traces screen, a new
   Audit screen, and `autoscale:` policy display/editing on the deployment create/detail screens,
   all backed by the real APIs this item's own dependencies already shipped — the console's
   `DeploymentSpec`/`DeploymentSpecInput` TypeScript types now model `autoscale` too. One honest
   caveat remains, not a gap in this item's own scope: the Audit screen is only ever populated in
   TLS mode, since `requireAuthorized` only records an event once it has resolved a real principal.
   The Traces screen's own former caveat (no real data for any process kind) closed with item 6's
   worker-tier shipping — its process-picker now offers a `WORKER` kind alongside the original four.
9. ~~**Weighted/tunable multi-metric autoscaling.**~~ **Done** — see [Control
   plane](../architecture/control-plane.md#reconcilers). `AutoscalePolicy.CombinationMode.WEIGHTED`
   is an opt-in alternative to the original "worst signal wins" default
   (`CombinationMode.WORST_SIGNAL`, matching Kubernetes HPA's own default algorithm, still what
   every pre-existing policy gets): each configured signal's observed/target ratio is weighted and
   averaged into one blended ratio instead of taking the max of independently-computed candidates.
   Configured via `autoscale.mode`/the four per-signal weight fields in the deployment manifest
   (see [Manifest schema](../reference/manifest-schema.md#deployment-manifest-autoscale)) — now
   also tunable from the console's own deployment create screen (item 8), not raw YAML only.

## Priority 3: workload diversity

Not every real workload is a stateless HTTP service.

10. ~~**Batch/scheduled workloads.**~~ **Done** — see [Manifest schema](../reference/manifest-schema.md#job-manifest)
    and its [CronJob manifest](../reference/manifest-schema.md#cronjob-manifest) section. `kind: Job`
    is a real run-to-completion lifecycle (`ModuleState.COMPLETED`, `JobHooks`, `JobReconciler`
    reusing `Scheduler.place` unchanged); `kind: CronJob` is a thin scheduled generator over it
    (`CronJobReconciler`, a hand-rolled 5-field cron evaluator, `concurrencyPolicy`, missed-schedule
    handling) — never a second execution engine. `gimle cronjob trigger <name>` covers the one
    manual action that doesn't fit CRUD.
11. ~~**Per-node placement.**~~ **Done** — see [Manifest schema](../reference/manifest-schema.md#daemonset-manifest).
    `kind: DaemonSet` places one instance on every node `Scheduler.eligibleNodes` (the same five-step
    tier/cordon/anti-affinity/tenant/label filter chain `place` already used, extracted so a caller
    can take every survivor instead of one bin-packed pick) currently admits, recomputed on every
    reconcile tick as nodes join, leave, or are cordoned — not a fixed operator-chosen count.
    `placement.antiAffinity` is rejected outright on this manifest kind (meaningless once placement
    is already one-per-node); `placement.requiredLabels` is promoted to the primary way an operator
    scopes which nodes run it, both in the manifest and on the console's own DaemonSets screen.
    `DaemonSetReconciler`'s rolling update is a deliberate node-keyed duplicate of
    `DeploymentReconciler`'s own index-keyed state machine, not a shared generalization — the two
    key types (`String nodeId` vs. `int instanceIndex`) don't unify cleanly enough to be worth it.
12. ~~**Stateful workload support.**~~ **Done** — see [Manifest schema](../reference/manifest-schema.md#statefulset-manifest).
    The last, deliberately hardest, workload-diversity item: `kind: StatefulSet` adds two properties
    neither Deployment nor DaemonSet has, both because a persistent local-disk volume can't move
    between nodes. `OrderedReady` (index `i+1` never placed before index `i` reports ready; scale-down
    removes the highest index first, one per tick) is enforced by `StatefulSetReconciler` simply
    scanning indices in order and stopping at the first one that isn't ready — no separate "am I
    mid-rollout" bookkeeping needed. Sticky placement is `Scheduler.place`'s new `stickyNodeId`
    parameter, which collapses the whole eligibility chain to "is this one node still eligible?" and
    never falls back to a different node if not — backed by `StateStore`'s own sticky node-binding
    map, written once at an index's first placement and read back on every later attempt (including
    across a rolling update), surviving everything except the index's own permanent removal. The new
    `VolumeManager`/`LocalDiskVolumeManager` (`gimle-os`) and `ModuleContext.dataDirectory()`
    (`gimle-module`) are the one genuinely new worker/agent capability this entire priority-3 body of
    work required — local-disk-only, no replication, no CSI-style pluggable backend, matching the
    single-node-local-disk durability this design deliberately promises and nothing more.

**Workload diversity is now fully closed.** Items 10-12 above cover the four new workload kinds
(Job, CronJob, DaemonSet, StatefulSet); the one adjacent piece bundled into the same body of
work — worker-tier metrics/trace shipping to Muninn — isn't itself a workload-diversity concern,
so it's tracked under items 3/6/9 above instead of renumbered in here, but it's done too. Job
`parallelism`/`completions`, multi-volume StatefulSets, volume replication/snapshotting/backup-
restore, and CSI-style pluggable storage backends were never part of this priority's own scope —
permanent, deliberate non-goals (single-node local-disk durability is the whole promise), not
work left undone.

## Priority 4: control-plane policy and fairness

13. ~~**Explicit, configurable disruption budgets.**~~ **Done** — see [Manifest schema § Deployment
    manifest: disruption](../reference/manifest-schema.md#deployment-manifest-disruption) and
    [Control plane § Reconcilers](../architecture/control-plane.md#reconcilers). A manifest
    `disruption:` block (Deployment and DaemonSet) exposes `maxUnavailable` as an explicit, tunable
    contract instead of the implicit one-at-a-time default every rollout had before —
    `DeploymentReconciler`'s single-index in-flight scalar and `DaemonSetReconciler`'s node-keyed
    duplicate both became small bounded sets, continuously topped up as each migration clears rather
    than draining a whole batch first. `maxSurge` (provisioning a replacement before removing the
    original) is now implemented for Deployment too, via a synthetic index range `>= replicas` the
    ordinary placement range never otherwise uses, promoted once the surge instance reports ready —
    DaemonSet's own one-instance-per-node placement has no equivalent, so `DaemonSetManifestParser`
    still rejects a nonzero value permanently, not as a scoped-out first pass. The
    tenant-quota-at-admission interaction item 14 tracks is closed for this item's own purposes:
    `DeploymentSpec#maxCommittedInstances()` (`replicas + maxSurge`) is what admission now checks a
    tenant's quota against, so a rollout can't transiently burst a tenant over its ceiling by
    surging — see [Multi-tenancy](../architecture/multi-tenancy.md).
14. **Pluggable admission/policy.** Validation today is hardcoded (manifest schema checks, quota
    checks in [Multi-tenancy](../architecture/multi-tenancy.md)). No policy layer for
    organization-specific rules — the "policy as data, not code" pattern real clusters lean on
    heavily.
15. **Priority and preemption.** No notion of a higher-priority deployment evicting a lower-priority
    one under resource pressure — a genuinely hard fairness-versus-urgency scheduling problem.
16. **Prometheus/OTLP-compatible read translation for Muninn.** Muninn's own first-party ingest/read
    APIs (`GET /metrics-history/*`, `GET /traces-history/*`) are deliberately not wire-compatible
    with a Prometheus scrape or an OTLP collector — a considered trade-off (see
    [Observability](../architecture/observability.md)), not an oversight: staying dependency-free
    and self-contained (no `micrometer-registry-prometheus`/`opentelemetry-exporter-otlp`, no
    operator-run collector) was preferred over out-of-the-box Grafana/Jaeger compatibility. Deferred
    to last priority — it recovers ecosystem compatibility for existing data rather than closing a
    cluster-mechanics gap the way the other Priority 4 items do. **Why it's worth building**: a thin
    read-side translation layer — Muninn's own stored data exposed as a Prometheus-compatible scrape
    endpoint — would recover that ecosystem compatibility without giving up the first-party
    ingest/storage path underneath it.

## Acknowledged, deliberately not prioritized

Multi-cluster federation (including federating audit trails or observability data specifically —
each cluster's audit trail and Muninn's shipped logs/metrics/traces stay in that cluster's own
store, matching every other piece of cluster state today), ingress/external load balancing,
DNS-based service discovery, secrets rotation, and client SDKs beyond Java are all real gaps versus
a production cluster — left off the priority list because they teach less about cluster *mechanics*
per unit of effort than the items above, for a project whose goal is understanding how clusters are
built, not building an ecosystem around one.

A new Raft-replicated cluster (`gimle-mimir`-shaped) for logs/metrics/traces is deliberately not on
this list at all, unlike the items above — it's a rejected alternative from the observability
design, not a deferred one: that data is high(er)-volume and archival rather than authoritative, so
it needs a durable place shipped-to data ends up readable from (what Muninn provides), not
consensus. See [Node topology](../architecture/node-topology.md#muninn).
