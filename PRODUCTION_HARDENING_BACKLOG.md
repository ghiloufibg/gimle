# Gimlé Production-Hardening Backlog

Gap analysis of the codebase at `master` (`d501cb2`), benchmarked against how real production
distributed systems solve the same problems: Kubernetes, Kafka (KRaft), Elasticsearch, and
Spark, plus — since those four are illustrative starting points, not the boundary of the
comparison — etcd, HashiCorp Consul/Nomad/Vault, Apache Cassandra, Apache ZooKeeper, Envoy/
Istio, Erlang/OTP supervision trees, Akka Cluster, and Temporal where any of them is a closer
or more precise match for a given mechanism than the original four. Gimlé is an educational
JPMS/`ModuleLayer` teaching project, not a product aimed at real users — the goal here is not
"compete with these systems," it's "where does the implementation stop matching the production
pattern it's teaching, and is that gap a deliberate, documented simplification or an
unintentional one worth closing."

Findings below come from four domain audits (control-plane/consensus, data-plane runtime,
service fabric/observability, security/engineering-rigor) that each read the real
implementation with file:line evidence — not just class/package names — and compared it to
a specific named mechanism in a specific real system, drawn from that broader pool wherever
it sharpens the comparison.

Every finding lists **Value** (educational/architectural payoff of fixing it), **Difficulty**
(S/M/L), and **Fit** (Yes/Partial/No — can one self-contained Claude Code session finish it).
Sections are ordered so a reader/implementer can work top-to-bottom and always be doing the
highest-value, most tractable thing next.

---

## How to use this document

This backlog lives on the `production-hardening` branch, which is meant to accumulate the
actual fix commits over time — pick an item, branch/commit against it, check it off. Items
are grouped into:

- **P0 — Fix first**: high value, small/medium effort, single-session fit. Do these.
- **P1 — Big rocks**: high value but large or multi-session in scope. Worth doing, needs to
  be broken into its own sub-plan first.
- **P2 — Solid improvements**: medium value, mostly single-session, good next batch after P0.
- **P3 — Backlog / optional**: low value, large effort, or genuinely a documented deliberate
  tradeoff flagged here only for completeness — do not prioritize these.
- **Not gaps**: things the audit checked and found already correct or already an explicit,
  documented design decision. Listed so nobody re-discovers them as "gaps" later.

---

## P0 — Fix first (high value, tractable)

### P0-1. Raft log entries and term/vote state are never fsynced before being acknowledged as durable
- **Module**: `gimle-mimir`
- **Evidence**: `AtomicFiles.writeAtomically` (`gimle-mimir/src/main/java/com/gimle/mimir/store/AtomicFiles.java:21-35`) does `Files.write` + atomic rename with no `FileChannel.force`/fsync; called from `RaftLog.append` (`RaftLog.java:76-79`) and `RaftLog.setTermAndVote` (`RaftLog.java:68-72`) before entries are treated as safely appended.
- **Real-world comparison**: ZooKeeper's transaction log (`SyncRequestProcessor`) fsyncs before ack, which is where this "sync before you tell anyone" discipline for coordination services originates; etcd's raft WAL calls `fileutil.Fsync` before an entry counts as persisted, and CockroachDB's Pebble/RocksDB engine does the same under its Raft layer; Kafka's metadata log fsyncs per its flush policy before advancing the high-watermark used for acks.
- **Gap**: An entry can be acked as `success=true` (or locally committed/applied) while still sitting only in the OS page cache. A node crash before flush silently loses data Raft already promised was durable — a genuine safety violation of the paper's own "before responding" durability requirement, not a modeled simplification.
- **Value**: High — this is *the* teaching point about why Raft requires durable-before-ack.
- **Difficulty**: Small · **Fit**: Yes

### P0-2. `placement.requiredLabels` is parsed and persisted but never consulted by the scheduler
- **Modules**: `gimle-mimir`, `gimle-controlplane`
- **Evidence**: `DeploymentManifestParser.parseRequiredLabels` (`gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentManifestParser.java:117-151`) round-trips through `StateStore.java:721`; `NodeCandidate` (`gimle-controlplane/src/main/java/com/gimle/controlplane/schedule/NodeCandidate.java:20-25`) has no node-label concept at all, and `Scheduler.place` (`Scheduler.java:24-100`) never filters on them.
- **Real-world comparison**: Kubernetes' `nodeSelector`/`nodeAffinity` actually filter the candidate set in `kube-scheduler`; HashiCorp Nomad's `constraint` stanza and Apache Mesos' resource-offer attribute matching do the equivalent filtering for their own schedulers — Nomad in particular is a closer scale/complexity match for `gimle-controlplane`'s scheduler than Kubernetes is.
- **Gap**: A manifest declaring `placement.requiredLabels: [gpu]` is accepted with no error, but instances land on any node regardless — a silent dead feature that teaches the wrong lesson about what the field does.
- **Value**: High — exactly the "looks implemented, isn't" class of bug this audit exists to catch.
- **Difficulty**: Small · **Fit**: Yes

### P0-3. Module restart-budget exhaustion is a dead end — no escalation to worker or machine tier
- **Module**: `gimle-worker`
- **Evidence**: `WorkerRuntime.restartModule` (`gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java:224-241`) — when the retry budget is exhausted, it only logs and calls `onModuleRestartBudgetExhausted`, which in `WorkerMain.java:144` is literally `id -> log.error(...)`. Module state is left `ACTIVE`, so `AgentMain`'s `alive` flag never flips and `HealthReconciler`'s machine-tier reschedule never fires.
- **Real-world comparison**: Kubernetes' `CrashLoopBackOff` never gives up — it caps backoff at 5 minutes and keeps retrying, so there's always a next action. This is also the exact shape of Erlang/OTP supervision trees, the direct intellectual ancestor of Gimlé's own tiered self-healing story: when a worker process exceeds its supervisor's `max_restarts`/`max_seconds` intensity, the supervisor itself terminates and escalates to *its* supervisor, restarting a whole subtree — there is always a next tier, never a silent dead end.
- **Gap**: A module that structurally restarts fine but always fails liveness becomes a permanent zombie — the module→worker→machine escalation chain CLAUDE.md describes is broken at the very first hop.
- **Value**: High · **Difficulty**: Small · **Fit**: Yes

### P0-4. Real inbound fabric traffic bypasses both the concurrency bound and the drain mechanism
- **Modules**: `gimle-module`, `gimle-fabric`, `gimle-worker`
- **Evidence**: `SimpleModuleContext.beginRequest`/`endRequest` (`gimle-module/src/main/java/com/gimle/module/lifecycle/SimpleModuleContext.java:41-48`) have **zero** call sites outside their own definitions; `ModuleController.awaitDrain` (`ModuleController.java:286-295`) polls `inFlightCount()`, which can therefore never be nonzero; `FabricServer` (`gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricServer.java:154,169,248`) dispatches inbound RPCs on a raw unbounded virtual-thread-per-connection, calling `method.invoke` directly — never through `BoundedModuleScheduler` or `ModuleContext`.
- **Real-world comparison**: Kubernetes' `preStop` + endpoint deregistration + grace period genuinely waits for real in-flight connections; Kafka broker shutdown drains real in-flight requests; Envoy's hot-restart connection draining and Akka Cluster's `CoordinatedShutdown` phases both make "stop accepting new work, finish what's in flight, then terminate" an explicit, ordered protocol rather than an immediate teardown.
- **Gap**: `ModuleController.stop()`'s drain wait and the "bounded virtual-thread scheduler per instance" CLAUDE.md describes are both real, tested mechanisms — but neither is wired to the actual request path. `stop()` proceeds to disposal regardless of genuinely in-flight fabric calls, and nothing caps per-instance concurrency.
- **Value**: High · **Difficulty**: Medium · **Fit**: Yes (thread `beginRequest`/`endRequest` + the scheduler through `FabricServer.invokeLocally`)

### P0-5. Metrics pipeline for autoscaling and observability is entirely disconnected — dead wiring
- **Modules**: `gimle-agent`, `gimle-os`, `gimle-observability`, `gimle-fabric`
- **Evidence**: `AgentMain.observationJson` (`gimle-agent/src/main/java/com/gimle/agent/AgentMain.java:459-476`) never sets `cpuMillicoresUsed`/`memoryBytesUsed`/`requestRatePerSecond`/`queueDepth`; `PortableJvmFlagsResourceLimiter.currentUsage()` (`gimle-os/src/main/java/com/gimle/os/portable/PortableJvmFlagsResourceLimiter.java:38-48`) unconditionally returns `(0, 0)`; `WorkerMetrics` (`gimle-observability/src/main/java/com/gimle/observability/WorkerMetrics.java:25-68`) is instantiated only in its own test — no caller anywhere in `gimle-worker`/`gimle-fabric`/`gimle-agent`; `FabricServiceRegistry.invokeRemote`/`FabricServer.dispatch` never record latency/error/traffic.
- **Real-world comparison**: kubelet/cAdvisor poll real cgroup counters feeding HPA and OOM decisions; Kafka/ES record request rate/latency/errors directly in the request path, not as disconnected instrumentation; the Nomad Autoscaler and Cassandra's `nodetool`/JMX metrics are built the same way — the scaling/ops decision is driven by a real, continuously-updated counter, never a field that's always zero.
- **Gap**: `AutoscaleReconciler` computes CPU utilization from a field that is architecturally guaranteed to always be `0` — autoscaling can mathematically never scale up. This is dead wiring, not a documented deferral (unlike cgroup enforcement, which *is* documented as deferred).
- **Value**: High · **Difficulty**: Medium · **Fit**: Partial (wiring `WorkerMetrics` + JVM-self-reported usage through the heartbeat is one session; true per-module JFR CPU attribution is more)

### P0-6. Reconciler backoff/grace-period state lives only in process memory, resets on controller failover
- **Module**: `gimle-controlplane`
- **Evidence**: `HealthReconciler`'s `restartTrackers`/`pendingRetry`/`permanentlyFailed` and `ReplicaCountReconciler`'s `firstSeenMissingAt` (`gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/HealthReconciler.java:44-46`, `ReplicaCountReconciler.java:48`) are plain `ConcurrentHashMap`s, never written through `MutationSink`/`StateMutation`. Reconcile ticks only run on whichever `ApiServer` replica holds the `reconciler-leader` lease (`ControlPlaneMain.java:123-146`), which can move.
- **Real-world comparison**: Kubernetes stores `CrashLoopBackOff`-relevant state in the Pod's own etcd-backed status precisely so controller-manager restarts don't reset it; Temporal's whole premise ("durable execution") is that workflow/retry state survives worker crashes because it's persisted, not held in a worker's memory; Akka Cluster Sharding persists rebalance/shard state via a cluster singleton plus replicated data rather than trusting one node's local memory to survive a handoff.
- **Gap**: When the reconciler-leader lease moves, every instance's restart backoff clock and "give up, stop retrying" marker silently resets — a replica already given up on can be rescheduled again, right when the system is least stable. This is the one place these reconcilers violate the "level-triggered, converge from any starting state" rule CLAUDE.md calls the hardest-to-test, most important correctness property in the codebase.
- **Value**: High · **Difficulty**: Medium · **Fit**: Yes (persist backoff state as `StateMutation`s, or re-derive from observation history each tick)

### ~~P0-7. No authentication/authorization by default~~ — reclassified, not a gap
See [Not gaps](#not-gaps--verified-correct-or-already-an-explicit-documented-tradeoff) below —
PLAINTEXT-by-default is a deliberate project decision (frictionless local testing/usage), not an
oversight. Original finding kept there for the record, with the one actionable sliver (a startup
banner) demoted to P3.

### P0-8. Secrets and plain config share one RBAC resource kind
- **Module**: `gimle-core`, `gimle-controlplane`
- **Evidence**: `ResourceKind` (`gimle-core/src/main/java/com/gimle/core/authz/ResourceKind.java:12-20`) has a single `CONFIG` entry, no `SECRET`; `ApiServer.handleListConfig` (`ApiServer.java:958-970`) returns every entry for a tenant already decrypted, gated only by `CONFIG:READ`.
- **Real-world comparison**: Kubernetes RBAC treats `configmaps` and `secrets` as distinct resource types so a role can grant one without the other; HashiCorp Vault goes further with per-path policies and dedicated, leased secret engines rather than any config/secret conflation.
- **Gap**: A role granted `CONFIG:READ` for dashboarding purposes silently also grants read access to every decrypted secret under that tenant — RBAC granularity doesn't match the sensitivity split the `encrypted` flag already implies.
- **Value**: High · **Difficulty**: Small · **Fit**: Yes

### P0-9. `FabricClient` has no connect or read timeout
- **Module**: `gimle-fabric`
- **Evidence**: `FabricClient.connect` (`gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricClient.java:33-51`) calls `connect(endpoint)` with no timeout; no `setSoTimeout`/connect-timeout anywhere under `gimle-fabric/src/main`.
- **Real-world comparison**: Envoy cluster config always sets `connect_timeout` + per-route `timeout`, and gRPC's deadline propagation makes an explicit timeout mandatory on every call; a timed-out upstream counts as an error for outlier detection just like a connection failure.
- **Gap**: A remote endpoint that accepts the connection but never responds (half-open, GC pause, deadlock) hangs the calling virtual thread forever. `CircuitBreaker.recordFailure()` is only reached on `IOException`, so a hung endpoint never trips the breaker — the exact "slow/stuck node" scenario outlier ejection exists to catch is currently undetectable.
- **Value**: High · **Difficulty**: Small · **Fit**: Yes

### P0-10. No CI and no installed git hooks enforce any of the documented "binding, not optional" conventions
- **Repo-wide**
- **Evidence**: No `.github/workflows`, `Jenkinsfile`, or `.gitlab-ci.yml` anywhere; `.git/hooks/` contains only default `*.sample` files — no real `pre-commit`/`commit-msg` script and no tracked install step for the ones CLAUDE.md/`gimle-PROJECT-v2.md` describe.
- **Real-world comparison**: any project claiming "pre-commit runs `mvn verify` and blocks on failure" backs it with either a committed hook-install step or branch-protection CI.
- **Gap**: The documented conventions (AI-attribution rejection, mandatory `mvn verify` before commit) are currently aspirational text with zero enforcement in the actual repo — a broken build or a policy-violating commit can reach `master` unchecked.
- **Value**: High · **Difficulty**: Small · **Fit**: Yes (a GitHub Actions workflow running `mvn verify` + a tracked hook-install script closes both halves)

### P0-11. Convergence-from-arbitrary-starting-state tests exist for only 2 of 5 reconcilers
- **Module**: `gimle-controlplane`
- **Evidence**: Only `DeploymentReconcilerTest` (`.../DeploymentReconcilerTest.java:148-167`) and `QuotaReconcilerTest` (`.../QuotaReconcilerTest.java:106`) are explicitly shaped as "arbitrary starting state converges" tests. `HealthReconcilerTest`, `ReplicaCountReconcilerTest`, `AutoscaleReconcilerTest` only cover individual transitions.
- **Real-world comparison**: Kubernetes controller test suites lean heavily on table-driven "given this arbitrary observed+desired state, converge to this" tests for exactly this property.
- **Gap**: CLAUDE.md states this test shape is mandatory for reconcilers; 3 of 5 don't have one, so a regression in level-triggered behavior (e.g. a hidden dependency on tick ordering, see P0-6) wouldn't necessarily be caught.
- **Value**: Medium-High (directly enforces the project's own stated rule) · **Difficulty**: Small · **Fit**: Yes

---

## P1 — Big rocks (high value, needs its own sub-plan)

### P1-1. Tier 1 "shared worker JVM density" is not implemented at the agent
- **Module**: `gimle-agent`, `gimle-controlplane`
- **Evidence**: `AgentMain`'s own javadoc (`gimle-agent/src/main/java/com/gimle/agent/AgentMain.java:64-69`) admits "each replica gets its own worker JVM"; `reconcileAssignments`/`instanceKey` (`:533-549`, `:869-871`) spawns one `WorkerProcessSupervisor` per instance unconditionally, tier is never consulted; `Scheduler.java:44-47`'s own comment: "Tier 1 density packing across separate deployments isn't implemented anywhere in this codebase today."
- **Real-world comparison**: Kubernetes packs many pods onto one kubelet/containerd node; Gimlé's own pitch is the JVM-analogue of that for Tier 1 — many modules in *one* worker JVM. The closer analogues are actually one runtime down in abstraction: Apache Karaf/Felix (OSGi containers, CLAUDE.md's own stated ancestor for the module system) genuinely host many bundles in one JVM as their density lever, and the Erlang BEAM VM's lightweight-process model — millions of isolated, independently-supervised processes sharing one VM — is the platform-level precedent for "many isolated units, one runtime, real density."
- **Gap**: The central architectural claim "Tier 1 — module in a shared worker JVM… Density win" (CLAUDE.md, "Core architecture") is not realized by any code path today. `gimle-worker` can technically host multiple `ModuleLayer`s in one JVM, but nothing in the placement/spawn loop ever routes two Tier-1 instances to an already-running worker. Self-disclosed in a code comment, but not listed among CLAUDE.md's documented deferrals.
- **Value**: High — this is the platform's core density claim, currently unrealized · **Difficulty**: Large · **Fit**: No (needs agent-side worker reuse, scheduler assign-to-existing-worker logic, and protocol changes — scope as its own multi-session initiative)

### P1-2. No queryable event log, despite the spec naming it the primary debugging surface
- **Modules**: `gimle-module`, `gimle-controlplane`, `gimle-core`
- **Evidence**: `gimle-PROJECT-v2.md:115` promises a structured event log "queryable via the API server — the `kubectl describe` equivalent." `LifecycleEvent` (`gimle-module/src/main/java/com/gimle/module/lifecycle/LifecycleEvent.java:8-29`) is only consumed transiently (mapped to a log line) in `WorkerRuntime.java:110-114`/`WorkerMain.java:121-123`, never persisted. Restart-attempt history lives only in an in-memory `RestartTracker` field with no getter, no durable snapshot, and no `/events` route in `ApiServer`.
- **Real-world comparison**: Kubernetes' Events API / `kubectl describe pod` surfacing `BackOff`/`Killing`/`Started` with counts; Nomad's own `nomad alloc status` Task Events stream (a much closer scale/complexity match than Kubernetes for `gimle-cli`) is the same idea — a durable, per-allocation timeline an operator reads after the fact, not just a moment-in-time log line.
- **Gap**: There is no way, via CLI or console, to answer "why did instance X restart 3 times" after the fact.
- **Value**: High · **Difficulty**: Large · **Fit**: Partial (a durable event append + a `/events` list endpoint is one session; a rich query language is more)

### P1-3. No metrics API endpoint and no real per-module RED (rate/errors/duration) data anywhere in the console
- **Modules**: `gimle-controlplane`, `gimle-console`
- **Evidence**: No `/metrics` route in `ApiServer`; `gimle-console/src/routes/metrics.tsx:1-60` builds its charts from lifecycle/placement/capacity/quota stores — none of it request latency/error/traffic (which, per P0-5, isn't collected anyway).
- **Real-world comparison**: Kafka/ES dashboards surface per-topic/per-index request rate, p99 latency, error rate as first-class panels; Consul and Nomad both expose a `telemetry` stanza feeding Prometheus for exactly this operator-facing purpose.
- **Gap**: The console's "Metrics" screen shows placement/capacity/quota signals only — no golden-signal fabric-call data, because nothing collects it and nothing exposes it.
- **Value**: High · **Difficulty**: Large · **Fit**: Partial (depends on P0-5 landing first; then a `/metrics` endpoint + console panel is a further session)

### P1-4. No wire-protocol versioning on any fabric codec
- **Module**: `gimle-fabric`
- **Evidence**: `FabricCodec` (`gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricCodec.java:22-161`), `SwimCodec` (`SwimCodec.java:20-154`), `ServiceCatalogCodec` (`ServiceCatalogCodec.java:30-130`) all decode strictly positionally with no schema/version field; payloads use raw `ObjectOutputStream` (`ObjectMarshalling.java:21-39`), coupling wire compatibility to exact class/serialVersionUID match.
- **Real-world comparison**: Kafka carries `api_key`+`api_version` on every request and negotiates via `ApiVersionsRequest`, enabling rolling upgrades; Cassandra's native protocol does the same with an explicit `STARTUP`/`SUPPORTED` version handshake, and Consul's `-raft-protocol` flag lets a cluster negotiate Raft protocol versions specifically to support rolling upgrades without downtime.
- **Gap**: Any future field addition/reorder, or a rolling upgrade running two Gimlé versions briefly, breaks decoding silently or throws, with no negotiation path.
- **Value**: High · **Difficulty**: Medium · **Fit**: Partial (adding a version byte + rejecting unknown versions is one session; full negotiation is more)

### P1-5. Static Raft cluster membership — no online reconfiguration
- **Module**: `gimle-mimir`
- **Evidence**: `StoreMain.java:52,66,154-163` parses `--peers` once at process start; no `AddServer`/`RemoveServer`/joint-consensus RPC anywhere in `RaftRpc.java`.
- **Real-world comparison**: the Raft paper's §6 joint-consensus protocol; etcd's `MemberAdd`/`MemberRemove`; Kafka KRaft's dynamic quorum reconfiguration. HashiCorp Consul's "Autopilot" feature is arguably the most apt comparison for Gimlé's own operational ethos — it automates peer add/remove and dead-server cleanup specifically so cluster operators don't have to reason about joint consensus by hand, which is the operational-simplicity direction this gap should eventually move toward.
- **Gap**: Growing/shrinking/replacing the store cluster requires a full restart with a new peer list — no safe, live way to do it, unlike real Raft deployments.
- **Value**: High — membership change is one of the trickiest, most instructive parts of Raft, currently entirely absent · **Difficulty**: Large · **Fit**: No (scope as its own project)

### P1-6. Scheduler is a single-pass filter+greedy-spread — no weighted multi-priority scoring, taints, or preemption
- **Module**: `gimle-controlplane`
- **Evidence**: `Scheduler.place` (`Scheduler.java:89-100`) — one comparator (free memory, then free CPU), single `filter(...).findFirst()`.
- **Real-world comparison**: Kubernetes runs predicates + a weighted sum of multiple priority functions + taints/tolerations + preemption; Nomad's scheduler runs the same filter-then-score shape with `spread`/`affinity`/`constraint` stanzas at a scale and complexity closer to Gimlé's own; Apache Mesos takes a genuinely different paradigm worth knowing about — two-level scheduling via resource offers, where frameworks (not a central scheduler) decide whether to accept.
- **Gap**: No node taints/cordoning, no priority classes, no preemption when the cluster is full — `GimleSchedulingException.noFeasiblePlacement` is the only outcome even when evicting something lower-priority would make room.
- **Value**: Medium — good next step once P0-2 (labels) lands · **Difficulty**: Large · **Fit**: Partial (one extra priority dimension or basic taints could fit one session; preemption would not)

---

## P2 — Solid improvements (medium value, mostly one-session)

| # | Finding | Module(s) | Value | Difficulty | Fit |
|---|---|---|---|---|---|
| P2-1 | Readiness probe is one-directional — never recovers automatically once failed (`WorkerRuntime.onReadinessResult`, `WorkerRuntime.java:196-206`; own comment confirms "deliberately no… mark ready call") | gimle-worker | Medium | Small | Yes |
| P2-2 | No live resource-usage feedback loop — `currentUsage()` always returns `(0,0)` even within the portable limiter's own stated scope (`PortableJvmFlagsResourceLimiter.java:38-48`) | gimle-os | Medium | Small | Yes |
| P2-3 | Worker crash has no cause classification (OOM vs. any other exit) — `WorkerProcessSupervisor.onExit` logs only the exit code (`WorkerProcessSupervisor.java:224-235`) | gimle-agent | Medium | Small | Yes |
| P2-4 | No `startupProbe`/`initialDelaySeconds` equivalent — a slow-starting module can be torn down by its own liveness probe within `failureThreshold × interval` (as little as 3s) (`WorkerRuntime.java:146-174`, `ProbeLoop.java:31-46`) | gimle-worker | Medium | Small | Yes |
| P2-5 | Hot redeploy has no version-aware traffic cutover — old/new versions round-robin together the instant the new one registers (`SimpleServiceRegistry.java:23-59` keys by interface only, not version) | gimle-module | Medium | Medium | Yes |
| P2-6 | Circuit breaker conflates application errors with transport/availability failures — a legitimate validation exception scores identically to a network failure (`FabricServiceRegistry.java:282-298`); cf. Netflix Hystrix, the pattern's original popularizer, which distinguishes failure types explicitly | gimle-fabric | Medium | Medium | Yes |
| P2-7 | No cluster-wide ejection safety valve ("panic threshold") — a correlated failure can open every endpoint's breaker simultaneously with `lookup` just returning empty, unlike Envoy's `max_ejection_percent` panic mode (`CircuitBreaker.java`, `FabricServiceRegistry.java:224-236`) | gimle-fabric | Medium | Small | Yes |
| P2-8 | SWIM gossip has no periodic anti-entropy full-state sync — bounded 64-entry piggyback history can let a slow/partitioned node permanently miss an update; cf. Cassandra's Merkle-tree anti-entropy repair (`nodetool repair`) and Hashicorp memberlist's periodic push-pull full-state sync, both built for exactly this convergence guarantee (`GossipMember.java:594-626`) | gimle-fabric | Medium | Medium | Yes |
| P2-9 | SWIM probe scheduling is pure-random with a fixed suspicion timeout — no per-cycle coverage guarantee, no Lifeguard-style adaptive suspicion; cf. Consul/Serf's adoption of Hashicorp's Lifeguard enhancement specifically to cut false-positive `DEAD` declarations under load, and Cassandra's own gossip generation/version numbers for similar robustness (`GossipMember.java:250-259`, `GossipConfig.java`) | gimle-fabric | Medium | Medium | Yes |
| P2-10 | Trace propagation uses manual `Context.wrap`, not the `ScopedValue` mechanism the architecture doc commits to, and `TraceContext` drops W3C `tracestate`/baggage entirely (`TraceContext.java:12`, `BoundedModuleScheduler.java:52-56`) | gimle-fabric, gimle-worker | Medium | Small | Yes |
| P2-11 | No brute-force protection on console login — no rate limit/lockout, only PBKDF2 cost as friction (`ApiServer.handleAuthLogin`, `:1285-1315`) | gimle-controlplane | Medium | Small | Yes |
| P2-12 | No SpotBugs/PMD/ErrorProne/JaCoCo anywhere in the build — Checkstyle covers naming/formatting only, nothing catches bug-pattern defects or measures coverage (root `pom.xml:239-260`) | repo-wide | Medium | Small | Yes |
| P2-13 | No SBOM generation or dependency-vulnerability scanning — a CVE in Bouncy Castle or SnakeYAML would go undetected | repo-wide | Medium | Small | Yes |
| P2-14 | Node heartbeats are leader-local/unreplicated but reads round-robin across all store replicas — a replica that never held leadership returns empty forever, risking spurious mass rescheduling on any store leader change. Contrast Cassandra, where gossip-based node status is *deliberately* eventually-consistent and documented as such — Gimlé's version is a bug because it mixes a leader-local write with a round-robin read, not a valid eventually-consistent design in its own right (`StateStore.java:202-210,399-402`, `StoreClient.java:66-68,175-203`) | gimle-mimir | High | Medium | Partial (fix is small — route reads through the leader — but needs a convergence/flap regression test) |
| P2-15 | CLAUDE.md's "single-node until Phase 5" framing for the state store is stale — multi-node HA, partition tolerance, and failover are implemented and covered by real 3-node tests (`RaftClusterTest.java`, `StoreClientClusterTest.java`), but `LOCAL_DEV.md`/`gimle-smoke-tests` still only run a single `StoreMain` node | gimle-mimir, docs | Medium | Small (doc fix) / Medium (smoke-test extension) | Yes (doc) / Partial (smoke tests) |
| P2-16 | Secrets master key has no rotation or KMS integration — one AES-256 key generated once, no key-id/versioning in the ciphertext format (`KeyFileManager.java:36-56`, `SecretCipher.java:25-56`) | gimle-controlplane | Medium | Medium | Partial (versioned-key format fits one session; external KMS does not) |
| P2-17 | Cross-tenant service-fabric access is default-allow — a manifest that omits tenant scoping is reachable by every tenant (`ServiceExport.java:29-40`, `FabricServiceRegistry.java:150-176`) | gimle-core, gimle-fabric | Medium | Medium | Partial (a cluster-wide default-deny flag is contained; retrofitting every manifest is broader) |
| P2-18 | Admission-time and every reconcile tick both require synchronous local-filesystem access to the module artifact, with no content hashing tying a spec to a specific artifact (`ApiServer.java:440-446`, `DeploymentReconciler.java:117-127`) | gimle-controlplane | Medium | Medium | Partial (content-hash validation is small; a real artifact registry is not) |
| P2-19 | Startup-hook failure and steady-state liveness failure take inconsistent self-healing paths — a hook throwing once is permanently stuck (no retry), while the same defect one probe cycle later gets automatic retries (documented as deliberate in `ModuleState`'s javadoc, but worth the asymmetry being explicit) | gimle-module, gimle-worker | Low-Medium | Medium | Yes |

---

## P3 — Backlog / optional (low value, large effort, or narrow payoff)

These are real, verified findings — just not worth prioritizing given the project's educational scope and the effort required relative to payoff. Revisit only if nothing in P0–P2 remains.

- **InstallSnapshot sends the whole snapshot in one RPC, no chunking** (Raft paper's Figure 13 has `offset`/`done` fields for this; fine at this project's data scale) — `gimle-mimir`. Low/Medium/Yes.
- **`GossipMember` never reaps `DEAD` members** — membership table grows unboundedly with node churn over a long-running cluster's life — `gimle-fabric`. Low-Medium/Small/Yes.
- **Locality-tier load balancing is a hard cutoff** — a single lightly-loaded same-machine replica absorbs 100% of traffic even with idle remote replicas, no capacity-aware spillover like Envoy's overprovisioning factor — `gimle-fabric`. Low/Medium/Yes.
- **Invalid all-zero `SpanContext`** constructed when no active span exists at call time — harmless under the current no-op exporter, will misbehave the moment a real OTLP backend is wired in — `gimle-fabric`. Low/Small/Yes.
- **No request rate limiting/throttling on the control-plane API**, even in authenticated/TLS mode — `gimle-controlplane`. Low/Medium/Partial.
- **No startup log banner for the no-auth-by-default posture** — the PLAINTEXT default itself is intentional (see Not gaps), but a loud, explicit "running with no authentication" line at boot would make the tradeoff visible instead of silent — `gimle-controlplane`. Low/Small/Yes.
- **Two independent, unsynchronized failure detectors** (SWIM gossip vs. the control plane's own 15s heartbeat-dark timeout) can disagree about node liveness — matches CLAUDE.md's stated design ("gossip off the control plane's critical path") so this is intentional, but worth documenting as a known inconsistency rather than rediscovering it as a bug — `gimle-fabric`, `gimle-controlplane`. Medium/Large/No.
- **No linearizable/quorum read path** in the store's read API — every read can observe stale/partitioned-minority data with no opt-in for a fresher read, a documented simplification already — `gimle-mimir`. Medium/Medium/Partial.
- **Raft chaos testing is real but scripted, not randomized/Jepsen-style** — six well-chosen scenarios exist (`RaftClusterTest.java:211-334`) but no randomized nemesis + linearizability checker — `gimle-mimir`. Medium/Large/No.
- **Single-tier root CA with no revocation mechanism (no CRL/OCSP)** — reasonable for an educational single-cluster scope, a real weakness only if this PKI pattern were ever treated as a template beyond a lab cluster — `gimle-pki`. Low/Large/No.

---

## Not gaps — verified correct or already an explicit, documented tradeoff

Listed so these don't get re-flagged in a future pass:

- **No authentication/authorization by default on the control-plane API** (`TransportProtocol` defaults to `PLAINTEXT` — `gimle-core/src/main/java/com/gimle/core/tls/TransportProtocol.java:27`; `ApiServer.requireAuthorized()` returns `true` for any non-`HttpsExchange` — `ApiServer.java:1878-1880`). **This is a deliberate project decision**, not an oversight: Gimlé is meant to be trivial to spin up and try locally, and requiring TLS/certs before you can deploy a module would be exactly the kind of onboarding friction this project wants to avoid. The RBAC/mTLS system underneath (`Authorizer`, `Principal`, `SessionTokens`, `gimle-pki`) is real and well-built for anyone who *does* want to lock a cluster down — it's opt-in via `gimle.transport.protocol=tls`, which is the right default posture for a teaching project even though it would be the wrong one for a real product. The one small, genuinely worthwhile follow-up: a loud startup log line ("running with no authentication — do not expose this port") so the tradeoff is visible, not silent. See P3.
- **Classloader leak detector's sampled, JFR-based retaining-path correlation** (`LeakTracker.java`, `OldObjectSampleCorrelator.java`) is meaningfully thinner than Eclipse MAT/Plumbr's exhaustive dominator-tree analysis — but this is an honest, self-documented tradeoff (the javadoc explains why the "proper" approach doesn't work in-process), not a hidden defect.
- **cgroup v2 kernel-level resource enforcement and Tier 3 FFM namespace isolation** — deliberately deferred per CLAUDE.md ("Core architecture: tiered isolation"). Do not build these speculatively.
- **Two independent failure detectors disagreeing** — see P3 above; intentional per CLAUDE.md's gossip-off-critical-path design note, just worth documenting explicitly.

## Cross-platform constraint check

CLAUDE.md's "Core architecture" section already commits Gimlé to a portable-first design:
enforcement stays in pure-JVM territory (`PortableJvmFlagsResourceLimiter` — `-Xmx`/
`ActiveProcessorCount`) until a second, explicitly platform-specific `ResourceLimiter`
implementation is built later; cgroup v2 and FFM `unshare`/`setns` are named, deliberate
deferrals, not gaps to close opportunistically. Every item in P0–P2 above was checked against
that same bar before being included:

- **P0-5** (metrics pipeline dead wiring) and **P2-2** (no live resource-usage feedback loop) both
  route through `ResourceUsage`/`WorkerMetrics`, which are populated by the worker JVM's own
  self-reporting — `Runtime.totalMemory()`/`freeMemory()`, `ThreadMXBean`, JFR CPU/allocation
  events. All of it is standard `java.lang.management`/JFR API, identical on Linux/macOS/Windows,
  zero native or OS-specific code — exactly the same portability bar
  `PortableJvmFlagsResourceLimiter` already meets. Nothing here needs cgroup reads or FFM calls.
- **P2-3** (worker crash cause classification) needs only `-XX:+ExitOnOutOfMemoryError` (a
  standard cross-platform JVM flag) plus parsing the `hs_err_pid*.log` the JVM already writes on
  every platform — no OS-specific exit-code interpretation (unlike, say, reading Linux's cgroup
  OOM-kill signal, which would *not* be portable and is correctly out of scope).
- **P0-6** (reconciler backoff state not replicated) is pure state-persistence logic against the
  existing `StateStore`/`MutationSink` — no platform surface at all.

None of P0–P2 needed to be pushed down for platform-dependence reasons; the only genuinely
platform-specific work in the whole gap analysis is cgroup v2 / FFM Tier 3, and those are already
correctly excluded above as existing, deliberate deferrals rather than items on this backlog.

---

## Suggested execution order

1. **P0 items, in listed order** (P0-7 reclassified as not-a-gap, see Not gaps) — each is
   small-to-medium, self-contained, and closes either a correctness bug (P0-1, P0-6, P0-9) or a
   "looks implemented, isn't" gap (P0-2, P0-3, P0-4, P0-5) or a foundational engineering-rigor gap
   (P0-8, P0-10, P0-11).
2. **P2 quick wins** (P2-1 through P2-13) — same shape as P0 but lower individual value; good
   filler between P1 initiatives.
3. **P1 big rocks**, each scoped into its own sub-plan before starting:
   - P1-4 (wire versioning) and P1-5 (dynamic Raft membership) are independent of each other.
   - P1-1 (Tier 1 density) is the highest-value, highest-effort item in this whole document —
     it's the platform's core pitch. Worth a dedicated design pass before any code.
   - P1-2 (event log) and P1-3 (metrics API) both depend on P0-5 landing first.
4. **P2 remainder and P3** as time allows; P3 items are explicitly lower priority, not urgent gaps.
