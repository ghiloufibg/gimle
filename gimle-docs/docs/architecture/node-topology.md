---
sidebar_position: 2
---

# Node topology

Six Java process roles run across a cluster — no other runtime, no containers, no sidecars:

```mermaid
graph TD
    subgraph Machine
        Agent["Node Agent<br/>(gimle-agent)"]
        Worker1["Worker JVM<br/>(gimle-worker)"]
        Worker2["Worker JVM<br/>(gimle-worker)"]
        Agent -->|spawns/supervises via Process API| Worker1
        Agent -->|spawns/supervises via Process API| Worker2
    end
    CP["Control Plane<br/>(gimle-controlplane, N replicas)"]
    Store["Store<br/>(gimle-mimir, M replicas, Raft-replicated)"]
    Fafnir["Fafnir<br/>(gimle-fafnir, K replicas)"]
    Muninn["Muninn<br/>(gimle-muninn, logs/metrics/traces sink)"]
    Agent -->|reports capacity/state, executes placement| CP
    Agent -->|fetches secret values directly, mTLS| Fafnir
    CP <-->|StoreRpc, TCP| Store
    CP -->|proxies /secrets/*, encrypt/decrypt/rotate-key, mTLS| Fafnir
    Fafnir <-->|StoreRpc, TCP| Store
    Muninn <-->|StoreRpc, TCP, read-only| Store
    Worker1 -.->|health/metrics over a local control channel| Agent
    Worker2 -.->|health/metrics over a local control channel| Agent
    Agent -.->|ships own logs + relays supervised workers' logs/metrics/traces| Muninn
    CP -.->|ships own request metrics/traces| Muninn
    Fafnir -.->|ships own request metrics/traces| Muninn
    Store -.->|ships own RPC metrics/traces| Muninn
    CP -->|proxies /logs/*, /metrics-history/*, /traces-history/*| Muninn
```

The control plane, the store, and Fafnir are three independently-scalable process kinds, not
one — the same split Kubernetes draws between `kube-apiserver` and `etcd`, extended one step
further for secret material specifically (see [Control plane](./control-plane.md)); `N`, `M`, and
`K` above need not match. Muninn (below) is a fourth, similarly independent process kind — a
unified sink every other process ships logs/metrics/traces to, rather than each owning its own
export path.

## Node Agent

One JVM per machine (`gimle-agent`). Owns the machine: spawns and supervises worker JVM processes
via the plain `Process` API (`WorkerProcessSupervisor`), assigns each worker's resource limits
(portable JVM flags today — see [Tiered isolation](./tiered-isolation.md)), reports machine
capacity and observed state to the control plane, and executes placement directives it receives.
It **never runs user code**, so a misbehaving module can't crash it — `ControlChannelServer` is the
local channel workers report over (`WorkerConnection` on the agent side).

## Worker JVM

Hosts module instances inside `ModuleLayer`s (`gimle-worker`). Started with limits derived from
its assigned modules' resource requests. Runs `BoundedModuleScheduler` (the bounded virtual-thread
scheduler each instance runs under) and `ProbeLoop` (calls each module's `LivenessProbe`/
`ReadinessProbe` directly — no HTTP, no sidecar). Disposable by design: the agent can
`destroyForcibly` and respawn one without touching anything else on the machine.

## Control Plane

One or more JVMs (`gimle-controlplane`). Owns the API server, the scheduler, and the reconcilers,
talking to a separate `gimle-mimir` store cluster over the network rather than embedding a state
store directly — see [Control plane](./control-plane.md) for how those pieces fit together.

## Store

One or more JVMs, Raft-replicated for HA (`gimle-mimir`). The etcd-equivalent piece: owns the
Raft-replicated `StateStore` and answers `StoreRpc` requests from every `gimle-controlplane`
replica. Decoupled from the control plane's own replica count on purpose — a control-plane
process can restart or scale independently of store/Raft membership, and vice versa.

## Fafnir

One or more stateless JVMs (`gimle-fafnir`) — the dedicated secrets service: owns the encryption
key ring, performs every encrypt/decrypt/rotate-key operation, and answers the versioned
`/secrets/*` API directly (see [Multi-tenancy](./multi-tenancy.md) for the tenant-scoped secret
model). Talks to the same `gimle-mimir` store
cluster over the network via its own `StoreClient`, exactly the way `gimle-controlplane` does — it
persists nothing locally beyond its own key-ring file. `gimle-controlplane` never performs crypto
itself; it proxies `/secrets/*` calls to Fafnir and forwards the calling principal's identity as
an internal claim, but Fafnir still authorizes every request independently against RBAC data it
reads itself, rather than trusting "this arrived from the control plane" as proof of
authorization. `gimle-agent` fetches secret values needed by a deployed module directly from
Fafnir over mTLS — not proxied through the control plane — authorized by the node's own
certificate identity and its current tenant assignments. Fafnir gets its own distinct certificate
identity minted at cluster-bootstrap time, not a borrowed one, so every action it takes is
attributable to its own certificate Subject in the audit log.

## Muninn

One or more stateless JVMs (`gimle-muninn`) — a unified sink for logs, metrics, and traces shipped
from every other process, replacing what would otherwise be a separate exporter path per process
kind. `gimle-agent` ships its own platform log plus every supervised worker's logs, metrics, and
traces (workers have no outbound network identity of their own, so a worker relays its own periodic
metrics snapshot and exported span batches to its agent over their existing control channel, which
the agent then forwards to Muninn byte-for-byte under a new `WORKER` processKind —
`{nodeId}:{workerId}`, see [Observability](./observability.md)); `gimle-controlplane`,
`gimle-fafnir`, and `gimle-mimir` each ship their own request metrics and traces directly, since
none of the three has a supervising agent. Shipping is entirely optional and best-effort — a
process with no Muninn endpoint configured behaves exactly as it did before Muninn existed (local
log tailing, no metrics/traces export), never blocked or degraded by Muninn being unreachable.

Storage is day-bucketed JSON-lines files under Muninn's own data root, keyed by node/instance for
logs and by process kind + process ID for metrics and traces — deliberately not a new storage
engine, just the same file-per-day shape `gimle-agent`'s own log rotation already uses, extended to
a cluster-wide store. Muninn holds a read-only `StoreClient` against the same `gimle-mimir` cluster
every other process talks to, and re-runs its own independent `Authorizer.authorize(...)` check on
proxied reads rather than trusting an already-forwarded principal claim as proof by itself — the
same defense-in-depth posture Fafnir established for `/secrets/*`.

`gimle-controlplane`'s existing `/logs/*` proxy falls back to Muninn's shipped history whenever a
live agent genuinely can't be reached (a gone node, an unreachable agent) instead of a bare
404/502; `GET /metrics-history/*` and `GET /traces-history/*` are new, Muninn-only read surfaces
with no live-process equivalent — a process's own metrics/traces only ever live in Muninn's shipped
history, never served directly by the process itself the way logs can be. Muninn gets its own
distinct certificate identity minted at cluster-bootstrap time, the same reasoning Fafnir's own
identity has: every ingest/read decision it makes is attributable to its own certificate Subject,
not a borrowed one.

## Multi-machine deployment

Nothing here is loopback-only — the `127.0.0.1` addresses in the local-dev walkthrough are
convenience defaults, not an architectural limit:

- **`ApiServer` listens on every network interface**, not just loopback (`new
  InetSocketAddress(port)` — Java's wildcard-bind constructor), so it's reachable from another
  machine on the network with no code change.
- **A node agent's control-plane and gossip addresses are real, configurable network locations.**
  `AgentMain` takes `<controlPlaneBaseUrl>` and `<gossipBindHost:port>` as genuine arguments; the
  `mvn gimle:agent` convenience goal's `gimle.agent.controlPlaneUrl`/`gimle.agent.gossipAddress`
  properties (see [`gimle-maven-plugin` goal reference](../reference/maven-plugin-goals.md)) are
  just where those defaults happen to point for a single-machine local cluster.
- **Membership gossip joins via `seeds`** — a list of other agents' real `host:port` addresses,
  the whole mechanism by which an agent on one machine finds agents on others (see
  [Service fabric](./service-fabric.md)).

:::info[Authentication and authorization]

A control plane bound to every interface now requires a real identity and a real permission for
every route — see [Authentication and authorization](./authn-authz.md). A node certificate can only
reach its own self-service endpoints (`register`/`heartbeat`/`assignments`, its own logs); nothing
else, including registering as a *different* node, is possible without an explicit `RoleBinding`.
This only applies in TLS mode (`gimle.transport.protocol=tls`) — plaintext mode remains fully open,
the same "local, trusted process" posture it always had.

:::

## Three failure domains, three recovery costs

Node failure, worker failure, and module failure are distinct events, reconciled at distinct
costs — this is why the tiered self-healing model exists, not an accident of implementation:

| Failure | Recovery action | Typical cost |
|---|---|---|
| Module | Dispose its `ModuleLayer`, re-instantiate | Milliseconds |
| Worker | `destroyForcibly`, respawn (AppCDS-accelerated) | Sub-second |
| Machine/node | Reschedule its modules onto other machines | Seconds |

Repeated module restarts escalate to a worker restart; repeated worker restarts escalate to
rescheduling elsewhere, with `CrashLoopBackOff`-style backoff at each level — the same escalation
shape Kubernetes uses, implemented directly in Java rather than delegated to a container runtime.
