# Gimle Control Plane

The control plane is Gimlé's stateless API/scheduling/reconciliation tier: it accepts desired-state
manifests over HTTP, persists them through a `StoreClient` to the `gimle-mimir` Raft cluster over
the network (it embeds no store of its own), places instances via a two-dimensional bin-packing
scheduler, and drives every resource kind toward its desired state through a set of independent,
level-triggered reconcilers. Any number of `ControlPlaneMain` replicas can run concurrently against
the same store cluster; only one at a time actually reconciles, elected via a store-backed lease,
while every replica keeps serving reads and accepting writes.

## Process entrypoint

`ControlPlaneMain` starts one replica:

```
ControlPlaneMain <port> <secretKeyPath> --store-endpoints host1:clientPort1,... \
                  --fafnir-endpoint host:port
                  [--host <hostname>] [--muninn-endpoint host1:port1,...]
                  [--andvari-endpoint host:port[,host:port...]]
```

`--fafnir-endpoint` is mandatory — the control plane no longer performs secret cryptography itself
and always needs somewhere to proxy `/secrets/*`/`/config/*` decryption to. `--muninn-endpoint` and
`--andvari-endpoint` are both optional: without Muninn, `/logs/*` simply has no fallback for a gone
node/instance and this replica's own metrics/traces are shipped nowhere; without Andvari, only
manifests with a local `artifactPath` resolve (registry-coordinate manifests do not). Plaintext is
the default transport, and `-Dgimle.transport.protocol=tls` is the switch to require mTLS
everywhere, printed as a loud startup warning when left at the (deliberate) plaintext default.

At startup it wires together a `Scheduler`, nine reconcilers (see below), an `ApiServer`, and three
independently-scheduled tickers — lease renewal, the reconcile tick, and certificate rotation — each
on its own dedicated executor. They're kept separate (`ControlPlaneMain.scheduleIndependentTickers`)
so a slow or hung reconcile pass (a stuck store connection during a failover sweep, for instance)
can never starve this replica's own certificate rotation or cost it the reconciler-leader lease.
Only the replica currently holding the `reconciler-leader` lease (`StoreClient.tryAcquireOrRenewLease`
— the same non-replicated, leader-local primitive Kubernetes' own `coordination.k8s.io/v1 Lease`
serves for `kube-controller-manager`/`kube-scheduler` elections) runs the reconcile tick and seeds
the bootstrap account; every replica renews or attempts the lease every tick regardless, and rotates
its own certificate unconditionally since a non-leader replica still terminates client TLS
connections. It also resolves and serves a bundled `gimle-console` SPA off the classpath
(`BundledSpa`, at `/console`) automatically, with no separate build/copy/flag step.

## Scheduler

`Scheduler.place` bin-packs one replica onto a node via a fixed filter pipeline, each stage a strict
exclusion — if honoring a constraint would leave no candidate, placement fails outright rather than
silently violating it:

1. **Isolation tier** — the candidate must support the requested `IsolationTier`.
2. **Cordon** — an operator-cordoned node is excluded from new placements (never evicts what's
   already running there).
3. **Anti-affinity** — when requested, excludes any node already running another replica of the
   same deployment.
4. **Tenant isolation** — for `TIER_2`/`TIER_3` only, excludes a node already hosting a *different*
   tenant's instance (a no-op for `TIER_1`, since same-node Tier 1 density packing across separate
   deployments isn't implemented).
5. **Required labels** — excludes a node missing any of the manifest's `placement.requiredLabels`.

The survivors are sorted by free memory then free CPU (first-fit-decreasing) and the first with
enough headroom wins. `eligibleNodes` exposes the same first four/five-step filter without the final
pick, for `DaemonSetReconciler`, which places on *every* eligible node rather than choosing one. A
separate `stickyNodeId`-accepting overload backs `StatefulSetReconciler`: once an index's local-disk
volume exists on a node, every later placement collapses to "is that exact node still eligible?" —
tier/cordon/tenant/label checks still apply, but anti-affinity and candidate selection are skipped
entirely, and a sticky node that fails eligibility never falls back to a different one. `Scheduler`
is a pure function of its `NodeCandidate` inputs — it never reads the store itself.

## Reconcilers

Nine reconcilers run each tick, in a fixed order that only matters for same-tick convergence, not
correctness across ticks (each is independently retried and its failures isolated — one reconciler
throwing never blocks the ones scheduled after it in the same tick):

| Order | Reconciler | Resource |
|---|---|---|
| 1 | `ReplicaCountReconciler` | Releases assignments that are missing/stale before placement runs |
| 2 | `HealthReconciler` | Releases assignments that fail health checks |
| 3 | `AutoscaleReconciler` | Computes effective replica counts from per-module metrics |
| 4 | `QuotaReconciler` | Enforces tenant resource quotas |
| 5 | `DeploymentReconciler` | Fills every placement gap — deployments, rolling updates, surge |
| 6 | `CronJobReconciler` | Materializes due `JobSpec`s from `CronJobSpec` schedules |
| 7 | `JobReconciler` | Places and tracks run-to-completion `JobRun`s |
| 8 | `DaemonSetReconciler` | Places one instance per eligible node |
| 9 | `StatefulSetReconciler` | Sticky-node placement for stateful indices |
| — | `ServiceReconciler` | Recomputes each `ServiceSpec`'s live endpoint set |

Every reconciler here is **level-triggered, not edge-triggered**: each tick recomputes the full
desired set from the current store snapshot and reconciles the delta from scratch, rather than
reacting only to what changed since the last tick — so an empty store, a store where everything is
already converged, and everything in between all converge through the identical code path.
`DeploymentReconciler` is the canonical example: rolling updates piggyback on the same convergence
loop rather than a parallel mechanism (a mismatched-`moduleId` assignment is simply removed and
re-placed by the ordinary missing-index logic), and both the rollout's `maxUnavailable` and
`maxSurge` disruption budgets are tracked as persisted state so a reconciler restart doesn't lose
track of an in-flight migration. `ServiceReconciler` is the simplest instance of the pattern:
every tick, every `ServiceSpec`'s endpoint list is thrown away and recomputed via
`ServiceEndpointResolver` from scratch — an empty result (no ready backing instance yet) is treated
as an ordinary, valid outcome, not a fault.

## Admission

`AdmissionChain<T>` runs an ordered list of `AdmissionPlugin<T>`s against a submission (currently
`DeploymentSpec`), short-circuiting on the first rejection; a later plugin sees whatever spec an
earlier one allowed through, since a plugin may hand back a mutated spec rather than merely echo
the one it received. It replaced what used to be a single hardcoded tenant-quota check called
directly from `ApiServer`. Two plugins exist today:

- `TenantQuotaPlugin` — rejects a tenanted submission that would push the tenant past its
  `ResourceQuota`, summing against `maxCommittedInstances()` (`replicas + maxSurge`, the real peak
  a surging rollout could reach) rather than `replicas` alone. An unreadable artifact rejects the
  submission outright for a tenanted deployment, since admission has no way to verify quota without
  it.
- `PolicyConfigPlugin` — the network-policy/other declarative-config admission check.

`AdmissionChain` is deliberately a plain ordered list built once by its caller, not a
dynamic/webhook-based registry — nothing in this codebase needs runtime plugin registration yet.

## HTTP API surface

`ApiServer` is a plain `com.sun.net.httpserver.HttpServer`/`HttpsServer` — no framework dependency.
Every context is wrapped at registration time with request-count/latency/error Micrometer
instrumentation (`ApiServerMetrics`). The full route set:

| Resource | Routes |
|---|---|
| Deployments | `/deployments`, `/deployments/{name}` |
| Jobs / CronJobs | `/jobs`, `/jobs/{name}`, `/cronjobs`, `/cronjobs/{name}` |
| DaemonSets / StatefulSets | `/daemonsets`, `/daemonsets/{name}`, `/statefulsets`, `/statefulsets/{name}` |
| Endpoints | `/endpoints/{name}` — resolved live instance addresses for a deployment (consumed by `gimle-gateway`'s VESSEL routes and by `ModuleContext#relayControlPlaneRead`) |
| Services / NetworkPolicies | `/services`, `/services/{name}`, `/services/{name}/endpoints`, `/networkpolicies`, `/networkpolicies/{name}` |
| Nodes / Tenants | `/nodes`, `/nodes/{id}`, `/tenants`, `/tenants/{id}` |
| Config | `/config/{tenantId}/...` |
| Observability | `/metrics`, `/events`, `/audit`, `/logs/*`, `/metrics-history/*`, `/traces-history/*` |
| RBAC | `/roles`, `/roles/{name}`, `/rolebindings`, `/rolebindings/{name}`, `/accounts`, `/accounts/{name}` |
| Secrets (proxy to Fafnir) | `/secrets/{tenantId}/...`, `/secrets/rotate-key` |
| Artifacts (proxy to Andvari) | `/artifacts`, `/artifacts/{module}[/...]` |
| Auth | `/auth/login`, `/auth/logout`, `/auth/session` |
| PKI bootstrap (when a CA is configured) | `/bootstrap/csr`, `/bootstrap/csr/{id}`, `/bootstrap/tokens` |
| Console | `/console` — the bundled `gimle-console` SPA |

`/logs/*`, `/metrics-history/*`, and `/traces-history/*` fall back to `MuninnClient` when the owning
node/instance is gone; `/secrets/*` and `/config/*` decryption proxy to Fafnir via `FafnirClient`
(the control plane performs no cryptography itself); `/artifacts/*` proxies a *streaming* relay to
Andvari via `AndvariClient` (unlike the buffered byte relay used for secrets, since an artifact jar
should never sit whole in memory). `/bootstrap/csr` signs incoming node CSRs directly via
`CertificateAuthority`.

## Key packages

| Package | Role |
|---|---|
| `api` | `ApiServer` — the HTTP surface, RBAC enforcement, and every proxy client's call site |
| `schedule` | `Scheduler`, `NodeCandidate` — bin-packing placement |
| `reconcile` | The nine level-triggered reconcilers listed above |
| `admission` | `AdmissionChain`, `AdmissionPlugin`, `TenantQuotaPlugin`, `PolicyConfigPlugin` |
| `service` | `ServiceRegistry`, `ServiceEndpointResolver`, `ServiceEndpoint` — the Service (ClusterIP analogue) abstraction |
| `networkpolicy` | `NetworkPolicyRegistry` — a thin `StoreReader`/`MutationSink` facade over `NetworkPolicySpec` |
| `fafnir` / `muninn` / `andvari` | `FafnirClient`, `MuninnClient`, `AndvariClient`, `ArtifactResolver` — the network clients to the other Gimlé processes this one proxies to or resolves artifacts through |
| `autoscale` | `AutoscaleReconciler` — metrics-driven effective replica count |
| `tenant` | `TenantUsage` — resource-usage accounting used by `TenantQuotaPlugin`/`QuotaReconciler` |
| `pki` | `CaKeyMaterial`, `PendingCsrStore`, `BootstrapTokenRegistry` — node bootstrap CSR signing |
| `authz` | `BootstrapAccountFile` — seeding the first operator account |

## Notable design decisions

- **`ServiceRegistry` state is in-memory on `ApiServer`, not `StoreClient`-backed**, unlike every
  reconciler above — `ServiceReconciler` reads/writes it directly through the instance `ApiServer`
  exposes via `serviceRegistry()`, rather than through a store round-trip like every other
  reconciler.
- **The reconciler-leader election is a lease, not Raft leadership** — this process is not itself a
  Raft participant (that's `gimle-mimir`'s job), so `ApiServer` replica count is fully decoupled
  from the store cluster's own membership, and "exactly one active reconciler" has to be
  established independently via a store-backed lease rather than falling out of Raft for free.
- **Independent failure boundaries per reconciler and per ticker** — both are deliberate isolation
  choices so a stuck store connection or a bug in one reconciler degrades only its own resource
  kind, not the whole control plane.
- **Streaming vs. buffered proxying differ by sensitivity and size** — the secrets proxy buffers
  small values in memory since it needs typed request/response handling anyway; the artifacts proxy
  streams because a module jar should never be fully materialized in this process's heap.

## How other modules consume it

- `gimle-agent` node agents register, heartbeat, and poll `/networkpolicies` and `/services/*`
  against this API.
- `gimle-cli` and `gimle-console` are HTTP clients of this entire surface — every CLI subcommand and
  every console screen ultimately calls one of the routes above.
- `gimle-gateway` resolves VESSEL routes via `/endpoints/{name}` and SERVICE routes via
  `/services/{name}/endpoints`.
- `gimle-skald` and `gimle-bifrost` (in `gimle-agent`) poll `/services/*` the same way, never
  talking to `gimle-mimir` directly.
- `gimle-smoke-tests` and `gimle-holmgang` spawn a real `ControlPlaneMain` subprocess and drive it
  through this same API end to end.
