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

1. **Transport encryption (TLS/mTLS).** Substantially done, gated behind
   `gimle.transport.protocol=tls` (default remains `plaintext`): the control-plane API server
   (`HttpsServer`/`HttpsConfigurator`), Raft peer RPC and cross-machine fabric calls (both over
   `SSLSocket`/`SSLServerSocket`), and gossip membership (DTLS via per-peer `SSLEngine`, since gossip
   is UDP) are all real mutual TLS, backed by a new `gimle-pki` module (Bouncy Castle-based CA/leaf
   certificate issuance — the JDK has no public API for that). What remains: the node-bootstrap CSR
   flow (`POST /bootstrap/csr`, bootstrap tokens, a brand-new agent minting its own identity to join
   a running cluster), the equivalent human-operator credential flow, and certificate rotation — see
   `claudedocs/tls-transport-security-design.md` §4/§4a/§4b. **Why it's worth building**: cert
   issuance, rotation, and trust bootstrapping are among the most genuinely transferable, hard-won
   lessons in real distributed systems — it's the reason Kubernetes ships an entire built-in CA.
2. **Authentication and authorization.** Already flagged as a deliberate gap for
   [the web console](../architecture/web-console.md) and
   [multi-machine node registration](../architecture/node-topology.md) — this closes both: anyone
   who can reach the API port today can register a node or submit a manifest. **Why it's worth
   building**: teaches the authn/authz split, and forces the scheduler and reconcilers to respect a
   decision made somewhere else, not just execute whatever request arrives.
3. **Audit logging.** Who submitted a given manifest, who deleted a tenant, when — distinct from
   the application logging this codebase already has. **Why it's worth building**: production
   systems need to be forensically inspectable after an incident, not just observable during normal
   operation.

## Priority 2: operational maturity

Instrumentation nobody consumes is decoration, not observability.

4. **Real metrics/tracing export.** `WorkerMetrics` (see
   [Observability](../architecture/observability.md)) defaults to an in-memory `SimpleMeterRegistry`
   that nothing external reads; `GimleTracing` defaults to a `LoggingSpanExporter` — spans are real
   and correctly parented, just logged rather than shipped anywhere. **Why it's worth building**:
   the gap between "instrumented" and "observable" is exactly the gap between a demo and an
   on-call-ready system.
5. **Centralized log aggregation.** Logs live per-node on local disk today; the
   [web console](../architecture/web-console.md) and CLI tail them live, but nothing searches a
   history once a node or instance is gone. **Why it's worth building**: "logs on disk" stops
   working somewhere between one machine and a hundred.
6. **Multi-metric autoscaling.** The cheapest item on this list: `AutoscaleReconciler` already
   computes real CPU utilization from live heartbeats and works correctly — it just needs
   request-rate/latency/queue-depth signals folded in alongside CPU to match what this project's
   own goals for scaling describe. **Why it's worth building**: reconciling several competing
   signals into one scaling decision is a small, self-contained version of a genuinely hard
   scheduling problem.

## Priority 3: workload diversity

Not every real workload is a stateless HTTP service.

7. **Batch/scheduled workloads.** No Job/CronJob equivalent — Gimlé only models long-running
   replicated deployments. **Why it's worth building**: run-to-completion is a fundamentally
   different lifecycle than run-forever, with different restart and scheduling semantics entirely.
8. **Per-node placement.** No DaemonSet equivalent — "exactly one instance per node" isn't an
   explicit scheduler mode, only resource-based bin-packing (see
   [Control plane](../architecture/control-plane.md)). **Why it's worth building**: a
   topology-driven placement constraint, genuinely different from the resource-driven one already
   built.
9. **Stateful workload support.** Deliberately last, not because it's unimportant — persistent
   storage with a lifecycle independent of the workload's own, plus ordered rollout and stable
   identity, is arguably the single most educational feature in all of Kubernetes. It's last here
   because it's the deepest rabbit hole, and Gimlé's module model today is inherently
   stateless-friendly.

## Priority 4: control-plane policy and fairness

10. **Explicit, configurable disruption budgets.** The rolling-update bookkeeping already appears
    to replace one instance at a time — a reasonable implicit default — but there's no exposed,
    tunable "max unavailable" the way real clusters make this an explicit contract rather than an
    implementation detail.
11. **Pluggable admission/policy.** Validation today is hardcoded (manifest schema checks, quota
    checks in [Multi-tenancy](../architecture/multi-tenancy.md)). No policy layer for
    organization-specific rules — the "policy as data, not code" pattern real clusters lean on
    heavily.
12. **Priority and preemption.** No notion of a higher-priority deployment evicting a lower-priority
    one under resource pressure — a genuinely hard fairness-versus-urgency scheduling problem.

## Acknowledged, deliberately not prioritized

Multi-cluster federation, ingress/external load balancing, DNS-based service discovery, secrets
rotation, and client SDKs beyond Java are all real gaps versus a production cluster — left off the
priority list because they teach less about cluster *mechanics* per unit of effort than the items
above, for a project whose goal is understanding how clusters are built, not building an ecosystem
around one.
