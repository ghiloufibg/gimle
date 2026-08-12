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
   configured — readable back through `GET /metrics-history/*` and `GET /traces-history/*`. Still
   open: `WorkerMetrics` itself stays local-registry-only (see Observability's own note on why
   worker-tier shipping is a real, separate gap, not covered by this item).
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

6. **Prometheus/OTLP-compatible read translation for Muninn.** Muninn's own first-party ingest/read
   APIs (`GET /metrics-history/*`, `GET /traces-history/*`) are deliberately not wire-compatible
   with a Prometheus scrape or an OTLP collector — a considered trade-off (see
   [Observability](../architecture/observability.md)), not an oversight: staying dependency-free
   and self-contained (no `micrometer-registry-prometheus`/`opentelemetry-exporter-otlp`, no
   operator-run collector) was preferred over out-of-the-box Grafana/Jaeger compatibility. **Why
   it's worth building**: a thin read-side translation layer — Muninn's own stored data exposed as
   a Prometheus-compatible scrape endpoint — would recover that ecosystem compatibility without
   giving up the first-party ingest/storage path underneath it.
7. ~~**p99/latency-histogram shipping.**~~ **Done** — see
   [Observability](../architecture/observability.md). `ApiServerMetrics`/`FafnirMetrics`/
   `StoreMetrics`' request-latency `Timer`s now publish p50/p95/p99, and `MuninnShipper` ships each
   as a `"percentiles"` map alongside its existing `"measurements"`, readable back through the same
   `GET /metrics-history/*` route unchanged. `WorkerMetrics`' own `Timer` gained the same percentile
   config for local parity, but stays unshipped — worker-tier metrics/trace shipping remains the
   separate, still-open gap it always was (see [Observability](../architecture/observability.md)'s
   own note on why worker-tier shipping needs a new `ControlMessage` shape, not a counter delta).
8. ~~**Audit trail coverage for read-only (`GET`) requests.**~~ **Done** — see [Authentication and
   authorization](../architecture/authn-authz.md#audit-logging).
   `-Dgimle.controlplane.audit.readResourceKinds` opts specific resource kinds into READ-decision
   auditing, both allowed and denied, alongside the always-audited `WRITE`/`DELETE` — unset (the
   default) reproduces Kubernetes' own default `Metadata`-level audit policy exactly, since a `GET
   /deployments` from every console page-load would otherwise dwarf the mutating-action volume worth
   capturing. `SECRET` reads on Fafnir's own `/secrets/*` surface were already audited
   unconditionally before this item; the opt-in is what lets the control plane's general RBAC
   surface reach the same bar for whichever other resource kind a deployment actually needs it for.
9. ~~**Console UI for audit trail, Muninn's logs/metrics/traces, and autoscale policy.**~~ **Done**
   — see [Web console](../architecture/web-console.md#metrics-history-traces-and-audit-trail). A
   process-scoped metrics-history time series on the Metrics screen, a new Traces screen, a new
   Audit screen, and `autoscale:` policy display/editing on the deployment create/detail screens,
   all backed by the real APIs this item's own dependencies already shipped — the console's
   `DeploymentSpec`/`DeploymentSpecInput` TypeScript types now model `autoscale` too. Two honest
   caveats, not gaps in this item's own scope: the Traces screen has no real data to show for any
   process kind yet, since worker-tier trace shipping to Muninn remains
   [Observability](../architecture/observability.md)'s own separate, still-open gap; the Audit
   screen is only ever populated in TLS mode, since `requireAuthorized` only records an event once
   it has resolved a real principal.
10. ~~**Weighted/tunable multi-metric autoscaling.**~~ **Done** — see [Control
    plane](../architecture/control-plane.md#reconcilers). `AutoscalePolicy.CombinationMode.WEIGHTED`
    is an opt-in alternative to the original "worst signal wins" default
    (`CombinationMode.WORST_SIGNAL`, matching Kubernetes HPA's own default algorithm, still what
    every pre-existing policy gets): each configured signal's observed/target ratio is weighted and
    averaged into one blended ratio instead of taking the max of independently-computed candidates.
    Configured via `autoscale.mode`/the four per-signal weight fields in the deployment manifest
    (see [Manifest schema](../reference/manifest-schema.md#deployment-manifest-autoscale)) — now
    also tunable from the console's own deployment create screen (item 9), not raw YAML only.

## Priority 3: workload diversity

Not every real workload is a stateless HTTP service.

11. **Batch/scheduled workloads.** No Job/CronJob equivalent — Gimlé only models long-running
    replicated deployments. **Why it's worth building**: run-to-completion is a fundamentally
    different lifecycle than run-forever, with different restart and scheduling semantics entirely.
12. **Per-node placement.** No DaemonSet equivalent — "exactly one instance per node" isn't an
    explicit scheduler mode, only resource-based bin-packing (see
    [Control plane](../architecture/control-plane.md)). **Why it's worth building**: a
    topology-driven placement constraint, genuinely different from the resource-driven one already
    built.
13. **Stateful workload support.** Deliberately last, not because it's unimportant — persistent
    storage with a lifecycle independent of the workload's own, plus ordered rollout and stable
    identity, is arguably the single most educational feature in all of Kubernetes. It's last here
    because it's the deepest rabbit hole, and Gimlé's module model today is inherently
    stateless-friendly.

## Priority 4: control-plane policy and fairness

14. **Explicit, configurable disruption budgets.** The rolling-update bookkeeping already appears
    to replace one instance at a time — a reasonable implicit default — but there's no exposed,
    tunable "max unavailable" the way real clusters make this an explicit contract rather than an
    implementation detail.
15. **Pluggable admission/policy.** Validation today is hardcoded (manifest schema checks, quota
    checks in [Multi-tenancy](../architecture/multi-tenancy.md)). No policy layer for
    organization-specific rules — the "policy as data, not code" pattern real clusters lean on
    heavily.
16. **Priority and preemption.** No notion of a higher-priority deployment evicting a lower-priority
    one under resource pressure — a genuinely hard fairness-versus-urgency scheduling problem.

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
