# Gimlé Requirements Matrix

This matrix was reverse-engineered directly from the Gimlé codebase as it stood at commit `919bd4063ab8ef5a430e1e3ed8bf2f12ddacd640` (2026-08-17), on branch `claude/gimle-requirements-matrix-lfkqqe`. Every entry below is grounded in code and test source actually read in the repository — main sources, `module-info.java` files, test classes, Gherkin `.feature` files, and (for the web console family) frontend routes/stores/repositories — not in the project's own planning docs (`gimle-PROJECT-v2.md`, `CLAUDE.md`) or aspirational design intent. Where behavior is stubbed, deliberately deferred, or documented as an accepted v1 limitation, the entry is marked `Partial` in the Status column with the reason inline; nothing here describes intended-but-unimplemented behavior.

**Scope**: all 27 Maven modules in the reactor — 564 distinct requirements identified, grouped by module below. IDs are sequential (`GIMLE-001`…`GIMLE-564`) in module order; they are stable identifiers for this document only, not references to any other tracker.

**Method note on Test Coverage classification**: `Yes` means the entry cites one or more real test class/method names; `Partial` means no dedicated test exists but the behavior is exercised indirectly (through another module's integration test, a smoke test, or a Holmgang scenario); `None` means no test — direct or indirect — was found for that specific behavior.

## Summary Table

| ID | Feature | Category | Status | Test Coverage |
|---|---|---|---|---|
| GIMLE-001 | Semantic module versioning | Module System | Complete | Yes |
| GIMLE-002 | Version range constraint matching | Module System | Complete | Yes |
| GIMLE-003 | Module descriptor validation (request ≤ limit invariant) | Module System | Complete | Yes |
| GIMLE-004 | Tiered isolation model (TIER_1/TIER_2/TIER_3) | Module System | Complete | Partial |
| GIMLE-005 | Kubernetes-shaped resource quantity parsing | Resource Limiting | Complete | Yes |
| GIMLE-006 | Tenant-scoped service export | Module System / Multi-tenancy | Complete | Yes |
| GIMLE-007 | StatefulSet-shaped persistent volume declaration | Module System / Storage | Complete | Yes |
| GIMLE-008 | Health probe configuration with initial delay | Module System / Health | Complete | Yes |
| GIMLE-009 | Vessel hosting mode (plain-process workload) | Module System / Vessel Hosting | Complete | Yes |
| GIMLE-010 | Artifact-registry vs local-path reference resolution | Internal/Infra | Complete | None |
| GIMLE-011 | RBAC domain model (resources, verbs, permissions, roles, bindings) | Security / RBAC | Complete | Yes |
| GIMLE-012 | Built-in cluster-admin role and operator/node certificate groups | Security / RBAC | Complete | Yes |
| GIMLE-013 | Console password hashing (PBKDF2-HMAC-SHA256) | Security | Complete | Yes |
| GIMLE-014 | Mutual-TLS SSLContext construction | PKI / Internal-Infra | Complete | Yes |
| GIMLE-015 | Cluster-wide transport protocol switch (plaintext/TLS) | Config | Complete | Yes |
| GIMLE-016 | Stateless HMAC-signed console session tokens | Security | Complete | Yes |
| GIMLE-017 | Session-signing key file load-or-create with owner-only permissions | Security | Complete | Yes |
| GIMLE-018 | Per-key exponential-backoff login throttle | Security | Complete | Yes |
| GIMLE-019 | Structured JSON log encoding with APPLICATION/PLATFORM categorization | Observability / Logging | Complete | Yes |
| GIMLE-020 | Human-readable colored console log encoding | Observability / Logging | Complete | Yes |
| GIMLE-021 | Runtime-switchable console log format (text default, JSON opt-in) | Observability / Logging | Complete | Yes |
| GIMLE-022 | MDC-tagged proxying for same-worker and probe-loop invocations | Observability / Logging | Complete | Yes |
| GIMLE-023 | Per-instance sifted log files | Observability / Logging | Complete | Yes |
| GIMLE-024 | Platform (non-instance) log file appender | Observability / Logging | Complete | Yes |
| GIMLE-025 | Kubelet-style size/count log rotation | Observability / Logging | Complete | Yes |
| GIMLE-026 | Cursor-based log paging and live-follow streaming | Observability / Logging | Complete | Yes |
| GIMLE-027 | Startup banner rendering with terminal color/Unicode auto-detection | Internal/Infra | Complete | Yes |
| GIMLE-028 | Single-write length-prefixed wire framing | Internal/Infra | Complete | None |
| GIMLE-029 | Hand-rolled JSON parser/writer | Internal/Infra | Complete | Yes |
| GIMLE-030 | Agent↔worker control-channel protocol and codec | Internal/Infra / Protocol | Complete | Yes |
| GIMLE-031 | Node registration/heartbeat/capacity-reporting protocol | Internal/Infra / Protocol | Complete | Partial |
| GIMLE-032 | Instance lifecycle event log model | Observability | Complete | None |
| GIMLE-033 | Cross-resource audit trail model | Security / Audit | Complete | Yes |
| GIMLE-034 | Certificate bootstrap (CSR) request/response protocol | PKI | Complete | None |
| GIMLE-035 | Assigned-instance work-order model (incl. in-place rename and vessel dispatch) | Scheduling | Complete | Partial |
| GIMLE-036 | Bounded-retry-with-backoff restart policy (CrashLoopBackOff-equivalent) | Self-Healing | Complete | Yes |
| GIMLE-037 | Tenant identity and resource quota model | Multi-tenancy | Complete | None |
| GIMLE-038 | Tenant-scoped config/secret entry model | Config / Secrets | Complete | None |
| GIMLE-039 | Bundled SPA static-asset resolution from classpath | Internal/Infra / Web | Complete | Yes |
| GIMLE-040 | SPA static file serving with client-side-route fallback | Internal/Infra / Web | Complete | Yes |
| GIMLE-041 | Saga test-run event model and NDJSON codec | Internal/Infra / Testing | Complete | Yes |
| GIMLE-042 | Stable failure-signature hashing for flaky-test clustering | Internal/Infra / Testing | Complete | Yes |
| GIMLE-043 | Module dependency resolution with cycle detection | Module System | Complete | Yes |
| GIMLE-044 | Module registry (install bookkeeping, idempotent re-install, content-mismatch rejection) | Module System | Complete | Yes |
| GIMLE-045 | Module lifecycle state machine (INSTALLED→RESOLVED→STARTING→ACTIVE→STOPPING→UNINSTALLED, plus FAILED/COMPLETED) | Module System | Complete | Yes |
| GIMLE-046 | Dynamic per-module-version JPMS ModuleLayer construction | Module System | Complete | Yes |
| GIMLE-047 | Unnamed-module readability grant for bundled hooks/probes | Module System / Internal-Infra | Complete | Yes |
| GIMLE-048 | Classloader leak detection via PhantomReference | Module System / Internal-Infra | Complete | Yes |
| GIMLE-049 | Repeated-redeploy flat-metaspace acceptance test | Module System | Complete | Yes |
| GIMLE-050 | Best-effort leak retaining-path attribution via JFR OldObjectSample | Module System / Internal-Infra | Complete | Yes |
| GIMLE-051 | Module lifecycle hooks (reflectively instantiated, JPMS-exported) | Module System | Complete | Yes |
| GIMLE-052 | Job-kind run-to-completion hooks | Module System | Complete | Yes |
| GIMLE-053 | Module context API (in-flight tracking, service lookup, config, data dir, control-plane relay) | Module System | Complete | Yes |
| GIMLE-054 | In-worker round-robin service registry with version-aware cutover | Module System | Complete | Yes |
| GIMLE-055 | Cross-tier name-driven service invocation | Module System | Complete | Yes |
| GIMLE-056 | Same-worker cross-module service publish/discover | Module System | Complete | Yes |
| GIMLE-057 | Graceful drain-then-dispose stop with deadline | Module System / Self-Healing | Complete | Yes |
| GIMLE-058 | Hot redeploy (old/new version coexistence with pinned dependent wiring) | Module System | Complete | Yes |
| GIMLE-059 | gimle-module.yaml descriptor parsing and validation | Module System | Complete | Yes |
| GIMLE-060 | Module artifact reading — real-JPMS-module and descriptor-presence validation | Module System | Complete | Yes |
| GIMLE-061 | Andvari artifact-registry pull-through cache | Module System / Internal-Infra | Complete | Partial |
| GIMLE-062 | Multi-endpoint Andvari failover on pull | Module System / Internal-Infra | Complete | None |
| GIMLE-063 | Health probe interfaces (liveness/readiness) | Module System / Health | Complete | None |
| GIMLE-064 | Pluggable resource-limiter abstraction | Resource Limiting | Complete | Yes |
| GIMLE-065 | Portable JVM-flags resource enforcement (Tier 1/Tier 2) | Resource Limiting | Complete | Yes |
| GIMLE-066 | Tier 3 (namespace isolation) — deliberately unsupported by the current limiter | Resource Limiting | Partial | Yes |
| GIMLE-067 | Kernel-level (cgroup v2) resource enforcement — deferred | Resource Limiting | Partial | None |
| GIMLE-068 | Pluggable persistent-volume-manager abstraction | Storage | Complete | Yes |
| GIMLE-069 | Local-disk persistent volume allocation for StatefulSet-shaped instances | Storage | Complete | Yes |
| GIMLE-070 | Self-signed cluster CA generation | PKI | Complete | Yes |
| GIMLE-071 | CSR-to-leaf-certificate signing with signature verification | PKI | Complete | Yes |
| GIMLE-072 | Server-stamped Subject override on signing (prevents self-declared privileged group) | PKI / Security | Complete | Yes |
| GIMLE-073 | CSR generation with Subject Alternative Names | PKI | Complete | Yes |
| GIMLE-074 | Hand-rolled PEM encode/decode for certs, CSRs, and private keys | PKI / Internal-Infra | Complete | Yes |
| GIMLE-075 | Randomized certificate-renewal scheduling (anti-thundering-herd) | PKI | Complete | None |
| GIMLE-076 | Own-certificate rotation over mTLS via CSR bootstrap endpoint | PKI | Complete | None |
| GIMLE-077 | X.500 Subject utilities: server-side O= stamping and Principal derivation | PKI / Security | Complete | Yes |
| GIMLE-078 | Cluster PKI bootstrap CLI (`mvn gimle:tls-init`) | PKI / Internal-Infra | Complete | None |
| GIMLE-079 | Worker JVM control-channel bootstrap | Worker Supervision / Internal-Infra | Complete | Yes |
| GIMLE-080 | Newline-delimited control-channel wire protocol (worker side) | Internal-Infra | Complete | Yes |
| GIMLE-081 | Module install/resolve/start/stop/uninstall command dispatch | Module System | Complete | Partial |
| GIMLE-082 | Instance identity registration and rename-in-place | Module System | Complete | None |
| GIMLE-083 | Per-instance MDC log tagging for lifecycle/hook/probe/request-dispatch logging | Observability / Internal-Infra | Complete | Yes |
| GIMLE-084 | Durable InstanceEvent emission per lifecycle transition | Observability | Complete | Partial |
| GIMLE-085 | Classloader leak detection on undeploy | Module System | Complete | None |
| GIMLE-086 | Per-module bounded virtual-thread scheduler | Worker Supervision | Complete | Yes |
| GIMLE-087 | OpenTelemetry context propagation across virtual-thread dispatch | Observability | Complete | Yes |
| GIMLE-088 | Liveness/readiness probe loop with timeout and initial-delay | Health / Self-Healing | Complete | Yes |
| GIMLE-089 | Module-tier self-healing — restart on repeated liveness failure with backoff and budget exhaustion | Worker Supervision / Self-Healing | Complete | Yes |
| GIMLE-090 | Readiness-driven service registry availability (without restart) | Health / Fabric | Complete | Yes |
| GIMLE-091 | Stopping/Uninstalled teardown of scheduler, probes, and service registry | Worker Supervision / Module System | Complete | Yes |
| GIMLE-092 | Job-kind module execution (run-to-completion, not probed) | Module System | Complete | Yes |
| GIMLE-093 | Fabric service registration, cross-worker/cross-machine invocation binding | Fabric | Complete | Partial |
| GIMLE-094 | Fabric TLS certificate rotation detection (mtime polling) | Internal-Infra / Fabric | Complete | Yes |
| GIMLE-095 | Control-plane read relay for hosted modules (RelayControlPlaneRead/Result round trip) | Fabric / Internal-Infra | Complete | Yes |
| GIMLE-096 | Worker-side trace relay to agent (no direct Muninn shipping) | Observability / Internal-Infra | Complete | Yes |
| GIMLE-097 | Per-module CPU/memory/request-rate/error-rate metrics reporting (portable, no cgroup) | Observability / Cgroup Management | Partial | None |
| GIMLE-098 | Worker-wide meter snapshot relay to Muninn (via agent) | Observability / Internal-Infra | Complete | Partial |
| GIMLE-099 | `module-info.java` platform-layer/observability/fabric wiring for the worker module | Internal-Infra | Complete | None |
| GIMLE-100 | Real bundled-hook/probe classloading against the platform layer | Module System / Internal-Infra | Complete | Yes |
| GIMLE-101 | Node agent registration and repeating reconcile/heartbeat/rotate tick loop | Worker Supervision / Internal-Infra | Complete | Yes |
| GIMLE-102 | Worker JVM process spawn and command-line construction | Worker Supervision | Complete | Yes |
| GIMLE-103 | Worker process crash detection, classification, and destroy-and-respawn | Worker Supervision / Self-Healing | Complete | Yes |
| GIMLE-104 | Deliberate-stop suppression of crash-respawn | Worker Supervision | Complete | Yes |
| GIMLE-105 | Worker stdout draining, JSON-line de-duplication, and raw SYSTEM-line capture | Observability / Internal-Infra | Complete | Yes |
| GIMLE-106 | Machine-level capacity tracking and admission (memory/CPU) | Worker Supervision / Config | Complete | Yes |
| GIMLE-107 | Portable JVM-flags resource limiting (Tier 1/2), cgroup enforcement deliberately deferred | Cgroup Management | Partial | Yes |
| GIMLE-108 | Tier 3 isolation rejection | Cgroup Management / Config | Partial | Partial |
| GIMLE-109 | Assignment reconciliation loop (fetch, start, replace, stop) | Worker Supervision | Complete | Yes |
| GIMLE-110 | Tier 1 density — shared-worker reuse for multiple module instances | Worker Supervision | Complete | Yes |
| GIMLE-111 | Instance rename-in-place (no restart) | Worker Supervision | Complete | Yes |
| GIMLE-112 | Worker respawn handshake re-drive after crash | Worker Supervision / Self-Healing | Complete | Partial |
| GIMLE-113 | Worker-crash-to-durable-InstanceEvent relay | Observability / Self-Healing | Complete | None |
| GIMLE-114 | Install-phase Nack escalates to FAILED (closing the "stuck at INSTALLED" gap) | Worker Supervision / Self-Healing | Complete | None |
| GIMLE-115 | Artifact-registry coordinate resolution via ArtifactPullCache | Config / Internal-Infra | Complete | None |
| GIMLE-116 | Instance-scoped log/config/secret delivery over the control channel | Config | Complete | None |
| GIMLE-117 | Persistent volume allocation for StatefulSet-shaped instances | Config | Complete | None |
| GIMLE-118 | Vessel process supervision (plain-jar workload as its own dedicated process) | Worker Supervision | Complete | Yes |
| GIMLE-119 | Vessel port allocation (dynamic/fixed) and env resolution (literal/port/secret) | Config | Complete | None |
| GIMLE-120 | Vessel config-file rendering to disk | Config | Complete | None |
| GIMLE-121 | Vessel health probing (process-alive + TCP/HTTP rungs, initial-delay aware) | Health / Self-Healing | Complete | None |
| GIMLE-122 | Vessel crash respawn resets probe initial-delay clock | Self-Healing | Complete | None |
| GIMLE-123 | mTLS bootstrap CSR flow for node identity | Internal-Infra / Config | Complete | Partial |
| GIMLE-124 | Periodic certificate rotation check and hot-swap of outbound HttpClient | Internal-Infra | Complete | None |
| GIMLE-125 | SWIM gossip membership integration with service catalog relay | Fabric | Complete | None |
| GIMLE-126 | Gossip membership read-only HTTP surface | Fabric / Observability | Complete | Yes |
| GIMLE-127 | Node/instance log-serving HTTP surface with tailing and follow | Observability | Complete | Yes |
| GIMLE-128 | Merged node-level SYSTEM log view | Observability | Complete | None |
| GIMLE-129 | `hs_err_pid*.log` crash-dump listing and fetch | Observability / Cgroup Management | Complete | Yes |
| GIMLE-130 | Node-agent log/metrics shipping to Muninn (own + supervised) | Observability | Complete | Yes |
| GIMLE-131 | Whitelisted control-plane read relay (worker→agent→control plane) with independent re-validation | Fabric / Config | Complete | Yes |
| GIMLE-132 | Node capacity/instance-observation heartbeat reporting | Observability / Worker Supervision | Complete | Yes |
| GIMLE-133 | Instance-event forwarding (worker-reported and agent-originated) to control plane | Observability | Complete | Partial |
| GIMLE-134 | Node placement-label registration | Config | Complete | None |
| GIMLE-135 | `module-info.java` wiring for the node agent module | Internal-Infra | Complete | None |
| GIMLE-136 | Raft Leader Election | Raft Consensus | Complete | Yes |
| GIMLE-137 | Log Replication (AppendEntries) | Raft Consensus | Complete | Yes |
| GIMLE-138 | Election Safety Restriction (log up-to-date check) | Raft Consensus | Complete | Yes |
| GIMLE-139 | Conflicting-Entry Truncation | Raft Consensus | Complete | Yes |
| GIMLE-140 | Leader-Only-Commits-Own-Term Rule (Figure 8) | Raft Consensus | Complete | Yes |
| GIMLE-141 | Strict Apply Ordering (commitIndex vs lastApplied) | Raft Consensus | Complete | Yes |
| GIMLE-142 | Proposal Timeout with Ghost-Write Prevention | Raft Consensus | Complete | Yes |
| GIMLE-143 | Chunked InstallSnapshot Transfer (Figure 13) | Raft Consensus | Complete | Yes |
| GIMLE-144 | Local Log Compaction / Snapshotting | Raft Consensus | Complete | Yes |
| GIMLE-145 | Check-Quorum Leader Self-Demotion | Raft Consensus | Complete | Yes |
| GIMLE-146 | Etcd-Style Live Membership Change (AddServer/RemoveServer) | Raft Consensus | Complete | Yes |
| GIMLE-147 | Non-Voting Learner & Automatic Promotion | Raft Consensus | Complete | Yes |
| GIMLE-148 | Durable Raft Log Persistence | Raft Consensus | Complete | Yes |
| GIMLE-149 | Raft Transport over Mutual TLS with Hot Cert Reload | Raft Consensus | Complete | Yes |
| GIMLE-150 | Raft RPC Wire Codec | Internal/Infra | Complete | Yes |
| GIMLE-151 | Atomic Durable File Writes | Internal/Infra | Complete | Yes |
| GIMLE-152 | File-Backed State Store Persistence Engine | State Store | Complete | Yes |
| GIMLE-153 | Full-State Snapshot / Restore | State Store | Complete | Yes |
| GIMLE-154 | Replicated Mutation Catalog (StateMutation) | Internal/Infra | Complete | Yes |
| GIMLE-155 | Leader-Local Node Heartbeat Tracking | State Store | Complete | Yes |
| GIMLE-156 | Distributed Lease Coordination (Grant/Renew/Release) | State Store | Complete | Yes |
| GIMLE-157 | Per-Instance Lifecycle Event Log with Retention Cap | State Store | Complete | Yes |
| GIMLE-158 | Cluster-Wide Audit Trail with Filtering | State Store | Complete | Yes |
| GIMLE-159 | Deployment Rolling-Update & Surge Bookkeeping | State Store | Complete | Yes |
| GIMLE-160 | StatefulSet OrderedReady Index & Sticky Node Binding | State Store | Complete | None |
| GIMLE-161 | Node Cordon (Scheduler Exclusion Flag) | State Store | Complete | Yes |
| GIMLE-162 | Tenant Quota-Violation Flag Tracking | State Store | Complete | Yes |
| GIMLE-163 | RBAC Data Persistence (Roles, RoleBindings, Accounts) | State Store | Complete | Yes |
| GIMLE-164 | Client-Facing Store RPC with Leader Redirect & Follow | Internal/Infra | Complete | Yes |
| GIMLE-165 | Store Read Load Balancing Across Replicas | State Store | Complete | Yes |
| GIMLE-166 | Store Node Leader-Only Write Gating | Internal/Infra | Complete | Yes |
| GIMLE-167 | Store Client Connection Timeout Bounds | Internal/Infra | Complete | Yes |
| GIMLE-168 | Store RPC Wire Codec | Internal/Infra | Complete | Yes |
| GIMLE-169 | RBAC Authorization Engine | Internal-Infra | Complete | Yes |
| GIMLE-170 | Node-Tenant Assignment Check | Internal-Infra | Complete | Yes |
| GIMLE-171 | Five-Field Cron Schedule Evaluator | Config | Complete | Yes |
| GIMLE-172 | Deployment Manifest Parsing (incl. Autoscale & Disruption Budget) | Config | Complete | Yes |
| GIMLE-173 | DaemonSet Manifest Parsing (Anti-Affinity/Surge Rejection) | Config | Complete | Yes |
| GIMLE-174 | Job / CronJob Manifest Parsing | Config | Complete | Yes |
| GIMLE-175 | StatefulSet Manifest Parsing | Config | Complete | Yes |
| GIMLE-176 | Kind-Dispatching Manifest Parser | Config | Complete | Yes |
| GIMLE-177 | Shared Domain Binary Codec | Internal/Infra | Complete | Yes |
| GIMLE-178 | Store Process Bootstrap with TLS Rotation Ticker | Internal/Infra | Complete | Partial |
| GIMLE-179 | Store/Raft Metrics Instrumentation | Internal/Infra | Complete | None |
| GIMLE-180 | module-info JPMS Boundary for gimle-mimir | Internal/Infra | Complete | None |
| GIMLE-181 | Same-Worker Direct Invocation Tier | Service Fabric | Complete | Yes |
| GIMLE-182 | Same-Machine Unix-Domain-Socket Invocation Tier | Service Fabric | Complete | Yes |
| GIMLE-183 | Cross-Machine TCP Invocation Tier | Service Fabric | Complete | Yes |
| GIMLE-184 | Locality-Aware Load Balancing with Spillover | Load Balancing | Complete | Yes |
| GIMLE-185 | Least-Outstanding-Requests Selection | Load Balancing | Complete | Yes |
| GIMLE-186 | Per-Endpoint Circuit Breaker | Circuit Breaking | Complete | Yes |
| GIMLE-187 | Circuit Breaker Exponential Cooldown Backoff | Circuit Breaking | Complete | Yes |
| GIMLE-188 | Panic-Mode Ejection Floor | Circuit Breaking | Complete | Yes |
| GIMLE-189 | Application-Exception vs Transport-Failure Breaker Scoring | Circuit Breaking | Complete | Yes |
| GIMLE-190 | Gossip-Propagated Service Catalog | Service Fabric | Complete | Yes |
| GIMLE-191 | Catalog Eviction on Gossip-Detected Node Death | Service Fabric | Complete | Yes |
| GIMLE-192 | Cross-Tenant Service Export Access Control | Service Fabric | Complete | Yes |
| GIMLE-193 | Runtime Name-Driven Cross-Tier Invocation (invokeByName) | Service Fabric | Complete | Yes |
| GIMLE-194 | Inbound Call Dispatch with Bounded Concurrency | Service Fabric | Complete | Yes |
| GIMLE-195 | Distributed Trace Propagation Across Fabric Hops | Service Fabric | Complete | Yes |
| GIMLE-196 | Fabric Transport over Mutual TLS with Hot Cert Reload | Service Fabric | Complete | Yes |
| GIMLE-197 | Fabric Call Timeout Enforcement | Internal/Infra | Complete | Yes |
| GIMLE-198 | Fabric Frame Wire Codec | Internal/Infra | Complete | Yes |
| GIMLE-199 | Cross-JVM Object Marshalling | Internal/Infra | Complete | Partial |
| GIMLE-200 | SWIM Gossip Membership Protocol (Ping/PingReq/Ack) | Gossip Membership | Complete | Yes |
| GIMLE-201 | SWIM Self-Refutation via Incarnation Bump | Gossip Membership | Complete | Yes |
| GIMLE-202 | Lifeguard-Style Local Health Multiplier | Gossip Membership | Complete | Yes |
| GIMLE-203 | Round-Robin Bounded-Coverage Probe Target Selection | Gossip Membership | Complete | Yes |
| GIMLE-204 | Anti-Entropy Full-State Sync | Gossip Membership | Complete | Yes |
| GIMLE-205 | Dead-Member Reaping | Gossip Membership | Complete | Yes |
| GIMLE-206 | Gossip over Mutual DTLS with Deterministic Initiator Selection | Gossip Membership | Complete | Yes |
| GIMLE-207 | SWIM Wire Codec | Internal/Infra | Complete | Yes |
| GIMLE-208 | Service Catalog Delta Wire Codec | Internal/Infra | Complete | Yes |
| GIMLE-209 | Reflective Cross-Module Method Dispatch | Internal/Infra | Complete | Yes |
| GIMLE-210 | module-info JPMS Boundary for gimle-fabric | Internal/Infra | Complete | None |
| GIMLE-211 | First-fit-decreasing bin-packing scheduler | Scheduling | Complete | Yes |
| GIMLE-212 | Isolation-tier placement filtering | Scheduling | Complete | Yes |
| GIMLE-213 | Node cordon exclusion | Scheduling | Complete | Yes |
| GIMLE-214 | Strict anti-affinity across nodes | Scheduling | Complete | Yes |
| GIMLE-215 | Tier 2/3 node-level tenant isolation | Scheduling / Multi-tenancy | Complete | Yes |
| GIMLE-216 | Required node-label placement constraint | Scheduling | Complete | Yes |
| GIMLE-217 | StatefulSet sticky node placement | Scheduling / Orchestration | Complete | Yes |
| GIMLE-218 | DaemonSet eligible-node enumeration (`eligibleNodes`) | Scheduling | Complete | Yes |
| GIMLE-219 | Deployment replica reconciliation (level-triggered) | Reconciliation | Complete | Yes |
| GIMLE-220 | Deployment scale-down | Reconciliation | Complete | Yes |
| GIMLE-221 | Artifact-hash drift detection at reconcile time | Reconciliation / Internal-Infra | Complete | Yes |
| GIMLE-222 | Rolling update via mismatched-index migration | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-223 | Rolling update surge (maxSurge) | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-224 | Node-death instance reclamation (`ReplicaCountReconciler`) | Reconciliation / Self-healing | Complete | Yes |
| GIMLE-225 | Persisted grace-period bookkeeping (survives leader failover) | Reconciliation / Internal-Infra | Complete | Yes |
| GIMLE-226 | Unhealthy-instance backoff-gated reschedule (`HealthReconciler`) | Reconciliation / Self-healing | Complete | Yes |
| GIMLE-227 | Readiness-only failures never trigger reschedule | Reconciliation | Complete | Yes |
| GIMLE-228 | Tenant quota drift detection (`QuotaReconciler`) | Reconciliation / Multi-tenancy | Complete | Yes |
| GIMLE-229 | Horizontal autoscaling — multi-signal (`AutoscaleReconciler`) | Reconciliation / Scheduling | Complete | Yes |
| GIMLE-230 | Autoscaling WEIGHTED combination mode | Reconciliation / Scheduling | Complete | Yes |
| GIMLE-231 | DaemonSet reconciliation and rolling update | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-232 | DaemonSet dark-node placement-safety grace period | Reconciliation / Self-healing | Complete | Yes |
| GIMLE-233 | StatefulSet OrderedReady placement | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-234 | StatefulSet one-index-at-a-time scale-down | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-235 | JobRun run-to-completion reconciliation | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-236 | Job active-deadline enforcement | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-237 | CronJob schedule-driven Job materialization | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-238 | CronJob concurrency policy (Allow/Forbid/Replace) | Reconciliation / Orchestration | Complete | Yes |
| GIMLE-239 | CronJob manual trigger (`gimle cronjob trigger`) | Reconciliation / API Server | Complete | Yes |
| GIMLE-240 | CronJob missed-schedule starting-deadline handling | Reconciliation | Complete | Yes |
| GIMLE-241 | Level-triggered orphan cleanup across every workload kind | Reconciliation | Complete | Yes |
| GIMLE-242 | Reconciler-leader election via non-replicated lease | Orchestration / Internal-Infra | Complete | Yes |
| GIMLE-243 | Independent-executor ticking (lease/reconcile/cert-rotation isolation) | Internal-Infra | Complete | Yes |
| GIMLE-244 | JPMS module boundary for gimle-controlplane | Internal-Infra | Complete | None |
| GIMLE-245 | Admission chain extension point | Admission / Internal-Infra | Complete | Yes |
| GIMLE-246 | Tenant resource quota admission check | Admission / Multi-tenancy | Complete | Yes |
| GIMLE-247 | Organization-specific policy-as-data admission (`policy.maxReplicasPerDeployment`) | Admission / Config | Complete | Yes |
| GIMLE-248 | Registry-coordinate artifact admission (Andvari integration) | Admission / Artifact Registry | Complete | Yes |
| GIMLE-249 | PUT-time re-tenanting double-authorization | Authorization | Complete | Yes |
| GIMLE-250 | RBAC-gated resource CRUD across every workload kind | Authorization | Complete | Yes |
| GIMLE-251 | WRITE/DELETE decisions durably audited (opt-in READ auditing) | Authorization / Internal-Infra | Complete | Yes |
| GIMLE-252 | `gimle-system` reserved-tenant operator-only guard | Authorization | Complete | Yes |
| GIMLE-253 | Node-scoped self-service authorization (`gimle:nodes` group) | Authorization | Complete | Yes |
| GIMLE-254 | Node-tenant-scoped `/endpoints/*` read access | Authorization | Complete | Yes |
| GIMLE-255 | mTLS-authenticated HTTP API server with client-cert principal resolution | Internal-Infra / API Server | Complete | Yes |
| GIMLE-256 | Console session login/logout/session cookie flow | Authorization / API Server | Complete | Yes |
| GIMLE-257 | Login throttling (address + username keyed) | Authorization / Internal-Infra | Complete | Yes |
| GIMLE-258 | Bootstrap node join via single-use token + CSR | Internal-Infra / API Server (PKI) | Complete | Yes |
| GIMLE-259 | Operator-approval-gated CSR flow | Internal-Infra / API Server (PKI) | Complete | Yes |
| GIMLE-260 | Certificate rotation (self-rotation and subject-preserving renewal) | Internal-Infra | Complete | Yes |
| GIMLE-261 | Zero-downtime TLS material reload | Internal-Infra | Complete | Yes |
| GIMLE-262 | `/secrets/*` byte-for-byte proxy to Fafnir | Secrets Management / Internal-Infra | Complete | Yes |
| GIMLE-263 | Secrets key rotation trigger (proxied) | Secrets Management | Complete | Yes |
| GIMLE-264 | CONFIG/SECRET resource-kind separation on one underlying store | Config / Authorization | Complete | Yes |
| GIMLE-265 | `/artifacts/*` streaming proxy to Andvari | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-266 | Andvari-client multi-endpoint failover with rotation | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-267 | `/logs/*` proxy with Muninn fallback | Internal-Infra | Complete | Yes |
| GIMLE-268 | `/metrics-history/*` and `/traces-history/*` Muninn proxy | Internal-Infra | Complete | Yes |
| GIMLE-269 | Node registration, heartbeat, and assignment-fetch API | API Server / Orchestration | Complete | Yes |
| GIMLE-270 | Unified `AssignedInstance` wire shape across every workload kind | Internal-Infra / API Server | Complete | Yes |
| GIMLE-271 | Reserved system-tenant auto-seeding | Multi-tenancy / Internal-Infra | Complete | Yes |
| GIMLE-272 | Bundled web console static serving | API Server / Internal-Infra | Complete | Yes |
| GIMLE-273 | Per-endpoint request metrics instrumentation | Internal-Infra | Complete | Yes |
| GIMLE-274 | Deployment/Job/DaemonSet/StatefulSet CRUD manifest API | API Server | Complete | Yes |
| GIMLE-275 | Per-deployment and per-instance metrics rollup | API Server / Observability | Complete | Yes |
| GIMLE-276 | AES-256-GCM secret value encryption with versioned key IDs | Secrets Management | Complete | Yes |
| GIMLE-277 | Legacy pre-key-id ciphertext format fallback | Secrets Management | Complete | Yes |
| GIMLE-278 | Local AES-256 key-file generation and loading | Secrets Management | Complete | Yes |
| GIMLE-279 | Key rotation with full-ring persistence (`KeyFileManager.rotate`) | Secrets Management | Complete | Yes |
| GIMLE-280 | Key-ring fingerprinting for cross-replica drift detection | Secrets Management / Internal-Infra | Complete | Yes |
| GIMLE-281 | Full-key-rotation re-encryption sweep | Secrets Management | Complete | Yes |
| GIMLE-282 | Versioned secret storage layered over ConfigEntry | Secrets Management | Complete | Yes |
| GIMLE-283 | Optimistic-write versioned put with narrow-lease serialization | Secrets Management / Internal-Infra | Complete | Yes |
| GIMLE-284 | Soft delete vs hard delete (`?destroy=true`) | Secrets Management | Complete | Yes |
| GIMLE-285 | Fafnir's own independent RBAC re-check (defense-in-depth) | Authorization | Complete | Yes |
| GIMLE-286 | Node-tenant-scoped secret reads (`gimle:nodes`) | Authorization | Complete | Yes |
| GIMLE-287 | Authorization-failure throttling and dual audit logging | Authorization / Internal-Infra | Complete | Yes |
| GIMLE-288 | Three-tier principal resolution (forwarded header > peer cert > session cookie) | Internal-Infra / Authorization | Complete | Yes |
| GIMLE-289 | mTLS HTTP server with dynamic TLS material reload | Internal-Infra | Complete | Yes |
| GIMLE-290 | Console session login (Fafnir's own operator dashboard) | API Server | Complete | Yes |
| GIMLE-291 | Plaintext-mode anonymous session carve-out | Authorization | Complete | Yes |
| GIMLE-292 | Bundled web console static serving (Fafnir) | API Server / Internal-Infra | Complete | Yes |
| GIMLE-293 | Process status endpoint with key-ring fingerprint | API Server / Internal-Infra | Complete | Yes |
| GIMLE-294 | Muninn metrics/traces shipping | Internal-Infra / Config | Complete | Partial |
| GIMLE-295 | Fafnir-metrics observability instrumentation | Internal-Infra | Complete | Yes |
| GIMLE-296 | JPMS module boundary for gimle-fafnir | Internal-Infra | Complete | None |
| GIMLE-297 | Immutable, content-addressed artifact store | Artifact Registry | Complete | Yes |
| GIMLE-298 | Streamed, digest-verified push with atomic commit | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-299 | Size-limited streaming upload rejection | Artifact Registry | Complete | Yes |
| GIMLE-300 | On-disk corruption detection and quarantine | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-301 | Periodic full-store integrity scrub | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-302 | Version retention sweeping (count and age based) | Artifact Registry | Complete | Yes |
| GIMLE-303 | Multi-replica peer synchronization (no consensus) | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-304 | Peer-sync conflict detection (irreconcilable divergence) | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-305 | Push/pull/list/delete `/artifacts/*` operational HTTP surface | Artifact Registry / API Server | Complete | Yes |
| GIMLE-306 | Maven-2-shaped `/repository/**` interop surface | Artifact Registry / API Server | Complete | Yes |
| GIMLE-307 | Server-computed checksum sidecars (never trusting client uploads) | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-308 | Generated `maven-metadata.xml` (never stored, always fresh) | Artifact Registry | Complete | Yes |
| GIMLE-309 | Maven GAV coordinate translation | Artifact Registry / Internal-Infra | Complete | Yes |
| GIMLE-310 | Defense-in-depth authorization (independent re-check, `ResourceKind.ARTIFACT`) | Authorization | Complete | Yes |
| GIMLE-311 | Module-scoped permission grants | Authorization | Complete | Yes |
| GIMLE-312 | Node pull-only artifact access, scoped to active assignments | Authorization | Complete | Yes |
| GIMLE-313 | Dual audit logging for push/delete decisions | Internal-Infra / Authorization | Complete | Yes |
| GIMLE-314 | Andvari's own console session story (`/auth/*`, bundled SPA) | API Server | Complete | Yes |
| GIMLE-315 | mTLS server with dynamic TLS reload | Internal-Infra | Complete | Yes |
| GIMLE-316 | Plaintext-mode loud supply-chain warning | Internal-Infra / Config | Complete | None |
| GIMLE-317 | Andvari observability instrumentation and Muninn shipping | Internal-Infra | Complete | Yes |
| GIMLE-318 | Process status endpoint (no RBAC gate) | API Server | Complete | Yes |
| GIMLE-319 | Node platform-log ingest | Logging | Complete | Yes |
| GIMLE-320 | Instance-log ingest | Logging | Complete | Yes |
| GIMLE-321 | Node/instance log read with cursor paging | Logging | Complete | Yes |
| GIMLE-322 | `follow=true` rejection on Muninn reads | Logging | Complete | Yes |
| GIMLE-323 | Metrics ingest | Metrics | Complete | Yes |
| GIMLE-324 | Metrics read | Metrics | Complete | Yes |
| GIMLE-325 | Traces ingest | Tracing | Complete | Yes |
| GIMLE-326 | Traces read | Tracing | Complete | Yes |
| GIMLE-327 | Day-bucketed JSON-lines store with oldest-first cursor semantics | Internal/Infra | Complete | Yes |
| GIMLE-328 | All-or-nothing batch validation on ingest | Internal/Infra | Complete | Yes |
| GIMLE-329 | Windows-safe on-disk path sanitization for colon-bearing processId | Internal/Infra | Complete | Yes |
| GIMLE-330 | Path-segment validation / directory-traversal defense | Internal/Infra | Complete | Yes |
| GIMLE-331 | Age-based retention sweep | Observability | Complete | Yes |
| GIMLE-332 | Plaintext-default transport with loud unauthenticated-mode warning | Config | Complete | Yes |
| GIMLE-333 | mTLS transport mode | Config | Complete | Yes |
| GIMLE-334 | Zero-downtime TLS material reload on certificate rotation | Config | Complete | Yes |
| GIMLE-335 | Node-identity check on node-log ingest | Internal/Infra | Complete | Partial |
| GIMLE-336 | Instance-owner check on instance-log ingest | Internal/Infra | Complete | None |
| GIMLE-337 | Verified-certificate-presence check on metrics/traces ingest | Internal/Infra | Complete | None |
| GIMLE-338 | Read surface has no RBAC/authorization re-check (documented-vs-actual gap) | Internal/Infra | Partial | None |
| GIMLE-339 | `/status` operational endpoint | Observability | Complete | Yes |
| GIMLE-340 | Default OpenTelemetry tracer installation | Tracing | Complete | Yes |
| GIMLE-341 | Configurable, batched span exporter installation | Tracing | Complete | Yes |
| GIMLE-342 | Bounded-wait tracer flush | Tracing | Complete | Yes |
| GIMLE-343 | Periodic log-file shipping to Muninn | Logging | Complete | Yes |
| GIMLE-344 | Periodic Micrometer metrics shipping | Metrics | Complete | Yes |
| GIMLE-345 | One-shot trace-batch and prepared-batch shipping | Tracing | Complete | Yes |
| GIMLE-346 | Multi-endpoint best-effort fan-out shipping | Internal/Infra | Complete | Yes |
| GIMLE-347 | In-memory (non-persisted) log-shipping cursor | Internal/Infra | Complete | None |
| GIMLE-348 | Micrometer meter → NDJSON codec | Internal/Infra | Complete | Yes |
| GIMLE-349 | OpenTelemetry span → NDJSON codec | Internal/Infra | Complete | Yes |
| GIMLE-350 | `MuninnSpanExporter` (OpenTelemetry SDK integration) | Tracing | Complete | Yes |
| GIMLE-351 | JFR-based per-module CPU/allocation attribution | Observability | Complete | Yes |
| GIMLE-352 | Per-process tagged Micrometer metrics wrappers | Metrics | Complete | Yes |
| GIMLE-353 | WorkerMetrics thread-count / metaspace gauges | Metrics | Complete | Yes |
| GIMLE-354 | Fafnir authz-failure counter (rate-limiting signal) | Metrics | Complete | Yes |
| GIMLE-355 | Muninn endpoint list parsing from config | Config | Complete | Partial |
| GIMLE-356 | Fabric-route HTTP-to-service dispatch | Gateway/Routing | Complete | Yes |
| GIMLE-357 | Fabric-route argument coercion (`ParamType`) | Gateway/Routing | Complete | Yes |
| GIMLE-358 | Vessel-route HTTP reverse-proxy dispatch | Gateway/Routing | Complete | Yes |
| GIMLE-359 | Vessel-endpoint resolution with TTL cache | Gateway/Routing | Complete | Yes |
| GIMLE-360 | Round-robin load balancing over ready vessel endpoints | Gateway/Routing | Complete | Yes |
| GIMLE-361 | Stale-cache fallback on endpoint-refresh failure | Gateway/Routing | Complete | Yes |
| GIMLE-362 | Vessel-route error surfacing (no ready endpoint / connect failure) | Gateway/Routing | Complete | Yes |
| GIMLE-363 | Route-table config DSL parsing | Config | Complete | Yes |
| GIMLE-364 | Duplicate route-path rejection at config-parse time | Config | Complete | Yes |
| GIMLE-365 | Gateway HTTP server bootstrap via module lifecycle hooks | Gateway/Routing | Complete | None |
| GIMLE-366 | Gateway liveness and readiness probes | Gateway/Routing | Complete | None |
| GIMLE-367 | HTTP status-code error mapping across the dispatcher | Gateway/Routing | Complete | Yes |
| GIMLE-368 | Boot-only platform-layer JPMS workaround (`requires static`) | Internal/Infra | Complete | Yes |
| GIMLE-369 | Vessel proxy: no TLS, no header forwarding (v1 scope limitation) | Gateway/Routing | Partial | Yes |
| GIMLE-370 | Fabric route "quiet success" ambiguity for a misrouted service name | Gateway/Routing | Partial | Yes |
| GIMLE-371 | Deployment resource management (get/apply/delete) | CLI | Complete | Yes |
| GIMLE-372 | Job resource management (get/apply/delete) | CLI | Complete | Yes |
| GIMLE-373 | CronJob management incl. manual trigger | CLI | Complete | Yes |
| GIMLE-374 | DaemonSet resource management | CLI | Complete | Yes |
| GIMLE-375 | StatefulSet resource management | CLI | Complete | Yes |
| GIMLE-376 | Node inventory and cordon/uncordon | CLI | Complete | Yes |
| GIMLE-377 | Instance lifecycle event timeline | CLI | Complete | None |
| GIMLE-378 | Tenant management and quota configuration | CLI | Complete | Yes |
| GIMLE-379 | Tenant plain configuration key/value store | CLI | Complete | Yes |
| GIMLE-380 | Versioned secrets management (Fafnir proxy) | CLI / Security | Complete | Yes |
| GIMLE-381 | Artifact registry client (push/list/get/delete) | CLI / Build Tooling | Complete | Partial |
| GIMLE-382 | Log viewing and live tailing | CLI | Complete | None |
| GIMLE-383 | Audit trail query | CLI / Security | Complete | Yes |
| GIMLE-384 | RBAC role management | CLI / Security | Complete | Yes |
| GIMLE-385 | RBAC role binding management | CLI / Security | Complete | Yes |
| GIMLE-386 | Operator account management | CLI / Security | Complete | Yes |
| GIMLE-387 | Certificate lifecycle management (bootstrap token, CSR request/status/approve, renewal) | CLI / Security | Complete | Partial |
| GIMLE-388 | Dual table/JSON output formatting | CLI / Internal-Infra | Complete | Yes |
| GIMLE-389 | kubectl-shaped global flag parsing, manifest-kind apply dispatch, and mTLS/leader-aware HTTP client | Internal-Infra | Complete | Yes |
| GIMLE-390 | Topology validation (`hilmir validate`) | Release Management | Complete | Yes |
| GIMLE-391 | Cluster launch planning (`hilmir plan`) | Release Management | Complete | Yes |
| GIMLE-392 | Real multi-process cluster bring-up (`hilmir up`) | Release Management | Complete | Yes |
| GIMLE-393 | Cluster teardown and status reporting (`hilmir down`/`status`) | Release Management | Complete | Yes |
| GIMLE-394 | Cluster TLS/PKI bootstrap (`hilmir pki init`) | Release Management / Security | Complete | Yes |
| GIMLE-395 | Raft store membership add (`hilmir store add`) | Release Management | Complete | Yes |
| GIMLE-396 | Raft store membership remove (`hilmir store remove`) | Release Management | Complete | Yes |
| GIMLE-397 | Per-machine platform binary rolling upgrade with quorum-safe store restart (`hilmir upgrade-cluster`) | Release Management | Complete | Yes |
| GIMLE-398 | Bundle-based fresh release deployment (`hilmir deploy`) | Release Management | Complete | Yes |
| GIMLE-399 | Bundle upgrade with automatic resource pruning (`hilmir upgrade`) | Release Management | Complete | Yes |
| GIMLE-400 | Release rollback to a prior revision (`hilmir rollback`) | Release Management | Complete | Yes |
| GIMLE-401 | Full release teardown (`hilmir undeploy`) | Release Management | Complete | Yes |
| GIMLE-402 | Release listing (`hilmir releases`) | Release Management | Complete | Yes |
| GIMLE-403 | Release status inspection (`hilmir release-status`) | Release Management | Complete | Yes |
| GIMLE-404 | GitOps directory reconciliation (`hilmir sync`, incl. `--watch` and `--prune`) | Release Management | Complete | Yes |
| GIMLE-405 | `--watch` interval loop for sync | Release Management | Partial | None |
| GIMLE-406 | Bundle value templating and override precedence (`${values.*}` substitution) | Release Management | Complete | Yes |
| GIMLE-407 | Bundle manifest schema parsing and validation | Release Management | Complete | Yes |
| GIMLE-408 | Workload readiness polling for `--wait` | Release Management | Complete | Yes |
| GIMLE-409 | Doctor static deployability diagnostics (`hilmir doctor`) | Build Tooling | Complete | Yes |
| GIMLE-410 | Doctor cluster-aware checks (`--server`, `--tenant`) | Build Tooling | Complete | None |
| GIMLE-411 | Manifest scaffolding (`hilmir init`) | Build Tooling | Complete | Yes |
| GIMLE-412 | Gateway extension enable (`hilmir enable gateway`) | Release Management | Complete | Yes |
| GIMLE-413 | Gateway extension disable (`hilmir disable gateway`) | Release Management | Complete | Yes |
| GIMLE-414 | Bundled JRE resolution for platform-binary launches | Internal/Infra | Complete | Yes |
| GIMLE-415 | `java @argfile` command-line rewriting | Internal/Infra | Complete | Yes |
| GIMLE-416 | Run ledger persistence for `up`/`down`/`status`/`upgrade-cluster` | Internal/Infra | Complete | Yes |
| GIMLE-417 | TCP-connect readiness polling | Internal/Infra | Complete | Yes |
| GIMLE-418 | `mvn gimle:agent` — spawn a real node agent (plus its worker command tail) | Build Tooling | Complete | None |
| GIMLE-419 | `mvn gimle:bootstrap` — full local-dev cluster orchestration in one foreground command | Build Tooling | Complete | None |
| GIMLE-420 | Process-launcher Maven goals for individual platform processes (`controlplane`/`store`/`fafnir`/`muninn`/`andvari`/`tls-init`) | Build Tooling | Complete | None |
| GIMLE-421 | `mvn gimle:deploy` — apply a deployment manifest via a real CLI subprocess | Build Tooling | Complete | None |
| GIMLE-422 | `mvn gimle:doctor` — run hilmir doctor against the invoking project's own built jar | Build Tooling | Complete | Yes |
| GIMLE-423 | `mvn gimle:init` — scaffold manifests for the invoking project's own built jar | Build Tooling | Complete | Yes |
| GIMLE-424 | `mvn gimle:publish` — push a built module jar to the artifact registry | Build Tooling | Complete | None |
| GIMLE-425 | `mvn gimle:docs` — full documentation site build pipeline | Build Tooling | Complete | None |
| GIMLE-426 | `mvn gimle:flaky-tests` — run known-flaky-tagged tests in isolated standalone reactors | Build Tooling | Complete | Yes |
| GIMLE-427 | `mvn gimle:saga` — ensure a Saga test-report server is running | Build Tooling | Complete | Yes |
| GIMLE-428 | `mvn gimle:verify` — full build run under Saga tracking | Build Tooling | Complete | Yes |
| GIMLE-429 | `mvn gimle:saga-import` — standalone sweep-and-import of existing surefire reports | Build Tooling | Complete | None |
| GIMLE-430 | `mvn gimle:saga-stop` — best-effort local Saga server shutdown | Build Tooling | Complete | None |
| GIMLE-431 | Internal — Aether-based cross-module runtime classpath resolution | Internal/Infra | Complete | None |
| GIMLE-432 | Internal — host-matching java/mvn executable resolution and subprocess supervision | Internal/Infra | Complete | Yes |
| GIMLE-433 | Internal — git commit/branch capture for run identification | Internal/Infra | Complete | None |
| GIMLE-434 | Internal — surefire report discovery and totals aggregation, including flaky-testcase counting | Internal/Infra | Complete | Yes |
| GIMLE-435 | Operator session login / logout | Web Console / Auth | Complete | Yes |
| GIMLE-436 | Session bootstrap & 401 handling | Web Console / Auth | Complete | Yes |
| GIMLE-437 | Cluster Overview dashboard | Web Console / Frontend | Complete | None |
| GIMLE-438 | Tactical HUD / Signal display-mode toggle | Web Console / Frontend | Complete | None |
| GIMLE-439 | Deployments list/create/detail/delete | Web Console / Frontend | Complete | Yes |
| GIMLE-440 | Jobs (run-to-completion workload) list | Web Console / Frontend | Complete | Yes |
| GIMLE-441 | CronJobs list/detail | Web Console / Frontend | Complete | Yes |
| GIMLE-442 | DaemonSets list/detail | Web Console / Frontend | Complete | Yes |
| GIMLE-443 | StatefulSets list/detail | Web Console / Frontend | Complete | Yes |
| GIMLE-444 | Instances table with filtering (global + node/tenant-scoped) | Web Console / Frontend | Complete | Yes |
| GIMLE-445 | Nodes list/detail with capacity bars and staleness | Web Console / Frontend | Complete | Yes |
| GIMLE-446 | Tenants list/detail with quota management and delete | Web Console / Frontend | Complete | Yes |
| GIMLE-447 | Topology placement map | Web Console / Frontend | Complete | None |
| GIMLE-448 | Cluster metrics charts (lifecycle mix, capacity, quota pressure) | Web Console / Frontend | Complete | None |
| GIMLE-449 | Per-process metrics history (Muninn-backed) | Web Console / Frontend | Complete | Yes |
| GIMLE-450 | Trace span history viewer | Web Console / Frontend | Complete | Yes |
| GIMLE-451 | Log explorer with live tailing | Web Console / Frontend | Complete | Yes |
| GIMLE-452 | Crash-dump (hs_err) listing on Logs screen | Web Console / Frontend | Complete | None |
| GIMLE-453 | Config entries management (per-tenant) | Web Console / Frontend | Complete | Yes |
| GIMLE-454 | Secrets management (Fafnir-backed, versioned) | Web Console / Frontend | Complete | Yes |
| GIMLE-455 | Module artifact registry browser (Andvari-backed) | Web Console / Frontend | Complete | Yes |
| GIMLE-456 | RBAC access control (roles, role bindings, accounts) | Web Console / Frontend | Complete | Yes |
| GIMLE-457 | Audit trail viewer with filtering | Web Console / Frontend | Complete | Yes |
| GIMLE-458 | Control-plane status panel | Web Console / Frontend | Partial | None |
| GIMLE-459 | Theme toggle (light/dark) | Web Console / Frontend | Complete | None |
| GIMLE-460 | Playwright end-to-end smoke suite against a real cluster | Web Console / Testing | Complete | Yes |
| GIMLE-461 | Vault operator login/logout (session-cookie auth) | Web Console / Auth | Complete | Yes |
| GIMLE-462 | Vault status overview (uptime, active key, transport mode, tenants) | Web Console / Frontend | Complete | None |
| GIMLE-463 | Secrets browsing/reveal/version/write/destroy (vault-native UI) | Web Console / Frontend | Complete | Yes |
| GIMLE-464 | Tenant filter via URL search param | Web Console / Frontend | Complete | None |
| GIMLE-465 | Key rotation trigger | Web Console / Frontend | Complete | Yes |
| GIMLE-466 | Fafnir console error banner / global error capture | Web Console / Frontend | Complete | None |
| GIMLE-467 | Andvari operator login/logout (session-cookie auth) | Web Console / Auth | Complete | Yes |
| GIMLE-468 | Registry status overview (uptime, transport, recent pushes) | Web Console / Frontend | Complete | Yes |
| GIMLE-469 | Artifact catalog browsing & search | Web Console / Frontend | Complete | Yes |
| GIMLE-470 | Artifact version detail (download, checksum display, delete) | Web Console / Frontend | Complete | Yes |
| GIMLE-471 | Client-side SHA-256 checksum verification on download | Web Console / Frontend | Complete | Yes |
| GIMLE-472 | Push artifact dialog (drag-and-drop upload) | Web Console / Frontend | Complete | Yes |
| GIMLE-473 | Maven-2 repository interop view | Web Console / Frontend | Complete | None |
| GIMLE-474 | Andvari copy-to-clipboard utility | Web Console / Frontend | Complete | None |
| GIMLE-475 | Runs list (no authentication) | Web Console / Reporting | Complete | Yes |
| GIMLE-476 | Live run detail with streaming test feed | Web Console / Reporting | Complete | Yes |
| GIMLE-477 | Run attachments: Gherkin scenario tree, Chaos ledger, Surtr phase table | Web Console / Reporting | Complete | Yes |
| GIMLE-478 | Test detail / per-test history | Web Console / Reporting | Complete | Yes |
| GIMLE-479 | Compare two runs (diff view) | Web Console / Reporting | Complete | Yes |
| GIMLE-480 | Gjallarhorn flake scoreboard | Web Console / Reporting | Complete | Yes |
| GIMLE-481 | Saga console theming (no auth surface) | Web Console / Frontend | Complete | Yes |
| GIMLE-482 | NDJSON event ingest API | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-483 | Idempotent per-run ingest / re-ingest replacement | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-484 | Crash-safe append (torn-tail recovery) | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-485 | Surefire/Failsafe XML import | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-486 | Fold-import safety net for a live run's gap | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-487 | Run listing, detail, and cursor-paginated event reads | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-488 | Live NDJSON tail (`follow=true`) of a run's event stream | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-489 | Abandoned-run detection on restart | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-490 | Flake ledger derivation (fail-then-pass rule) and rebuild | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-491 | Flaky scoreboard with time-window ranking | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-492 | Test-tag index and quarantine status | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-493 | Per-test history endpoint | Reporting backend / Internal-Infra | Complete | Yes |
| GIMLE-494 | Path traversal protection on run IDs | Internal-Infra / Security | Complete | Yes |
| GIMLE-495 | Bundled console static serving | Internal-Infra / Config | Complete | Yes |
| GIMLE-496 | Poll-until-condition primitive (`Await`) | Internal/Infra | Complete | None |
| GIMLE-497 | Kernel-assigned loopback port leasing (`PortLease`) | Internal/Infra | Complete | Yes |
| GIMLE-498 | Heimdall event-driven cluster condition harness | Test Infrastructure | Complete | Partial |
| GIMLE-499 | Replica-scoped condition observation | Test Infrastructure | Complete | Yes |
| GIMLE-500 | Deployment/node/log condition builders | Test Infrastructure | Complete | Yes |
| GIMLE-501 | Time-windowed negative invariants (`Invariant`/`InvariantGuard`) | Test Infrastructure | Complete | Yes |
| GIMLE-502 | Forensic failure reporting | Test Infrastructure | Complete | Yes |
| GIMLE-503 | `hello-module` — minimal inert deployable fixture | Sample Module | Complete | None |
| GIMLE-504 | `greeter-provider` — real fabric service export with lifecycle hooks and health probes | Sample Module | Complete | Yes |
| GIMLE-505 | `greeter-consumer` — real cross-worker fabric call with MDC-tagged background caller | Sample Module | Complete | Yes |
| GIMLE-506 | `greeter-load-generator` — HTTP bridge for external load tools driving real fabric traffic | Sample Module / Load Testing | Complete | Yes |
| GIMLE-507 | Real multi-process cluster fixture (store/control-plane/agent/Fafnir/Muninn) | Internal/Infra | Complete | Yes |
| GIMLE-508 | On-the-fly compiled module variants via `TestModuleBuilder` | Internal/Infra | Complete | Yes |
| GIMLE-509 | Base cluster topology deploy across store cluster and multiple CP replicas | Cluster Validation | Complete | Yes |
| GIMLE-510 | Raft store resilience (member loss, leader failover, live membership change) | Cluster Validation | Complete | Yes |
| GIMLE-511 | Tiered self-healing (worker respawn, liveness-exhaustion escalation to FAILED) | Cluster Validation | Complete | Yes |
| GIMLE-512 | Classloader leak detection wired into a real worker | Cluster Validation | Complete | Yes |
| GIMLE-513 | Repeated redeploy stability without false-positive leaks | Cluster Validation | Complete | Yes |
| GIMLE-514 | Tier 1 worker density packing and its cap | Cluster Validation | Complete | Yes |
| GIMLE-515 | Node cordoning blocks new placement without evicting running instances | Cluster Validation | Complete | Yes |
| GIMLE-516 | DaemonSet per-node fan-out and dead-node assignment cleanup | Cluster Validation | Complete | Yes |
| GIMLE-517 | Job and CronJob real-cluster lifecycle | Cluster Validation | Complete | Yes |
| GIMLE-518 | StatefulSet sticky placement and volume persistence across worker restart | Cluster Validation | Complete | Yes |
| GIMLE-519 | Rolling update preserves serving capacity and reaches new version | Cluster Validation | Complete | Yes |
| GIMLE-520 | Surge worker promotion carries out via in-place retarget, not respawn | Cluster Validation | Complete | Yes |
| GIMLE-521 | Autoscaling under real request-rate, error-rate, queue-depth, and weighted-blended load | Load Testing / Cluster Validation | Complete | Yes |
| GIMLE-522 | Multi-tenant quota enforcement (flag-not-evict, and admission rejection) | Cluster Validation | Complete | Yes |
| GIMLE-523 | Circuit breaker excludes a consistently-failing replica | Cluster Validation | Complete | Yes |
| GIMLE-524 | Gossip/SWIM failure detection across real separate agent processes | Cluster Validation | Complete | Yes |
| GIMLE-525 | Observability data survives agent death (Muninn fallback) and control-plane metrics round-trip | Cluster Validation | Complete | Yes |
| GIMLE-526 | Worker-tier metrics/trace relay to Muninn via the agent | Cluster Validation | Complete | Yes |
| GIMLE-527 | Artifact registry (Andvari) resolution path end to end | Cluster Validation | Complete | Yes |
| GIMLE-528 | External HTTP request reaches a fabric service through the gateway | Cluster Validation | Complete | Yes |
| GIMLE-529 | Declarative cluster topology DSL/YAML parsing and validation | Internal/Infra | Complete | Yes |
| GIMLE-530 | Real subprocess cluster orchestration (`GimleCluster`) | Internal/Infra | Complete | Yes |
| GIMLE-531 | Cluster pooling per topology with destructive-scenario isolation | Internal/Infra | Complete | Yes |
| GIMLE-532 | JUnit `@Holmgang`/`@HolmgangCluster` extension for plain-JUnit cluster tests | Internal/Infra | Complete | Yes |
| GIMLE-533 | Fenrir randomized chaos-fault soak executor | Chaos Engineering | Complete | Yes |
| GIMLE-534 | Chaos ledger recording and rendering | Chaos Engineering | Complete | Yes |
| GIMLE-535 | Randomized fault soak with no lost writes (basic and compound-fault modes) | Chaos Engineering | Complete | Yes |
| GIMLE-536 | Muninn/Andvari replica-bounce resilience soak | Chaos Engineering | Complete | Yes |
| GIMLE-537 | Live store membership change (AddServer/RemoveServer) | Cluster Validation | Complete | Yes |
| GIMLE-538 | Mutual TLS end-to-end operation and anonymous-client rejection | Cluster Validation | Complete | Yes |
| GIMLE-539 | Control-plane partition tolerance (store-side) and reconvergence on heal | Cluster Validation | Complete | Yes |
| GIMLE-540 | Store leader self-demotion under silent peer partition; bounded write latency | Cluster Validation | Complete | Yes |
| GIMLE-541 | Tenant deployment lifecycle with secret delivery and clean deletion | Cluster Validation | Complete | Yes |
| GIMLE-542 | Tenant quota retroactive violation (flag, not evict) and admission rejection | Cluster Validation | Complete | Yes |
| GIMLE-543 | Node cordoning blocks placement until uncordoned | Cluster Validation | Complete | Yes |
| GIMLE-544 | Worker-tier self-healing and liveness-exhaustion escalation (Gherkin coverage) | Cluster Validation | Complete | Yes |
| GIMLE-545 | Zero-downtime rolling update under surge budget (Gherkin coverage) | Cluster Validation | Complete | Yes |
| GIMLE-546 | Request-rate autoscaling under real Gatling-driven fabric load (Gherkin coverage) | Load Testing | Complete | Yes |
| GIMLE-547 | Artifact registry coordinate-only deployment (Gherkin coverage) | Cluster Validation | Complete | Yes |
| GIMLE-548 | Surtr scale/churn/performance workload runner | Load Testing | Complete | Yes |
| GIMLE-549 | Surtr Muninn-window measurement (documented gap) | Load Testing | Partial | None |
| GIMLE-550 | Module-density Tier 1 packing Surtr reference workload | Load Testing | Complete | Yes |
| GIMLE-551 | Saga unified run reporting (Gherkin + JUnit + Fenrir + Surtr) | Internal/Infra | Complete | Yes |
| GIMLE-552 | Saga best-effort shipping to a remote report server | Internal/Infra | Complete | None |
| GIMLE-553 | Loki fault-injection proxy for store/control-plane link partitions | Internal/Infra | Complete | Yes |
| GIMLE-554 | Utgard multi-container distributed boot ordering | Cluster Validation | Complete | Yes |
| GIMLE-555 | Utgard real machine loss (hard container kill) and rejoin | Cluster Validation | Complete | Yes |
| GIMLE-556 | Utgard network partition (vs hard kill) with reconvergence | Cluster Validation | Complete | Yes |
| GIMLE-557 | Utgard real-hostname mTLS bootstrap across containers | Cluster Validation | Complete | Yes |
| GIMLE-558 | Utgard Docker container fleet management primitives | Internal/Infra | Complete | Yes |
| GIMLE-559 | Docker Compose manual validation topologies (bundled-JRE and full-JRE) | Packaging / Internal-Infra | Complete | None |
| GIMLE-560 | Standalone CLI distribution archive | Packaging | Complete | None |
| GIMLE-561 | Standalone Hilmir bootstrap-tool distribution archive | Packaging | Complete | None |
| GIMLE-562 | Cluster-machine platform distribution archive | Packaging | Complete | Partial |
| GIMLE-563 | Opt-in bundled-JRE distribution variant (`dist-with-jre` profile) | Packaging | Complete | Partial |
| GIMLE-564 | Distribution archive checksums and SBOM generation | Packaging | Complete | None |
| GIMLE-566 | Service abstraction: stable name, CRUD API, and endpoint reconciliation | Reconciliation / Service Fabric | Complete | Yes |
| GIMLE-567 | Fabric listener-side tenant re-check on inbound service calls | Fabric / Multi-tenancy | Complete | Yes |
| GIMLE-568 | gimle-bifrost: per-node service proxy (kube-proxy analogue) | Service Fabric | Complete | Yes |
| GIMLE-569 | gimle-skald: cluster DNS server resolving Service names to live endpoints | Service Fabric | Complete | Yes |
| GIMLE-570 | Gateway virtual-host routing and Service-backed (SERVICE) route kind | Gateway/Routing | Complete | Yes |
| GIMLE-571 | Hosted-module runtime port reporting folded into instance observation | Networking/Service Discovery | Complete | Yes |
| GIMLE-572 | NetworkPolicySpec durable persistence through StoreClient | Networking/Security | Complete | Yes |
| GIMLE-573 | Doctor advisory-only outbound-connection hazard detection | Build Tooling | Complete | Yes |
| GIMLE-574 | Per-deployment-scoped NetworkPolicySpec enforcement | Networking/Security | Complete | Yes |
| GIMLE-575 | Bifrost fails closed for a NetworkPolicySpec-restricted Service | Networking/Security | Complete | Yes |
| GIMLE-576 | Remote (SSH) fleet bootstrap (`hilmir up/down/status --remote`) | Release Management | Complete (v1 scope) | Yes |
| GIMLE-577 | Multi-jar publish with per-module tenant tagging (`kind: ArtifactSet`) | Artifact Registry | Complete (v1 scope) | Yes |

## Detailed Requirements

### gimle-core

#### GIMLE-001 — Semantic module versioning

- **Category**: Module System
- **User story**: As a platform operator, I want module versions parsed and compared per a strict major.minor.patch[-qualifier] grammar.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/Version.java`
- **Test coverage**: `VersionTest` (parses_major_minor_patch, orders_by_major_then_minor_then_patch, unqualified_outranks_qualified, qualifiers_compare_lexicographically, rejects_negative_components)
- **Gherkin scenario**:
  ```gherkin
  Given "1.2.3-rc1" parsed via Version.parse, When compared against unqualified "1.2.3", Then the unqualified version compares as greater.
  ```

#### GIMLE-002 — Version range constraint matching

- **Category**: Module System
- **User story**: As a module author, I want to declare a dependency's acceptable version range using Maven/OSGi interval notation.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/VersionRange.java`
- **Test coverage**: `VersionRangeTest` (inclusive/exclusive bounds, unbounded above, rejects lower>upper)
- **Gherkin scenario**:
  ```gherkin
  Given "[1.0.0,2.0.0)", When candidate 1.5.0 is checked, Then satisfies; 2.0.0 does not (exclusive upper).
  ```

#### GIMLE-003 — Module descriptor validation (request ≤ limit invariant)

- **Category**: Module System
- **User story**: As a platform operator, I want a module's resource request validated against its limit at construction time.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/ModuleDescriptor.java`
- **Test coverage**: `ModuleDescriptorTest` (accepts_request_within_limit, rejects_memory/cpu_request_exceeding_limit, rejects_blank_name, id_combines_name_and_version)
- **Gherkin scenario**:
  ```gherkin
  Given a ModuleDescriptor whose request exceeds limit, When constructed, Then IllegalArgumentException naming both values.
  ```

#### GIMLE-004 — Tiered isolation model (TIER_1/TIER_2/TIER_3)

- **Category**: Module System
- **User story**: As a module author, I want to declare an isolation tier per module.
- **Status**: Complete (enum); TIER_3 enforcement Partial — see gimle-os
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/IsolationTier.java`
- **Test coverage**: NONE directly (exercised indirectly via ModuleDescriptorParserTest, gimle-os's PortableJvmFlagsResourceLimiterTest)
- **Gherkin scenario**:
  ```gherkin
  Given isolation.tier: TIER_2, When parsed, Then the module requires a dedicated worker JVM.
  ```

#### GIMLE-005 — Kubernetes-shaped resource quantity parsing

- **Category**: Resource Limiting
- **User story**: As a module author, I want to write resource requests using Kubernetes-style quantity suffixes.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/ResourceSpec.java`, `ResourceQuantity.java`
- **Test coverage**: Indirect via ModuleDescriptorTest, VesselSpecTest — NONE direct
- **Gherkin scenario**:
  ```gherkin
  Given ResourceSpec{memory:"128Mi", cpu:"250m"}, When memoryBytes()/cpuMillicores() called, Then 134217728 and 250 respectively.
  ```

#### GIMLE-006 — Tenant-scoped service export

- **Category**: Module System / Multi-tenancy
- **User story**: As a platform operator, I want a module's exported service restricted to an explicit tenant allow-list.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/ServiceExport.java`
- **Test coverage**: `ServiceExportTenantTest` (unrestricted permits any, restricted permits only listed, never permits untenanted caller, empty allow list permits no one)
- **Gherkin scenario**:
  ```gherkin
  Given ServiceExport{allowedTenantIds={"tenant-a"}}, When permitsTenant(Optional.of("tenant-b")), Then false.
  ```

#### GIMLE-007 — StatefulSet-shaped persistent volume declaration

- **Category**: Module System / Storage
- **User story**: As a module author building StatefulSet-kind workloads, I want to declare a persistent local-disk volume request in the module artifact.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/VolumeRequest.java`
- **Test coverage**: `ModuleDescriptorParserTest` (no_volume_leaves_it_empty, parses_volume_size_and_mount_path, volume_with_missing_mount_path_throws, volume_with_non_positive_size_bytes_throws)
- **Gherkin scenario**:
  ```gherkin
  Given gimle-module.yaml declaring volume:{sizeBytes,mountPath}, When parsed, Then ModuleDescriptor.volume() is present.
  ```

#### GIMLE-008 — Health probe configuration with initial delay

- **Category**: Module System / Health
- **User story**: As a module author, I want liveness/readiness probe classes plus an initial delay before the first check.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/HealthProbes.java`
- **Test coverage**: `ModuleDescriptorParserTest` (no_initial_delay leaves empty, parses seconds, negative/non-numeric throws)
- **Gherkin scenario**:
  ```gherkin
  Given health:{liveness:..., initialDelaySeconds:30}, When parsed, Then HealthProbes.initialDelay() is 30s.
  ```

#### GIMLE-009 — Vessel hosting mode (plain-process workload)

- **Category**: Module System / Vessel Hosting
- **User story**: As a platform operator, I want to run a plain runnable jar as its own OS process under the same scheduling/health/resource machinery as a module.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/vessel/VesselSpec.java`, `VesselProbes.java`, `VesselProbeSpec.java`, `VesselEnvValue.java`, `VesselFileMount.java`, `VesselArtifacts.java`
- **Test coverage**: `VesselSpecTest` (no probes/ports is valid, TCP readiness requires a declared port, fixed port allocation carries its number, negative fixed port rejected); VesselArtifacts NONE dedicated
- **Gherkin scenario**:
  ```gherkin
  Given a vessel: block declaring args/env/probe/resources, When validated, Then synthesized into a ModuleDescriptor always at TIER_2, no exports/hooks/volume; a TCP/HTTP probe with no declared port is rejected.
  ```

#### GIMLE-010 — Artifact-registry vs local-path reference resolution

- **Category**: Internal/Infra
- **User story**: As a platform developer, I want one convention (blank string = resolve from Andvari by coordinate) for artifact path travel.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/ArtifactReference.java`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given artifactPath="", When ArtifactReference.isRegistryCoordinate is called, Then true, isLocalPath false.
  ```

#### GIMLE-011 — RBAC domain model (resources, verbs, permissions, roles, bindings)

- **Category**: Security / RBAC
- **User story**: As a cluster operator, I want a coarse but complete RBAC model, optionally tenant-scoped, granted via named roles.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/authz/Permission.java`, `Role.java`, `RoleBinding.java`, `ResourceKind.java`, `Verb.java`, `Principal.java`
- **Test coverage**: `PermissionTest` (unscoped covers any tenant, scoped only own tenant, mismatch never covers), `RoleBindingTest` (well-formed subject accepted, malformed rejected)
- **Gherkin scenario**:
  ```gherkin
  Given Permission scoped to (DEPLOYMENT,WRITE,tenant="acme"), When covers(DEPLOYMENT,WRITE,Optional.of("acme")), Then true; a different tenant, false.
  ```

#### GIMLE-012 — Built-in cluster-admin role and operator/node certificate groups

- **Category**: Security / RBAC
- **User story**: As a cluster operator upgrading to RBAC, I want every existing operator certificate to retain full access via an implicit cluster-admin binding.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/authz/BuiltinRoles.java`
- **Test coverage**: `BuiltinRolesTest` (cluster_admin_covers_every_resource_and_verb_unscoped, group_names_match_what_the_pki_layer_stamps)
- **Gherkin scenario**:
  ```gherkin
  Given BuiltinRoles.CLUSTER_ADMIN, When inspected, Then contains one unscoped Permission for every (ResourceKind,Verb) combination.
  ```

#### GIMLE-013 — Console password hashing (PBKDF2-HMAC-SHA256)

- **Category**: Security
- **User story**: As a cluster operator, I want console account passwords stored as salted PBKDF2 hashes, never plaintext.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/authz/PasswordHashes.java`, `Account.java`
- **Test coverage**: `PasswordHashesTest` (hash_then_verify_round_trips, verify_rejects_wrong_password, two_hashes_differ_due_to_random_salt, verify_rejects_truncated_hash)
- **Gherkin scenario**:
  ```gherkin
  Given a password hashed twice via PasswordHashes.hash, When compared, Then outputs differ but both verify against the original.
  ```

#### GIMLE-014 — Mutual-TLS SSLContext construction

- **Category**: PKI / Internal-Infra
- **User story**: As a platform component, I want to build an mTLS SSLContext from my own leaf cert/key plus the cluster CA in one call.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/tls/SslContexts.java`
- **Test coverage**: gimle-pki's `SslContextsIntegrationTest` (mutual_tls_handshake_succeeds_when_both_sides_trust_the_same_ca, handshake_is_rejected_when_the_client_trusts_a_different_ca_than_the_server)
- **Gherkin scenario**:
  ```gherkin
  Given valid cert/key/CA PEM files, When SslContexts.forMutualTls(settings) is called, Then a TLS 1.3 handshake against a same-CA-trusting peer succeeds.
  ```

#### GIMLE-015 — Cluster-wide transport protocol switch (plaintext/TLS)

- **Category**: Config
- **User story**: As a cluster operator, I want one system property/env var to switch every network-exposed transport between plaintext and TLS.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/tls/TransportProtocol.java`, `TlsSettings.java`
- **Test coverage**: `TransportProtocolTest`, `TlsSettingsTest` (defaults to plaintext, case-insensitive, rejects unrecognized value, fails fast on unset property)
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.transport.protocol=tls, When TransportProtocol.fromConfig() is called, Then TLS; an unrecognized value throws.
  ```

#### GIMLE-016 — Stateless HMAC-signed console session tokens

- **Category**: Security
- **User story**: As a console user, I want my login session represented as a self-verifying, stateless signed token.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/session/SessionTokens.java`
- **Test coverage**: `SessionTokensTest` (issue_then_verify round trips, expired rejected, wrong-key rejected, tampered rejected, garbage input rejected not thrown)
- **Gherkin scenario**:
  ```gherkin
  Given a token issued with 5-minute TTL, When verified 1ms before expiry, Then succeeds; after expiry, tampered, or wrong-key-signed, returns empty.
  ```

#### GIMLE-017 — Session-signing key file load-or-create with owner-only permissions

- **Category**: Security
- **User story**: As an operator standing up a console-serving process, I want its session-signing key auto-generated and persisted with owner-only permissions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/session/SessionKeyFileManager.java`
- **Test coverage**: `SessionKeyFileManagerTest` (generates_on_first_run_reuses_on_later, rejects corrupted/empty key file)
- **Gherkin scenario**:
  ```gherkin
  Given no key file exists, When loadOrCreate called twice, Then first generates rw------- key, second reuses it.
  ```

#### GIMLE-018 — Per-key exponential-backoff login throttle

- **Category**: Security
- **User story**: As a cluster operator, I want repeated failed login attempts against one username or address throttled with exponential backoff.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/throttle/LoginThrottle.java`
- **Test coverage**: `LoginThrottleTest` (delay doubles up to cap, success clears history, keys tracked independently)
- **Gherkin scenario**:
  ```gherkin
  Given 3 failed attempts (threshold 3), When a 4th failure is recorded, Then throttledUntil(key) returns a future instant, doubling per failure up to a cap.
  ```

#### GIMLE-019 — Structured JSON log encoding with APPLICATION/PLATFORM categorization

- **Category**: Observability / Logging
- **User story**: As an operator querying logs programmatically, I want every log line self-describing JSON tagged with process role, node id, and (for hosted modules) deployment/instance identity.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/JsonLogEncoder.java`, `InstanceMdcKeys.java`
- **Test coverage**: `JsonLogEncoderTest` (categorizes platform/application, tenant id included only when present, process role/node id read fresh)
- **Gherkin scenario**:
  ```gherkin
  Given a log event with deploymentName/instanceIndex MDC keys set, When encoded by JsonLogEncoder, Then category="APPLICATION" carrying moduleId/deploymentName/instanceIndex; without those, category="PLATFORM".
  ```

#### GIMLE-020 — Human-readable colored console log encoding

- **Category**: Observability / Logging
- **User story**: As a developer watching a process's terminal, I want log lines colored by level and tagged with [deployment#index].
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/TextLogEncoder.java`, `AnsiPalette.java`
- **Test coverage**: `TextLogEncoderTest`, `AnsiPaletteTest` (override wins regardless of environment)
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.color=always, When encoded by TextLogEncoder, Then output contains ANSI escapes; -Dgimle.color=never produces none.
  ```

#### GIMLE-021 — Runtime-switchable console log format (text default, JSON opt-in)

- **Category**: Observability / Logging
- **User story**: As a platform developer, I want the console appender to default to colored text but be forceable to JSON.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/ConsoleLogEncoder.java`
- **Test coverage**: `ConsoleLogEncoderTest` (explicit json/text override, no override defaults to text)
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.log.console=json, When CONSOLE appender starts, Then delegates to JsonLogEncoder; no override defaults to text.
  ```

#### GIMLE-022 — MDC-tagged proxying for same-worker and probe-loop invocations

- **Category**: Observability / Logging
- **User story**: As a platform developer, I want a hosted module's service reference/probe callback automatically tagged with that instance's identity in the logging MDC.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/InstanceMdcContext.java`
- **Test coverage**: `InstanceMdcContextTest` (tag_proxy sets/restores MDC, restores on throw, run_tagged restores previous value)
- **Gherkin scenario**:
  ```gherkin
  Given a service reference wrapped via InstanceMdcContext.tagProxy, When a method throws, Then caller's MDC is restored to prior state, original exception propagates.
  ```

#### GIMLE-023 — Per-instance sifted log files

- **Category**: Observability / Logging
- **User story**: As an operator debugging one instance among many hosted in the same worker, I want that instance's own log lines routed to their own rotated file.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/InstanceSiftingFileAppender.java`
- **Test coverage**: `InstanceSiftingFileAppenderTest` (routes application lines by deployment/instance, skips platform lines, never leaks across instances, reopens after close)
- **Gherkin scenario**:
  ```gherkin
  Given two instances of different deployments logging concurrently, When both emit APPLICATION lines, Then each lands only in its own deployment-index.log file.
  ```

#### GIMLE-024 — Platform (non-instance) log file appender

- **Category**: Observability / Logging
- **User story**: As an operator, I want a process's own component logs written to a dedicated rotated file separate from any hosted-instance output.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/PlatformFileAppender.java`
- **Test coverage**: Exercised via `LogRotationTest`; no dedicated unit test class
- **Gherkin scenario**:
  ```gherkin
  Given an APPLICATION event and a PLATFORM event, When both reach PlatformFileAppender, Then only the PLATFORM event is written.
  ```

#### GIMLE-025 — Kubelet-style size/count log rotation

- **Category**: Observability / Logging
- **User story**: As an operator, I want log files rotated by size with a fixed number of retained copies, matching kubelet's semantics.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/RollingFileAppenders.java`
- **Test coverage**: `LogRotationTest` (rolls over by size and evicts oldest, cursor paging/follow resolve correctly across rotation)
- **Gherkin scenario**:
  ```gherkin
  Given small maxFileSizeBytes/maxFiles, When enough lines exceed the cap repeatedly, Then the oldest rotated copy is evicted past maxFiles.
  ```

#### GIMLE-026 — Cursor-based log paging and live-follow streaming

- **Category**: Observability / Logging
- **User story**: As an operator, I want to page backward through historical log lines and live-tail via a stable timestamp cursor.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/LogFileReader.java`
- **Test coverage**: `LogRotationTest#cursor_paging_and_follow_resolve_correctly_across_a_rotation_boundary`
- **Gherkin scenario**:
  ```gherkin
  Given a log stream spanning a rotation, When streamFollow is called from a cursor before the rotation, Then every line after that cursor is streamed as NDJSON, including lines now in the rotated file.
  ```

#### GIMLE-027 — Startup banner rendering with terminal color/Unicode auto-detection

- **Category**: Internal/Infra
- **User story**: As an operator starting any Gimlé process, I want a branded startup banner falling back to ASCII on non-UTF-8 terminals.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/banner/GimleBanner.java`, `AnsiPalette.java`, `GimleVersion.java`
- **Test coverage**: `GimleBannerTest`, `GimleVersionTest`
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.banner.enabled=false, When GimleBanner.print is called, Then nothing is written.
  ```

#### GIMLE-028 — Single-write length-prefixed wire framing

- **Category**: Internal/Infra
- **User story**: As a platform developer implementing a binary transport, I want a shared length-prefix framing helper that writes the length and body as one syscall to avoid Nagle-related latency.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/codec/Frames.java`
- **Test coverage**: NONE dedicated
- **Gherkin scenario**:
  ```gherkin
  Given a body byte array, When Frames.writeFrame(out, body) is called, Then exactly one write call places a 4-byte length prefix followed by the body, then flushes.
  ```

#### GIMLE-029 — Hand-rolled JSON parser/writer

- **Category**: Internal/Infra
- **User story**: As a platform developer, I want a minimal, dependency-free JSON reader/writer for the control plane's fixed, fully-known HTTP shapes.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/Json.java`
- **Test coverage**: `JsonTest` (nested objects/arrays, negative/exponent numbers, escaped strings, round trip, escapes special chars, malformed throws)
- **Gherkin scenario**:
  ```gherkin
  Given a nested JSON document, When parsed then re-written via Json.write(Json.parse(text)), Then every value round-trips; malformed input throws IllegalArgumentException.
  ```

#### GIMLE-030 — Agent↔worker control-channel protocol and codec

- **Category**: Internal/Infra / Protocol
- **User story**: As a platform developer, I want a simple, human-debuggable line-oriented protocol for the agent-worker control channel.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/ControlMessage.java`, `ControlMessageCodec.java`
- **Test coverage**: `ControlMessageCodecTest` (module id with qualifier round trips, rejects empty line/unknown type/missing fields/malformed module id)
- **Gherkin scenario**:
  ```gherkin
  Given a ControlMessage.InstallModule with a free-text artifactPath containing a space, When encoded/decoded via ControlMessageCodec, Then field-for-field identical.
  ```

#### GIMLE-031 — Node registration/heartbeat/capacity-reporting protocol

- **Category**: Internal/Infra / Protocol
- **User story**: As the control plane, I want each node agent to register supported isolation tiers/labels once and periodically report capacity/instance state.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/NodeRegistration.java`, `NodeCapabilities.java`, `NodeHeartbeat.java`, `ResourceUsageSnapshot.java`, `InstanceObservation.java`
- **Test coverage**: NONE (exercised in gimle-controlplane)
- **Gherkin scenario**:
  ```gherkin
  Given a node registering with NodeCapabilities(supportedTiers={TIER_1,TIER_2}), When the scheduler considers a TIER_3 replica, Then rejected as a placement candidate.
  ```

#### GIMLE-032 — Instance lifecycle event log model

- **Category**: Observability
- **User story**: As an operator, I want every instance's lifecycle transition recorded as a durable, stably-identified event with a compact cause summary on failure.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/InstanceEvent.java`, `InstanceEventKind.java`
- **Test coverage**: NONE direct (produced by gimle-module's ModuleController/LifecycleEvent)
- **Gherkin scenario**:
  ```gherkin
  Given a TRANSITION_FAILED event, When constructed, Then it carries a non-empty causeSummary alongside a stable id.
  ```

#### GIMLE-033 — Cross-resource audit trail model

- **Category**: Security / Audit
- **User story**: As a cluster operator, I want every authorization decision recorded as a durable, queryable audit entry.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/AuditEvent.java`
- **Test coverage**: `AuditEventTest` (denied represented same as allowed, null groups/tenant/target coalesce to empty, blank id rejected)
- **Gherkin scenario**:
  ```gherkin
  Given a denied authorization decision, When recorded as an AuditEvent, Then allowed=false and otherwise structurally identical to an allowed one.
  ```

#### GIMLE-034 — Certificate bootstrap (CSR) request/response protocol

- **Category**: PKI
- **User story**: As a node agent or new human operator, I want a standard request/response shape for submitting a CSR.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/CsrSubmission.java`, `CsrResult.java`, `CsrPurpose.java`, `CsrRequestStatus.java`
- **Test coverage**: NONE direct (approval-policy logic in gimle-controlplane's ApiServer)
- **Gherkin scenario**:
  ```gherkin
  Given CsrSubmission with purpose=OPERATOR_CLIENT, When submitted, Then CsrResult status=PENDING with a requestId, never auto-approved.
  ```

#### GIMLE-035 — Assigned-instance work-order model (incl. in-place rename and vessel dispatch)

- **Category**: Scheduling
- **User story**: As a node agent, I want one work-order record per placement decision that also tells me whether it's a retarget or a vessel dispatch.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/AssignedInstance.java`
- **Test coverage**: NONE direct (exercised in gimle-agent/gimle-controlplane)
- **Gherkin scenario**:
  ```gherkin
  Given an AssignedInstance with renamedFromInstanceIndex present, When the agent processes it, Then it retargets the already-running instance under that prior index in place.
  ```

#### GIMLE-036 — Bounded-retry-with-backoff restart policy (CrashLoopBackOff-equivalent)

- **Category**: Self-Healing
- **User story**: As the platform, I want one shared exponential-backoff-with-rolling-window retry policy for both module-level and worker-level restart decisions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/restart/RestartTracker.java`
- **Test coverage**: `RestartTrackerTest` (allows retry within budget, exhausts after max attempts, delay grows exponentially/capped, window resets budget, success resets tracker)
- **Gherkin scenario**:
  ```gherkin
  Given a tracker with maxAttemptsPerWindow=3, When a 4th failure is recorded within the window, Then recordFailureAndCheckShouldRetry returns false.
  ```

#### GIMLE-037 — Tenant identity and resource quota model

- **Category**: Multi-tenancy
- **User story**: As a cluster operator, I want each tenant to carry an explicit resource ceiling.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/tenant/Tenant.java`, `ResourceQuota.java`
- **Test coverage**: NONE direct (enforcement is gimle-controlplane's QuotaReconciler)
- **Gherkin scenario**:
  ```gherkin
  Given a Tenant with negative quota field, When constructed, Then rejected with IllegalArgumentException.
  ```

#### GIMLE-038 — Tenant-scoped config/secret entry model

- **Category**: Config / Secrets
- **User story**: As a module author, I want to read tenant-scoped config or secret values through one uniform record shape.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/config/ConfigEntry.java`
- **Test coverage**: NONE direct
- **Gherkin scenario**:
  ```gherkin
  Given ConfigEntry{encrypted:true}, When constructed and value() accessed, Then a defensive clone is returned each time.
  ```

#### GIMLE-039 — Bundled SPA static-asset resolution from classpath

- **Category**: Internal/Infra / Web
- **User story**: As a platform developer packaging a console frontend into a backend process's own jar, I want a shared mechanism to resolve the bundled SPA's static root whether running from a real jar or exploded classes.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/web/BundledSpa.java`
- **Test coverage**: `BundledSpaTest` (file-scheme, jar-scheme, empty when absent, resolves different markers for different consoles)
- **Gherkin scenario**:
  ```gherkin
  Given a marker resource existing only inside a jar's entry, When BundledSpa.resolve(classLoader, markerResource) is called against a real built jar, Then it opens a jar filesystem and returns the marker's parent directory.
  ```

#### GIMLE-040 — SPA static file serving with client-side-route fallback

- **Category**: Internal/Infra / Web
- **User story**: As a platform developer serving a built React SPA, I want unknown paths to fall back to the SPA shell (200), while a genuinely missing asset still 404s, with path-traversal/symlink-escape rejected.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/web/SpaStaticHandler.java`
- **Test coverage**: `SpaStaticHandlerTest` (serves real static file, falls back to shell, missing asset 404s, rejects traversal, rejects symlink escape)
- **Gherkin scenario**:
  ```gherkin
  Given a request for /some/client/route with no matching file, When handled by SpaStaticHandler, Then the SPA shell is returned with 200; /../etc/passwd is rejected with 400.
  ```

#### GIMLE-041 — Saga test-run event model and NDJSON codec

- **Category**: Internal/Infra / Testing
- **User story**: As a test-infrastructure maintainer, I want every test run's events shipped as an append-only, replayable NDJSON stream.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/saga/SagaEvent.java`, `SagaEventCodec.java`
- **Test coverage**: `SagaEventCodecTest` (single line naming type first, absent fields omitted)
- **Gherkin scenario**:
  ```gherkin
  Given a TestFinished event with no failure fields, When encoded via SagaEventCodec, Then absent Optional failure fields are omitted, never written as null.
  ```

#### GIMLE-042 — Stable failure-signature hashing for flaky-test clustering

- **Category**: Internal/Infra / Testing
- **User story**: As a test-infrastructure maintainer, I want a stable, normalized hash of a failure's "shape" so the same underlying defect clusters under one signature across runs.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/saga/FailureSignature.java`
- **Test coverage**: `FailureSignatureTest` (run-specific numbers don't change signature, hex ids don't change it, different exception types differ, different messages differ, oversized messages truncated)
- **Gherkin scenario**:
  ```gherkin
  Given two failure messages differing only in an embedded port number, When both hashed via FailureSignature.of, Then identical signature; a genuinely different message produces a different one.
  ```

### gimle-module

#### GIMLE-043 — Module dependency resolution with cycle detection

- **Category**: Module System
- **User story**: As a platform operator, I want a module's requires: declarations resolved to the highest satisfying installed version, with cycles rejected up front.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/resolve/ModuleResolver.java`
- **Test coverage**: `ModuleResolverTest` (wires to highest satisfying version, candidate must be resolved/active, unsatisfied requirement reported, 2/3-length cycle detected, diamond dependency not a cycle, independent dependents wire independently)
- **Gherkin scenario**:
  ```gherkin
  Given module A requires B and B requires A, When ModuleResolver.resolve(A) is called, Then GimleResolutionException naming the cycle.
  ```

#### GIMLE-044 — Module registry (install bookkeeping, idempotent re-install, content-mismatch rejection)

- **Category**: Module System
- **User story**: As the platform, I want one authoritative source of truth for installed modules, with identical re-install a no-op but content-mismatch rejected.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/resolve/ModuleRegistry.java`
- **Test coverage**: `ModuleRegistryTest` (register stores as installed, idempotent identical re-register, rejects differing re-register, unknown module id throws, named transitions update state, mark_failed reachable)
- **Gherkin scenario**:
  ```gherkin
  Given module foo@1.0.0 already registered, When register is called again with identical sha256, Then no-op returning the same id; differing sha256 throws.
  ```

#### GIMLE-045 — Module lifecycle state machine (INSTALLED→RESOLVED→STARTING→ACTIVE→STOPPING→UNINSTALLED, plus FAILED/COMPLETED)

- **Category**: Module System
- **User story**: As the platform, I want a module driven through a strict lifecycle state machine — gating hooks abort/propagate synchronously, teardown hooks best-effort never blocking.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleController.java`, `ModuleState.java`, `LifecycleEvent.java`
- **Test coverage**: `ModuleControllerTest` (full happy path, start before resolve illegal, stop before active illegal, resolve failure marks failed, uninstall rejects active module, force_failed transitions to failed, complete_succeeded/failed paths)
- **Gherkin scenario**:
  ```gherkin
  Given a module whose onStart hook throws, When ModuleController.start(id) is called, Then transitions to FAILED, emits TransitionFailed, and rethrows wrapped as GimleLifecycleException.
  ```

#### GIMLE-046 — Dynamic per-module-version JPMS ModuleLayer construction

- **Category**: Module System
- **User story**: As the platform, I want each resolved module version to get its own dedicated ModuleLayer/classloader, parented on the shared platform layer plus wired dependencies' layers.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/layer/ModuleLayerFactory.java`, `ModuleLayerHandle.java`, `PlatformLayer.java`
- **Test coverage**: `ModuleLayerFactoryTest` (builds dependency-free layer, dependent layer calls into exported API, two versions get distinct layers, missing parent layer fails with GimleResolutionException)
- **Gherkin scenario**:
  ```gherkin
  Given two installed versions of the same module both resolved, When each built via ModuleLayerFactory.create, Then distinct ModuleLayer/ClassLoader instances.
  ```

#### GIMLE-047 — Unnamed-module readability grant for bundled hooks/probes

- **Category**: Module System / Internal-Infra
- **User story**: As a hosted-module author bundling my own ModuleLifecycleHooks/LivenessProbe/ReadinessProbe implementations, I want my module's layer granted readability to the platform's own unnamed module.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/layer/ModuleLayerFactory.java` (`controller.addReads`)
- **Test coverage**: gimle-worker's `RealBundledHookAndProbeInvocationTest`; this module's own `ModuleLayerFactoryTest` exercises the general mechanism
- **Gherkin scenario**:
  ```gherkin
  Given a real module jar bundling its own ModuleLifecycleHooks implementation declared "requires static com.gimle.module", When the module's layer is created, Then the hook class resolves and correctly implements the platform's interface at runtime.
  ```

#### GIMLE-048 — Classloader leak detection via PhantomReference

- **Category**: Module System / Internal-Infra
- **User story**: As a platform operator, I want the platform to detect when a disposed module's classloader was not actually garbage-collected within a configurable window.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/leak/LeakTracker.java`, `ModuleLeakDetected.java`
- **Test coverage**: `LeakTrackerTest` (no leak when collected, leak reported when retained, wired through ModuleController reports no leak on clean stop)
- **Gherkin scenario**:
  ```gherkin
  Given a module's layer/loader is tracked after disposal and never released, When the tracking window elapses, Then a ModuleLeakDetected event fires naming the module id, survival duration, and (best-effort) retaining path.
  ```

#### GIMLE-049 — Repeated-redeploy flat-metaspace acceptance test

- **Category**: Module System
- **User story**: As the platform, I want a mandatory acceptance test that redeploys a module in a loop and asserts metaspace usage plateaus.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/test/java/com/gimle/module/leak/RedeployLoopFlatMetaspaceTest.java`, `RedeployLoopDriver.java`
- **Test coverage**: `RedeployLoopFlatMetaspaceTest#redeploy_loop_keeps_metaspace_flat`
- **Gherkin scenario**:
  ```gherkin
  Given a module redeployed N times in a loop, When metaspace usage is sampled, Then samples plateau after warm-up.
  ```

#### GIMLE-050 — Best-effort leak retaining-path attribution via JFR OldObjectSample

- **Category**: Module System / Internal-Infra
- **User story**: As an operator diagnosing a detected leak, I want the platform to attempt identifying what's still holding a reference into the leaked module's packages.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/leak/OldObjectSampleCorrelator.java`
- **Test coverage**: `RetainingPathAttributionTest#leak_detector_surfaces_a_retaining_path_when_the_worker_jvm_enables_path_to_gc_roots`
- **Gherkin scenario**:
  ```gherkin
  Given a worker JVM launched with the gimle-leak-detection JFR recording enabled with path-to-gc-roots=true, When a real leak is detected, Then OldObjectSampleCorrelator reports a human-readable retaining chain; without that flag, it degrades to no path.
  ```

#### GIMLE-051 — Module lifecycle hooks (reflectively instantiated, JPMS-exported)

- **Category**: Module System
- **User story**: As a module author, I want to implement onInstall/onStart/onStop/onUninstall hooks instantiated reflectively by the platform.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleLifecycleHooks.java`, `ModuleController.instantiateHooks`
- **Test coverage**: `RealHookInvocationTest#hooks_fire_in_order_with_a_dynamically_loaded_module`
- **Gherkin scenario**:
  ```gherkin
  Given a descriptor naming a real ModuleLifecycleHooks implementation in an exported package, When the module resolves, Then the hooks class is instantiated via its no-arg constructor and onInstall invoked.
  ```

#### GIMLE-052 — Job-kind run-to-completion hooks

- **Category**: Module System
- **User story**: As a module author building a batch workload, I want a single run(ctx) executed once to completion, reporting SUCCEEDED/FAILED.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/JobHooks.java`, `CompletionStatus.java`
- **Test coverage**: `ModuleControllerTest` (complete_succeeded/complete_failed/complete_rejects_non_active)
- **Gherkin scenario**:
  ```gherkin
  Given an ACTIVE Job-kind module whose JobHooks.run returns SUCCEEDED, When ModuleController.complete(id, SUCCEEDED) is called, Then transitions straight to COMPLETED, emitting a Completed lifecycle event.
  ```

#### GIMLE-053 — Module context API (in-flight tracking, service lookup, config, data dir, control-plane relay)

- **Category**: Module System
- **User story**: As a hosted module's hook/probe code, I want one context object exposing request in-flight counting, service lookup/invoke, config, volume path, and a whitelisted control-plane relay.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleContext.java`, `SimpleModuleContext.java`
- **Test coverage**: `SimpleModuleContextTest` (invoke_service_by_name delegates/empty-on-unknown/propagates exception); `DrainDeadlineTest#stop_completes_after_deadline_despite_perpetual_in_flight_work`
- **Gherkin scenario**:
  ```gherkin
  Given a hook calls ctx.beginRequest() and never calls endRequest(), When ModuleController.stop is called, Then the drain wait blocks up to its deadline because inFlightCount() > 0.
  ```

#### GIMLE-054 — In-worker round-robin service registry with version-aware cutover

- **Category**: Module System
- **User story**: As a hosted module, I want the same-worker service registry to select among ready providers round-robin, atomically cutting over to a newer version once ready.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/SimpleServiceRegistry.java`
- **Test coverage**: `SimpleServiceRegistryTest` (round robins, prefers highest ready version, falls back while highest has none ready, round robins within preferred version, mark_unready excludes without removing); `HotRedeployTest#old_and_new_versions_coexist_with_dependents_pinned_to_their_own_wiring`
- **Gherkin scenario**:
  ```gherkin
  Given both v1 (draining, still ready) and v2 (freshly started, one ready) registered, When lookup is called repeatedly, Then every call routes to v2 exclusively once v2 has any ready entry.
  ```

#### GIMLE-055 — Cross-tier name-driven service invocation

- **Category**: Module System
- **User story**: As a module whose routing config names a target only as runtime strings, I want to invoke it by name through the service registry.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ServiceRegistry.java` (default invokeByName), `SimpleServiceRegistry.java`
- **Test coverage**: `SimpleServiceRegistryTest` (invokes directly by name, unknown interface returns empty, wrong method name throws, rethrows application exception with real type, void method returns empty Optional)
- **Gherkin scenario**:
  ```gherkin
  Given a registered Greeter provider with method greet(String), When invokeByName("...Greeter",1,"greet",["java.lang.String"],["world"]) is called, Then invoked reflectively; a wrong method name throws rather than matching a different overload.
  ```

#### GIMLE-056 — Same-worker cross-module service publish/discover

- **Category**: Module System
- **User story**: As two hosted modules in the same worker JVM, I want the provider to publish a service and the consumer to look it up by interface type.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ServiceRegistry.java`, `SimpleServiceRegistry.java`
- **Test coverage**: `ServiceRegistryIntegrationTest` (consumer finds service, consumer finds nothing without a provider)
- **Gherkin scenario**:
  ```gherkin
  Given a provider registers a Greeter instance and a consumer resolves afterward, When the consumer calls ctx.lookupService(Greeter.class), Then finds the provider's instance directly.
  ```

#### GIMLE-057 — Graceful drain-then-dispose stop with deadline

- **Category**: Module System / Self-Healing
- **User story**: As the platform, I want stop to wait for in-flight requests to drain up to a fixed deadline, never indefinitely.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleController.java` (`awaitDrain`)
- **Test coverage**: `DrainDeadlineTest#stop_completes_after_deadline_despite_perpetual_in_flight_work`
- **Gherkin scenario**:
  ```gherkin
  Given a module whose in-flight counter never reaches zero, When stop is called with a drain timeout, Then stop still completes once the deadline passes.
  ```

#### GIMLE-058 — Hot redeploy (old/new version coexistence with pinned dependent wiring)

- **Category**: Module System
- **User story**: As a platform operator, I want to install a new version alongside the still-active old one, with existing dependents staying pinned to whichever version they originally wired to.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/resolve/ModuleResolver.java`, `ModuleWiring.java`
- **Test coverage**: `HotRedeployTest#old_and_new_versions_coexist_with_dependents_pinned_to_their_own_wiring`
- **Gherkin scenario**:
  ```gherkin
  Given dependent D wired to dependency v1, and v2 is then installed/resolved, When D's wiring is inspected afterward, Then still wired to v1 unless explicitly re-resolved.
  ```

#### GIMLE-059 — gimle-module.yaml descriptor parsing and validation

- **Category**: Module System
- **User story**: As a module author, I want my gimle-module.yaml parsed with strict validation (safe YAML loading, well-formed fields, request≤limit invariant).
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/descriptor/ModuleDescriptorParser.java`
- **Test coverage**: `ModuleDescriptorParserTest` (various); indirectly via TestModuleBuilderTest
- **Gherkin scenario**:
  ```gherkin
  Given isolation.tier: BOGUS, When parsed via ModuleDescriptorParser.parse, Then GimleManifestException naming the invalid tier value.
  ```

#### GIMLE-060 — Module artifact reading — real-JPMS-module and descriptor-presence validation

- **Category**: Module System
- **User story**: As the platform, I want a module jar rejected outright if it lacks a real module-info.class or its bundled gimle-module.yaml.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/artifact/ModuleArtifactReader.java`
- **Test coverage**: exercised via `TestModuleBuilderTest`; NONE dedicated `ModuleArtifactReaderTest` found
- **Gherkin scenario**:
  ```gherkin
  Given a jar with no module-info.class, When ModuleArtifactReader.read(jarPath) is called, Then GimleManifestException explaining automatic modules are rejected.
  ```

#### GIMLE-061 — Andvari artifact-registry pull-through cache

- **Category**: Module System / Internal-Infra
- **User story**: As a node agent or control plane, I want a module coordinate resolved from a local cache when present, or downloaded once from Andvari (digest-verified, atomically committed).
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/artifact/ArtifactPullCache.java`
- **Test coverage**: NONE direct (exercised end-to-end by gimle-smoke-tests' AndvariRegistryIT)
- **Gherkin scenario**:
  ```gherkin
  Given a coordinate not yet cached and Andvari's response has a mismatching sha256 header, When ArtifactPullCache.resolve is called, Then GimleManifestException reporting the digest mismatch, no torn file committed.
  ```

#### GIMLE-062 — Multi-endpoint Andvari failover on pull

- **Category**: Module System / Internal-Infra
- **User story**: As a node agent with multiple Andvari replica endpoints, I want a pull to try each endpoint in order until one succeeds.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/artifact/ArtifactPullCache.java`
- **Test coverage**: NONE direct
- **Gherkin scenario**:
  ```gherkin
  Given two configured endpoints, the first unreachable, When resolve is called, Then falls through to the second endpoint.
  ```

#### GIMLE-063 — Health probe interfaces (liveness/readiness)

- **Category**: Module System / Health
- **User story**: As a module author, I want to implement plain, parameterless LivenessProbe/ReadinessProbe interfaces called directly by the worker's probe loop.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/probe/LivenessProbe.java`, `ReadinessProbe.java`
- **Test coverage**: NONE direct (loop-driving behavior lives in gimle-worker)
- **Gherkin scenario**:
  ```gherkin
  Given a module implementing both ModuleLifecycleHooks and LivenessProbe on the same class, When the worker's probe loop invokes isAlive(), Then calls straight into that instance's method with no network hop.
  ```

#### GIMLE-571 — Hosted-module runtime port reporting folded into instance observation

- **Category**: Networking/Service Discovery
- **User story**: As a module author, I want to report the port(s) my module is actually listening on at runtime, so a Service fronting my module's deployment can resolve a live endpoint the same way it already can for a Vessel workload.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleContext.java` (`reportPort`, `reportedPorts`), `gimle-module/src/main/java/com/gimle/module/lifecycle/SimpleModuleContext.java`, `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`reportedPortsFor`), `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`metricsReportLoop`), `gimle-core/src/main/java/com/gimle/core/protocol/ControlMessage.java` (`MetricsReport.ports`), `gimle-core/src/main/java/com/gimle/core/protocol/ControlMessageCodec.java` (`encodePorts`/`decodePorts`), `gimle-agent/src/main/java/com/gimle/agent/SupervisedInstance.java` (`ports`), `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`observationJson`)
- **Test coverage**: `SimpleModuleContextTest` (report/retrieve, replace-on-re-report, multiple named ports, blank-name and out-of-range rejection, snapshot isolation); `WorkerRuntimeReportedPortsTest` (a real dynamically-built module calling `ctx.reportPort` from `onStart`, plus no-report and unknown-id cases); `ControlMessageCodecTest` (a `MetricsReport` round trip carrying a two-entry ports map); `AgentMainTest` (`observation_json_*` cases proving `instance.ports` folds into the heartbeat JSON); `AgentMetricsReportPortFoldingTest` (end-to-end over a real Unix socket: a `MetricsReport` with ports sent through `AgentMain.readLoop` updates `SupervisedInstance.ports` and folds into `observationJson`)
- **Gherkin scenario**:
  ```gherkin
  Given a hosted module's onStart hook calls ctx.reportPort(name, port), When the worker's next metrics report reaches its node agent, Then that instance's observation JSON carries the reported port under the same "ports" key shape a Vessel workload's allocatedPorts already uses.
  Given a module reports exactly one port, When ServiceEndpointResolver resolves a Service fronting that module's deployment, Then solePort() succeeds and a live endpoint is produced -- closing the gap where only Vessel instances could ever resolve.
  ```

### gimle-os

#### GIMLE-064 — Pluggable resource-limiter abstraction

- **Category**: Resource Limiting
- **User story**: As the platform, I want resource enforcement expressed behind one ResourceLimiter interface, so a future kernel-level implementation can drop in without any caller branching.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/ResourceLimiter.java`, `ResourceLimitHandle.java`
- **Test coverage**: exercised via `PortableJvmFlagsResourceLimiterTest`
- **Gherkin scenario**:
  ```gherkin
  Given a caller holding only a ResourceLimiter reference, When it calls supports/prepare/jvmFlags/release, Then behavior is identical regardless of concrete implementation.
  ```

#### GIMLE-065 — Portable JVM-flags resource enforcement (Tier 1/Tier 2)

- **Category**: Resource Limiting
- **User story**: As a platform operator running on any OS, I want module memory/CPU limits enforced via portable -Xmx/-XX:ActiveProcessorCount flags identically everywhere.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/portable/PortableJvmFlagsResourceLimiter.java`
- **Test coverage**: `PortableJvmFlagsResourceLimiterTest` (supports tier 1/2 not 3, prepare returns handle, jvm flags derive Xmx/ActiveProcessorCount, release no-op)
- **Gherkin scenario**:
  ```gherkin
  Given ResourceSpec{memory=512Mi, cpu=1500m}, When jvmFlags(handle) is called, Then returns ["-Xmx536870912","-XX:ActiveProcessorCount=2"] (rounded up).
  ```

#### GIMLE-066 — Tier 3 (namespace isolation) — deliberately unsupported by the current limiter

- **Category**: Resource Limiting
- **User story**: As the platform, I want the only current resource limiter to explicitly report it does not support TIER_3.
- **Status**: Partial — Tier 3 (FFM unshare/setns) unimplemented on any platform, deliberate deferral per CLAUDE.md
- **Confidence**: High
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/portable/PortableJvmFlagsResourceLimiter.java` (`supports`); rejection (GimleIsolationException.tierUnsupported) is invoked by callers outside this module (e.g. gimle-agent)
- **Test coverage**: `PortableJvmFlagsResourceLimiterTest#supports_tier_1_and_tier_2_but_not_tier_3`
- **Gherkin scenario**:
  ```gherkin
  Given PortableJvmFlagsResourceLimiter, When supports(IsolationTier.TIER_3) is called, Then returns false.
  ```

#### GIMLE-067 — Kernel-level (cgroup v2) resource enforcement — deferred

- **Category**: Resource Limiting
- **User story**: As a platform operator on Linux, I would want CPU/memory limits enforced at the kernel level via cgroup v2, catching runaway native allocations JVM flags alone can't.
- **Status**: Partial/Not Implemented — explicitly, deliberately deferred per ResourceLimiter's own javadoc and CLAUDE.md, not a discovered gap
- **Confidence**: High
- **Source location(s)**: N/A — no cgroup-writing code exists anywhere in gimle-os; only PortableJvmFlagsResourceLimiter exists
- **Test coverage**: NONE (nothing to test)
- **Gherkin scenario**:
  ```gherkin
  N/A — not implemented
  ```

#### GIMLE-068 — Pluggable persistent-volume-manager abstraction

- **Category**: Storage
- **User story**: As the platform, I want persistent-volume allocation expressed behind one VolumeManager interface, structurally parallel to ResourceLimiter.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/VolumeManager.java`, `VolumeHandle.java`
- **Test coverage**: exercised via `LocalDiskVolumeManagerTest`
- **Gherkin scenario**:
  ```gherkin
  Given a caller holding only a VolumeManager reference, When it calls allocate/hostPath/release, Then behavior is identical regardless of concrete backend.
  ```

#### GIMLE-069 — Local-disk persistent volume allocation for StatefulSet-shaped instances

- **Category**: Storage
- **User story**: As a platform operator running a StatefulSet-shaped module, I want each (statefulSetName, instanceIndex) pair allocated its own sticky local-disk directory, checked against free disk space.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/localdisk/LocalDiskVolumeManager.java`
- **Test coverage**: `LocalDiskVolumeManagerTest` (creates keyed directory, idempotent for same index, distinct dirs per index/statefulset, throws when exceeding usable space, release deletes contents, release of never-allocated is no-op)
- **Gherkin scenario**:
  ```gherkin
  Given a volume request exceeding the target filesystem's usable space, When LocalDiskVolumeManager.allocate is called, Then GimleVolumeException reporting insufficient space; allocating twice for the same index is idempotent.
  ```

### gimle-pki

#### GIMLE-070 — Self-signed cluster CA generation

- **Category**: PKI
- **User story**: As a cluster operator bootstrapping a brand-new cluster in TLS mode, I want a fresh, self-signed CA with correct BasicConstraints/KeyUsage extensions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/CertificateAuthority.java`
- **Test coverage**: `CertificateAuthorityTest` (generated_ca_is_self_signed_and_marked_as_a_ca, generated_ca_can_be_loaded_back_via_of)
- **Gherkin scenario**:
  ```gherkin
  Given CertificateAuthority.generateSelfSignedCa(subject, validity), When inspected, Then self-signed, CA=true, carries critical keyCertSign/cRLSign key usage.
  ```

#### GIMLE-071 — CSR-to-leaf-certificate signing with signature verification

- **Category**: PKI
- **User story**: As the cluster CA, I want to sign a CSR only after verifying its own signature matches its declared public key.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/CertificateAuthority.java`
- **Test coverage**: `CertificateAuthorityTest` (signed leaf chains to CA, signing rejects bad self-signature, leaf doesn't verify against unrelated CA)
- **Gherkin scenario**:
  ```gherkin
  Given a CSR whose signature doesn't match its own public key, When signCertificateRequest is called, Then throws rather than issuing a certificate.
  ```

#### GIMLE-072 — Server-stamped Subject override on signing (prevents self-declared privileged group)

- **Category**: PKI / Security
- **User story**: As the control plane approving a CSR, I want to sign the issued certificate's Subject as a server-computed value rather than the requester's own CSR.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/CertificateAuthority.java`
- **Test coverage**: `CertificateAuthorityTest` (subject_override_wins, still rejects bad self-signature)
- **Gherkin scenario**:
  ```gherkin
  Given a CSR whose own Subject requests O=gimle:operators but the caller only has node-join authorization, When signed via the subject-override overload, Then the issued certificate's Subject is exactly the override; a bad self-signature is still rejected.
  ```

#### GIMLE-073 — CSR generation with Subject Alternative Names

- **Category**: PKI
- **User story**: As a component requesting its own leaf certificate, I want to include DNS SANs in my CSR, so real HTTPS clients performing hostname verification accept the connection.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/CertificateSigningRequests.java`
- **Test coverage**: `CertificateSigningRequestsTest`; SAN propagation covered by `CertificateAuthorityTest#signed_leaf_certificate_carries_requested_subject_alternative_names`
- **Gherkin scenario**:
  ```gherkin
  Given a CSR built with dnsNames=["node1.local","localhost"], When verified with its own public key, Then succeeds, and the SAN extension request is present.
  ```

#### GIMLE-074 — Hand-rolled PEM encode/decode for certs, CSRs, and private keys

- **Category**: PKI / Internal-Infra
- **User story**: As the platform, I want one shared PEM encode/decode utility rather than four call sites each reimplementing base64 wrap/unwrap.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/Pem.java`
- **Test coverage**: exercised indirectly throughout CertificateAuthorityTest (`generated_leaf_certificate_is_readable_by_openssl`, `certificate_survives_a_keystore_round_trip`); NONE dedicated PemTest
- **Gherkin scenario**:
  ```gherkin
  Given a generated leaf certificate, When encoded via Pem.encodeCertificate then re-loaded by openssl, Then readable as a valid X.509 certificate.
  ```

#### GIMLE-075 — Randomized certificate-renewal scheduling (anti-thundering-herd)

- **Category**: PKI
- **User story**: As a cluster operator, I want each certificate's renewal point randomized within the last 20–30% of validity, so many nodes provisioned at once don't all renew simultaneously.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/RenewalSchedule.java`
- **Test coverage**: NONE dedicated found
- **Gherkin scenario**:
  ```gherkin
  Given a certificate's validity window, When RenewalSchedule.of(certificate) is called, Then renewAt falls within the last 20–30% of validity.
  ```

#### GIMLE-076 — Own-certificate rotation over mTLS via CSR bootstrap endpoint

- **Category**: PKI
- **User story**: As a long-running component whose leaf cert is approaching expiry, I want to automatically generate a fresh keypair, submit a rotation CSR over my existing mTLS connection, and persist the new cert.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/OwnCertificateRotator.java`
- **Test coverage**: NONE dedicated in this module's test tree
- **Gherkin scenario**:
  ```gherkin
  Given a component's currently-loaded cert is past renewal, When checkAndRotateIfDue(settings, csrEndpoint) is called, Then submits a NODE_CLIENT CSR, writes new key then new cert, returns a new HttpClient with rotated=true; plaintext mode or nothing due is a no-op.
  ```

#### GIMLE-077 — X.500 Subject utilities: server-side O= stamping and Principal derivation

- **Category**: PKI / Security
- **User story**: As the control plane (or Fafnir), I want to rebuild a CSR's Subject with a server-computed O= while preserving CN=, and derive a Principal from an issued certificate's Subject.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/Subjects.java`
- **Test coverage**: `SubjectsTest` (replaces existing organization, adds organization to one with none, rejects subject with no common name)
- **Gherkin scenario**:
  ```gherkin
  Given a Subject with CN=node-1 and no O=, When Subjects.withOrganization(subject,"gimle:nodes") is called, Then result is O=gimle:nodes,CN=node-1.
  ```

#### GIMLE-078 — Cluster PKI bootstrap CLI (`mvn gimle:tls-init`)

- **Category**: PKI / Internal-Infra
- **User story**: As a cluster operator standing up a brand-new TLS-mode cluster, I want one command generating the cluster CA plus leaf certificates for the control plane, Fafnir, Muninn, Andvari, and the first human operator, plus a bootstrap console account.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/PkiBootstrapMain.java`
- **Test coverage**: NONE direct (constituent pieces — CertificateAuthority, CertificateSigningRequests, PasswordHashes, Pem — each independently tested)
- **Gherkin scenario**:
  ```gherkin
  Given an empty output directory, When PkiBootstrapMain.main(["outDir","MyClusterCA","localhost"]) runs, Then outDir contains ca.crt/.key, controlplane/fafnir/muninn/andvari/operator .crt/.key, and bootstrap-account.yaml with only a username and password hash.
  ```

### gimle-worker

#### GIMLE-079 — Worker JVM control-channel bootstrap

- **Category**: Worker Supervision / Internal-Infra
- **User story**: As the platform, I want a freshly-spawned worker JVM to connect out to its agent's control socket and treat every subsequent module operation as arriving over that channel (including its very first), so that a worker's startup and a mid-redeploy scenario are handled by identical code paths.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`main`), `ControlChannelClient.connectWithRetry`
- **Test coverage**: `ControlChannelClientTest#connect_with_retry_succeeds_once_the_listener_is_up`, `#connect_with_retry_gives_up_after_its_timeout_if_nothing_ever_listens`, `AgentWorkerIntegrationTest#agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel` (gimle-agent)
- **Gherkin scenario**:
  ```gherkin
  Given a worker JVM is spawned with <nodeId> <tenantId-or-empty> <control-socket-path> as arguments
  When WorkerMain.main starts
  Then it connects out to the agent's UDS control socket, retrying until the listener is up
  And it sends a Hello message carrying its workerId, pid, and fabric UDS/TCP endpoints
  And it then loops reading ControlMessages until the channel closes
  ```

#### GIMLE-080 — Newline-delimited control-channel wire protocol (worker side)

- **Category**: Internal-Infra
- **User story**: As the platform, I want a simple, synchronized, newline-framed message codec between worker and agent, so that concurrent senders (metrics reporter, Muninn relay loop, fabric registry, relay callers) never corrupt the wire.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/ControlChannelClient.java`
- **Test coverage**: `ControlChannelClientTest#a_sent_message_is_received_intact_on_the_other_end`, `#receive_returns_empty_once_the_peer_closes_the_connection`
- **Gherkin scenario**:
  ```gherkin
  Given multiple background threads call ControlChannelClient.send concurrently
  When two messages are sent at nearly the same time
  Then send() is synchronized so no interleaved/corrupted line is ever written
  And receive() returns Optional.empty() once the peer closes the connection
  ```

#### GIMLE-081 — Module install/resolve/start/stop/uninstall command dispatch

- **Category**: Module System
- **User story**: As the node agent, I want to drive a worker's module lifecycle (InstallModule/ResolveModule/StartModule/StopModule/UninstallModule) over the control channel with Ack/Nack correlation, so that the control plane's desired state is reliably reflected in a running module instance.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`handle`, `runCommand`)
- **Test coverage**: NONE at the gimle-worker unit level (exercised end-to-end via `gimle-agent`'s `AgentWorkerIntegrationTest`, `RelayControlPlaneEndToEndTest`, `Tier1DensityIntegrationTest`)
- **Gherkin scenario**:
  ```gherkin
  Given a worker receives ControlMessage.InstallModule with a valid artifact path
  When the worker reads and registers the artifact
  Then it replies with ModuleStateChanged(INSTALLED) followed by an Ack carrying the same correlationId
  Given the artifact cannot be read
  Then the worker replies with a Nack carrying the exception message instead
  ```

#### GIMLE-082 — Instance identity registration and rename-in-place

- **Category**: Module System
- **User story**: As the control plane, I want a module instance's deployment name/index/tenant identity to be registrable and retargetable without restarting the module, so that a rename-only reconciliation (e.g. surge-instance promotion) never disturbs a running process.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/InstanceIdentity.java`, `InstanceIdentityRegistry.java`, `WorkerMain.java` (`RenameInstance` case)
- **Test coverage**: NONE directly in gimle-worker (agent-side `AgentMainTest#rename_in_place_notifies_the_connected_worker_of_its_new_identity` exercises the message send; no worker-side unit test of the handler itself)
- **Gherkin scenario**:
  ```gherkin
  Given a module is already ACTIVE with a registered InstanceIdentity
  When the worker receives ControlMessage.RenameInstance with a new deploymentName/instanceIndex
  Then InstanceIdentityRegistry is overwritten in place with no resolve/start/stop calls
  And subsequent log lines and probe MDC tags reflect the new identity immediately
  ```

#### GIMLE-083 — Per-instance MDC log tagging for lifecycle/hook/probe/request-dispatch logging

- **Category**: Observability / Internal-Infra
- **User story**: As an operator, I want every synchronous log line a module's own lifecycle hook, probe, or service call produces to be tagged with its owning instance's deployment name/index/tenant, so that logs land in that instance's own APPLICATION log file rather than the shared platform log.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`mdcTagsFor`, `runCommand`), `InstanceTaggingServiceRegistry.java`, `BoundedModuleScheduler.java` (probe-tick tagging)
- **Test coverage**: `BoundedModuleSchedulerTest#mdc_tags_are_visible_inside_a_tagged_submission`, `#empty_mdc_tags_leave_the_submission_untagged`; `InstanceTaggingServiceRegistryTest#registers_untagged_when_no_identity_is_known_for_the_owner`, `#registers_a_tagging_proxy_when_identity_is_known`
- **Gherkin scenario**:
  ```gherkin
  Given a module has a registered InstanceIdentity
  When ModuleController invokes its onInstall/onStart/onStop/onUninstall hooks via runCommand
  Then InstanceMdcContext.runTagged wraps the call with that instance's MDC tags
  Given no identity is registered yet
  Then mdcTagsFor returns an empty map and such lines fall back to PLATFORM
  ```

#### GIMLE-084 — Durable InstanceEvent emission per lifecycle transition

- **Category**: Observability
- **User story**: As an operator, I want every module lifecycle transition (INSTALLED/RESOLVED/STARTING/ACTIVE/STOPPING/UNINSTALLED/FAILED/COMPLETED) to produce a durable, timestamped InstanceEvent with a stable UUID, so that the console's events panel and `gimle-cli events` have a queryable history independent of storage order.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`handleLifecycleEvent`, `instanceEventFor`)
- **Test coverage**: NONE at the gimle-worker unit level; indirectly exercised via `GreeterClusterTopologyIT` (gimle-smoke-tests, out of scope here)
- **Gherkin scenario**:
  ```gherkin
  Given a module with a registered InstanceIdentity transitions from ACTIVE to STOPPING
  When the lifecycle sink handles the LifecycleEvent.Stopping event
  Then it builds an InstanceEvent with kind STOPPING, a message including the drain deadline, and a fresh UUID
  And sends it to the agent as ControlMessage.InstanceEventOccurred
  Given no InstanceIdentity is registered for the module
  Then no InstanceEvent is built or sent (nowhere durable to attach it to)
  ```

#### GIMLE-085 — Classloader leak detection on undeploy

- **Category**: Module System
- **User story**: As a platform operator, I want a disposed module's classloader to be watched via a PhantomReference after undeploy, so that a genuine leak is reported (with retaining path where attributable) within a bounded window rather than silently exhausting metaspace.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`buildControllerAndRuntime`, `LeakTracker` wiring); actual detection logic lives in `gimle-module`
- **Test coverage**: NONE in gimle-worker itself (leak-detection mechanics tested in `gimle-module`); wiring only
- **Gherkin scenario**:
  ```gherkin
  Given a module is uninstalled and its ModuleLayer's loader is disposed
  When the loader survives past LEAK_DETECTION_WINDOW (30s)
  Then LeakTracker calls back with a warning logging the module id, survival time, and retaining path if known
  Given the loader is collected within the window
  Then no leak is reported
  ```

#### GIMLE-086 — Per-module bounded virtual-thread scheduler

- **Category**: Worker Supervision
- **User story**: As the platform, I want each ACTIVE module to get its own concurrency-bounded, virtual-thread-per-task scheduler, so that a hung or overloaded module can't starve other modules sharing the same worker JVM and its queue depth is directly observable.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/BoundedModuleScheduler.java`
- **Test coverage**: `BoundedModuleSchedulerTest#concurrency_bound_limits_how_many_tasks_run_at_once`, `#closed_scheduler_rejects_further_submissions`, `#max_concurrency_below_one_is_rejected`, `#submitted_task_runs_and_returns_its_result`, `#a_thrown_exception_surfaces_through_the_future`
- **Gherkin scenario**:
  ```gherkin
  Given a module goes ACTIVE with defaultMaxConcurrency = 4
  When 4 tasks are already running and a 5th is submitted
  Then the 5th blocks behind a Semaphore permit until one of the first 4 completes
  And queuedCount() reports an estimate of tasks waiting for a permit
  Given the scheduler is closed
  Then further submissions are rejected
  ```

#### GIMLE-087 — OpenTelemetry context propagation across virtual-thread dispatch

- **Category**: Observability
- **User story**: As the platform, I want a span started before dispatching work onto a module's scheduler to remain the parent of any span the dispatched task itself starts, so that cross-thread (virtual-thread-per-task) tracing stays correctly parented.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/BoundedModuleScheduler.java` (`submit`)
- **Test coverage**: `BoundedModuleSchedulerTest#the_callers_ambient_context_is_restored_inside_the_submitted_task`, `#a_submission_made_outside_any_context_scope_sees_no_value_for_that_key`
- **Gherkin scenario**:
  ```gherkin
  Given a caller has an active OpenTelemetry Context with a value bound
  When it submits a task to BoundedModuleScheduler
  Then Context.current().wrap(task) captures that context and restores it on the fresh virtual thread for the task's duration
  Given the submission happens outside any context scope
  Then the task sees no value for that key
  ```

#### GIMLE-088 — Liveness/readiness probe loop with timeout and initial-delay

- **Category**: Health / Self-Healing
- **User story**: As the platform, I want a module's declared liveness/readiness probes invoked on their own module's concurrency budget, with a hard per-check timeout and a configurable initial-delay before the first tick, so that a hung probe never blocks a shared platform thread and a module gets a post-start warmup window.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/ProbeLoop.java`, `WorkerRuntime.java` (`onActive` probe wiring)
- **Test coverage**: `ProbeLoopTest#a_passing_check_reports_true_repeatedly`, `#a_failing_check_reports_false`, `#a_check_that_throws_is_reported_as_a_failure_not_propagated`, `#a_check_that_hangs_past_its_timeout_is_reported_as_a_failure`, `#no_tick_fires_before_the_initial_delay_elapses`, `#after_the_initial_delay_ticks_settle_onto_the_ordinary_interval`, `#stop_halts_further_invocations_of_that_key`, `#two_keys_are_scheduled_independently`, `#the_production_constructor_still_schedules_on_a_real_ticker`
- **Gherkin scenario**:
  ```gherkin
  Given a module declares health.liveness/readiness classes and an initialDelaySeconds
  When ProbeLoop.start schedules the check
  Then no tick fires before initialDelay elapses, and subsequent ticks settle onto the ordinary interval
  And a check that hangs past its timeout, or throws, is reported as a failed check, never propagated
  ```

#### GIMLE-089 — Module-tier self-healing — restart on repeated liveness failure with backoff and budget exhaustion

- **Category**: Worker Supervision / Self-Healing
- **User story**: As an operator, I want a module that fails its liveness probe a threshold number of times in a row to be disposed and reinstantiated automatically (with exponential backoff), and — once its restart budget is exhausted — escalated to FAILED so the worker/machine-tier self-healing chain can take over, so that a genuinely broken module doesn't loop forever without ever surfacing as needing intervention.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`onLivenessResult`, `restartModule`, `scheduleModuleStabilityConfirmation`, `newRestartTracker`)
- **Test coverage**: `WorkerRuntimeTest#repeated_liveness_failures_restart_the_module_and_it_stays_registered_and_active`, `#a_module_that_never_recovers_liveness_exhausts_its_restart_budget_and_is_marked_failed`, `#a_module_that_recovers_before_failing_again_gets_a_fresh_restart_budget`
- **Gherkin scenario**:
  ```gherkin
  Given a module's consecutive liveness failures reach livenessFailureThreshold (3)
  When restartModule() runs
  Then it stops (drains + uninstalls), re-registers the artifact, resolves, and starts it again
  And only one restart is ever in flight per module at a time
  Given the module recovers and stays stable past stableUptimeThreshold (10s)
  Then RestartTracker.recordSuccess() resets the backoff budget for the next failure cycle
  Given the module never recovers and the restart budget (5 attempts / 60s window) is exhausted
  Then controller.forceFailed(id, "restart budget exhausted") is called, escalating to FAILED
  And onModuleRestartBudgetExhausted is invoked, logging that the worker itself needs restarting
  ```

#### GIMLE-090 — Readiness-driven service registry availability (without restart)

- **Category**: Health / Fabric
- **User story**: As a service consumer, I want a module whose readiness probe fails to be marked unready in the registry (unreachable) without a restart, and to become reachable again automatically once readiness recovers, so that transient not-ready windows don't trigger unnecessary module churn.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`onReadinessResult`)
- **Test coverage**: `WorkerRuntimeTest#a_readiness_failure_marks_the_service_unready_without_stopping_the_module`, `#a_module_becomes_lookupable_again_when_its_readiness_probe_recovers`
- **Gherkin scenario**:
  ```gherkin
  Given an ACTIVE module's readiness probe reports false
  When onReadinessResult(id, false) runs
  Then serviceRegistry.markUnready(id) is called, and the module stays ACTIVE (no restart)
  Given readiness later reports true
  Then serviceRegistry.markReady(id) makes the module lookupable again
  ```

#### GIMLE-091 — Stopping/Uninstalled teardown of scheduler, probes, and service registry

- **Category**: Worker Supervision / Module System
- **User story**: As the platform, I want a module's probe loop stopped and its service marked unready as soon as it enters STOPPING, and its scheduler/restart-tracking state fully removed once UNINSTALLED, so that no stale probe tick or reachable-but-draining service call slips through during teardown.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`onStopping`, `onUninstalled`)
- **Test coverage**: `WorkerRuntimeTest#stopping_a_module_makes_its_service_unreachable_and_removes_it_from_the_registry`, `#on_uninstalled_fires_the_close_callback_exactly_once_with_the_registered_identity`
- **Gherkin scenario**:
  ```gherkin
  Given a module transitions to STOPPING
  When onStopping(id) runs
  Then both liveness and readiness probe keys are cancelled and the service is marked unready
  Given the module then reaches UNINSTALLED
  Then its BoundedModuleScheduler is closed, restart trackers/failure counters are removed, and serviceRegistry.remove(id) is called
  And the InstanceIdentity is looked up (for the caller's close-log callback) before removal clears it
  ```

#### GIMLE-092 — Job-kind module execution (run-to-completion, not probed)

- **Category**: Module System
- **User story**: As a module author, I want a Job-kind module (declaring `lifecycle.jobHooks` instead of health probes) to run its unit of work exactly once on its own virtual thread and reach COMPLETED or FAILED based on its result, so that a long-running or blocking job never ties up a probe-loop or control-channel thread.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`runJobHooks`)
- **Test coverage**: `JobHooksExecutionTest#a_succeeding_job_runs_its_hooks_and_reaches_completed`, `#a_failing_job_reaches_failed`, `#a_job_hooks_run_that_throws_is_treated_as_failed`
- **Gherkin scenario**:
  ```gherkin
  Given a module descriptor declares a jobHooksClass and goes ACTIVE
  When WorkerRuntime.onActive runs runJobHooks
  Then JobHooks.run(ctx) executes on a dedicated virtual thread
  And its returned CompletionStatus (or FAILED, if it threw) drives controller.complete(id, status)
  Given the module already left ACTIVE some other way before completion posts
  Then the failure to complete is logged and swallowed (best-effort)
  ```

#### GIMLE-093 — Fabric service registration, cross-worker/cross-machine invocation binding

- **Category**: Fabric
- **User story**: As a hosted module, I want my exported services to be reachable from other modules in the same worker, other workers on this machine (UDS), and other nodes (TCP) without my code caring which, so that same-worker calls stay direct in-JVM invocations while remote calls go through a compact binary codec.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`buildFabricRegistry`, `bindFabricServer`), `InstanceTaggingServiceRegistry.java`
- **Test coverage**: NONE in gimle-worker directly (exercised end-to-end by `FabricCrossProcessIntegrationTest`, `RelayControlPlaneEndToEndTest` in gimle-agent)
- **Gherkin scenario**:
  ```gherkin
  Given a worker boots and binds one UDS listener plus one TCP listener via FabricServer
  When a module registers a service through the tagged local registry
  Then it becomes reachable via ServiceCatalog delta relay to the agent, and other workers can call it via UDS/TCP
  And FabricServer routes inbound calls through the target module's own ModuleContext and BoundedModuleScheduler (real concurrency bound, not just probe checks)
  ```

#### GIMLE-094 — Fabric TLS certificate rotation detection (mtime polling)

- **Category**: Internal-Infra / Fabric
- **User story**: As the platform, I want a worker's FabricServer to detect that its agent-managed certificate file was rotated on disk and reload its TLS material automatically, so that a rotated cert takes effect without a worker restart.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/FabricServerTlsWatcher.java`
- **Test coverage**: `FabricServerTlsWatcherTest#detects_a_rotated_certificate_file_and_reloads_the_fabric_server`
- **Gherkin scenario**:
  ```gherkin
  Given the fabric server is running under TLS and FabricServerTlsWatcher is started
  When the certificate file's mtime changes between polls
  Then server.reloadTlsMaterial() is called and the new mtime is recorded
  Given the mtime is unchanged on a tick
  Then nothing is reloaded
  Given transport is PLAINTEXT
  Then the watcher's ticker never even starts
  ```

#### GIMLE-095 — Control-plane read relay for hosted modules (RelayControlPlaneRead/Result round trip)

- **Category**: Fabric / Internal-Infra
- **User story**: As a hosted module, I want a synchronous ModuleContext call that needs to read from the control plane (e.g. `GET /endpoints/{name}`) to work even though only WorkerMain's own receive loop can read the control channel, so that arbitrary module/hook threads can make such a call without owning the channel themselves.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/ControlPlaneRelay.java`
- **Test coverage**: `ControlPlaneRelayTest#a_matching_response_completes_the_waiting_caller_and_leaves_no_pending_entry`, `#no_response_times_out_and_still_leaves_no_pending_entry`, `#a_late_response_after_the_caller_already_gave_up_is_dropped_without_error`
- **Gherkin scenario**:
  ```gherkin
  Given a module thread calls ControlPlaneRelay.request(path)
  When it registers a CompletableFuture keyed by a fresh correlationId and sends RelayControlPlaneRead
  Then it blocks until a matching RelayControlPlaneResult arrives or 5s times out
  Given no response ever arrives
  Then it returns a synthesized 504 RelayResult and the pending entry is removed either way
  Given a response arrives after the caller already gave up
  Then it is logged and dropped without error
  ```

#### GIMLE-096 — Worker-side trace relay to agent (no direct Muninn shipping)

- **Category**: Observability / Internal-Infra
- **User story**: As the platform, I want a worker JVM's exported OpenTelemetry span batches shipped to its agent over the existing control channel (since a worker has no outbound network identity of its own), so that traces still reach Muninn without giving every worker its own network egress.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/RelayingSpanExporter.java`
- **Test coverage**: `RelayingSpanExporterTest#a_real_span_batch_relays_as_a_traces_snapshot_with_the_given_worker_id`, `#export_never_throws_even_when_the_sink_throws`, `#flush_and_shutdown_always_report_success`
- **Gherkin scenario**:
  ```gherkin
  Given GimleTracing is installed with a RelayingSpanExporter
  When a span batch is exported
  Then SpanLineCodec.toNdjson encodes it and sends ControlMessage.TracesSnapshot over the control channel
  And export() never surfaces a relay failure to the OpenTelemetry SDK (always CompletableResultCode.ofSuccess)
  ```

#### GIMLE-097 — Per-module CPU/memory/request-rate/error-rate metrics reporting (portable, no cgroup)

- **Category**: Observability / Cgroup Management
- **User story**: As the autoscaler, I want each worker to self-report its own JVM-wide CPU/heap usage plus per-module request/error rate and queue depth every 5 seconds via portable `java.lang.management` APIs, so that AutoscaleReconciler's CPU-utilization math has real data instead of always seeing zero.
- **Status**: Partial — deliberately portable-only; explicitly documented as not reading cgroup data (`gimle-os`'s kernel-level `ResourceLimiter` remains deferred per CLAUDE.md/`ResourceLimiter` javadoc)
- **Confidence**: Medium
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`metricsReportLoop`, `rateSince`)
- **Test coverage**: NONE at unit level in gimle-worker (portable-by-design, matching `PortableJvmFlagsResourceLimiter`'s own bar — see gimle-os for the sibling implementation's own tests)
- **Gherkin scenario**:
  ```gherkin
  Given a worker has one or more ACTIVE modules
  When METRICS_REPORT_INTERVAL (5s) elapses
  Then it reads OperatingSystemMXBean.getProcessCpuLoad() and Runtime heap usage (no cgroup reads, no FFM)
  And computes a per-module request/error rate as a diff against the previous tick (0 on a module's first tick)
  And sends ControlMessage.MetricsReport per active module, including queue depth from that module's own scheduler
  ```

#### GIMLE-098 — Worker-wide meter snapshot relay to Muninn (via agent)

- **Category**: Observability / Internal-Infra
- **User story**: As the platform, I want a worker's whole Micrometer registry shipped as one NDJSON snapshot per tick to the agent (which relays to Muninn), plus a best-effort extra snapshot on every StopModule, so that a short-lived Job instance's metrics aren't lost to a tick that never fires before the process exits.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`muninnMetricsRelayLoop`, `StopModule` case)
- **Test coverage**: NONE in gimle-worker directly; exercised via `AgentMuninnShippingTest` (gimle-agent)
- **Gherkin scenario**:
  ```gherkin
  Given MUNINN_SHIP_INTERVAL (5s) elapses
  When muninnMetricsRelayLoop runs
  Then MeterSnapshotCodec.toNdjson(workerMetrics.registry()) is sent as ControlMessage.MetricsSnapshot, skipped if empty
  Given a StopModule command completes
  Then one extra best-effort snapshot is sent immediately, regardless of whether this is the worker's last instance
  ```

#### GIMLE-099 — `module-info.java` platform-layer/observability/fabric wiring for the worker module

- **Category**: Internal-Infra
- **User story**: As a maintainer, I want `com.gimle.worker`'s JPMS declaration to require exactly the modules its runtime behavior needs (core, module, observability, fabric, OTel context, JDK management/jdk.management for CPU-load reads, SLF4J), so that module boundaries stay explicit and `com.sun.management.OperatingSystemMXBean` access is intentional, not incidental.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/main/java/module-info.java`
- **Test coverage**: NONE (compile-time enforcement only)
- **Gherkin scenario**:
  ```gherkin
  Given gimle-worker's module-info.java
  When it is compiled
  Then it requires com.gimle.core, com.gimle.module, com.gimle.observability, com.gimle.fabric, io.opentelemetry.context, java.management, jdk.management, org.slf4j
  And exports only com.gimle.worker
  ```

#### GIMLE-100 — Real bundled-hook/probe classloading against the platform layer

- **Category**: Module System / Internal-Infra
- **User story**: As a module author bundling my own `ModuleLifecycleHooks`/`LivenessProbe`/`ReadinessProbe` implementations inside my module's jar (not on a test classpath), I want them to resolve the platform's own hook/probe interfaces at runtime, so that a hosted module's own classes can implement platform contracts without a classpath shortcut.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-worker/src/test/java/com/gimle/worker/RealBundledHookAndProbeInvocationTest.java` (test validates production wiring in `gimle-module`'s `ModuleLayerFactory`)
- **Test coverage**: `RealBundledHookAndProbeInvocationTest#bundled_hooks_and_probes_load_and_cast_against_this_jvms_own_platform_types`, `#bundled_probes_instantiate_and_cast_cleanly`
- **Gherkin scenario**:
  ```gherkin
  Given a real module jar bundles its own LivenessProbe/ReadinessProbe/ModuleLifecycleHooks implementation classes
  When the worker loads the module's ModuleLayer and instantiates those classes
  Then they cast cleanly against this JVM's own platform interface types, thanks to explicit Module.addReads granted by ModuleLayerFactory
  ```

### gimle-agent

#### GIMLE-101 — Node agent registration and repeating reconcile/heartbeat/rotate tick loop

- **Category**: Worker Supervision / Internal-Infra
- **User story**: As the control plane, I want each node agent to register itself once, then repeatedly (every 5s) reconcile its locally-supervised worker set against its fetched assignments, send a heartbeat, and check for due certificate rotation, so that node state stays convergent without a push channel.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`main`, `register`, `reconcileAssignments`, `sendHeartbeat`)
- **Test coverage**: `AgentWorkerIntegrationTest#agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel`, `ControlPlaneAgentWorkerIntegrationTest#control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed`
- **Gherkin scenario**:
  ```gherkin
  Given an agent starts with nodeId/controlPlaneBaseUrl/gossip config
  When main() runs
  Then it registers via POST /nodes/{nodeId}/register, then loops forever: reconcileAssignments, sendHeartbeat, rotateCertificateIfDue, sleep(TICK_INTERVAL)
  And a tick that throws is caught, logged, and recorded as a failed tick in AgentMetrics without stopping the loop
  ```

#### GIMLE-102 — Worker JVM process spawn and command-line construction

- **Category**: Worker Supervision
- **User story**: As the platform, I want the agent to build a fully-formed worker JVM command line (leak-detection JFR flag, `-XX:+ExitOnOutOfMemoryError`, scoped log root, `-XX:ErrorFile`, ResourceLimiter flags, banner suppression, forced JSON console logging) and spawn it via ProcessBuilder, so that every worker this agent supervises is consistently instrumented and crash-classifiable.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`buildWorkerCommand`, `prepareResourceLimit`, `startInstance`)
- **Test coverage**: `AgentMainTest#the_spawned_command_carries_the_manifests_limit_not_its_request`, `#the_spawned_command_always_carries_exit_on_out_of_memory_error`, `#the_spawned_command_always_suppresses_the_startup_banner`, `#the_spawned_command_always_forces_json_console_logging`, `#the_spawned_command_forwards_the_default_deny_cross_tenant_flag`, `#prepare_resource_limit_hands_the_limiter_the_descriptors_limit_not_its_request`
- **Gherkin scenario**:
  ```gherkin
  Given a module descriptor with a resource limit and a node id
  When AgentMain.buildWorkerCommand constructs the command
  Then it always includes -XX:+ExitOnOutOfMemoryError, -Dgimle.log.root scoped per-worker, -XX:ErrorFile scoped per-worker, -Dgimle.banner.enabled=false, -Dgimle.log.console=json
  And it uses the descriptor's LIMIT (not its request) for the resource limiter's -Xmx/-XX:ActiveProcessorCount flags
  ```

#### GIMLE-103 — Worker process crash detection, classification, and destroy-and-respawn

- **Category**: Worker Supervision / Self-Healing
- **User story**: As an operator, I want a worker JVM that exits unexpectedly to be classified (OOM via exit code 3, native crash via a fresh hs_err dump, or unknown) and automatically respawned with exponential backoff, escalating to "give up locally" once its restart budget is exhausted, so that worker-tier failures self-heal within sub-second latency without operator intervention.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/WorkerProcessSupervisor.java` (`onExit`, `classifyCrash`, `spawn`), `CrashInfo.java`
- **Test coverage**: `WorkerProcessSupervisorTest#backoff_delay_escalates_across_repeated_crashes_then_gives_up`, `#a_respawn_that_stays_up_past_the_stability_threshold_resets_the_backoff`, `#an_exit_with_a_fresh_crash_dump_is_classified_as_native_crash`, `#a_plain_exit_with_no_crash_dump_is_classified_as_unknown`; `SystemLogCaptureTest#system_log_capture_survives_a_respawn`
- **Gherkin scenario**:
  ```gherkin
  Given a worker process exits without a prior deliberate stop()
  When onExit() fires
  Then classifyCrash() determines OOM (exit code 3), NATIVE_CRASH (fresh hs_err_pid<pid>.log present), or UNKNOWN
  And onCrash callback fires with the CrashInfo before the respawn decision
  Given the restart budget is not exhausted
  Then it waits the tracker's computed delay, then respawns via spawn() and schedules a stability confirmation
  Given the budget IS exhausted
  Then onRestartBudgetExhausted fires and no further respawn is attempted
  ```

#### GIMLE-104 — Deliberate-stop suppression of crash-respawn

- **Category**: Worker Supervision
- **User story**: As the agent, I want a worker process torn down deliberately (`stop()`/`close()` via `destroyForcibly()`) to never trigger the crash-respawn path, so that a normal undeploy/reassignment doesn't fight against the supervisor trying to bring the worker back up.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/WorkerProcessSupervisor.java` (`stop`, `onExit`'s `closed` guard)
- **Test coverage**: Implicit in `WorkerProcessSupervisorTest` setup/teardown paths; no dedicated `@Test` name asserting this directly — NONE explicit
- **Gherkin scenario**:
  ```gherkin
  Given the supervisor's stop() sets closed=true and calls process.destroyForcibly()
  When the process's onExit() callback then fires
  Then it returns immediately without classifying a crash or scheduling a respawn
  ```

#### GIMLE-105 — Worker stdout draining, JSON-line de-duplication, and raw SYSTEM-line capture

- **Category**: Observability / Internal-Infra
- **User story**: As an operator, I want a worker's raw stdout piped (never inherited, to avoid corrupting a Surefire-forked test process's own stdout protocol) and drained on a dedicated thread, with already-JSON-structured lines skipped (already captured by Logback) and non-JSON lines captured verbatim as a `category: SYSTEM` log entry, so that pre-Logback startup output or a stray `System.out.println` isn't lost.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/WorkerProcessSupervisor.java` (`drainOutput`, `isJsonLine`, `captureSystemLine`)
- **Test coverage**: `SystemLogCaptureTest#system_log_capture_survives_a_respawn`
- **Gherkin scenario**:
  ```gherkin
  Given a worker process writes a raw (non-JSON) line to stdout before Logback initializes
  When drainOutput reads it
  Then it is logged via this agent's own logger and appended to systemLogFile as a JSON entry tagged SYSTEM
  Given the worker writes an already-JSON-encoded line
  Then it is skipped (not re-logged), since PlatformFileAppender already captured it structurally
  ```

#### GIMLE-106 — Machine-level capacity tracking and admission (memory/CPU)

- **Category**: Worker Supervision / Config
- **User story**: As the scheduler, I want the agent to track total vs. assigned memory/CPU per machine and reject an assignment that would exceed capacity, so that placement math has a real local backstop against overcommitting a node.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/CapacityTracker.java`
- **Test coverage**: `CapacityTrackerTest#try_assign_succeeds_within_capacity_and_is_reflected_in_the_snapshot`, `#try_assign_fails_once_it_would_exceed_total_capacity`, `#try_assign_rejects_a_key_already_holding_a_reservation`, `#release_frees_the_reservation_for_reuse`, `#rekey_moves_the_reservation_to_the_new_key_without_changing_total_usage`, `#rekey_is_a_noop_when_the_old_key_holds_no_reservation`
- **Gherkin scenario**:
  ```gherkin
  Given a machine's total memory/CPU capacity and a set of currently-assigned worker keys
  When tryAssign(key, limit) is called and would exceed total capacity
  Then it returns false and the reservation is not recorded
  Given release(key) or rekey(oldKey, newKey) is called
  Then the reservation is freed or moved without double-counting or leaking
  ```

#### GIMLE-107 — Portable JVM-flags resource limiting (Tier 1/2), cgroup enforcement deliberately deferred

- **Category**: Cgroup Management
- **User story**: As the platform, I want a worker's `-Xmx`/`-XX:ActiveProcessorCount` computed from a module's resource LIMIT and applied at spawn time, identically on Linux/macOS/Windows, so that Tier 1/2 isolation has a guaranteed-minimum enforcement mechanism even before kernel-level enforcement exists.
- **Status**: Partial — kernel-level cgroup v2 enforcement is explicitly documented as deferred (see `ResourceLimiter`'s own javadoc: "a future kernel-level implementation drops in without touching a caller"); today only the portable JVM-flags path exists, and a runaway native allocation is not caught
- **Confidence**: High
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/portable/PortableJvmFlagsResourceLimiter.java`, `ResourceLimiter.java` (interface javadoc explicitly names a future kernel-level cgroup v2 implementation as not yet built); consumed by `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`prepareResourceLimit`, `buildWorkerCommand`, `buildVesselCommand`)
- **Test coverage**: `ResourceLimitEnforcementTest#a_spawned_jvm_honors_the_computed_memory_and_processor_ceiling` (gimle-agent, real subprocess); `PortableJvmFlagsResourceLimiterTest` (gimle-os)
- **Gherkin scenario**:
  ```gherkin
  Given a ModuleDescriptor's resourceLimit (memoryBytes, cpuMillicores)
  When PortableJvmFlagsResourceLimiter.prepare/jvmFlags are called
  Then it returns "-Xmx<bytes>" and "-XX:ActiveProcessorCount=<ceil(millicores/1000)>", with no cgroup file writes, no FFM downcalls
  And supports(tier) is true only for TIER_1/TIER_2 — TIER_3 is unsupported
  ```

#### GIMLE-108 — Tier 3 isolation rejection

- **Category**: Cgroup Management / Config
- **User story**: As the platform, I want an assignment that requires an isolation tier the current ResourceLimiter doesn't support (Tier 3, unimplemented on every platform) to fail loudly with `GimleIsolationException` rather than silently downgrading.
- **Status**: Partial — deliberately rejected outright, not implemented; FFM `unshare`/`setns` namespace isolation does not exist on any platform in this codebase.
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`reconcileAssignments`, `startVesselInstance` — both call `resourceLimiter.supports`/`GimleIsolationException.tierUnsupported`)
- **Test coverage**: NONE directly asserting Tier 3 rejection in gimle-agent tests found (PortableJvmFlagsResourceLimiter's own `supports()` behavior for TIER_3 is covered in `gimle-os`'s `PortableJvmFlagsResourceLimiterTest`, not re-asserted at the agent call site)
- **Gherkin scenario**:
  ```gherkin
  Given a module descriptor declares IsolationTier.TIER_3
  When the agent checks resourceLimiter.supports(descriptor.isolationTier()) before starting the instance
  Then it throws GimleIsolationException.tierUnsupported(moduleId, tier) and the instance never starts
  ```

#### GIMLE-109 — Assignment reconciliation loop (fetch, start, replace, stop)

- **Category**: Worker Supervision
- **User story**: As the control plane, I want the agent to poll `GET /nodes/{nodeId}/assignments` and reconcile its locally-supervised instance set to match — starting new ones, replacing ones whose moduleId/artifactPath changed, and stopping ones no longer assigned — every tick, so that node state converges to desired state even after missed events (level-triggered).
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`reconcileAssignments`, `requiresReplacement`, `stopInstance`)
- **Test coverage**: `AgentMainTest#a_module_id_change_at_the_same_key_requires_replacement`, `#an_artifact_path_change_with_the_same_module_id_requires_replacement`, `#an_unchanged_assignment_at_the_same_key_never_requires_replacement`; `ControlPlaneAgentWorkerIntegrationTest#control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed`
- **Gherkin scenario**:
  ```gherkin
  Given the control plane's current assignments for this node
  When reconcileAssignments runs
  Then every assignment not yet supervised is started (fresh worker, reused Tier-1 worker, or rename-in-place)
  And every already-supervised key whose moduleId/artifactPath changed is stopped then restarted
  And every supervised key no longer in the current assignment set is stopped with volume release
  ```

#### GIMLE-110 — Tier 1 density — shared-worker reuse for multiple module instances

- **Category**: Worker Supervision
- **User story**: As the platform, I want up to MAX_TIER1_DENSITY (4) Tier 1 instances of the same tenant, distinct modules, to be packed into one already-running worker JVM instead of always spawning a fresh one, so that classloader-level density is realized without a per-instance JVM cost.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`findReusableTier1Worker`, `installIntoExistingWorker`)
- **Test coverage**: `AgentMainTest#a_worker_already_hosting_the_same_module_is_never_reused_for_another_replica`, `#a_worker_at_the_density_cap_is_not_reused`, `#a_worker_with_no_established_connection_yet_is_never_reused`; `Tier1DensityIntegrationTest#two_modules_share_one_worker_process_and_survive_one_being_stopped`
- **Gherkin scenario**:
  ```gherkin
  Given an existing worker hosts only TIER_1 instances, all the same tenant, none running the incoming moduleId, and under the density cap
  When findReusableTier1Worker is consulted for a new TIER_1 assignment
  Then it returns that worker's representative instance for reuse
  Given the worker is at the density cap, already hosts the same moduleId, hosts a non-TIER_1 instance, or has no established connection yet
  Then it is never reused
  ```

#### GIMLE-111 — Instance rename-in-place (no restart)

- **Category**: Worker Supervision
- **User story**: As the control plane, I want a surge-instance promotion (a `renamedFromInstanceIndex` hint matching an already-supervised, already-matching key) to retarget the running instance's bookkeeping (supervised map, log shippers, capacity tracker, worker's own InstanceIdentityRegistry) without stopping/restarting it, so that a rename is genuinely free of process churn.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`findRenameSource`, `renameInPlace`)
- **Test coverage**: `AgentMainTest#find_rename_source_finds_the_already_supervised_instance_at_the_hinted_index`, `#find_rename_source_is_empty_without_a_rename_hint`, `#find_rename_source_falls_back_when_the_hinted_source_key_is_not_supervised`, `#find_rename_source_falls_back_when_the_source_runs_a_different_module`, `#rename_in_place_rekeys_supervised_and_shippers_and_updates_the_assigned_identity`, `#rename_in_place_notifies_the_connected_worker_of_its_new_identity`
- **Gherkin scenario**:
  ```gherkin
  Given an assignment carries a renamedFromInstanceIndex pointing at an already-supervised, matching-module instance
  When findRenameSource finds it and renameInPlace runs
  Then supervised/instanceShippers/capacityTracker are re-keyed and instance.assigned is updated in place
  And if the worker is already connected, ControlMessage.RenameInstance is sent so its own identity registry follows
  Given no matching source exists (already renamed, or gone)
  Then it falls through to the ordinary start path
  ```

#### GIMLE-112 — Worker respawn handshake re-drive after crash

- **Category**: Worker Supervision / Self-Healing
- **User story**: As the platform, I want every instance a crashed-and-respawned worker hosted to be reset to its pre-connection state and re-driven through the full InstallModule/ResolveModule/StartModule handshake as a group over the one freshly-accepted connection, so that a Tier 1-density worker's crash doesn't leave siblings stuck referencing a dead connection.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`onWorkerRespawned`, `resetForRespawn`)
- **Test coverage**: NONE with a dedicated unit test name found; exercised implicitly by `ControlPlaneAgentWorkerIntegrationTest` (crash/reschedule scenario) and `WorkerProcessSupervisorTest`'s respawn tests (which cover the supervisor half, not the agent-side redrive)
- **Gherkin scenario**:
  ```gherkin
  Given a worker process crashes and WorkerProcessSupervisor respawns it (same workerId, same control-socket path)
  When onWorkerRespawned fires
  Then every SupervisedInstance sharing that workerId is reset (resetForRespawn) except volumeHandle/assigned/supervisor/server/descriptor
  And a fresh connection is accepted, a reader loop started, and sendInstallStartSequence re-run for every hosted instance
  Given every instance the crashed worker hosted was already torn down before respawn
  Then nothing is redriven
  ```

#### GIMLE-113 — Worker-crash-to-durable-InstanceEvent relay

- **Category**: Observability / Self-Healing
- **User story**: As an operator, I want every instance a crashed worker hosted to get its own durable `TRANSITION_FAILED` InstanceEvent with a cause summary (OOM/native crash + hs_err path/unknown), so that the crash is visible in the timeline, not just a log line.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`onWorkerCrash`)
- **Test coverage**: NONE with a dedicated unit test found
- **Gherkin scenario**:
  ```gherkin
  Given a worker crashes and is classified via CrashInfo
  When onWorkerCrash runs
  Then for every SupervisedInstance sharing that workerId, a TRANSITION_FAILED InstanceEvent with a human-readable causeSummary is posted to the control plane
  ```

#### GIMLE-114 — Install-phase Nack escalates to FAILED (closing the "stuck at INSTALLED" gap)

- **Category**: Worker Supervision / Self-Healing
- **User story**: As an operator, I want an instance whose InstallModule handshake nacks (e.g. unreadable jar) to be marked FAILED rather than staying at INSTALLED forever, so that HealthReconciler's machine-tier reschedule can actually see and heal it.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`readLoop`, `ControlMessage.Nack` case)
- **Test coverage**: NONE with a dedicated unit test found (documented in code comment as a real fix for a previously-stuck-forever bug)
- **Gherkin scenario**:
  ```gherkin
  Given an instance's lifecycleState is still "INSTALLED"
  When a Nack for that instance arrives over the control channel
  Then lifecycleState is set to "FAILED"
  Given the instance already progressed past INSTALLED (a later nack)
  Then its last real lifecycle state is preserved, not clobbered
  ```

#### GIMLE-115 — Artifact-registry coordinate resolution via ArtifactPullCache

- **Category**: Config / Internal-Infra
- **User story**: As the platform, I want a blank `artifactPath` assignment (registry-coordinate form) resolved through Andvari via a node-local pull-through cache, and a resolution failure reported as a durable TRANSITION_FAILED event rather than silently leaving the instance stuck, so that coordinate-only deployments work end to end.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`resolveArtifactReference`, `reconcileAssignments`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found (end-to-end coverage lives in `AndvariRegistryIT`, gimle-smoke-tests, out of scope here)
- **Gherkin scenario**:
  ```gherkin
  Given an assignment's artifactPath is blank and andvariBaseUrls is configured
  When resolveArtifactReference runs
  Then it resolves via artifactCache.resolve(httpClient, andvariBaseUrls, moduleId) to a concrete local jar path
  Given resolution fails (e.g. -Dgimle.agent.andvariEndpoint not configured, or Andvari unreachable)
  Then a TRANSITION_FAILED InstanceEvent with "artifact resolution failed" is posted and this assignment is skipped this tick, not fatal to the whole reconcile
  ```

#### GIMLE-116 — Instance-scoped log/config/secret delivery over the control channel

- **Category**: Config
- **User story**: As a module instance, I want my tenant's plain config values and Fafnir-managed secrets delivered over the control channel right after Resolve (before Start), so that every hook's `ctx.config(key)` lookup is backed by real values from the moment the module starts.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`deliverConfig`, `fetchConfigForTenant`, `fetchSecretsForTenant`, `sendInstallStartSequence`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found for `deliverConfig` itself; secret/config delivery end-to-end lives in `GreeterClusterTopologyIT` (gimle-smoke-tests, out of scope)
- **Gherkin scenario**:
  ```gherkin
  Given an instance belongs to a tenant
  When sendInstallStartSequence runs (InstallModule -> ResolveModule -> deliverConfig -> StartModule)
  Then plain config is fetched via GET /config/{tenantId} (already decrypted server-side)
  And Fafnir secrets are fetched directly via this agent's own mTLS node identity (never relayed through the control plane) if fafnirBaseUrl is configured
  And each entry is sent as ControlMessage.ConfigDelivered(key, value, wasEncrypted)
  Given the plain config fetch fails (e.g. a node principal isn't authorized for /config under mTLS)
  Then secret delivery still proceeds independently — one failure never blocks the other
  ```

#### GIMLE-117 — Persistent volume allocation for StatefulSet-shaped instances

- **Category**: Config
- **User story**: As a StatefulSet-shaped module, I want a declared `volume:` request to be allocated to a stable local-disk directory keyed by (deploymentName, instanceIndex), resolved before ResolveModule is sent, so that my `ModuleContext.dataDirectory()` is populated before `onInstall` fires, and a rolling-update teardown-then-replace never releases my data (only a genuine scale-down/deletion does).
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`allocateVolumeIfNeeded`, `sendInstallStartSequence`, `stopInstance`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found (VolumeManager/LocalDiskVolumeManager itself is tested in `gimle-os`'s `LocalDiskVolumeManagerTest`, out of scope)
- **Gherkin scenario**:
  ```gherkin
  Given a descriptor declares volume: and the instance is starting or being installed into an existing worker
  When allocateVolumeIfNeeded runs
  Then volumeManager.allocate(deploymentName, instanceIndex, request) returns a handle before ResolveModule is built
  Given allocation fails (disk space, I/O error)
  Then it is logged and treated as "no volume" rather than blocking the instance from starting
  Given stopInstance runs with releaseVolume=false (rolling-update replace)
  Then the volume handle is deliberately NOT released
  Given stopInstance runs with releaseVolume=true (real scale-down/deletion)
  Then the volume is released
  ```

#### GIMLE-118 — Vessel process supervision (plain-jar workload as its own dedicated process)

- **Category**: Worker Supervision
- **User story**: As an operator running a plain runnable-jar workload (not a Gimlé module), I want it spawned and supervised like a dedicated worker (ResourceLimiter flags, RestartTracker crash-respawn) but without any Gimlé control-protocol handshake, volume allocation, or fabric/gossip registration, so that non-module workloads still get consistent process-lifecycle guarantees.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/VesselProcessSupervisor.java`, `AgentMain.java` (`reconcileVesselAssignment`, `startVesselInstance`, `buildVesselCommand`)
- **Test coverage**: `VesselProcessSupervisorTest#captures_stdout_lines_as_the_instance_application_log`, `#a_crashed_vessel_process_is_respawned`, `#exhausting_the_restart_budget_reports_it_and_stops_respawning`
- **Gherkin scenario**:
  ```gherkin
  Given an assignment carries a VesselSpec
  When reconcileVesselAssignment runs (entirely separate from the module path)
  Then startVesselInstance spawns `java <ResourceLimiter flags> <vessel.jvmFlags> -jar <jar> <vessel.args>` via VesselProcessSupervisor
  And stdout/stderr is captured unconditionally as this instance's own APPLICATION log (no JSON-sniffing, unlike a real worker)
  And a crash restarts via the same RestartTracker-driven backoff/give-up policy as WorkerProcessSupervisor
  ```

#### GIMLE-119 — Vessel port allocation (dynamic/fixed) and env resolution (literal/port/secret)

- **Category**: Config
- **User story**: As a vessel author, I want my declared env vars to resolve to literal values, dynamically-allocated (or fixed) ports, or Fafnir secret values, so that a plain jar can be configured the same declarative way a Gimlé module can, without writing its own bootstrap logic.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`allocateVesselPorts`, `resolveVesselEnv`, `fetchVesselSecretsByKey`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found for these specific methods
- **Gherkin scenario**:
  ```gherkin
  Given a VesselSpec declares env entries of each kind (Literal/PortAllocation/SecretRef)
  When allocateVesselPorts and resolveVesselEnv run
  Then a PortAllocation with no fixed port gets a bind-then-release ephemeral port; a fixed one is used as declared
  And a SecretRef is resolved via fetchVesselSecretsByKey (lazy, fetched at most once per spawn)
  Given a referenced secret key doesn't exist for the tenant
  Then GimleSecretsException.secretNotFound is thrown, failing the spawn
  ```

#### GIMLE-120 — Vessel config-file rendering to disk

- **Category**: Config
- **User story**: As a vessel author, I want declared `vessel.files` entries rendered verbatim (no templating) to disk from tenant config before the process starts, so that a plain jar expecting a config file on disk gets one without needing its own fetch logic.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`renderVesselFiles`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found
- **Gherkin scenario**:
  ```gherkin
  Given a VesselSpec declares files: [{configKey, path}]
  When renderVesselFiles runs before the process spawns
  Then each declared config value's raw content is written verbatim to path (relative to the instance root, or absolute as-is)
  Given the referenced config key has no value for the tenant
  Then GimleManifestException is thrown
  ```

#### GIMLE-121 — Vessel health probing (process-alive + TCP/HTTP rungs, initial-delay aware)

- **Category**: Health / Self-Healing
- **User story**: As the platform, I want a vessel's health derived from OS-level process liveness plus its declared TCP/HTTP probe rung (dialed externally, since a vessel shares no classloader with this agent), honoring its own initialDelaySeconds, so that HealthReconciler treats a dead/not-ready vessel exactly like a dead/not-ready module instance.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`updateVesselHealth`, `evaluateProbe`), `VesselProber.java`
- **Test coverage**: NONE with a dedicated gimle-agent unit test found (VesselProber's tcp/http helpers have no dedicated test file either)
- **Gherkin scenario**:
  ```gherkin
  Given a vessel process is alive and declares an HTTP readiness probe
  When updateVesselHealth runs (polled once per agent tick)
  Then lifecycleState becomes FAILED if the process is dead or liveness fails, STARTING if alive-but-not-ready, ACTIVE if both pass
  Given the probe's initialDelaySeconds hasn't elapsed since startedAt
  Then it reports the appropriate before-delay default (true for liveness, false for readiness) rather than actually dialing
  ```

#### GIMLE-122 — Vessel crash respawn resets probe initial-delay clock

- **Category**: Self-Healing
- **User story**: As the platform, I want a crash-triggered vessel respawn to reset `startedAt`, so that every probe rung's initialDelaySeconds restarts counting from the fresh process's actual start, not the original one.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`onVesselRespawned`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found
- **Gherkin scenario**:
  ```gherkin
  Given a vessel process crashes and VesselProcessSupervisor respawns it
  When onVesselRespawned fires
  Then instance.startedAt is reset to Instant.now() and lifecycleState set to STARTING
  Given the instance was already torn down (undeploy/reassignment) in the crash-to-respawn window
  Then nothing happens (instance is null, so nothing to reset)
  ```

#### GIMLE-123 — mTLS bootstrap CSR flow for node identity

- **Category**: Internal-Infra / Config
- **User story**: As an operator deploying a fresh node under `gimle.transport.protocol=tls`, I want the agent to generate a keypair/CSR in-process and submit it (with a one-time bootstrap token) to `POST /bootstrap/csr` over a server-authenticated-only connection, so that the node obtains its first signed certificate without a manual cert-provisioning step.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`bootstrapCertificateIfNeeded`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found (exercised in mTLS-enabled `gimle-holmgang` topologies, out of scope here)
- **Gherkin scenario**:
  ```gherkin
  Given TLS is enabled and no cert/key files exist yet on disk
  When bootstrapCertificateIfNeeded runs
  Then it generates an RSA keypair and CSR, connects with server-trust-only TLS, and POSTs it plus the bootstrap token to /bootstrap/csr
  And on a 200 response, writes the returned certificate and encoded private key to the configured cert/key files
  Given cert/key files already exist (a redeploy of an already-bootstrapped node)
  Then this is a no-op
  Given the bootstrap token is missing/blank
  Then GimleTlsException.missingProperty is thrown
  ```

#### GIMLE-124 — Periodic certificate rotation check and hot-swap of outbound HttpClient

- **Category**: Internal-Infra
- **User story**: As the platform, I want the agent to check its own leaf certificate for renewal-due status each tick and, if due, submit a same-subject rotation CSR over its current still-valid mTLS connection, write the new key before the new cert (ordering matters for FabricServerTlsWatcher's mtime-poll safety), and swap to a freshly-built HttpClient, so that certificates rotate without a node restart.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`rotateCertificateIfDue`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found
- **Gherkin scenario**:
  ```gherkin
  Given the agent's currently-loaded certificate is due for renewal (per RenewalSchedule)
  When rotateCertificateIfDue runs
  Then it submits a rotation CSR with a fresh keypair over the current httpClient, writes the key file first then the cert file, and returns a new HttpClient plus rotated=true
  Given the rotation request fails or nothing is due
  Then the current HttpClient is returned unchanged with rotated=false, logged but not fatal to this tick
  Given rotation succeeded
  Then gossipMember.reloadDtlsMaterial() is also called
  ```

#### GIMLE-125 — SWIM gossip membership integration with service catalog relay

- **Category**: Fabric
- **User story**: As the platform, I want each agent to run a SWIM gossip member joined via configured seeds, fold local/remote service-registration deltas into a ServiceCatalog, and relay every genuinely-new delta down to every supervised worker's own FabricServiceRegistry, so that service discovery stays eventually consistent without a central catalog service.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`relayCatalogDelta`, `registerIntoCatalog`, `syncCatalogToWorker`, gossip wiring in `main`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test for the relay path itself; `FabricCrossProcessIntegrationTest` exercises it end to end
- **Gherkin scenario**:
  ```gherkin
  Given an agent starts its GossipMember and attaches a ServiceCatalog
  When a supervised worker reports ServiceRegistered/ServiceUnregistered, or gossip learns of a remote node's delta
  Then the catalog applies it and onDelta relays a CatalogUpdate to every currently-supervised worker's connection
  And gossip DEAD/ALIVE convergence feeds directly into catalog.onMembershipChange
  ```

#### GIMLE-126 — Gossip membership read-only HTTP surface

- **Category**: Fabric / Observability
- **User story**: As an operator/tooling author, I want to query an agent's own live SWIM membership view (node id, status, incarnation) directly over HTTP, rather than grepping logs or waiting out a real timeout in a test, so that membership state is directly observable.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentGossipServer.java`
- **Test coverage**: `AgentGossipServerTest#reports_the_lone_self_member_alive_at_incarnation_zero`, `#reflects_a_peer_learned_through_real_swim_convergence`, `#rejects_non_get_methods`
- **Gherkin scenario**:
  ```gherkin
  Given an agent's GossipMember tracks itself and any learned peers
  When GET /gossip/members is requested
  Then it returns each member's nodeId/status/incarnation as JSON
  Given a non-GET method is used
  Then 405 is returned
  ```

#### GIMLE-127 — Node/instance log-serving HTTP surface with tailing and follow

- **Category**: Observability
- **User story**: As the console/CLI, I want to fetch or follow a node's PLATFORM log or an instance's PLATFORM/APPLICATION log directly from the agent hosting it (control plane proxies to the right node), so that log viewing works the same "API server → kubelet → node log" way `kubectl logs` does, including live tailing.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentLogServer.java`
- **Test coverage**: `AgentLogServerTest#node_platform_logs_have_the_shape_the_console_and_cli_need`, `#instance_application_logs_are_scoped_to_the_right_deployment_and_index`, `#instance_logs_reject_a_deployment_name_containing_a_path_separator`, `#instance_logs_reject_a_deployment_name_that_would_escape_the_log_root`
- **Gherkin scenario**:
  ```gherkin
  Given a node or instance log file exists under this agent's logRoot
  When GET /logs/nodes/{nodeId}?category=PLATFORM or /logs/instances/{deploymentName}/{instanceIndex}?category=APPLICATION is requested
  Then a cursor-paginated page (readOlder) or forward-polling page (since=) is returned as JSON
  Given follow=true
  Then the response streams NDJSON via streamFollow until the client disconnects
  Given a deploymentName contains a path separator or would escape the log root
  Then the request is rejected with 400
  ```

#### GIMLE-128 — Merged node-level SYSTEM log view

- **Category**: Observability
- **User story**: As an operator, I want a node's SYSTEM category log (raw non-JSON stdout captured per instance) readable as one merged, timestamp-sorted view across every instance currently on disk for that node, so that I don't have to check each instance's file individually.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentLogServer.java` (`readMergedSystemLogs`, `handleNodeLogs`)
- **Test coverage**: NONE with a dedicated `@Test` name found for the merged-SYSTEM-log path specifically
- **Gherkin scenario**:
  ```gherkin
  Given multiple instances on this node have their own -system.log capture files
  When GET /logs/nodes/{nodeId}?category=SYSTEM is requested
  Then every *-system.log file under workers/ is read and merged, sorted by timestamp
  Given follow=true is requested for SYSTEM
  Then 400 is returned (not supported — multiple underlying files)
  ```

#### GIMLE-129 — `hs_err_pid*.log` crash-dump listing and fetch

- **Category**: Observability / Cgroup Management
- **User story**: As an operator debugging a native crash, I want to list and fetch the exact `hs_err_pid*.log` dump(s) for a crashed instance directly from the agent, so that crash forensics don't require shell access to the node.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentLogServer.java` (`listCrashDumps`, `fetchCrashDump`, `CRASH_DUMP_FILENAME`)
- **Test coverage**: `AgentLogServerTest#crash_dumps_are_listed_from_the_right_worker_directory_only`, `#crash_dumps_list_is_empty_when_the_worker_never_crashed`, `#a_crash_dump_is_fetched_with_its_exact_content_and_a_plain_text_content_type`, `#crash_dump_fetch_rejects_a_filename_that_does_not_match_the_expected_pattern`
- **Gherkin scenario**:
  ```gherkin
  Given a worker JVM native-crashed and HotSpot wrote hs_err_pid<pid>.log under its workerLogRoot
  When GET /logs/instances/{deploymentName}/{instanceIndex}/crashdumps is requested
  Then every matching file is listed with name/sizeBytes/lastModified, sorted
  When GET .../crashdumps/{name} is requested with a name matching the exact expected pattern
  Then the raw file content is returned as text/plain
  Given the filename doesn't match the strict pattern
  Then 400 is returned before any filesystem access
  ```

#### GIMLE-130 — Node-agent log/metrics shipping to Muninn (own + supervised)

- **Category**: Observability
- **User story**: As the platform, I want an agent to ship its own platform log/metrics plus every supervised instance's PLATFORM/APPLICATION logs, and every supervised worker JVM's own metrics/traces, to Muninn (when configured), each on its own best-effort NDJSON-per-tick shipper, so that logs/metrics/traces survive a node's death via Muninn's fallback.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`startShippingInstanceLogs`, `stopShippingInstanceLogs`, `startShippingWorkerMetricsAndTraces`, `stopShippingWorkerMetricsAndTraces`, `WorkerShipperPair`)
- **Test coverage**: `AgentMuninnShippingTest#a_null_muninn_endpoint_starts_no_shippers`, `#a_configured_endpoint_ships_the_instances_application_log_to_its_own_instance_scoped_path`, `#stopping_shipping_removes_the_key_and_closes_every_shipper_so_no_further_ticks_arrive`, `#a_null_muninn_endpoint_starts_no_worker_shippers`, `#a_configured_endpoint_starts_one_metrics_and_one_traces_shipper_keyed_by_worker_id`, `#starting_twice_for_the_same_worker_id_is_a_noop_not_a_second_pair`, `#stopping_removes_the_key_and_a_missing_worker_id_is_a_noop`, `#hello_then_metrics_and_traces_snapshots_relay_to_the_stub_muninn_server`
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.agent.muninnEndpoint is configured
  When an instance is added to supervised
  Then startShippingInstanceLogs starts a PLATFORM and an APPLICATION MuninnShipper for it, mirroring AgentLogServer's own path derivation exactly
  When a worker's Hello handshake arrives
  Then startShippingWorkerMetricsAndTraces starts one metrics and one traces shipper per worker id (never duplicated for a second instance sharing the same connection)
  Given muninnEndpoint is unset
  Then no shippers are ever created (local-only tailing still works unchanged)
  ```

#### GIMLE-131 — Whitelisted control-plane read relay (worker→agent→control plane) with independent re-validation

- **Category**: Fabric / Config
- **User story**: As the trust boundary of the whole relay mechanism, I want the agent to independently re-validate a worker-relayed read request's path against a strict whitelist regex (exactly `GET /endpoints/{name}`, no traversal, no smuggled segments) before ever making the real call, so that a hosted module is never implicitly trusted to only ask for something already allowed.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`handleRelayRead`, `RELAY_WHITELIST_PATTERN`)
- **Test coverage**: `AgentRelayControlPlaneReadTest#a_non_whitelisted_path_is_rejected_locally_and_never_reaches_the_control_plane`, `#a_path_traversal_attempt_disguised_as_a_single_segment_is_rejected`, `#a_whitelisted_path_triggers_a_real_call_and_relays_the_response_back`; end-to-end via `RelayControlPlaneEndToEndTest#a_hosted_modules_relay_call_round_trips_through_a_real_worker_process`
- **Gherkin scenario**:
  ```gherkin
  Given a worker sends RelayControlPlaneRead for a whitelisted path
  When handleRelayRead validates it against RELAY_WHITELIST_PATTERN
  Then the real GET call is made against the control plane and the response relayed back as RelayControlPlaneResult
  Given the path is not whitelisted (wrong shape, traversal attempt, extra segments)
  Then a synthesized 403 is returned locally without ever reaching the control plane
  Given the control plane call itself fails
  Then a synthesized 502 is returned
  ```

#### GIMLE-132 — Node capacity/instance-observation heartbeat reporting

- **Category**: Observability / Worker Supervision
- **User story**: As the control plane, I want each agent's heartbeat to report machine capacity plus every supervised instance's/vessel's alive/ready state and self-reported CPU/memory/request-rate/error-rate/queue-depth, so that scheduling and health reconciliation have current, accurate observed state.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`sendHeartbeat`, `observationJson`, `vesselObservationJson`)
- **Test coverage**: `AgentMainTest#observation_json_reports_the_instances_real_self_reported_resource_usage`, `#observation_json_reports_the_instances_real_self_reported_request_and_error_rate`, `#observation_json_reports_a_completed_job_run_as_alive_but_not_ready`, `#observation_json_reports_a_failed_instance_as_not_alive`
- **Gherkin scenario**:
  ```gherkin
  Given supervised instances/vessels and a capacity snapshot
  When sendHeartbeat POSTs to /nodes/{nodeId}/heartbeat
  Then alive is derived as an EXCLUSION check (not "FAILED"), so a COMPLETED job still reports alive=true
  And ready is true only when lifecycleState is exactly ACTIVE
  Given a vessel instance
  Then it reports zero for every in-JVM-only metrics field but real allocatedPorts
  ```

#### GIMLE-133 — Instance-event forwarding (worker-reported and agent-originated) to control plane

- **Category**: Observability
- **User story**: As the control plane, I want both worker-reported InstanceEventOccurred messages and agent-originated events (crash classification, artifact resolution failure) posted to `/nodes/{nodeId}/events`, best-effort, so that the durable timeline captures the full picture without ever stalling the agent on a delivery failure.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`postInstanceEvent`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found (covered indirectly through end-to-end IT scenarios)
- **Gherkin scenario**:
  ```gherkin
  Given a worker-reported or agent-originated InstanceEvent
  When postInstanceEvent is called
  Then it POSTs the event body to /nodes/{nodeId}/events
  Given the POST fails (network error, control plane down)
  Then it is logged and swallowed — never propagated to stall the caller
  ```

#### GIMLE-134 — Node placement-label registration

- **Category**: Config
- **User story**: As an operator, I want to tag a node with operator-assigned placement labels (`-Dgimle.node.labels=gpu,ssd`) at registration time, so that the scheduler can satisfy deployments requiring a specific node capability.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`nodeLabels`, `register`)
- **Test coverage**: NONE with a dedicated gimle-agent unit test found
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.node.labels=gpu,ssd is set on the agent process
  When register() runs
  Then the registration body's capabilities.labels includes ["gpu", "ssd"]
  Given the property is unset or blank
  Then labels is an empty list
  ```

#### GIMLE-135 — `module-info.java` wiring for the node agent module

- **Category**: Internal-Infra
- **User story**: As a maintainer, I want `com.gimle.agent`'s JPMS declaration to require exactly what its runtime behavior needs (core, os, module, fabric, pki, observability, micrometer, java/jdk management, java.net.http, jdk.httpserver, SLF4J, BouncyCastle PKIX/provider for CSR generation), so that dependency boundaries stay explicit.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/module-info.java`
- **Test coverage**: NONE (compile-time enforcement only)
- **Gherkin scenario**:
  ```gherkin
  Given gimle-agent's module-info.java
  When it is compiled
  Then it requires com.gimle.core, com.gimle.os, com.gimle.module, com.gimle.fabric, com.gimle.pki, com.gimle.observability, micrometer.core, java.management, jdk.management, java.net.http, jdk.httpserver, org.slf4j, org.bouncycastle.pkix, org.bouncycastle.provider
  And exports only com.gimle.agent
  ```

#### GIMLE-568 — gimle-bifrost: per-node service proxy (kube-proxy analogue)

- **Category**: Service Fabric
- **User story**: As a module author, I want to dial a Service by its synthesized loopback ClusterIP address from any node and have that node's own agent transparently forward my connection to one of the Service's currently live backing instances, round-robin, without needing to resolve endpoints myself.
- **Status**: Complete for v1's stated scope (round-robin only, no locality- or load-aware selection); opt-in via -Dgimle.agent.bifrostEnabled=true, off by default
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/bifrost/BifrostProxy.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/ServiceListener.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/LoopbackAddressAllocator.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/HttpServiceSource.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/ServiceSource.java`, `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (bifrostEnabled wiring)
- **Test coverage**: `BifrostProxyTest` (round_robin_rotates_across_multiple_endpoints, a_service_disappearing_from_the_source_closes_its_listener, a_new_service_appearing_gets_a_new_listener); `LoopbackAddressAllocatorTest`; `HttpServiceSourceTest`
- **Gherkin scenario**:
  ```gherkin
  Given BifrostProxy polling a ServiceSource that reports Service "orders" with two live endpoints, When pollOnce runs, Then a loopback listener is bound at a stable 127.x.y.1 address and successive connections round-robin across both endpoints.
  Given a service previously bound, When it disappears from the next poll's service list, Then its listener is closed.
  Given a service appearing for the first time on a poll, When pollOnce runs, Then a new listener is bound for it without disturbing already-bound listeners for other services.
  ```

#### GIMLE-575 — Bifrost fails closed for a NetworkPolicySpec-restricted Service

- **Category**: Networking/Security
- **User story**: As a platform operator, I want Bifrost's per-node service proxy to refuse to relay traffic for a Service whose tenant currently has an applicable NetworkPolicySpec, so dialing a Service's synthesized loopback ClusterIP through Bifrost cannot be used to bypass the same policy FabricServer already enforces on the real fabric path.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/bifrost/ServiceSummary.java` (new: a Service's own tenantId/deploymentNames), `gimle-agent/src/main/java/com/gimle/agent/bifrost/ServiceSource.java` (`listServices` replaces `listServiceNames`), `gimle-agent/src/main/java/com/gimle/agent/bifrost/HttpServiceSource.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/BifrostProxy.java` (`isRestricted`, polls `NetworkPolicySource` each tick), `gimle-agent/src/main/java/com/gimle/agent/bifrost/ServiceListener.java` (`setRestricted`, `forward` fails closed), `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (shares one `HttpNetworkPolicySource` between `BifrostProxy` and `NetworkPolicyRelay`)
- **Test coverage**: `BifrostProxyTest` (a_tenant_wide_network_policy_makes_bifrost_refuse_to_proxy_the_restricted_tenants_service, a_deployment_scoped_network_policy_only_restricts_a_service_it_actually_names, a_network_policy_lifted_on_a_later_poll_lets_bifrost_resume_proxying_the_now_unrestricted_service); `HttpServiceSourceTest` (parses tenantId/deploymentNames from GET /services)
- **Gherkin scenario**:
  ```gherkin
  Given a NetworkPolicySpec restricting tenant acme (tenant-wide), When a caller dials acme's Service through its Bifrost-synthesized ClusterIP, Then Bifrost refuses the connection outright rather than proxying it to a live endpoint.
  Given a NetworkPolicySpec scoped to one of acme's own deployments, When a caller dials a different Service in the same tenant fronting an unrelated deployment, Then Bifrost proxies it normally -- the scoped policy never restricts a deployment it doesn't name.
  Given a Service Bifrost is currently refusing to proxy, When the restricting NetworkPolicySpec is removed and Bifrost polls again, Then it resumes proxying that Service on the very next tick.
  ```

### gimle-mimir

#### GIMLE-136 — Raft Leader Election

- **Category**: Raft Consensus
- **User story**: As a control-plane operator, I want the store cluster to autonomously elect exactly one leader per term, so that writes always have a single, unambiguous owner even after a node fails.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.raft.RaftNode` (`startElectionLocked`, `becomeLeaderLocked`, `onRequestVote`), `RequestVote`/`RequestVoteResponse`
- **Test coverage**: `RaftClusterTest#leader_election_converges_to_exactly_one_leader`, `RaftNodeSafetyMechanicsTest#a_candidate_with_a_stale_log_never_wins_even_when_its_request_vote_arrives_first`
- **Gherkin scenario**:
  ```gherkin
  Given a 3-node Raft cluster with no leader yet; When election timeouts fire and a candidate requests votes; Then exactly one node becomes leader for that term, and every write submitted afterward is accepted only by that leader.
  ```

#### GIMLE-137 — Log Replication (AppendEntries)

- **Category**: Raft Consensus
- **User story**: As a control-plane operator, I want every committed write replicated to a majority of store nodes before it's acknowledged, so that no acknowledged write is ever lost.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#sendOnce`, `#onAppendEntries`, `AppendEntries`/`AppendEntriesResponse`
- **Test coverage**: `RaftClusterTest#a_submitted_write_becomes_visible_on_every_replica_after_the_next_append_entries_round`
- **Gherkin scenario**:
  ```gherkin
  Given a leader with two healthy followers; When the leader proposes a StateMutation; Then the entry is appended and sent via AppendEntries, becoming visible on every replica after the next round.
  ```

#### GIMLE-138 — Election Safety Restriction (log up-to-date check)

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want a candidate whose log is behind to never win an election, so a leader with an incomplete log can never overwrite committed entries.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#onRequestVote` (`candidateUpToDate` check)
- **Test coverage**: `RaftNodeSafetyMechanicsTest#a_candidate_with_a_stale_log_never_wins_even_when_its_request_vote_arrives_first`
- **Gherkin scenario**:
  ```gherkin
  Given a candidate whose log lags the current leader's log; When its RequestVote arrives elsewhere first; Then the vote is denied because the candidate's log isn't at least as up to date.
  ```

#### GIMLE-139 — Conflicting-Entry Truncation

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want a follower whose log diverges from the leader's corrected automatically, so every replica converges to the same log.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#onAppendEntries` (truncate-on-conflict loop), `RaftLog#truncateFrom`
- **Test coverage**: `RaftNodeSafetyMechanicsTest#a_follower_truncates_a_conflicting_entry_and_everything_after_it_before_appending`
- **Gherkin scenario**:
  ```gherkin
  Given a follower with an entry at index N from an old term; When an AppendEntries request carries a different entry at index N; Then the follower truncates from index N onward before appending the leader's entries.
  ```

#### GIMLE-140 — Leader-Only-Commits-Own-Term Rule (Figure 8)

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want a leader to only commit entries replicated during its own term, so an entry from a prior uncommitted term can never be silently re-exposed as committed.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#advanceCommitIndexLocked`
- **Test coverage**: `RaftNodeSafetyMechanicsTest#the_leader_only_commits_an_entry_from_its_own_current_term`
- **Gherkin scenario**:
  ```gherkin
  Given a leader whose matchIndex table reaches majority for an entry from an earlier term; When advanceCommitIndex evaluates that index; Then the entry is not committed unless a later same-term entry also reaches majority.
  ```

#### GIMLE-141 — Strict Apply Ordering (commitIndex vs lastApplied)

- **Category**: Raft Consensus
- **User story**: As a state-machine implementer, I want committed entries applied strictly in order with no gaps.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#applyCommittedLocked`
- **Test coverage**: `RaftNodeSafetyMechanicsTest#apply_never_runs_ahead_of_commit_index_and_never_skips_an_entry`
- **Gherkin scenario**:
  ```gherkin
  Given commitIndex advanced past lastApplied by several entries; When applyCommittedLocked runs; Then every entry from lastApplied+1 through commitIndex is applied one at a time, in order.
  ```

#### GIMLE-142 — Proposal Timeout with Ghost-Write Prevention

- **Category**: Raft Consensus
- **User story**: As an application caller, I want a timed-out proposal truncated from the log rather than silently committing later.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#awaitAppliedThrowing`, `#giveUpAndTruncateLocked`
- **Test coverage**: `RaftNodeSafetyMechanicsTest#a_timed_out_proposal_is_truncated_so_it_cannot_ghost_commit_once_quorum_returns`, `#a_proposal_that_commits_just_before_its_timeout_fires_is_not_truncated`
- **Gherkin scenario**:
  ```gherkin
  Given a leader isolated from quorum submits a proposal; When the propose timeout elapses without majority ack; Then the entry is truncated from the leader's own log and GimleRaftException is thrown.
  ```

#### GIMLE-143 — Chunked InstallSnapshot Transfer (Figure 13)

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want a far-behind follower to catch up via bounded-size snapshot transfer instead of replaying the entire log.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#sendInstallSnapshot`, `#onInstallSnapshot`, `InstallSnapshot`/`InstallSnapshotResponse`
- **Test coverage**: `RaftClusterTest#a_far_behind_follower_catches_up_via_install_snapshot_not_full_log_replay`, `RaftNodeSafetyMechanicsTest#an_install_snapshot_is_applied_only_once_the_final_done_chunk_arrives`, `#a_chunk_arriving_at_an_unexpected_offset_is_acknowledged_but_not_buffered`, `#an_offset_zero_chunk_discards_a_stale_in_progress_transfer_and_starts_a_fresh_one`
- **Gherkin scenario**:
  ```gherkin
  Given a follower's nextIndex has fallen below the leader's snapshot floor; When the leader's peer-sender loop runs; Then it sends the snapshot as sequential offset/done chunks, installed only once the final chunk arrives.
  ```

#### GIMLE-144 — Local Log Compaction / Snapshotting

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want the Raft log compacted once it grows past a threshold, so disk usage and startup replay time stay bounded.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#maybeCompactLocked`, `RaftLog#installSnapshot`
- **Test coverage**: `RaftLogTest#install_snapshot_persists_and_discards_compacted_entries`
- **Gherkin scenario**:
  ```gherkin
  Given the log has grown beyond SNAPSHOT_THRESHOLD past the current floor; When applyCommittedLocked finishes; Then a new StateStore snapshot is taken and every entry at or below the floor is discarded.
  ```

#### GIMLE-145 — Check-Quorum Leader Self-Demotion

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want a leader isolated in a minority partition to step down on its own without observing a higher term.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#checkQuorumTick`, `#CHECK_QUORUM_WINDOW`
- **Test coverage**: `RaftClusterTest#a_leader_partitioned_from_the_majority_steps_down_on_its_own_via_check_quorum`, `#a_leader_with_a_reachable_majority_never_self_demotes_via_check_quorum`
- **Gherkin scenario**:
  ```gherkin
  Given a leader can no longer reach a majority of peers; When the check-quorum window elapses with no successful RPC round trip; Then the leader steps down to follower on its own.
  ```

#### GIMLE-146 — Etcd-Style Live Membership Change (AddServer/RemoveServer)

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want to grow or shrink the Raft cluster one server at a time while live.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#addServer`, `#removeServer`, `#appendMembershipChangeLocked`, `#reconfigurePeersLocked`, `MembershipChange`
- **Test coverage**: `RaftMembershipChangeTest#adding_a_server_joins_it_and_a_subsequent_mutation_still_commits`, `#a_second_membership_change_is_rejected_while_an_earlier_one_is_still_uncommitted`, `#removing_a_server_drops_it_from_the_peer_set_and_the_lone_remaining_node_still_commits`, `RaftClusterTest#a_three_node_cluster_grows_to_five_live_and_writes_continue_succeeding`
- **Gherkin scenario**:
  ```gherkin
  Given a running 3-node cluster with a leader; When addServer is called for a new peer; Then a self-inclusive MembershipChange entry is replicated, and a subsequent mutation still commits once the new node acknowledges; a second membership change is rejected while the first is uncommitted.
  ```

#### GIMLE-147 — Non-Voting Learner & Automatic Promotion

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want a newly-added peer to join as a non-voting learner and only be promoted once caught up.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RaftNode#learners`, `#maybePromoteLearnerLocked`, `#LEARNER_CATCH_UP_THRESHOLD`, `#votingPeersLocked`
- **Test coverage**: `RaftMembershipChangeTest#a_freshly_added_learner_does_not_block_or_count_toward_commit_quorum`, `#a_learner_is_promoted_to_a_full_voting_member_once_its_log_catches_up`, `#a_never_caught_up_learner_stays_non_voting_indefinitely`
- **Gherkin scenario**:
  ```gherkin
  Given a newly-added peer far behind the leader's log; When added via addServer; Then it doesn't count toward quorum until its matchIndex is within the catch-up threshold, at which point it's automatically promoted via a replicated MembershipChange.
  ```

#### GIMLE-148 — Durable Raft Log Persistence

- **Category**: Raft Consensus
- **User story**: As a cluster operator, I want every log entry and the current term/vote to survive a process crash.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.raft.RaftLog` (`append`, `setTermAndVote`, `loadState`, `loadEntries`)
- **Test coverage**: `RaftLogTest#term_and_vote_persist_across_reopen`, `#reopening_recovers_every_persisted_entry`, `#a_far_behind_node_recovers_the_snapshot_floor_and_bytes_across_reopen`, `#a_corrupted_log_entry_file_fails_loudly_at_construction`
- **Gherkin scenario**:
  ```gherkin
  Given a node appends entries and votes; When restarted and RaftLog reopened; Then every persisted entry, term, and vote are recovered exactly as written.
  ```

#### GIMLE-149 — Raft Transport over Mutual TLS with Hot Cert Reload

- **Category**: Raft Consensus
- **User story**: As a security-conscious operator, I want Raft peer traffic authenticated by mutual TLS with hot cert reload.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.raft.RaftTransport`, `PeerConnection`
- **Test coverage**: `RaftClusterTlsTest#leader_election_and_write_replication_work_over_mtls`, `#leader_crash_triggers_re_election_over_mtls`, `#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_transport`, `#a_peer_cert_not_signed_by_the_configured_ca_is_rejected_at_handshake`
- **Gherkin scenario**:
  ```gherkin
  Given a cluster configured for mTLS with a shared CA; When peers exchange RequestVote/AppendEntries/InstallSnapshot; Then election and replication succeed as in plaintext; a peer cert not signed by the CA is rejected; reloaded TLS material lets a fresh connection succeed without restart.
  ```

#### GIMLE-150 — Raft RPC Wire Codec

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want Raft RPCs and log entries encoded in a compact, adversarial-input-resilient binary format.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.raft.RaftCodec`
- **Test coverage**: `RaftCodecTest#round_trips_through_streams`, `#rejects_an_oversized_length_prefix_before_allocating`, `#rejects_a_negative_length_prefix_before_allocating`, `#rejects_a_forged_huge_entry_count_without_preallocating`, `#round_trips_a_state_snapshot`, `#round_trips_a_log_entry_carrying_a_membership_change`
- **Gherkin scenario**:
  ```gherkin
  Given an arbitrarily-constructed RaftRpc; When written and read back via RaftCodec; Then the decoded value equals the original; a forged oversized/negative length prefix is rejected before allocation.
  ```

#### GIMLE-151 — Atomic Durable File Writes

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want every persisted state file written via temp-file-plus-atomic-rename with fsync.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.store.AtomicFiles`
- **Test coverage**: `AtomicFilesTest#writes_content_visible_under_the_final_name_with_no_leftover_tmp_file`, `#the_written_file_has_no_unflushed_dirty_state_after_writeatomically_returns`, `StateStoreTest#a_leftover_tmp_file_from_an_interrupted_write_is_never_read_back`
- **Gherkin scenario**:
  ```gherkin
  Given a write crashes between temp-file write and final rename; When later read; Then no torn or partially-written file is ever visible.
  ```

#### GIMLE-152 — File-Backed State Store Persistence Engine

- **Category**: State Store
- **User story**: As the Raft state machine, I want every resource kind durably persisted to disk and rebuilt in memory on restart.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.store.StateStore`
- **Test coverage**: `StateStoreTest#a_fresh_store_creates_its_directory_layout`, `#deployment_round_trips_through_a_fresh_store_instance`, `#removed_deployment_is_gone_after_reload`, `#assignment_round_trips_and_is_scoped_to_its_deployment`, `#role_role_binding_and_account_round_trip_through_a_fresh_store_instance`
- **Gherkin scenario**:
  ```gherkin
  Given a StateStore against an empty directory; When a DeploymentSpec is put, then a fresh instance opened against the same directory; Then the deployment is present in the reloaded store.
  ```

#### GIMLE-153 — Full-State Snapshot / Restore

- **Category**: State Store
- **User story**: As the Raft state machine, I want a point-in-time snapshot installable wholesale on another replica.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StateStore#snapshot`, `#restoreFromSnapshot`, `com.gimle.mimir.store.StateSnapshot`
- **Test coverage**: `StateStoreTest#a_snapshot_carries_reconciler_instance_state_and_restores_it`, `#a_snapshot_carries_instance_events_and_restores_them`, `#a_snapshot_carries_audit_events_and_restores_them`, `RaftCodecTest#round_trips_a_state_snapshot`
- **Gherkin scenario**:
  ```gherkin
  Given a StateStore holding every resource kind; When snapshot() then restoreFromSnapshot on a fresh store; Then every kind (except leader-local heartbeats) is reproduced identically.
  ```

#### GIMLE-154 — Replicated Mutation Catalog (StateMutation)

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want every state-changing operation represented as one sealed, replicated mutation type applied identically on every replica.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.raft.StateMutation` (sealed interface, ~40 record variants)
- **Test coverage**: `RaftCodecTest#round_trips_role_rolebinding_and_account_mutations_through_a_log_entry`, `#round_trips_an_append_instance_event_mutation_with_and_without_a_cause_summary`, `#round_trips_an_append_audit_event_mutation_allowed_and_denied_with_and_without_scope`
- **Gherkin scenario**:
  ```gherkin
  Given a StateMutation; When applied via applyTo(store) on any replica after commit; Then the exact same StateStore call is invoked with the exact same arguments everywhere.
  ```

#### GIMLE-155 — Leader-Local Node Heartbeat Tracking

- **Category**: State Store
- **User story**: As a reconciler, I want node heartbeats handled outside the replicated Raft log so high-frequency traffic never inflates the log.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StateStore#putNodeHeartbeat`, `#getNodeHeartbeat`, `com.gimle.mimir.rpc.StoreNode#handleGetNodeHeartbeat`
- **Test coverage**: `StoreNodeTest#a_leader_reads_back_a_heartbeat_it_just_accepted`, `rpc/StoreClientClusterTest#heartbeat_reads_are_leader_routed_and_never_answer_empty_from_a_stale_follower`
- **Gherkin scenario**:
  ```gherkin
  Given a leader receives a heartbeat via putHeartbeat; When a follower is asked for that node's heartbeat; Then the follower answers empty, while the leader answers with the observed heartbeat.
  ```

#### GIMLE-156 — Distributed Lease Coordination (Grant/Renew/Release)

- **Category**: State Store
- **User story**: As a control-plane replica, I want a leader-local, TTL-based lease primitive usable to build reconciler-leader election.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StateStore#tryAcquireOrRenewLease`, `#releaseLease`, `#getLeaseHolder`, `com.gimle.mimir.store.LeaseGrant`
- **Test coverage**: `StateStoreTest#a_free_lease_is_granted_to_the_first_caller`, `#the_current_holder_can_renew_its_own_lease`, `#a_different_holder_is_denied_while_the_lease_is_still_valid`, `#a_different_holder_is_granted_once_the_lease_has_expired`, `rpc/StoreClientClusterTest#leases_are_acquired_renewed_and_released_through_the_client`
- **Gherkin scenario**:
  ```gherkin
  Given a free lease; When holder A acquires it, then B tries before it expires; Then A succeeds, B is denied, and B succeeds once the TTL expires.
  ```

#### GIMLE-157 — Per-Instance Lifecycle Event Log with Retention Cap

- **Category**: State Store
- **User story**: As an operator, I want a bounded, newest-first timeline of lifecycle events per instance.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StateStore#putInstanceEvent`, `#listInstanceEvents`, `StateMutation.AppendInstanceEvent`
- **Test coverage**: `StateStoreTest#instance_events_round_trip_newest_first_through_a_fresh_store_instance`, `#instance_events_beyond_the_retention_cap_prune_the_oldest_first`
- **Gherkin scenario**:
  ```gherkin
  Given an instance that has accumulated more than MAX_EVENTS_PER_INSTANCE events; When another event is appended; Then the oldest event is pruned; listInstanceEvents returns newest-first.
  ```

#### GIMLE-158 — Cluster-Wide Audit Trail with Filtering

- **Category**: State Store
- **User story**: As a security auditor, I want every authorization decision recorded to a cluster-wide, filterable, bounded audit trail.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StateStore#putAuditEvent`, `#listAuditEvents`, `StateMutation.AppendAuditEvent`
- **Test coverage**: `StateStoreTest#audit_events_filter_by_principal_resource_kind_tenant_and_since_independently`, `#audit_events_beyond_the_retention_cap_prune_the_oldest_first`, `#concurrent_audit_event_appends_never_exceed_the_cap_or_lose_or_duplicate_an_event`
- **Gherkin scenario**:
  ```gherkin
  Given events across different principals/resources/tenants; When listAuditEvents is called with filters; Then only matching events are returned newest-first, and concurrent appends never lose/duplicate/exceed the cap.
  ```

#### GIMLE-159 — Deployment Rolling-Update & Surge Bookkeeping

- **Category**: State Store
- **User story**: As a reconciler, I want in-flight rolling-update and surge state persisted per deployment so a reconciler restart resumes instead of duplicating.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `StateStore#addRollingIndex`/`#getRollingIndices`, `#addSurgeIndex`/`#getSurgeIndices`, `StateMutation.AddRollingIndex`/`AddSurgeIndex`
- **Test coverage**: Covered indirectly by `RaftCodecTest` mutation round-trips — NONE direct StateStoreTest method found
- **Gherkin scenario**:
  ```gherkin
  Given a deployment mid-rolling-update with index 2 marked in flight; When the control-plane process restarts; Then getRollingIndices still reports index 2 as in flight.
  ```

#### GIMLE-160 — StatefulSet OrderedReady Index & Sticky Node Binding

- **Category**: State Store
- **User story**: As a StatefulSet operator, I want each ordinal index sticky-bound to the node it first lands on.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `StateStore#putStatefulSetIndexNode`/`#getStatefulSetIndexNode`, `#putRollingStatefulSetIndex`, `StateMutation.PutStatefulSetIndexNode`
- **Test coverage**: NONE dedicated `StateStoreTest` method found
- **Gherkin scenario**:
  ```gherkin
  Given an index never placed before; When first placed on node A via putStatefulSetIndexNode; Then a later rolling-update replacement of that index is placed on node A again; the binding survives ordinary assignment removal, cleared only on permanent index removal.
  ```

#### GIMLE-161 — Node Cordon (Scheduler Exclusion Flag)

- **Category**: State Store
- **User story**: As an operator, I want to mark a node as "don't place anything new here" without evicting what's already running.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StateStore#putNodeCordon`, `#isNodeCordoned`, `StateMutation.PutNodeCordon`
- **Test coverage**: `StateStoreTest#node_cordon_round_trips_through_a_fresh_store_instance`, `#uncordoning_a_node_clears_it_and_is_gone_after_reload`, `#a_snapshot_carries_node_cordons_and_restores_them`
- **Gherkin scenario**:
  ```gherkin
  Given an uncordoned node; When cordoned; Then isNodeCordoned reports true and survives a reload; uncordoning clears it.
  ```

#### GIMLE-162 — Tenant Quota-Violation Flag Tracking

- **Category**: State Store
- **User story**: As a tenant administrator, I want a deployment's quota-violation status tracked as a level-triggered flag.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `StateStore#putQuotaViolation`, `#isQuotaViolating`
- **Test coverage**: Covered indirectly via `rpc.StoreClientClusterTest`/`StoreNodeTest` — NONE direct
- **Gherkin scenario**:
  ```gherkin
  Given a deployment marked quota-violating; When putQuotaViolation is called again with violating=false; Then isQuotaViolating reports false and the file is deleted.
  ```

#### GIMLE-163 — RBAC Data Persistence (Roles, RoleBindings, Accounts)

- **Category**: State Store
- **User story**: As a cluster administrator, I want roles, role bindings, and console-login accounts stored as first-class replicated resources.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StateStore#putRole`/`#putRoleBinding`/`#putAccount`
- **Test coverage**: `StateStoreTest#role_role_binding_and_account_round_trip_through_a_fresh_store_instance`
- **Gherkin scenario**:
  ```gherkin
  Given a custom Role, RoleBinding, and Account; When put and a fresh StateStore instance opened; Then all three round-trip identically.
  ```

#### GIMLE-164 — Client-Facing Store RPC with Leader Redirect & Follow

- **Category**: Internal/Infra
- **User story**: As a control-plane process, I want a StoreClient that reads from any replica but automatically follows a NotLeader redirect for writes.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.rpc.StoreClient#sendLeaderOnly`, `#followLeaderHint`
- **Test coverage**: `StoreClientClusterTest#a_client_can_read_and_write_through_any_endpoint_once_a_leader_is_elected`, `#a_client_keeps_writing_successfully_across_a_forced_leader_failover`
- **Gherkin scenario**:
  ```gherkin
  Given a StoreClient with no known leader; When propose() is called and a follower answers NotLeader with the real leader's address; Then the client retries directly against that address and caches it.
  ```

#### GIMLE-165 — Store Read Load Balancing Across Replicas

- **Category**: State Store
- **User story**: As a control-plane process, I want reads spread round-robin across every configured store endpoint, skipping unreachable ones.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StoreClient#sendRead`
- **Test coverage**: `StoreConnectionTimeoutTest#a_store_client_fails_over_past_a_silent_endpoint_to_one_that_answers`, `rpc/StoreRpcLatencyTest#many_sequential_store_reads_are_not_paying_a_per_call_nagle_stall`
- **Gherkin scenario**:
  ```gherkin
  Given three endpoints, one unreachable; When several reads are issued; Then each read tries endpoints from a rotating cursor and returns from the first reachable one.
  ```

#### GIMLE-166 — Store Node Leader-Only Write Gating

- **Category**: Internal/Infra
- **User story**: As a Raft node, I want every mutating RPC rejected with a leader-hint redirect if this node isn't leader.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.rpc.StoreNode#handlePropose`, `#handlePutHeartbeat`, `#handleAcquireOrRenewLease`, `#handleAddServer`, `#notLeaderResponse`
- **Test coverage**: `StoreNodeTest#a_non_leader_rejects_a_propose_with_not_leader_and_no_hint_yet`, `#a_non_leader_rejects_a_heartbeat_a_lease_acquire_and_a_lease_release`, `#a_non_leader_rejects_an_add_server_request_with_not_leader`
- **Gherkin scenario**:
  ```gherkin
  Given a non-leader StoreNode; When it receives Propose/PutHeartbeat/AcquireOrRenewLease/AddServer; Then it responds NotLeader, carrying the leader's address if known, without applying anything.
  ```

#### GIMLE-167 — Store Client Connection Timeout Bounds

- **Category**: Internal/Infra
- **User story**: As a control-plane process, I want a connection that accepts but never responds to time out rather than hang forever.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.rpc.StoreConnection`
- **Test coverage**: `StoreConnectionTimeoutTest#a_connection_that_accepts_but_never_responds_times_out_instead_of_blocking_forever`
- **Gherkin scenario**:
  ```gherkin
  Given an endpoint that accepts but never writes a response; When a StoreClient call is made; Then it fails with SocketTimeoutException within the configured bound, not indefinitely.
  ```

#### GIMLE-168 — Store RPC Wire Codec

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want the client-facing StoreRpc protocol encoded compactly and defensively.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.rpc.StoreCodec`
- **Test coverage**: `StoreCodecTest#round_trips_through_streams`, `#round_trips_a_weighted_autoscale_policy_with_every_weight_present`, `#round_trips_an_account_result_carrying_a_password_hash`
- **Gherkin scenario**:
  ```gherkin
  Given any StoreRpc request/response; When written and read back through StoreCodec; Then the decoded value equals the original exactly.
  ```

#### GIMLE-169 — RBAC Authorization Engine

- **Category**: Internal-Infra
- **User story**: As an operator, I want a single authorizer combining explicit RoleBindings, an implicit cluster-admin operator group, and node self-service.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.authz.Authorizer#authorize`, `#isNodeSelfService`, `#resolveRole`
- **Test coverage**: `AuthorizerTest#a_principal_with_no_binding_and_no_group_is_denied_everything`, `#an_operator_group_member_is_allowed_everything_via_the_implicit_cluster_admin_binding`, `#a_custom_role_bound_to_a_user_grants_exactly_its_declared_permissions`, `#a_tenant_scoped_permission_only_matches_its_own_tenant`, `#a_node_may_act_on_its_own_node_and_log_endpoints_with_no_role_binding_at_all`, `#a_node_is_denied_another_nodes_endpoints`, `#a_binding_referencing_a_role_that_no_longer_exists_grants_nothing`
- **Gherkin scenario**:
  ```gherkin
  Given a principal with a custom role bound to a declared permission set; When authorize is called for an action within it; Then allowed; outside it, denied; a group:gimle:operators member is always allowed everything; a gimle:nodes principal may act on its own node/log endpoints with no RoleBinding needed.
  ```

#### GIMLE-170 — Node-Tenant Assignment Check

- **Category**: Internal-Infra
- **User story**: As a security-conscious operator, I want a node agent's identity to only read a tenant's data if it currently hosts at least one instance for that tenant.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `Authorizer#isTenantAssignedToNode`
- **Test coverage**: `AuthorizerTest#a_node_with_an_active_assignment_for_the_tenant_is_assigned`, `#a_node_with_no_assignment_for_the_tenant_is_not_assigned`, `#a_node_with_no_assignments_at_all_is_not_assigned`
- **Gherkin scenario**:
  ```gherkin
  Given a node with an active instance for tenant "acme"; When isTenantAssignedToNode is checked; Then true; for an unassigned tenant, false.
  ```

#### GIMLE-171 — Five-Field Cron Schedule Evaluator

- **Category**: Config
- **User story**: As a CronJob author, I want standard 5-field cron expressions evaluated correctly, including the day-of-month/day-of-week OR quirk.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.cron.CronSchedule`
- **Test coverage**: `CronScheduleTest#day_of_month_and_day_of_week_both_restricted_combine_with_or`, `#range_and_step_combine`, `#comma_list_matches_any_listed_value`, `#wrong_field_count_throws`, `#inverted_range_throws`, `#zero_step_throws`
- **Gherkin scenario**:
  ```gherkin
  Given a cron expression restricting both day-of-month and day-of-week; When mostRecentDueInstant is evaluated; Then a moment matching either restricted field counts as due; an invalid expression is rejected at parse time.
  ```

#### GIMLE-172 — Deployment Manifest Parsing (incl. Autoscale & Disruption Budget)

- **Category**: Config
- **User story**: As an operator, I want a Deployment manifest's replicas/placement/autoscale/rolling-update budget validated and parsed at submission time.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.manifest.DeploymentManifestParser`, `DeploymentSpec`, `AutoscalePolicy`, `DisruptionBudget`
- **Test coverage**: `DeploymentManifestParserTest#parses_a_weighted_autoscale_block_with_per_signal_weights`, `#accepts_a_nonzero_max_surge`, `#rejects_max_unavailable_0_with_no_max_surge_to_rescue_it`, `#accepts_max_unavailable_0_paired_with_a_nonzero_max_surge_for_a_pure_surge_rollout`, `DisruptionBudgetTest#max_unavailable_and_max_surge_must_not_both_be_0`
- **Gherkin scenario**:
  ```gherkin
  Given a manifest with a weighted autoscale block; When DeploymentManifestParser.parse is called; Then a DeploymentSpec carrying the exact weighted AutoscalePolicy is produced; maxUnavailable=0 with no maxSurge is rejected.
  ```

#### GIMLE-173 — DaemonSet Manifest Parsing (Anti-Affinity/Surge Rejection)

- **Category**: Config
- **User story**: As an operator, I want a DaemonSet manifest to reject anti-affinity and nonzero surge outright.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.manifest.DaemonSetManifestParser`, `DaemonSetSpec`
- **Test coverage**: `DaemonSetManifestParserTest#placement_anti_affinity_field_is_rejected_outright`, `#disruption_max_surge_field_is_rejected_outright_if_nonzero`, `#disruption_max_surge_field_set_to_0_is_accepted`, `#rejects_a_max_unavailable_of_0`
- **Gherkin scenario**:
  ```gherkin
  Given placement.antiAffinity: true; When DaemonSetManifestParser.parse is called; Then parsing throws; a nonzero disruption.maxSurge is likewise rejected while 0 is accepted.
  ```

#### GIMLE-174 — Job / CronJob Manifest Parsing

- **Category**: Config
- **User story**: As an operator, I want Job and CronJob manifests parsed with sensible defaults and validated cron schedules.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.manifest.CronJobManifestParser`, `JobManifestParser`, `CronJobSpec`, `ConcurrencyPolicy`
- **Test coverage**: `CronJobManifestParserTest#parses_a_minimal_manifest_defaulting_backoff_limit_and_concurrency_policy`, `#invalid_cron_schedule_throws`, `#unknown_concurrency_policy_throws`, `JobManifestParserTest#parses_a_minimal_manifest_defaulting_backoff_limit_to_six`
- **Gherkin scenario**:
  ```gherkin
  Given a minimal CronJob manifest with no explicit backoffLimit; When parsed; Then defaults are applied and an invalid cron schedule/unknown concurrencyPolicy is rejected.
  ```

#### GIMLE-175 — StatefulSet Manifest Parsing

- **Category**: Config
- **User story**: As an operator, I want a StatefulSet manifest parsed with the same placement/tenant/vessel shape as other kinds.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.manifest.StatefulSetManifestParser`, `StatefulSetSpec`
- **Test coverage**: `StatefulSetManifestParserTest#parses_a_minimal_manifest`, `#zero_replicas_is_legal`, `#negative_replicas_throws`, `#parses_a_vessel_block`
- **Gherkin scenario**:
  ```gherkin
  Given a manifest declaring replicas/placement/a vessel block; When parsed; Then a StatefulSetSpec is produced; zero replicas is legal, negative rejected.
  ```

#### GIMLE-176 — Kind-Dispatching Manifest Parser

- **Category**: Config
- **User story**: As an API server, I want one entry point that reads a manifest's `kind:` field and dispatches to the right parser.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.manifest.ManifestParser`
- **Test coverage**: `ManifestParserTest#kind_deployment_dispatches_to_deployment_manifest_parser`
- **Gherkin scenario**:
  ```gherkin
  Given a manifest with kind: Deployment; When ManifestParser.parse is called; Then it dispatches to DeploymentManifestParser; an unrecognized kind is rejected via GimleManifestException.unknownKind.
  ```

#### GIMLE-177 — Shared Domain Binary Codec

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want domain types encoded once and shared between the Raft wire format and the client RPC wire format.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.mimir.codec.DomainCodec`
- **Test coverage**: `DomainCodecTest#a_vessel_spec_round_trips_through_the_wire`, `#an_absent_vessel_spec_round_trips_as_empty`, `#a_deployment_spec_with_a_vessel_round_trips`
- **Gherkin scenario**:
  ```gherkin
  Given a DeploymentSpec carrying an optional VesselSpec; When encoded via DomainCodec and decoded back; Then the result equals the original, including the absent-vessel case.
  ```

#### GIMLE-178 — Store Process Bootstrap with TLS Rotation Ticker

- **Category**: Internal/Infra
- **User story**: As an operator, I want the store process to wire up Raft node/RPC transports and a periodic cert-rotation check on startup.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `com.gimle.mimir.StoreMain`
- **Test coverage**: NONE (exercised indirectly by gimle-smoke-tests)
- **Gherkin scenario**:
  ```gherkin
  Given a store process started with a state dir, Raft port, client port; When it starts up; Then it constructs StateStore/RaftLog/RaftNode, binds both transports, and rotates its own cert if due.
  ```

#### GIMLE-179 — Store/Raft Metrics Instrumentation

- **Category**: Internal/Infra
- **User story**: As an operator, I want per-RPC-kind request/error/latency metrics recorded for the store's client-facing RPC handling.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `StoreMain` (`instrumentedStoreNode`), `com.gimle.observability.StoreMetrics`
- **Test coverage**: NONE found in gimle-mimir
- **Gherkin scenario**:
  ```gherkin
  Given a client RPC request handled by StoreNode; When it completes; Then StoreMetrics records the kind, duration, and error status exactly once.
  ```

#### GIMLE-180 — module-info JPMS Boundary for gimle-mimir

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want gimle-mimir's public surface explicitly exported and dependencies declared.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `/home/user/gimle/gimle-mimir/src/main/java/module-info.java`
- **Test coverage**: NONE (structural)
- **Gherkin scenario**:
  ```gherkin
  Given the gimle-mimir module descriptor; When another module requires com.gimle.mimir; Then it can access authz/cron/manifest/store/raft/rpc, nothing unexported.
  ```

#### GIMLE-572 — NetworkPolicySpec durable persistence through StoreClient

- **Category**: Networking/Security
- **User story**: As a platform operator, I want a NetworkPolicySpec I create to survive a control-plane restart and be visible to every control-plane replica, the same durability guarantee ServiceSpec already has, so tenant network policy is never lost or replica-inconsistent.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-mimir/src/main/java/com/gimle/mimir/raft/StateMutation.java` (`PutNetworkPolicy`/`RemoveNetworkPolicy`), `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java` (map, directory persistence, snapshot/restore wiring, YAML (de)serialization), `gimle-mimir/src/main/java/com/gimle/mimir/store/StoreReader.java` (`getNetworkPolicy`/`listNetworkPolicies`), `gimle-mimir/src/main/java/com/gimle/mimir/store/StateSnapshot.java`, `gimle-mimir/src/main/java/com/gimle/mimir/codec/DomainCodec.java` (`writeNetworkPolicySpec`/`readNetworkPolicySpec`), `gimle-mimir/src/main/java/com/gimle/mimir/raft/RaftCodec.java` (mutation/log-entry/snapshot encode-decode), `gimle-mimir/src/main/java/com/gimle/mimir/rpc/StoreRpc.java`, `StoreCodec.java`, `StoreNode.java`, `StoreClient.java` (`GetNetworkPolicy`/`ListNetworkPolicies`), `gimle-controlplane/src/main/java/com/gimle/controlplane/networkpolicy/NetworkPolicyRegistry.java` (rewritten from an in-memory map to a `StoreReader`/`MutationSink` facade), `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java`
- **Test coverage**: `NetworkPolicyRegistryTest` (including two independent registries sharing one store); `ApiServerNetworkPoliciesTest` (multi-replica visibility: a policy POSTed to one `ApiServer` instance is visible via `GET /networkpolicies` on a second independent instance sharing the same store); `gimle-mimir` raft/codec/store round-trip test additions in `RaftCodecTest`, `RaftLogTest`, `RaftNodeSafetyMechanicsTest`, `StateStoreTest`
- **Gherkin scenario**:
  ```gherkin
  Given a NetworkPolicySpec POSTed to one control-plane replica's /networkpolicies API, When a second independent replica backed by the same store queries GET /networkpolicies, Then the policy is visible there too.
  Given a control-plane process restarts, When it reloads its state from the store on startup, Then previously created NetworkPolicySpecs are loaded back, not lost, mirroring ServiceSpec's own persistence guarantee.
  ```

### gimle-fabric

#### GIMLE-181 — Same-Worker Direct Invocation Tier

- **Category**: Service Fabric
- **User story**: As a hosted module, I want a service call to a provider in my own worker JVM served as a direct in-JVM method call.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.registry.FabricServiceRegistry#lookup`
- **Test coverage**: `FabricServiceRegistryTest#same_worker_tier_wins_over_same_machine_and_remote`
- **Gherkin scenario**:
  ```gherkin
  Given a service registered in the same worker's local registry; When lookup(Class) is called; Then the local registry's instance is returned directly, bypassing the catalog entirely.
  ```

#### GIMLE-182 — Same-Machine Unix-Domain-Socket Invocation Tier

- **Category**: Service Fabric
- **User story**: As a hosted module, I want a call to a provider in a different worker on the same machine dispatched over a Unix domain socket.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServiceRegistry#resolveAddress`, `FabricServer#listen`
- **Test coverage**: `FabricServiceRegistryTest#same_machine_tier_wins_over_remote_when_both_are_idle`, `FabricTransportTlsTest#same_machine_unix_domain_socket_path_ignores_tls_config`
- **Gherkin scenario**:
  ```gherkin
  Given a provider in a different worker on the same node; When lookup resolves to that endpoint; Then the call is dispatched over a UDS (always plaintext).
  ```

#### GIMLE-183 — Cross-Machine TCP Invocation Tier

- **Category**: Service Fabric
- **User story**: As a hosted module, I want a call to a provider on a different machine dispatched over TCP (optionally TLS).
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServiceRegistry#resolveAddress`, `FabricClient#call`, `FabricServer#listenTls`
- **Test coverage**: `FabricServiceRegistryTest#least_outstanding_requests_prefers_the_idle_endpoint`, `FabricTransportTlsTest#cross_machine_invocation_succeeds_over_mtls`
- **Gherkin scenario**:
  ```gherkin
  Given a provider on a different node's worker; When lookup resolves to that endpoint; Then the call is dispatched over TCP, TLS when configured.
  ```

#### GIMLE-184 — Locality-Aware Load Balancing with Spillover

- **Category**: Load Balancing
- **User story**: As a platform operator, I want the fabric to prefer same-machine endpoints but spill into remote once every same-machine candidate is busier than the least-loaded remote one.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServiceRegistry#localityAwareCandidates`, `#effectiveLoad`
- **Test coverage**: `FabricServiceRegistryTest#same_machine_tier_spills_over_to_remote_once_saturated`, `#an_open_breaker_on_every_same_machine_endpoint_spills_over_to_a_healthy_remote_endpoint`
- **Gherkin scenario**:
  ```gherkin
  Given several same-machine endpoints saturated and a remote endpoint with spare capacity; When lookup selects a candidate; Then the remote tier is admitted; when a same-machine endpoint is idle, remote is never consulted.
  ```

#### GIMLE-185 — Least-Outstanding-Requests Selection

- **Category**: Load Balancing
- **User story**: As a platform operator, I want traffic within a candidate tier routed to the endpoint with fewest outstanding requests, ties broken round-robin.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.balance.LeastOutstandingRequestsSelector`
- **Test coverage**: `LeastOutstandingRequestsSelectorTest#selects_the_candidate_with_fewest_outstanding_requests`, `#ties_are_broken_round_robin`, `#end_never_goes_negative`, `FabricServiceRegistryTest#least_outstanding_requests_prefers_the_idle_endpoint`
- **Gherkin scenario**:
  ```gherkin
  Given two candidates, one busier; When select is called; Then the less-loaded candidate is chosen; ties round-robin.
  ```

#### GIMLE-186 — Per-Endpoint Circuit Breaker

- **Category**: Circuit Breaking
- **User story**: As a platform operator, I want each remote endpoint protected by its own sliding-window error-rate circuit breaker.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.breaker.CircuitBreaker`
- **Test coverage**: `CircuitBreakerTest#opens_once_error_rate_crosses_threshold_over_the_window`, `#half_opens_after_cooldown_and_allows_exactly_one_trial`, `#half_open_success_closes_the_breaker`, `#half_open_failure_reopens_the_breaker`, `FabricServiceRegistryTest#a_failing_endpoints_breaker_opens_and_is_excluded`
- **Gherkin scenario**:
  ```gherkin
  Given an endpoint's error rate crosses errorRateThreshold; When another call is attempted; Then the breaker opens and excludes it; after cooldown it half-opens for one trial call.
  ```

#### GIMLE-187 — Circuit Breaker Exponential Cooldown Backoff

- **Category**: Circuit Breaking
- **User story**: As a platform operator, I want each consecutive re-open to double the effective cooldown (capped at 16x).
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `CircuitBreaker#effectiveCooldown`, `#MAX_BACKOFF_SHIFT`
- **Test coverage**: `CircuitBreakerTest#repeated_reopens_double_the_effective_cooldown`, `#the_doubling_backoff_stops_at_its_documented_ceiling`, `#a_successful_half_open_trial_resets_the_backoff_to_the_base_cooldown`
- **Gherkin scenario**:
  ```gherkin
  Given a breaker that re-opens repeatedly; When each re-open occurs; Then the cooldown doubles up to a ceiling; a successful half-open trial resets it.
  ```

#### GIMLE-188 — Panic-Mode Ejection Floor

- **Category**: Circuit Breaking
- **User story**: As a platform operator, I want lookup to stop excluding open-breaker endpoints once more than a configured fraction are ejected.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServiceRegistry#selectAllowedCandidate`, `#DEFAULT_MAX_EJECTION_PERCENT`
- **Test coverage**: `FabricServiceRegistryTest#all_endpoints_failing_still_yields_a_candidate_once_the_panic_threshold_is_crossed`, `#no_known_exporter_anywhere_throws_gimle_cluster_exception`
- **Gherkin scenario**:
  ```gherkin
  Given a lookup whose candidates are more than maxEjectionPercent open-breaker; When selectAllowedCandidate runs; Then every candidate is admitted back in; no known exporter anywhere still throws GimleClusterException.
  ```

#### GIMLE-189 — Application-Exception vs Transport-Failure Breaker Scoring

- **Category**: Circuit Breaking
- **User story**: As a platform operator, I want a remote method's own thrown exception counted as a successful (reachable) call for breaker purposes.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServiceRegistry#invokeOverWire`
- **Test coverage**: `FabricServiceRegistryTest#an_endpoint_whose_method_throws_an_application_exception_does_not_open_its_breaker`
- **Gherkin scenario**:
  ```gherkin
  Given a remote endpoint whose method throws an application exception; When the call completes with InvokeError; Then the breaker records a success; only genuine transport failures count against it.
  ```

#### GIMLE-190 — Gossip-Propagated Service Catalog

- **Category**: Service Fabric
- **User story**: As a node agent, I want every locally-registered service export propagated cluster-wide over the SWIM gossip channel, never through the control plane.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.catalog.ServiceCatalog`, `CatalogDelta`, `PiggybackExtension`
- **Test coverage**: `ServiceCatalogTest#a_local_registration_is_immediately_visible`, `#gossip_deltas_round_trip_and_merge_into_a_second_catalog`, `#a_stale_delta_at_a_lower_version_is_ignored`, `#two_different_workers_can_both_export_the_same_interface`
- **Gherkin scenario**:
  ```gherkin
  Given a local registration on one catalog; When its payload is applied to a second catalog's onReceived; Then the second reflects the registration; a stale delta at a lower version is ignored.
  ```

#### GIMLE-191 — Catalog Eviction on Gossip-Detected Node Death

- **Category**: Service Fabric
- **User story**: As a caller, I want a node's catalog entries proactively hidden the moment SWIM converges on it as DEAD.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ServiceCatalog#onMembershipChange`, `#unavailableNodes`
- **Test coverage**: `GossipMemberTest#a_node_marked_dead_via_gossip_has_its_catalog_entries_evicted_without_a_breaker_trip`
- **Gherkin scenario**:
  ```gherkin
  Given a member gossip converges on as DEAD; When ServiceCatalog#onMembershipChange receives it; Then endpointsForInterface no longer returns any of its endpoints; once ALIVE again, endpoints reappear with no re-registration.
  ```

#### GIMLE-192 — Cross-Tenant Service Export Access Control

- **Category**: Service Fabric
- **User story**: As a multi-tenant platform operator, I want a service export's `allowedTenantIds` allow-list enforced at lookup time, with opt-in default-deny for unscoped exports.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServiceRegistry#permitsUnderTenantPolicy`, `#defaultDenyCrossTenant`
- **Test coverage**: `FabricServiceRegistryTest#a_caller_belonging_to_an_allowed_tenant_reaches_a_restricted_export`, `#a_caller_from_a_different_tenant_cannot_reach_a_restricted_export`, `#a_tenanted_caller_cannot_reach_an_unrestricted_export_with_default_deny_cross_tenant_on`, `FabricServiceRegistryInvokeByNameTest#a_caller_from_a_different_tenant_cannot_invoke_a_restricted_export_by_name`
- **Gherkin scenario**:
  ```gherkin
  Given an export restricted to tenant "acme"; When a caller from a different tenant looks it up; Then lookup returns empty; with defaultDenyCrossTenant on, an unscoped export is reachable only by an untenanted caller.
  ```

#### GIMLE-193 — Runtime Name-Driven Cross-Tier Invocation (invokeByName)

- **Category**: Service Fabric
- **User story**: As a gateway module resolving routes at runtime, I want to invoke a service by interface name/version/method rather than a compile-time Class<T>.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServiceRegistry#invokeByName`, `#invokeLocalByName`, `com.gimle.fabric.transport.ReflectiveDispatch`
- **Test coverage**: `FabricServiceRegistryInvokeByNameTest#a_same_worker_registration_is_invoked_directly_by_name`, `#a_same_machine_registration_is_invoked_over_the_wire_by_name`, `#a_remote_registration_is_invoked_over_the_wire_by_name`, `#wrong_param_type_names_fail_clearly_rather_than_hanging_or_matching_a_wrong_overload`
- **Gherkin scenario**:
  ```gherkin
  Given a route naming an interface/version/method/param types; When invokeByName is called; Then it resolves the same tier/breaker/tenant logic dispatched by name; an unresolvable method name fails clearly.
  ```

#### GIMLE-194 — Inbound Call Dispatch with Bounded Concurrency

- **Category**: Service Fabric
- **User story**: As a worker JVM, I want inbound fabric calls routed through the target module's own bounding scheduler.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.transport.FabricServer#invokeLocally`, `#invokeBounded`, `ModuleWorkExecutor`
- **Test coverage**: `FabricServerTest#a_real_inbound_call_is_visible_in_the_targets_in_flight_count_while_it_runs`, `#concurrent_calls_are_bounded_by_the_targets_executor_not_run_unbounded`, `#real_calls_are_recorded_in_the_targets_worker_metrics_including_errors`
- **Gherkin scenario**:
  ```gherkin
  Given a target module with a bounded ModuleWorkExecutor; When more concurrent inbound calls arrive than allowed; Then extra calls queue; ModuleContext's in-flight counter reflects real inbound calls.
  ```

#### GIMLE-195 — Distributed Trace Propagation Across Fabric Hops

- **Category**: Service Fabric
- **User story**: As an operator, I want a caller's OpenTelemetry span/baggage propagated across a cross-worker or cross-machine fabric call.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServiceRegistry#captureTrace`, `#encodeTraceState`, `#encodeBaggage`, `FabricServer#startChildSpanContext`, `com.gimle.fabric.trace.TraceContext`
- **Test coverage**: `FabricServerTest#baggage_from_the_caller_survives_an_inbound_call_into_the_handler`, `#has_remote_span_distinguishes_a_real_caller_span_from_the_no_active_span_marker`, `FabricServerGlobalTracingTest#a_call_with_no_active_caller_span_starts_a_fresh_valid_trace_not_the_all_zero_marker`, `transport/FabricCodecTest#round_trips_a_non_empty_tracestate_and_baggage`
- **Gherkin scenario**:
  ```gherkin
  Given a caller with an active span and baggage; When it invokes a remote service; Then the callee starts a child span parented on the caller's real span, observing the same baggage.
  ```

#### GIMLE-196 — Fabric Transport over Mutual TLS with Hot Cert Reload

- **Category**: Service Fabric
- **User story**: As a security-conscious operator, I want cross-machine fabric calls authenticated by mutual TLS, hot-reloadable.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FabricServer#listenTls`, `#reloadTlsMaterial`, `FabricClient#call`
- **Test coverage**: `FabricTransportTlsTest#cross_machine_invocation_succeeds_over_mtls`, `#cross_machine_call_is_rejected_when_client_trusts_a_different_ca`
- **Gherkin scenario**:
  ```gherkin
  Given fabric configured for mTLS; When a cross-machine invocation is made; Then it succeeds over TLS; a client trusting a different CA is rejected; reload lets a fresh connection succeed without restart.
  ```

#### GIMLE-197 — Fabric Call Timeout Enforcement

- **Category**: Internal/Infra
- **User story**: As a caller, I want a cross-hop call bounded by one overall timeout regardless of transport.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.transport.FabricClient#runBounded`, `#DEFAULT_TIMEOUT`
- **Test coverage**: `FabricClientTest#a_peer_that_accepts_but_never_responds_times_out_within_the_configured_bound`, `#a_refused_connection_fails_fast_without_waiting_out_the_timeout`
- **Gherkin scenario**:
  ```gherkin
  Given a peer that accepts but never responds; When FabricClient.call is made; Then it fails with SocketTimeoutException within the bound; a refused connection fails fast.
  ```

#### GIMLE-198 — Fabric Frame Wire Codec

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want cross-hop invocation requests/responses encoded compactly and defensively.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.transport.FabricCodec`
- **Test coverage**: `FabricCodecTest#round_trips_through_streams`, `#round_trips_a_non_empty_tracestate_and_baggage`, `#rejects_an_oversized_length_prefix_before_allocating`, `#rejects_a_forged_huge_param_count_before_allocating`
- **Gherkin scenario**:
  ```gherkin
  Given a FabricFrame.InvokeRequest with tracestate/baggage; When written and read back through FabricCodec; Then the decoded frame equals the original; forged length/param counts are rejected before allocating.
  ```

#### GIMLE-199 — Cross-JVM Object Marshalling

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want method arguments/return values/exceptions serialized via plain Java serialization for cross-hop calls.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `com.gimle.fabric.transport.ObjectMarshalling`
- **Test coverage**: NONE dedicated (exercised indirectly by `FabricServiceRegistryTest`/`FabricServerTest`)
- **Gherkin scenario**:
  ```gherkin
  Given an object graph as a fabric method argument; When serialize then deserialize is applied; Then the result is an equivalent object graph, trusted only within the same platform trust boundary (no ObjectInputFilter allowlist).
  ```

#### GIMLE-200 — SWIM Gossip Membership Protocol (Ping/PingReq/Ack)

- **Category**: Gossip Membership
- **User story**: As a node agent, I want cluster membership tracked via SWIM's ping/indirect-ping/ack protocol independent of the control plane.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.cluster.GossipMember#tick`, `#pingRandomMember`, `#escalate`, `#handle`
- **Test coverage**: `GossipMemberTest#two_nodes_discover_each_other_via_join`, `#a_killed_member_converges_to_dead_across_the_rest`, `#a_lone_node_with_no_seeds_starts_as_a_new_cluster`, `#a_single_unreachable_seed_is_a_legitimate_bootstrap_not_an_error`, `#multiple_unreachable_seeds_throw_gimle_cluster_exception`
- **Gherkin scenario**:
  ```gherkin
  Given two node agents configured with each other as a seed; When they join; Then each discovers the other via direct ping/ack; a killed member converges to DEAD via direct probe timeout escalating to indirect PingReq relays.
  ```

#### GIMLE-201 — SWIM Self-Refutation via Incarnation Bump

- **Category**: Gossip Membership
- **User story**: As a node being falsely suspected, I want to automatically refute the suspicion by gossiping a higher incarnation of myself.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GossipMember#refuteIfNeeded`, `#mergeOne`
- **Test coverage**: `GossipMemberTest#a_member_refutes_a_suspicion_of_itself_by_bumping_incarnation`, `#a_stale_suspicion_below_the_current_incarnation_is_ignored`
- **Gherkin scenario**:
  ```gherkin
  Given a member observes a piggyback entry naming itself as SUSPECT; When processed; Then it bumps its own incarnation and re-gossips as ALIVE; a stale suspicion below the current incarnation is ignored.
  ```

#### GIMLE-202 — Lifeguard-Style Local Health Multiplier

- **Category**: Gossip Membership
- **User story**: As a cluster, I want a node whose own probes keep timing out to scale up its own timeouts, so it doesn't flood the cluster with false suspicions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GossipMember#bumpLocalHealthMultiplier`, `#decayLocalHealthMultiplier`, `#MAX_LOCAL_HEALTH_MULTIPLIER`
- **Test coverage**: `GossipMemberTest#the_local_health_multiplier_clamps_rather_than_growing_unbounded`
- **Gherkin scenario**:
  ```gherkin
  Given a node whose probes repeatedly time out; When each timeout occurs; Then its local health multiplier increases (clamped at a ceiling); a successfully resolved probe decays it back down.
  ```

#### GIMLE-203 — Round-Robin Bounded-Coverage Probe Target Selection

- **Category**: Gossip Membership
- **User story**: As a cluster, I want every live member probed at least once per protocol-period cycle.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GossipMember#nextProbeTarget`, `#probeOrder`
- **Test coverage**: `GossipMemberTest#probe_target_selection_visits_every_live_member_within_one_cycle`
- **Gherkin scenario**:
  ```gherkin
  Given N live members besides self; When N consecutive ticks each call nextProbeTarget; Then every live member is visited exactly once before the queue reshuffles.
  ```

#### GIMLE-204 — Anti-Entropy Full-State Sync

- **Category**: Gossip Membership
- **User story**: As a cluster, I want a periodic full-membership-table push-pull sync so a lagging node still eventually converges.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GossipMember#maybeSyncWithRandomMember`, `#currentFullState`, `SwimMessage.SyncRequest`/`SyncResponse`
- **Test coverage**: `GossipMemberTest#anti_entropy_sync_delivers_a_change_piggyback_alone_cannot_carry`
- **Gherkin scenario**:
  ```gherkin
  Given a change piggyback alone never delivered to a lagging node; When the antiEntropyInterval elapses and a sync fires to a random peer; Then the full table is exchanged and the lagging node picks up the missed change.
  ```

#### GIMLE-205 — Dead-Member Reaping

- **Category**: Gossip Membership
- **User story**: As a cluster, I want a member DEAD longer than a configured window forgotten from the membership table.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GossipMember#reapExpiredDeadMembers`
- **Test coverage**: `GossipMemberTest#a_long_dead_member_is_eventually_forgotten_not_kept_forever`
- **Gherkin scenario**:
  ```gherkin
  Given a member DEAD longer than deadMemberReapAfter; When the next tick's reapExpiredDeadMembers runs; Then the member is removed entirely.
  ```

#### GIMLE-206 — Gossip over Mutual DTLS with Deterministic Initiator Selection

- **Category**: Gossip Membership
- **User story**: As a security-conscious operator, I want gossip UDP traffic encrypted/authenticated via DTLS when TLS mode is configured.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GossipMember#sendSecure`, `#isDesignatedInitiator`, `#handleSecureDatagram`, `#reloadDtlsMaterial`, `com.gimle.fabric.cluster.DtlsPeerSession`
- **Test coverage**: `GossipMemberDtlsTest#two_nodes_discover_each_other_over_mutual_dtls`, `#a_killed_member_still_converges_to_dead_over_dtls`, `#members_trusting_different_cas_never_become_mutually_aware`, `#a_member_reaches_a_new_peer_over_dtls_after_reloading_rotated_material`
- **Gherkin scenario**:
  ```gherkin
  Given two nodes configured for DTLS gossip; When they exchange pings; Then they discover each other over mutual DTLS, with only the lexicographically-lower-addressed side originating the handshake; different-CA members never become mutually aware; reloaded material lets a node reach a new peer.
  ```

#### GIMLE-207 — SWIM Wire Codec

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want SWIM protocol messages encoded compactly over UDP with defensive bounds against malformed datagrams.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.cluster.SwimCodec`
- **Test coverage**: `SwimCodecTest#round_trips_through_a_datagram`, `#a_forged_huge_piggyback_count_fails_cleanly_instead_of_preallocating`, `#rejects_an_unrecognized_version_before_decoding_the_tag`
- **Gherkin scenario**:
  ```gherkin
  Given any SwimMessage variant; When encoded and decoded via SwimCodec; Then the decoded value equals the original; a forged huge piggyback count or unrecognized protocol version fails cleanly.
  ```

#### GIMLE-208 — Service Catalog Delta Wire Codec

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want catalog deltas encoded defensively so a forged delta count can't force unbounded allocation.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `com.gimle.fabric.catalog.ServiceCatalogCodec`
- **Test coverage**: `ServiceCatalogCodecTest#round_trips_a_catalog_delta`, `#round_trips_an_empty_delta_list`, `#a_forged_huge_delta_count_fails_cleanly_instead_of_preallocating`
- **Gherkin scenario**:
  ```gherkin
  Given a list of CatalogDelta values; When encoded/decoded via ServiceCatalogCodec; Then the round trip is exact; a forged huge delta count fails cleanly.
  ```

#### GIMLE-209 — Reflective Cross-Module Method Dispatch

- **Category**: Internal/Infra
- **User story**: As the fabric transport, I want to resolve and invoke a wire-carried interface/method/parameter-type triple against a module-private service interface without a shared platform-layer classloader.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `com.gimle.fabric.transport.ReflectiveDispatch#findInterface`, `#resolveParamTypes`, `FabricServer#invokeLocally`
- **Test coverage**: Exercised indirectly through `FabricServiceRegistryInvokeByNameTest`/`FabricServerTest` — NONE dedicated
- **Gherkin scenario**:
  ```gherkin
  Given an inbound call naming an interface/method/param types belonging to a module-private interface; When ReflectiveDispatch resolves and FabricServer invokes it; Then it resolves against the instance's own declaring interface, avoiding IllegalAccessException.
  ```

#### GIMLE-210 — module-info JPMS Boundary for gimle-fabric

- **Category**: Internal/Infra
- **User story**: As a platform engineer, I want gimle-fabric's public surface explicitly exported and its dependency on gimle-module explicit.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `/home/user/gimle/gimle-fabric/src/main/java/module-info.java`
- **Test coverage**: NONE (structural)
- **Gherkin scenario**:
  ```gherkin
  Given the gimle-fabric module descriptor; When gimle-worker requires com.gimle.fabric; Then it can access cluster/catalog/registry/transport/balance/breaker/trace, nothing unexported.
  ```

#### GIMLE-567 — Fabric listener-side tenant re-check on inbound service calls

- **Category**: Fabric / Multi-tenancy
- **User story**: As a platform operator, I want the receiving worker of a cross-hop fabric call to independently re-verify the caller's tenant against the target service's own declared allowedTenantIds, so a caller cannot bypass tenant scoping by dialing a raw ServiceEndpoint address directly instead of going through FabricServiceRegistry's own caller-side filter.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricServer.java` (`dispatch`/`checkTenantPermitted`), `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricFrame.java` (`InvokeRequest.callerTenantId`), `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricCodec.java`, `gimle-core/src/main/java/com/gimle/core/exception/GimleFabricAuthorizationException.java`
- **Test coverage**: `FabricServerTest` (a_caller_bypassing_the_registry_and_dialing_directly_is_rejected_by_the_listeners_own_check, an_untenanted_caller_is_rejected_against_a_restricted_export, a_caller_from_the_allowed_tenant_is_permitted_through_the_listeners_own_check, an_export_with_no_tenant_restriction_permits_any_caller); `FabricCodecTest`'s `callerTenantId` round-trip assertions
- **Gherkin scenario**:
  ```gherkin
  Given a module exporting an interface restricted to allowedTenantIds ["tenant-a"], When a caller wire-carrying callerTenantId "tenant-b" dials the ServiceEndpoint's raw address directly (bypassing FabricServiceRegistry's own caller-side filter), Then FabricServer.dispatch independently rejects the call with GimleFabricAuthorizationException.
  Given the same restricted export, When a caller carrying callerTenantId "tenant-a" dials directly, Then the listener's own re-check permits the call through.
  Given an export with no allowedTenantIds restriction, When any caller (including an untenanted one) dials directly, Then the call is permitted -- the re-check enforces exactly what the module declared, never a stricter default.
  ```

#### GIMLE-574 — Per-deployment-scoped NetworkPolicySpec enforcement

- **Category**: Networking/Security
- **User story**: As a platform operator, I want a NetworkPolicySpec scoped to specific deployments (not the whole tenant) to actually restrict cross-tenant traffic against just those deployments, so I can apply a narrower policy than tenant-wide without it silently going unenforced.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/tenant/NetworkPolicyRule.java` (`deploymentNames`, `appliesToDeployment`), `gimle-core/src/main/java/com/gimle/core/protocol/ControlMessageCodec.java` (`encodeNetworkPolicies`/`decodeNetworkPolicies`), `gimle-agent/src/main/java/com/gimle/agent/networkpolicy/HttpNetworkPolicySource.java` (relays deployment-scoped rules, no longer filters them out), `gimle-agent/src/main/java/com/gimle/agent/NetworkPolicyRelay.java`, `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricServer.java` (`deploymentNameOf`, `checkNetworkPolicyPermitted`), `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`bindFabricServer` wires `InstanceIdentityRegistry` through as `deploymentNameOf`)
- **Test coverage**: `NetworkPolicyRuleTest` (deployment-scoping predicate: matches its named deployment, never matches a different one, never matches an unknown one, tenant-wide rule matches everything); `HttpNetworkPolicySourceTest` (parses both an empty deploymentNames array as tenant-wide and a populated one as scoped); `FabricServerTest` (a_deployment_scoped_network_policy_restricts_a_call_targeting_that_deployment, a_deployment_scoped_network_policy_never_restricts_a_call_targeting_a_different_deployment, a_deployment_scoped_network_policy_never_restricts_a_target_with_no_known_deployment_name); `ControlMessageCodecTest`'s round-trip list now includes a deployment-scoped rule
- **Gherkin scenario**:
  ```gherkin
  Given a NetworkPolicySpec scoped to deployment "orders-service" only, When a cross-tenant caller not on its allow list invokes a service hosted by an instance of that exact deployment, Then FabricServer.checkNetworkPolicyPermitted rejects the call.
  Given the same deployment-scoped policy, When the same caller invokes a service hosted by an instance of a different deployment in the same tenant, Then the call is permitted -- the scoped policy never restricts a deployment it doesn't name.
  Given the same deployment-scoped policy, When the target instance has no deployment identity registered at all, Then the call is permitted -- a scoped rule can only be proven to apply, never assumed to.
  ```

### gimle-controlplane

#### GIMLE-211 — First-fit-decreasing bin-packing scheduler

- **Category**: Scheduling
- **User story**: As a control-plane operator, I want instances placed on the node with the most free capacity among feasible candidates, so that cluster load stays balanced without manual placement.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/schedule/Scheduler.java`, `NodeCandidate.java`
- **Test coverage**: `SchedulerTest` — `places_on_the_only_feasible_node`, `prefers_the_node_with_more_free_capacity`, `throws_when_no_node_has_enough_free_capacity`
- **Gherkin scenario**:
  ```gherkin
  Given multiple registered nodes with differing free memory/CPU; When a replica is placed for a deployment; Then the node with the most free memory (tie-broken by free CPU) among tier-eligible, uncordoned nodes is chosen.
  ```

#### GIMLE-212 — Isolation-tier placement filtering

- **Category**: Scheduling
- **User story**: As an operator, I want instances only scheduled onto nodes that support the requested isolation tier, so that Tier 2/3 guarantees are never silently downgraded.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `Scheduler.filterByTier`
- **Test coverage**: `SchedulerTest` — `rejects_a_node_that_does_not_support_the_requested_tier`, `throws_when_no_node_supports_the_requested_tier`
- **Gherkin scenario**:
  ```gherkin
  Given a node that does not declare support for TIER_2; When a TIER_2 replica is placed; Then that node is excluded and placement fails if no other node supports the tier.
  ```

#### GIMLE-213 — Node cordon exclusion

- **Category**: Scheduling
- **User story**: As an operator, I want to mark a node as unschedulable without evicting what's already running there, so I can drain it safely for maintenance.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `Scheduler.filterByCordon`; `ApiServer.handleCordon` (`POST /nodes/{id}/cordon|uncordon`)
- **Test coverage**: `SchedulerTest` — `cordon_excludes_a_cordoned_node_from_placement`, `cordon_fails_outright_when_every_capable_node_is_cordoned`; `DaemonSetReconcilerTest#cordoning_a_node_removes_its_assignment_on_the_next_tick`
- **Gherkin scenario**:
  ```gherkin
  Given a node marked cordoned; When a new replica needs placement; Then the cordoned node is excluded from candidacy, but its existing instances are untouched.
  ```

#### GIMLE-214 — Strict anti-affinity across nodes

- **Category**: Scheduling
- **User story**: As an operator, I want replicas of one deployment spread across distinct nodes when anti-affinity is requested, so a single node failure can't take down every replica.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `Scheduler.filterByAntiAffinity`, `GimleSchedulingException.antiAffinityViolated`
- **Test coverage**: `SchedulerTest` — `anti_affinity_excludes_nodes_already_running_a_replica_of_the_same_deployment`, `anti_affinity_fails_outright_rather_than_placing_on_an_occupied_node`
- **Gherkin scenario**:
  ```gherkin
  Given anti-affinity is requested and every eligible node already runs a replica; When another replica is placed; Then placement fails outright rather than co-locating.
  ```

#### GIMLE-215 — Tier 2/3 node-level tenant isolation

- **Category**: Scheduling / Multi-tenancy
- **User story**: As a platform operator, I want Tier 2/3 instances from different tenants kept off the same node, so compliance-sensitive workloads get physical separation.
- **Status**: Complete (documented one-directional asymmetry across other reconcilers' tenant checks, not a bug)
- **Confidence**: High
- **Source location(s)**: `Scheduler.filterByTenant`/`enforcesTenantIsolation`; `DeploymentReconciler.buildCandidates`
- **Test coverage**: `SchedulerTest` — `tenant_isolation_permits_a_node_already_running_the_same_tenant`, `tenant_isolation_fails_outright_when_every_capable_node_hosts_a_different_tenant`
- **Gherkin scenario**:
  ```gherkin
  Given a node already running Tier 2 instances for tenant A; When a Tier 2 replica for tenant B is scheduled; Then that node is excluded; if every candidate hosts a different tenant, placement fails outright.
  ```

#### GIMLE-216 — Required node-label placement constraint

- **Category**: Scheduling
- **User story**: As an operator, I want to require a deployment land only on nodes carrying specific labels, so hardware/location-specific workloads reach the right machines.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `Scheduler.filterByLabels`
- **Test coverage**: `SchedulerTest` — `required_labels_excludes_a_node_missing_one_of_them`, `required_labels_fails_outright_when_no_capable_node_carries_them`
- **Gherkin scenario**:
  ```gherkin
  Given a manifest declares placement.requiredLabels; When placement runs; Then only nodes carrying every required label are candidates; fails outright if none qualify.
  ```

#### GIMLE-217 — StatefulSet sticky node placement

- **Category**: Scheduling / Orchestration
- **User story**: As an operator running a stateful workload, I want each replica index to always return to the same node once placed there, so its local-disk volume is never orphaned.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `Scheduler.place` (stickyNodeId overload), `Scheduler.placeSticky`, `GimleSchedulingException.stickyNodeUnavailable`
- **Test coverage**: `SchedulerTest` — `sticky_placement_returns_the_sticky_node_even_when_a_roomier_node_exists`, `sticky_placement_fails_outright_rather_than_choosing_a_different_node_when_sticky_is_gone`
- **Gherkin scenario**:
  ```gherkin
  Given index 3 was previously placed on node-A; When index 3 needs re-placement; Then it is placed on node-A again if eligible, and fails outright (never relocated) if node-A is unavailable.
  ```

#### GIMLE-218 — DaemonSet eligible-node enumeration (`eligibleNodes`)

- **Category**: Scheduling
- **User story**: As an operator running a DaemonSet, I want it placed on every eligible node cluster-wide rather than bin-packed onto one.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `Scheduler.eligibleNodes`; `DaemonSetReconciler.reconcileDaemonSet`
- **Test coverage**: `SchedulerTest` — `eligible_nodes_returns_every_node_that_passes_every_filter`, `eligible_nodes_returns_an_empty_list_rather_than_throwing_when_nothing_qualifies`; `DaemonSetReconcilerTest#places_an_assignment_on_every_registered_node`
- **Gherkin scenario**:
  ```gherkin
  Given several nodes, some cordoned, some missing required labels; When DaemonSetReconciler computes eligible nodes; Then every node passing tier/cordon/tenant/label filters is returned, no single-winner pick.
  ```

#### GIMLE-219 — Deployment replica reconciliation (level-triggered)

- **Category**: Reconciliation
- **User story**: As an operator, I want actual running instances to always converge to my declared replica count, regardless of prior history.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/DeploymentReconciler.java`
- **Test coverage**: `DeploymentReconcilerTest` — `creates_assignments_for_every_missing_index_when_capacity_exists`, `an_arbitrary_starting_snapshot_converges_the_same_as_a_fresh_reconcile`
- **Gherkin scenario**:
  ```gherkin
  Given a deployment declares 3 replicas and only 1 assignment exists; When DeploymentReconciler ticks; Then 2 more are placed; an arbitrary starting snapshot converges to the identical result a fresh reconcile would produce.
  ```

#### GIMLE-220 — Deployment scale-down

- **Category**: Reconciliation
- **User story**: As an operator, I want reducing replica count to immediately remove excess assignments.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `DeploymentReconciler.reclaimStaleAssignments`
- **Test coverage**: `DeploymentReconcilerTest#scale_down_removes_assignments_at_or_beyond_the_new_replica_count`
- **Gherkin scenario**:
  ```gherkin
  Given 5 assignments and replicas lowered to 3; When DeploymentReconciler ticks; Then assignments for indices 3 and 4 are removed.
  ```

#### GIMLE-221 — Artifact-hash drift detection at reconcile time

- **Category**: Reconciliation / Internal-Infra
- **User story**: As a platform operator, I want the reconciler to refuse new placements if a deployment's on-disk artifact no longer matches the hash recorded at admission.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `DeploymentReconciler.validateArtifact`
- **Test coverage**: `DeploymentReconcilerTest` — `places_new_instances_when_the_recorded_artifact_hash_still_matches_the_jar_on_disk`, `refuses_to_place_new_instances_once_the_jar_on_disk_no_longer_matches_the_recorded_hash`
- **Gherkin scenario**:
  ```gherkin
  Given a deployment admitted with artifactSha256 recorded; When the jar is later swapped for different bytes; Then no new instance is placed until resubmitted.
  ```

#### GIMLE-222 — Rolling update via mismatched-index migration

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator, I want deploying a new module version to gradually replace old-version instances up to `maxUnavailable` at a time.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `DeploymentReconciler.handleRollingUpdate`, `mismatchedAssignments`, `isReady`
- **Test coverage**: `DeploymentReconcilerRollingUpdateTest` (convergence-from-arbitrary-state coverage present)
- **Gherkin scenario**:
  ```gherkin
  Given 5 running old-moduleId instances and disruption.maxUnavailable=1; When the spec's moduleId changes; Then exactly 1 index is replaced at a time, waiting for readiness before the next.
  ```

#### GIMLE-223 — Rolling update surge (maxSurge)

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator, I want a rollout to optionally provision replacement instances before tearing down old ones, so the deployment never drops below its declared count.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `DeploymentReconciler.handleSurge`, `nextFreeSurgeIndex`, `StateMutation.PutAssignment` (`renamedFromInstanceIndex`)
- **Test coverage**: `DeploymentReconcilerSurgeTest`
- **Gherkin scenario**:
  ```gherkin
  Given disruption.maxSurge=1 and an old-version instance at index 2; When a rollout starts; Then a synthetic surge index is placed with the new version, promoted onto index 2 once ready, and the old assignment removed.
  ```

#### GIMLE-224 — Node-death instance reclamation (`ReplicaCountReconciler`)

- **Category**: Reconciliation / Self-healing
- **User story**: As an operator, I want an instance whose node has gone dark to be released for re-placement after a grace period.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/ReplicaCountReconciler.java`
- **Test coverage**: `ReplicaCountReconcilerTest` (grace-period and persisted-state convergence tests present)
- **Gherkin scenario**:
  ```gherkin
  Given an assignment's node hasn't heartbeated within nodeDarkTimeout, persisted beyond placementGracePeriod; When ReplicaCountReconciler ticks; Then the assignment is removed, freeing re-placement.
  ```

#### GIMLE-225 — Persisted grace-period bookkeeping (survives leader failover)

- **Category**: Reconciliation / Internal-Infra
- **User story**: As an operator, I want the "how long missing" timer to survive a reconciler-leader failover, so a fresh leader doesn't restart the grace period.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ReplicaCountReconciler` via `ReconcilerInstanceState`/`StoreReader.getReconcilerInstanceState`
- **Test coverage**: `ReplicaCountReconcilerTest`; `HealthReconcilerTest#backoff_state_survives_a_reconciler_reconstruction_against_the_same_store`
- **Gherkin scenario**:
  ```gherkin
  Given an instance missing for half of placementGracePeriod when the leader lease changes hands; When the new leader reconstructs from the store; Then it resumes counting from the persisted timestamp, not zero.
  ```

#### GIMLE-226 — Unhealthy-instance backoff-gated reschedule (`HealthReconciler`)

- **Category**: Reconciliation / Self-healing
- **User story**: As an operator, I want an instance reported unhealthy to be rescheduled with exponential backoff, so a persistently crashing replica isn't bounced in a tight loop.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/HealthReconciler.java`, `RestartTracker`
- **Test coverage**: `HealthReconcilerTest` — `an_unhealthy_instance_is_rescheduled_once_its_backoff_elapses`, `repeated_failures_across_reschedules_eventually_exhaust_the_budget_and_stop_retrying`, `converges_correctly_from_an_arbitrary_mix_of_persisted_backoff_states`
- **Gherkin scenario**:
  ```gherkin
  Given an instance's heartbeat reports alive=false or lifecycleState=FAILED; When HealthReconciler ticks; Then it is rescheduled after growing delay; once maxAttemptsPerWindow is exhausted it's marked permanently failed.
  ```

#### GIMLE-227 — Readiness-only failures never trigger reschedule

- **Category**: Reconciliation
- **User story**: As an operator, I want a not-yet-ready instance left running rather than torn down.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `HealthReconciler.isHealthy`
- **Test coverage**: `HealthReconcilerTest#readiness_alone_never_triggers_a_reschedule`
- **Gherkin scenario**:
  ```gherkin
  Given an instance reports ready=false but alive=true and lifecycleState != FAILED; When HealthReconciler ticks; Then no reschedule action is taken.
  ```

#### GIMLE-228 — Tenant quota drift detection (`QuotaReconciler`)

- **Category**: Reconciliation / Multi-tenancy
- **User story**: As a platform operator, I want a deployment marked quota-violating whenever its tenant's actual usage exceeds quota, without automatic eviction.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/QuotaReconciler.java`, `TenantUsage`
- **Test coverage**: `QuotaReconcilerTest` — `marks_a_deployment_violating_when_its_tenant_exceeds_quota`, `clears_a_violation_once_the_quota_is_raised_again_convergence_from_arbitrary_state`, `proposes_exactly_once_when_a_violation_is_introduced_then_nothing_more_while_it_persists`
- **Gherkin scenario**:
  ```gherkin
  Given a tenant's quota is lowered below already-running usage; When QuotaReconciler ticks; Then affected deployments are marked quota-violating with no instances evicted.
  ```

#### GIMLE-229 — Horizontal autoscaling — multi-signal (`AutoscaleReconciler`)

- **Category**: Reconciliation / Scheduling
- **User story**: As an operator, I want replica count to auto-adjust based on CPU, request rate, error rate, and queue depth.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/autoscale/AutoscaleReconciler.java`
- **Test coverage**: `AutoscaleReconcilerTest` — `scales_up_by_one_replica_per_tick_under_sustained_high_utilization`, `queue_depth_alone_can_drive_scale_up_when_cpu_is_under_target`, `converges_correctly_from_an_arbitrary_out_of_range_persisted_replica_count`
- **Gherkin scenario**:
  ```gherkin
  Given a policy targeting CPU and queue depth, WORST_SIGNAL mode; When queue depth implies more replicas than CPU; Then effective count moves toward the worst signal's ideal, one replica per tick, clamped to [min,max].
  ```

#### GIMLE-230 — Autoscaling WEIGHTED combination mode

- **Category**: Reconciliation / Scheduling
- **User story**: As an operator, I want to blend multiple autoscale signals into one weighted ratio instead of taking the worst.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AutoscaleReconciler.computeWeightedIdeal`
- **Test coverage**: `AutoscaleReconcilerTest` — `weighted_mode_blends_two_signals_instead_of_taking_the_max`, `weighted_mode_with_no_weights_configured_behaves_like_an_unweighted_average`
- **Gherkin scenario**:
  ```gherkin
  Given combinationMode=WEIGHTED with per-signal weights; When two signals disagree; Then effective count is driven by the weighted-average blended ratio.
  ```

#### GIMLE-231 — DaemonSet reconciliation and rolling update

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator, I want a DaemonSet's per-node instance kept in sync with eligible nodes and rolled forward on version changes.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/DaemonSetReconciler.java`
- **Test coverage**: `DaemonSetReconcilerTest#rolling_update_replaces_one_node_at_a_time_and_waits_for_readiness`
- **Gherkin scenario**:
  ```gherkin
  Given an old module version on 3 nodes; When the spec's moduleId changes; Then nodes migrate one at a time, waiting for readiness.
  ```

#### GIMLE-232 — DaemonSet dark-node placement-safety grace period

- **Category**: Reconciliation / Self-healing
- **User story**: As an operator, I want a briefly-partitioned node's assignment left alone rather than torn down.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `DaemonSetReconciler.isMerelyDarkWithinGracePeriod`
- **Test coverage**: `DaemonSetReconcilerTest#a_replica_on_a_dark_but_not_yet_timed_out_node_is_not_relocated`, `cordoning_a_dark_node_still_removes_its_assignment_immediately`
- **Gherkin scenario**:
  ```gherkin
  Given a node's heartbeat is stale but within nodeDarkTimeout+placementGracePeriod; When DaemonSetReconciler ticks; Then the assignment is left in place.
  ```

#### GIMLE-233 — StatefulSet OrderedReady placement

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator running a stateful workload, I want index i+1 never placed until index i reports ready.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/StatefulSetReconciler.java`
- **Test coverage**: `StatefulSetReconcilerTest` — `does_not_place_index_one_until_index_zero_reports_ready`, `places_index_one_once_index_zero_becomes_ready`
- **Gherkin scenario**:
  ```gherkin
  Given index 0 is not yet ready; When StatefulSetReconciler ticks; Then index 1 is never placed.
  ```

#### GIMLE-234 — StatefulSet one-index-at-a-time scale-down

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator, I want scaling down to remove exactly one (highest) index per tick.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StatefulSetReconciler.scaleDownOneIndexIfNeeded`
- **Test coverage**: `StatefulSetReconcilerTest#scale_down_removes_the_highest_index_first_one_at_a_time`
- **Gherkin scenario**:
  ```gherkin
  Given 5 indices and replicas lowered to 2; When StatefulSetReconciler ticks; Then only index 4 is removed this tick.
  ```

#### GIMLE-235 — JobRun run-to-completion reconciliation

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator, I want a Job to run to completion with automatic retry up to backoffLimit.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/JobReconciler.java`
- **Test coverage**: `JobReconcilerTest` — `a_failed_observation_retries_the_next_attempt_when_backoff_budget_remains`, `exhausting_the_backoff_limit_marks_the_job_permanently_failed`, `an_arbitrary_starting_snapshot_with_two_coexisting_runs_converges_to_the_highest_attempt`
- **Gherkin scenario**:
  ```gherkin
  Given a Job's current attempt fails and backoffLimit allows another; When JobReconciler ticks; Then a new attempt is placed; once exhausted, the Job is marked FAILED.
  ```

#### GIMLE-236 — Job active-deadline enforcement

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator, I want a Job running longer than its activeDeadline forcibly marked failed.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `JobReconciler.reconcileCurrentRun`
- **Test coverage**: `JobReconcilerTest#exceeding_the_active_deadline_marks_the_job_permanently_failed_even_mid_attempt`
- **Gherkin scenario**:
  ```gherkin
  Given activeDeadline=10min and the run has been active 11min; When JobReconciler ticks; Then the run is removed and the Job marked FAILED mid-attempt.
  ```

#### GIMLE-237 — CronJob schedule-driven Job materialization

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator, I want a CronJob's schedule to automatically materialize a real Job at each due time.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/CronJobReconciler.java`
- **Test coverage**: `CronJobReconcilerTest` — `first_tick_records_a_baseline_and_materializes_nothing`, `a_due_firing_materializes_a_job_named_with_the_epoch_second_suffix`
- **Gherkin scenario**:
  ```gherkin
  Given a CronJobSpec with schedule "* * * * *" and no prior lastSchedule; When first ticked; Then baseline is recorded with no retroactive burst; on the next due tick a Job named "{name}-{epochSeconds}" is materialized.
  ```

#### GIMLE-238 — CronJob concurrency policy (Allow/Forbid/Replace)

- **Category**: Reconciliation / Orchestration
- **User story**: As an operator, I want to control overlapping CronJob firings via concurrency semantics.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `CronJobReconciler.materializeFiring`
- **Test coverage**: `CronJobReconcilerTest` — `concurrency_policy_forbid_skips_a_firing_while_the_previous_one_is_still_running`, `concurrency_policy_replace_removes_the_still_running_job_before_placing_the_new_one`, `concurrency_policy_allow_lets_a_new_firing_run_alongside_a_still_running_one`
- **Gherkin scenario**:
  ```gherkin
  Given the previous firing is still non-terminal and concurrencyPolicy=FORBID; When a new firing is due; Then it is skipped and logged.
  ```

#### GIMLE-239 — CronJob manual trigger (`gimle cronjob trigger`)

- **Category**: Reconciliation / API Server
- **User story**: As an operator, I want to fire a CronJob on demand independent of its schedule.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `CronJobReconciler.triggerNow`; `ApiServer.handleCronJobTrigger`
- **Test coverage**: `CronJobReconcilerTest#trigger_now_fires_immediately_and_does_not_touch_last_schedule_time`; `ApiServerTest#trigger_fires_immediately_and_the_generated_job_appears_on_the_jobs_list`
- **Gherkin scenario**:
  ```gherkin
  Given a CronJob exists; When POST /cronjobs/{name}/trigger is called; Then a Job is materialized immediately, and cronJobLastSchedule is left untouched.
  ```

#### GIMLE-240 — CronJob missed-schedule starting-deadline handling

- **Category**: Reconciliation
- **User story**: As an operator, I want a too-late firing skipped rather than run stale, so an outage doesn't cause a burst of overdue runs.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `CronJobReconciler.reconcileCronJob`
- **Test coverage**: Covered indirectly by `CronJobReconcilerTest`'s convergence/missed-schedule handling
- **Gherkin scenario**:
  ```gherkin
  Given a firing's startingDeadline is exceeded by processing time; When CronJobReconciler ticks; Then the firing is logged as missed with no Job materialized.
  ```

#### GIMLE-241 — Level-triggered orphan cleanup across every workload kind

- **Category**: Reconciliation
- **User story**: As an operator, I want deleting a workload spec to automatically clean up its stale assignments on the next tick.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: each reconciler's orphan-sweep (`DeploymentReconciler`, `JobReconciler`, `DaemonSetReconciler`, `StatefulSetReconciler`)
- **Test coverage**: `DeploymentReconcilerTest#deleting_a_deployment_removes_all_of_its_assignments`, `JobReconcilerTest#deleting_a_job_removes_its_orphaned_run_on_the_next_tick`, `DaemonSetReconcilerTest#deleting_a_daemonset_removes_its_orphaned_assignments`, `StatefulSetReconcilerTest#deleting_a_statefulset_removes_its_orphaned_assignment_and_sticky_binding`
- **Gherkin scenario**:
  ```gherkin
  Given a deployment is deleted but its assignments remain; When the reconciler ticks; Then every assignment is removed.
  ```

#### GIMLE-242 — Reconciler-leader election via non-replicated lease

- **Category**: Orchestration / Internal-Infra
- **User story**: As an operator running multiple control-plane replicas, I want exactly one replica actively reconciling at a time.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ControlPlaneMain` — `leaseTick`, `StoreClient.tryAcquireOrRenewLease`
- **Test coverage**: Indirect (multi-replica smoke/holmgang tests)
- **Gherkin scenario**:
  ```gherkin
  Given multiple ApiServer replicas share one store cluster; When each replica's leaseTick runs; Then only the lease-holder executes the reconcile tick.
  ```

#### GIMLE-243 — Independent-executor ticking (lease/reconcile/cert-rotation isolation)

- **Category**: Internal-Infra
- **User story**: As an operator, I want a slow reconcile tick to never block certificate rotation or lease renewal.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ControlPlaneMain.scheduleIndependentTickers`
- **Test coverage**: `ControlPlaneSchedulingTest` — `cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_is_blocked_forever`, `cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_throws_every_time`
- **Gherkin scenario**:
  ```gherkin
  Given the reconcile tick is deliberately blocked forever; When cert rotation and lease renewal keep ticking on their own executors; Then both continue unaffected.
  ```

#### GIMLE-244 — JPMS module boundary for gimle-controlplane

- **Category**: Internal-Infra
- **User story**: As a maintainer, I want the control plane's public API surface explicitly exported and dependencies explicitly declared.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/module-info.java`
- **Test coverage**: NONE (structural)
- **Gherkin scenario**:
  ```gherkin
  Given module-info.java for com.gimle.controlplane; When compiled/linked with jlink; Then only schedule/reconcile/api/pki/top-level packages are exported.
  ```

#### GIMLE-245 — Admission chain extension point

- **Category**: Admission / Internal-Infra
- **User story**: As a platform maintainer, I want admission checks run as an ordered, pluggable chain rather than hardcoded inline.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/admission/AdmissionChain.java`, `AdmissionPlugin.java`, `AdmissionDecision.java`
- **Test coverage**: `AdmissionChainTest` — `empty_chain_allows_the_spec_unchanged`, `a_rejecting_plugin_short_circuits_every_later_plugin`, `a_later_plugin_sees_the_spec_an_earlier_plugin_mutated`
- **Gherkin scenario**:
  ```gherkin
  Given a chain with a rejecting plugin followed by an allowing plugin; When admit() runs; Then processing short-circuits at the first rejection; a later plugin sees an earlier plugin's mutated spec.
  ```

#### GIMLE-246 — Tenant resource quota admission check

- **Category**: Admission / Multi-tenancy
- **User story**: As a platform operator, I want a deployment rejected outright if it would push a tenant past its quota (accounting for surge headroom).
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/admission/TenantQuotaPlugin.java`, `TenantUsage.java`
- **Test coverage**: `TenantQuotaPluginTest` — `deployment_exceeding_its_tenants_quota_is_rejected`, `a_deployment_fitting_at_replicas_alone_but_not_with_surge_is_rejected`
- **Gherkin scenario**:
  ```gherkin
  Given tenant T's quota is nearly exhausted; When a submission with maxCommittedInstances (replicas+maxSurge) would exceed it; Then admission rejects with 409.
  ```

#### GIMLE-247 — Organization-specific policy-as-data admission (`policy.maxReplicasPerDeployment`)

- **Category**: Admission / Config
- **User story**: As a platform operator, I want a per-tenant ceiling on replicas-per-deployment via ordinary config.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/admission/PolicyConfigPlugin.java`
- **Test coverage**: `PolicyConfigPluginTest` — `a_deployment_exceeding_the_configured_ceiling_is_rejected`, `a_malformed_policy_value_is_rejected_rather_than_silently_ignored`, `exactly_at_the_ceiling_is_allowed`
- **Gherkin scenario**:
  ```gherkin
  Given tenant T has policy.maxReplicasPerDeployment=10; When a submission requests 15 replicas; Then admission rejects citing the ceiling.
  ```

#### GIMLE-248 — Registry-coordinate artifact admission (Andvari integration)

- **Category**: Admission / Artifact Registry
- **User story**: As an operator, I want a coordinate-only deployment admitted only if Andvari confirms the artifact exists.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.admissionArtifact`; `AndvariClient.head`/`HeadOutcome`; `ArtifactResolver`
- **Test coverage**: `AndvariClientTest`; end-to-end in `gimle-smoke-tests/AndvariRegistryIT`
- **Gherkin scenario**:
  ```gherkin
  Given a manifest names moduleId:version with no artifactPath, and Andvari doesn't have it; When submitted; Then admission rejects with 400; if Andvari is merely unreachable, admission still accepts with no recorded digest (level-triggered tolerance).
  ```

#### GIMLE-249 — PUT-time re-tenanting double-authorization

- **Category**: Authorization
- **User story**: As a platform security owner, I want moving a resource between tenants to require write access under both tenants.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ApiServer.dispatchResourceRequest` (PUT branch)
- **Test coverage**: Embedded in `ApiServerAuthzTest`'s broader RBAC flow coverage
- **Gherkin scenario**:
  ```gherkin
  Given deployment D belongs to tenant A; When a caller with write access only to tenant B PUTs D with tenantId=B; Then the request is rejected unless the caller also has write access to tenant A.
  ```

#### GIMLE-250 — RBAC-gated resource CRUD across every workload kind

- **Category**: Authorization
- **User story**: As a platform operator, I want every workload resource gated by an independent RBAC check per verb.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.requireAuthorized`, `dispatchResourceRequest`; `com.gimle.mimir.authz.Authorizer`
- **Test coverage**: `ApiServerAuthzTest`, `ApiServerEndpointsAuthzTest`
- **Gherkin scenario**:
  ```gherkin
  Given a principal with no grant for ResourceKind.JOB; When GET/PUT/DELETE against /jobs/{name}; Then every request is rejected with 403.
  ```

#### GIMLE-251 — WRITE/DELETE decisions durably audited (opt-in READ auditing)

- **Category**: Authorization / Internal-Infra
- **User story**: As a compliance auditor, I want every WRITE/DELETE decision recorded, and READ optionally audited per resource kind.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.requireAuthorized`, `recordAuditEvent`, `parseAuditReadResourceKinds`; `handleAudit`
- **Test coverage**: `ApiServerAuthzTest#configured_read_resource_kinds_are_audited_allowed_and_denied_reads`
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.controlplane.audit.readResourceKinds=CONFIG,SECRET; When a READ hits /config/* or /secrets/*; Then an AuditEvent is durably appended even for allowed reads.
  ```

#### GIMLE-252 — `gimle-system` reserved-tenant operator-only guard

- **Category**: Authorization
- **User story**: As a platform operator, I want the reserved `gimle-system` tenant accessible only by `gimle:operators`-group callers.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ApiServer.rejectIfReservedSystemTenant`, `isOperatorCaller`
- **Test coverage**: Exercised within `ApiServerAuthzTest`'s broader RBAC test set
- **Gherkin scenario**:
  ```gherkin
  Given a caller holds a broad but non-operator-group grant; When writing under tenantId=gimle-system; Then rejected 403 regardless of ordinary RBAC outcome.
  ```

#### GIMLE-253 — Node-scoped self-service authorization (`gimle:nodes` group)

- **Category**: Authorization
- **User story**: As a node agent, I want to reach only my own node's subresources with no explicit RoleBinding needed.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleNode`; `com.gimle.mimir.authz.Authorizer`
- **Test coverage**: `ApiServerAuthzTest`; `NodeBootstrapCsrTest#fresh_agent_obtains_a_signed_certificate_and_completes_mtls_handshake`
- **Gherkin scenario**:
  ```gherkin
  Given a certificate carrying gimle:nodes and CN=node-42; When calling POST /nodes/node-42/heartbeat; Then it succeeds via the self-service short-circuit; a request against node-99 is rejected.
  ```

#### GIMLE-254 — Node-tenant-scoped `/endpoints/*` read access

- **Category**: Authorization
- **User story**: As a node agent, I want to read service-discovery endpoint data only for tenants my node currently hosts an assignment for.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.authorizeEndpointsRead`, `Authorizer.isTenantAssignedToNode`
- **Test coverage**: `ApiServerEndpointsAuthzTest` — `a_node_with_an_active_assignment_for_the_deployments_tenant_may_read_its_endpoints`, `a_node_with_no_assignment_for_the_deployments_tenant_is_forbidden`
- **Gherkin scenario**:
  ```gherkin
  Given node-42 has an active instance for tenant T; When it calls GET /endpoints/{workload-of-T}; Then access is granted; a request for a tenant it has no assignment for is rejected 403.
  ```

#### GIMLE-255 — mTLS-authenticated HTTP API server with client-cert principal resolution

- **Category**: Internal-Infra / API Server
- **User story**: As a platform security owner, I want every control-plane API call authenticated by client cert under TLS mode, falling back to session cookie for the console.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.createHttpServer`, `resolvePrincipal`, `peerCertificate`
- **Test coverage**: `ApiServerTlsTest` — `https_request_with_a_valid_client_cert_succeeds`, `https_request_without_a_client_cert_is_rejected`
- **Gherkin scenario**:
  ```gherkin
  Given gimle.transport.protocol=tls and a CA-signed client cert; When any request is made; Then the principal is resolved from the certificate subject; absent a cert, a session cookie is checked.
  ```

#### GIMLE-256 — Console session login/logout/session cookie flow

- **Category**: Authorization / API Server
- **User story**: As a human operator, I want to log in with username/password and get a signed session cookie.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleAuthLogin`/`handleAuthLogout`/`handleAuthSession`, `SessionTokens`
- **Test coverage**: `ApiServerAuthzTest#login_session_and_logout_round_trip_with_no_client_certificate_at_all`
- **Gherkin scenario**:
  ```gherkin
  Given a valid bootstrap Account; When POST /auth/login with correct credentials; Then a signed, HttpOnly, SameSite=Strict session cookie is set.
  ```

#### GIMLE-257 — Login throttling (address + username keyed)

- **Category**: Authorization / Internal-Infra
- **User story**: As a platform security owner, I want repeated failed logins throttled independently by username and address.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ApiServer.loginThrottle`, `handleAuthLogin`; `com.gimle.core.throttle.LoginThrottle`
- **Test coverage**: Exercised via shared `LoginThrottle` mechanics (`FafnirObservabilityTest`'s equivalent); no isolated ApiServer-level test method found
- **Gherkin scenario**:
  ```gherkin
  Given repeated failed logins for the same username from different addresses; When the username-keyed threshold is exceeded; Then further attempts are throttled with 429+Retry-After.
  ```

#### GIMLE-258 — Bootstrap node join via single-use token + CSR

- **Category**: Internal-Infra / API Server (PKI)
- **User story**: As a platform operator, I want a freshly-provisioned node to join via a one-time bootstrap token and a CSR.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleNodeJoinRequest`, `BootstrapTokenRegistry`
- **Test coverage**: `NodeBootstrapCsrTest` — `fresh_agent_obtains_a_signed_certificate_and_completes_mtls_handshake`, `invalid_bootstrap_token_is_rejected`; `BootstrapTokenRegistryTest` — `issued_token_can_be_consumed_exactly_once`, `expired_token_cannot_be_consumed`
- **Gherkin scenario**:
  ```gherkin
  Given an operator issued a bootstrap token; When a new node submits a CSR (purpose=NODE_CLIENT) with that token; Then the CA signs a cert stamped O=gimle:nodes, and the token cannot be reused.
  ```

#### GIMLE-259 — Operator-approval-gated CSR flow

- **Category**: Internal-Infra / API Server (PKI)
- **User story**: As a platform operator, I want a human operator's CSR to require an existing operator's explicit approval.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleOperatorJoinRequest`, `PendingCsrStore`, `handleApprove`
- **Test coverage**: `HumanOperatorCsrTest` — `operator_csr_sits_pending_until_an_existing_operator_approves_it`, `approve_without_a_client_certificate_is_rejected`
- **Gherkin scenario**:
  ```gherkin
  Given a human submits a CSR (purpose=OPERATOR_CLIENT) with no client certificate; When submitted; Then it sits pending (202) until an existing operator approves it.
  ```

#### GIMLE-260 — Certificate rotation (self-rotation and subject-preserving renewal)

- **Category**: Internal-Infra
- **User story**: As a platform operator, I want a certificate renewable before expiry using its own still-valid certificate, preserving its exact prior subject.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleRotationRequest`; `checkAndRotateOwnCertificateIfDue`, `reloadTlsMaterial`
- **Test coverage**: `CertificateRotationTest` — `rotation_issues_a_new_cert_for_the_same_subject_and_it_works_immediately`, `rotation_csr_with_a_mismatched_subject_is_rejected`
- **Gherkin scenario**:
  ```gherkin
  Given a caller presents its own still-valid cert with a matching-subject rotation CSR; When submitted; Then a new cert is issued for the identical subject; a mismatched subject is rejected 403.
  ```

#### GIMLE-261 — Zero-downtime TLS material reload

- **Category**: Internal-Infra
- **User story**: As a platform operator, I want a certificate rotation to take effect without restarting the control-plane process.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ApiServer.reloadTlsMaterial`
- **Test coverage**: Exercised via `CertificateRotationTest`; analogous pattern in `FafnirServerTlsTest`/`AndvariServerTlsTest`
- **Gherkin scenario**:
  ```gherkin
  Given the control plane's cert is rotated; When reloadTlsMaterial runs; Then the HttpsServer is rebuilt with new key material and a fresh connection succeeds.
  ```

#### GIMLE-262 — `/secrets/*` byte-for-byte proxy to Fafnir

- **Category**: Secrets Management / Internal-Infra
- **User story**: As a client, I want to interact with the versioned secrets API without knowing Fafnir runs as a separate process.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleSecretsProxy`; `FafnirClient.forward`
- **Test coverage**: `ApiServerAuthzTest#config_and_secret_permissions_are_independently_enforced_and_filtered`, `a_secret_survives_key_rotation_and_new_secrets_use_the_rotated_key`
- **Gherkin scenario**:
  ```gherkin
  Given a caller has WRITE access to SECRET for tenant T; When PUT /secrets/T/mykey; Then ApiServer authorizes locally, forwards byte-for-byte to Fafnir with the calling principal as an internal claim, and relays the response verbatim.
  ```

#### GIMLE-263 — Secrets key rotation trigger (proxied)

- **Category**: Secrets Management
- **User story**: As a platform operator, I want to trigger secrets master-key rotation and have every encrypted entry re-encrypted.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleRotateSecretsKey`; `FafnirClient.rotateKey`; `FafnirCrypto.rotate`
- **Test coverage**: `ApiServerAuthzTest#a_secret_survives_key_rotation_and_new_secrets_use_the_rotated_key`
- **Gherkin scenario**:
  ```gherkin
  Given several encrypted entries exist; When POST /secrets/rotate-key; Then Fafnir generates a new key, re-encrypts every entry, and every previously-encrypted secret still decrypts.
  ```

#### GIMLE-264 — CONFIG/SECRET resource-kind separation on one underlying store

- **Category**: Config / Authorization
- **User story**: As a platform security owner, I want plain config and encrypted secrets independently RBAC-gated even though they share one store.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleConfig`, `isFafnirManagedSecretKey`
- **Test coverage**: `ApiServerAuthzTest#config_and_secret_permissions_are_independently_enforced_and_filtered`
- **Gherkin scenario**:
  ```gherkin
  Given a caller has CONFIG:WRITE but not SECRET:WRITE; When PUT /config/{tenant}/{key} with encrypted=true; Then the write is rejected because it routes authorization through ResourceKind.SECRET.
  ```

#### GIMLE-265 — `/artifacts/*` streaming proxy to Andvari

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a client, I want to push/pull/list module jars through the control plane's single API surface.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleArtifactsProxy`; `AndvariClient.forward`
- **Test coverage**: `AndvariClientTest`; end-to-end in `gimle-smoke-tests/AndvariRegistryIT`
- **Gherkin scenario**:
  ```gherkin
  Given --andvari-endpoint is configured; When a caller with ARTIFACT:WRITE PUTs a jar; Then ApiServer authorizes locally and streams the jar straight through, never buffered whole.
  ```

#### GIMLE-266 — Andvari-client multi-endpoint failover with rotation

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a platform operator running multiple Andvari replicas, I want reads to automatically fail over on transport failure.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariClient.withEndpoints`, `head`, `forward`
- **Test coverage**: `AndvariClientTest` — `a_head_call_fails_over_from_an_unreachable_endpoint_to_a_reachable_one`, `unreachable_on_every_configured_endpoint_answers_unreachable`
- **Gherkin scenario**:
  ```gherkin
  Given two configured endpoints, the first unreachable; When a HEAD request is made; Then it transparently retries against the second endpoint.
  ```

#### GIMLE-267 — `/logs/*` proxy with Muninn fallback

- **Category**: Internal-Infra
- **User story**: As an operator, I want log requests to fall through to Muninn when the owning agent/node is gone.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleInstanceLogsProxy`/`handleNodeLogsProxy`, `MuninnClient.get`
- **Test coverage**: `ApiServerLogsFallbackTest` — `a_node_with_no_registration_falls_through_to_muninn_when_configured`, `a_registered_but_unreachable_agent_falls_through_to_muninn_when_configured`, `a_live_reachable_agent_is_still_served_directly_not_from_muninn`, `a_muninn_fallback_fails_over_to_a_second_configured_endpoint_when_the_first_is_unreachable`
- **Gherkin scenario**:
  ```gherkin
  Given an instance's owning node is unregistered or unreachable, and Muninn is configured; When a caller requests logs; Then ApiServer falls through to Muninn instead of 404/502.
  ```

#### GIMLE-268 — `/metrics-history/*` and `/traces-history/*` Muninn proxy

- **Category**: Internal-Infra
- **User story**: As an operator, I want to query historical metrics/traces for any process kind through the control plane.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleMetricsHistory`/`handleTracesHistory`/`handleHistoryProxy`
- **Test coverage**: `ApiServerMetricsHistoryTest#proxies_to_muninn_forwarding_the_since_query_parameter`, `ApiServerTracesHistoryTest`
- **Gherkin scenario**:
  ```gherkin
  Given Muninn is configured; When a caller with LOGS:READ requests /metrics-history/{processKind}/{processId}?since=...; Then the request is proxied to Muninn.
  ```

#### GIMLE-269 — Node registration, heartbeat, and assignment-fetch API

- **Category**: API Server / Orchestration
- **User story**: As a node agent, I want to register capabilities, send heartbeats, and fetch my current assignment set.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ApiServer.handleRegister`/`handleHeartbeat`/`handleAssignments`
- **Test coverage**: `ApiServerTest` (transitive); `gimle-agent` integration tests
- **Gherkin scenario**:
  ```gherkin
  Given a node agent starts up; When it POSTs /nodes/{id}/register then periodically /nodes/{id}/heartbeat; Then the control plane records capacity/observations, usable by GET /nodes/{id}/assignments.
  ```

#### GIMLE-270 — Unified `AssignedInstance` wire shape across every workload kind

- **Category**: Internal-Infra / API Server
- **User story**: As a maintainer, I want Job/DaemonSet/StatefulSet/Deployment assignments all mapped onto one wire shape.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleAssignments`
- **Test coverage**: `ApiServerEndpointsTest` — `a_job_run_is_listed_under_its_own_endpoints_route`, `a_daemonset_assignment_is_listed_under_its_own_endpoints_route`, `a_statefulset_assignment_is_listed_under_its_own_endpoints_route`
- **Gherkin scenario**:
  ```gherkin
  Given a node has a mix of assignment kinds; When GET /nodes/{id}/assignments; Then every kind is represented uniformly as AssignedInstance entries.
  ```

#### GIMLE-271 — Reserved system-tenant auto-seeding

- **Category**: Multi-tenancy / Internal-Infra
- **User story**: As a platform operator, I want the reserved `gimle-system` tenant to always exist with generous quota, seeded automatically.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ApiServer.seedReservedSystemTenantIfAbsent`
- **Test coverage**: Implicit in test fixtures bootstrapping ApiServer
- **Gherkin scenario**:
  ```gherkin
  Given a fresh control-plane replica starts against an empty store; When it initializes; Then it proposes a Tenant row for RESERVED_SYSTEM_TENANT_ID unless one already exists.
  ```

#### GIMLE-272 — Bundled web console static serving

- **Category**: API Server / Internal-Infra
- **User story**: As an operator, I want the control plane to serve its own bundled web console at /console with zero extra deploy steps.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.serveConsole`, `com.gimle.core.web.BundledSpa`, `ControlPlaneMain`
- **Test coverage**: `ApiServerConsoleContractTest`
- **Gherkin scenario**:
  ```gherkin
  Given the console/index.html resource is present on the classpath; When ControlPlaneMain starts; Then it serves the bundled SPA at /console with client-side-route fallback.
  ```

#### GIMLE-273 — Per-endpoint request metrics instrumentation

- **Category**: Internal-Infra
- **User story**: As an operator, I want every API route's request count, latency, and error rate tracked independently.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.instrument`, `com.gimle.observability.ApiServerMetrics`
- **Test coverage**: `ApiServerMetricsTest`
- **Gherkin scenario**:
  ```gherkin
  Given a request is made to any registered route; When the handler completes; Then request count/latency/error status are recorded, tagged by endpoint and verb.
  ```

#### GIMLE-274 — Deployment/Job/DaemonSet/StatefulSet CRUD manifest API

- **Category**: API Server
- **User story**: As an operator, I want to submit, read, and delete declarative workload manifests via a uniform REST surface.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ApiServer.handleDeployment`/`handleJob`/`handleDaemonSet`/`handleStatefulSet`
- **Test coverage**: `ApiServerTest` — extensive PUT/GET/DELETE/list round-trip coverage
- **Gherkin scenario**:
  ```gherkin
  Given a valid Deployment manifest YAML; When PUT /deployments/{name}; Then GET returns the round-tripped spec, and DELETE removes it.
  ```

#### GIMLE-275 — Per-deployment and per-instance metrics rollup

- **Category**: API Server / Observability
- **User story**: As an operator, I want request-rate/error-rate rollups per deployment surfaced through the API.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ApiServer.handleMetrics`, `average`
- **Test coverage**: Covered within `ApiServerConsoleContractTest`/`ApiServerTest`
- **Gherkin scenario**:
  ```gherkin
  Given a deployment has 3 ready instances reporting different request rates; When GET /metrics; Then a per-deployment row with the averaged rates is returned.
  ```

#### GIMLE-566 — Service abstraction: stable name, CRUD API, and endpoint reconciliation

- **Category**: Reconciliation / Service Fabric
- **User story**: As a module author, I want to declare a stable Service name in front of one or more Deployments' replicas, so a caller addresses a fixed name/port instead of chasing the ephemeral per-instance addresses those replicas actually listen on.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-mimir/src/main/java/com/gimle/mimir/manifest/ServiceSpec.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/ServiceReconciler.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/service/ServiceRegistry.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/service/ServiceEndpointResolver.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/service/ServiceEndpoint.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java` (`/services`, `/services/{name}`, `/services/{name}/endpoints`)
- **Test coverage**: `ServiceReconcilerTest` (an_empty_store_converges_to_an_empty_endpoint_list, a_store_with_active_and_ready_instances_already_present_converges_to_their_endpoints, an_instance_reported_alive_but_not_ready_yet_contributes_no_endpoint, endpoints_are_aggregated_across_every_deployment_name_in_the_selector, one_services_failure_does_not_prevent_another_from_reconciling, reconciling_again_after_a_backing_instance_goes_away_clears_its_endpoint); `ApiServerServicesTest` (post_then_get_a_service_round_trips, a_target_port_distinct_from_port_round_trips, get_of_an_unknown_service_is_404, delete_removes_a_service, services_list_endpoint_returns_every_service, a_missing_service_name_on_post_is_a_400, an_empty_deployment_names_is_a_400, endpoints_route_returns_the_exact_contract_shape_for_a_live_ready_instance, endpoints_route_returns_200_with_an_empty_array_when_no_backing_instance_is_live_yet, endpoints_route_for_an_unknown_service_is_404, posting_the_same_name_again_replaces_the_prior_spec); `ServiceRegistryTest`
- **Gherkin scenario**:
  ```gherkin
  Given a Service "orders" selecting deployment "orders-service" on port 8080, When POSTed to /services and the reconciler ticks against a store with an ACTIVE, ready instance of "orders-service", Then GET /services/orders/endpoints returns that instance's real host:port.
  Given a Service whose selected deployment currently has no ACTIVE-and-ready instance, When the reconciler ticks, Then it converges to an empty endpoint list rather than failing.
  Given a Service posted with the same name twice, When the second POST carries a different deploymentNames/port, Then GET returns the second spec, replacing the first entirely.
  ```

### gimle-fafnir

#### GIMLE-276 — AES-256-GCM secret value encryption with versioned key IDs

- **Category**: Secrets Management
- **User story**: As a platform security owner, I want every secret encrypted at rest with AES-256-GCM tagged with which key encrypted it, so key rotation doesn't break decryption.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/SecretCipher.java`
- **Test coverage**: `SecretCipherTest` — `round_trips_plaintext_through_encryption_and_decryption`, `round_trips_through_a_specific_key_id`, `ciphertext_never_contains_the_plaintext_bytes`, `the_same_plaintext_encrypts_differently_each_time_due_to_a_random_iv`
- **Gherkin scenario**:
  ```gherkin
  Given a secret encrypted under key id 3; When decrypted after the ring rotates to key id 5; Then decryption succeeds by looking up key id 3 from the embedded byte.
  ```

#### GIMLE-277 — Legacy pre-key-id ciphertext format fallback

- **Category**: Secrets Management
- **User story**: As a platform operator upgrading an existing cluster, I want secrets encrypted before key-rotation existed to still decrypt correctly.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `SecretCipher.decrypt`
- **Test coverage**: `SecretCipherTest` (legacy-format coverage per class javadoc)
- **Gherkin scenario**:
  ```gherkin
  Given a blob encrypted before the version/keyId prefix existed; When decrypt() runs; Then the new-format decode fails GCM verification and falls back to the legacy iv||ciphertext layout under key id 0.
  ```

#### GIMLE-278 — Local AES-256 key-file generation and loading

- **Category**: Secrets Management
- **User story**: As a platform operator, I want Fafnir to generate its own master key file on first run with owner-only permissions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/KeyFileManager.java`
- **Test coverage**: `KeyFileManagerTest` — `generates_a_key_on_first_run_and_reuses_it_on_later_runs`, `a_key_loaded_via_a_second_manager_instance_can_decrypt_what_the_first_encrypted`
- **Gherkin scenario**:
  ```gherkin
  Given no key file exists; When KeyFileManager.loadOrCreate runs; Then a fresh key is generated, written rw-------, and reused on later loads.
  ```

#### GIMLE-279 — Key rotation with full-ring persistence (`KeyFileManager.rotate`)

- **Category**: Secrets Management
- **User story**: As a platform operator, I want rotating the key to generate a new key while keeping every previous one loadable.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `KeyFileManager.rotate`
- **Test coverage**: `KeyFileManagerTest#rotate_adds_a_new_active_key_while_keeping_the_old_one_loadable`
- **Gherkin scenario**:
  ```gherkin
  Given a ring holding key id 0; When rotate() runs; Then a new key id 1 file is written, .active repointed, key id 0 remains loadable.
  ```

#### GIMLE-280 — Key-ring fingerprinting for cross-replica drift detection

- **Category**: Secrets Management / Internal-Infra
- **User story**: As a platform operator running multiple Fafnir replicas, I want a safe-to-log fingerprint of each replica's key ring.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/KeyRing.java`
- **Test coverage**: `KeyRingTest` — `fingerprint_does_not_depend_on_keysbyid_map_iteration_order`, `fingerprint_changes_when_key_material_differs`, `fingerprint_changes_after_a_real_rotation_via_keyfilemanager`
- **Gherkin scenario**:
  ```gherkin
  Given two replicas hold identical key material in different map order; When fingerprint() is computed; Then both produce the identical SHA-256 hex digest.
  ```

#### GIMLE-281 — Full-key-rotation re-encryption sweep

- **Category**: Secrets Management
- **User story**: As a platform operator, I want every existing encrypted config entry re-encrypted under the newly-rotated key.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirCrypto.java`
- **Test coverage**: `FafnirCryptoTest` — `rotate_reencrypts_every_existing_encrypted_entry_under_the_new_active_key`, `rotate_never_loses_a_previously_encrypted_value_still_decryptable_after_multiple_rounds`, `a_plain_unencrypted_entry_is_untouched_by_rotation`
- **Gherkin scenario**:
  ```gherkin
  Given several tenants have encrypted entries under the old key; When FafnirCrypto.rotate() runs; Then every encrypted entry is re-encrypted under the new key.
  ```

#### GIMLE-282 — Versioned secret storage layered over ConfigEntry

- **Category**: Secrets Management
- **User story**: As an operator, I want secrets stored with full version history, not just the latest value.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/SecretStore.java`
- **Test coverage**: `SecretStoreTest#list_returns_metadata_only_for_every_written_secret_in_the_tenant`; `FafnirServerTest#versions_lists_every_claimed_version_number`, `an_explicit_version_query_parameter_reads_that_historical_value`
- **Gherkin scenario**:
  ```gherkin
  Given a secret key written 3 times; When GET /secrets/{tenant}/{key}/versions; Then versions 1,2,3 are all listed and independently retrievable via ?version=N.
  ```

#### GIMLE-283 — Optimistic-write versioned put with narrow-lease serialization

- **Category**: Secrets Management / Internal-Infra
- **User story**: As a platform maintainer, I want concurrent writes to never produce duplicate version claims.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `SecretStore.put`
- **Test coverage**: `SecretStoreTest` (contention scenario per class javadoc)
- **Gherkin scenario**:
  ```gherkin
  Given two writers race to PUT the same key; When both complete; Then exactly one version number is claimed by each, serialized via a narrow lease around the final @meta-advance step.
  ```

#### GIMLE-284 — Soft delete vs hard delete (`?destroy=true`)

- **Category**: Secrets Management
- **User story**: As an operator, I want soft-delete by default (recoverable) and hard-delete only when explicitly requested.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SecretStore.softDelete`/`hardDelete`; `FafnirServer.handleDeleteSecret`
- **Test coverage**: `SecretStoreTest#soft_delete_marks_the_secret_deleted_but_keeps_every_version_readable_by_number`, `hard_delete_removes_every_version_and_the_metadata_entry_itself`; `FafnirServerTest#soft_deleting_a_secret_hides_it_from_a_default_get_but_versions_remain_readable`
- **Gherkin scenario**:
  ```gherkin
  Given a key with 3 versions; When DELETE without ?destroy=true; Then hidden from default GET but every version remains individually readable; with ?destroy=true, every version is permanently removed.
  ```

#### GIMLE-285 — Fafnir's own independent RBAC re-check (defense-in-depth)

- **Category**: Authorization
- **User story**: As a platform security owner, I want Fafnir to never trust a proxied "already authorized" claim from the control plane.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirServer.java`
- **Test coverage**: `FafnirSecretsAuthzTest#a_forwarded_principal_who_does_not_actually_hold_the_permission_is_still_forbidden`
- **Gherkin scenario**:
  ```gherkin
  Given a forwarded principal claims access but doesn't actually hold the permission; When Fafnir independently re-checks Authorizer.authorize; Then denied with 403 regardless of the forwarder's decision.
  ```

#### GIMLE-286 — Node-tenant-scoped secret reads (`gimle:nodes`)

- **Category**: Authorization
- **User story**: As a node agent, I want to read a tenant's secrets directly from Fafnir only for a tenant I currently host an assignment for, never write/delete.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirServer.decideAllowed`; `Authorizer.isTenantAssignedToNode`
- **Test coverage**: `FafnirSecretsAuthzTest` — `a_node_with_an_active_assignment_for_the_tenant_may_read_its_secrets`, `a_node_with_no_assignment_for_the_tenant_is_forbidden_regardless_of_key`, `a_node_may_never_write_a_secret_even_with_an_active_assignment`
- **Gherkin scenario**:
  ```gherkin
  Given node-42 has an active assignment for tenant T; When it reads GET /secrets/T/{key}; Then granted; PUT/DELETE, or reading an unassigned tenant, is rejected.
  ```

#### GIMLE-287 — Authorization-failure throttling and dual audit logging

- **Category**: Authorization / Internal-Infra
- **User story**: As a platform security owner, I want repeated authz failures throttled, and every /secrets/* decision durably audited.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirServer.authorizeSecrets`, `recordAudit`, `authzThrottle`
- **Test coverage**: `FafnirObservabilityTest` — `repeated_authorization_failures_from_the_same_principal_are_eventually_throttled`, `a_successful_authorization_clears_prior_recorded_failures`, `audit_log_records_the_decision_without_ever_logging_the_secret_value`
- **Gherkin scenario**:
  ```gherkin
  Given a principal repeatedly fails authorization; When the failure count crosses the threshold; Then further requests are throttled with 429+Retry-After.
  ```

#### GIMLE-288 — Three-tier principal resolution (forwarded header > peer cert > session cookie)

- **Category**: Internal-Infra / Authorization
- **User story**: As a maintainer, I want Fafnir to correctly identify the true calling principal regardless of arrival path.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirServer.resolvePrincipal`
- **Test coverage**: `FafnirSecretsAuthzTest`; `FafnirServerAuthTest`
- **Gherkin scenario**:
  ```gherkin
  Given a request carries both a forwarded header and its own peer cert; When resolvePrincipal runs; Then the forwarded header wins.
  ```

#### GIMLE-289 — mTLS HTTP server with dynamic TLS material reload

- **Category**: Internal-Infra
- **User story**: As a platform operator, I want Fafnir's certificate rotation to take effect without a process restart.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirServer.reloadTlsMaterial`; `FafnirMain`'s cert-rotation ticker
- **Test coverage**: `FafnirServerTlsTest` — `a_real_mtls_request_with_a_ca_signed_client_cert_succeeds`, `reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server`
- **Gherkin scenario**:
  ```gherkin
  Given Fafnir's cert is rotated; When reloadTlsMaterial runs; Then a fresh mTLS connection using the new cert succeeds without restart.
  ```

#### GIMLE-290 — Console session login (Fafnir's own operator dashboard)

- **Category**: API Server
- **User story**: As a human operator, I want to log in to Fafnir's own console independent of the control plane's console.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirServer.handleAuthLogin`/`handleAuthLogout`/`handleAuthSession`
- **Test coverage**: `FafnirServerAuthTest` — `login_session_and_logout_round_trip_with_no_client_certificate_at_all`, `a_wrong_password_is_rejected_with_no_cookie_set`
- **Gherkin scenario**:
  ```gherkin
  Given a valid Account; When logging in via Fafnir's /auth/login; Then a distinct gimle_fafnir_session cookie is issued.
  ```

#### GIMLE-291 — Plaintext-mode anonymous session carve-out

- **Category**: Authorization
- **User story**: As an operator running Fafnir in plaintext mode with no bootstrap account, I want an anonymous free-pass session instead of an unsatisfiable login.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `FafnirServer.handleAuthSession`
- **Test coverage**: Implicit in `FafnirServerAuthTest`'s plaintext-mode coverage
- **Gherkin scenario**:
  ```gherkin
  Given plaintext mode with no session cookie and no TLS; When GET /auth/session; Then a synthetic "anonymous: true" principal is returned rather than 401.
  ```

#### GIMLE-292 — Bundled web console static serving (Fafnir)

- **Category**: API Server / Internal-Infra
- **User story**: As an operator, I want Fafnir to serve its own bundled web console, mirroring the control plane's pattern.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirServer.serveConsole`; `FafnirMain`
- **Test coverage**: `FafnirServerConsoleTest` — `console_static_files_are_served_once_wired`, `the_real_bundled_console_jar_resolves_and_serves_its_own_index_html`
- **Gherkin scenario**:
  ```gherkin
  Given fafnir-console/index.html is on the classpath; When FafnirMain starts; Then the SPA is served at /console.
  ```

#### GIMLE-293 — Process status endpoint with key-ring fingerprint

- **Category**: API Server / Internal-Infra
- **User story**: As an operator, I want a status endpoint showing uptime, active key id, key-ring fingerprint, transport mode, and known tenants.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirServer.handleStatus`
- **Test coverage**: `FafnirServerAuthTest#status_reports_uptime_active_key_and_transport_mode`
- **Gherkin scenario**:
  ```gherkin
  Given Fafnir has run with one key rotation; When GET /status; Then it returns uptimeSeconds/activeKeyId/secretsKeyRingFingerprint/transportProtocol/tenant list — never a raw key.
  ```

#### GIMLE-294 — Muninn metrics/traces shipping

- **Category**: Internal-Infra / Config
- **User story**: As a platform operator, I want Fafnir's own metrics/traces shipped to Muninn when configured.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirMain.java`
- **Test coverage**: NONE directly in gimle-fafnir's own test tree (exercised via `gimle-smoke-tests/ObservabilityIT`)
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.fafnir.muninnEndpoint is set; When FafnirMain starts; Then a MuninnShipper periodically ships this replica's metrics and traces.
  ```

#### GIMLE-295 — Fafnir-metrics observability instrumentation

- **Category**: Internal-Infra
- **User story**: As an operator, I want every Fafnir endpoint's request count/latency/error rate independently tracked.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirServer.instrument`, `com.gimle.observability.FafnirMetrics`
- **Test coverage**: `FafnirObservabilityTest#a_real_request_is_recorded_in_fafnir_metrics`
- **Gherkin scenario**:
  ```gherkin
  Given a real request hits any Fafnir endpoint; When the handler completes; Then FafnirMetrics records it, tagged per-endpoint.
  ```

#### GIMLE-296 — JPMS module boundary for gimle-fafnir

- **Category**: Internal-Infra
- **User story**: As a maintainer, I want Fafnir's crypto and process packages explicitly exported and dependencies explicitly declared.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-fafnir/src/main/java/module-info.java`
- **Test coverage**: NONE (structural)
- **Gherkin scenario**:
  ```gherkin
  Given module-info.java for com.gimle.fafnir; When compiled/linked; Then only com.gimle.fafnir.secret and com.gimle.fafnir are exported.
  ```

### gimle-andvari

#### GIMLE-297 — Immutable, content-addressed artifact store

- **Category**: Artifact Registry
- **User story**: As a platform operator, I want a pushed coordinate to be permanently immutable, so downstream nodes can trust a cached coordinate by presence alone.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/ArtifactStore.java`
- **Test coverage**: `ArtifactStoreTest` — `an_identical_re_push_is_idempotent`, `a_differing_re_push_is_a_conflict_and_the_stored_bytes_are_untouched`; `AndvariServerTest` — `a_differing_re_push_is_refused_as_immutable`, `an_identical_re_push_is_idempotent`
- **Gherkin scenario**:
  ```gherkin
  Given moduleId:version was never pushed; When pushed with jar bytes X; Then CREATED; re-pushing identical X is IDENTICAL (no-op); re-pushing different Y is CONFLICT (409).
  ```

#### GIMLE-298 — Streamed, digest-verified push with atomic commit

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a platform maintainer, I want a jar upload never fully buffered in memory and never leave a torn file visible after a crash.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ArtifactStore.put` (DigestInputStream + ATOMIC_MOVE), `sweepOrphanedTempFiles`
- **Test coverage**: `ArtifactStoreTest` (push mechanics covered by round-trip tests)
- **Gherkin scenario**:
  ```gherkin
  Given a push is streaming when the process is killed; When it restarts; Then no torn file is visible at the coordinate's path — only an orphaned temp file (swept later).
  ```

#### GIMLE-299 — Size-limited streaming upload rejection

- **Category**: Artifact Registry
- **User story**: As a platform operator, I want an oversized upload rejected mid-stream, not after writing it fully to disk.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ArtifactStore.SizeLimitedInputStream`, `ArtifactTooLargeException`; `AndvariServer.handleUpload`
- **Test coverage**: Implicit in `ArtifactStoreTest`'s put-path coverage
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.andvari.maxArtifactBytes (default 500 MiB); When a push streams past that many bytes; Then aborted with 413 before writing excess bytes.
  ```

#### GIMLE-300 — On-disk corruption detection and quarantine

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a platform operator, I want a coordinate whose on-disk bytes no longer match the recorded sha256 quarantined rather than silently served.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.handleDownload`, `reportIntegrityFailure`; `ArtifactStore.quarantine`
- **Test coverage**: `AndvariServerTest#a_get_against_bytes_corrupted_on_disk_still_serves_them_but_quarantines_the_coordinate`
- **Gherkin scenario**:
  ```gherkin
  Given stored jar bytes are corrupted after being written; When a GET re-checks the digest while streaming; Then the request already committed to wire is served, but the coordinate is immediately quarantined and audited.
  ```

#### GIMLE-301 — Periodic full-store integrity scrub

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a platform operator, I want an optional background job re-digesting every stored artifact regardless of download activity.
- **Status**: Complete (off by default, opt-in — deliberate)
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/IntegrityScrubber.java`; `AndvariMain`
- **Test coverage**: `IntegrityScrubberTest` — `a_coordinate_whose_bytes_no_longer_match_its_recorded_digest_is_reported`, `an_uncorrupted_coordinate_is_never_reported`, `a_version_missing_its_jar_is_skipped_rather_than_reported_as_corrupted`
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.andvari.scrub.enabled=true and a stored jar is silently corrupted; When the scheduled scrub runs; Then the mismatch is detected and reported through the same quarantine/audit path.
  ```

#### GIMLE-302 — Version retention sweeping (count and age based)

- **Category**: Artifact Registry
- **User story**: As a platform operator, I want to optionally retire old artifact versions once a module exceeds a max-versions or max-age threshold.
- **Status**: Complete (off by default, opt-in — deliberate)
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/ArtifactRetentionSweeper.java`
- **Test coverage**: `ArtifactRetentionSweeperTest` — `retires_the_oldest_versions_once_a_module_exceeds_the_configured_count`, `retires_versions_older_than_the_configured_age`, `a_version_over_both_limits_is_reported_once_with_a_combined_reason`, `neither_policy_configured_retires_nothing`
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.andvari.retention.enabled=true with maxVersionsPerModule=10; When a module has 15 versions; Then the 5 oldest-by-push-time versions are retired, dual-audited under a synthetic system principal.
  ```

#### GIMLE-303 — Multi-replica peer synchronization (no consensus)

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a platform operator running multiple Andvari replicas, I want a push to one replica to eventually appear on every other without consensus.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/AndvariPeerSync.java`
- **Test coverage**: `AndvariPeerSyncTest` — `a_sync_tick_pulls_an_artifact_that_only_exists_on_a_peer`, `a_push_to_one_replica_becomes_visible_through_another_after_a_sync_tick`, `an_already_present_coordinate_is_never_re_pulled`
- **Gherkin scenario**:
  ```gherkin
  Given two peer replicas and a jar pushed only to A; When B's periodic sync tick runs; Then B pulls the coordinate from A, verifies the digest, and quarantines on mismatch.
  ```

#### GIMLE-304 — Peer-sync conflict detection (irreconcilable divergence)

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a platform operator, I want peer sync to loudly refuse when two replicas independently hold different bytes under the same coordinate.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `AndvariPeerSync.pullOne`
- **Test coverage**: Documented in class javadoc
- **Gherkin scenario**:
  ```gherkin
  Given A and B both committed different bytes under the same coordinate; When B's peer sync attempts to pull from A; Then ArtifactStore.put reports CONFLICT and neither replica's copy is touched.
  ```

#### GIMLE-305 — Push/pull/list/delete `/artifacts/*` operational HTTP surface

- **Category**: Artifact Registry / API Server
- **User story**: As a client, I want a straightforward REST surface for the artifact registry.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/AndvariServer.java`
- **Test coverage**: `AndvariServerTest` — `push_head_and_download_round_trip_with_the_digest_in_the_header`, `the_catalog_and_version_listing_reflect_pushed_artifacts`, `delete_removes_the_artifact`
- **Gherkin scenario**:
  ```gherkin
  Given a jar is pushed via PUT /artifacts/{moduleId}/{version}; When HEAD is called; Then a 200 with X-Gimle-Artifact-Sha256; GET streams the jar; DELETE removes it.
  ```

#### GIMLE-306 — Maven-2-shaped `/repository/**` interop surface

- **Category**: Artifact Registry / API Server
- **User story**: As a developer using standard Maven tooling, I want to `mvn deploy`/`mvn install` directly against Andvari.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.handleRepository`, `MavenCoordinates`
- **Test coverage**: `AndvariServerMavenRepositoryTest` — `a_jar_pushed_through_the_repository_path_is_readable_from_the_operational_surface`, `a_jar_pushed_through_the_operational_surface_is_downloadable_via_the_repository_path`, `a_differing_re_push_through_the_repository_path_is_still_refused_as_immutable`
- **Gherkin scenario**:
  ```gherkin
  Given a jar is pushed via mvn deploy to /repository/com/gimle/.../provider-1.0.0.jar; When fetched via GET /artifacts/com.gimle.examples.greeter.provider/1.0.0; Then identical bytes are returned.
  ```

#### GIMLE-307 — Server-computed checksum sidecars (never trusting client uploads)

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a Maven client verifying a download, I want the `.jar.sha256` checksum always freshly computed by the server.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.handleRepositoryChecksum` vs `putSidecar`
- **Test coverage**: `AndvariServerMavenRepositoryTest#the_jar_checksum_is_always_server_computed_and_ignores_an_uploaded_sidecar`
- **Gherkin scenario**:
  ```gherkin
  Given a client uploads a .jar.sha256 sidecar with an incorrect value; When another client GETs it; Then the response is always the server's own re-derived digest.
  ```

#### GIMLE-308 — Generated `maven-metadata.xml` (never stored, always fresh)

- **Category**: Artifact Registry
- **User story**: As a Maven client resolving latest/release, I want maven-metadata.xml generated fresh from the actual stored versions every time.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.handleRepositoryMetadata`, `generateMavenMetadataXml`; `ArtifactStore.versions`/`compareVersions`
- **Test coverage**: `AndvariServerMavenRepositoryTest` — `maven_metadata_lists_every_pushed_version_and_names_the_latest`, `maven_metadata_checksum_is_computed_over_the_generated_document`, `a_single_segment_module_has_an_empty_group_id_in_the_generated_metadata`; `ArtifactStoreTest#versions_sort_semver_aware_not_lexicographically`
- **Gherkin scenario**:
  ```gherkin
  Given three versions pushed out of order; When GET .../maven-metadata.xml; Then the document lists every version in semver order and names the correct latest/release.
  ```

#### GIMLE-309 — Maven GAV coordinate translation

- **Category**: Artifact Registry / Internal-Infra
- **User story**: As a maintainer, I want Maven-2 repository paths translated to/from Andvari's own coordinates deterministically.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/MavenCoordinates.java`
- **Test coverage**: `MavenCoordinatesTest` — `a_multi_segment_group_joins_with_the_artifact_id_by_dots`, `distinct_gavs_can_alias_to_the_same_module_coordinate`
- **Gherkin scenario**:
  ```gherkin
  Given com/gimle/examples/greeter/provider/1.0.0/provider-1.0.0.jar; When MavenCoordinates.parseArtifactFile parses it; Then it resolves to module com.gimle.examples.greeter.provider, artifactId provider, version 1.0.0.
  ```

#### GIMLE-310 — Defense-in-depth authorization (independent re-check, `ResourceKind.ARTIFACT`)

- **Category**: Authorization
- **User story**: As a platform security owner, I want Andvari to never trust a proxied "already authorized" claim from the control plane.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/AndvariServer.java`
- **Test coverage**: `AndvariServerTlsTest#a_forwarded_principal_wins_over_the_peer_certificate_and_is_re_checked`, `an_ungrouped_certificate_is_refused_by_the_independent_rbac_check`
- **Gherkin scenario**:
  ```gherkin
  Given a forwarded principal claims artifact access but doesn't hold ARTIFACT:WRITE; When Andvari independently re-checks; Then the push is rejected with 403.
  ```

#### GIMLE-311 — Module-scoped permission grants

- **Category**: Authorization
- **User story**: As a platform operator, I want to grant a principal artifact access scoped to a single module.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.authorizeArtifacts`
- **Test coverage**: `AndvariServerTlsTest` — `a_module_scoped_permission_grants_access_to_only_that_module`, `a_module_scoped_permission_cannot_list_the_full_catalog`
- **Gherkin scenario**:
  ```gherkin
  Given a principal holds a permission scoped to only module com.example.foo; When it pushes/pulls that module; Then granted; catalog listing (whole registry) is denied.
  ```

#### GIMLE-312 — Node pull-only artifact access, scoped to active assignments

- **Category**: Authorization
- **User story**: As a node agent, I want to pull only coordinates I currently hold a real assignment for, never push/delete.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.authorizeArtifacts`, `nodeHasAssignmentFor`, `coordinateMatches`
- **Test coverage**: `AndvariServerTlsTest#a_nodes_group_certificate_may_pull_only_coordinates_assigned_to_its_node`
- **Gherkin scenario**:
  ```gherkin
  Given node-42 holds an active assignment for module M:1.0.0; When it GETs /artifacts/M/1.0.0; Then granted; an unassigned coordinate, or any PUT/DELETE, is rejected.
  ```

#### GIMLE-313 — Dual audit logging for push/delete decisions

- **Category**: Internal-Infra / Authorization
- **User story**: As a compliance auditor, I want every artifact push and delete decision durably recorded, both as a log line and a queryable AuditEvent.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `AndvariServer.recordAudit`, `authorizeArtifacts`
- **Test coverage**: Exercised via `AndvariServerTlsTest`'s auth test set
- **Gherkin scenario**:
  ```gherkin
  Given a push or delete is attempted; When authorizeArtifacts completes; Then a SLF4J line and a durable AuditEvent are both recorded — reads (pulls) are not durably audited.
  ```

#### GIMLE-314 — Andvari's own console session story (`/auth/*`, bundled SPA)

- **Category**: API Server
- **User story**: As a human operator, I want to log in to Andvari's own operator console directly.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.handleAuthLogin`/`handleAuthLogout`/`handleAuthSession`/`serveConsole`; `AndvariMain`
- **Test coverage**: `AndvariServerAuthTest` — `login_session_and_logout_round_trip_with_no_client_certificate_at_all`, `a_wrong_password_is_rejected_with_no_cookie_set`
- **Gherkin scenario**:
  ```gherkin
  Given a valid Account exists; When logging in via Andvari's /auth/login; Then a distinct gimle_andvari_session cookie is issued and the bundled SPA is served at /console.
  ```

#### GIMLE-315 — mTLS server with dynamic TLS reload

- **Category**: Internal-Infra
- **User story**: As a platform operator, I want Andvari's certificate rotation to take effect without a process restart.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.reloadTlsMaterial`; `AndvariMain`'s cert-rotation ticker
- **Test coverage**: `AndvariServerTlsTest#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server`
- **Gherkin scenario**:
  ```gherkin
  Given Andvari's cert is rotated; When reloadTlsMaterial runs; Then a fresh mTLS connection succeeds without restart.
  ```

#### GIMLE-316 — Plaintext-mode loud supply-chain warning

- **Category**: Internal-Infra / Config
- **User story**: As a platform operator, I want an explicit warning at startup when Andvari runs in plaintext mode, naming the supply-chain risk.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/AndvariMain.java`
- **Test coverage**: NONE (logging-only)
- **Gherkin scenario**:
  ```gherkin
  Given gimle.transport.protocol=plaintext (default); When AndvariMain starts; Then it logs a warning naming that anyone reaching the port can push executable jars.
  ```

#### GIMLE-317 — Andvari observability instrumentation and Muninn shipping

- **Category**: Internal-Infra
- **User story**: As an operator, I want every Andvari endpoint's request metrics tracked and optionally shipped to Muninn.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.instrument`; `com.gimle.observability.AndvariMetrics`; `AndvariMain`
- **Test coverage**: `AndvariObservabilityTest` — `a_real_request_is_recorded_in_andvari_metrics`, `every_registered_route_is_independently_tagged`
- **Gherkin scenario**:
  ```gherkin
  Given a real request hits any Andvari endpoint; When the handler completes; Then AndvariMetrics records it per-endpoint.
  ```

#### GIMLE-318 — Process status endpoint (no RBAC gate)

- **Category**: API Server
- **User story**: As an operator, I want a basic status endpoint reachable without authentication.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariServer.handleStatus`
- **Test coverage**: `AndvariServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status`
- **Gherkin scenario**:
  ```gherkin
  Given Andvari is running; When GET /status; Then process-level status is returned with no RBAC check.
  ```

#### GIMLE-577 — Multi-jar publish with per-module tenant tagging (`kind: ArtifactSet`)

- **Category**: Artifact Registry
- **User story**: As a platform team publishing a multi-service application, I want to push every module jar in one command and tag each with the tenant it belongs to, so I don't have to run `gimle artifact push` once per jar, and Andvari finally records real ownership instead of the moduleId-scoping stand-in it used before.
- **Status**: Complete, including the admission-time tenant cross-check -- a deploying workload's own tenantId is checked against a registry-resolved coordinate's recorded tenant, rejecting on mismatch (either side untenanted skips the check). Still deliberately deferred: no server-side staging/two-phase commit for stricter all-or-nothing set semantics (publish stays pre-flight HEAD then sequential idempotent PUT, not a transaction), no migration of gimle-examples/orders-platform onto registry coordinates.
- **Confidence**: High
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/ArtifactStore.java` (tenant field, untenanted-to-tenanted backfill, tenant-swap conflict), `AndvariServer.java` (`X-Gimle-Artifact-Tenant` header, dual-scope authorization), `AndvariPeerSync.java` (tenant propagated across replica sync), `gimle-controlplane/src/main/java/com/gimle/controlplane/andvari/AndvariClient.java`, `api/ApiServer.java` (`/artifacts/*` proxy header passthrough), `gimle-module/src/main/java/com/gimle/module/artifactset/ArtifactSetManifest.java`, `ArtifactSetModuleEntry.java`, `ArtifactSetManifestParser.java`, `gimle-cli/src/main/java/com/gimle/cli/ArtifactSetCommand.java`, `ArtifactCommand.java` (`--tenant`), `ControlPlaneClient.java` (`head`/`putFile` with headers), `GimleCli.java` (`kind: ArtifactSet` dispatch), `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/ArtifactSetMojo.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/andvari/AndvariClient.java` (`HeadOutcome.Found#tenantId`), `api/ApiServer.java` (`admissionArtifact`'s deployingTenantId cross-check, all four workload kinds)
- **Test coverage**: `ArtifactStoreTest` (tenant round-trip through `meta.json`, untenanted-to-tenanted backfill exactly once, a further tenant swap still conflicts); `AndvariServerTest` (tenant header round-trip on HEAD/GET/PUT, catalog listing includes `tenantId`); `AndvariServerTlsTest` (tenant-scoped RBAC grants, a push cannot claim a tenant the caller holds no permission for, reads/deletes check the stored tenant not a caller claim); `ArtifactSetManifestParserTest` (`tenant:`/`modules:` grouping, push-order preservation, duplicate-path rejection across tenants and against `modules`); `ArtifactSetCommandTest` (real end-to-end `gimle apply` against a real in-process `AndvariServer`: multi-tenant push, pre-flight digest-conflict abort before any push, idempotent resume on re-apply); `ArtifactSetMojoTest` (per-submodule tenant-property override, generated manifest content); `ArtifactSetCommandTest` (admission cross-check: a mismatched tenantId rejected with 400 naming both tenants, a matching tenantId admitted, an untenanted workload against a tenanted coordinate skips the check)
- **Gherkin scenario**:
  ```gherkin
  Given an ArtifactSet manifest naming several module jars grouped under two different tenants, When "gimle apply -f" is run once, Then every jar is pushed and tagged with its own tenant, and a pre-existing digest conflict on any one coordinate aborts the whole set -- touching nothing -- before a single byte is pushed.
  ```

### gimle-muninn

#### GIMLE-319 — Node platform-log ingest

- **Category**: Logging
- **User story**: As a node agent, I want to ship my own platform log lines to a central sink, so that a node's logs survive that node dying and are queryable cluster-wide.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-muninn/src/main/java/com/gimle/muninn/MuninnServer.java` (`handleIngestNodeLogs`, `ingest`), `MuninnDayFileStore.appendLines`
- **Test coverage**: `MuninnServerLogsIngestTest#an_ingested_node_log_line_is_readable_back`, `#a_malformed_batch_is_rejected_entirely_and_nothing_from_it_is_readable`
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer in plaintext mode
  When a client POSTs a valid NDJSON batch to /ingest/logs/nodes/{nodeId}/{category}
  Then the response is 200 with the count of appended lines
  And each line becomes readable back from /logs/nodes/{nodeId}/{category}
  ```

#### GIMLE-320 — Instance-log ingest

- **Category**: Logging
- **User story**: As a node agent supervising a module instance, I want to ship that instance's application logs to Muninn on its behalf, so that per-instance log history survives the instance/worker being torn down.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java` (`handleIngestInstanceLogs`, `parseInstanceLogPath`)
- **Test coverage**: `MuninnServerLogsIngestTest#an_ingested_instance_log_line_is_readable_back`
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer
  When a client POSTs an NDJSON batch to /ingest/logs/instances/{deploymentName}/{instanceIndex}/{category}
  Then the response is 200
  And the lines are readable back from /logs/instances/{deploymentName}/{instanceIndex}/{category}
  ```

#### GIMLE-321 — Node/instance log read with cursor paging

- **Category**: Logging
- **User story**: As a console or CLI user, I want to page through a node's or instance's shipped log history oldest-first, so that I can browse historical logs beyond what one node currently retains locally.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java` (`read`, `handleReadNodeLogs`, `handleReadInstanceLogs`), `MuninnDayFileStore.readOlder`/`readAfter`
- **Test coverage**: `MuninnServerLogsIngestTest`, `MuninnDayFileStoreTest#read_after_and_read_older_round_trip_through_a_fresh_store_instance`
- **Gherkin scenario**:
  ```gherkin
  Given logs previously ingested for a nodeId/category
  When a client issues GET /logs/nodes/{nodeId}/{category}?cursor=...&limit=...
  Then the response returns up to `limit` lines older than `cursor`, oldest-first
  And includes olderCursor/newerCursor for continued paging
  ```

#### GIMLE-322 — `follow=true` rejection on Muninn reads

- **Category**: Logging
- **User story**: As an API designer, I want live-tail semantics rejected on Muninn's read surface, so that clients don't mistake shipped-history reads for a real-time tail Muninn cannot provide.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java#read`
- **Test coverage**: `MuninnServerLogsIngestTest#follow_true_is_rejected_since_muninn_only_serves_shipped_history`
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer
  When a client requests GET /logs/nodes/{nodeId}/{category}?follow=true
  Then the response is 400 explaining Muninn only serves shipped history
  ```

#### GIMLE-323 — Metrics ingest

- **Category**: Metrics
- **User story**: As any Gimlé process (control plane, Fafnir, Mimir, Andvari, agent), I want to ship my own Micrometer meter snapshots to Muninn, so that fleet-wide metrics history survives past any one process's own lifetime.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java` (`handleIngestMetrics`)
- **Test coverage**: `MuninnServerMetricsIngestTest#an_ingested_counter_and_timer_batch_round_trips_with_measurements_intact`, `#an_ingested_timer_with_percentiles_round_trips_the_percentiles_map`
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer
  When a client POSTs an NDJSON batch of counter/timer lines to /ingest/metrics/{processKind}/{processId}
  Then the response is 200
  And the measurements (including percentiles, when present) round-trip exactly on read
  ```

#### GIMLE-324 — Metrics read

- **Category**: Metrics
- **User story**: As a control-plane API proxying `/metrics-history/*`, I want to fetch a process's historical metric lines from Muninn, so that a gone process's own metrics remain visible.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java#handleReadMetrics`
- **Test coverage**: `MuninnServerMetricsIngestTest`
- **Gherkin scenario**:
  ```gherkin
  same shape as #3, scoped to `/metrics/{processKind}/{processId}`
  ```

#### GIMLE-325 — Traces ingest

- **Category**: Tracing
- **User story**: As any Gimlé process, I want to ship exported OpenTelemetry spans to Muninn, so that distributed traces survive past the exporting process and are queryable centrally.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java#handleIngestTraces`
- **Test coverage**: `MuninnServerTracesIngestTest#an_ingested_span_line_round_trips_with_attributes_intact`
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer
  When a client POSTs an NDJSON batch of span lines to /ingest/traces/{processKind}/{processId}
  Then the response is 200
  And span fields (traceId, spanId, name, custom attributes like http.method) round-trip on read
  ```

#### GIMLE-326 — Traces read

- **Category**: Tracing
- **User story**: As a control-plane API proxying `/traces-history/*`, I want to fetch historical span lines from Muninn.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java#handleReadTraces`
- **Test coverage**: `MuninnServerTracesIngestTest`
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer and traces previously ingested for a processKind/processId, When a client issues GET /traces/{processKind}/{processId}?cursor=...&limit=..., Then the response returns matching span lines, oldest-first, with paging cursors (the same shape as the node/instance log and metrics read endpoints).
  ```

#### GIMLE-327 — Day-bucketed JSON-lines store with oldest-first cursor semantics

- **Category**: Internal/Infra
- **User story**: As Muninn's implementation, I want one JSON-lines file per calendar day per subtree, so that an unbounded, ever-growing cluster-wide history stays organized for age-based retention rather than count-based rotation.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnDayFileStore.java` (`appendLines`, `readAllLinesSorted`)
- **Test coverage**: `MuninnDayFileStoreTest#lines_spanning_two_days_land_in_two_separate_day_files`, `#a_late_arriving_line_appends_into_the_existing_day_file_rather_than_overwriting_it`
- **Gherkin scenario**:
  ```gherkin
  Given lines timestamped across two different UTC calendar days
  When they are appended to the same subtree
  Then two separate day files are created
  And a late-arriving (out-of-order) line appends into the correct existing day file
  And a subsequent read returns all lines sorted oldest-first regardless of append/arrival order
  ```

#### GIMLE-328 — All-or-nothing batch validation on ingest

- **Category**: Internal/Infra
- **User story**: As a shipper (MuninnShipper), I want a malformed line anywhere in a batch to reject the whole batch, so that my shipping cursor never advances past data that wasn't actually made durable.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnDayFileStore.java#appendLines`, `#requireTimestamp`
- **Test coverage**: `MuninnDayFileStoreTest#a_malformed_line_rejects_the_whole_batch_and_writes_nothing`; `MuninnServerLogsIngestTest#a_malformed_batch_is_rejected_entirely_and_nothing_from_it_is_readable`
- **Gherkin scenario**:
  ```gherkin
  Given a batch containing one valid line and one line missing "timestamp"
  When the batch is appended
  Then the whole append throws IllegalArgumentException
  And nothing from the batch is written to disk
  ```

#### GIMLE-329 — Windows-safe on-disk path sanitization for colon-bearing processId

- **Category**: Internal/Infra
- **User story**: As an operator running Muninn on Windows, I want a `host:port`-shaped processId to round-trip through ingest/read without `InvalidPathException`, so that metrics/traces ingest from every non-AGENT process kind works cross-platform.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnDayFileStore.java#resolveSubtree`, `MuninnServer.java#PROCESS_ID_SEGMENT`
- **Test coverage**: `MuninnDayFileStoreTest#a_subtree_path_containing_a_colon_round_trips_without_an_invalid_path_error`
- **Gherkin scenario**:
  ```gherkin
  Given a subtreePath containing a literal colon (e.g. "metrics/CONTROLPLANE/127.0.0.1:8080")
  When lines are appended and then read back
  Then the write succeeds by substituting "_" for ":" in the on-disk directory name only
  And the returned data's processId (from the URL, never the directory name) is unchanged
  ```

#### GIMLE-330 — Path-segment validation / directory-traversal defense

- **Category**: Internal/Infra
- **User story**: As Muninn's HTTP surface, I want every `nodeId`/`category`/`deploymentName`/`processKind` path segment allow-listed against a strict pattern, so a crafted path segment can't escape the configured data root.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java` (`PATH_SEGMENT`, `PROCESS_ID_SEGMENT`, `parseTwoSegments`, `parseInstanceLogPath`)
- **Test coverage**: `MuninnServerLogsIngestTest#an_invalid_node_id_path_segment_is_rejected_before_touching_the_filesystem`, `MuninnServerMetricsIngestTest#an_invalid_process_kind_path_segment_is_rejected_before_touching_the_filesystem`, `MuninnServerTracesIngestTest` (same)
- **Gherkin scenario**:
  ```gherkin
  Given a request path with a segment like "..%2F..%2Fetc"
  When it is decoded and matched against PATH_SEGMENT
  Then the request is rejected with 400 before any filesystem access happens
  ```

#### GIMLE-331 — Age-based retention sweep

- **Category**: Observability
- **User story**: As an operator, I want day files older than a configurable retention window automatically deleted, so that an unbounded cluster-wide ingest history doesn't grow forever on disk.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RetentionSweeper.java` (`sweep`, `sweepQuietly`); wired in `MuninnMain.java` via `-Dgimle.muninn.retentionDays`/`retentionSweepIntervalSeconds`
- **Test coverage**: `RetentionSweeperTest#a_day_file_older_than_the_retention_window_is_deleted`, `#a_day_file_within_the_retention_window_survives`, `#sweeping_twice_is_idempotent...`, `#sweeping_a_data_root_that_does_not_exist_yet_is_a_no_op`
- **Gherkin scenario**:
  ```gherkin
  Given a day file older than the configured retentionDays
  When the retention sweep runs
  Then that file is deleted
  And a day file within the window is left untouched
  And sweeping twice, or sweeping a data root that doesn't exist yet, is a safe no-op
  ```

#### GIMLE-332 — Plaintext-default transport with loud unauthenticated-mode warning

- **Category**: Config
- **User story**: As an operator, I want Muninn to default to plaintext HTTP but log a loud warning that ingest/read are unauthenticated in that mode, so that I'm not silently exposed if I forget to enable TLS.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnMain.java` (startup warning), `MuninnServer.java#createHttpServer`
- **Test coverage**: `MuninnServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status`
- **Gherkin scenario**:
  ```gherkin
  Given gimle.transport.protocol is unset
  When MuninnMain starts
  Then MuninnServer binds a plain HttpServer
  And a WARN log line states every /ingest/* and read call is unauthenticated
  ```

#### GIMLE-333 — mTLS transport mode

- **Category**: Config
- **User story**: As an operator running a security-sensitive cluster, I want Muninn to require mutual TLS client certificates when configured, so that only cluster-issued identities can ingest or read observability data.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java#createHttpServer` (HttpsServer + `wantClientAuth`)
- **Test coverage**: `MuninnServerTlsTest#a_real_mtls_request_with_a_ca_signed_client_cert_succeeds`
- **Gherkin scenario**:
  ```gherkin
  Given gimle.transport.protocol=tls and valid cert/key/CA files
  When a client with a CA-signed leaf certificate connects
  Then the HTTPS request succeeds and reports transportProtocol=TLS
  ```

#### GIMLE-334 — Zero-downtime TLS material reload on certificate rotation

- **Category**: Config
- **User story**: As an operator, I want Muninn to rebind its HTTPS listener with freshly-rotated certificate material without a process restart, so that certificate rotation doesn't cause a service interruption.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java#reloadTlsMaterial`; ticker in `MuninnMain.java` (`OwnCertificateRotator.checkAndRotateIfDue`)
- **Test coverage**: `MuninnServerTlsTest#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server`
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer in TLS mode with an established client connection
  When the certificate/key files are overwritten and reloadTlsMaterial() is called
  Then a brand-new connection at the same port succeeds against the reloaded listener
  ```

#### GIMLE-335 — Node-identity check on node-log ingest

- **Category**: Internal/Infra
- **User story**: As Muninn, I want to verify (in TLS mode) that the calling certificate's CN equals the `nodeId` path segment before accepting node-log lines, so one compromised node's certificate can't overwrite another node's log stream.
- **Status**: Complete (code path exists and is exercised only in the always-true plaintext branch by tests; the mismatch branch itself is untested)
- **Confidence**: Medium
- **Source location(s)**: `MuninnServer.java#identityAllowedToIngestAsNode`
- **Test coverage**: NONE (only exercised implicitly in plaintext mode by ingest tests; no dedicated TLS-mode identity-mismatch test found in the module's test tree)
- **Gherkin scenario**:
  ```gherkin
  Given TLS mode and a client certificate with CN "node-x"
  When it POSTs to /ingest/logs/nodes/node-y/PLATFORM
  Then the request is rejected 403 (certificate identity does not match nodeId)
  ```

#### GIMLE-336 — Instance-owner check on instance-log ingest

- **Category**: Internal/Infra
- **User story**: As Muninn, I want to verify (in TLS mode) that the calling node currently holds a live assignment for the deployment/instance it's shipping logs for, so an agent can't ship logs claiming ownership of an instance it doesn't run.
- **Status**: Complete (implemented but untested at the module level)
- **Confidence**: Medium
- **Source location(s)**: `MuninnServer.java#identityAllowedToIngestAsInstanceOwner`
- **Test coverage**: NONE found in `gimle-muninn`'s own test tree (no TLS-mode instance-ownership-mismatch test)
- **Gherkin scenario**:
  ```gherkin
  Given TLS mode and StoreClient.listAssignments() has no assignment matching (deploymentName, instanceIndex, callerNodeId)
  When the caller POSTs to /ingest/logs/instances/{deploymentName}/{instanceIndex}/{category}
  Then the request is rejected 403
  ```

#### GIMLE-337 — Verified-certificate-presence check on metrics/traces ingest

- **Category**: Internal/Infra
- **User story**: As Muninn, I want to at least require a completed mTLS handshake with *some* CA-issued certificate for metrics/traces ingest (since processId isn't a fixed per-role CN), so a handshake that somehow completes with no peer certificate is rejected.
- **Status**: Complete (implemented, untested)
- **Confidence**: Medium
- **Source location(s)**: `MuninnServer.java#identityAllowedToIngestMetricsOrTraces`
- **Test coverage**: NONE (no dedicated test found)
- **Gherkin scenario**:
  ```gherkin
  Given TLS mode
  When a request to /ingest/metrics/{processKind}/{processId} arrives with no verifiable peer certificate
  Then it is rejected 403 ("no verified client certificate")
  ```

#### GIMLE-338 — Read surface has no RBAC/authorization re-check (documented-vs-actual gap)

- **Category**: Internal/Infra
- **User story**: CLAUDE.md and this class's own field-level comment (`MuninnServer.storeClient` javadoc, `MuninnMain`'s own class javadoc) both state Muninn "re-runs its own independent `Authorizer.authorize(...)` check on every read, gated on `ResourceKind.LOGS`" — but the actual `read()`/`handleReadNodeLogs`/`handleReadInstanceLogs`/`handleReadMetrics`/`handleReadTraces` code path in `MuninnServer.java` contains no call to `com.gimle.mimir.authz.Authorizer` anywhere; only the *ingest* handlers perform any identity check, and only in TLS mode. In plaintext mode (the default), and for every read regardless of mode, there is no authorization check at all beyond whatever the reverse proxy in front of Muninn enforces.
- **Status**: Partial — the RBAC re-check described in the module's own comments/CLAUDE.md is not implemented in code; reads are unauthenticated/unauthorized beyond transport-level TLS peer-cert presence (which itself is only checked on ingest, not reads)
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java` (absence of any `Authorizer` import/call, contradicting its own field comment at lines 76-80); `com.gimle.mimir.authz.Authorizer` exists in `gimle-mimir` but is never referenced from `gimle-muninn`
- **Test coverage**: NONE (no test asserts a read is rejected for an unauthorized principal)
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer (plaintext or TLS)
  When any client issues GET /logs/nodes/{nodeId}/{category} (or /metrics/*, /traces/*, /logs/instances/*)
  Then the request is served with no principal/authorization check of any kind
  ```

#### GIMLE-339 — `/status` operational endpoint

- **Category**: Observability
- **User story**: As an operator or console, I want an unauthenticated status endpoint reporting uptime and transport mode, so I can health-check Muninn without needing RBAC-scoped credentials.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnServer.java#handleStatus`
- **Test coverage**: `MuninnServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status`, `#a_non_get_status_request_is_rejected`
- **Gherkin scenario**:
  ```gherkin
  Given a running MuninnServer
  When GET /status is issued
  Then it returns 200 with uptimeSeconds and transportProtocol
  And a non-GET method is rejected with 405
  ```

### gimle-observability

#### GIMLE-340 — Default OpenTelemetry tracer installation

- **Category**: Tracing
- **User story**: As any Gimlé process, I want a working, idempotent process-wide `OpenTelemetry` tracer installed with a sensible default exporter, so cross-JVM spans are correctly parented even before a real shipping backend is configured.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GimleTracing.java` (`installDefault`)
- **Test coverage**: `GimleTracingTest#install_is_idempotent_and_yields_a_working_tracer`
- **Gherkin scenario**:
  ```gherkin
  Given no tracer provider has been installed yet in this JVM
  When GimleTracing.installDefault() is called (even multiple times)
  Then a working SdkTracerProvider using LoggingSpanExporter/SimpleSpanProcessor is installed exactly once
  And GlobalOpenTelemetry exposes it
  ```

#### GIMLE-341 — Configurable, batched span exporter installation

- **Category**: Tracing
- **User story**: As a process shipping spans to Muninn, I want to install an arbitrary `SpanExporter` behind a `BatchSpanProcessor`, so a network-bound exporter doesn't block the exporting thread per span.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GimleTracing.java#install`, `#installWithMuninnShipping`
- **Test coverage**: `GimleTracingInstallTest#install_swaps_in_the_given_exporter_and_a_real_span_reaches_it`
- **Gherkin scenario**:
  ```gherkin
  Given a custom SpanExporter (e.g. a capturing test double or MuninnSpanExporter)
  When GimleTracing.install(exporter) is called
  Then a real span created afterward reaches that exporter
  ```

#### GIMLE-342 — Bounded-wait tracer flush

- **Category**: Tracing
- **User story**: As a short-lived worker instance/job run, I want to force-flush pending spans on shutdown, so I don't lose final spans to the batch processor's own periodic interval never firing again.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GimleTracing.java#flush`
- **Test coverage**: `GimleTracingInstallTest#flush_forces_the_batch_processor_to_export_before_the_next_periodic_tick`, `#flush_before_any_install_is_a_noop`
- **Gherkin scenario**:
  ```gherkin
  Given a tracer installed with a BatchSpanProcessor and a pending unexported span
  When GimleTracing.flush() is called
  Then the span is exported before the call returns (bounded to 2 seconds)
  And calling flush() before any install is a no-op
  ```

#### GIMLE-343 — Periodic log-file shipping to Muninn

- **Category**: Logging
- **User story**: As an agent (or standalone control-plane/Fafnir/Mimir replica), I want to periodically tail my own log file and ship new lines to Muninn, with the shipping cursor advancing only on a successful ship, so a failed tick re-sends unshipped lines rather than losing them.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnShipper.java` (`startShippingLogFile`, `tickLogs`)
- **Test coverage**: `MuninnShipperTest#a_successful_tick_ships_new_log_lines_and_advances_the_cursor`, `#a_failed_tick_does_not_advance_the_cursor_and_retries_next_tick`
- **Gherkin scenario**:
  ```gherkin
  Given an active log file with new lines since the last shipped cursor
  When a shipping tick runs and Muninn accepts the batch (2xx)
  Then the cursor advances to the last shipped line's timestamp
  But when the POST fails, the cursor does not advance, and the same lines are retried next tick
  ```

#### GIMLE-344 — Periodic Micrometer metrics shipping

- **Category**: Metrics
- **User story**: As a process with a MeterRegistry, I want to periodically snapshot every meter and push it to Muninn as NDJSON, so metrics history is centrally queryable without Muninn needing a pull-based scrape endpoint.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnShipper.java#startShippingMetrics`, `#tickMetrics`
- **Test coverage**: `MuninnShipperTest#a_metrics_tick_ships_one_ndjson_line_per_meter`
- **Gherkin scenario**:
  ```gherkin
  Given a MeterRegistry with counters/timers
  When a metrics shipping tick fires
  Then one NDJSON line per meter is POSTed to Muninn's /ingest/metrics/* path
  ```

#### GIMLE-345 — One-shot trace-batch and prepared-batch shipping

- **Category**: Tracing
- **User story**: As `MuninnSpanExporter` (or a worker relaying pre-serialized batches through its agent), I want to hand a batch directly to Muninn with no periodic tick or cursor involved, so span export timing stays owned by the OTel SDK's own `BatchSpanProcessor`.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnShipper.java` (`shipTraceBatch`, `shipPreparedBatch`)
- **Test coverage**: `MuninnShipperTest#ship_trace_batch_is_a_one_shot_post_with_no_periodic_ticking`, `#ship_prepared_batch_posts_the_given_body_verbatim_with_no_periodic_ticking`, `#ship_prepared_batch_is_a_noop_for_an_empty_body`
- **Gherkin scenario**:
  ```gherkin
  Given a list of span lines, or a pre-serialized NDJSON body
  When shipTraceBatch(...)/shipPreparedBatch(...) is called
  Then exactly one immediate POST is made per configured endpoint, with no periodic ticker started
  And an empty batch/body is a no-op (no POST made)
  ```

#### GIMLE-346 — Multi-endpoint best-effort fan-out shipping

- **Category**: Internal/Infra
- **User story**: As a shipper configured with several Muninn replica addresses, I want a batch sent independently to every configured endpoint, succeeding overall if any one accepts it, so one unreachable replica never blocks delivery to the others.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnShipper.java` (`postNdjsonBody`, `postToOne`), `#parseEndpoints`
- **Test coverage**: `MuninnShipperTest#a_batch_ships_to_every_configured_endpoint`, `#a_batch_still_lands_on_reachable_endpoints_when_one_configured_endpoint_is_down`
- **Gherkin scenario**:
  ```gherkin
  Given two configured Muninn endpoints, one of which is down
  When a batch is shipped
  Then the batch still lands on the reachable endpoint
  And the tick is considered successful (cursor advances) because at least one endpoint accepted it
  ```

#### GIMLE-347 — In-memory (non-persisted) log-shipping cursor

- **Category**: Internal/Infra
- **User story**: As the system design, I want the log-shipping cursor kept in memory only (not persisted to disk), so I avoid a second persisted-cursor mechanism alongside `LogFileReader`'s own, accepting a small duplicate-history window on process restart.
- **Status**: Complete (deliberate, documented tradeoff, not a gap)
- **Confidence**: Medium
- **Source location(s)**: `MuninnShipper.java` (field `logCursor`, class javadoc)
- **Test coverage**: NONE (documented design decision; no explicit restart-simulation test in this module)
- **Gherkin scenario**:
  ```gherkin
  (documented tradeoff, not directly assertion-tested as a restart scenario)
  ```

#### GIMLE-348 — Micrometer meter → NDJSON codec

- **Category**: Internal/Infra
- **User story**: As the shipping pipeline, I want a pure (no-I/O) function turning a `MeterRegistry` snapshot into one NDJSON line per meter (including Timer percentiles when configured), so a worker's own relayed snapshot and a directly-shipping process produce byte-identical wire output.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MeterSnapshotCodec.java`
- **Test coverage**: `MeterSnapshotCodecTest#one_line_per_meter_with_the_meters_own_name`, `#a_timer_with_percentiles_ships_a_percentiles_map`, `#a_timer_without_percentiles_omits_the_percentiles_key`, `#an_empty_registry_produces_an_empty_string`
- **Gherkin scenario**:
  ```gherkin
  Given a registry with a Counter, a Gauge, and a Timer built with publishPercentiles
  When MeterSnapshotCodec.toNdjson(registry) is called
  Then each meter becomes one JSON line with name/type/tags/measurements
  And the Timer's line additionally carries a "percentiles" map
  And a Timer without percentiles configured omits the "percentiles" key entirely
  ```

#### GIMLE-349 — OpenTelemetry span → NDJSON codec

- **Category**: Internal/Infra
- **User story**: As the shipping pipeline, I want a pure function turning exported `SpanData` into one flat NDJSON line per span (fixed fields plus every attribute flattened onto the line), so `MuninnSpanExporter` and a worker's relaying exporter share byte-identical output.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SpanLineCodec.java`
- **Test coverage**: `SpanLineCodecTest#one_line_per_span_with_attributes_flattened_onto_it`, `#an_empty_batch_produces_an_empty_string`
- **Gherkin scenario**:
  ```gherkin
  Given a batch of SpanData with custom attributes (e.g. http.method)
  When SpanLineCodec.toNdjson(spans) is called
  Then each span becomes one line with timestamp/traceId/spanId/parentSpanId/name/kind/status
  And every span attribute is flattened directly onto that same line
  ```

#### GIMLE-350 — `MuninnSpanExporter` (OpenTelemetry SDK integration)

- **Category**: Tracing
- **User story**: As a process with tracing enabled, I want an `SpanExporter` implementation that ships every exported batch to Muninn via `MuninnShipper`, never surfacing a shipping failure back to the OTel SDK, so tracing itself never fails a request over an unreachable Muninn.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `MuninnSpanExporter.java`
- **Test coverage**: `MuninnSpanExporterTest#a_real_span_batch_reaches_the_stub_ingest_server_with_the_expected_shape`, `#export_never_throws_even_when_shipping_fails`
- **Gherkin scenario**:
  ```gherkin
  Given a MuninnSpanExporter wrapping a MuninnShipper pointed at a real ingest stub
  When export(spans) is called
  Then the spans reach the stub's /ingest/traces/* endpoint with the expected shape
  And export() always returns CompletableResultCode.ofSuccess(), even when shipping throws
  ```

#### GIMLE-351 — JFR-based per-module CPU/allocation attribution

- **Category**: Observability
- **User story**: As the platform, I want to attribute JFR execution-sample and allocation events to the module whose worker-thread naming convention they came from, so Tier-1 soft resource accounting can charge CPU/allocation to the correct module without per-module isolation.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `ThreadNameJfrAttributor.java`
- **Test coverage**: `ThreadNameJfrAttributorTest#construction_and_shutdown_do_not_throw`, `#register_and_unregister_module_do_not_throw`, `#unregistering_a_module_never_registered_does_not_throw` (no test directly asserts a classified sample producing a counter increment — JFR event emission isn't driven from the test)
- **Gherkin scenario**:
  ```gherkin
  Given a module registered under a thread-name prefix "gimle-<module>-<version>-"
  When a JFR jdk.ExecutionSample/jdk.ThreadAllocationStatistics event fires on a thread with that prefix
  Then gimle.module.cpu.samples / gimle.module.allocated.bytes counters are incremented tagged by module_prefix
  And events from unregistered/unclassifiable threads are ignored
  And JFR being unavailable degrades to "no samples" rather than failing the worker
  ```

#### GIMLE-352 — Per-process tagged Micrometer metrics wrappers

- **Category**: Metrics
- **User story**: As each Gimlé process kind (agent, control-plane API server, worker, store, Fafnir, Andvari), I want a dedicated request-latency/count/error-count metrics wrapper tagged by that process's own meaningful dimension (endpoint+verb, ModuleId, or RPC kind), so per-process request behavior is queryable without cross-process tag collisions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AgentMetrics.java`, `ApiServerMetrics.java`, `WorkerMetrics.java`, `StoreMetrics.java`, `FafnirMetrics.java`, `AndvariMetrics.java`, shared helper `TaggedRequestMetrics.java`
- **Test coverage**: `AgentMetricsTest`, `ApiServerMetricsTest`, `WorkerMetricsTest`, `StoreMetricsTest`, `FafnirMetricsTest` (e.g. `#record_request_increments_count_and_records_latency`, `#request_latency_timer_publishes_percentiles_for_muninn_shipping`, `#error_counter_is_not_created_when_no_error_ever_recorded`, `#different_endpoints_and_verbs_are_tagged_independently`)
- **Gherkin scenario**:
  ```gherkin
  Given a fresh metrics wrapper (e.g. ApiServerMetrics) with an in-memory SimpleMeterRegistry
  When recordRequest(endpoint, verb, latency, error=true) is called
  Then the latency Timer, the count Counter, and the errors Counter are all incremented/recorded
  And requestCount/errorCount return 0 for a tag combination never recorded
  And different endpoint/verb (or ModuleId, or rpcKind) combinations are tracked independently
  ```

#### GIMLE-353 — WorkerMetrics thread-count / metaspace gauges

- **Category**: Metrics
- **User story**: As a worker JVM, I want live gauges of per-module thread count and metaspace footprint (not cumulative counters), so a periodic metrics report reflects each module's current, not historical, resource footprint.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `WorkerMetrics.java` (`recordThreadCount`, `recordMetaspaceBytes`, `gaugeHolder`)
- **Test coverage**: `WorkerMetricsTest#thread_count_gauge_reflects_the_latest_recorded_value_not_the_first`, `#metaspace_gauge_reflects_the_latest_recorded_value_not_the_first`
- **Gherkin scenario**:
  ```gherkin
  Given WorkerMetrics.recordThreadCount(moduleId, 5) then recordThreadCount(moduleId, 9)
  When the gauge is read
  Then it reflects 9 (the latest value), not the first recorded value
  ```

#### GIMLE-354 — Fafnir authz-failure counter (rate-limiting signal)

- **Category**: Metrics
- **User story**: As Fafnir, I want a verb-tagged (not principal-tagged) counter of consecutive authorization failures, so `LoginThrottle`-based rate limiting on `/secrets/*` has a bounded-cardinality signal to key off regardless of fleet size.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `FafnirMetrics.java` (`recordAuthzFailure`, `authzFailureCount`)
- **Test coverage**: `FafnirMetricsTest#authz_failures_are_recorded_and_tagged_by_verb_only`, `#authz_failure_count_is_zero_before_any_failure_is_recorded`
- **Gherkin scenario**:
  ```gherkin
  Given a FafnirMetrics instance
  When recordAuthzFailure("GET") is called
  Then gimle.fafnir.authz.failures{verb=GET} increments
  And authzFailureCount for an unrecorded verb returns 0
  ```

#### GIMLE-355 — Muninn endpoint list parsing from config

- **Category**: Config
- **User story**: As a process reading `-Dgimle.agent.muninnEndpoint`-style config, I want a comma-separated address list parsed into a normalized endpoint list (trimmed, blanks dropped, single-address backward compatible), so multi-replica Muninn shipping is configurable via one property.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `MuninnShipper.java#parseEndpoints`
- **Test coverage**: NONE found as a dedicated test (only exercised indirectly via multi-endpoint shipping tests that construct `MuninnShipper` with an already-parsed `List<String>`)
- **Gherkin scenario**:
  ```gherkin
  Given a config value of "host1:9090, host2:9090,,host3:9090"
  When MuninnShipper.parseEndpoints(value) is called
  Then it returns ["host1:9090", "host2:9090", "host3:9090"], trimmed and blanks dropped
  And a null/blank value returns an empty list
  ```

### gimle-gateway

#### GIMLE-356 — Fabric-route HTTP-to-service dispatch

- **Category**: Gateway/Routing
- **User story**: As an operator, I want an HTTP path to invoke a named fabric service method by interface/version/method name, so external HTTP traffic can reach a hosted module's service without that module exposing its own listener.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayDispatcher.java` (`dispatchFabric`), `GatewayRoute.java` (`FabricRoute`)
- **Test coverage**: `GatewayDispatcherTest#a_string_argument_route_dispatches_and_returns_the_real_result`, `#a_no_argument_route_is_served_on_get`, `#an_int_argument_route_coerces_and_dispatches_correctly`
- **Gherkin scenario**:
  ```gherkin
  Given a FabricRoute "/greet" bound to interface Greeter, version 1, method "greet", ParamType.STRING
  When a POST /greet request arrives with body "Gimlé"
  Then ModuleContext#invokeServiceByName is called with the coerced argument
  And the response is 200 with the real return value as plain text
  ```

#### GIMLE-357 — Fabric-route argument coercion (`ParamType`)

- **Category**: Gateway/Routing
- **User story**: As a route author, I want a route's single argument type declared (NONE/STRING/INT/LONG/DOUBLE/BOOLEAN) and its plain-text HTTP body coerced accordingly, so a malformed request body is rejected cleanly rather than propagating a raw parse exception.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayRoute.java` (`FabricRoute.ParamType`, `coerce`, `wireTypeName`)
- **Test coverage**: `GatewayDispatcherTest#a_body_that_does_not_coerce_to_the_declared_param_type_returns_400`, `#the_wrong_http_method_for_a_fabric_route_returns_405`
- **Gherkin scenario**:
  ```gherkin
  Given a FabricRoute with ParamType.INT
  When a request body that isn't a valid integer is POSTed
  Then the response is 400 with a message naming the expected type
  And a NONE-typed route is only ever served on GET; every other type is served on POST
  ```

#### GIMLE-358 — Vessel-route HTTP reverse-proxy dispatch

- **Category**: Gateway/Routing
- **User story**: As an operator, I want an HTTP path to reverse-proxy verbatim (method, path, body) to a live instance of a named deployment's named port, so I can front any HTTP-speaking Gimlé deployment through one gateway without the gateway understanding its protocol.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayDispatcher.java` (`dispatchVessel`), `GatewayRoute.java` (`VesselRoute`)
- **Test coverage**: `GatewayDispatcherTest#a_vessel_route_proxies_to_the_real_target_with_method_path_body_and_response_intact`
- **Gherkin scenario**:
  ```gherkin
  Given a VesselRoute "/api/orders" -> deployment "orders-service", port "HTTP_PORT"
  When a PUT /api/orders request with a JSON body arrives
  Then a resolved live instance receives the same method, path, and body unchanged
  And the gateway's response is exactly that instance's status and body
  ```

#### GIMLE-359 — Vessel-endpoint resolution with TTL cache

- **Category**: Gateway/Routing
- **User story**: As the gateway, I want a deployment's live endpoint list cached for a bounded TTL rather than refreshed via a relay round trip on every request, so the gateway itself never becomes the latency bottleneck.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `VesselEndpointCache.java` (`resolve`, `endpointsFor`, `isStale`)
- **Test coverage**: `VesselEndpointCacheTest#a_call_within_the_ttl_does_not_relay_again`, `#a_call_past_the_ttl_relays_again`
- **Gherkin scenario**:
  ```gherkin
  Given a resolved endpoint list cached at time T with TTL=5s
  When resolve() is called again before T+5s
  Then no new relay call is made (cache hit)
  When resolve() is called after T+5s
  Then a fresh relay call is made
  ```

#### GIMLE-360 — Round-robin load balancing over ready vessel endpoints

- **Category**: Gateway/Routing
- **User story**: As the gateway, I want repeated requests to a vessel route spread round-robin across every endpoint that reports both a host and the named port, so no single instance absorbs all traffic.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `VesselEndpointCache.java` (`resolve`, `readyTargets`, `roundRobinCursors`)
- **Test coverage**: `VesselEndpointCacheTest#round_robins_across_every_ready_endpoint_over_repeated_calls`, `#skips_endpoints_missing_the_named_port_or_the_host`; `GatewayDispatcherTest#a_vessel_route_round_robins_across_ready_instances_over_repeated_real_calls`
- **Gherkin scenario**:
  ```gherkin
  Given two ready endpoints for a deployment
  When four consecutive requests are dispatched
  Then both endpoints are hit, alternating in round-robin order
  And an endpoint missing the named port or missing a host is never selected
  ```

#### GIMLE-361 — Stale-cache fallback on endpoint-refresh failure

- **Category**: Gateway/Routing
- **User story**: As the gateway, I want a failed endpoint refresh (non-2xx relay response, or an unparsable body) to fall back to the still-cached endpoint list when one exists, so one bad refresh doesn't fail every in-flight request to a previously-healthy deployment.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `VesselEndpointCache.java#fallbackOrFail`
- **Test coverage**: `VesselEndpointCacheTest#a_non_2xx_refresh_falls_back_to_the_stale_cached_list`, `#a_terminal_relay_status_with_nothing_cached_yet_is_a_clear_error`, `#an_unparsable_relay_body_with_nothing_cached_yet_is_a_clear_error`
- **Gherkin scenario**:
  ```gherkin
  Given a cached endpoint list from a prior successful refresh
  When the TTL expires and the next refresh returns 504
  Then resolve() still returns the previously-cached Ready endpoint, with a warning logged
  But if no list was ever cached and the refresh fails, resolve() returns Unavailable
  ```

#### GIMLE-362 — Vessel-route error surfacing (no ready endpoint / connect failure)

- **Category**: Gateway/Routing
- **User story**: As a client of a vessel route, I want a clear error status (not a misleading 200) when a target deployment has no ready endpoints or refuses the outbound connection, so I can distinguish "downstream unhealthy" from "call succeeded."
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `VesselEndpointCache.java` (`resolve` — the 503 case), `VesselProxyClient.java` (`proxy` — IOException → 502)
- **Test coverage**: `GatewayDispatcherTest#a_vessel_route_for_a_deployment_with_no_usable_endpoints_returns_a_clear_error_not_a_200`, `#a_vessel_route_reports_a_target_that_refuses_the_connection_as_a_clean_502`; `VesselEndpointCacheTest#an_empty_endpoint_list_is_a_clear_error_not_a_silent_200`
- **Gherkin scenario**:
  ```gherkin
  Given a deployment with zero live/ready endpoints
  When a vessel route is dispatched
  Then the response is 503
  Given a resolved endpoint that refuses the TCP connection
  When the proxy call is attempted
  Then the response is a clean 502, not an uncaught exception
  ```

#### GIMLE-363 — Route-table config DSL parsing

- **Category**: Config
- **User story**: As an operator, I want gateway routes declared in a compact line-oriented text format (`FABRIC ...` / `VESSEL ...`) read from `gateway.routes` config, so routing behavior is fully config-driven with no code change per deployment.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayRouteConfig.java`
- **Test coverage**: `GatewayRouteConfigTest#parses_a_mix_of_fabric_and_vessel_routes_ignoring_blank_lines_and_comments`, `#an_unknown_kind_token_is_rejected`, `#a_fabric_line_with_the_wrong_number_of_fields_is_rejected`, `#a_non_integer_fabric_version_is_rejected`, `#a_fabric_param_type_outside_the_v1_restriction_is_rejected_at_parse_time`
- **Gherkin scenario**:
  ```gherkin
  Given a gateway.routes value mixing FABRIC and VESSEL lines, blank lines, and "#" comments
  When GatewayRouteConfig.parse(text) is called
  Then blank/comment lines are ignored and each remaining line becomes the correct route type
  And a malformed line (wrong field count, unknown kind, bad majorVersion/paramType) throws GatewayConfigException naming the line number
  ```

#### GIMLE-364 — Duplicate route-path rejection at config-parse time

- **Category**: Config
- **User story**: As an operator, I want two routes (of either kind) declaring the same HTTP path rejected at config-parse time (module startup), rather than silently letting one shadow the other on request, so a config mistake fails fast and loudly.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayRouteConfig.java#parse` (`seenPaths` check)
- **Test coverage**: `GatewayRouteConfigTest#a_duplicate_route_path_across_fabric_and_vessel_is_rejected`, `#a_duplicate_fabric_route_path_is_rejected`, `#a_duplicate_vessel_route_path_is_rejected`
- **Gherkin scenario**:
  ```gherkin
  Given a config with two lines both declaring path "/api/orders" (one FABRIC, one VESSEL, or same-kind duplicates)
  When GatewayRouteConfig.parse is called
  Then it throws GatewayConfigException before any route table is built
  ```

#### GIMLE-365 — Gateway HTTP server bootstrap via module lifecycle hooks

- **Category**: Gateway/Routing
- **User story**: As the gateway module, I want its listen port and route table read from `ModuleContext#config(...)` and a plain `HttpServer` bound on `onStart`, torn down on `onStop`, so it participates in the platform's own module lifecycle rather than needing external process management.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayHooks.java` (`onStart`, `onStop`, `requiredIntConfig`)
- **Test coverage**: NONE found in `gimle-gateway/src/test/java` (no direct `GatewayHooks` test; only `GatewayDispatcher`/`GatewayRouteConfig`/`VesselEndpointCache` are unit-tested)
- **Gherkin scenario**:
  ```gherkin
  Given required config keys gateway.port and gateway.routes are present
  When onStart(ctx) runs
  Then an HttpServer binds on 0.0.0.0:{port}, one context per configured route, using a virtual-thread-per-task executor
  And onStop() stops the server and flips readiness to false
  And a missing/non-integer gateway.port throws GatewayConfigException before binding
  ```

#### GIMLE-366 — Gateway liveness and readiness probes

- **Category**: Gateway/Routing
- **User story**: As the platform's self-healing supervisor, I want a liveness probe that's always true once loaded and a readiness probe gated on the HTTP listener actually being bound, so traffic isn't routed to a gateway instance before it can accept connections.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayLivenessProbe.java`, `GatewayReadinessProbe.java`, `GatewayHooks.java` (`ready` AtomicBoolean)
- **Test coverage**: NONE found (no dedicated probe test in `gimle-gateway`'s test tree)
- **Gherkin scenario**:
  ```gherkin
  Given GatewayHooks has not yet run onStart
  Then GatewayReadinessProbe.isReady() returns false
  When onStart completes successfully (port bound)
  Then GatewayReadinessProbe.isReady() returns true
  And GatewayLivenessProbe.isAlive() always returns true
  ```

#### GIMLE-367 — HTTP status-code error mapping across the dispatcher

- **Category**: Gateway/Routing
- **User story**: As a gateway client, I want a consistent, never-throwing mapping of every known failure mode to an HTTP status (404 unknown path, 405 wrong verb, 400 bad body, 502 downstream failure, 503 no ready endpoint), so `GatewayDispatcher#dispatch` never requires its caller to catch an exception to produce a response.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayDispatcher.java#dispatch` and its `dispatchFabric`/`dispatchVessel` helpers
- **Test coverage**: `GatewayDispatcherTest#an_unknown_path_returns_404`, `#the_wrong_http_method_for_a_fabric_route_returns_405`, `#a_downstream_fabric_call_that_throws_returns_502`
- **Gherkin scenario**:
  ```gherkin
  Given a request to a path with no configured route
  Then the response is 404
  Given a fabric route invoked with the wrong HTTP verb
  Then the response is 405
  Given a downstream fabric call that throws
  Then the response is 502
  ```

#### GIMLE-368 — Boot-only platform-layer JPMS workaround (`requires static`)

- **Category**: Internal/Infra
- **User story**: As the module system, I want `gimle-gateway` to declare `com.gimle.module`/`com.gimle.core`/`org.slf4j` as `requires static` rather than plain `requires`, so JPMS `Configuration.resolve` succeeds even though no real `gimle-api` platform module exists yet — resolution then relies on `ModuleLayerFactory` separately granting readability to the platform's unnamed module at runtime.
- **Status**: Complete (deliberate, documented stopgap per module's own javadoc, not a gap)
- **Confidence**: Medium
- **Source location(s)**: `gimle-gateway/src/main/java/module-info.java`
- **Test coverage**: Indirectly covered by `RealBundledHookAndProbeInvocationTest` in `gimle-worker` (per CLAUDE.md, established for the same pattern in `greeter-provider`/`greeter-consumer`); no dedicated gateway-specific test found in `gimle-gateway` itself
- **Gherkin scenario**:
  ```gherkin
  (structural/build-time behavior, not a runtime scenario)
  ```

#### GIMLE-369 — Vessel proxy: no TLS, no header forwarding (v1 scope limitation)

- **Category**: Gateway/Routing
- **User story**: As implemented, `VesselProxyClient` forwards only method, path, and body to a resolved vessel target — plaintext only (no TLS support at all for the gateway's outbound or inbound side) and no request/response header forwarding in either direction (documented as deliberate v1 scope, not an oversight: correct header forwarding requires hop-by-hop stripping, `Host` rewriting, and avoiding double `Content-Length`, none of which is attempted).
- **Status**: Partial — explicitly out of scope for v1, documented in the class's own javadoc rather than a discovered gap
- **Confidence**: High
- **Source location(s)**: `VesselProxyClient.java` (class javadoc, `proxy` method — only method/path/body set on the outbound request)
- **Test coverage**: `GatewayDispatcherTest#a_vessel_route_proxies_to_the_real_target_with_method_path_body_and_response_intact` confirms what *is* forwarded; no test exercises header forwarding since none exists
- **Gherkin scenario**:
  ```gherkin
  Given a vessel target that reads a custom request header
  When a request carrying that header is proxied through the gateway
  Then the header is not forwarded to the target (only method/path/body cross)
  And the connection to the target is always plain HTTP, never HTTPS
  ```

#### GIMLE-370 — Fabric route "quiet success" ambiguity for a misrouted service name

- **Category**: Gateway/Routing
- **User story**: As implemented, a `FabricRoute` naming an interface/method nothing currently exports is served as `200` with an empty body rather than a `404`/`502`, because `ModuleContext#invokeServiceByName`'s `Optional.empty()` return means either "no exporter known" or "found and returned void/null," and the dispatcher has no separate signal to distinguish the two.
- **Status**: Partial — documented, accepted limitation of routing purely by name with no separate existence check, not an oversight
- **Confidence**: High
- **Source location(s)**: `GatewayDispatcher.java#dispatchFabric` (own javadoc explicitly documents this as a known v1 limitation)
- **Test coverage**: `GatewayDispatcherTest#a_fabric_route_naming_a_service_nothing_exports_is_served_as_200_with_an_empty_body`
- **Gherkin scenario**:
  ```gherkin
  Given a FabricRoute naming a service interface nothing currently exports
  When a request is dispatched to that route
  Then the response is 200 with an empty body (not an error)
  ```

#### GIMLE-570 — Gateway virtual-host routing and Service-backed (SERVICE) route kind

- **Category**: Gateway/Routing
- **User story**: As a platform operator, I want to declare gateway routes constrained to a specific Host header, and route a path to a control-plane-declared Service's live endpoints, so one gateway can front multiple virtual hosts and proxy to a Service directly instead of only a named Deployment port or a fabric call.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-gateway/src/main/java/com/gimle/gateway/GatewayDispatcher.java` (`selectRoute`, `dispatchService`), `gimle-gateway/src/main/java/com/gimle/gateway/GatewayRoute.java` (`host()`, `ServiceRoute`), `gimle-gateway/src/main/java/com/gimle/gateway/GatewayRouteConfig.java` (`HOST <hostname>` prefix, `SERVICE` route kind), `gimle-gateway/src/main/java/com/gimle/gateway/ServiceEndpointCache.java`
- **Test coverage**: `GatewayDispatcherTest` (a_host_constrained_route_matches_only_the_declared_host_header, a_host_constrained_route_falls_through_to_404_on_a_mismatched_host_header, a_host_unconstrained_route_is_unaffected_by_host_based_routing, a_host_constrained_route_falls_through_to_a_host_unconstrained_sibling_at_the_same_path, a_service_route_for_a_service_with_no_ready_endpoints_returns_a_clear_error_not_a_200, a_service_route_reuses_the_cached_endpoint_list_across_dispatcher_instances_seam); `ServiceEndpointCacheTest` (resolves_a_ready_endpoint_to_its_host_and_port, relays_to_the_services_endpoints_path_for_the_named_service, skips_endpoints_missing_a_usable_host_or_port, round_robins_across_every_ready_endpoint_over_repeated_calls, a_call_within_the_ttl_does_not_relay_again, a_call_past_the_ttl_relays_again, a_non_2xx_refresh_falls_back_to_the_stale_cached_list, a_terminal_relay_status_with_nothing_cached_yet_is_a_clear_error, an_unparsable_relay_body_with_nothing_cached_yet_is_a_clear_error, an_empty_endpoint_list_is_a_clear_error_not_a_silent_200, a_response_with_no_endpoints_field_at_all_is_a_clear_error)
- **Gherkin scenario**:
  ```gherkin
  Given two routes declared at the same path, one HOST orders.example.com and one host-unconstrained, When a request arrives with Host: orders.example.com, Then the host-constrained route serves it; When a request arrives with a different or missing Host header, Then the host-unconstrained sibling serves it instead.
  Given a route declared HOST orders.example.com only, When a request arrives with a non-matching Host header, Then the response is 404, not a fallback to any other route at that path.
  Given a SERVICE route naming a control-plane Service with a live ready endpoint, When dispatched, Then the request is proxied verbatim to that endpoint's host:port, resolved through ServiceEndpointCache's own TTL-bounded relay to GET /services/{name}/endpoints; When the Service has no ready endpoint, Then a clear error status is returned, not a silent 200.
  ```

### gimle-cli

#### GIMLE-371 — Deployment resource management (get/apply/delete)

- **Category**: CLI
- **User story**: As a platform operator, I want to list, apply, and delete Deployment manifests from the command line.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/DeploymentsCommand.java`, `GimleCli.java`, `ManifestFiles.java`
- **Test coverage**: `GimleCliTest.apply_then_get_deployments_round_trips`, `apply_then_delete_removes_the_deployment`, `apply_then_get_deployments_as_json_round_trips`, `apply_and_delete_deployment_produce_real_json_under_json_output_format`
- **Gherkin scenario**:
  ```gherkin
  Given a control plane reachable at --server host:port, When I run "gimle apply -f orders-service.yaml" (kind:Deployment), Then /deployments/<name> is PUT; "gimle get deployments" lists it.
  ```

#### GIMLE-372 — Job resource management (get/apply/delete)

- **Category**: CLI
- **User story**: As a platform operator, I want to submit and inspect one-shot Job manifests.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/JobsCommand.java`
- **Test coverage**: `GimleCliTest.apply_then_get_jobs_round_trips`, `apply_then_delete_removes_the_job`
- **Gherkin scenario**:
  ```gherkin
  Given a Job manifest (kind:Job), When "gimle apply -f cleanup.yaml", Then /jobs/<name> is PUT; delete then get returns 404/exit 1.
  ```

#### GIMLE-373 — CronJob management incl. manual trigger

- **Category**: CLI
- **User story**: As a platform operator, I want to schedule recurring Jobs and force-fire one immediately.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/CronJobsCommand.java`, `GimleCli.handleCronJobVerb`
- **Test coverage**: `GimleCliTest.apply_then_get_cronjobs_round_trips`, `cronjob_trigger_fires_immediately_and_the_generated_job_is_real`, `cronjob_trigger_on_an_unknown_cronjob_fails`
- **Gherkin scenario**:
  ```gherkin
  Given an applied CronJob "trigger-me", When "gimle cronjob trigger trigger-me", Then prints "cronjob/trigger-me triggered -> job/...-<epochSeconds>"; unknown cronjob fails with exit 1.
  ```

#### GIMLE-374 — DaemonSet resource management

- **Category**: CLI
- **User story**: As a platform operator, I want to deploy a module to every eligible node without setting a replica count.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/DaemonSetsCommand.java`
- **Test coverage**: `GimleCliTest.apply_then_get_daemonsets_round_trips`, `apply_then_delete_removes_the_daemonset`
- **Gherkin scenario**:
  ```gherkin
  Given a manifest (kind:DaemonSet), When "gimle apply -f node-exporter.yaml", Then /daemonsets/<name> is PUT; no "scale" verb exists (topology-derived, not operator-set).
  ```

#### GIMLE-375 — StatefulSet resource management

- **Category**: CLI
- **User story**: As a platform operator, I want to deploy modules with sticky per-instance node placement.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/StatefulSetsCommand.java`
- **Test coverage**: `GimleCliTest.apply_then_get_statefulsets_round_trips`, `apply_then_delete_removes_the_statefulset`
- **Gherkin scenario**:
  ```gherkin
  Given a manifest (kind:StatefulSet, replicas:3), When "gimle apply -f orders-statefulset.yaml", Then /statefulsets/<name> is PUT.
  ```

#### GIMLE-376 — Node inventory and cordon/uncordon

- **Category**: CLI
- **User story**: As a platform operator, I want to list registered nodes and cordon a node before maintenance.
- **Status**: Complete (cordon/uncordon lacks dedicated test coverage)
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/NodesCommand.java`
- **Test coverage**: `GimleCliTest.get_nodes_lists_a_registered_node`, `get_nodes_as_json_includes_the_node_id_field`
- **Gherkin scenario**:
  ```gherkin
  Given node "node-a" registered, When "gimle cordon node-a", Then POST /nodes/node-a/cordon succeeds and prints "node/node-a cordoned".
  ```

#### GIMLE-377 — Instance lifecycle event timeline

- **Category**: CLI
- **User story**: As an operator debugging a misbehaving instance, I want to see its full lifecycle event history.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/EventsCommand.java`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given deployment "orders-service" index 0, When "gimle events orders-service 0", Then GET /events?deployment=orders-service&instance=0 results are printed.
  ```

#### GIMLE-378 — Tenant management and quota configuration

- **Category**: CLI
- **User story**: As a cluster administrator, I want to create/inspect/delete tenants with explicit quotas.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/TenantsCommand.java`
- **Test coverage**: `GimleCliTest.set_tenant_then_get_tenants_round_trips`, `set_and_delete_tenant_produce_real_json_under_json_output_format`
- **Gherkin scenario**:
  ```gherkin
  Given "gimle set tenant acme --max-memory-bytes 1000000000 --max-cpu-millicores 4000 --max-instances 10", Then PUT /tenants/acme is sent; "gimle get tenants" lists "acme".
  ```

#### GIMLE-379 — Tenant plain configuration key/value store

- **Category**: CLI
- **User story**: As an operator, I want to set/read/delete plaintext config entries scoped to a tenant.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/ConfigCommand.java`
- **Test coverage**: `GimleCliTest.set_and_get_config_round_trips`, `set_and_delete_config_produce_real_json_under_json_output_format`
- **Gherkin scenario**:
  ```gherkin
  Given tenant "acme" exists, When "gimle set config acme greeting hello", Then PUT /config/acme/greeting; delete then get fails.
  ```

#### GIMLE-380 — Versioned secrets management (Fafnir proxy)

- **Category**: CLI / Security
- **User story**: As an operator, I want to set, read (by version), list, delete/destroy, and view version history of secrets, and rotate the active key.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/SecretCommand.java`
- **Test coverage**: `GimleCliTest.secret_set_then_get_round_trips_the_plaintext_value`, `secret_list_shows_the_key_without_ever_printing_a_value`, `secret_versions_lists_every_claimed_version_after_two_writes`, `secret_get_with_an_explicit_version_reads_the_historical_value`, `secret_delete_then_get_returns_not_found`, `secret_rotate_key_returns_an_incrementing_active_key_id`
- **Gherkin scenario**:
  ```gherkin
  Given "gimle secret set acme db-password --value hunter2", Then base64-encoded PUT to /secrets/acme/db-password; two writes yield 2 versions; "rotate-key" twice increments activeKeyId (1 then 2).
  ```

#### GIMLE-381 — Artifact registry client (push/list/get/delete)

- **Category**: CLI / Build Tooling
- **User story**: As a developer, I want to push a module jar to the artifact registry, browse coordinates/versions, and download/delete artifacts.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/ArtifactCommand.java`
- **Test coverage**: NONE found in GimleCliTest.java (exercised indirectly by AndvariRegistryIT in gimle-smoke-tests)
- **Gherkin scenario**:
  ```gherkin
  Given "gimle artifact push target/orders-1.0.0.jar", Then the coordinate is read from the jar's own gimle-module.yaml and streamed to PUT /artifacts/<id>/<version>; a second identical push reports "already present".
  ```

#### GIMLE-382 — Log viewing and live tailing

- **Category**: CLI
- **User story**: As an operator, I want to fetch recent log lines and optionally follow them live like kubectl logs -f.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/LogsCommand.java`
- **Test coverage**: NONE found in GimleCliTest.java
- **Gherkin scenario**:
  ```gherkin
  Given "gimle logs instance/orders-service/0", Then GET /logs/instances/orders-service/0?category=APPLICATION&limit=200; "--follow" opens a chunked GET with follow=true and prints new lines until interrupted.
  ```

#### GIMLE-383 — Audit trail query

- **Category**: CLI / Security
- **User story**: As a security-conscious operator, I want to query the cross-resource audit log filtered by principal, resource kind, tenant, time, and limit.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/AuditCommand.java`
- **Test coverage**: `GimleCliTest.audit_list_with_no_filters_succeeds_and_is_empty_in_plaintext_mode`, `audit_list_accepts_every_filter_flag_without_a_malformed_request`, `audit_command_without_the_list_verb_prints_usage_and_nonzero_exit`
- **Gherkin scenario**:
  ```gherkin
  Given "gimle audit list --principal alice --resource DEPLOYMENT --tenant acme --since 0 --limit 10", Then GET /audit?... is called; "gimle audit" alone prints usage with exit 1.
  ```

#### GIMLE-384 — RBAC role management

- **Category**: CLI / Security
- **User story**: As a cluster administrator, I want to define named roles with resource:verb[:tenant] permission grants.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/RolesCommand.java`
- **Test coverage**: `GimleCliTest.set_role_then_get_roles_round_trips_then_delete`
- **Gherkin scenario**:
  ```gherkin
  Given "gimle set role deployment-reader --permission deployment:read --permission config:write:acme", Then PUT /roles/deployment-reader; deleted role then get returns exit 1.
  ```

#### GIMLE-385 — RBAC role binding management

- **Category**: CLI / Security
- **User story**: As a cluster administrator, I want to bind a user or group subject to a named role.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/RoleBindingsCommand.java`
- **Test coverage**: `GimleCliTest.set_rolebinding_then_get_rolebindings_round_trips_then_delete`
- **Gherkin scenario**:
  ```gherkin
  Given "gimle set rolebinding b1 --subject user:alice --role cluster-admin", Then PUT /rolebindings/b1; "gimle get rolebindings" lists "user:alice".
  ```

#### GIMLE-386 — Operator account management

- **Category**: CLI / Security
- **User story**: As a cluster administrator, I want to create/reset a username+password account without the password ever being persisted/displayed in plaintext by the CLI.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/AccountsCommand.java`
- **Test coverage**: `GimleCliTest.set_account_then_get_accounts_round_trips_and_never_leaks_the_password_hash`
- **Gherkin scenario**:
  ```gherkin
  Given "gimle set account admin --password s3cret-password", Then PUT /accounts/admin sent; JSON output includes "username" but never "passwordHash" or the raw password.
  ```

#### GIMLE-387 — Certificate lifecycle management (bootstrap token, CSR request/status/approve, renewal)

- **Category**: CLI / Security
- **User story**: As an operator/node bootstrapping into the cluster, I want to mint bootstrap tokens, submit/poll/approve CSRs, and renew my own client cert.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/CertCommand.java`
- **Test coverage**: NONE in GimleCliTest.java (covered by control-plane-side bootstrap tests)
- **Gherkin scenario**:
  ```gherkin
  Given "gimle cert request --purpose operator --out-cert op.crt --out-key op.key" against a trust-only connection, Then a keypair/CSR is generated locally, private key written immediately, and CSR POSTed unauthenticated to /bootstrap/csr; a due-for-renewal cred triggers a warning on any other command.
  ```

#### GIMLE-388 — Dual table/JSON output formatting

- **Category**: CLI / Internal-Infra
- **User story**: As a scripting user, I want every command's output available as either a table or raw JSON via -o json.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/OutputFormat.java`
- **Test coverage**: Exercised implicitly throughout GimleCliTest via -o json assertions
- **Gherkin scenario**:
  ```gherkin
  Given any read verb with "-o json", Then raw JSON printed; default format prints a tab-separated table or "No resources found.".
  ```

#### GIMLE-389 — kubectl-shaped global flag parsing, manifest-kind apply dispatch, and mTLS/leader-aware HTTP client

- **Category**: Internal-Infra
- **User story**: As a CLI developer, I want a single shared dispatcher parsing --server/-o/GIMLE_SERVER, routing apply -f by manifest kind, and a shared HTTP client transparently following leader redirects.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/GimleCli.java`, `Flags.java`, `ManifestFiles.java`, `ControlPlaneClient.java`, `ApiResponse.java`, `CliException.java`
- **Test coverage**: `GimleCliTest.a_bare_invocation_with_no_verb_prints_usage_rather_than_a_server_configuration_error`, `missing_server_configuration_is_a_clear_error`, `an_unreachable_control_plane_produces_a_clear_error_and_nonzero_exit`, `a_malformed_server_response_produces_a_clear_error_not_a_stack_trace`, `a_404_produces_a_clear_error_and_nonzero_exit`, `unknown_verb_prints_usage_and_nonzero_exit`
- **Gherkin scenario**:
  ```gherkin
  Given no --server and no GIMLE_SERVER, When any verb runs, Then "no control-plane server configured" and exit 1, no stack trace; a 307 with no Location reports "control plane leader is currently unknown".
  ```

### gimle-hilmir

#### GIMLE-390 — Topology validation (`hilmir validate`)

- **Category**: Release Management
- **User story**: As an operator authoring a multi-machine cluster topology, I want an offline rule catalog checked before ever booting it.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/validate/TopologyValidator.java`, `Finding.java`, `Severity.java`, `topology/Topology.java`, `TopologyParser.java`
- **Test coverage**: `TopologyValidatorTest` (extensive, ~25+ tests); `HilmirMainTest.validate_exits_zero_for_a_topology_with_no_error_severity_findings`, `validate_exits_one_and_lists_errors_before_warnings_for_a_broken_topology`
- **Gherkin scenario**:
  ```gherkin
  Given a topology declaring zero machines, When "hilmir validate -f topology.yaml", Then a "[ERROR] NO_MACHINES" finding and exit 1; a healthy topology exits 0 (only ERROR-severity affects exit code).
  ```

#### GIMLE-391 — Cluster launch planning (`hilmir plan`)

- **Category**: Release Management
- **User story**: As an operator, I want to see exact per-machine process command lines before actually spawning anything.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/plan/LaunchPlanner.java`, `ClusterPlan.java`, `MachinePlan.java`, `ProcessCommand.java`
- **Test coverage**: `HilmirMainTest.plan_prints_the_resolved_commands_for_a_healthy_topology`, `plan_filters_to_one_machine_when_requested`, `plan_aborts_with_findings_and_exit_one_when_the_topology_has_an_error`; `LaunchPlannerTest` (multiple)
- **Gherkin scenario**:
  ```gherkin
  Given a validated healthy topology with machine "m1", When "hilmir plan -f topology.yaml", Then output includes "machine: m1" and the full StoreMain command line; "--machine m2" filters to just that machine.
  ```

#### GIMLE-392 — Real multi-process cluster bring-up (`hilmir up`)

- **Category**: Release Management
- **User story**: As an operator, I want one command to actually spawn every process a topology places on a named machine, in correct boot order.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/MachineLauncher.java`, `BootOrder.java`, `ReadinessPoller.java`, `RunLedger.java`, `JavaArgFile.java`
- **Test coverage**: `MachineLauncherIntegrationTest.up_waits_on_a_remote_prerequisite_then_down_and_status_reflect_the_real_processes`; `HilmirMainTest.up_requires_the_machine_flag`, `up_aborts_with_findings_before_launching_anything_when_the_topology_has_an_error`; `BootOrderTest`
- **Gherkin scenario**:
  ```gherkin
  Given a validated topology, When "hilmir up -f topology.yaml --machine m1", Then every process is spawned in plan order, waited for readiness, remote prerequisites TCP-polled first, and a run ledger written.
  ```

#### GIMLE-393 — Cluster teardown and status reporting (`hilmir down`/`status`)

- **Category**: Release Management
- **User story**: As an operator, I want to stop every process a prior "hilmir up" started, and inspect whether they're still alive.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/MachineLauncher.java`, `RunLedger.java`
- **Test coverage**: `MachineLauncherIntegrationTest.down_is_a_clean_no_op_for_an_already_dead_recorded_pid`, `status_reports_a_dead_pid_as_not_alive_and_a_never_bound_address_as_closed`; `HilmirCliDownStatusEndToEndTest`; `HilmirMainTest` (multiple)
- **Gherkin scenario**:
  ```gherkin
  Given a run ledger from a prior "hilmir up", When "hilmir down --machine m1", Then every process (and descendants) killed in reverse spawn order and the ledger deleted; status against a never-run machine reports a clean error.
  ```

#### GIMLE-394 — Cluster TLS/PKI bootstrap (`hilmir pki init`)

- **Category**: Release Management / Security
- **User story**: As an operator standing up a fresh mTLS cluster, I want one command that generates the cluster CA and every process's server leaf certificate.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/PkiInit.java`
- **Test coverage**: `PkiInitTest` (multiple); `HilmirMainTest.pki_requires_the_init_subcommand`, `pki_init_requires_the_file_flag`, `pki_init_refuses_a_topology_with_no_tls_material_dir_dir`
- **Gherkin scenario**:
  ```gherkin
  Given an mtls-transport topology with tls.materialDir set, When "hilmir pki init -f topology.yaml", Then a PkiBootstrapMain subprocess writes CA+leaf material; no materialDir fails clearly naming the field.
  ```

#### GIMLE-395 — Raft store membership add (`hilmir store add`)

- **Category**: Release Management
- **User story**: As an operator scaling a live store cluster, I want to add a new Raft peer from the CLI.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/store/StoreAddCommand.java`, `StoreEndpoints.java`, `StoreRetry.java`
- **Test coverage**: `StoreCommandsClusterTest.add_joins_a_real_peer_and_it_becomes_a_visible_cluster_member`; `HilmirMainTest` (positional args, one-of-topology/server); `StoreEndpointsTest`
- **Gherkin scenario**:
  ```gherkin
  Given a running store cluster, When "hilmir store add peer-4 host4 9080 9091 --server host1:9091,host2:9091", Then StoreClient#addServer is called with retry and the peer becomes a visible member.
  ```

#### GIMLE-396 — Raft store membership remove (`hilmir store remove`)

- **Category**: Release Management
- **User story**: As an operator decommissioning a store node, I want to remove it from Raft membership.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/store/StoreRemoveCommand.java`
- **Test coverage**: `StoreCommandsClusterTest.remove_drops_a_previously_added_peer_from_the_membership`, `remove_of_a_never_added_peer_fails_fast_with_a_clean_error`
- **Gherkin scenario**:
  ```gherkin
  Given a previously-added peer, When "hilmir store remove peer-4 --server host1:9091", Then StoreClient#removeServer is called; removing a never-added peer fails fast.
  ```

#### GIMLE-397 — Per-machine platform binary rolling upgrade with quorum-safe store restart (`hilmir upgrade-cluster`)

- **Category**: Release Management
- **User story**: As an operator rolling out a new platform build, I want to restart one machine's stateless processes one role at a time, with the store restart refusing to break Raft quorum.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/upgrade/UpgradeClusterCommand.java`, `RoleRestarter.java`, `MachineLauncher.restartRole`/`requireStoreQuorumMaintained`
- **Test coverage**: `UpgradeClusterCommandTest` (multiple); `MachineLauncherRestartRoleIntegrationTest` (multiple); `MachineLauncherStoreQuorumGateTest` (multiple)
- **Gherkin scenario**:
  ```gherkin
  Given a machine hosting a store replica among a quorum, When "hilmir upgrade-cluster --machine m1 --new-classpath <cp>", Then only STORE/MUNINN/ANDVARI/FAFNIR/CONTROL_PLANE are restartable (AGENT rejected outright); a store restart is refused if it would break quorum.
  ```

#### GIMLE-398 — Bundle-based fresh release deployment (`hilmir deploy`)

- **Category**: Release Management
- **User story**: As an operator, I want to deploy a versioned "Bundle" (tenants+config+secrets+workloads) as a named release with --dry-run and --wait.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/DeployCommand.java`, `ReleaseReconciler.deployFresh`, `ReleasePlan.java`
- **Test coverage**: `DeployCommandTest` (multiple, incl. dry-run, unresolved value ref, json output, wait); `HilmirMainTest.deploy_requires_the_file_flag`
- **Gherkin scenario**:
  ```gherkin
  Given no existing release "orders", When "hilmir deploy -f orders-bundle.yaml --wait", Then tenant/config/secrets/workloads applied in order, each polled to readiness, revision 1 written; re-deploying an existing release fails clearly.
  ```

#### GIMLE-399 — Bundle upgrade with automatic resource pruning (`hilmir upgrade`)

- **Category**: Release Management
- **User story**: As an operator rolling a new bundle revision, I want previously-applied workloads no longer declared automatically deleted.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/UpgradeCommand.java`, `ReleaseReconciler.upgradeExisting`/`computePrune`
- **Test coverage**: `UpgradeCommandTest` (prunes workload, requires existing release, dry-run computes prune with no mutating call)
- **Gherkin scenario**:
  ```gherkin
  Given release "orders" rev 1 with workloads A+B, When upgraded with a bundle declaring only A, Then B is pruned, revision 2 written, reported "pruned 1 resource(s)"; no existing release fails clearly.
  ```

#### GIMLE-400 — Release rollback to a prior revision (`hilmir rollback`)

- **Category**: Release Management
- **User story**: As an operator who shipped a bad revision, I want to re-apply a past revision's exact recorded state as a new revision.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/RollbackCommand.java`
- **Test coverage**: `RollbackCommandTest` (multiple); `HilmirMainTest.rollback_requires_the_release_flag`
- **Gherkin scenario**:
  ```gherkin
  Given release "orders" at rev 3, When "hilmir rollback --release orders" with no --to-revision, Then rev 2's snapshot re-applied as rev 4; explicit --to-revision reads that exact revision.
  ```

#### GIMLE-401 — Full release teardown (`hilmir undeploy`)

- **Category**: Release Management
- **User story**: As an operator retiring a release, I want workloads deleted in reverse apply order, tenant deleted (unless --keep-tenants), and the ledger wiped.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/UndeployCommand.java`, `ReleaseReconciler.undeployRelease`
- **Test coverage**: `UndeployCommandTest` (multiple); `HilmirMainTest.undeploy_requires_the_release_flag`
- **Gherkin scenario**:
  ```gherkin
  Given release "orders" with workloads+tenant, When "hilmir undeploy --release orders", Then workloads deleted reverse-order, tenant deleted, ledger removed; --keep-tenants leaves the tenant.
  ```

#### GIMLE-402 — Release listing (`hilmir releases`)

- **Category**: Release Management
- **User story**: As an operator, I want to see every release tracked in the ledger with its current revision and bundle version.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/ReleasesCommand.java`
- **Test coverage**: `ReleasesCommandTest` (2 tests)
- **Gherkin scenario**:
  ```gherkin
  Given releases exist, When "hilmir releases", Then NAME/REVISION/BUNDLE_VERSION columns listed; empty ledger prints "No releases found.".
  ```

#### GIMLE-403 — Release status inspection (`hilmir release-status`)

- **Category**: Release Management
- **User story**: As an operator, I want a release's meta plus every resource's live control-plane status pulled in one command.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/ReleaseStatusCommand.java`
- **Test coverage**: `ReleaseStatusCommandTest`; `HilmirMainTest.release_status_requires_a_release_name`
- **Gherkin scenario**:
  ```gherkin
  Given "hilmir release-status orders", Then bundleVersion/currentRevision/tenants plus each resource's live status printed; a fetch failure shows an "error" field rather than aborting.
  ```

#### GIMLE-404 — GitOps directory reconciliation (`hilmir sync`, incl. `--watch` and `--prune`)

- **Category**: Release Management
- **User story**: As an operator running GitOps, I want a directory of bundles converged to the cluster in one command.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/sync/SyncCommand.java`
- **Test coverage**: `SyncCommandTest` (11 tests)
- **Gherkin scenario**:
  ```gherkin
  Given 3 bundle files (new, changed, unchanged), When "hilmir sync --dir ./bundles", Then new deployed, changed upgraded+pruned, unchanged reported "already-converged" with zero calls; "--prune" undeploys orphaned releases.
  ```

#### GIMLE-405 — `--watch` interval loop for sync

- **Category**: Release Management
- **User story**: As an operator, I want "hilmir sync --watch <seconds>" to keep re-running the reconcile pass on an interval.
- **Status**: Partial — deliberately untested, per its own documentation
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/sync/SyncCommand.java`
- **Test coverage**: NONE (documented in class javadoc as deliberately untested convenience wrapper)
- **Gherkin scenario**:
  ```gherkin
  Given "hilmir sync --dir ./bundles --watch 30", Then the reconcile pass runs every 30s until interrupted.
  ```

#### GIMLE-406 — Bundle value templating and override precedence (`${values.*}` substitution)

- **Category**: Release Management
- **User story**: As a bundle author, I want ${values.key} placeholders overridable by --values file then --set flags (Helm's precedence).
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/BundleRenderer.java`, `ValueOverrides.java`
- **Test coverage**: `BundleRendererTest` (6 tests); `ValueOverridesTest` (4 tests)
- **Gherkin scenario**:
  ```gherkin
  Given a bundle with values:{region:us-east}, When rendered with --set region=eu-west, Then eu-west wins; an unresolved reference fails with a named error, never a literal "${values.key}".
  ```

#### GIMLE-407 — Bundle manifest schema parsing and validation

- **Category**: Release Management
- **User story**: As a bundle author, I want strict, allowlisted-field YAML parsing rejecting unknown keys and workload entries specifying neither/both of file/manifest.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/BundleParser.java`
- **Test coverage**: `BundleParserTest` (8 tests)
- **Gherkin scenario**:
  ```gherkin
  Given an unrecognized top-level key, When parsed, Then GimleManifestException names it; a workload with both file and manifest is rejected as ambiguous.
  ```

#### GIMLE-408 — Workload readiness polling for `--wait`

- **Category**: Release Management
- **User story**: As an operator applying a bundle with --wait, I want the CLI to block until each workload reaches a kind-appropriate ready state.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/WaitPoller.java`
- **Test coverage**: `DeployCommandTest.wait_polls_until_the_workloads_instances_report_active` (indirect); NONE dedicated
- **Gherkin scenario**:
  ```gherkin
  Given a Deployment applied with --wait, When instances haven't all reported ACTIVE, Then polls every 2s up to a 5-minute timeout.
  ```

#### GIMLE-409 — Doctor static deployability diagnostics (`hilmir doctor`)

- **Category**: Build Tooling
- **User story**: As a developer preparing to deploy a jar, I want a static analysis pass catching deployability problems (packaging, manifest, tier 3, resources, probes, version drift, native code, System.exit, thread leaks, split packages, bundled logging bindings).
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/doctor/DoctorCommand.java`, `DoctorAnalyzer.java`, `DoctorFinding.java`, `analyze/*`
- **Test coverage**: `DoctorAnalyzerTest` (10 tests); `DoctorCommandTest`; `BytecodeScannerTest`, `JarStructureInspectorTest`
- **Gherkin scenario**:
  ```gherkin
  Given a jar declaring isolation.tier:TIER_3, When "hilmir doctor my-module.jar", Then TIER3_REQUESTED ERROR and exit 1; a launcher-archive jar under module intent is ERROR, under --vessel INFO.
  ```

#### GIMLE-410 — Doctor cluster-aware checks (`--server`, `--tenant`)

- **Category**: Build Tooling
- **User story**: As a developer preparing a coordinate-only manifest, I want "doctor --server host:port" to confirm the coordinate exists in the registry and the target tenant exists.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/doctor/DoctorClusterCheck.java`, `DoctorCommand.runClusterChecks`
- **Test coverage**: NONE dedicated to DoctorClusterCheck found
- **Gherkin scenario**:
  ```gherkin
  Given a coordinate not present in the registry, When "hilmir doctor my-module.jar --server host:port", Then REGISTRY_COORDINATE_NOT_FOUND (ERROR); an unreachable registry gives REGISTRY_UNREACHABLE (WARNING) instead of a hard failure.
  ```

#### GIMLE-411 — Manifest scaffolding (`hilmir init`)

- **Category**: Build Tooling
- **User story**: As a developer with a freshly built jar, I want "hilmir init" to generate a starter deployment.yaml (and gimle-module.yaml if module-shaped) with detected probe/hook classes.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/init/InitCommand.java`, `ModuleYamlWriter.java`, `DeploymentYamlWriter.java`
- **Test coverage**: `InitCommandTest` (3 tests); `ModuleYamlWriterTest` (2 tests)
- **Gherkin scenario**:
  ```gherkin
  Given a module-shaped jar with a detected LivenessProbe, When "hilmir init my-module.jar", Then both YAMLs written with the probe class filled confidently; existing deployment.yaml is never overwritten.
  ```

#### GIMLE-412 — Gateway extension enable (`hilmir enable gateway`)

- **Category**: Release Management
- **User story**: As an operator, I want a one-command way to push gimle-gateway's own jar to the registry and deploy/upgrade a synthesized bundle for it.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/extension/EnableGatewayCommand.java`, `GatewayBundleTemplate.java`, `GatewayJarLocator.java`
- **Test coverage**: `EnableGatewayCommandTest` (5 tests); `GatewayJarLocatorTest` (7 tests)
- **Gherkin scenario**:
  ```gherkin
  Given gimle-gateway not registered/deployed, When "hilmir enable gateway --server host:port", Then jar pushed and a synthesized bundle deployed fresh; identical-sha jar already registered skips the push; already-deployed at an older version upgrades instead.
  ```

#### GIMLE-413 — Gateway extension disable (`hilmir disable gateway`)

- **Category**: Release Management
- **User story**: As an operator, I want a single command to undeploy the gimle-gateway release.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/extension/DisableGatewayCommand.java`
- **Test coverage**: `DisableGatewayCommandTest` (2 tests)
- **Gherkin scenario**:
  ```gherkin
  Given gateway currently enabled, When "hilmir disable gateway --server host:port", Then the release is fully undeployed; never-enabled reports a clear "nothing to disable" message.
  ```

#### GIMLE-414 — Bundled JRE resolution for platform-binary launches

- **Category**: Internal/Infra
- **User story**: As a deployer of a packaged distribution, I want processes launched via hilmir to use ${GIMLE_HOME}/jre/<component>/bin/java when runtime.useBundledJre is set.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/plan/BundledJreResolver.java`
- **Test coverage**: `BundledJreResolverTest` (6 tests); `LaunchPlannerTest` (2 tests)
- **Gherkin scenario**:
  ```gherkin
  Given runtime.useBundledJre:true and GIMLE_HOME set with jre/mimir/bin/java present, When planning a STORE process, Then that exact bundled java path is used; unset GIMLE_HOME fails clearly naming it.
  ```

#### GIMLE-415 — `java @argfile` command-line rewriting

- **Category**: Internal/Infra
- **User story**: As a launcher spawning a process with a long classpath, I want the command line rewritten to `java @argfile` form (JEP 293) to avoid OS command-line length limits.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/JavaArgFile.java`, `gimle-hilmir/src/main/java/com/gimle/hilmir/plan/JavaArgFile.java`
- **Test coverage**: `JavaArgFileTest` (2 tests)
- **Gherkin scenario**:
  ```gherkin
  Given a command with many arguments, When JavaArgFile.rewrite is called, Then each argument is individually quoted/escaped into an argfile, and the returned command is [javaExecutable, "@<argfile>"].
  ```

#### GIMLE-416 — Run ledger persistence for `up`/`down`/`status`/`upgrade-cluster`

- **Category**: Internal/Infra
- **User story**: As a durable record surviving across separate hilmir invocations, I need a corruption-tolerant JSON record of every spawned process's id/role/pid/command/log file/readiness address.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/RunLedger.java`, `RunRecord.java`
- **Test coverage**: `RunLedgerTest` (9 tests)
- **Gherkin scenario**:
  ```gherkin
  Given a set of RunRecords written via RunLedger.write, When read back, Then every field round-trips; a corrupt ledger file reports clearly rather than propagating a raw parse exception.
  ```

#### GIMLE-417 — TCP-connect readiness polling

- **Category**: Internal/Infra
- **User story**: As a launcher waiting on a just-spawned or remote prerequisite process, I want a uniform "successful TCP connect" readiness signal.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/ReadinessPoller.java`
- **Test coverage**: `ReadinessPollerTest` (4 tests)
- **Gherkin scenario**:
  ```gherkin
  Given a port that never opens, When awaitPortOpen is called with a short timeout, Then times out with a clear message; an already-listening port returns immediately.
  ```

#### GIMLE-573 — Doctor advisory-only outbound-connection hazard detection

- **Category**: Build Tooling
- **User story**: As a module author running `hilmir doctor` on my module jar, I want to be told when my bytecode constructs an outbound Socket/SocketChannel/HttpClient, so I understand my module can reach the network today even though nothing on the platform restricts or enforces that -- an informational signal, not a rejection.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/analyze/BytecodeScanner.java` (`isOutboundConnectionCallSite`), `gimle-hilmir/src/main/java/com/gimle/hilmir/analyze/ClassHazards.java` (`makesOutboundConnection`), `gimle-hilmir/src/main/java/com/gimle/hilmir/doctor/DoctorAnalyzer.java` (`checkMakesOutboundCalls`, `MAKES_OUTBOUND_CALLS` at `INFO` severity)
- **Test coverage**: `BytecodeScannerTest` (connecting Socket constructor, Socket() + separate connect(), bare Socket() with no connect as a negative case, SocketChannel.open(), HttpClient.newHttpClient(), HttpClient.newBuilder().build()); `DoctorAnalyzerTest` (a real compiled fixture calling HttpClient.newHttpClient() asserts MAKES_OUTBOUND_CALLS at INFO with no errors; a clean fixture asserts its absence)
- **Gherkin scenario**:
  ```gherkin
  Given a module jar whose bytecode constructs a connecting java.net.Socket, opens a SocketChannel, or builds a java.net.http.HttpClient, When `hilmir doctor` analyzes it, Then it reports MAKES_OUTBOUND_CALLS at INFO severity, explaining that nothing on the platform restricts a module's outbound traffic today.
  Given a module jar with no outbound-connection call sites, When `hilmir doctor` analyzes it, Then MAKES_OUTBOUND_CALLS is not reported.
  ```

#### GIMLE-576 — Remote (SSH) fleet bootstrap (`hilmir up/down/status --remote`)

- **Category**: Release Management
- **User story**: As an operator, I want `hilmir up/down/status` to dispatch over SSH to every machine a topology declares, so I don't need a shell already open on each target machine to bootstrap a real fleet.
- **Status**: Complete for v1 scope (no host-key verification, no provisioning, no credential handling of its own)
- **Confidence**: High
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/remote/RemoteDispatch.java`, `SshProcessExec.java`, `ResolvedSshTarget.java`, `RemoteExec.java`, `RemoteOutput.java`, `SshCliFlags.java`, `SshSettings.java`, `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardSshDeployIT.java`, `UtgardSshMachine.java`
- **Test coverage**: `RemoteDispatchTest`; `ResolvedSshTargetTest`; `SshProcessExecTest`; `HilmirMainTest.up_with_remote_does_not_require_the_machine_flag`, `down_with_remote_requires_the_file_flag`, `status_with_remote_requires_the_file_flag`; `TopologyParserTest` (`ssh:` block parsing); `gimle-holmgang`'s `UtgardSshDeployIT` (a real Docker+SSH round trip: `hilmir up/down/status --remote` against a genuine sshd over an ephemeral authorized keypair, deploying a real greeter-provider instance to `ACTIVE` through the control plane's own HTTP API)
- **Gherkin scenario**:
  ```gherkin
  Given a topology declaring two or more machines, When "hilmir up -f topology.yaml --remote" with no --machine, Then every machine is dispatched to concurrently over SSH -- the identical local up --machine <name> re-invoked on each target -- and one machine's failure never aborts the others.
  ```

### gimle-maven-plugin

#### GIMLE-418 — `mvn gimle:agent` — spawn a real node agent (plus its worker command tail)

- **Category**: Build Tooling
- **User story**: As a developer doing local-dev, I want a single goal that launches a real AgentMain with sensible dev defaults.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/AgentMojo.java`, `AbstractGimleMojo.java`, `GimleProcesses.resolveRuntimeClasspath`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:agent" from gimle-agent, Then a real AgentMain process is spawned with the resolved runtime classpath, plus a worker command tail using gimle-worker's own independently-resolved classpath; run from another module, no-ops.
  ```

#### GIMLE-419 — `mvn gimle:bootstrap` — full local-dev cluster orchestration in one foreground command

- **Category**: Build Tooling
- **User story**: As a developer, I want one command bringing up store/muninn/andvari/fafnir/control-plane/agent+worker with a connection summary and clean Ctrl+C teardown.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/BootstrapMojo.java`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:bootstrap" from the root project, Then processes spawn in dependency order, each awaited on its port; by default every gimle-examples module is deployed and awaited ACTIVE (best-effort); "-Dgimle.bootstrap.protocol=tls" runs PkiBootstrapMain first and wires mTLS.
  ```

#### GIMLE-420 — Process-launcher Maven goals for individual platform processes (`controlplane`/`store`/`fafnir`/`muninn`/`andvari`/`tls-init`)

- **Category**: Build Tooling
- **User story**: As a developer, I want one goal per platform process kind, each spawning a real subprocess with coordinated default ports/endpoints.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/{ControlPlaneMojo,StoreMojo,FafnirMojo,MuninnMojo,AndvariMojo,TlsInitMojo}.java`, `AbstractGimleMojo.java`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:store" from gimle-mimir, Then StoreMain spawned on raftPort 9080/clientPort 9091; "mvn gimle:controlplane" defaults --store-endpoints to 127.0.0.1:9091 and --fafnir-endpoint to 127.0.0.1:9092.
  ```

#### GIMLE-421 — `mvn gimle:deploy` — apply a deployment manifest via a real CLI subprocess

- **Category**: Build Tooling
- **User story**: As a developer, I want to apply a manifest to a running control plane as part of a Maven build/dev loop.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/DeployMojo.java`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:deploy -Dgimle.deploy.file=deployment.yaml", Then a real GimleCli "apply -f <file> --server <server>" subprocess is spawned.
  ```

#### GIMLE-422 — `mvn gimle:doctor` — run hilmir doctor against the invoking project's own built jar

- **Category**: Build Tooling
- **User story**: As a developer of any module, I want a goal running "hilmir doctor" against my own built jar without needing gimle-hilmir on my classpath.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/DoctorMojo.java`
- **Test coverage**: `DoctorMojoTest` (4 tests, against the pure buildCommand seam)
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:doctor" bound to package phase, Then a real "doctor <jar>" subprocess runs with --vessel/--server/--tenant passed through only when set; a blank server is treated as unset.
  ```

#### GIMLE-423 — `mvn gimle:init` — scaffold manifests for the invoking project's own built jar

- **Category**: Build Tooling
- **User story**: As a developer, I want a goal running "hilmir init" against my own built jar.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/InitMojo.java`
- **Test coverage**: `InitMojoTest` (3 tests)
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:init", Then a real "init <jar> [--out-dir <dir>]" subprocess is spawned; blank outDir treated as unset.
  ```

#### GIMLE-424 — `mvn gimle:publish` — push a built module jar to the artifact registry

- **Category**: Build Tooling
- **User story**: As a module developer, I want a goal that pushes my just-built jar to Andvari via a real CLI subprocess.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/PublishMojo.java`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:publish", Then a real "artifact push <file> --server <server>" subprocess is spawned using gimle-cli's independently resolved classpath.
  ```

#### GIMLE-425 — `mvn gimle:docs` — full documentation site build pipeline

- **Category**: Build Tooling
- **User story**: As a maintainer, I want one command running javadoc:aggregate, copying it into the docs site's static assets, and building the Docusaurus site via Bun.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/DocsMojo.java`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:docs" from the root project, Then javadoc:aggregate runs, output copied to gimle-docs/static/javadoc, then "bun install"/"bun run build"; missing aggregated output logs a warning without failing.
  ```

#### GIMLE-426 — `mvn gimle:flaky-tests` — run known-flaky-tagged tests in isolated standalone reactors

- **Category**: Build Tooling
- **User story**: As a maintainer, I want each module's @Tag("flaky") tests run as their own genuinely separate child mvn process, removing cross-module Surefire-JVM contention.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/FlakyTestsMojo.java`
- **Test coverage**: `FlakyTestsMojoTest` (pure-function seams)
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:flaky-tests" (default modules=gimle-mimir, repeat=1), Then a standalone "mvn -pl gimle-mimir test -Dgroups=flaky ..." child runs; -Dgimle.flakyTests.repeat=0 fails immediately before spawning anything.
  ```

#### GIMLE-427 — `mvn gimle:saga` — ensure a Saga test-report server is running

- **Category**: Build Tooling
- **User story**: As a developer, I want a goal reusing an already-healthy Saga server or spawning a fresh detached one.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SagaMojo.java`, `SagaServer.java`, `SagaClient.java`, `AbstractGimleRootMojo.java`
- **Test coverage**: `SagaServerTest`, `SagaClientTest`
- **Gherkin scenario**:
  ```gherkin
  Given no Saga server listening on 9096, When "mvn gimle:saga", Then a real SagaMain process is spawned detached, pid recorded, health-polled until ready or a 30s timeout; a healthy server is reused.
  ```

#### GIMLE-428 — `mvn gimle:verify` — full build run under Saga tracking

- **Category**: Build Tooling
- **User story**: As a developer, I want a goal ensuring Saga is up, minting a run id, spawning the actual build as a separate child, and importing surefire reports at the end.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SagaVerifyMojo.java`, `SagaEvents.java`, `SurefireReports.java`, `GitInfo.java`
- **Test coverage**: `SagaVerifyMojoTest` (pure-function seams); `SagaEventsTest`; `SurefireReportsTest`
- **Gherkin scenario**:
  ```gherkin
  Given "mvn gimle:verify -Dgimle.saga.mavenArgs='clean verify -Psmoke'", Then a run id like "2026-08-17T10-00-00_abc1234" is minted, the child mvn command spawned/streamed, surefire reports swept and imported, run-finished posted; a non-zero child exit fails this build too, only after import.
  ```

#### GIMLE-429 — `mvn gimle:saga-import` — standalone sweep-and-import of existing surefire reports

- **Category**: Build Tooling
- **User story**: As a developer who already ran a plain "mvn verify", I want to import its surefire results into an already-running Saga server after the fact.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SagaImportMojo.java`
- **Test coverage**: NONE dedicated
- **Gherkin scenario**:
  ```gherkin
  Given a running Saga server and existing target/surefire-reports/*.xml files, When "mvn gimle:saga-import", Then every discovered report is imported under a freshly-minted or explicit --runId; no reachable server fails immediately.
  ```

#### GIMLE-430 — `mvn gimle:saga-stop` — best-effort local Saga server shutdown

- **Category**: Build Tooling
- **User story**: As a developer, I want a goal that asks the Saga server to shut down gracefully, falling back to signalling its recorded pid.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SagaStopMojo.java`
- **Test coverage**: NONE dedicated
- **Gherkin scenario**:
  ```gherkin
  Given a running Saga server, When "mvn gimle:saga-stop", Then POST /api/shutdown called and pidfile deleted; neither present reports "nothing to stop" without failing.
  ```

#### GIMLE-431 — Internal — Aether-based cross-module runtime classpath resolution

- **Category**: Internal/Infra
- **User story**: As a goal that spawns a different reactor module's process, I need to resolve that module's full runtime classpath against its already-installed jar via Maven's dependency resolver, independent of reactor build order.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/GimleProcesses.java`
- **Test coverage**: NONE dedicated to resolveRuntimeClasspath itself
- **Gherkin scenario**:
  ```gherkin
  Given GimleProcesses.resolveRuntimeClasspath("gimle-worker", version, ...), Then resolves the artifact and its runtime dependencies via Aether; a never-installed artifact throws a clear MojoExecutionException suggesting "mvn install" first.
  ```

#### GIMLE-432 — Internal — host-matching java/mvn executable resolution and subprocess supervision

- **Category**: Internal/Infra
- **User story**: As every process-spawning goal, I need to resolve the exact java/mvn launcher the current build is running under, and shared spawn/wait/shutdown-hook helpers.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/GimleProcesses.java`
- **Test coverage**: `GimleProcessesTest` (6 tests)
- **Gherkin scenario**:
  ```gherkin
  Given GimleProcesses.javaExecutable(), Then returns the real java binary this Maven process runs under; mavenLauncherUnder(mavenHome) resolves bin/mvn or empty.
  ```

#### GIMLE-433 — Internal — git commit/branch capture for run identification

- **Category**: Internal/Infra
- **User story**: As gimle:verify/gimle:saga-import, I need the working tree's current short commit sha and branch, degrading gracefully with no git.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/GitInfo.java`
- **Test coverage**: NONE dedicated
- **Gherkin scenario**:
  ```gherkin
  Given a real git repo, When GitInfo.capture(root) is called, Then real short sha/branch returned; no .git or 10s timeout returns both empty rather than throwing.
  ```

#### GIMLE-434 — Internal — surefire report discovery and totals aggregation, including flaky-testcase counting

- **Category**: Internal/Infra
- **User story**: As every Saga goal, I need to recursively discover surefire XML reports, parse both testsuite shapes, sum totals, and count flaky testcases once per testcase.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SurefireReports.java`
- **Test coverage**: `SurefireReportsTest`
- **Gherkin scenario**:
  ```gherkin
  Given a mix of testsuite-rooted and testsuites-wrapped XML files, When SurefireReports.totals is called, Then counts summed correctly; a testcase with a flakyFailure element counts once regardless of rerun attempts; unparseable files are warned about and skipped.
  ```

### gimle-console

#### GIMLE-435 — Operator session login / logout

- **Category**: Web Console / Auth
- **User story**: As a cluster operator, I want to sign in with a username/password, so that only authorized people can operate the cluster through the console.
- **Status**: Complete (plaintext mode reports a synthetic anonymous principal instead of forcing login — deliberate)
- **Confidence**: High
- **Source location(s)**: `gimle-console/src/routes/login.tsx`, `src/stores/useAuthStore.ts`, `src/repositories/http/auth.ts`
- **Test coverage**: `src/stores/useAuthStore.test.ts` — "a successful login sets status authenticated and clears any previous error", "login failure surfaces a generic error and leaves status unauthenticated"
- **Gherkin scenario**:
  ```gherkin
  Given I am unauthenticated, When I submit valid credentials on `/login`, Then I am redirected to the Overview screen and my principal is stored; Given invalid credentials, Then an "invalid username or password" error is shown.
  ```

#### GIMLE-436 — Session bootstrap & 401 handling

- **Category**: Web Console / Auth
- **User story**: As an operator, I want the console to detect an existing/expired session automatically.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/stores/useAuthStore.ts`, `src/repositories/http/apiClient.ts`, `src/routes/__root.tsx`
- **Test coverage**: `useAuthStore.test.ts` — "init() only calls session() once even if invoked twice", "handleUnauthorized clears principal and sets status unauthenticated"
- **Gherkin scenario**:
  ```gherkin
  Given a valid session cookie exists, When the app loads, Then `/auth/session` resolves my principal without a login prompt; Given any API call returns 401, Then the store clears my principal and redirects to `/login`.
  ```

#### GIMLE-437 — Cluster Overview dashboard

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want an at-a-glance summary of nodes, deployments, tenants, and health.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/routes/index.tsx`, `src/stores/useOverviewStore.ts`, `src/components/overview-signal.tsx`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given the console loads `/`, When overview data is fetched, Then node/deployment/tenant totals and staleness indicators render as metric tiles.
  ```

#### GIMLE-438 — Tactical HUD / Signal display-mode toggle

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to switch between a dense "HUD" layout and a simplified "signal" layout.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/stores/useDisplayStore.ts`, `src/components/overview-signal.tsx`, `src/routes/index.tsx`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given I toggle display mode, When I select "signal", Then `OverviewSignal` renders instead of the default HUD layout, persisting via localStorage.
  ```

#### GIMLE-439 — Deployments list/create/detail/delete

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to list, create, inspect, and delete module deployments.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/deployments.index.tsx`, `deployments.$name.tsx`, `deployments.new.tsx`, `src/stores/useDeploymentsStore.ts`, `src/repositories/http/deployments.ts`
- **Test coverage**: `src/stores/useDeploymentsStore.test.ts`, `src/repositories/http/deployments.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given I fill the "New deployment" form, When I submit, Then a new deployment is created and I'm navigated to its detail page; Given ACTIVE, When I delete and confirm, Then it is removed.
  ```

#### GIMLE-440 — Jobs (run-to-completion workload) list

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to view run-to-completion Jobs and their phase/attempt state.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/jobs.index.tsx`, `jobs.$name.tsx`, `src/stores/useJobsStore.ts`, `src/repositories/http/jobs.ts`
- **Test coverage**: `src/repositories/http/jobs.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given Jobs exist, When I open `/jobs`, Then each job's phase is listed; no "create" form (job manifests applied via CLI).
  ```

#### GIMLE-441 — CronJobs list/detail

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to view scheduled CronJobs.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/cronjobs.index.tsx`, `cronjobs.$name.tsx`, `src/stores/useCronJobsStore.ts`, `src/repositories/http/cronjobs.ts`
- **Test coverage**: `src/repositories/http/cronjobs.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given CronJobs exist, When I open `/cronjobs`, Then each entry's schedule/concurrency policy is listed with a link to run history.
  ```

#### GIMLE-442 — DaemonSets list/detail

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to view node-scoped DaemonSet workloads.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/daemonsets.index.tsx`, `daemonsets.$name.tsx`, `src/stores/useDaemonSetsStore.ts`, `src/repositories/http/daemonsets.ts`
- **Test coverage**: `src/repositories/http/daemonsets.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a DaemonSet is deployed, When I open `/daemonsets`, Then per-node placement status is shown.
  ```

#### GIMLE-443 — StatefulSets list/detail

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to view stable-identity StatefulSet workloads.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/statefulsets.index.tsx`, `statefulsets.$name.tsx`, `src/stores/useStatefulSetsStore.ts`, `src/repositories/http/statefulsets.ts`
- **Test coverage**: `src/repositories/http/statefulsets.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a StatefulSet is deployed, When I open `/statefulsets`, Then each ordinal replica's identity/lifecycle is shown.
  ```

#### GIMLE-444 — Instances table with filtering (global + node/tenant-scoped)

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to view every running module instance with filters by lifecycle state/node/tenant.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/instances.index.tsx`, `instances.$name.$idx.tsx`, `src/components/instances-table.tsx`, `src/stores/useInstancesStore.ts`, `src/repositories/http/instances.ts`
- **Test coverage**: `src/stores/instances.test.ts`, `src/repositories/http/instances.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given I navigate from a node's detail page, When instances loads, Then it's pre-filtered to that node's instances; navigating directly resets any leftover filter.
  ```

#### GIMLE-445 — Nodes list/detail with capacity bars and staleness

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to see every node's heartbeat freshness and CPU/memory capacity usage.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/nodes.index.tsx`, `nodes.$nodeId.tsx`, `src/stores/useNodesStore.ts`, `src/repositories/http/nodes.ts`, `src/lib/format.ts`
- **Test coverage**: `src/repositories/http/nodes.test.ts`, `src/stores/nodes.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a node's last heartbeat exceeds the staleness threshold, When I view `/nodes`, Then that node is visually flagged stale.
  ```

#### GIMLE-446 — Tenants list/detail with quota management and delete

- **Category**: Web Console / Frontend
- **User story**: As a platform admin, I want to create/inspect/delete tenants and view their resource quotas.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/tenants.index.tsx`, `tenants.$id.tsx`, `src/stores/useTenantsStore.ts`, `src/repositories/http/tenants.ts`
- **Test coverage**: `src/repositories/http/tenants.test.ts`, `src/stores/tenants.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a tenant with active deployments, When I attempt to delete it, Then a confirmation dialog is shown before the delete request fires.
  ```

#### GIMLE-447 — Topology placement map

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want a visual map of deployment replicas placed across nodes, grouped by tenant or node.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/routes/topology.tsx`, `src/components/topology-drawer.tsx`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given a deployment has fewer placed instances than its replica count, When I view `/topology`, Then hollow squares show unplaced slots, tagged "warn"/"bad".
  ```

#### GIMLE-448 — Cluster metrics charts (lifecycle mix, capacity, quota pressure)

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want charted views of lifecycle-state mix, placement coverage, node capacity balance, tenant quota pressure.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/routes/metrics.tsx`, `src/components/chart-kit.tsx`, `src/components/metrics-history-panel.tsx`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given cluster/tenant data are loaded, When I open `/metrics`, Then pie/scatter charts render via recharts.
  ```

#### GIMLE-449 — Per-process metrics history (Muninn-backed)

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to pick a process and see its metrics history over time.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/components/metrics-history-panel.tsx`, `src/components/process-picker.tsx`, `src/stores/useMetricsHistoryStore.ts`, `src/repositories/http/metricsHistory.ts`
- **Test coverage**: `src/repositories/http/metricsHistory.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given I pick a process target via `ProcessPicker`, When metrics history loads, Then `GET /metrics-history/*` results render as a time-series panel.
  ```

#### GIMLE-450 — Trace span history viewer

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want a sortable table of trace spans per process.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/traces.tsx`, `src/stores/useTracesStore.ts`, `src/repositories/http/tracesHistory.ts`
- **Test coverage**: `src/repositories/http/tracesHistory.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given traces exist, When I open `/traces` and sort by status, Then rows re-order; ERROR-status spans are visually distinguished.
  ```

#### GIMLE-451 — Log explorer with live tailing

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to view and live-tail logs for an instance, node, or the control plane.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/logs.tsx`, `src/stores/useLogStore.ts`, `src/repositories/http/logs.ts`
- **Test coverage**: `src/routes/logs.test.ts`, `src/repositories/http/logs.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given I open `/logs` with no query params, Then it falls back to the control-plane PLATFORM target instead of crashing; Given I click "follow", Then new lines are polled via `since=` cursor and appended live.
  ```

#### GIMLE-452 — Crash-dump (hs_err) listing on Logs screen

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to see JVM crash-dump files for a crashed instance directly in the Logs screen.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/routes/logs.tsx`, `src/types/index.ts`
- **Test coverage**: NONE beyond `logs.test.ts`'s search-schema tests
- **Gherkin scenario**:
  ```gherkin
  Given an instance crashed and left an hs_err file, When I view its logs, Then the crash dump is listed.
  ```

#### GIMLE-453 — Config entries management (per-tenant)

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to view, add, reveal, and remove per-tenant config entries.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/config.tsx`, `src/stores/useConfigStore.ts`, `src/repositories/http/config.ts`
- **Test coverage**: `src/repositories/http/config.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given an encrypted config entry, When I click "reveal", Then its decrypted value is fetched on demand; "hide" clears it from view without re-fetching.
  ```

#### GIMLE-454 — Secrets management (Fafnir-backed, versioned)

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to browse, reveal, version, write, soft-delete, and hard-destroy per-tenant secrets.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/secrets.tsx`, `src/stores/useSecretsStore.ts`, `src/repositories/http/secrets.ts`
- **Test coverage**: `src/stores/useSecretsStore.test.ts`, `src/repositories/http/secrets.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a secret has multiple versions, When I request an older version, Then that specific version's value is fetched via `?version=N`; destroy=true removes the entry entirely.
  ```

#### GIMLE-455 — Module artifact registry browser (Andvari-backed)

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to browse module jars pushed to Andvari and delete versions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/artifacts.tsx`, `src/stores/useArtifactsStore.ts`, `src/repositories/http/artifacts.ts`
- **Test coverage**: `src/stores/useArtifactsStore.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given an artifact module is selected, When I request its versions, Then only that module's versions load on demand.
  ```

#### GIMLE-456 — RBAC access control (roles, role bindings, accounts)

- **Category**: Web Console / Frontend
- **User story**: As a platform admin, I want to create/edit/delete roles, bind roles to subjects, and manage operator accounts.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/access-control.tsx`, `src/components/rbac/*`, `src/stores/useRolesStore.ts`, `useRoleBindingsStore.ts`, `useAccountsStore.ts`, `src/repositories/http/{roles,roleBindings,accounts}.ts`
- **Test coverage**: `src/repositories/http/roles.test.ts`, `roleBindings.test.ts`, `accounts.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given I edit a role's permissions, When I add a permission row and save, Then `PUT /roles/{name}` sends the updated permission list.
  ```

#### GIMLE-457 — Audit trail viewer with filtering

- **Category**: Web Console / Frontend
- **User story**: As an operator/auditor, I want a filterable log of principal, resource, tenant, and allow/deny decisions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/audit.tsx`, `src/stores/useAuditStore.ts`, `src/repositories/http/audit.ts`
- **Test coverage**: `src/stores/useAuditStore.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given I set a date-range/verb filter, When search runs, Then results sort newest-first regardless of response order.
  ```

#### GIMLE-458 — Control-plane status panel

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want a quick status view of scheduler/quota-enforcer running state.
- **Status**: Partial (static status display, hardcoded "running" badges, not wired to a live per-subsystem health API)
- **Confidence**: Medium
- **Source location(s)**: `src/routes/controlplane.tsx`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given the control plane is running, When I open `/controlplane`, Then scheduler/quota-enforcer badges show "running".
  ```

#### GIMLE-459 — Theme toggle (light/dark)

- **Category**: Web Console / Frontend
- **User story**: As an operator, I want to switch between light and dark themes.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/components/theme-toggle.tsx`, `src/stores/useThemeStore.ts`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given I click the theme toggle, When dark mode is selected, Then the preference persists across reloads.
  ```

#### GIMLE-460 — Playwright end-to-end smoke suite against a real cluster

- **Category**: Web Console / Testing
- **User story**: As a developer, I want an automated browser test proving the console reflects real deployed-module state.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-console/e2e/greeter-smoke.spec.ts`, `playwright.config.ts`
- **Test coverage**: `e2e/greeter-smoke.spec.ts` (opt-in, `bun run test:e2e`, excluded from default Vitest run)
- **Gherkin scenario**:
  ```gherkin
  Given a real control plane with greeter-provider/consumer deployed, When the Playwright suite runs, Then Deployments/Logs screens reflect genuine real state.
  ```

### gimle-fafnir-console

#### GIMLE-461 — Vault operator login/logout (session-cookie auth)

- **Category**: Web Console / Auth
- **User story**: As a vault operator, I want to sign in to the Fafnir console with credentials.
- **Status**: Complete (deliberate auth, mirroring gimle-console — unlike gimle-saga-console)
- **Confidence**: High
- **Source location(s)**: `gimle-fafnir-console/src/routes/login.tsx`, `src/stores/useAuthStore.ts`, `src/repositories/http/auth.ts`; backend `FafnirServer.java`
- **Test coverage**: `src/stores/useAuthStore.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given plaintext mode with no bootstrap account, When session endpoint loads, Then a synthetic anonymous principal is returned; TLS mode with valid credentials sets a `gimle_fafnir_session`-style cookie.
  ```

#### GIMLE-462 — Vault status overview (uptime, active key, transport mode, tenants)

- **Category**: Web Console / Frontend
- **User story**: As a vault operator, I want a live overview of Fafnir's uptime, active key id, transport mode, and known tenants.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/routes/_shell.index.tsx`, `src/stores/useStatusStore.ts`, `src/repositories/http/status.ts`
- **Test coverage**: NONE dedicated
- **Gherkin scenario**:
  ```gherkin
  Given the vault is running, When I open the overview route, Then uptime is formatted `Xd Yh Zm`, active key id and tenant count shown from `GET /status`.
  ```

#### GIMLE-463 — Secrets browsing/reveal/version/write/destroy (vault-native UI)

- **Category**: Web Console / Frontend
- **User story**: As a vault operator, I want to browse secrets, reveal values, view version history, write new versions, and soft-/hard-delete a key.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/_shell.secrets.tsx`, `src/components/vault/SecretDialog.tsx`, `src/stores/useSecretsStore.ts`, `src/repositories/http/secrets.ts`
- **Test coverage**: `src/repositories/secrets.test.ts`, `src/repositories/http/secrets.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a secret has 3 versions, When I view its history, Then all versions are listed with the latest flagged; "destroy" permanently removes it.
  ```

#### GIMLE-464 — Tenant filter via URL search param

- **Category**: Web Console / Frontend
- **User story**: As a vault operator, I want to deep-link to a specific tenant's secrets via a query parameter.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/routes/_shell.secrets.tsx`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given a URL of `/secrets?tenant=acme`, When the route loads, Then the list is pre-filtered to tenant `acme`.
  ```

#### GIMLE-465 — Key rotation trigger

- **Category**: Web Console / Frontend
- **User story**: As a vault operator, I want to rotate the vault's active encryption key from the UI.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/repositories/secrets.ts`/`http/secrets.ts`, `src/components/vault/StatusPill.tsx`
- **Test coverage**: `secrets.test.ts`, `http/secrets.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given I trigger rotation, When complete, Then the new active key id is returned and reflected in the status panel.
  ```

#### GIMLE-466 — Fafnir console error banner / global error capture

- **Category**: Web Console / Frontend
- **User story**: As a vault operator, I want API errors surfaced clearly in-page.
- **Status**: Complete
- **Confidence**: Low
- **Source location(s)**: `src/components/vault/ErrorBanner.tsx`, `src/lib/errors.ts`, `src/lib/error-capture.ts`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given an API call fails, When the error propagates to the store, Then `ErrorBanner` renders the message.
  ```

### gimle-andvari-console

#### GIMLE-467 — Andvari operator login/logout (session-cookie auth)

- **Category**: Web Console / Auth
- **User story**: As a registry operator, I want to sign in to the Andvari console.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-andvari-console/src/routes/login.tsx`, `src/stores/authStore.ts`, `src/repositories/http/authRepository.ts`; backend `AndvariServer.java`
- **Test coverage**: `src/repositories/__tests__/repositories.test.ts` — "returns an anonymous principal by default", "rejects empty credentials"
- **Gherkin scenario**:
  ```gherkin
  Given plaintext mode with no account seeded, When session endpoint loads, Then an anonymous principal is returned rather than 401.
  ```

#### GIMLE-468 — Registry status overview (uptime, transport, recent pushes)

- **Category**: Web Console / Frontend
- **User story**: As a registry operator, I want to see uptime, transport mode, and recent artifact pushes.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/routes/_shell.index.tsx`, `src/stores/statusStore.ts`, `src/stores/artifactsStore.ts`, `src/repositories/http/statusRepository.ts`
- **Test coverage**: `src/stores/artifactsStore.test.ts` (partial)
- **Gherkin scenario**:
  ```gherkin
  Given artifacts have been pushed recently, When I open the overview route, Then `selectRecentPushes` surfaces the most recent pushes.
  ```

#### GIMLE-469 — Artifact catalog browsing & search

- **Category**: Web Console / Frontend
- **User story**: As a registry operator, I want to browse and search the module-jar catalog by module id.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/_shell.artifacts.index.tsx`, `src/stores/artifactsStore.ts`, `src/repositories/http/artifactsRepository.ts`
- **Test coverage**: `repositories.test.ts` — "returns a sorted catalog of module ids"
- **Gherkin scenario**:
  ```gherkin
  Given the catalog is loaded, When I type into the search box, Then the list filters client-side by substring match.
  ```

#### GIMLE-470 — Artifact version detail (download, checksum display, delete)

- **Category**: Web Console / Frontend
- **User story**: As a registry operator, I want to see every stored version of a module and download or delete a version.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/_shell.artifacts.$moduleId.tsx`, `src/stores/artifactsStore.ts`, `src/repositories/http/artifactsRepository.ts`
- **Test coverage**: `src/stores/artifactsStore.test.ts`, `repositories.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a module has multiple versions, When I select it, Then its versions load on demand; deleting a version calls `DELETE /artifacts/{moduleId}/{version}`.
  ```

#### GIMLE-471 — Client-side SHA-256 checksum verification on download

- **Category**: Web Console / Frontend
- **User story**: As a registry operator, I want a downloaded artifact's bytes verified against the registry-reported SHA-256 in the browser.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/lib/hash.ts` (Web Crypto API)
- **Test coverage**: `src/lib/hash.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given downloaded jar bytes, When `verifySha256` compares them against the `X-Gimle-Artifact-Sha256` header, Then a mismatch is flagged.
  ```

#### GIMLE-472 — Push artifact dialog (drag-and-drop upload)

- **Category**: Web Console / Frontend
- **User story**: As a registry operator, I want to push a new module jar with module id/version.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/components/PushArtifactDialog.tsx`, `src/stores/artifactsStore.ts`
- **Test coverage**: `repositories.test.ts` — "rejects re-pushing an existing version with 409"
- **Gherkin scenario**:
  ```gherkin
  Given I push an existing module@version unmodified, When upload completes, Then it's treated as an idempotent no-op; a differing re-push surfaces a 409 conflict.
  ```

#### GIMLE-473 — Maven-2 repository interop view

- **Category**: Web Console / Frontend
- **User story**: As a developer, I want a read-only page showing the Maven-2-shaped repository URL and usage snippet.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `src/routes/_shell.repository.tsx`, `src/lib/format.ts`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given the console is served at a known origin, When I open `/repository`, Then the repository base URL and a copyable Maven config snippet render.
  ```

#### GIMLE-474 — Andvari copy-to-clipboard utility

- **Category**: Web Console / Frontend
- **User story**: As a registry operator, I want a one-click copy button for URLs/snippets/coordinates.
- **Status**: Complete
- **Confidence**: Low
- **Source location(s)**: `src/components/CopyButton.tsx`
- **Test coverage**: NONE
- **Gherkin scenario**:
  ```gherkin
  Given I click the copy button next to the repository URL, When the click completes, Then the value is written to the clipboard.
  ```

### gimle-saga-console

#### GIMLE-475 — Runs list (no authentication)

- **Category**: Web Console / Reporting
- **User story**: As a developer, I want to see every Gimlé test run newest-first with status, totals, flake counts, and duration.
- **Status**: Complete (deliberately no authentication — local development tool, loopback-bound by default)
- **Confidence**: High
- **Source location(s)**: `gimle-saga-console/src/routes/index.tsx`, `src/stores/runsStore.ts`, `src/repositories/http/runs.ts`
- **Test coverage**: `src/repositories/http/runs.test.ts` — "listRuns fetches /api/runs and maps every entry"
- **Gherkin scenario**:
  ```gherkin
  Given runs exist, When I open `/`, Then rows are searchable by runId/branch/gitSha/command via client-side filtering.
  ```

#### GIMLE-476 — Live run detail with streaming test feed

- **Category**: Web Console / Reporting
- **User story**: As a developer watching a build run, I want a live-updating feed of test results for one run.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/runs.$runId.tsx`, `src/components/saga/RunFeed.tsx`, `src/stores/runDetailStore.ts`, `src/repositories/http/eventsClient.ts`, `src/repositories/http/runs.ts`
- **Test coverage**: `src/repositories/http/runs.test.ts` — "followRunEvents streams new finished-test events and skips the already-known count"
- **Gherkin scenario**:
  ```gherkin
  Given a run is LIVE, When I open its detail page, Then new finished-test events are streamed in via NDJSON polling, ending once terminal.
  ```

#### GIMLE-477 — Run attachments: Gherkin scenario tree, Chaos ledger, Surtr phase table

- **Category**: Web Console / Reporting
- **User story**: As a developer, I want to see attached Gherkin/chaos-soak/Surtr output embedded in a run's detail page.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/components/saga/RunAttachments.tsx`, `src/routes/runs.$runId.tsx`
- **Test coverage**: `src/repositories/http/mapping.test.ts` — "groups attachment events by kind and skips unparseable or unrecognized payloads", "accepts a payload shipped as an array of the shape"
- **Gherkin scenario**:
  ```gherkin
  Given a run's events include attachment payloads, When I switch tabs, Then each tab renders its own structured view.
  ```

#### GIMLE-478 — Test detail / per-test history

- **Category**: Web Console / Reporting
- **User story**: As a developer, I want to see one test's outcome history, duration trend, and failure signatures across recent runs.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/tests.$testId.tsx`, `src/stores/testHistoryStore.ts`, `src/repositories/http/testHistory.ts`
- **Test coverage**: `src/repositories/http/testHistory.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a test has run across several builds, When I open its detail page, Then an outcome strip renders each run's result chronologically.
  ```

#### GIMLE-479 — Compare two runs (diff view)

- **Category**: Web Console / Reporting
- **User story**: As a developer, I want to diff two runs to see newly failing/passing/flaky tests and duration regressions over 25%.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/compare.tsx`, `src/stores/compareStore.ts`, `src/repositories/http/mapping.ts`, `src/repositories/http/runs.ts`
- **Test coverage**: `src/repositories/http/mapping.test.ts`, `runs.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a base/head run selection, When compare loads, Then newly-failing/passing/recovered and >25%-slower tests are each listed.
  ```

#### GIMLE-480 — Gjallarhorn flake scoreboard

- **Category**: Web Console / Reporting
- **User story**: As a developer, I want a ranked scoreboard of flaky tests with quarantine status and a flake budget.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `src/routes/gjallarhorn.tsx`, `src/stores/flakyStore.ts`, `src/repositories/http/flaky.ts`
- **Test coverage**: `src/repositories/http/flaky.test.ts`
- **Gherkin scenario**:
  ```gherkin
  Given a 30-day window, When the scoreboard loads, Then entries rank by score descending, quarantined tests are marked, and budget is computed without dividing by zero.
  ```

#### GIMLE-481 — Saga console theming (no auth surface)

- **Category**: Web Console / Frontend
- **User story**: As a developer, I want the Saga console to load directly without a login screen.
- **Status**: Complete (deliberately no auth — local dev tool)
- **Confidence**: High
- **Source location(s)**: `gimle-saga/src/main/java/com/gimle/saga/SagaServer.java` (no `/auth/*` contexts), `gimle-saga-console`
- **Test coverage**: `SagaServerTest.java` — "the_bundled_console_is_served_at_console"
- **Gherkin scenario**:
  ```gherkin
  Given the console is served, When I navigate to any route, Then no authentication check or redirect occurs.
  ```

### gimle-saga

#### GIMLE-482 — NDJSON event ingest API

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a test infrastructure integrator, I want to POST batches of NDJSON SagaEvent lines to `/api/ingest`.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-saga/src/main/java/com/gimle/saga/SagaServer.java`, `SagaStore.ingest`
- **Test coverage**: `SagaServerTest.java` — "ingested_events_round_trip_through_the_runs_and_events_apis", "a_malformed_ingest_line_is_rejected_with_its_line_number"; `SagaStoreTest.java#ingest_then_read_round_trips_events_and_meta`
- **Gherkin scenario**:
  ```gherkin
  Given a batch with a malformed event on line 3, When I POST it, Then rejected with 400 naming the line number, none persisted.
  ```

#### GIMLE-483 — Idempotent per-run ingest / re-ingest replacement

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a test infrastructure integrator, I want a re-shipped whole run to replace the prior copy rather than duplicate it.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaStore.ingestRun`
- **Test coverage**: `SagaStoreTest.java#re_ingesting_a_whole_run_replaces_it_without_double_counting_the_ledger`
- **Gherkin scenario**:
  ```gherkin
  Given a run already ingested with ledger observations, When re-ingested from scratch, Then its old directory/ledger lines are deleted first and replaced.
  ```

#### GIMLE-484 — Crash-safe append (torn-tail recovery)

- **Category**: Reporting backend / Internal-Infra
- **User story**: As an operator of the Saga process, I want a crash mid-append to never corrupt the events file.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaStore.truncateTornTail`, `SagaStore.completeLines`
- **Test coverage**: `SagaStoreTest.java#a_torn_trailing_line_is_skipped_on_read`, `#an_append_after_a_torn_line_never_fuses_two_events_into_one`
- **Gherkin scenario**:
  ```gherkin
  Given the last line has no trailing newline (torn write), When a new append occurs, Then the fragment is truncated first.
  ```

#### GIMLE-485 — Surefire/Failsafe XML import

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a test infrastructure integrator, I want to import existing Surefire/Failsafe XML reports into Saga.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SurefireXmlImporter.java`, `SagaServer.handleImport`
- **Test coverage**: `SurefireXmlImporterTest.java`, `SagaServerTest.java#importing_surefire_xml_with_a_flaky_failure_lands_a_run_and_a_flake_observation`
- **Gherkin scenario**:
  ```gherkin
  Given a report with a `flakyFailure` element, When imported, Then translated into a failed attempt followed by a passing one; unparseable XML rejected with 400.
  ```

#### GIMLE-486 — Fold-import safety net for a live run's gap

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a test infrastructure integrator, I want to fold an XML import into an already-live-streamed run rather than overwrite it.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaStore.fold`, `SagaServer.handleImport`
- **Test coverage**: `SagaStoreTest.java#fold_appends_only_test_ids_the_live_stream_never_finished_and_drops_framing`, `#fold_without_an_existing_run_ingests_the_batch_unmodified`
- **Gherkin scenario**:
  ```gherkin
  Given a run has live-streamed events for some tests, When I import an XML report for the same runId, Then only test IDs with no `TestFinished` yet are appended, framing never duplicated.
  ```

#### GIMLE-487 — Run listing, detail, and cursor-paginated event reads

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a console client, I want to list runs, fetch one run's meta, and read its event log from a cursor.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaServer.handleRuns`, `SagaStore.listRuns`, `SagaStore.run`, `SagaStore.readEvents`, `RunMeta.fold`
- **Test coverage**: `SagaStoreTest.java#runs_list_newest_first_and_honors_the_limit`, `#a_run_with_events_but_no_meta_file_is_reconstructed_from_its_events`, `#the_events_cursor_resumes_from_a_line_offset`; `SagaServerTest.java#an_unknown_run_returns_404`
- **Gherkin scenario**:
  ```gherkin
  Given a run's meta.json is missing or stale, When requested, Then reconstructed on the fly by replaying events.ndjson.
  ```

#### GIMLE-488 — Live NDJSON tail (`follow=true`) of a run's event stream

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a console client, I want a chunked HTTP stream that tails a run's events as they arrive.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaServer.streamFollow`, `SagaServer.isTerminal`
- **Test coverage**: `SagaServerTest.java#follow_streams_new_lines_as_they_arrive_and_ends_when_the_run_finishes`
- **Gherkin scenario**:
  ```gherkin
  Given a run is LIVE, When I request `/api/runs/{id}/events?follow=true`, Then new lines stream as appended; the stream closes only after the run reaches terminal AND every line has been delivered.
  ```

#### GIMLE-489 — Abandoned-run detection on restart

- **Category**: Reporting backend / Internal-Infra
- **User story**: As an operator, I want any run still marked LIVE at server startup automatically marked ABANDONED.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaStore.markLiveRunsAbandoned`, `RunMeta.RunStatus.ABANDONED`
- **Test coverage**: `SagaStoreTest.java#a_live_run_is_marked_abandoned_at_startup`
- **Gherkin scenario**:
  ```gherkin
  Given a run was LIVE when Saga last shut down, When the server restarts, Then that run's status is rewritten to ABANDONED before any read can observe it.
  ```

#### GIMLE-490 — Flake ledger derivation (fail-then-pass rule) and rebuild

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a test infrastructure integrator, I want Saga to automatically derive flake observations whenever a run finishes, and rebuild the whole ledger from scratch.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaStore.deriveFlakeObservations`, `SagaStore.rebuildLedger`, `FlakeObservation.java`
- **Test coverage**: `SagaStoreTest.java#a_failed_attempt_followed_by_a_passing_retry_yields_one_flake_observation`, `#a_test_that_fails_every_attempt_yields_no_flake_observation`, `#rebuild_ledger_reproduces_the_derived_observations_from_scratch`, `#an_unparseable_ledger_line_is_skipped_not_fatal`
- **Gherkin scenario**:
  ```gherkin
  Given a test fails attempt 1 and passes attempt 2 in the same run, When the run finishes, Then exactly one flake observation is recorded.
  ```

#### GIMLE-491 — Flaky scoreboard with time-window ranking

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a developer, I want a `GET /api/flaky?window=N` endpoint ranking tests by flake score within a time window.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaServer.handleFlaky`, `SagaStore.flakyScoreboard`
- **Test coverage**: `SagaStoreTest.java#the_flaky_scoreboard_counts_runs_seen_and_ranks_by_score`, `#the_flaky_scoreboard_window_excludes_older_observations`; `SagaServerTest.java#flaky_entries_carry_quarantine_status_and_the_response_carries_the_budget_allowance`
- **Gherkin scenario**:
  ```gherkin
  Given observations both inside/outside a 30-day window, When I query the scoreboard, Then only in-window observations count.
  ```

#### GIMLE-492 — Test-tag index and quarantine status

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a developer, I want a test's most recently observed JUnit tags tracked as a current-state index.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaStore.updateTestTags`, `SagaStore.quarantined`, `SagaStore.loadTestTags`
- **Test coverage**: `SagaStoreTest.java#a_test_tagged_flaky_is_quarantined_and_an_untagged_one_is_not`, `#the_latest_tag_set_for_a_test_id_overwrites_an_earlier_one`, `#the_test_tags_index_survives_a_store_restart`
- **Gherkin scenario**:
  ```gherkin
  Given a test was last observed tagged `flaky`, When I query quarantine status, Then it reports true even if an earlier run tagged it differently.
  ```

#### GIMLE-493 — Per-test history endpoint

- **Category**: Reporting backend / Internal-Infra
- **User story**: As a console client, I want `GET /api/tests/{testId}/history` for the Test Detail screen.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaServer.handleTestHistory`, `SagaStore.testHistory`
- **Test coverage**: `SagaStoreTest.java#test_history_reports_final_outcome_and_flakiness_per_run_newest_first`
- **Gherkin scenario**:
  ```gherkin
  Given a test ran in 3 runs with a flaky pass in one, When I request its history, Then each entry reports the final attempt's outcome/duration and flaky flag.
  ```

#### GIMLE-494 — Path traversal protection on run IDs

- **Category**: Internal-Infra / Security
- **User story**: As an operator of the Saga process, I want run IDs used as directory names strictly allow-listed.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaStore.RUN_ID`, `SagaStore.validateRunId`
- **Test coverage**: `SagaStoreTest.java#a_run_id_that_could_escape_the_store_directory_is_rejected`
- **Gherkin scenario**:
  ```gherkin
  Given a run ID containing `../` or path separators, When validated, Then rejected with 400.
  ```

#### GIMLE-495 — Bundled console static serving

- **Category**: Internal-Infra / Config
- **User story**: As a developer running Saga locally, I want the bundled gimle-saga-console SPA served automatically at `/console`.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SagaMain.java`, `SagaServer.serveConsole`
- **Test coverage**: `SagaServerTest.java#the_bundled_console_is_served_at_console`
- **Gherkin scenario**:
  ```gherkin
  Given the console jar is bundled, When SagaMain starts, Then `/console` serves the SPA; absent, `/console` is disabled with a log line.
  ```

### gimle-testkit

#### GIMLE-496 — Poll-until-condition primitive (`Await`)

- **Category**: Internal/Infra
- **User story**: As a test author writing real-cluster tests, I want a spin-poll-until-true helper instead of a fixed sleep.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/Await.java`
- **Test coverage**: NONE directly (used pervasively across gimle-mimir/gimle-fabric/gimle-worker/gimle-controlplane test sources)
- **Gherkin scenario**:
  ```gherkin
  Given a boolean condition and a timeout, When the condition becomes true before the deadline, Then `Await.until` returns immediately; When the deadline passes first, Then it throws naming the condition.
  ```

#### GIMLE-497 — Kernel-assigned loopback port leasing (`PortLease`)

- **Category**: Internal/Infra
- **User story**: As a real-cluster test fixture, I want to reserve a whole port budget up front via bound loopback sockets.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/PortLease.java`
- **Test coverage**: `PortLeaseTest`
- **Gherkin scenario**:
  ```gherkin
  Given a requested port count, When `PortLease.reserve(n)` is called, Then n distinct kernel-assigned loopback ports are bound and held; `release(port)` closes the socket and hands the port number to the process.
  ```

#### GIMLE-498 — Heimdall event-driven cluster condition harness

- **Category**: Test Infrastructure
- **User story**: As a cluster-validation test author, I want to await conditions over real cluster state driven by events rather than polling loops in my own test code.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/Heimdall.java`, `HeimdallScope.java`, `HeimdallCondition.java`
- **Test coverage**: NONE at unit level (exercised by every gimle-holmgang scenario); `ClusterViewTest` covers view-parsing
- **Gherkin scenario**:
  ```gherkin
  Given control-plane replica base URLs and supervised processes, When a registered view/log/probe condition's predicate becomes true, Then the corresponding HeimdallCondition future completes; a supervised process dying fails every pending condition immediately.
  ```

#### GIMLE-499 — Replica-scoped condition observation

- **Category**: Test Infrastructure
- **User story**: As a multi-replica-cluster test author, I want to scope a condition to one specific control-plane replica's own observed view.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/HeimdallScope.java`, `Heimdall.java`
- **Test coverage**: Exercised by `HaTopologyIT.deployments_written_via_one_replica_are_observed_active_via_the_other`, `deployment-lifecycle.feature`
- **Gherkin scenario**:
  ```gherkin
  Given `cluster.when(1)`, When a ClusterView satisfying the predicate is fetched from replica 1, Then the condition completes; views from other replicas are ignored.
  ```

#### GIMLE-500 — Deployment/node/log condition builders

- **Category**: Test Infrastructure
- **User story**: As a scenario author, I want typed, fluent condition builders for common deployment/node/log assertions.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/DeploymentConditions.java`, `NodeConditions.java`, `LogConditions.java`
- **Test coverage**: Exercised throughout gimle-holmgang `*.feature` files and `HaTopologyIT`/`MinimalTopologyIT`
- **Gherkin scenario**:
  ```gherkin
  Given a deployment name, When `.isActive()`/`.hasActiveReplicas(n)`/`.allOnVersion(v,n)`/`.hasFailedInstance()`/`.isAbsent()`/`.reportsQuotaViolation()` is awaited, Then it resolves once the corresponding condition holds.
  ```

#### GIMLE-501 — Time-windowed negative invariants (`Invariant`/`InvariantGuard`)

- **Category**: Test Infrastructure
- **User story**: As a scenario author asserting a negative property, I want to hold an invariant over a fixed window and fail with a forensic report the moment it breaks.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/Invariants.java`, `InvariantGuard.java`, `Invariant.java`
- **Test coverage**: `InvariantTest`; `rolling-update.feature`, `quota-and-admission.feature`
- **Gherkin scenario**:
  ```gherkin
  Given an Invariant and a Duration window, When `holdFor(invariant, window)` runs, Then every observed view is checked; the call throws with a forensic report the instant one violates it.
  ```

#### GIMLE-502 — Forensic failure reporting

- **Category**: Test Infrastructure
- **User story**: As a developer debugging a failed real-cluster test, I want every condition timeout/failure to carry a rendered snapshot of cluster state.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/ForensicReport.java`, `HeimdallConditionError.java`
- **Test coverage**: `ForensicReportTest`; `MinimalTopologyIT.a_failed_condition_reports_the_cluster_state_it_gave_up_on`
- **Gherkin scenario**:
  ```gherkin
  Given a condition times out or a process dies, When the failure is raised, Then the error message includes the last ClusterView, process liveness, recent harness events, and recent platform events.
  ```

### gimle-examples

#### GIMLE-503 — `hello-module` — minimal inert deployable fixture

- **Category**: Sample Module
- **User story**: As a platform developer doing manual QA, I want a trivial, deliberately inert real module artifact with distinct request/limit resource values.
- **Status**: Complete (deliberately minimal by design)
- **Confidence**: High
- **Source location(s)**: `gimle-examples/hello-module/src/main/resources/META-INF/gimle/gimle-module.yaml`, `Hello.java`, `deployment.yaml`
- **Test coverage**: NONE (manual verification only)
- **Gherkin scenario**:
  ```gherkin
  Given the hello-module jar and its deployment.yaml, When deployed with 1 replica, Then it reaches ACTIVE with no health probes or lifecycle hooks resolved (none declared).
  ```

#### GIMLE-504 — `greeter-provider` — real fabric service export with lifecycle hooks and health probes

- **Category**: Sample Module
- **User story**: As a platform developer proving the fabric works end to end, I want a real Tier 2 module that registers a Greeter service on onStart, reports readiness once registered, and reads a tenant secret/config value.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-examples/greeter-provider/src/main/java/com/gimle/examples/greeter/provider/GreeterProviderHooks.java`, probes, `gimle-module.yaml`
- **Test coverage**: `GreeterClusterTopologyIT`; multiple gimle-holmgang `*.feature`/`*IT`
- **Gherkin scenario**:
  ```gherkin
  Given greeter-provider deployed with a tenant, When the instance starts, Then it registers Greeter on the fabric, ReadinessProbe reports ready only after registration, and logs the tenant's secret value fetched via ctx.config(...).
  ```

#### GIMLE-505 — `greeter-consumer` — real cross-worker fabric call with MDC-tagged background caller

- **Category**: Sample Module
- **User story**: As a platform developer proving cross-worker service invocation, I want a module that repeatedly looks up and calls Greeter on a background virtual thread, re-resolving on every call.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-examples/greeter-consumer/src/main/java/com/gimle/examples/greeter/consumer/GreeterConsumerHooks.java`, probes, `gimle-module.yaml`
- **Test coverage**: `GreeterClusterTopologyIT`; `deployment-lifecycle.feature`
- **Gherkin scenario**:
  ```gherkin
  Given greeter-consumer deployed alongside greeter-provider, When the consumer's background loop runs, Then every 5s it looks up Greeter, calls greet("Gimlé"), and logs the reply with its own instance's MDC tags applied.
  ```

#### GIMLE-506 — `greeter-load-generator` — HTTP bridge for external load tools driving real fabric traffic

- **Category**: Sample Module / Load Testing
- **User story**: As a load-testing harness (Gatling), I want an HTTP endpoint inside a real hosted module that performs a synchronous fabric lookup+call per request.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-examples/greeter-load-generator/src/main/java/com/gimle/examples/greeter/loadgen/GreeterLoadGeneratorHooks.java`, `gimle-module.yaml`
- **Test coverage**: `AutoscaleIT.a_deployment_scales_up_under_real_gatling_generated_request_rate_load`; `autoscale.feature`
- **Gherkin scenario**:
  ```gherkin
  Given greeter-load-generator deployed with load.port via tenant config, When an HTTP GET hits /call, Then it performs a real lookupService(Greeter.class)+greet("Gatling") call and reflects 200/502/503 based on outcome.
  ```

### gimle-smoke-tests

#### GIMLE-507 — Real multi-process cluster fixture (store/control-plane/agent/Fafnir/Muninn)

- **Category**: Internal/Infra
- **User story**: As a smoke-test author, I want a shared fixture that spawns a genuine multi-node Raft store cluster, multiple control-plane replicas, one node agent, multiple Fafnir replicas, and a Muninn replica as real OS subprocesses.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterSmokeClusterSupport.java`
- **Test coverage**: Base fixture for every `*IT` in this module (24 concrete IT classes)
- **Gherkin scenario**:
  ```gherkin
  Given `GreeterSmokeClusterSupport.startCluster()`, When the fixture boots, Then 3 store nodes, 2 control-plane replicas, 1 agent, 2 Fafnir replicas, and 1 Muninn are all live real processes torn down in reverse dependency order.
  ```

#### GIMLE-508 — On-the-fly compiled module variants via `TestModuleBuilder`

- **Category**: Internal/Infra
- **User story**: As a smoke-test author needing a faulty/slow/leaky/version-bumped module without modifying the real committed example, I want purpose-built module variants compiled at test run time.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GreeterSmokeClusterSupport.java` (buildGreeterProviderVariant, buildProviderV2Jar, buildFaultyProviderJar, buildAlwaysBrokenProviderJar, buildAlwaysUnhealthyProviderJar, buildLeakyProviderJar, buildInertTier1ModuleJar, buildQuickSucceedingJobModuleJar, buildStatefulModuleJar, buildSlowProviderJar)
- **Test coverage**: Used by ClassloaderLeakIT, RedeployStabilityIT, SelfHealingIT, ServiceFabricIT, Tier1DensityIT, JobLifecycleIT, StatefulSetPersistenceIT, AutoscaleIT, RollingUpdateIT, SurgePromotionIT
- **Gherkin scenario**:
  ```gherkin
  Given a variant spec, When `buildFaultyProviderJar()` is called, Then a real compiled jar with that behavior is produced and deployable through the real HTTP API.
  ```

#### GIMLE-509 — Base cluster topology deploy across store cluster and multiple CP replicas

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want deployments to reach ACTIVE consistently regardless of which control-plane replica served the submission or is being observed.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterClusterTopologyIT.java`
- **Test coverage**: `GreeterClusterTopologyIT.greeter_modules_deploy_across_a_store_cluster_and_multiple_control_plane_replicas`
- **Gherkin scenario**:
  ```gherkin
  Given a real cluster, When greeter-provider and greeter-consumer are deployed, Then both reach ACTIVE (observed via a different replica than submission), the consumer's fabric call appears in its own log, and the provider's log shows its secret round-tripped through Fafnir.
  ```

#### GIMLE-510 — Raft store resilience (member loss, leader failover, live membership change)

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want the state store to tolerate losing a member (including the leader) mid-workload with zero acknowledged-write loss, and support adding/removing members live.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-smoke-tests/src/test/java/com/gimle/smoketests/RaftResilienceIT.java`
- **Test coverage**: `RaftResilienceIT.cluster_tolerates_losing_one_store_node_mid_deployment`, `a_leader_failover_loses_no_acknowledged_write_under_concurrent_load`, `a_new_store_node_joins_via_live_membership_change_and_is_then_removed`
- **Gherkin scenario**:
  ```gherkin
  Given a real 3-store cluster with concurrent writes in flight, When a store node (or the leader) is killed, Then the cluster keeps accepting writes and no acknowledged write is lost.
  ```

#### GIMLE-511 — Tiered self-healing (worker respawn, liveness-exhaustion escalation to FAILED)

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a killed worker JVM automatically respawned and its instance returned to ACTIVE, and a module that never passes liveness to escalate to FAILED once its restart budget exhausts.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-smoke-tests/src/test/java/com/gimle/smoketests/SelfHealingIT.java`
- **Test coverage**: `SelfHealingIT.a_crashed_workers_instance_is_respawned_and_returns_to_active`, `a_module_that_never_passes_its_own_liveness_check_exhausts_its_restart_budget_and_fails`
- **Gherkin scenario**:
  ```gherkin
  Given an ACTIVE instance, When its worker JVM is force-killed, Then the agent respawns a new worker and the deployment returns to ACTIVE; a never-recovering module escalates to FAILED after budget exhaustion.
  ```

#### GIMLE-512 — Classloader leak detection wired into a real worker

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a module that leaks its own classloader on redeploy to be detected and reported by the worker's real LeakTracker.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ClassloaderLeakIT.java` (also fixed a real gap: WorkerMain previously never wired LeakTracker into the real ModuleController)
- **Test coverage**: `ClassloaderLeakIT.a_module_that_leaks_its_own_classloader_on_redeploy_is_reported_by_leak_tracker`
- **Gherkin scenario**:
  ```gherkin
  Given a module leaving a background platform thread running past onStop, When redeployed to a new version, Then a ModuleLeakDetected event is logged to worker-platform.log.
  ```

#### GIMLE-513 — Repeated redeploy stability without false-positive leaks

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a well-behaved module to survive several real redeploy cycles on a shared worker without ever reporting a false-positive leak.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RedeployStabilityIT.java`
- **Test coverage**: `RedeployStabilityIT.a_well_behaved_module_survives_repeated_redeploys_without_ever_reporting_a_leak`
- **Gherkin scenario**:
  ```gherkin
  Given a well-behaved module on a shared Tier 1 worker, When redeployed several times in a row, Then LeakTracker never reports a leak for any cycle.
  ```

#### GIMLE-514 — Tier 1 worker density packing and its cap

- **Category**: Cluster Validation
- **User story**: As a platform operator relying on Tier 1 density, I want several distinct Tier 1 modules to genuinely share one worker JVM up to MAX_TIER1_DENSITY.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `Tier1DensityIT.java`
- **Test coverage**: `Tier1DensityIT` (density-packing test)
- **Gherkin scenario**:
  ```gherkin
  Given up to MAX_TIER1_DENSITY distinct Tier 1 modules deployed, When each deploys, Then all pack onto one real worker process; one more distinct module gets a fresh worker.
  ```

#### GIMLE-515 — Node cordoning blocks new placement without evicting running instances

- **Category**: Cluster Validation
- **User story**: As a platform operator performing maintenance, I want a cordoned node to accept no new placements while never evicting what's already running there.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `NodeCordoningIT.java`
- **Test coverage**: `NodeCordoningIT.a_cordoned_node_blocks_new_placement_but_never_evicts_an_already_running_instance`
- **Gherkin scenario**:
  ```gherkin
  Given a cordoned single-node cluster, When a new deployment is submitted, Then it stays unplaced, while an already-running instance keeps serving.
  ```

#### GIMLE-516 — DaemonSet per-node fan-out and dead-node assignment cleanup

- **Category**: Cluster Validation
- **User story**: As a platform operator running cluster-wide agents, I want a DaemonSet to place one instance on every eligible node and have a dead node's assignment cleaned up.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `DaemonSetLifecycleIT.java`
- **Test coverage**: `DaemonSetLifecycleIT.a_daemonset_places_on_every_node_and_a_dead_nodes_assignment_is_cleaned_up`
- **Gherkin scenario**:
  ```gherkin
  Given a DaemonSet deployed across 2 real agent nodes, When both are up, Then one instance per node; When one node's agent is hard-killed, Then that node's assignment is cleaned up.
  ```

#### GIMLE-517 — Job and CronJob real-cluster lifecycle

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a Job to run its JobHooks#run on a real worker JVM and reach SUCCEEDED, and a CronJob trigger to generate a real Job that succeeds.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `JobLifecycleIT.java`
- **Test coverage**: `JobLifecycleIT.a_job_that_succeeds_reaches_the_succeeded_phase_and_stays_there`, `a_triggered_cronjob_generates_a_real_job_that_reaches_succeeded`
- **Gherkin scenario**:
  ```gherkin
  Given a Job-kind module returning SUCCEEDED, When deployed, Then it reaches JobPhase.SUCCEEDED; a triggered CronJob generates a Job that likewise succeeds.
  ```

#### GIMLE-518 — StatefulSet sticky placement and volume persistence across worker restart

- **Category**: Cluster Validation
- **User story**: As a platform operator running stateful workloads, I want a StatefulSet instance's persistent volume and node placement to survive a worker crash-and-respawn.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `StatefulSetPersistenceIT.java`
- **Test coverage**: `StatefulSetPersistenceIT.a_statefulset_instance_keeps_its_sticky_node_and_its_volume_data_across_a_worker_restart`
- **Gherkin scenario**:
  ```gherkin
  Given a StatefulSet instance that wrote a marker file, When its worker is killed and respawned, Then the instance lands on the same node and sees the marker file already present.
  ```

#### GIMLE-519 — Rolling update preserves serving capacity and reaches new version

- **Category**: Cluster Validation
- **User story**: As a platform operator rolling out a new module version, I want a surge-budgeted rollout to never drop below the guaranteed replica floor and every instance to end on the new version.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `RollingUpdateIT.java`
- **Test coverage**: `RollingUpdateIT.a_rolling_update_keeps_at_least_one_instance_serving_traffic_throughout`, `a_single_replica_rolling_update_has_real_observed_downtime`
- **Gherkin scenario**:
  ```gherkin
  Given a 2-replica deployment with maxUnavailable=1/maxSurge=1, When rolled to v1.1.0, Then at least 1 instance stays ACTIVE throughout and all 2 end on v1.1.0.
  ```

#### GIMLE-520 — Surge worker promotion carries out via in-place retarget, not respawn

- **Category**: Cluster Validation
- **User story**: As a platform operator relying on surge rollouts, I want a promoted surge worker to keep its exact OS process while the vacated index gets a genuinely new process.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `SurgePromotionIT.java`
- **Test coverage**: `SurgePromotionIT.a_promoted_surge_worker_keeps_the_same_process_while_the_restarted_one_does_not`
- **Gherkin scenario**:
  ```gherkin
  Given a 2-replica rollout with maxUnavailable=1/maxSurge=1, When the surge worker is promoted, Then its OS PID is unchanged, while the other (restarted) instance's PID changes.
  ```

#### GIMLE-521 — Autoscaling under real request-rate, error-rate, queue-depth, and weighted-blended load

- **Category**: Load Testing / Cluster Validation
- **User story**: As a platform operator, I want deployments to scale up in response to real worker-reported request rate, error rate, or queue depth signals.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AutoscaleIT.java`
- **Test coverage**: `AutoscaleIT.a_deployment_scales_up_under_real_gatling_generated_request_rate_load`, `a_deployment_scales_up_under_real_error_rate_load`, `a_deployment_scales_up_under_real_queue_depth_load`, `a_weighted_policy_blends_request_rate_and_queue_depth_signals_under_real_load`
- **Gherkin scenario**:
  ```gherkin
  Given an autoscale policy targeting a signal, When real Gatling-generated load pushes it past target, Then the deployment scales toward maxReplicas.
  ```

#### GIMLE-522 — Multi-tenant quota enforcement (flag-not-evict, and admission rejection)

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a retroactively-lowered quota to flag a violating deployment without eviction, and a new deployment exceeding quota rejected at admission.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `QuotaIT.java`
- **Test coverage**: `QuotaIT.a_tenant_over_quota_deployment_is_flagged_but_not_evicted`, `a_deployment_that_would_exceed_tenant_quota_is_rejected_at_admission`
- **Gherkin scenario**:
  ```gherkin
  Given a tenant already running at quota, When quota is lowered, Then the deployment is flagged but keeps running; a new submission exceeding quota is rejected with 409.
  ```

#### GIMLE-523 — Circuit breaker excludes a consistently-failing replica

- **Category**: Cluster Validation
- **User story**: As a service fabric consumer, I want repeated real failures from one endpoint to open its circuit breaker and exclude it.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ServiceFabricIT.java`
- **Test coverage**: `ServiceFabricIT.a_circuit_breaker_excludes_a_consistently_failing_replica_after_real_failures`
- **Gherkin scenario**:
  ```gherkin
  Given a genuinely broken provider replica hanging past the client timeout, When enough consecutive calls fail via real timeout, Then FabricServiceRegistry's breaker opens and excludes that replica.
  ```

#### GIMLE-524 — Gossip/SWIM failure detection across real separate agent processes

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a hard-killed member to converge to "dead" on every surviving agent's own gossip view.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GossipFailureDetectionIT.java`
- **Test coverage**: `GossipFailureDetectionIT.a_hard_killed_member_converges_to_dead_on_both_surviving_real_agents`
- **Gherkin scenario**:
  ```gherkin
  Given real agent processes joined via gossip, When one is hard-killed, Then every surviving agent's SWIM state eventually marks it dead.
  ```

#### GIMLE-525 — Observability data survives agent death (Muninn fallback) and control-plane metrics round-trip

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a deployed instance's log to remain readable via Muninn after its agent dies, and control-plane metrics to round-trip through Muninn.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `ObservabilityIT.java`
- **Test coverage**: `ObservabilityIT.a_deployed_instances_log_survives_its_owning_agent_dying`, `a_control_planes_own_request_metrics_round_trip_through_muninn`
- **Gherkin scenario**:
  ```gherkin
  Given a deployed instance logging normally, When its agent dies, Then its log is still readable via the /logs/* Muninn fallback.
  ```

#### GIMLE-526 — Worker-tier metrics/trace relay to Muninn via the agent

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a module's own request counter and fabric-call trace span to reach Muninn under WORKER process kind via agent relay.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `WorkerObservabilityIT.java`
- **Test coverage**: single @Test in WorkerObservabilityIT
- **Gherkin scenario**:
  ```gherkin
  Given a real deployed module making a real fabric call, When its worker relays MetricsSnapshot/TracesSnapshot to its agent, Then the agent forwards it to Muninn and it's readable via GET /metrics-history/*.
  ```

#### GIMLE-527 — Artifact registry (Andvari) resolution path end to end

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a jar pushed to Andvari and a coordinate-only deployment to resolve through a real agent pull-through cache and reach ACTIVE.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `AndvariRegistryIT.java`
- **Test coverage**: `AndvariRegistryIT.a_coordinate_only_deployment_pulls_its_jar_from_andvari_through_the_agent_cache`
- **Gherkin scenario**:
  ```gherkin
  Given a real Andvari replica and a pushed jar, When a coordinate-only deployment is submitted, Then the agent resolves and caches the jar and the instance reaches ACTIVE.
  ```

#### GIMLE-528 — External HTTP request reaches a fabric service through the gateway

- **Category**: Cluster Validation
- **User story**: As an external client, I want an HTTP request to gimle-gateway (deployed as a DaemonSet in gimle-system) to route to a real fabric service and return its response.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `GatewayFabricRouteIT.java`
- **Test coverage**: `GatewayFabricRouteIT.an_external_http_request_reaches_a_real_fabric_service_through_the_gateway`
- **Gherkin scenario**:
  ```gherkin
  Given greeter-provider and gimle-gateway deployed as a DaemonSet on an edge-labeled node, When an external client hits the gateway, Then the response reflects a real fabric call to greeter-provider.
  ```

### gimle-holmgang

#### GIMLE-529 — Declarative cluster topology DSL/YAML parsing and validation

- **Category**: Internal/Infra
- **User story**: As a validation-suite author, I want to declare a real cluster's shape as YAML or a fluent Java DSL.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/topology/ClusterTopology.java`, `ClusterTopologyParser.java`, `ClusterSpec.java`, `NodeSpec.java`, `Transport.java`
- **Test coverage**: `ClusterTopologyDslTest`, `ClusterTopologyParserTest`, `GimleClusterStartRejectionTest.a_fault_proxied_mtls_topology_is_rejected_at_model_construction`
- **Gherkin scenario**:
  ```gherkin
  Given a topologies/*.yaml file or a ClusterTopology.named(...) DSL chain, When parsed/built, Then a ClusterSpec results; the one unsupported combination (fault-proxied + mTLS) is rejected before any process spawns.
  ```

#### GIMLE-530 — Real subprocess cluster orchestration (`GimleCluster`)

- **Category**: Internal/Infra
- **User story**: As a scenario author, I want a single API to boot every process kind a topology declares as real subprocesses.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/cluster/GimleCluster.java`, `GimleProcess.java`, `ManagedProcess.java`, `ClusterApi.java`
- **Test coverage**: Foundation for every scenario in this module
- **Gherkin scenario**:
  ```gherkin
  Given a ClusterSpec, When GimleCluster.start(spec, workDir) runs, Then every declared process is a live, correctly-wired real subprocess reachable through cluster.api()/cluster.when().
  ```

#### GIMLE-531 — Cluster pooling per topology with destructive-scenario isolation

- **Category**: Internal/Infra
- **User story**: As the Gherkin runner, I want non-destructive scenarios to share one pooled cluster per topology while @destructive scenarios get a fresh, exclusively-owned cluster.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/steps/ClusterPool.java`
- **Test coverage**: Exercised implicitly by every HolmgangIT-run Gherkin scenario
- **Gherkin scenario**:
  ```gherkin
  Given a scenario tagged @destructive, When it starts, Then a brand-new GimleCluster is booted solely for it; non-destructive scenarios reuse the shared pooled cluster.
  ```

#### GIMLE-532 — JUnit `@Holmgang`/`@HolmgangCluster` extension for plain-JUnit cluster tests

- **Category**: Internal/Infra
- **User story**: As a plain-JUnit test author, I want an annotation-driven extension that boots a named topology and injects the live cluster into my test.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/junit/Holmgang.java`, `HolmgangCluster.java`, `HolmgangExtension.java`
- **Test coverage**: `HaTopologyIT`, `MinimalTopologyIT` both use it directly
- **Gherkin scenario**:
  ```gherkin
  Given a class annotated @Holmgang(topology="...") with a @HolmgangCluster field, When JUnit runs the test, Then the extension boots the topology and injects the cluster.
  ```

#### GIMLE-533 — Fenrir randomized chaos-fault soak executor

- **Category**: Chaos Engineering
- **User story**: As a platform reliability engineer, I want a seeded, weighted-pool fault scheduler that strikes a healthy real cluster repeatedly and gates every next strike on full recovery.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/fenrir/Fenrir.java`, `FaultKind.java`, `FenrirPlan.java`, `ChaosSchedule.java`, `Pool.java`
- **Test coverage**: `FenrirPlanTest`, `ChaosScheduleTest`; end-to-end via `chaos-soak.feature`/`observability-registry-ha.feature`
- **Gherkin scenario**:
  ```gherkin
  Given a FenrirPlan with a soak duration and strike interval, When Fenrir.unleash(cluster, plan) runs, Then it repeatedly draws a fault kind/victim, applies it, and awaits recovery through Heimdall, recording each strike into a ChaosLedger.
  ```

#### GIMLE-534 — Chaos ledger recording and rendering

- **Category**: Chaos Engineering
- **User story**: As a chaos-soak scenario author, I want every strike's outcome recorded and renderable as a human-readable summary.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/fenrir/ChaosLedger.java`
- **Test coverage**: `ChaosLedgerTest`
- **Gherkin scenario**:
  ```gherkin
  Given a completed Fenrir run, When its ChaosLedger is queried, Then executedCount()/recoveredCount()/skippedCount()/allRecovered() reflect actual strikes.
  ```

#### GIMLE-535 — Randomized fault soak with no lost writes (basic and compound-fault modes)

- **Category**: Chaos Engineering
- **User story**: As a platform reliability engineer, I want the whole cluster to survive a randomized fault soak while a live write workload runs throughout, proving no acknowledged write is ever lost.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/chaos-soak.feature`; `ChaosSteps.java`, `WorkloadSteps.java`
- **Test coverage**: `HolmgangIT` executing `chaos-soak.feature`'s two scenarios
- **Gherkin scenario**:
  ```gherkin
  Given a running HA cluster and a background write workload, When Fenrir is unleashed striking every 15s, Then at least 3 faults execute, every fault recovers, and every acknowledged write remains readable.
  ```

#### GIMLE-536 — Muninn/Andvari replica-bounce resilience soak

- **Category**: Chaos Engineering
- **User story**: As a platform reliability engineer, I want targeted chaos coverage of Muninn's fan-out and Andvari's peer-sync, bouncing only their replicas.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/observability-registry-ha.feature`; `Fenrir.muninnBounce`/`andvariBounce`
- **Test coverage**: `HolmgangIT` executing `observability-registry-ha.feature`
- **Gherkin scenario**:
  ```gherkin
  Given a topology with 2 Muninn and 2 Andvari replicas, When Fenrir strikes only Muninn/Andvari bounces, Then at least 2 faults execute and recover, and a coordinate-only deployment still reaches ACTIVE afterward.
  ```

#### GIMLE-537 — Live store membership change (AddServer/RemoveServer)

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want to add a new store node to a running Raft cluster and later remove one, with writes accepted throughout.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/membership-change.feature`; `ClusterSteps.java`
- **Test coverage**: `HolmgangIT` executing `membership-change.feature`
- **Gherkin scenario**:
  ```gherkin
  Given a 3-store HA cluster, When a fourth store node joins, Then the store reports 4 members and accepts writes; leaving reports 3 members again.
  ```

#### GIMLE-538 — Mutual TLS end-to-end operation and anonymous-client rejection

- **Category**: Cluster Validation
- **User story**: As a security-conscious operator, I want the whole cluster to run with mTLS on every inter-process hop, and any anonymous client turned away.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/mtls.feature`; `SecuritySteps.java`
- **Test coverage**: `HolmgangIT` executing `mtls.feature`'s two scenarios
- **Gherkin scenario**:
  ```gherkin
  Given an mTLS-transport cluster, When greeter-provider is deployed with a tenant secret, Then it logs the secret value within 60s; an anonymous client attempting a write is rejected with 401.
  ```

#### GIMLE-539 — Control-plane partition tolerance (store-side) and reconvergence on heal

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a control-plane replica cut off from the store cluster to stop serving, while the surviving replica keeps handling all work.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` (scenario 1)
- **Test coverage**: `HolmgangIT` executing `partition-tolerance.feature`
- **Gherkin scenario**:
  ```gherkin
  Given a fault-proxied HA cluster, When the network between control plane 1 and all stores is cut, Then control plane 1 stops serving within 30s while a submission via replica 0 still reaches ACTIVE; on heal, also ACTIVE via replica 1.
  ```

#### GIMLE-540 — Store leader self-demotion under silent peer partition; bounded write latency

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a Raft leader silently cut off from its followers to step down within a bounded window, and any in-flight write to complete rather than hang.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` (scenario 2); `WorkloadSteps.java`
- **Test coverage**: `HolmgangIT` executing `partition-tolerance.feature`
- **Gherkin scenario**:
  ```gherkin
  Given an HA cluster, When the store leader is partitioned, Then it steps down within 10s; a write submitted during the partition completes within 30s (success or failure).
  ```

#### GIMLE-541 — Tenant deployment lifecycle with secret delivery and clean deletion

- **Category**: Cluster Validation
- **User story**: As a tenant operator, I want a tenant-scoped module to deploy, read its secret through Fafnir, and be completely drained on deletion.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature`
- **Test coverage**: `HolmgangIT` executing `deployment-lifecycle.feature`
- **Gherkin scenario**:
  ```gherkin
  Given a tenant secret and a deployment submitted, When the instance starts, Then it logs the secret within 60s; on deletion, absent within 60s.
  ```

#### GIMLE-542 — Tenant quota retroactive violation (flag, not evict) and admission rejection

- **Category**: Cluster Validation
- **User story**: As a tenant operator, I want a retroactively-lowered quota to flag a violation without eviction, and an over-quota submission rejected.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/quota-and-admission.feature`
- **Test coverage**: `HolmgangIT` executing `quota-and-admission.feature`
- **Gherkin scenario**:
  ```gherkin
  Given a tenant at quota, When quota is lowered, Then the deployment reports a violation within 60s while keeping its 1 ACTIVE instance for 10s; a starved-tenant submission is rejected with 409.
  ```

#### GIMLE-543 — Node cordoning blocks placement until uncordoned

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a cordoned node to leave a submitted deployment unplaced, and let the placement proceed once uncordoned.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/scheduling.feature`
- **Test coverage**: `HolmgangIT` executing `scheduling.feature`
- **Gherkin scenario**:
  ```gherkin
  Given node-1 cordoned, When a deployment is submitted, Then it stays unplaced for 10s; uncordoning it, the deployment reaches ACTIVE within 120s.
  ```

#### GIMLE-544 — Worker-tier self-healing and liveness-exhaustion escalation (Gherkin coverage)

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a killed worker JVM respawned with the deployment returning to ACTIVE, and a module that never passes liveness to escalate to FAILED.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/self-healing.feature`
- **Test coverage**: `HolmgangIT` executing `self-healing.feature`
- **Gherkin scenario**:
  ```gherkin
  Given greeter-provider deployed, When its worker is killed, Then within 120s the deployment is ACTIVE again; an always-failing-liveness provider escalates to a FAILED instance within 240s once its restart budget exhausts.
  ```

#### GIMLE-545 — Zero-downtime rolling update under surge budget (Gherkin coverage)

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a rolling update under maxSurge/maxUnavailable to never drop below a guaranteed-active floor.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/rolling-update.feature`
- **Test coverage**: `HolmgangIT` executing `rolling-update.feature`
- **Gherkin scenario**:
  ```gherkin
  Given a 2-replica deployment with a guard held, When rolled with maxUnavailable=1/maxSurge=1, Then within 180s both instances are ACTIVE on the new version, and the guard held throughout.
  ```

#### GIMLE-546 — Request-rate autoscaling under real Gatling-driven fabric load (Gherkin coverage)

- **Category**: Load Testing
- **User story**: As a platform operator, I want a deployment to scale from provider-reported request-rate signal driven by genuine external HTTP load.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/autoscale.feature`; `LoadGenerator.java`, `GreeterLoadSimulation.java`, `LoadSteps.java`
- **Test coverage**: `HolmgangIT` executing `autoscale.feature`
- **Gherkin scenario**:
  ```gherkin
  Given greeter-load-generator deployed and an autoscaling deployment targeting 5 rps min 1/max 2, When 20 rps load runs for 60s, Then within 120s the deployment has 2 ACTIVE replicas.
  ```

#### GIMLE-547 — Artifact registry coordinate-only deployment (Gherkin coverage)

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a module pushed to Andvari deployable by coordinate alone through the declarative harness.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/registry-deploy.feature`; `RegistrySteps.java`
- **Test coverage**: `HolmgangIT` executing `registry-deploy.feature`
- **Gherkin scenario**:
  ```gherkin
  Given a running "registry" topology, When greeter-provider is pushed and a deployment is submitted with no artifact path, Then within 60s the deployment is ACTIVE.
  ```

#### GIMLE-548 — Surtr scale/churn/performance workload runner

- **Category**: Load Testing
- **User story**: As a platform performance engineer, I want to declaratively describe a load burn and run it against a real cluster, measuring instance startup latency, API latency, failures, and placement spread.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/surtr/SurtrRunner.java`, `SurtrWorkload.java`, `SurtrJob.java`, `TokenBucket.java`, `Measurements.java`
- **Test coverage**: `SurtrWorkloadParserTest`, `SurtrUnitTest`; `SurtrIT.runs_the_configured_surtr_workload` (opt-in via `-Dgimle.surtr.workload=<name|path>`)
- **Gherkin scenario**:
  ```gherkin
  Given a SurtrWorkload and its declared topology, When SurtrRunner.run() executes each job in order, Then measurements are collected from real cluster state/event logs and gates evaluated into a SurtrRunResult.
  ```

#### GIMLE-549 — Surtr Muninn-window measurement (documented gap)

- **Category**: Load Testing
- **User story**: As a platform performance engineer, I want Surtr to also report Muninn-shipped metric windows for a burn.
- **Status**: Partial — explicitly deferred, self-documented in code
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/surtr/SurtrRunner.java` (`skippedMuninn()`)
- **Test coverage**: NONE (nothing real to test)
- **Gherkin scenario**:
  ```gherkin
  Given a workload requesting the muninnWindow measurement, When the run completes, Then it always reports a skipped placeholder series ("Muninn window metrics collection is not implemented in this build").
  ```

#### GIMLE-550 — Module-density Tier 1 packing Surtr reference workload

- **Category**: Load Testing
- **User story**: As a platform performance engineer validating the platform's core density claim, I want a reference workload packing many single-replica deployments of one module across a two-node cluster.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/resources/workloads/module-density.yaml`, `topologies/surtr-density.yaml`
- **Test coverage**: `SurtrIT` (opt-in, `-Dgimle.surtr.workload=module-density`)
- **Gherkin scenario**:
  ```gherkin
  Given workloads/module-density.yaml (10 iterations at 8 qps against topologies/surtr-density.yaml), When run via SurtrIT, Then all created deployments reach ACTIVE within 180s and the gates pass.
  ```

#### GIMLE-551 — Saga unified run reporting (Gherkin + JUnit + Fenrir + Surtr)

- **Category**: Internal/Infra
- **User story**: As a platform engineer reviewing a validation run, I want one unified report combining every Gherkin scenario result, plain-JUnit `*IT` result, Fenrir chaos ledger, Surtr measurement, and booted topology.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/saga/SagaCollector.java`, `SagaWriter.java`, `SagaCucumberPlugin.java`, `SagaJUnitListener.java`
- **Test coverage**: `SagaWriterTest`
- **Gherkin scenario**:
  ```gherkin
  Given a -Pvalidation run, When the JVM shuts down, Then SagaCollector.flush() writes a versioned JSON report plus a self-contained HTML console with that run's data embedded.
  ```

#### GIMLE-552 — Saga best-effort shipping to a remote report server

- **Category**: Internal/Infra
- **User story**: As a platform engineer aggregating results across multiple validation runs, I want the local Saga run's data to also ship as attachment events to a central Saga report server when configured.
- **Status**: Complete
- **Confidence**: Medium
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/saga/SagaShipper.java`
- **Test coverage**: NONE dedicated found
- **Gherkin scenario**:
  ```gherkin
  Given -Dgimle.saga.endpoint=<url>, When the collector flushes, Then it POSTs NDJSON-encoded attachment events; an unset/unreachable/erroring endpoint silently no-ops and never fails the run.
  ```

#### GIMLE-553 — Loki fault-injection proxy for store/control-plane link partitions

- **Category**: Internal/Infra
- **User story**: As a chaos/partition scenario author, I want to interpose proxies on control-plane→store and store→store links at boot, so I can cut or blackhole specific links live and heal them again.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/loki/Loki.java`, `LokiProxy.java`
- **Test coverage**: Exercised via `partition-tolerance.feature`; no dedicated LokiTest
- **Gherkin scenario**:
  ```gherkin
  Given a fault-proxied topology, When cutControlPlaneFromStores(index) is called, Then that replica's links reset immediately; cutStoreFromPeers(index) blackholes raft traffic until heal().
  ```

#### GIMLE-554 — Utgard multi-container distributed boot ordering

- **Category**: Cluster Validation
- **User story**: As a platform operator deploying across real separate machines, I want `hilmir up --machine` to genuinely block until remote prerequisites are up.
- **Status**: Complete (Docker-access-dependent skip via assumeTrue)
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardDistributedBootIT.java`
- **Test coverage**: `UtgardDistributedBootIT.a_machine_started_out_of_dependency_order_blocks_then_completes_once_its_prerequisites_are_up`
- **Gherkin scenario**:
  ```gherkin
  Given a 3-container fleet, When `hilmir up --machine` is issued for the server machine before prerequisites, Then it blocks until store and Fafnir machines are up, then completes and a real deployment reaches ACTIVE.
  ```

#### GIMLE-555 — Utgard real machine loss (hard container kill) and rejoin

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a hard-killed machine's hosted instance rescheduled onto a surviving node, and the machine able to rejoin after restart.
- **Status**: Complete (Docker-access-dependent skip)
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardMachineLossIT.java`
- **Test coverage**: `UtgardMachineLossIT.a_killed_machine_is_rescheduled_around_and_can_rejoin_after_restart`
- **Gherkin scenario**:
  ```gherkin
  Given an instance placed on one agent machine, When that container is hard-killed, Then rescheduled onto the surviving machine; restarting and rejoining re-registers its node.
  ```

#### GIMLE-556 — Utgard network partition (vs hard kill) with reconvergence

- **Category**: Cluster Validation
- **User story**: As a platform operator, I want a merely network-partitioned machine's instance rescheduled while partitioned, and reconvergence to exactly one ACTIVE instance on heal.
- **Status**: Complete (Docker-access-dependent skip)
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardPartitionIT.java`
- **Test coverage**: `UtgardPartitionIT.a_partitioned_machine_is_rescheduled_around_then_the_cluster_converges_on_reconnect`
- **Gherkin scenario**:
  ```gherkin
  Given an instance on one agent machine, When disconnected from the network, Then rescheduled to the surviving machine; reconnecting converges to exactly 1 ACTIVE instance.
  ```

#### GIMLE-557 — Utgard real-hostname mTLS bootstrap across containers

- **Category**: Cluster Validation
- **User story**: As a security-conscious operator running mTLS across real machines, I want CSR bootstrap and hostname verification proven against genuine DNS-addressed containers.
- **Status**: Complete (Docker-access-dependent skip)
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardMtlsIT.java`
- **Test coverage**: `UtgardMtlsIT.an_mtls_cluster_bootstraps_across_containers_addressed_by_real_hostnames`
- **Gherkin scenario**:
  ```gherkin
  Given two containers with `hilmir pki init` on the server machine, When `hilmir up` runs on each, Then the agent's CSR bootstrap succeeds over mTLS automatically, and the CLI dialing the server's real hostname shows the agent's node.
  ```

#### GIMLE-558 — Utgard Docker container fleet management primitives

- **Category**: Internal/Infra
- **User story**: As an Utgard scenario author, I want a reusable fleet abstraction (start/stop, exec, copy, kill/restart/disconnect/reconnect network) with a clean skip when Docker is unavailable.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardMachines.java`, `UtgardExec.java`, `UtgardForensics.java`, `UtgardPoll.java`, `UtgardTopologies.java`
- **Test coverage**: `UtgardExecTest`, `UtgardPollTest`, `UtgardTopologiesTest`
- **Gherkin scenario**:
  ```gherkin
  Given UtgardMachines.start(names), When a live Docker daemon with registry access is available, Then real containers boot on a shared network; blocked image pulls throw UtgardDockerUnavailableException and dependent tests skip via assumeTrue.
  ```

#### GIMLE-559 — Docker Compose manual validation topologies (bundled-JRE and full-JRE)

- **Category**: Packaging / Internal-Infra
- **User story**: As a platform engineer manually validating a gimle-dist build, I want ready-made Docker Compose files standing up a full cluster off the bundled jlink JRE or off full Temurin JRE images.
- **Status**: Complete (manual validation artifact)
- **Confidence**: High
- **Source location(s)**: `gimle-holmgang/compose/docker-compose.bundled-jre.yml`, `docker-compose.full-jre.yml`, `topology-bundled-jre.yaml`, `topology-full-jre.yaml`
- **Test coverage**: NONE automated — documented manual validation flow per README
- **Gherkin scenario**:
  ```gherkin
  Given `mvn -pl gimle-dist -am install -P dist-with-jre` produced the tarball, When `docker compose -f docker-compose.bundled-jre.yml up` runs, Then every service launches off `jre/<component>/bin/java` from a shared volume, with agent alone using a real eclipse-temurin base image.
  ```

### gimle-dist

#### GIMLE-560 — Standalone CLI distribution archive

- **Category**: Packaging
- **User story**: As an end user who only needs the gimle CLI, I want a minimal standalone tarball with gimle-cli's own runtime dependency closure plus a launcher script.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-dist/src/main/assembly/cli.xml`, `gimle-dist/pom.xml`, `gimle-dist/src/main/dist/bin/gimle`, `gimle-dist/src/main/dist/bin/gimle.cmd`
- **Test coverage**: NONE automated
- **Gherkin scenario**:
  ```gherkin
  Given `mvn -pl gimle-dist package`, When the cli assembly execution runs, Then `gimle-cli-<version>.tar.gz` contains bin/gimle, bin/gimle.cmd, and lib/*.jar scoped to exactly gimle-cli/core/module/pki, slf4j, logback, snakeyaml, Bouncy Castle.
  ```

#### GIMLE-561 — Standalone Hilmir bootstrap-tool distribution archive

- **Category**: Packaging
- **User story**: As an operator bootstrapping a new cluster machine, I want a minimal standalone tarball with gimle-hilmir's runtime closure plus its launcher.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-dist/src/main/assembly/hilmir.xml`, `gimle-dist/pom.xml`, `gimle-dist/src/main/dist/bin/hilmir`, `gimle-dist/src/main/dist/bin/hilmir.cmd`
- **Test coverage**: NONE automated
- **Gherkin scenario**:
  ```gherkin
  Given `mvn -pl gimle-dist package`, When the hilmir assembly execution runs, Then `gimle-hilmir-<version>.tar.gz` contains bin/hilmir, bin/hilmir.cmd, and lib/*.jar scoped to hilmir, core, slf4j, logback, snakeyaml.
  ```

#### GIMLE-562 — Cluster-machine platform distribution archive

- **Category**: Packaging
- **User story**: As an operator standing up a cluster machine, I want one archive with every process-kind jar on a single flat classpath, plus gimle-gateway's hosted-module jar kept separately.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-dist/src/main/assembly/platform.xml`, `gimle-dist/pom.xml`, `gimle-dist/src/main/dist/bin/gimle.cmd`, `gimle-dist/src/main/dist/bin/hilmir.cmd`
- **Test coverage**: NONE automated; proven indirectly by gimle-holmgang's Docker Compose validation
- **Gherkin scenario**:
  ```gherkin
  Given `mvn -pl gimle-dist package`, When the platform assembly execution runs, Then `gimle-platform-<version>.tar.gz` contains lib/*.jar (full closure minus gateway), modules/gimle-gateway-*.jar (undeployed), and all four wrapper scripts (bin/gimle, bin/gimle.cmd, bin/hilmir, bin/hilmir.cmd).
  ```

#### GIMLE-563 — Opt-in bundled-JRE distribution variant (`dist-with-jre` profile)

- **Category**: Packaging
- **User story**: As an operator wanting a self-contained archive with no host JDK dependency, I want an opt-in profile that jlinks a trimmed, per-component JRE, deliberately excluding agent/worker.
- **Status**: Complete
- **Confidence**: High
- **Source location(s)**: `gimle-dist/pom.xml` (`dist-with-jre` profile), `platform-with-jre.xml`, `cli-with-jre.xml`, `hilmir-with-jre.xml`
- **Test coverage**: NONE automated; manually validated via gimle-holmgang's docker-compose.bundled-jre.yml
- **Gherkin scenario**:
  ```gherkin
  Given `mvn -pl gimle-dist -am install -P dist-with-jre`, When the profile's exec-maven-plugin executions run, Then a trimmed JRE (--strip-debug --no-header-files --no-man-pages) is jlinked per component; the three archives additionally contain jre/<component>/ for their own components.
  ```

#### GIMLE-564 — Distribution archive checksums and SBOM generation

- **Category**: Packaging
- **User story**: As a security-conscious operator, I want every distribution archive accompanied by a SHA-256 checksum file and a CycloneDX SBOM.
- **Status**: Complete (one combined SBOM covering all three archives — deliberate simplification, documented in the pom's own comment)
- **Confidence**: High
- **Source location(s)**: `gimle-dist/pom.xml` (cyclonedx-maven-plugin and maven-antrun-plugin executions)
- **Test coverage**: NONE automated
- **Gherkin scenario**:
  ```gherkin
  Given `mvn -pl gimle-dist package`, When cyclonedx-maven-plugin and maven-antrun-plugin executions run, Then bom.json is generated and copied per archive, and a .sha256 checksum file is written next to each.
  ```

### gimle-skald

#### GIMLE-569 — gimle-skald: cluster DNS server resolving Service names to live endpoints

- **Category**: Service Fabric
- **User story**: As a module author, I want to resolve a Service's name via standard DNS (<service>[.<tenant>].svc.gimle.local) to a live backing address, so any DNS-capable client can reach a Service without depending on the control plane's HTTP API directly.
- **Status**: Complete for v1's stated scope: only a standard A query against the svc.gimle.local zone is answered; anything else (wrong opcode, non-A type, unknown/uncached name) gets a well-formed NOTIMP/NXDOMAIN reply, not silence. Polling the control plane stays plain HTTP for this first slice, no mTLS path yet.
- **Confidence**: High
- **Source location(s)**: `gimle-skald/src/main/java/com/gimle/skald/SkaldMain.java`, `gimle-skald/src/main/java/com/gimle/skald/SkaldServer.java`, `gimle-skald/src/main/java/com/gimle/skald/directory/CachingServiceDirectory.java`, `gimle-skald/src/main/java/com/gimle/skald/directory/ControlPlaneServicePoller.java`, `gimle-skald/src/main/java/com/gimle/skald/directory/HttpServiceCatalogClient.java`, `gimle-skald/src/main/java/com/gimle/skald/dns/DnsCodec.java`, `gimle-skald/src/main/java/com/gimle/skald/dns/ServiceDnsNames.java`
- **Test coverage**: `SkaldServerTest` (answers_a_tenant_scoped_hit_with_one_a_record, answers_an_untenanted_hit_and_round_robins_across_endpoints, answers_unknown_name_with_nxdomain, answers_unsupported_query_type_with_notimp, answers_unsupported_opcode_with_notimp, drops_a_malformed_datagram_instead_of_replying); `CachingServiceDirectoryTest`; `ControlPlaneServicePollerTest`; `DnsCodecTest`; `ServiceDnsNamesTest`
- **Gherkin scenario**:
  ```gherkin
  Given SkaldServer's directory cache holds "orders.acme.svc.gimle.local" -> [10.0.0.5], When a standard A query for that name arrives over UDP, Then the response carries exactly that one A record.
  Given the directory holds no entry for a queried name inside the svc.gimle.local zone, When queried, Then the response is NXDOMAIN.
  Given a query for a name outside svc.gimle.local, or a non-A query type, or a non-QUERY opcode, When received, Then the response is NOTIMP/NXDOMAIN as appropriate rather than silence.
  ```
