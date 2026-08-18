# Gimle Mimir

The Raft-replicated state store, as its own process. `StoreMain` holds every piece of cluster
desired/observed state — deployment and workload manifests, instance assignments, tenants, RBAC,
config/secret entries, node heartbeats — replicated across a cluster of store replicas via a
from-scratch Raft implementation, and serves it to callers (principally `gimle-controlplane`, but
also `gimle-fafnir` and `gimle-muninn` for authorization) over its own client-facing RPC protocol.
It is decoupled 1:N from the control plane's own replica count: a control-plane replica holds no
state of its own and talks to this cluster over the network via `StoreClient` rather than embedding
a store.

## Process role

`StoreMain <stateDir> <raftPort> <clientPort> [--host h] [--peers h:rp:cp,...] [--csr-endpoint h:p]`
wires together the embedded `StateStore`, the peer-to-peer `RaftTransport`, and the client-facing
`StoreTransport`/`StoreNode` — everything `gimle-controlplane` used to construct in-process before
the store was split out into its own process kind, minus anything API-server-shaped (no scheduler,
no reconcilers, no HTTP surface). `--peers` is bootstrap configuration only — where a brand-new
cluster starts, not a fixed configuration for its lifetime; `gimle-cli`'s add-peer/remove-peer
surface (`StoreClient#addServer`/`#removeServer`) grows or shrinks membership afterward, live,
etcd-style, one server at a time. In TLS mode, `StoreMain` also runs its own certificate-rotation
ticker so store replicas stay on CA-signed leaf certs from the same cluster CA every other Gimlé
process uses.

## Raft (`com.gimle.mimir.raft`)

`RaftNode` implements consensus directly against Figure 2 of Ongaro & Ousterhout's "In Search of an
Understandable Consensus Algorithm" — leader election, log replication, the `AppendEntries`
consistency check and conflicting-entry truncation, the commit-index term rule, and separate
`commitIndex`/`lastApplied` tracking — plus real production concerns the paper leaves as extensions:

- **Snapshotting** — log compaction past a fixed entry-count threshold, with `InstallSnapshot`
  chunked (Figure 13) rather than sent as one message.
- **Check-quorum** (dissertation §6.2 / etcd's `CheckQuorum`) — a leader that hasn't completed an
  RPC round trip with a majority of its voting peers within one election-timeout window steps down
  on its own, without waiting to observe a higher term.
- **Live membership change** — `AddServer`/`RemoveServer`, one server at a time, with a new server
  joining as a non-voting learner and only promoted to a full voting member once its match index is
  close enough to the leader's log to avoid stalling commits during catch-up.
- **`propose(StateMutation)`** is the sole entry point application code (an `ApiServer` handler, or
  a reconciler via `MutationSink`) ever calls; every RPC handler exists purely to serve replication
  among `RaftNode` peers.

A single `ReentrantLock` guards all mutable consensus state; RPCs to peers are sent without holding
it (they block on real I/O), with every response processed back under the lock. `RaftTransport`
carries the peer-to-peer wire protocol; `RaftLog` is the durable log/term/vote store on disk.

## Storage (`com.gimle.mimir.store`)

`StateStore` is the embedded, single-node, file-backed engine `RaftNode` applies committed entries
against: a directory of small YAML files, one per resource, written via temp-file-plus-atomic-move
so a crash mid-write never leaves a torn file a reader could observe. An in-memory index is rebuilt
from disk on construction; every mutation writes through to disk before being reflected in memory.
Deliberately not an embedded SQL engine or a hand-rolled binary format — a Raft-replicated log
already sits on top of this layer, so this is the least engineering that survives a process
restart, not a storage engine built to stand alone. `StoreReader` is the read-only interface both
`StateStore` and `StoreClient` implement, letting code like `Authorizer` work against either a
local store (tests) or a networked one (every real process) uninterested in the difference.

State held here spans several Kubernetes-shaped workload kinds, not just plain deployments:
`DeploymentSpec`/`InstanceAssignment`, `StatefulSetSpec`/`StatefulSetAssignment` (stable per-index
identity), `DaemonSetSpec`/`DaemonSetAssignment` (one-per-node, node-keyed), `JobSpec`/`JobRun`/
`JobPhase`, and `CronJobSpec` with its own last-fired-schedule tracking — plus `ServiceSpec` (the
ClusterIP-style service-endpoint abstraction), `NetworkPolicySpec` (deny-by-default per-tenant
policy), tenants, accounts, roles/role bindings, and config/secret entries. `LeaseGrant` backs
leader-election-style leases (e.g. for reconciler singleton work).

## Manifests (`com.gimle.mimir.manifest`)

One `record` spec type plus one `*ManifestParser` per workload kind (`DeploymentManifestParser`,
`StatefulSetManifestParser`, `DaemonSetManifestParser`, `JobManifestParser`,
`CronJobManifestParser`), each turning submitted YAML into the corresponding spec record. Shared
concerns — `PlacementConstraints` (anti-affinity, node selection), `AutoscalePolicy`,
`DisruptionBudget` (rolling-update pacing), `ConcurrencyPolicy`/`JobTemplate` (for cron/job kinds)
— live as their own types referenced from multiple specs rather than duplicated per kind. A spec's
own manifest never embeds the module artifact's contents (isolation tier, resource
request/limit, health probes) — those are read from the artifact itself once resolved, keeping
"artifact contents" and "runtime assignment" strictly separate; a manifest carries only the path or
registry coordinate needed to resolve it.

## Authorization (`com.gimle.mimir.authz`)

`Authorizer.authorize(principal, resource, verb, tenant, targetId)` resolves whether a principal
may perform an action, reading `RoleBinding`/`Role`/`Permission` straight from a `StoreReader` on
every call rather than caching — an authorization check happens once per request, not in a hot
loop, so there's no performance reason to diverge from every reconciler's own re-derive-from-store
posture. `Authorizer` lives here rather than in `gimle-core` specifically because it depends on
`StoreReader`; every process that needs its own authorization decision (`gimle-controlplane`
today, and `gimle-fafnir`/`gimle-muninn` independently re-checking a forwarded request rather than
trusting the proxy alone) already depends on `gimle-mimir` for `StoreClient`, so this adds no new
coupling in either direction — and it's what let secret/observability authorization move out of
`gimle-controlplane` without a dependency cycle back into it.

## Client RPC (`com.gimle.mimir.rpc`)

`StoreClient` is the client side of `StoreRpc` that `ApiServer`, every reconciler, and `Authorizer`
talk to in place of a direct `StateStore` reference — it deliberately mirrors every `StateStore`
read method's name and signature exactly, and implements `MutationSink` so it drops into any
constructor already written to accept one. Reads go to any configured endpoint, rotating on
transport failure (no leader-awareness needed for reads — a follower's own state can already be
slightly stale). Writes (`propose`, `putHeartbeat`, lease acquire/release) are leader-only: on a
`StoreRpc.NotLeader` response, the client follows the returned address and retries once, then
caches that endpoint as the preferred leader. `StoreNode` is the server-side counterpart handling
incoming `StoreRpc` requests against the local `RaftNode`/`StateStore`; `StoreTransport` carries
the wire protocol.

## Observability

`StoreMain` instruments every `StoreRpc` request with `StoreMetrics` (per-RPC-kind request count,
error count, latency), and, when `-Dgimle.store.muninnEndpoint` is configured, ships those metrics
and its own traces to `gimle-muninn` via `MuninnShipper` from `gimle-observability`.

## How other modules use this one

`gimle-controlplane` is this module's primary consumer: `ApiServer`, every reconciler, and
`NetworkPolicyRegistry`/`ServiceReconciler` all hold a `StoreClient` rather than any in-process
store. `gimle-fafnir` and `gimle-muninn` each depend on `gimle-mimir` for `Authorizer` and
`StoreClient` to independently re-check authorization against the same RBAC data, without a
dependency back onto `gimle-controlplane`. `gimle-cli`, `gimle-hilmir`, `gimle-smoke-tests`, and
`gimle-holmgang` all spawn or drive real `StoreMain` processes as part of a cluster.
