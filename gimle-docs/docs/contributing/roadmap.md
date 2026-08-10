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
7. **p99/latency-histogram shipping.** `WorkerMetrics`' `Timer`s already record the raw data;
   percentile computation and shipping it off-worker is materially more plumbing than a counter
   delta and remains unbuilt — see [Observability](../architecture/observability.md).
8. **Audit trail coverage for read-only (`GET`) requests.** Today's audit trail only records
   `WRITE`/`DELETE` decisions (see [Authentication and
   authorization](../architecture/authn-authz.md#audit-logging)), matching Kubernetes' own default
   audit policy (`Metadata` level by default) for the same reason: a `GET /deployments` from every
   console page-load would dwarf the mutating-action volume audit logging is meant to capture.
   **Why it's worth building**: a per-resource-kind opt-in for read auditing, for the rare
   deployment that genuinely needs it.
9. **Console UI for audit trail, Muninn's logs/metrics/traces, and autoscale policy.** None of the
   audit logging, observability, or autoscaling work above shipped a console screen — the audit
   trail, metrics/traces history, and the `autoscale:` manifest fields (the console's own
   `DeploymentSpec`/`InstanceObservation` TypeScript types don't model any of them today) are all
   API/CLI-only. Deliberate, not an oversight — UI work has its own review cost independent of the
   backend design, the same deferral the secrets vault design made for Fafnir before its own
   Secrets screen landed later.
10. **Weighted/tunable multi-metric autoscaling.** `AutoscaleReconciler`'s "worst signal wins" (see
    [Control plane](../architecture/control-plane.md#reconcilers)) is the MVP heuristic, matching
    Kubernetes HPA's own default algorithm — a configurable weighting scheme across signals is
    future scope if the simple version proves insufficient in practice.

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
