---
sidebar_position: 3
---

# Roadmap: closing the gap with a real-world cluster

Gimlé's goal is not to compete with Kubernetes — it's to learn how a cluster is actually built by
building one. This page tracks the gap between what exists today and what a production cluster
(Kubernetes-with-etcd, in practice) provides, ordered by priority and by how much building each one
actually teaches, not by a feature-parity checklist.

## Priority 1: control-plane policy and fairness

1. **Priority and preemption.** No notion of a higher-priority deployment evicting a lower-priority
   one under resource pressure — a genuinely hard fairness-versus-urgency scheduling problem.
2. **Exporting Muninn's data to an external observability stack.** Muninn's own first-party
   ingest/read APIs (`/ingest/metrics/*`, `/metrics/*`, `/ingest/traces/*`, `/traces/*` — what
   `gimle-controlplane`'s `GET /metrics-history/*`/`GET /traces-history/*` proxy onto, see [Web
   console](../architecture/web-console.md#metrics-history-traces-and-audit-trail)) are deliberately
   not wire-compatible with any third-party collector — a considered trade-off (see
   [Observability](../architecture/observability.md)), not an oversight: staying dependency-free
   and self-contained was preferred over out-of-the-box Grafana/Jaeger compatibility. Deferred to
   last priority — it recovers ecosystem compatibility for existing data rather than closing a
   cluster-mechanics gap the way item 1 above does. **Why it's worth building**: an opt-in,
   push-based export from Muninn over OTLP/HTTP to a self-hosted Loki/Tempo/Mimir stack, driven off
   the day files Muninn already retains, would recover that compatibility without giving up the
   first-party ingest/storage path underneath it. A Prometheus scrape endpoint is explicitly *not*
   the shape this takes: Gimlé carries no Prometheus support, and a scrape of an aggregating sink
   needs staleness and authentication semantics Muninn's reads cannot offer a scraper.

## Acknowledged, deliberately not prioritized

Multi-cluster federation (including federating audit trails or observability data specifically —
each cluster's audit trail and Muninn's shipped logs/metrics/traces stay in that cluster's own
store, matching every other piece of cluster state today), ingress/external load balancing, and
client SDKs beyond Java are all real gaps versus a production cluster — left off the priority list
because they teach less about cluster *mechanics* per unit of effort than the items above, for a
project whose goal is understanding how clusters are built, not building an ecosystem around one.
(Secret master-key rotation itself is not on this list — Fafnir's
`POST /secrets/rotate-key` already does it; see [Multi-tenancy §
Secrets](../architecture/multi-tenancy.md#secrets).)

A new Raft-replicated cluster (`gimle-mimir`-shaped) for logs/metrics/traces is deliberately not on
this list at all, unlike the items above — it's a rejected alternative from the observability
design, not a deferred one: that data is high(er)-volume and archival rather than authoritative, so
it needs a durable place shipped-to data ends up readable from (what Muninn provides), not
consensus. See [Node topology](../architecture/node-topology.md#muninn).
