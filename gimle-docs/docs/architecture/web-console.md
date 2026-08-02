---
sidebar_position: 7
---

# Web console

The operator-facing web UI (`gimle-console`) — real data from a running control plane, no mocks,
no seeded state. Bun/Vite/React/TanStack Router; embedded into `gimle-controlplane`'s own jar and
served at `/console` with no separate deploy step (see
[Project structure](../contributing/project-structure.md)).

## Screens

Nine routes, each backed by a real `Http*Repository` hitting the control plane's own API — the
same data the [CLI](../reference/cli-reference.md) reads, not a parallel source of truth:

| Screen | Shows |
|---|---|
| Overview | Landing dashboard summarizing cluster state at a glance. |
| Deployments | List/create/inspect deployments — the UI equivalent of `gimle get/apply/delete deployment`. |
| Instances | Per-instance detail: lifecycle state, health, resource usage. |
| Nodes | Registered node agents and their reported capacity — the UI equivalent of `gimle get nodes`. |
| Topology | A real-time graph of the cluster's actual placement (which instances landed on which nodes/workers). |
| Metrics | Per-module dashboards backed by `WorkerMetrics` (see [Observability](./observability.md)). |
| Tenants | Tenant list and quota management — see [Multi-tenancy](./multi-tenancy.md). |
| Config | Tenant-scoped config/secrets, including encrypted values — see [Multi-tenancy](./multi-tenancy.md). |
| Logs | Live log tailing and crash-dump listing, below. |

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

## No authentication

Deliberate, not an oversight: the console has no auth today. Revisit before ever pointing it at
anything beyond a single local/trusted process.
