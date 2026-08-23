# Gimlé Requirements Traceability Matrix (RTM)

- **Baseline requirements document**: `REQUIREMENTS_MATRIX.md`, generated at commit `919bd4063ab8ef5a430e1e3ed8bf2f12ddacd640`
- **Current HEAD scanned**: `4134b92c10afb9075a446f1a5dfaf4ea1681dcd5` (`4134b92`), branch `claude/kubernetes-kinds-gimle-gap-o5499m`
- **Rebase acknowledgment**: this branch was rebased onto `origin/master` for this scan. Between the baseline commit and `origin/master`'s current tip there is exactly **one** new commit -- `6bd450f` ("test: add Norn deterministic Raft fault-injection simulation"), which adds three new test-only files to `gimle-mimir` and modifies nothing else (`git diff 919bd40..origin/master --stat` confirms 3 files changed, 807 insertions, 0 deletions, 0 files touched elsewhere). Consequently **every one of the 564 baseline requirements is unchanged (`Active`)** -- none are `Modified` or `Removed` -- and exactly **one new requirement** (`GIMLE-565`) was discovered and added.
- **Scan date**: 2026-08-23

## Coverage rule (strict, as specified)

A requirement is **Covered** only if a Cucumber `.feature` file + step definitions in `gimle-holmgang` exist that exercise that exact capability against a real, running cluster — matched on scenario *intent* (the Given/When/Then steps actually driving that behavior), not on naming similarity. Unit tests, integration tests, and JUnit `*IT` classes in `gimle-smoke-tests`, and every other module's own test suite — **including Holmgang's own plain-JUnit `*IT` classes** (`HaTopologyIT`, `MinimalTopologyIT`, the `Utgard*IT` suite, `SurtrIT`) which are real-cluster tests but not Cucumber scenarios — do **not** count toward Coverage here. Where such tests exist they are listed under **Other test coverage (non-Holmgang)**, informationally, and do not change the Coverage column.

**Holmgang's entire Cucumber surface at current HEAD**: 12 `.feature` files, 21 scenarios total (catalogued in full below). One of those 12 files — `raft-resilience.feature` — exists at the baseline commit but was never catalogued as its own entry in `REQUIREMENTS_MATRIX.md`'s original scan; it is used here as a coverage source for the requirements it actually exercises, without retroactively editing the baseline.

## Summary Table

| ID | Feature | Status | Coverage | Holmgang scenario reference |
|---|---|---|---|---|
| GIMLE-001 | Semantic module versioning | Active | Not Covered | — |
| GIMLE-002 | Version range constraint matching | Active | Covered | `module-system.feature` — "A dependent resolves only when a version inside its declared range is present" |
| GIMLE-003 | Module descriptor validation (request ≤ limit invariant) | Active | Covered | `module-system.feature` — "A manifest that fails its own validation never gets placed" |
| GIMLE-004 | Tiered isolation model (TIER_1/TIER_2/TIER_3) | Active | Covered | `module-system.feature` — "A dependent resolves only when a version inside its declared range is present"; `self-healing.feature` — "A killed worker JVM is respawned and the deployment returns to ACTIVE" |
| GIMLE-005 | Kubernetes-shaped resource quantity parsing | Active | Not Covered | — |
| GIMLE-006 | Tenant-scoped service export | Active | Not Covered | — |
| GIMLE-007 | StatefulSet-shaped persistent volume declaration | Active | Not Covered | — |
| GIMLE-008 | Health probe configuration with initial delay | Active | Covered | `module-system.feature` — "A liveness probe's initial delay is honored before the first tick" |
| GIMLE-009 | Vessel hosting mode (plain-process workload) | Active | Not Covered | — |
| GIMLE-010 | Artifact-registry vs local-path reference resolution | Active | Not Covered | — |
| GIMLE-011 | RBAC domain model (resources, verbs, permissions, roles, bindings) | Active | Covered | `console-security.feature` — "A role scoped to one tenant grants write access to that tenant alone" |
| GIMLE-012 | Built-in cluster-admin role and operator/node certificate groups | Active | Covered | `console-security.feature` — "A console login round-trips the right password and rejects the wrong one"; `console-security.feature` — "A role scoped to one tenant grants write access to that tenant alone"; `mtls.feature` — "A tenant write is recorded in the durable audit trail" |
| GIMLE-013 | Console password hashing (PBKDF2-HMAC-SHA256) | Active | Covered | `console-security.feature` — "A console login round-trips the right password and rejects the wrong one" |
| GIMLE-014 | Mutual-TLS SSLContext construction | Active | Covered | `mtls.feature` — "The cluster functions end to end over mutual TLS" |
| GIMLE-015 | Cluster-wide transport protocol switch (plaintext/TLS) | Active | Covered | `module-system.feature` — "A dependent resolves only when a version inside its declared range is present"; `mtls.feature` — "A tenant write is recorded in the durable audit trail" |
| GIMLE-016 | Stateless HMAC-signed console session tokens | Active | Covered | `console-security.feature` — "A console login round-trips the right password and rejects the wrong one" |
| GIMLE-017 | Session-signing key file load-or-create with owner-only permissions | Active | Not Covered | — |
| GIMLE-018 | Per-key exponential-backoff login throttle | Active | Covered | `console-security.feature` — "Repeated failed logins are throttled with a Retry-After backoff" |
| GIMLE-019 | Structured JSON log encoding with APPLICATION/PLATFORM categorization | Active | Not Covered | — |
| GIMLE-020 | Human-readable colored console log encoding | Active | Not Covered | — |
| GIMLE-021 | Runtime-switchable console log format (text default, JSON opt-in) | Active | Not Covered | — |
| GIMLE-022 | MDC-tagged proxying for same-worker and probe-loop invocations | Active | Not Covered | — |
| GIMLE-023 | Per-instance sifted log files | Active | Not Covered | — |
| GIMLE-024 | Platform (non-instance) log file appender | Active | Not Covered | — |
| GIMLE-025 | Kubelet-style size/count log rotation | Active | Not Covered | — |
| GIMLE-026 | Cursor-based log paging and live-follow streaming | Active | Not Covered | — |
| GIMLE-027 | Startup banner rendering with terminal color/Unicode auto-detection | Active | Not Covered | — |
| GIMLE-028 | Single-write length-prefixed wire framing | Active | Not Covered | — |
| GIMLE-029 | Hand-rolled JSON parser/writer | Active | Not Covered | — |
| GIMLE-030 | Agent↔worker control-channel protocol and codec | Active | Not Covered | — |
| GIMLE-031 | Node registration/heartbeat/capacity-reporting protocol | Active | Not Covered | — |
| GIMLE-032 | Instance lifecycle event log model | Active | Not Covered | — |
| GIMLE-033 | Cross-resource audit trail model | Active | Covered | `mtls.feature` — "A tenant write is recorded in the durable audit trail" |
| GIMLE-034 | Certificate bootstrap (CSR) request/response protocol | Active | Covered | `secrets-and-pki.feature` — "A node-join CSR that self-declares a privileged group is stamped with the node group instead"; `secrets-and-pki.feature` — "A CSR whose signature does not match its own declared key is rejected" |
| GIMLE-035 | Assigned-instance work-order model (incl. in-place rename and vessel dispatch) | Active | Not Covered | — |
| GIMLE-036 | Bounded-retry-with-backoff restart policy (CrashLoopBackOff-equivalent) | Active | Covered | `self-healing.feature` — "A module that never passes liveness is escalated to FAILED for good" |
| GIMLE-037 | Tenant identity and resource quota model | Active | Not Covered | — |
| GIMLE-038 | Tenant-scoped config/secret entry model | Active | Covered | `secrets-and-pki.feature` — "A secret's versions round-trip and a soft delete behaves differently from a hard one"; `secrets-and-pki.feature` — "A legacy pre-key-id secret ciphertext still decrypts correctly" |
| GIMLE-039 | Bundled SPA static-asset resolution from classpath | Active | Not Covered | — |
| GIMLE-040 | SPA static file serving with client-side-route fallback | Active | Not Covered | — |
| GIMLE-041 | Saga test-run event model and NDJSON codec | Active | Not Covered | — |
| GIMLE-042 | Stable failure-signature hashing for flaky-test clustering | Active | Not Covered | — |
| GIMLE-043 | Module dependency resolution with cycle detection | Active | Covered | `module-system.feature` — "A dependent resolves only when a version inside its declared range is present" |
| GIMLE-044 | Module registry (install bookkeeping, idempotent re-install, content-mismatch rejection) | Active | Not Covered | — |
| GIMLE-045 | Module lifecycle state machine (INSTALLED→RESOLVED→STARTING→ACTIVE→STOPPING→UNINSTALLED, plus FAILED/COMPLETED) | Active | Covered | `module-system.feature` — "A hook that always throws on start never reaches ACTIVE" |
| GIMLE-046 | Dynamic per-module-version JPMS ModuleLayer construction | Active | Not Covered | — |
| GIMLE-047 | Unnamed-module readability grant for bundled hooks/probes | Active | Not Covered | — |
| GIMLE-048 | Classloader leak detection via PhantomReference | Active | Not Covered | — |
| GIMLE-049 | Repeated-redeploy flat-metaspace acceptance test | Active | Not Covered | — |
| GIMLE-050 | Best-effort leak retaining-path attribution via JFR OldObjectSample | Active | Not Covered | — |
| GIMLE-051 | Module lifecycle hooks (reflectively instantiated, JPMS-exported) | Active | Not Covered | — |
| GIMLE-052 | Job-kind run-to-completion hooks | Active | Not Covered | — |
| GIMLE-053 | Module context API (in-flight tracking, service lookup, config, data dir, control-plane relay) | Active | Covered | `module-system.feature` — "The service registry cuts a same-worker caller over to a newer ready version"; `module-system.feature` — "Stopping a module with a perpetually in-flight request still completes"; `deployment-lifecycle.feature` — "A tenant-scoped module deploys, reads its secret, and is cleanly removed" |
| GIMLE-054 | In-worker round-robin service registry with version-aware cutover | Active | Covered | `module-system.feature` — "The service registry cuts a same-worker caller over to a newer ready version" |
| GIMLE-055 | Cross-tier name-driven service invocation | Active | Covered | `module-system.feature` — "A dependent resolves only when a version inside its declared range is present"; `module-system.feature` — "The service registry cuts a same-worker caller over to a newer ready version" |
| GIMLE-056 | Same-worker cross-module service publish/discover | Active | Covered | `module-system.feature` — "A dependent resolves only when a version inside its declared range is present"; `module-system.feature` — "The service registry cuts a same-worker caller over to a newer ready version" |
| GIMLE-057 | Graceful drain-then-dispose stop with deadline | Active | Covered | `module-system.feature` — "Stopping a module with a perpetually in-flight request still completes" |
| GIMLE-058 | Hot redeploy (old/new version coexistence with pinned dependent wiring) | Active | Not Covered | — |
| GIMLE-059 | gimle-module.yaml descriptor parsing and validation | Active | Covered | `module-system.feature` — "A manifest that fails its own validation never gets placed" |
| GIMLE-060 | Module artifact reading — real-JPMS-module and descriptor-presence validation | Active | Not Covered | — |
| GIMLE-061 | Andvari artifact-registry pull-through cache | Active | Covered | `registry-deploy.feature` — "A pushed module deploys by coordinate with no artifact path" |
| GIMLE-062 | Multi-endpoint Andvari failover on pull | Active | Not Covered | — |
| GIMLE-063 | Health probe interfaces (liveness/readiness) | Active | Covered | `module-system.feature` — "A liveness probe's initial delay is honored before the first tick"; `self-healing.feature` — "A module that never passes liveness is escalated to FAILED for good" |
| GIMLE-064 | Pluggable resource-limiter abstraction | Active | Not Covered | — |
| GIMLE-065 | Portable JVM-flags resource enforcement (Tier 1/Tier 2) | Active | Not Covered | — |
| GIMLE-066 | Tier 3 (namespace isolation) — deliberately unsupported by the current limiter | Active | Not Covered | — |
| GIMLE-067 | Kernel-level (cgroup v2) resource enforcement — deferred | Active | Not Covered | — |
| GIMLE-068 | Pluggable persistent-volume-manager abstraction | Active | Not Covered | — |
| GIMLE-069 | Local-disk persistent volume allocation for StatefulSet-shaped instances | Active | Not Covered | — |
| GIMLE-070 | Self-signed cluster CA generation | Active | Covered | `secrets-and-pki.feature` — "A node-join CSR that self-declares a privileged group is stamped with the node group instead"; `secrets-and-pki.feature` — "A node rotates its own certificate over mTLS and keeps its identity" |
| GIMLE-071 | CSR-to-leaf-certificate signing with signature verification | Active | Covered | `secrets-and-pki.feature` — "A node-join CSR that self-declares a privileged group is stamped with the node group instead"; `secrets-and-pki.feature` — "A CSR whose signature does not match its own declared key is rejected"; `secrets-and-pki.feature` — "A node rotates its own certificate over mTLS and keeps its identity" |
| GIMLE-072 | Server-stamped Subject override on signing (prevents self-declared privileged group) | Active | Covered | `secrets-and-pki.feature` — "A node-join CSR that self-declares a privileged group is stamped with the node group instead" |
| GIMLE-073 | CSR generation with Subject Alternative Names | Active | Covered | `secrets-and-pki.feature` — "A node-join CSR that self-declares a privileged group is stamped with the node group instead" |
| GIMLE-074 | Hand-rolled PEM encode/decode for certs, CSRs, and private keys | Active | Not Covered | — |
| GIMLE-075 | Randomized certificate-renewal scheduling (anti-thundering-herd) | Active | Not Covered | — |
| GIMLE-076 | Own-certificate rotation over mTLS via CSR bootstrap endpoint | Active | Covered | `secrets-and-pki.feature` — "A node rotates its own certificate over mTLS and keeps its identity" |
| GIMLE-077 | X.500 Subject utilities: server-side O= stamping and Principal derivation | Active | Covered | `secrets-and-pki.feature` — "A node-join CSR that self-declares a privileged group is stamped with the node group instead"; `secrets-and-pki.feature` — "Fafnir independently authorizes node-scoped secret reads by tenant assignment" |
| GIMLE-078 | Cluster PKI bootstrap CLI (`mvn gimle:tls-init`) | Active | Not Covered | — |
| GIMLE-079 | Worker JVM control-channel bootstrap | Active | Not Covered | — |
| GIMLE-080 | Newline-delimited control-channel wire protocol (worker side) | Active | Not Covered | — |
| GIMLE-081 | Module install/resolve/start/stop/uninstall command dispatch | Active | Not Covered | — |
| GIMLE-082 | Instance identity registration and rename-in-place | Active | Not Covered | — |
| GIMLE-083 | Per-instance MDC log tagging for lifecycle/hook/probe/request-dispatch logging | Active | Not Covered | — |
| GIMLE-084 | Durable InstanceEvent emission per lifecycle transition | Active | Not Covered | — |
| GIMLE-085 | Classloader leak detection on undeploy | Active | Not Covered | — |
| GIMLE-086 | Per-module bounded virtual-thread scheduler | Active | Not Covered | — |
| GIMLE-087 | OpenTelemetry context propagation across virtual-thread dispatch | Active | Not Covered | — |
| GIMLE-088 | Liveness/readiness probe loop with timeout and initial-delay | Active | Not Covered | — |
| GIMLE-089 | Module-tier self-healing — restart on repeated liveness failure with backoff and budget exhaustion | Active | Covered | `self-healing.feature` — "A module that never passes liveness is escalated to FAILED for good" |
| GIMLE-090 | Readiness-driven service registry availability (without restart) | Active | Not Covered | — |
| GIMLE-091 | Stopping/Uninstalled teardown of scheduler, probes, and service registry | Active | Not Covered | — |
| GIMLE-092 | Job-kind module execution (run-to-completion, not probed) | Active | Not Covered | — |
| GIMLE-093 | Fabric service registration, cross-worker/cross-machine invocation binding | Active | Covered | `deployment-lifecycle.feature` — "A consumer completes a real fabric call to a provider" |
| GIMLE-094 | Fabric TLS certificate rotation detection (mtime polling) | Active | Not Covered | — |
| GIMLE-095 | Control-plane read relay for hosted modules (RelayControlPlaneRead/Result round trip) | Active | Not Covered | — |
| GIMLE-096 | Worker-side trace relay to agent (no direct Muninn shipping) | Active | Not Covered | — |
| GIMLE-097 | Per-module CPU/memory/request-rate/error-rate metrics reporting (portable, no cgroup) | Active | Not Covered | — |
| GIMLE-098 | Worker-wide meter snapshot relay to Muninn (via agent) | Active | Not Covered | — |
| GIMLE-099 | `module-info.java` platform-layer/observability/fabric wiring for the worker module | Active | Not Covered | — |
| GIMLE-100 | Real bundled-hook/probe classloading against the platform layer | Active | Not Covered | — |
| GIMLE-101 | Node agent registration and repeating reconcile/heartbeat/rotate tick loop | Active | Not Covered | — |
| GIMLE-102 | Worker JVM process spawn and command-line construction | Active | Not Covered | — |
| GIMLE-103 | Worker process crash detection, classification, and destroy-and-respawn | Active | Covered | `self-healing.feature` — "A killed worker JVM is respawned and the deployment returns to ACTIVE" |
| GIMLE-104 | Deliberate-stop suppression of crash-respawn | Active | Not Covered | — |
| GIMLE-105 | Worker stdout draining, JSON-line de-duplication, and raw SYSTEM-line capture | Active | Not Covered | — |
| GIMLE-106 | Machine-level capacity tracking and admission (memory/CPU) | Active | Not Covered | — |
| GIMLE-107 | Portable JVM-flags resource limiting (Tier 1/2), cgroup enforcement deliberately deferred | Active | Not Covered | — |
| GIMLE-108 | Tier 3 isolation rejection | Active | Not Covered | — |
| GIMLE-109 | Assignment reconciliation loop (fetch, start, replace, stop) | Active | Not Covered | — |
| GIMLE-110 | Tier 1 density — shared-worker reuse for multiple module instances | Active | Not Covered | — |
| GIMLE-111 | Instance rename-in-place (no restart) | Active | Not Covered | — |
| GIMLE-112 | Worker respawn handshake re-drive after crash | Active | Covered | `self-healing.feature` — "A killed worker JVM is respawned and the deployment returns to ACTIVE" |
| GIMLE-113 | Worker-crash-to-durable-InstanceEvent relay | Active | Not Covered | — |
| GIMLE-114 | Install-phase Nack escalates to FAILED (closing the "stuck at INSTALLED" gap) | Active | Not Covered | — |
| GIMLE-115 | Artifact-registry coordinate resolution via ArtifactPullCache | Active | Covered | `registry-deploy.feature` — "A pushed module deploys by coordinate with no artifact path" |
| GIMLE-116 | Instance-scoped log/config/secret delivery over the control channel | Active | Covered | `deployment-lifecycle.feature` — "A tenant-scoped module deploys, reads its secret, and is cleanly removed" |
| GIMLE-117 | Persistent volume allocation for StatefulSet-shaped instances | Active | Not Covered | — |
| GIMLE-118 | Vessel process supervision (plain-jar workload as its own dedicated process) | Active | Not Covered | — |
| GIMLE-119 | Vessel port allocation (dynamic/fixed) and env resolution (literal/port/secret) | Active | Not Covered | — |
| GIMLE-120 | Vessel config-file rendering to disk | Active | Not Covered | — |
| GIMLE-121 | Vessel health probing (process-alive + TCP/HTTP rungs, initial-delay aware) | Active | Not Covered | — |
| GIMLE-122 | Vessel crash respawn resets probe initial-delay clock | Active | Not Covered | — |
| GIMLE-123 | mTLS bootstrap CSR flow for node identity | Active | Covered | `mtls.feature` — "The cluster functions end to end over mutual TLS" |
| GIMLE-124 | Periodic certificate rotation check and hot-swap of outbound HttpClient | Active | Not Covered | — |
| GIMLE-125 | SWIM gossip membership integration with service catalog relay | Active | Not Covered | — |
| GIMLE-126 | Gossip membership read-only HTTP surface | Active | Not Covered | — |
| GIMLE-127 | Node/instance log-serving HTTP surface with tailing and follow | Active | Not Covered | — |
| GIMLE-128 | Merged node-level SYSTEM log view | Active | Not Covered | — |
| GIMLE-129 | `hs_err_pid*.log` crash-dump listing and fetch | Active | Not Covered | — |
| GIMLE-130 | Node-agent log/metrics shipping to Muninn (own + supervised) | Active | Not Covered | — |
| GIMLE-131 | Whitelisted control-plane read relay (worker→agent→control plane) with independent re-validation | Active | Not Covered | — |
| GIMLE-132 | Node capacity/instance-observation heartbeat reporting | Active | Not Covered | — |
| GIMLE-133 | Instance-event forwarding (worker-reported and agent-originated) to control plane | Active | Not Covered | — |
| GIMLE-134 | Node placement-label registration | Active | Not Covered | — |
| GIMLE-135 | `module-info.java` wiring for the node agent module | Active | Not Covered | — |
| GIMLE-136 | Raft Leader Election | Active | Covered | `raft-resilience.feature` — "The store leader dies mid-workload and nothing acknowledged is lost" |
| GIMLE-137 | Log Replication (AppendEntries) | Active | Covered | `raft-resilience.feature` — "A store member dies mid-workload and nothing acknowledged is lost"; `raft-resilience.feature` — "The store leader dies mid-workload and nothing acknowledged is lost" |
| GIMLE-138 | Election Safety Restriction (log up-to-date check) | Active | Covered | `raft-resilience.feature` — "A stale, partitioned follower cannot win an election despite outracing the cluster's term" |
| GIMLE-139 | Conflicting-Entry Truncation | Active | Not Covered | — |
| GIMLE-140 | Leader-Only-Commits-Own-Term Rule (Figure 8) | Active | Not Covered | — |
| GIMLE-141 | Strict Apply Ordering (commitIndex vs lastApplied) | Active | Covered | `raft-resilience.feature` — "A store member dies mid-workload and nothing acknowledged is lost" |
| GIMLE-142 | Proposal Timeout with Ghost-Write Prevention | Active | Covered | `partition-tolerance.feature` — "A leader's write proposed while partitioned is truncated and never resurfaces" |
| GIMLE-143 | Chunked InstallSnapshot Transfer (Figure 13) | Active | Covered | `raft-resilience.feature` — "A learner catches up through a compacted leader's snapshot and only helps quorum once promoted" |
| GIMLE-144 | Local Log Compaction / Snapshotting | Active | Covered | `raft-resilience.feature` — "A learner catches up through a compacted leader's snapshot and only helps quorum once promoted" |
| GIMLE-145 | Check-Quorum Leader Self-Demotion | Active | Covered | `partition-tolerance.feature` — "A store leader silently partitioned from its peers steps down and writes stay bounded" |
| GIMLE-146 | Etcd-Style Live Membership Change (AddServer/RemoveServer) | Active | Covered | `membership-change.feature` — "A fourth store joins and then leaves, one server at a time" |
| GIMLE-147 | Non-Voting Learner & Automatic Promotion | Active | Covered | `raft-resilience.feature` — "A learner catches up through a compacted leader's snapshot and only helps quorum once promoted" |
| GIMLE-148 | Durable Raft Log Persistence | Active | Covered | `raft-resilience.feature` — "A store member dies mid-workload and nothing acknowledged is lost"; `raft-resilience.feature` — "The store leader dies mid-workload and nothing acknowledged is lost" |
| GIMLE-149 | Raft Transport over Mutual TLS with Hot Cert Reload | Active | Covered | `mtls.feature` — "The cluster functions end to end over mutual TLS"; `mtls.feature` — "The audit trail records and filters real authorization decisions over mutual TLS" |
| GIMLE-150 | Raft RPC Wire Codec | Active | Not Covered | — |
| GIMLE-151 | Atomic Durable File Writes | Active | Not Covered | — |
| GIMLE-152 | File-Backed State Store Persistence Engine | Active | Covered | `state-store-persistence.feature` — "Tenants, roles, role bindings, and accounts survive a store restart, snapshot included" |
| GIMLE-153 | Full-State Snapshot / Restore | Active | Covered | `state-store-persistence.feature` — "Tenants, roles, role bindings, and accounts survive a store restart, snapshot included" |
| GIMLE-154 | Replicated Mutation Catalog (StateMutation) | Active | Not Covered | — |
| GIMLE-155 | Leader-Local Node Heartbeat Tracking | Active | Covered | `state-store-mechanics.feature` — "Node heartbeats update continuously for a live node" |
| GIMLE-156 | Distributed Lease Coordination (Grant/Renew/Release) | Active | Covered | `state-store-mechanics.feature` — "A lease is exclusive to its holder until it expires" |
| GIMLE-157 | Per-Instance Lifecycle Event Log with Retention Cap | Active | Covered | `state-store-mechanics.feature` — "An instance's event log is capped and returns newest first" |
| GIMLE-158 | Cluster-Wide Audit Trail with Filtering | Active | Covered | `mtls.feature` — "The audit trail records and filters real authorization decisions over mutual TLS" |
| GIMLE-159 | Deployment Rolling-Update & Surge Bookkeeping | Active | Covered | `rolling-update.feature` — "Zero-downtime rollout under a surge budget" |
| GIMLE-160 | StatefulSet OrderedReady Index & Sticky Node Binding | Active | Covered | `state-store-mechanics.feature` — "A StatefulSet ordinal index stays bound to the node it first lands on" |
| GIMLE-161 | Node Cordon (Scheduler Exclusion Flag) | Active | Covered | `scheduling.feature` — "A cordoned node blocks placement until uncordoned" |
| GIMLE-162 | Tenant Quota-Violation Flag Tracking | Active | Covered | `quota-and-admission.feature` — "A retroactive quota violation is flagged but never evicts" |
| GIMLE-163 | RBAC Data Persistence (Roles, RoleBindings, Accounts) | Active | Covered | `state-store-persistence.feature` — "Tenants, roles, role bindings, and accounts survive a store restart, snapshot included" |
| GIMLE-164 | Client-Facing Store RPC with Leader Redirect & Follow | Active | Covered | `deployment-lifecycle.feature` — "State written through one control-plane replica serves through another" |
| GIMLE-165 | Store Read Load Balancing Across Replicas | Active | Covered | `deployment-lifecycle.feature` — "State written through one control-plane replica serves through another" |
| GIMLE-166 | Store Node Leader-Only Write Gating | Active | Not Covered | — |
| GIMLE-167 | Store Client Connection Timeout Bounds | Active | Not Covered | — |
| GIMLE-168 | Store RPC Wire Codec | Active | Not Covered | — |
| GIMLE-169 | RBAC Authorization Engine | Modified | Not Covered | — |
| GIMLE-170 | Node-Tenant Assignment Check | Active | Not Covered | — |
| GIMLE-171 | Five-Field Cron Schedule Evaluator | Active | Covered | `workload-manifests.feature` — "A CronJob schedule fires on the day-of-month/day-of-week OR quirk" |
| GIMLE-172 | Deployment Manifest Parsing (incl. Autoscale & Disruption Budget) | Active | Covered | `workload-manifests.feature` — "A weighted autoscale policy is accepted, an unreplaceable disruption budget is rejected" |
| GIMLE-173 | DaemonSet Manifest Parsing (Anti-Affinity/Surge Rejection) | Active | Covered | `workload-manifests.feature` — "A DaemonSet rejects anti-affinity and nonzero surge but accepts zero surge" |
| GIMLE-174 | Job / CronJob Manifest Parsing | Active | Covered | `workload-manifests.feature` — "A CronJob applies sensible defaults and rejects an invalid schedule or concurrency policy" |
| GIMLE-175 | StatefulSet Manifest Parsing | Active | Covered | `workload-manifests.feature` — "A StatefulSet accepts zero replicas and rejects a negative count" |
| GIMLE-176 | Kind-Dispatching Manifest Parser | Active | Covered | `workload-manifests.feature` — "An unrecognized manifest kind is rejected via the dispatching parser" |
| GIMLE-177 | Shared Domain Binary Codec | Active | Not Covered | — |
| GIMLE-178 | Store Process Bootstrap with TLS Rotation Ticker | Active | Not Covered | — |
| GIMLE-179 | Store/Raft Metrics Instrumentation | Active | Not Covered | — |
| GIMLE-180 | module-info JPMS Boundary for gimle-mimir | Active | Not Covered | — |
| GIMLE-565 | Norn deterministic virtual-time Raft fault-injection simulation | New | Not Covered | — |
| GIMLE-181 | Same-Worker Direct Invocation Tier | Active | Not Covered | — |
| GIMLE-182 | Same-Machine Unix-Domain-Socket Invocation Tier | Active | Covered | `deployment-lifecycle.feature` — "A consumer completes a real fabric call to a provider" |
| GIMLE-183 | Cross-Machine TCP Invocation Tier | Active | Not Covered | — |
| GIMLE-184 | Locality-Aware Load Balancing with Spillover | Active | Not Covered | — |
| GIMLE-185 | Least-Outstanding-Requests Selection | Active | Not Covered | — |
| GIMLE-186 | Per-Endpoint Circuit Breaker | Active | Not Covered | — |
| GIMLE-187 | Circuit Breaker Exponential Cooldown Backoff | Active | Not Covered | — |
| GIMLE-188 | Panic-Mode Ejection Floor | Active | Not Covered | — |
| GIMLE-189 | Application-Exception vs Transport-Failure Breaker Scoring | Active | Not Covered | — |
| GIMLE-190 | Gossip-Propagated Service Catalog | Active | Not Covered | — |
| GIMLE-191 | Catalog Eviction on Gossip-Detected Node Death | Active | Not Covered | — |
| GIMLE-192 | Cross-Tenant Service Export Access Control | Active | Not Covered | — |
| GIMLE-193 | Runtime Name-Driven Cross-Tier Invocation (invokeByName) | Active | Not Covered | — |
| GIMLE-194 | Inbound Call Dispatch with Bounded Concurrency | Active | Not Covered | — |
| GIMLE-195 | Distributed Trace Propagation Across Fabric Hops | Active | Not Covered | — |
| GIMLE-196 | Fabric Transport over Mutual TLS with Hot Cert Reload | Active | Not Covered | — |
| GIMLE-197 | Fabric Call Timeout Enforcement | Active | Not Covered | — |
| GIMLE-198 | Fabric Frame Wire Codec | Active | Not Covered | — |
| GIMLE-199 | Cross-JVM Object Marshalling | Active | Not Covered | — |
| GIMLE-200 | SWIM Gossip Membership Protocol (Ping/PingReq/Ack) | Active | Not Covered | — |
| GIMLE-201 | SWIM Self-Refutation via Incarnation Bump | Active | Not Covered | — |
| GIMLE-202 | Lifeguard-Style Local Health Multiplier | Active | Not Covered | — |
| GIMLE-203 | Round-Robin Bounded-Coverage Probe Target Selection | Active | Not Covered | — |
| GIMLE-204 | Anti-Entropy Full-State Sync | Active | Not Covered | — |
| GIMLE-205 | Dead-Member Reaping | Active | Not Covered | — |
| GIMLE-206 | Gossip over Mutual DTLS with Deterministic Initiator Selection | Active | Not Covered | — |
| GIMLE-207 | SWIM Wire Codec | Active | Not Covered | — |
| GIMLE-208 | Service Catalog Delta Wire Codec | Active | Not Covered | — |
| GIMLE-209 | Reflective Cross-Module Method Dispatch | Active | Not Covered | — |
| GIMLE-210 | module-info JPMS Boundary for gimle-fabric | Active | Not Covered | — |
| GIMLE-211 | First-fit-decreasing bin-packing scheduler | Active | Not Covered | — |
| GIMLE-212 | Isolation-tier placement filtering | Active | Not Covered | — |
| GIMLE-213 | Node cordon exclusion | Active | Covered | `scheduling.feature` — "A cordoned node blocks placement until uncordoned" |
| GIMLE-214 | Strict anti-affinity across nodes | Active | Not Covered | — |
| GIMLE-215 | Tier 2/3 node-level tenant isolation | Active | Not Covered | — |
| GIMLE-216 | Required node-label placement constraint | Active | Not Covered | — |
| GIMLE-217 | StatefulSet sticky node placement | Active | Not Covered | — |
| GIMLE-218 | DaemonSet eligible-node enumeration (`eligibleNodes`) | Active | Not Covered | — |
| GIMLE-219 | Deployment replica reconciliation (level-triggered) | Active | Covered | `deployment-lifecycle.feature` — "A tenant-scoped module deploys, reads its secret, and is cleanly removed"; `partition-tolerance.feature` — "A control plane cut off from the store stops serving and reconverges after heal" |
| GIMLE-220 | Deployment scale-down | Active | Not Covered | — |
| GIMLE-221 | Artifact-hash drift detection at reconcile time | Active | Not Covered | — |
| GIMLE-222 | Rolling update via mismatched-index migration | Active | Covered | `rolling-update.feature` — "Zero-downtime rollout under a surge budget" |
| GIMLE-223 | Rolling update surge (maxSurge) | Active | Covered | `rolling-update.feature` — "Zero-downtime rollout under a surge budget" |
| GIMLE-224 | Node-death instance reclamation (`ReplicaCountReconciler`) | Active | Not Covered | — |
| GIMLE-225 | Persisted grace-period bookkeeping (survives leader failover) | Active | Not Covered | — |
| GIMLE-226 | Unhealthy-instance backoff-gated reschedule (`HealthReconciler`) | Active | Not Covered | — |
| GIMLE-227 | Readiness-only failures never trigger reschedule | Active | Not Covered | — |
| GIMLE-228 | Tenant quota drift detection (`QuotaReconciler`) | Active | Covered | `quota-and-admission.feature` — "A retroactive quota violation is flagged but never evicts" |
| GIMLE-229 | Horizontal autoscaling — multi-signal (`AutoscaleReconciler`) | Active | Covered | `autoscale.feature` — "Request-rate load scales the provider up" |
| GIMLE-230 | Autoscaling WEIGHTED combination mode | Active | Not Covered | — |
| GIMLE-231 | DaemonSet reconciliation and rolling update | Active | Not Covered | — |
| GIMLE-232 | DaemonSet dark-node placement-safety grace period | Active | Not Covered | — |
| GIMLE-233 | StatefulSet OrderedReady placement | Active | Not Covered | — |
| GIMLE-234 | StatefulSet one-index-at-a-time scale-down | Active | Not Covered | — |
| GIMLE-235 | JobRun run-to-completion reconciliation | Active | Not Covered | — |
| GIMLE-236 | Job active-deadline enforcement | Active | Not Covered | — |
| GIMLE-237 | CronJob schedule-driven Job materialization | Active | Not Covered | — |
| GIMLE-238 | CronJob concurrency policy (Allow/Forbid/Replace) | Active | Not Covered | — |
| GIMLE-239 | CronJob manual trigger (`gimle cronjob trigger`) | Active | Not Covered | — |
| GIMLE-240 | CronJob missed-schedule starting-deadline handling | Active | Not Covered | — |
| GIMLE-241 | Level-triggered orphan cleanup across every workload kind | Active | Covered | `deployment-lifecycle.feature` — "A tenant-scoped module deploys, reads its secret, and is cleanly removed" |
| GIMLE-242 | Reconciler-leader election via non-replicated lease | Active | Not Covered | — |
| GIMLE-243 | Independent-executor ticking (lease/reconcile/cert-rotation isolation) | Active | Not Covered | — |
| GIMLE-244 | JPMS module boundary for gimle-controlplane | Active | Not Covered | — |
| GIMLE-245 | Admission chain extension point | Active | Not Covered | — |
| GIMLE-246 | Tenant resource quota admission check | Active | Covered | `quota-and-admission.feature` — "An over-quota deployment is rejected at admission" |
| GIMLE-247 | Organization-specific policy-as-data admission (`policy.maxReplicasPerDeployment`) | Active | Not Covered | — |
| GIMLE-248 | Registry-coordinate artifact admission (Andvari integration) | Active | Covered | `registry-deploy.feature` — "A pushed module deploys by coordinate with no artifact path" |
| GIMLE-249 | PUT-time re-tenanting double-authorization | Active | Not Covered | — |
| GIMLE-250 | RBAC-gated resource CRUD across every workload kind | Active | Not Covered | — |
| GIMLE-251 | WRITE/DELETE decisions durably audited (opt-in READ auditing) | Active | Not Covered | — |
| GIMLE-252 | `gimle-system` reserved-tenant operator-only guard | Active | Not Covered | — |
| GIMLE-253 | Node-scoped self-service authorization (`gimle:nodes` group) | Active | Not Covered | — |
| GIMLE-254 | Node-tenant-scoped `/endpoints/*` read access | Active | Not Covered | — |
| GIMLE-255 | mTLS-authenticated HTTP API server with client-cert principal resolution | Active | Covered | `mtls.feature` — "The cluster functions end to end over mutual TLS"; `mtls.feature` — "An anonymous client cannot write" |
| GIMLE-256 | Console session login/logout/session cookie flow | Active | Not Covered | — |
| GIMLE-257 | Login throttling (address + username keyed) | Active | Not Covered | — |
| GIMLE-258 | Bootstrap node join via single-use token + CSR | Active | Not Covered | — |
| GIMLE-259 | Operator-approval-gated CSR flow | Active | Not Covered | — |
| GIMLE-260 | Certificate rotation (self-rotation and subject-preserving renewal) | Active | Not Covered | — |
| GIMLE-261 | Zero-downtime TLS material reload | Active | Not Covered | — |
| GIMLE-262 | `/secrets/*` byte-for-byte proxy to Fafnir | Active | Not Covered | — |
| GIMLE-263 | Secrets key rotation trigger (proxied) | Active | Covered | `secrets-and-pki.feature` — "Rotating the secrets key re-encrypts an existing secret under the new key id" |
| GIMLE-264 | CONFIG/SECRET resource-kind separation on one underlying store | Active | Not Covered | — |
| GIMLE-265 | `/artifacts/*` streaming proxy to Andvari | Active | Not Covered | — |
| GIMLE-266 | Andvari-client multi-endpoint failover with rotation | Active | Not Covered | — |
| GIMLE-267 | `/logs/*` proxy with Muninn fallback | Active | Not Covered | — |
| GIMLE-268 | `/metrics-history/*` and `/traces-history/*` Muninn proxy | Active | Not Covered | — |
| GIMLE-269 | Node registration, heartbeat, and assignment-fetch API | Active | Covered | `module-system.feature` — "A hook that always throws on start never reaches ACTIVE" |
| GIMLE-270 | Unified `AssignedInstance` wire shape across every workload kind | Active | Not Covered | — |
| GIMLE-271 | Reserved system-tenant auto-seeding | Active | Not Covered | — |
| GIMLE-272 | Bundled web console static serving | Active | Not Covered | — |
| GIMLE-273 | Per-endpoint request metrics instrumentation | Active | Not Covered | — |
| GIMLE-274 | Deployment/Job/DaemonSet/StatefulSet CRUD manifest API | Active | Covered | `deployment-lifecycle.feature` — "A tenant-scoped module deploys, reads its secret, and is cleanly removed" |
| GIMLE-275 | Per-deployment and per-instance metrics rollup | Active | Not Covered | — |
| GIMLE-276 | AES-256-GCM secret value encryption with versioned key IDs | Active | Covered | `secrets-and-pki.feature` — "A secret's versions round-trip and a soft delete behaves differently from a hard one"; `secrets-and-pki.feature` — "Rotating the secrets key re-encrypts an existing secret under the new key id" |
| GIMLE-277 | Legacy pre-key-id ciphertext format fallback | Active | Covered | `secrets-and-pki.feature` — "A legacy pre-key-id secret ciphertext still decrypts correctly" |
| GIMLE-278 | Local AES-256 key-file generation and loading | Active | Covered | `secrets-and-pki.feature` — "A secret's versions round-trip and a soft delete behaves differently from a hard one"; `secrets-and-pki.feature` — "A legacy pre-key-id secret ciphertext still decrypts correctly" |
| GIMLE-279 | Key rotation with full-ring persistence (`KeyFileManager.rotate`) | Active | Covered | `secrets-and-pki.feature` — "Rotating the secrets key re-encrypts an existing secret under the new key id" |
| GIMLE-280 | Key-ring fingerprinting for cross-replica drift detection | Active | Not Covered | — |
| GIMLE-281 | Full-key-rotation re-encryption sweep | Active | Covered | `secrets-and-pki.feature` — "Rotating the secrets key re-encrypts an existing secret under the new key id" |
| GIMLE-282 | Versioned secret storage layered over ConfigEntry | Active | Covered | `secrets-and-pki.feature` — "A secret's versions round-trip and a soft delete behaves differently from a hard one" |
| GIMLE-283 | Optimistic-write versioned put with narrow-lease serialization | Active | Not Covered | — |
| GIMLE-284 | Soft delete vs hard delete (`?destroy=true`) | Active | Covered | `secrets-and-pki.feature` — "A secret's versions round-trip and a soft delete behaves differently from a hard one" |
| GIMLE-285 | Fafnir's own independent RBAC re-check (defense-in-depth) | Active | Covered | `secrets-and-pki.feature` — "Fafnir independently authorizes node-scoped secret reads by tenant assignment" |
| GIMLE-286 | Node-tenant-scoped secret reads (`gimle:nodes`) | Active | Covered | `secrets-and-pki.feature` — "Fafnir independently authorizes node-scoped secret reads by tenant assignment" |
| GIMLE-287 | Authorization-failure throttling and dual audit logging | Active | Not Covered | — |
| GIMLE-288 | Three-tier principal resolution (forwarded header > peer cert > session cookie) | Active | Not Covered | — |
| GIMLE-289 | mTLS HTTP server with dynamic TLS material reload | Active | Not Covered | — |
| GIMLE-290 | Console session login (Fafnir's own operator dashboard) | Active | Covered | `secrets-and-pki.feature` — "Fafnir's console session login round-trips and the plaintext session falls back to anonymous" |
| GIMLE-291 | Plaintext-mode anonymous session carve-out | Active | Covered | `secrets-and-pki.feature` — "Fafnir's console session login round-trips and the plaintext session falls back to anonymous" |
| GIMLE-292 | Bundled web console static serving (Fafnir) | Active | Not Covered | — |
| GIMLE-293 | Process status endpoint with key-ring fingerprint | Active | Not Covered | — |
| GIMLE-294 | Muninn metrics/traces shipping | Active | Not Covered | — |
| GIMLE-295 | Fafnir-metrics observability instrumentation | Active | Not Covered | — |
| GIMLE-296 | JPMS module boundary for gimle-fafnir | Active | Not Covered | — |
| GIMLE-297 | Immutable, content-addressed artifact store | Active | Not Covered | — |
| GIMLE-298 | Streamed, digest-verified push with atomic commit | Active | Not Covered | — |
| GIMLE-299 | Size-limited streaming upload rejection | Active | Not Covered | — |
| GIMLE-300 | On-disk corruption detection and quarantine | Active | Not Covered | — |
| GIMLE-301 | Periodic full-store integrity scrub | Active | Not Covered | — |
| GIMLE-302 | Version retention sweeping (count and age based) | Active | Not Covered | — |
| GIMLE-303 | Multi-replica peer synchronization (no consensus) | Active | Covered | `observability-registry-ha.feature` — "Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces" |
| GIMLE-304 | Peer-sync conflict detection (irreconcilable divergence) | Active | Not Covered | — |
| GIMLE-305 | Push/pull/list/delete `/artifacts/*` operational HTTP surface | Active | Covered | `observability-registry-ha.feature` — "Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces"; `registry-deploy.feature` — "A pushed module deploys by coordinate with no artifact path" |
| GIMLE-306 | Maven-2-shaped `/repository/**` interop surface | Active | Not Covered | — |
| GIMLE-307 | Server-computed checksum sidecars (never trusting client uploads) | Active | Not Covered | — |
| GIMLE-308 | Generated `maven-metadata.xml` (never stored, always fresh) | Active | Not Covered | — |
| GIMLE-309 | Maven GAV coordinate translation | Active | Not Covered | — |
| GIMLE-310 | Defense-in-depth authorization (independent re-check, `ResourceKind.ARTIFACT`) | Active | Not Covered | — |
| GIMLE-311 | Module-scoped permission grants | Active | Not Covered | — |
| GIMLE-312 | Node pull-only artifact access, scoped to active assignments | Active | Not Covered | — |
| GIMLE-313 | Dual audit logging for push/delete decisions | Active | Not Covered | — |
| GIMLE-314 | Andvari's own console session story (`/auth/*`, bundled SPA) | Active | Not Covered | — |
| GIMLE-315 | mTLS server with dynamic TLS reload | Active | Not Covered | — |
| GIMLE-316 | Plaintext-mode loud supply-chain warning | Active | Not Covered | — |
| GIMLE-317 | Andvari observability instrumentation and Muninn shipping | Active | Not Covered | — |
| GIMLE-318 | Process status endpoint (no RBAC gate) | Active | Not Covered | — |
| GIMLE-319 | Node platform-log ingest | Active | Not Covered | — |
| GIMLE-320 | Instance-log ingest | Active | Not Covered | — |
| GIMLE-321 | Node/instance log read with cursor paging | Active | Not Covered | — |
| GIMLE-322 | `follow=true` rejection on Muninn reads | Active | Not Covered | — |
| GIMLE-323 | Metrics ingest | Active | Not Covered | — |
| GIMLE-324 | Metrics read | Active | Not Covered | — |
| GIMLE-325 | Traces ingest | Active | Not Covered | — |
| GIMLE-326 | Traces read | Active | Not Covered | — |
| GIMLE-327 | Day-bucketed JSON-lines store with oldest-first cursor semantics | Active | Not Covered | — |
| GIMLE-328 | All-or-nothing batch validation on ingest | Active | Not Covered | — |
| GIMLE-329 | Windows-safe on-disk path sanitization for colon-bearing processId | Active | Not Covered | — |
| GIMLE-330 | Path-segment validation / directory-traversal defense | Active | Not Covered | — |
| GIMLE-331 | Age-based retention sweep | Active | Not Covered | — |
| GIMLE-332 | Plaintext-default transport with loud unauthenticated-mode warning | Active | Not Covered | — |
| GIMLE-333 | mTLS transport mode | Active | Not Covered | — |
| GIMLE-334 | Zero-downtime TLS material reload on certificate rotation | Active | Not Covered | — |
| GIMLE-335 | Node-identity check on node-log ingest | Active | Not Covered | — |
| GIMLE-336 | Instance-owner check on instance-log ingest | Active | Not Covered | — |
| GIMLE-337 | Verified-certificate-presence check on metrics/traces ingest | Active | Not Covered | — |
| GIMLE-338 | Read surface has no RBAC/authorization re-check (documented-vs-actual gap) | Active | Not Covered | — |
| GIMLE-339 | `/status` operational endpoint | Active | Not Covered | — |
| GIMLE-340 | Default OpenTelemetry tracer installation | Active | Not Covered | — |
| GIMLE-341 | Configurable, batched span exporter installation | Active | Not Covered | — |
| GIMLE-342 | Bounded-wait tracer flush | Active | Not Covered | — |
| GIMLE-343 | Periodic log-file shipping to Muninn | Active | Not Covered | — |
| GIMLE-344 | Periodic Micrometer metrics shipping | Active | Covered | `observability-registry-ha.feature` — "Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces" |
| GIMLE-345 | One-shot trace-batch and prepared-batch shipping | Active | Not Covered | — |
| GIMLE-346 | Multi-endpoint best-effort fan-out shipping | Active | Not Covered | — |
| GIMLE-347 | In-memory (non-persisted) log-shipping cursor | Active | Not Covered | — |
| GIMLE-348 | Micrometer meter → NDJSON codec | Active | Not Covered | — |
| GIMLE-349 | OpenTelemetry span → NDJSON codec | Active | Not Covered | — |
| GIMLE-350 | `MuninnSpanExporter` (OpenTelemetry SDK integration) | Active | Not Covered | — |
| GIMLE-351 | JFR-based per-module CPU/allocation attribution | Active | Not Covered | — |
| GIMLE-352 | Per-process tagged Micrometer metrics wrappers | Active | Not Covered | — |
| GIMLE-353 | WorkerMetrics thread-count / metaspace gauges | Active | Not Covered | — |
| GIMLE-354 | Fafnir authz-failure counter (rate-limiting signal) | Active | Not Covered | — |
| GIMLE-355 | Muninn endpoint list parsing from config | Active | Not Covered | — |
| GIMLE-356 | Fabric-route HTTP-to-service dispatch | Active | Not Covered | — |
| GIMLE-357 | Fabric-route argument coercion (`ParamType`) | Active | Not Covered | — |
| GIMLE-358 | Vessel-route HTTP reverse-proxy dispatch | Active | Not Covered | — |
| GIMLE-359 | Vessel-endpoint resolution with TTL cache | Active | Not Covered | — |
| GIMLE-360 | Round-robin load balancing over ready vessel endpoints | Active | Not Covered | — |
| GIMLE-361 | Stale-cache fallback on endpoint-refresh failure | Active | Not Covered | — |
| GIMLE-362 | Vessel-route error surfacing (no ready endpoint / connect failure) | Active | Not Covered | — |
| GIMLE-363 | Route-table config DSL parsing | Active | Not Covered | — |
| GIMLE-364 | Duplicate route-path rejection at config-parse time | Active | Not Covered | — |
| GIMLE-365 | Gateway HTTP server bootstrap via module lifecycle hooks | Active | Not Covered | — |
| GIMLE-366 | Gateway liveness and readiness probes | Active | Not Covered | — |
| GIMLE-367 | HTTP status-code error mapping across the dispatcher | Active | Not Covered | — |
| GIMLE-368 | Boot-only platform-layer JPMS workaround (`requires static`) | Active | Not Covered | — |
| GIMLE-369 | Vessel proxy: no TLS, no header forwarding (v1 scope limitation) | Active | Not Covered | — |
| GIMLE-370 | Fabric route "quiet success" ambiguity for a misrouted service name | Active | Not Covered | — |
| GIMLE-371 | Deployment resource management (get/apply/delete) | Active | Not Covered | — |
| GIMLE-372 | Job resource management (get/apply/delete) | Active | Not Covered | — |
| GIMLE-373 | CronJob management incl. manual trigger | Active | Not Covered | — |
| GIMLE-374 | DaemonSet resource management | Active | Not Covered | — |
| GIMLE-375 | StatefulSet resource management | Active | Not Covered | — |
| GIMLE-376 | Node inventory and cordon/uncordon | Active | Not Covered | — |
| GIMLE-377 | Instance lifecycle event timeline | Modified | Not Covered | — |
| GIMLE-378 | Tenant management and quota configuration | Active | Not Covered | — |
| GIMLE-379 | Tenant plain configuration key/value store | Active | Not Covered | — |
| GIMLE-380 | Versioned secrets management (Fafnir proxy) | Active | Not Covered | — |
| GIMLE-381 | Artifact registry client (push/list/get/delete) | Active | Not Covered | — |
| GIMLE-382 | Log viewing and live tailing | Active | Not Covered | — |
| GIMLE-383 | Audit trail query | Active | Not Covered | — |
| GIMLE-384 | RBAC role management | Active | Not Covered | — |
| GIMLE-385 | RBAC role binding management | Active | Not Covered | — |
| GIMLE-386 | Operator account management | Active | Not Covered | — |
| GIMLE-387 | Certificate lifecycle management (bootstrap token, CSR request/status/approve, renewal) | Active | Not Covered | — |
| GIMLE-388 | Dual table/JSON output formatting | Active | Not Covered | — |
| GIMLE-389 | kubectl-shaped global flag parsing, manifest-kind apply dispatch, and mTLS/leader-aware HTTP client | Active | Not Covered | — |
| GIMLE-390 | Topology validation (`hilmir validate`) | Active | Not Covered | — |
| GIMLE-391 | Cluster launch planning (`hilmir plan`) | Active | Not Covered | — |
| GIMLE-392 | Real multi-process cluster bring-up (`hilmir up`) | Active | Not Covered | — |
| GIMLE-393 | Cluster teardown and status reporting (`hilmir down`/`status`) | Active | Not Covered | — |
| GIMLE-394 | Cluster TLS/PKI bootstrap (`hilmir pki init`) | Active | Not Covered | — |
| GIMLE-395 | Raft store membership add (`hilmir store add`) | Active | Not Covered | — |
| GIMLE-396 | Raft store membership remove (`hilmir store remove`) | Active | Not Covered | — |
| GIMLE-397 | Per-machine platform binary rolling upgrade with quorum-safe store restart (`hilmir upgrade-cluster`) | Active | Not Covered | — |
| GIMLE-398 | Bundle-based fresh release deployment (`hilmir deploy`) | Active | Not Covered | — |
| GIMLE-399 | Bundle upgrade with automatic resource pruning (`hilmir upgrade`) | Active | Not Covered | — |
| GIMLE-400 | Release rollback to a prior revision (`hilmir rollback`) | Active | Not Covered | — |
| GIMLE-401 | Full release teardown (`hilmir undeploy`) | Active | Not Covered | — |
| GIMLE-402 | Release listing (`hilmir releases`) | Active | Not Covered | — |
| GIMLE-403 | Release status inspection (`hilmir release-status`) | Active | Not Covered | — |
| GIMLE-404 | GitOps directory reconciliation (`hilmir sync`, incl. `--watch` and `--prune`) | Active | Not Covered | — |
| GIMLE-405 | `--watch` interval loop for sync | Active | Not Covered | — |
| GIMLE-406 | Bundle value templating and override precedence (`${values.*}` substitution) | Active | Not Covered | — |
| GIMLE-407 | Bundle manifest schema parsing and validation | Active | Not Covered | — |
| GIMLE-408 | Workload readiness polling for `--wait` | Active | Not Covered | — |
| GIMLE-409 | Doctor static deployability diagnostics (`hilmir doctor`) | Active | Not Covered | — |
| GIMLE-410 | Doctor cluster-aware checks (`--server`, `--tenant`) | Active | Not Covered | — |
| GIMLE-411 | Manifest scaffolding (`hilmir init`) | Active | Not Covered | — |
| GIMLE-412 | Gateway extension enable (`hilmir enable gateway`) | Active | Not Covered | — |
| GIMLE-413 | Gateway extension disable (`hilmir disable gateway`) | Active | Not Covered | — |
| GIMLE-414 | Bundled JRE resolution for platform-binary launches | Active | Not Covered | — |
| GIMLE-415 | `java @argfile` command-line rewriting | Active | Not Covered | — |
| GIMLE-416 | Run ledger persistence for `up`/`down`/`status`/`upgrade-cluster` | Active | Not Covered | — |
| GIMLE-417 | TCP-connect readiness polling | Active | Not Covered | — |
| GIMLE-418 | `mvn gimle:agent` — spawn a real node agent (plus its worker command tail) | Active | Not Covered | — |
| GIMLE-419 | `mvn gimle:bootstrap` — full local-dev cluster orchestration in one foreground command | Active | Not Covered | — |
| GIMLE-420 | Process-launcher Maven goals for individual platform processes (`controlplane`/`store`/`fafnir`/`muninn`/`andvari`/`tls-init`) | Active | Not Covered | — |
| GIMLE-421 | `mvn gimle:deploy` — apply a deployment manifest via a real CLI subprocess | Active | Not Covered | — |
| GIMLE-422 | `mvn gimle:doctor` — run hilmir doctor against the invoking project's own built jar | Active | Not Covered | — |
| GIMLE-423 | `mvn gimle:init` — scaffold manifests for the invoking project's own built jar | Active | Not Covered | — |
| GIMLE-424 | `mvn gimle:publish` — push a built module jar to the artifact registry | Active | Not Covered | — |
| GIMLE-425 | `mvn gimle:docs` — full documentation site build pipeline | Active | Not Covered | — |
| GIMLE-426 | `mvn gimle:flaky-tests` — run known-flaky-tagged tests in isolated standalone reactors | Active | Not Covered | — |
| GIMLE-427 | `mvn gimle:saga` — ensure a Saga test-report server is running | Active | Not Covered | — |
| GIMLE-428 | `mvn gimle:verify` — full build run under Saga tracking | Active | Not Covered | — |
| GIMLE-429 | `mvn gimle:saga-import` — standalone sweep-and-import of existing surefire reports | Active | Not Covered | — |
| GIMLE-430 | `mvn gimle:saga-stop` — best-effort local Saga server shutdown | Active | Not Covered | — |
| GIMLE-431 | Internal — Aether-based cross-module runtime classpath resolution | Active | Not Covered | — |
| GIMLE-432 | Internal — host-matching java/mvn executable resolution and subprocess supervision | Active | Not Covered | — |
| GIMLE-433 | Internal — git commit/branch capture for run identification | Active | Not Covered | — |
| GIMLE-434 | Internal — surefire report discovery and totals aggregation, including flaky-testcase counting | Active | Not Covered | — |
| GIMLE-435 | Operator session login / logout | Active | Not Covered | — |
| GIMLE-436 | Session bootstrap & 401 handling | Active | Not Covered | — |
| GIMLE-437 | Cluster Overview dashboard | Active | Not Covered | — |
| GIMLE-438 | Tactical HUD / Signal display-mode toggle | Active | Not Covered | — |
| GIMLE-439 | Deployments list/create/detail/delete | Active | Not Covered | — |
| GIMLE-440 | Jobs (run-to-completion workload) list | Active | Not Covered | — |
| GIMLE-441 | CronJobs list/detail | Active | Not Covered | — |
| GIMLE-442 | DaemonSets list/detail | Active | Not Covered | — |
| GIMLE-443 | StatefulSets list/detail | Active | Not Covered | — |
| GIMLE-444 | Instances table with filtering (global + node/tenant-scoped) | Active | Not Covered | — |
| GIMLE-445 | Nodes list/detail with capacity bars and staleness | Active | Not Covered | — |
| GIMLE-446 | Tenants list/detail with quota management and delete | Active | Not Covered | — |
| GIMLE-447 | Topology placement map | Active | Not Covered | — |
| GIMLE-448 | Cluster metrics charts (lifecycle mix, capacity, quota pressure) | Active | Not Covered | — |
| GIMLE-449 | Per-process metrics history (Muninn-backed) | Active | Not Covered | — |
| GIMLE-450 | Trace span history viewer | Active | Not Covered | — |
| GIMLE-451 | Log explorer with live tailing | Active | Not Covered | — |
| GIMLE-452 | Crash-dump (hs_err) listing on Logs screen | Active | Not Covered | — |
| GIMLE-453 | Config entries management (per-tenant) | Active | Not Covered | — |
| GIMLE-454 | Secrets management (Fafnir-backed, versioned) | Active | Not Covered | — |
| GIMLE-455 | Module artifact registry browser (Andvari-backed) | Active | Not Covered | — |
| GIMLE-456 | RBAC access control (roles, role bindings, accounts) | Active | Not Covered | — |
| GIMLE-457 | Audit trail viewer with filtering | Active | Not Covered | — |
| GIMLE-458 | Control-plane status panel | Active | Not Covered | — |
| GIMLE-459 | Theme toggle (light/dark) | Active | Not Covered | — |
| GIMLE-460 | Playwright end-to-end smoke suite against a real cluster | Active | Not Covered | — |
| GIMLE-461 | Vault operator login/logout (session-cookie auth) | Active | Not Covered | — |
| GIMLE-462 | Vault status overview (uptime, active key, transport mode, tenants) | Active | Not Covered | — |
| GIMLE-463 | Secrets browsing/reveal/version/write/destroy (vault-native UI) | Active | Not Covered | — |
| GIMLE-464 | Tenant filter via URL search param | Active | Not Covered | — |
| GIMLE-465 | Key rotation trigger | Active | Not Covered | — |
| GIMLE-466 | Fafnir console error banner / global error capture | Active | Not Covered | — |
| GIMLE-467 | Andvari operator login/logout (session-cookie auth) | Active | Not Covered | — |
| GIMLE-468 | Registry status overview (uptime, transport, recent pushes) | Active | Not Covered | — |
| GIMLE-469 | Artifact catalog browsing & search | Active | Not Covered | — |
| GIMLE-470 | Artifact version detail (download, checksum display, delete) | Active | Not Covered | — |
| GIMLE-471 | Client-side SHA-256 checksum verification on download | Active | Not Covered | — |
| GIMLE-472 | Push artifact dialog (drag-and-drop upload) | Active | Not Covered | — |
| GIMLE-473 | Maven-2 repository interop view | Active | Not Covered | — |
| GIMLE-474 | Andvari copy-to-clipboard utility | Active | Not Covered | — |
| GIMLE-475 | Runs list (no authentication) | Active | Not Covered | — |
| GIMLE-476 | Live run detail with streaming test feed | Active | Not Covered | — |
| GIMLE-477 | Run attachments: Gherkin scenario tree, Chaos ledger, Surtr phase table | Active | Not Covered | — |
| GIMLE-478 | Test detail / per-test history | Active | Not Covered | — |
| GIMLE-479 | Compare two runs (diff view) | Active | Not Covered | — |
| GIMLE-480 | Gjallarhorn flake scoreboard | Active | Not Covered | — |
| GIMLE-481 | Saga console theming (no auth surface) | Active | Not Covered | — |
| GIMLE-482 | NDJSON event ingest API | Active | Not Covered | — |
| GIMLE-483 | Idempotent per-run ingest / re-ingest replacement | Active | Not Covered | — |
| GIMLE-484 | Crash-safe append (torn-tail recovery) | Active | Not Covered | — |
| GIMLE-485 | Surefire/Failsafe XML import | Active | Not Covered | — |
| GIMLE-486 | Fold-import safety net for a live run's gap | Active | Not Covered | — |
| GIMLE-487 | Run listing, detail, and cursor-paginated event reads | Active | Not Covered | — |
| GIMLE-488 | Live NDJSON tail (`follow=true`) of a run's event stream | Active | Not Covered | — |
| GIMLE-489 | Abandoned-run detection on restart | Active | Not Covered | — |
| GIMLE-490 | Flake ledger derivation (fail-then-pass rule) and rebuild | Active | Not Covered | — |
| GIMLE-491 | Flaky scoreboard with time-window ranking | Active | Not Covered | — |
| GIMLE-492 | Test-tag index and quarantine status | Active | Not Covered | — |
| GIMLE-493 | Per-test history endpoint | Active | Not Covered | — |
| GIMLE-494 | Path traversal protection on run IDs | Active | Not Covered | — |
| GIMLE-495 | Bundled console static serving | Active | Not Covered | — |
| GIMLE-496 | Poll-until-condition primitive (`Await`) | Active | Not Covered | — |
| GIMLE-497 | Kernel-assigned loopback port leasing (`PortLease`) | Active | Not Covered | — |
| GIMLE-498 | Heimdall event-driven cluster condition harness | Active | Not Covered | — |
| GIMLE-499 | Replica-scoped condition observation | Active | Not Covered | — |
| GIMLE-500 | Deployment/node/log condition builders | Active | Not Covered | — |
| GIMLE-501 | Time-windowed negative invariants (`Invariant`/`InvariantGuard`) | Active | Not Covered | — |
| GIMLE-502 | Forensic failure reporting | Active | Not Covered | — |
| GIMLE-503 | `hello-module` — minimal inert deployable fixture | Active | Not Covered | — |
| GIMLE-504 | `greeter-provider` — real fabric service export with lifecycle hooks and health probes | Active | Covered | `deployment-lifecycle.feature` — "A consumer completes a real fabric call to a provider" |
| GIMLE-505 | `greeter-consumer` — real cross-worker fabric call with MDC-tagged background caller | Active | Covered | `deployment-lifecycle.feature` — "A consumer completes a real fabric call to a provider" |
| GIMLE-506 | `greeter-load-generator` — HTTP bridge for external load tools driving real fabric traffic | Active | Covered | `autoscale.feature` — "Request-rate load scales the provider up" |
| GIMLE-507 | Real multi-process cluster fixture (store/control-plane/agent/Fafnir/Muninn) | Active | Not Covered | — |
| GIMLE-508 | On-the-fly compiled module variants via `TestModuleBuilder` | Active | Not Covered | — |
| GIMLE-509 | Base cluster topology deploy across store cluster and multiple CP replicas | Active | Not Covered | — |
| GIMLE-510 | Raft store resilience (member loss, leader failover, live membership change) | Active | Not Covered | — |
| GIMLE-511 | Tiered self-healing (worker respawn, liveness-exhaustion escalation to FAILED) | Active | Not Covered | — |
| GIMLE-512 | Classloader leak detection wired into a real worker | Active | Not Covered | — |
| GIMLE-513 | Repeated redeploy stability without false-positive leaks | Active | Not Covered | — |
| GIMLE-514 | Tier 1 worker density packing and its cap | Active | Not Covered | — |
| GIMLE-515 | Node cordoning blocks new placement without evicting running instances | Active | Not Covered | — |
| GIMLE-516 | DaemonSet per-node fan-out and dead-node assignment cleanup | Active | Not Covered | — |
| GIMLE-517 | Job and CronJob real-cluster lifecycle | Active | Not Covered | — |
| GIMLE-518 | StatefulSet sticky placement and volume persistence across worker restart | Active | Not Covered | — |
| GIMLE-519 | Rolling update preserves serving capacity and reaches new version | Active | Not Covered | — |
| GIMLE-520 | Surge worker promotion carries out via in-place retarget, not respawn | Active | Not Covered | — |
| GIMLE-521 | Autoscaling under real request-rate, error-rate, queue-depth, and weighted-blended load | Active | Not Covered | — |
| GIMLE-522 | Multi-tenant quota enforcement (flag-not-evict, and admission rejection) | Active | Not Covered | — |
| GIMLE-523 | Circuit breaker excludes a consistently-failing replica | Active | Not Covered | — |
| GIMLE-524 | Gossip/SWIM failure detection across real separate agent processes | Active | Not Covered | — |
| GIMLE-525 | Observability data survives agent death (Muninn fallback) and control-plane metrics round-trip | Active | Not Covered | — |
| GIMLE-526 | Worker-tier metrics/trace relay to Muninn via the agent | Active | Not Covered | — |
| GIMLE-527 | Artifact registry (Andvari) resolution path end to end | Active | Not Covered | — |
| GIMLE-528 | External HTTP request reaches a fabric service through the gateway | Active | Not Covered | — |
| GIMLE-529 | Declarative cluster topology DSL/YAML parsing and validation | Active | Not Covered | — |
| GIMLE-530 | Real subprocess cluster orchestration (`GimleCluster`) | Active | Not Covered | — |
| GIMLE-531 | Cluster pooling per topology with destructive-scenario isolation | Active | Not Covered | — |
| GIMLE-532 | JUnit `@Holmgang`/`@HolmgangCluster` extension for plain-JUnit cluster tests | Active | Not Covered | — |
| GIMLE-533 | Fenrir randomized chaos-fault soak executor | Active | Covered | `chaos-soak.feature` — "The cluster survives a randomized fault soak with no lost writes"; `observability-registry-ha.feature` — "Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces" |
| GIMLE-534 | Chaos ledger recording and rendering | Active | Covered | `chaos-soak.feature` — "The cluster survives a randomized fault soak with no lost writes" |
| GIMLE-535 | Randomized fault soak with no lost writes (basic and compound-fault modes) | Active | Covered | `chaos-soak.feature` — "The cluster survives a randomized fault soak with no lost writes"; `chaos-soak.feature` — "The cluster survives a compound-fault soak with overlapping faults and no lost writes" |
| GIMLE-536 | Muninn/Andvari replica-bounce resilience soak | Active | Covered | `observability-registry-ha.feature` — "Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces" |
| GIMLE-537 | Live store membership change (AddServer/RemoveServer) | Active | Covered | `membership-change.feature` — "A fourth store joins and then leaves, one server at a time" |
| GIMLE-538 | Mutual TLS end-to-end operation and anonymous-client rejection | Active | Covered | `mtls.feature` — "The cluster functions end to end over mutual TLS"; `mtls.feature` — "An anonymous client cannot write" |
| GIMLE-539 | Control-plane partition tolerance (store-side) and reconvergence on heal | Active | Covered | `partition-tolerance.feature` — "A control plane cut off from the store stops serving and reconverges after heal" |
| GIMLE-540 | Store leader self-demotion under silent peer partition; bounded write latency | Active | Covered | `partition-tolerance.feature` — "A store leader silently partitioned from its peers steps down and writes stay bounded" |
| GIMLE-541 | Tenant deployment lifecycle with secret delivery and clean deletion | Active | Covered | `deployment-lifecycle.feature` — "A tenant-scoped module deploys, reads its secret, and is cleanly removed" |
| GIMLE-542 | Tenant quota retroactive violation (flag, not evict) and admission rejection | Active | Covered | `quota-and-admission.feature` — "A retroactive quota violation is flagged but never evicts"; `quota-and-admission.feature` — "An over-quota deployment is rejected at admission" |
| GIMLE-543 | Node cordoning blocks placement until uncordoned | Active | Covered | `scheduling.feature` — "A cordoned node blocks placement until uncordoned" |
| GIMLE-544 | Worker-tier self-healing and liveness-exhaustion escalation (Gherkin coverage) | Active | Covered | `self-healing.feature` — "A killed worker JVM is respawned and the deployment returns to ACTIVE"; `self-healing.feature` — "A module that never passes liveness is escalated to FAILED for good" |
| GIMLE-545 | Zero-downtime rolling update under surge budget (Gherkin coverage) | Active | Covered | `rolling-update.feature` — "Zero-downtime rollout under a surge budget" |
| GIMLE-546 | Request-rate autoscaling under real Gatling-driven fabric load (Gherkin coverage) | Active | Covered | `autoscale.feature` — "Request-rate load scales the provider up" |
| GIMLE-547 | Artifact registry coordinate-only deployment (Gherkin coverage) | Active | Covered | `registry-deploy.feature` — "A pushed module deploys by coordinate with no artifact path" |
| GIMLE-548 | Surtr scale/churn/performance workload runner | Active | Not Covered | — |
| GIMLE-549 | Surtr Muninn-window measurement (documented gap) | Active | Not Covered | — |
| GIMLE-550 | Module-density Tier 1 packing Surtr reference workload | Active | Not Covered | — |
| GIMLE-551 | Saga unified run reporting (Gherkin + JUnit + Fenrir + Surtr) | Active | Not Covered | — |
| GIMLE-552 | Saga best-effort shipping to a remote report server | Active | Not Covered | — |
| GIMLE-553 | Loki fault-injection proxy for store/control-plane link partitions | Active | Covered | `partition-tolerance.feature` — "A control plane cut off from the store stops serving and reconverges after heal"; `partition-tolerance.feature` — "A store leader silently partitioned from its peers steps down and writes stay bounded" |
| GIMLE-554 | Utgard multi-container distributed boot ordering | Active | Not Covered | — |
| GIMLE-555 | Utgard real machine loss (hard container kill) and rejoin | Active | Not Covered | — |
| GIMLE-556 | Utgard network partition (vs hard kill) with reconvergence | Active | Not Covered | — |
| GIMLE-557 | Utgard real-hostname mTLS bootstrap across containers | Active | Not Covered | — |
| GIMLE-558 | Utgard Docker container fleet management primitives | Active | Not Covered | — |
| GIMLE-559 | Docker Compose manual validation topologies (bundled-JRE and full-JRE) | Active | Not Covered | — |
| GIMLE-560 | Standalone CLI distribution archive | Active | Not Covered | — |
| GIMLE-561 | Standalone Hilmir bootstrap-tool distribution archive | Active | Not Covered | — |
| GIMLE-562 | Cluster-machine platform distribution archive | Active | Not Covered | — |
| GIMLE-563 | Opt-in bundled-JRE distribution variant (`dist-with-jre` profile) | Active | Not Covered | — |
| GIMLE-564 | Distribution archive checksums and SBOM generation | Active | Not Covered | — |
| GIMLE-566 | Service abstraction: stable name, CRUD API, and endpoint reconciliation | New | Covered | `service-fabric.feature` — "A Service resolves a live endpoint for a hosted module reporting its own port" |
| GIMLE-567 | Fabric listener-side tenant re-check on inbound service calls | New | Not Covered | — |
| GIMLE-568 | gimle-bifrost: per-node service proxy (kube-proxy analogue) | New | Not Covered | — |
| GIMLE-569 | gimle-skald: cluster DNS server resolving Service names to live endpoints | New | Not Covered | — |
| GIMLE-570 | Gateway virtual-host routing and Service-backed (SERVICE) route kind | New | Not Covered | — |
| GIMLE-571 | Hosted-module runtime port reporting folded into instance observation | New | Covered | `service-fabric.feature` — "A Service resolves a live endpoint for a hosted module reporting its own port" |
| GIMLE-572 | NetworkPolicySpec durable persistence through StoreClient | New | Covered | `network-policy.feature` — "A network policy created through one control-plane replica is visible through another" |
| GIMLE-573 | Doctor advisory-only outbound-connection hazard detection | New | Not Covered | — |
| GIMLE-574 | Per-deployment-scoped NetworkPolicySpec enforcement | New | Not Covered | — |
| GIMLE-575 | Bifrost fails closed for a NetworkPolicySpec-restricted Service | New | Not Covered | — |
| GIMLE-576 | Remote (SSH) fleet bootstrap (`hilmir up/down/status --remote`) | Modified | Not Covered | — |
| GIMLE-577 | Multi-jar publish with per-module tenant tagging (`kind: ArtifactSet`) | New | Not Covered | — |
| GIMLE-578 | Service CRUD and live endpoint lookup | New | Not Covered | — |
| GIMLE-579 | NetworkPolicy CRUD | New | Not Covered | — |
| GIMLE-580 | `hilmir upgrade-cluster --remote` (SSH-dispatched platform binary rollout) | New | Not Covered | — |
| GIMLE-581 | ConfigMap store and API with optimistic-concurrency writes | New | Not Covered | — |
| GIMLE-582 | Deployment `configMapRefs` field with admission-time collision rejection | New | Not Covered | — |
| GIMLE-583 | Narrowed config delivery to instances declaring `configMapRefs` | New | Not Covered | — |
| GIMLE-584 | `gimle configmap` command | New | Not Covered | — |
| GIMLE-585 | ConfigMaps screen | New | Not Covered | — |
| GIMLE-586 | Service CRUD and live endpoint lookup (Networking screen) | New | Not Covered | — |
| GIMLE-587 | NetworkPolicy CRUD (Networking screen) | New | Not Covered | — |
| GIMLE-588 | SecretMap store and `/secretmaps/*` API | New | Not Covered | — |
| GIMLE-589 | Deployment `secretMapRefs` field with admission-time collision rejection | New | Not Covered | — |
| GIMLE-590 | `/secretmaps/*` proxy and `ResourceKind.SECRETMAP` RBAC | New | Not Covered | — |
| GIMLE-591 | Narrowed secret delivery via `secretMapRefs` | New | Not Covered | — |
| GIMLE-592 | `gimle secretmap` command | New | Not Covered | — |
| GIMLE-593 | SecretMaps screen | New | Not Covered | — |
| GIMLE-594 | SecretMap group-version ledger and rollback | New | Not Covered | — |
| GIMLE-595 | `secretmap versions`/`secretmap rollback` verbs | New | Not Covered | — |
| GIMLE-596 | SecretMaps screen History panel | New | Not Covered | — |
| GIMLE-597 | Sealed SecretMap envelope crypto and key retirement | New | Not Covered | — |
| GIMLE-598 | `/seal/*` and key-retirement HTTP routes | New | Not Covered | — |
| GIMLE-599 | `/seal/*` and `/secrets/retire-key` proxy routes | New | Not Covered | — |
| GIMLE-600 | `gimle seal` command, `secret retire-key`, `secretmap seal` verbs | New | Not Covered | — |
| GIMLE-601 | ControllerRevision history and Deployment/StatefulSet/DaemonSet rollback | New | Not Covered | — |
| GIMLE-602 | `deployment`/`statefulset`/`daemonset` `revisions`/`rollback` verbs | New | Not Covered | — |
| GIMLE-603 | Sleipnir: agent-managed JDK AOT startup cache for worker JVMs | New | Covered | `aot-cache.feature` — "the agent logs ineligibility and the deployment still reaches ACTIVE normally" |

## Detailed Requirements

### gimle-core

#### GIMLE-001 — Semantic module versioning

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given "1.2.3-rc1" parsed via Version.parse, When compared against unqualified "1.2.3", Then the unqualified version compares as greater.
- **Other test coverage (non-Holmgang, informational only)**: `VersionTest` (parses_major_minor_patch, orders_by_major_then_minor_then_patch, unqualified_outranks_qualified, qualifiers_compare_lexicographically, rejects_negative_components)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/Version.java`

#### GIMLE-002 — Version range constraint matching

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A dependent resolves only when a version inside its declared range is present*
  - _Why this counts_: Deploys a dependency library, then wires a consumer whose declared version range includes it -- resolves and reaches ACTIVE; a second consumer whose declared range excludes the only deployed version is asserted to fail placement instead, proving the range check is genuinely evaluated at resolution time, not just recorded from the manifest.
- **Other test coverage (non-Holmgang, informational only)**: `VersionRangeTest` (inclusive/exclusive bounds, unbounded above, rejects lower>upper)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/VersionRange.java`

#### GIMLE-003 — Module descriptor validation (request ≤ limit invariant)

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A manifest that fails its own validation never gets placed*
  - _Why this counts_: Submits a deployment whose resource request exceeds its own declared limit; asserted to never reach a placed/ACTIVE instance, proving the request<=limit invariant is enforced before scheduling rather than merely documented.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleDescriptorTest` (accepts_request_within_limit, rejects_memory/cpu_request_exceeding_limit, rejects_blank_name, id_combines_name_and_version)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/ModuleDescriptor.java`

#### GIMLE-004 — Tiered isolation model (TIER_1/TIER_2/TIER_3)

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A dependent resolves only when a version inside its declared range is present*
  - _Why this counts_: The dependency and its consumer's cross-deployment `requires:` wiring can only resolve directly if both land on one shared Tier 1 worker JVM; their successful resolution and same-worker service lookup is the black-box proof of Tier 1 density, not just a configuration flag.
  - `gimle-holmgang/src/test/resources/features/self-healing.feature` — Scenario: *A killed worker JVM is respawned and the deployment returns to ACTIVE*
  - _Why this counts_: A Tier 2 module's dedicated worker JVM is killed directly; the deployment recovers via a fresh worker rather than an in-process module reload, proving Tier 2's independent-crash-domain guarantee -- pre-existing coverage, reused here rather than duplicated.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/IsolationTier.java`

#### GIMLE-005 — Kubernetes-shaped resource quantity parsing

- **Category**: Resource Limiting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given ResourceSpec{memory:"128Mi", cpu:"250m"}, When memoryBytes()/cpuMillicores() called, Then 134217728 and 250 respectively.
- **Other test coverage (non-Holmgang, informational only)**: Indirect via ModuleDescriptorTest, VesselSpecTest — NONE direct
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/ResourceSpec.java`, `ResourceQuantity.java`

#### GIMLE-006 — Tenant-scoped service export

- **Category**: Module System / Multi-tenancy
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given ServiceExport{allowedTenantIds={"tenant-a"}}, When permitsTenant(Optional.of("tenant-b")), Then false.
- **Other test coverage (non-Holmgang, informational only)**: `ServiceExportTenantTest` (unrestricted permits any, restricted permits only listed, never permits untenanted caller, empty allow list permits no one)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/ServiceExport.java`

#### GIMLE-007 — StatefulSet-shaped persistent volume declaration

- **Category**: Module System / Storage
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given gimle-module.yaml declaring volume:{sizeBytes,mountPath}, When parsed, Then ModuleDescriptor.volume() is present.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleDescriptorParserTest` (no_volume_leaves_it_empty, parses_volume_size_and_mount_path, volume_with_missing_mount_path_throws, volume_with_non_positive_size_bytes_throws)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/VolumeRequest.java`

#### GIMLE-008 — Health probe configuration with initial delay

- **Category**: Module System / Health
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A liveness probe's initial delay is honored before the first tick*
  - _Why this counts_: A module with a declared health-probe initial delay stays ACTIVE through that whole window and only fails once probing genuinely starts afterward -- if the delay weren't honored, the probe would trip immediately and the instance would never observably survive the delay window.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleDescriptorParserTest` (no_initial_delay leaves empty, parses seconds, negative/non-numeric throws)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/HealthProbes.java`

#### GIMLE-009 — Vessel hosting mode (plain-process workload)

- **Category**: Module System / Vessel Hosting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a vessel: block declaring args/env/probe/resources, When validated, Then synthesized into a ModuleDescriptor always at TIER_2, no exports/hooks/volume; a TCP/HTTP probe with no declared port is rejected.
- **Other test coverage (non-Holmgang, informational only)**: `VesselSpecTest` (no probes/ports is valid, TCP readiness requires a declared port, fixed port allocation carries its number, negative fixed port rejected); VesselArtifacts NONE dedicated
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/vessel/VesselSpec.java`, `VesselProbes.java`, `VesselProbeSpec.java`, `VesselEnvValue.java`, `VesselFileMount.java`, `VesselArtifacts.java`

#### GIMLE-010 — Artifact-registry vs local-path reference resolution

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Artifact-registry vs local-path reference resolution" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/module/ArtifactReference.java`

#### GIMLE-011 — RBAC domain model (resources, verbs, permissions, roles, bindings)

- **Category**: Security / RBAC
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/console-security.feature` — Scenario: *A role scoped to one tenant grants write access to that tenant alone*
  - _Why this counts_: A role/role-binding pair scoped to one tenant is created through the real RBAC API; the bound principal can write to that tenant but is rejected (403) writing to a different one, proving permissions/roles/bindings are genuinely evaluated together, not merely stored.
- **Other test coverage (non-Holmgang, informational only)**: `PermissionTest` (unscoped covers any tenant, scoped only own tenant, mismatch never covers), `RoleBindingTest` (well-formed subject accepted, malformed rejected)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/authz/Permission.java`, `Role.java`, `RoleBinding.java`, `ResourceKind.java`, `Verb.java`, `Principal.java`

#### GIMLE-012 — Built-in cluster-admin role and operator/node certificate groups

- **Category**: Security / RBAC
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/console-security.feature` — Scenario: *A console login round-trips the right password and rejects the wrong one*
  - _Why this counts_: Every scenario in this track runs as the operator mTLS identity performing writes across multiple resource kinds without any role ever having been provisioned for it -- only the built-in cluster-admin role, implicitly bound to the operator certificate group, makes that possible.
  - `gimle-holmgang/src/test/resources/features/console-security.feature` — Scenario: *A role scoped to one tenant grants write access to that tenant alone*
  - _Why this counts_: The tenant-scoped role is layered on top of, and asserted not to replace, the operator's own implicit cluster-admin grant used to provision it in the first place.
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *A tenant write is recorded in the durable audit trail*
  - _Why this counts_: The node agent's own CSR-bootstrapped registration in the same mTLS cluster exercises the built-in `gimle:nodes` certificate group the same way the operator scenarios exercise cluster-admin.
- **Other test coverage (non-Holmgang, informational only)**: `BuiltinRolesTest` (cluster_admin_covers_every_resource_and_verb_unscoped, group_names_match_what_the_pki_layer_stamps)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/authz/BuiltinRoles.java`

#### GIMLE-013 — Console password hashing (PBKDF2-HMAC-SHA256)

- **Category**: Security
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/console-security.feature` — Scenario: *A console login round-trips the right password and rejects the wrong one*
  - _Why this counts_: An account is created with a plaintext password via the real API (server-side PBKDF2-HMAC-SHA256 hashing, never stored in the clear); login with the correct password succeeds and the wrong password is rejected, proving the hash-and-verify path is genuinely exercised end to end.
- **Other test coverage (non-Holmgang, informational only)**: `PasswordHashesTest` (hash_then_verify_round_trips, verify_rejects_wrong_password, two_hashes_differ_due_to_random_salt, verify_rejects_truncated_hash)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/authz/PasswordHashes.java`, `Account.java`

#### GIMLE-014 — Mutual-TLS SSLContext construction

- **Category**: PKI / Internal-Infra
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *The cluster functions end to end over mutual TLS*
  - _Why this counts_: Boots the whole cluster under the 'mtls' topology (every hop mTLS, agent CSR bootstrap via token) and asserts a tenant secret still round-trips end to end.
- **Other test coverage (non-Holmgang, informational only)**: gimle-pki's `SslContextsIntegrationTest` (mutual_tls_handshake_succeeds_when_both_sides_trust_the_same_ca, handshake_is_rejected_when_the_client_trusts_a_different_ca_than_the_server)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/tls/SslContexts.java`

#### GIMLE-015 — Cluster-wide transport protocol switch (plaintext/TLS)

- **Category**: Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A dependent resolves only when a version inside its declared range is present*
  - _Why this counts_: Runs against topology "minimal" over plaintext transport -- the same deploy/lifecycle/service-registry machinery this track's whole module-system.feature suite exercises under the plaintext side of the transport switch.
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *A tenant write is recorded in the durable audit trail*
  - _Why this counts_: Runs against topology "mtls" over full mutual TLS -- the identical deployment/audit machinery proven functioning under the TLS side of the same transport switch.
- **Other test coverage (non-Holmgang, informational only)**: `TransportProtocolTest`, `TlsSettingsTest` (defaults to plaintext, case-insensitive, rejects unrecognized value, fails fast on unset property)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/tls/TransportProtocol.java`, `TlsSettings.java`

#### GIMLE-016 — Stateless HMAC-signed console session tokens

- **Category**: Security
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/console-security.feature` — Scenario: *A console login round-trips the right password and rejects the wrong one*
  - _Why this counts_: Login returns a session cookie whose validity is then checked via `/auth/session`; the whole login-to-cookie-to-session-lookup round trip only works if the cookie carries a genuine HMAC-signed, stateless session token the server can independently verify without server-side session storage.
- **Other test coverage (non-Holmgang, informational only)**: `SessionTokensTest` (issue_then_verify round trips, expired rejected, wrong-key rejected, tampered rejected, garbage input rejected not thrown)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/session/SessionTokens.java`

#### GIMLE-017 — Session-signing key file load-or-create with owner-only permissions

- **Category**: Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given no key file exists, When loadOrCreate called twice, Then first generates rw------- key, second reuses it.
- **Other test coverage (non-Holmgang, informational only)**: `SessionKeyFileManagerTest` (generates_on_first_run_reuses_on_later, rejects corrupted/empty key file)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/session/SessionKeyFileManager.java`

#### GIMLE-018 — Per-key exponential-backoff login throttle

- **Category**: Security
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/console-security.feature` — Scenario: *Repeated failed logins are throttled with a Retry-After backoff*
  - _Why this counts_: Five wrong-password attempts against the same account are asserted to eventually return 429 with a Retry-After header, proving the exponential-backoff throttle is genuinely applied per key, not just configured.
- **Other test coverage (non-Holmgang, informational only)**: `LoginThrottleTest` (delay doubles up to cap, success clears history, keys tracked independently)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/throttle/LoginThrottle.java`

#### GIMLE-019 — Structured JSON log encoding with APPLICATION/PLATFORM categorization

- **Category**: Observability / Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a log event with deploymentName/instanceIndex MDC keys set, When encoded by JsonLogEncoder, Then category="APPLICATION" carrying moduleId/deploymentName/instanceIndex; without those, category="PLATFORM".
- **Other test coverage (non-Holmgang, informational only)**: `JsonLogEncoderTest` (categorizes platform/application, tenant id included only when present, process role/node id read fresh)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/JsonLogEncoder.java`, `InstanceMdcKeys.java`

#### GIMLE-020 — Human-readable colored console log encoding

- **Category**: Observability / Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given -Dgimle.color=always, When encoded by TextLogEncoder, Then output contains ANSI escapes; -Dgimle.color=never produces none.
- **Other test coverage (non-Holmgang, informational only)**: `TextLogEncoderTest`, `AnsiPaletteTest` (override wins regardless of environment)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/TextLogEncoder.java`, `AnsiPalette.java`

#### GIMLE-021 — Runtime-switchable console log format (text default, JSON opt-in)

- **Category**: Observability / Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given -Dgimle.log.console=json, When CONSOLE appender starts, Then delegates to JsonLogEncoder; no override defaults to text.
- **Other test coverage (non-Holmgang, informational only)**: `ConsoleLogEncoderTest` (explicit json/text override, no override defaults to text)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/ConsoleLogEncoder.java`

#### GIMLE-022 — MDC-tagged proxying for same-worker and probe-loop invocations

- **Category**: Observability / Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a service reference wrapped via InstanceMdcContext.tagProxy, When a method throws, Then caller's MDC is restored to prior state, original exception propagates.
- **Other test coverage (non-Holmgang, informational only)**: `InstanceMdcContextTest` (tag_proxy sets/restores MDC, restores on throw, run_tagged restores previous value)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/InstanceMdcContext.java`

#### GIMLE-023 — Per-instance sifted log files

- **Category**: Observability / Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given two instances of different deployments logging concurrently, When both emit APPLICATION lines, Then each lands only in its own deployment-index.log file.
- **Other test coverage (non-Holmgang, informational only)**: `InstanceSiftingFileAppenderTest` (routes application lines by deployment/instance, skips platform lines, never leaks across instances, reopens after close)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/InstanceSiftingFileAppender.java`

#### GIMLE-024 — Platform (non-instance) log file appender

- **Category**: Observability / Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an APPLICATION event and a PLATFORM event, When both reach PlatformFileAppender, Then only the PLATFORM event is written.
- **Other test coverage (non-Holmgang, informational only)**: Exercised via `LogRotationTest`; no dedicated unit test class
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/PlatformFileAppender.java`

#### GIMLE-025 — Kubelet-style size/count log rotation

- **Category**: Observability / Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given small maxFileSizeBytes/maxFiles, When enough lines exceed the cap repeatedly, Then the oldest rotated copy is evicted past maxFiles.
- **Other test coverage (non-Holmgang, informational only)**: `LogRotationTest` (rolls over by size and evicts oldest, cursor paging/follow resolve correctly across rotation)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/RollingFileAppenders.java`

#### GIMLE-026 — Cursor-based log paging and live-follow streaming

- **Category**: Observability / Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a log stream spanning a rotation, When streamFollow is called from a cursor before the rotation, Then every line after that cursor is streamed as NDJSON, including lines now in the rotated file.
- **Other test coverage (non-Holmgang, informational only)**: `LogRotationTest#cursor_paging_and_follow_resolve_correctly_across_a_rotation_boundary`
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/logging/LogFileReader.java`

#### GIMLE-027 — Startup banner rendering with terminal color/Unicode auto-detection

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Startup banner rendering with terminal color/Unicode auto-detection" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `GimleBannerTest`, `GimleVersionTest`
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/banner/GimleBanner.java`, `AnsiPalette.java`, `GimleVersion.java`

#### GIMLE-028 — Single-write length-prefixed wire framing

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Single-write length-prefixed wire framing" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/codec/Frames.java`

#### GIMLE-029 — Hand-rolled JSON parser/writer

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Hand-rolled JSON parser/writer" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `JsonTest` (nested objects/arrays, negative/exponent numbers, escaped strings, round trip, escapes special chars, malformed throws)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/Json.java`

#### GIMLE-030 — Agent↔worker control-channel protocol and codec

- **Category**: Internal/Infra / Protocol
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Agent↔worker control-channel protocol and codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ControlMessageCodecTest` (module id with qualifier round trips, rejects empty line/unknown type/missing fields/malformed module id)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/ControlMessage.java`, `ControlMessageCodec.java`

#### GIMLE-031 — Node registration/heartbeat/capacity-reporting protocol

- **Category**: Internal/Infra / Protocol
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Node registration/heartbeat/capacity-reporting protocol" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/NodeRegistration.java`, `NodeCapabilities.java`, `NodeHeartbeat.java`, `ResourceUsageSnapshot.java`, `InstanceObservation.java`

#### GIMLE-032 — Instance lifecycle event log model

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a TRANSITION_FAILED event, When constructed, Then it carries a non-empty causeSummary alongside a stable id.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/InstanceEvent.java`, `InstanceEventKind.java`

#### GIMLE-033 — Cross-resource audit trail model

- **Category**: Security / Audit
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *A tenant write is recorded in the durable audit trail*
  - _Why this counts_: A real tenant write is followed by a read of the audit API showing an allowed WRITE event for that resource, proving the cross-resource audit trail is populated by real authorization decisions, not just declared as a schema.
- **Other test coverage (non-Holmgang, informational only)**: `AuditEventTest` (denied represented same as allowed, null groups/tenant/target coalesce to empty, blank id rejected)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/AuditEvent.java`

#### GIMLE-034 — Certificate bootstrap (CSR) request/response protocol

- **Category**: PKI
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node-join CSR that self-declares a privileged group is stamped with the node group instead*
  - _Why this counts_: Builds a real CsrSubmission (purpose/csrPem/bootstrapToken) and POSTs it to /bootstrap/csr, then decodes the response into a real CsrResult (status/certificatePem/caCertificatePem) using the same gimle-core protocol records production code uses.
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A CSR whose signature does not match its own declared key is rejected*
  - _Why this counts_: Drives the identical CsrSubmission/CsrResult wire protocol for the rejection path: a structurally valid but cryptographically bogus CSR is submitted and the resulting CsrResult status is asserted never to reach APPROVED.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/CsrSubmission.java`, `CsrResult.java`, `CsrPurpose.java`, `CsrRequestStatus.java`

#### GIMLE-035 — Assigned-instance work-order model (incl. in-place rename and vessel dispatch)

- **Category**: Scheduling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an AssignedInstance with renamedFromInstanceIndex present, When the agent processes it, Then it retargets the already-running instance under that prior index in place.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/AssignedInstance.java`

#### GIMLE-036 — Bounded-retry-with-backoff restart policy (CrashLoopBackOff-equivalent)

- **Category**: Self-Healing
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/self-healing.feature` — Scenario: *A module that never passes liveness is escalated to FAILED for good*
  - _Why this counts_: Deploys a provider variant whose LivenessProbe always reports false and asserts it escalates to a terminal FAILED instance once its restart budget (RestartTracker) is exhausted.
- **Other test coverage (non-Holmgang, informational only)**: `RestartTrackerTest` (allows retry within budget, exhausts after max attempts, delay grows exponentially/capped, window resets budget, success resets tracker)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/restart/RestartTracker.java`

#### GIMLE-037 — Tenant identity and resource quota model

- **Category**: Multi-tenancy
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a Tenant with negative quota field, When constructed, Then rejected with IllegalArgumentException.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/tenant/Tenant.java`, `ResourceQuota.java`

#### GIMLE-038 — Tenant-scoped config/secret entry model

- **Category**: Config / Secrets
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A secret's versions round-trip and a soft delete behaves differently from a hard one*
  - _Why this counts_: Every secret write/read/delete in this scenario is a real ConfigEntry row in the tenant-scoped store, exercised end to end through Fafnir's own versioned surface.
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A legacy pre-key-id secret ciphertext still decrypts correctly*
  - _Why this counts_: Plants a ConfigEntry directly (tenantId/key/value/encrypted) in the exact legacy ciphertext layout Fafnir's own writer no longer produces, proving the tenant-scoped entry model reads back correctly regardless of which era wrote it.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/config/ConfigEntry.java`

#### GIMLE-039 — Bundled SPA static-asset resolution from classpath

- **Category**: Internal/Infra / Web
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Bundled SPA static-asset resolution from classpath" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `BundledSpaTest` (file-scheme, jar-scheme, empty when absent, resolves different markers for different consoles)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/web/BundledSpa.java`

#### GIMLE-040 — SPA static file serving with client-side-route fallback

- **Category**: Internal/Infra / Web
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "SPA static file serving with client-side-route fallback" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SpaStaticHandlerTest` (serves real static file, falls back to shell, missing asset 404s, rejects traversal, rejects symlink escape)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/web/SpaStaticHandler.java`

#### GIMLE-041 — Saga test-run event model and NDJSON codec

- **Category**: Internal/Infra / Testing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Saga test-run event model and NDJSON codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaEventCodecTest` (single line naming type first, absent fields omitted)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/saga/SagaEvent.java`, `SagaEventCodec.java`

#### GIMLE-042 — Stable failure-signature hashing for flaky-test clustering

- **Category**: Internal/Infra / Testing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Stable failure-signature hashing for flaky-test clustering" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `core`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FailureSignatureTest` (run-specific numbers don't change signature, hex ids don't change it, different exception types differ, different messages differ, oversized messages truncated)
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/saga/FailureSignature.java`

### gimle-module

#### GIMLE-043 — Module dependency resolution with cycle detection

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A dependent resolves only when a version inside its declared range is present*
  - _Why this counts_: Same evidence as version-range matching (GIMLE-002): a dependent module only resolves and reaches ACTIVE once its declared dependency is present and compatible, proving non-cycle dependency resolution actually gates placement.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleResolverTest` (wires to highest satisfying version, candidate must be resolved/active, unsatisfied requirement reported, 2/3-length cycle detected, diamond dependency not a cycle, independent dependents wire independently)
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/resolve/ModuleResolver.java`

#### GIMLE-044 — Module registry (install bookkeeping, idempotent re-install, content-mismatch rejection)

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given module foo@1.0.0 already registered, When register is called again with identical sha256, Then no-op returning the same id; differing sha256 throws.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleRegistryTest` (register stores as installed, idempotent identical re-register, rejects differing re-register, unknown module id throws, named transitions update state, mark_failed reachable)
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/resolve/ModuleRegistry.java`

#### GIMLE-045 — Module lifecycle state machine (INSTALLED→RESOLVED→STARTING→ACTIVE→STOPPING→UNINSTALLED, plus FAILED/COMPLETED)

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A hook that always throws on start never reaches ACTIVE*
  - _Why this counts_: A module whose onStart hook always throws is deployed and asserted to end up FAILED rather than ACTIVE, proving the lifecycle state machine genuinely routes a startup failure to a terminal FAILED state instead of getting stuck or silently succeeding.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleControllerTest` (full happy path, start before resolve illegal, stop before active illegal, resolve failure marks failed, uninstall rejects active module, force_failed transitions to failed, complete_succeeded/failed paths)
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleController.java`, `ModuleState.java`, `LifecycleEvent.java`

#### GIMLE-046 — Dynamic per-module-version JPMS ModuleLayer construction

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Dynamic per-module-version JPMS ModuleLayer construction" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `module`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleLayerFactoryTest` (builds dependency-free layer, dependent layer calls into exported API, two versions get distinct layers, missing parent layer fails with GimleResolutionException)
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/layer/ModuleLayerFactory.java`, `ModuleLayerHandle.java`, `PlatformLayer.java`

#### GIMLE-047 — Unnamed-module readability grant for bundled hooks/probes

- **Category**: Module System / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Unnamed-module readability grant for bundled hooks/probes" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `module`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: gimle-worker's `RealBundledHookAndProbeInvocationTest`; this module's own `ModuleLayerFactoryTest` exercises the general mechanism
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/layer/ModuleLayerFactory.java` (`controller.addReads`)

#### GIMLE-048 — Classloader leak detection via PhantomReference

- **Category**: Module System / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Classloader leak detection via PhantomReference" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `module`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `LeakTrackerTest` (no leak when collected, leak reported when retained, wired through ModuleController reports no leak on clean stop)
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/leak/LeakTracker.java`, `ModuleLeakDetected.java`

#### GIMLE-049 — Repeated-redeploy flat-metaspace acceptance test

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module redeployed N times in a loop, When metaspace usage is sampled, Then samples plateau after warm-up.
- **Other test coverage (non-Holmgang, informational only)**: `RedeployLoopFlatMetaspaceTest#redeploy_loop_keeps_metaspace_flat`
- **Source location(s)**: `gimle-module/src/test/java/com/gimle/module/leak/RedeployLoopFlatMetaspaceTest.java`, `RedeployLoopDriver.java`

#### GIMLE-050 — Best-effort leak retaining-path attribution via JFR OldObjectSample

- **Category**: Module System / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Best-effort leak retaining-path attribution via JFR OldObjectSample" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `module`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `RetainingPathAttributionTest#leak_detector_surfaces_a_retaining_path_when_the_worker_jvm_enables_path_to_gc_roots`
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/leak/OldObjectSampleCorrelator.java`

#### GIMLE-051 — Module lifecycle hooks (reflectively instantiated, JPMS-exported)

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Module lifecycle hooks (reflectively instantiated, JPMS-exported)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `module`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `RealHookInvocationTest#hooks_fire_in_order_with_a_dynamically_loaded_module`
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleLifecycleHooks.java`, `ModuleController.instantiateHooks`

#### GIMLE-052 — Job-kind run-to-completion hooks

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an ACTIVE Job-kind module whose JobHooks.run returns SUCCEEDED, When ModuleController.complete(id, SUCCEEDED) is called, Then transitions straight to COMPLETED, emitting a Completed lifecycle event.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleControllerTest` (complete_succeeded/complete_failed/complete_rejects_non_active)
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/JobHooks.java`, `CompletionStatus.java`

#### GIMLE-053 — Module context API (in-flight tracking, service lookup, config, data dir, control-plane relay)

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *The service registry cuts a same-worker caller over to a newer ready version*
  - _Why this counts_: The cutover consumer's repeated in-flight service lookups through ModuleContext's own service-lookup API are what let it observe the newer version after cutover, exercising the context's live service-lookup path.
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *Stopping a module with a perpetually in-flight request still completes*
  - _Why this counts_: A module with a permanently in-flight request is stopped and confirmed torn down within the drain deadline, exercising ModuleContext's in-flight-request tracking that graceful drain depends on.
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A tenant-scoped module deploys, reads its secret, and is cleanly removed*
  - _Why this counts_: A tenant-scoped module reads its own secret via ModuleContext's config API and is cleanly removed -- pre-existing coverage of the context's config/data-dir surface, reused here rather than duplicated.
- **Other test coverage (non-Holmgang, informational only)**: `SimpleModuleContextTest` (invoke_service_by_name delegates/empty-on-unknown/propagates exception); `DrainDeadlineTest#stop_completes_after_deadline_despite_perpetual_in_flight_work`
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleContext.java`, `SimpleModuleContext.java`

#### GIMLE-054 — In-worker round-robin service registry with version-aware cutover

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *The service registry cuts a same-worker caller over to a newer ready version*
  - _Why this counts_: A same-worker caller's repeated calls through the in-worker service registry switch from an older to a newer version once the newer one registers and becomes ready, proving the registry's own version-aware cutover, not just static lookup.
- **Other test coverage (non-Holmgang, informational only)**: `SimpleServiceRegistryTest` (round robins, prefers highest ready version, falls back while highest has none ready, round robins within preferred version, mark_unready excludes without removing); `HotRedeployTest#old_and_new_versions_coexist_with_dependents_pinned_to_their_own_wiring`
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/SimpleServiceRegistry.java`

#### GIMLE-055 — Cross-tier name-driven service invocation

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A dependent resolves only when a version inside its declared range is present*
  - _Why this counts_: The consumer invokes its dependency purely by declared service name, never a compile-time reference, proving name-driven invocation resolves correctly.
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *The service registry cuts a same-worker caller over to a newer ready version*
  - _Why this counts_: The cutover consumer's repeated calls resolve by service name across a version change, proving name-driven invocation stays correct across tiers as the target rebinds.
- **Other test coverage (non-Holmgang, informational only)**: `SimpleServiceRegistryTest` (invokes directly by name, unknown interface returns empty, wrong method name throws, rethrows application exception with real type, void method returns empty Optional)
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ServiceRegistry.java` (default invokeByName), `SimpleServiceRegistry.java`

#### GIMLE-056 — Same-worker cross-module service publish/discover

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A dependent resolves only when a version inside its declared range is present*
  - _Why this counts_: The consumer and its dependency share one Tier 1 worker and the consumer discovers the dependency's published service without any network hop, proving same-worker publish/discover works end to end.
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *The service registry cuts a same-worker caller over to a newer ready version*
  - _Why this counts_: The cutover consumer and both library versions share one worker throughout, proving publish/discover keeps working same-worker even as the discovered service's own version changes underneath it.
- **Other test coverage (non-Holmgang, informational only)**: `ServiceRegistryIntegrationTest` (consumer finds service, consumer finds nothing without a provider)
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ServiceRegistry.java`, `SimpleServiceRegistry.java`

#### GIMLE-057 — Graceful drain-then-dispose stop with deadline

- **Category**: Module System / Self-Healing
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *Stopping a module with a perpetually in-flight request still completes*
  - _Why this counts_: A module with a request that never completes on its own is stopped anyway and confirmed torn down within the drain deadline, proving the drain-then-dispose stop path has a real deadline rather than blocking forever on an in-flight call.
- **Other test coverage (non-Holmgang, informational only)**: `DrainDeadlineTest#stop_completes_after_deadline_despite_perpetual_in_flight_work`
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleController.java` (`awaitDrain`)

#### GIMLE-058 — Hot redeploy (old/new version coexistence with pinned dependent wiring)

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given dependent D wired to dependency v1, and v2 is then installed/resolved, When D's wiring is inspected afterward, Then still wired to v1 unless explicitly re-resolved.
- **Other test coverage (non-Holmgang, informational only)**: `HotRedeployTest#old_and_new_versions_coexist_with_dependents_pinned_to_their_own_wiring`
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/resolve/ModuleResolver.java`, `ModuleWiring.java`

#### GIMLE-059 — gimle-module.yaml descriptor parsing and validation

- **Category**: Module System
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A manifest that fails its own validation never gets placed*
  - _Why this counts_: A manifest declaring a bogus/invalid isolation tier is submitted and never reaches a placed instance, proving gimle-module.yaml's own descriptor validation genuinely rejects malformed manifests before scheduling.
- **Other test coverage (non-Holmgang, informational only)**: `ModuleDescriptorParserTest` (various); indirectly via TestModuleBuilderTest
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/descriptor/ModuleDescriptorParser.java`

#### GIMLE-060 — Module artifact reading — real-JPMS-module and descriptor-presence validation

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Module artifact reading — real-JPMS-module and descriptor-presence validation" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `module`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: exercised via `TestModuleBuilderTest`; NONE dedicated `ModuleArtifactReaderTest` found
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/artifact/ModuleArtifactReader.java`

#### GIMLE-061 — Andvari artifact-registry pull-through cache

- **Category**: Module System / Internal-Infra
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/registry-deploy.feature` — Scenario: *A pushed module deploys by coordinate with no artifact path*
  - _Why this counts_: Pushes a real jar to Andvari, then submits a manifest with a coordinate and no artifactPath, asserting it resolves through the real agent pull-through cache and reaches ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/artifact/ArtifactPullCache.java`

#### GIMLE-062 — Multi-endpoint Andvari failover on pull

- **Category**: Module System / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Multi-endpoint Andvari failover on pull" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `module`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/artifact/ArtifactPullCache.java`

#### GIMLE-063 — Health probe interfaces (liveness/readiness)

- **Category**: Module System / Health
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A liveness probe's initial delay is honored before the first tick*
  - _Why this counts_: Directly exercises the liveness probe interface's own initial-delay semantics.
  - `gimle-holmgang/src/test/resources/features/self-healing.feature` — Scenario: *A module that never passes liveness is escalated to FAILED for good*
  - _Why this counts_: A module that never passes liveness is escalated to terminal FAILED -- pre-existing coverage of the readiness/liveness probe interfaces' failure path, reused here rather than duplicated.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/probe/LivenessProbe.java`, `ReadinessProbe.java`

#### GIMLE-571 — Hosted-module runtime port reporting folded into instance observation

- **Category**: Networking/Service Discovery
- **Status**: New  _(newly added as part of the Service/Bifrost/Skald/gateway/fabric-tenant-check network model work)_
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/service-fabric.feature` — Scenario: *A Service resolves a live endpoint for a hosted module reporting its own port*
  - _Why this counts_: The same scenario deploys a plain TIER_2 hosted module (not a Vessel) whose onStart calls ctx.reportPort("http", 9500), then asserts the fronting Service still resolves a live endpoint -- proving ServiceEndpointResolver.solePort() genuinely reads a hosted module's own reported port against a real cluster instead of only ever a Vessel workload's allocated one.
- **Other test coverage (non-Holmgang, informational only)**: `SimpleModuleContextTest`, `WorkerRuntimeReportedPortsTest`, `ControlMessageCodecTest`, `AgentMainTest`, `AgentMetricsReportPortFoldingTest` -- see requirements-matrix.json for detail
- **Source location(s)**: `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleContext.java`, `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java`, `gimle-core/src/main/java/com/gimle/core/protocol/ControlMessage.java`, `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java`

### gimle-os

#### GIMLE-064 — Pluggable resource-limiter abstraction

- **Category**: Resource Limiting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a caller holding only a ResourceLimiter reference, When it calls supports/prepare/jvmFlags/release, Then behavior is identical regardless of concrete implementation.
- **Other test coverage (non-Holmgang, informational only)**: exercised via `PortableJvmFlagsResourceLimiterTest`
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/ResourceLimiter.java`, `ResourceLimitHandle.java`

#### GIMLE-065 — Portable JVM-flags resource enforcement (Tier 1/Tier 2)

- **Category**: Resource Limiting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given ResourceSpec{memory=512Mi, cpu=1500m}, When jvmFlags(handle) is called, Then returns ["-Xmx536870912","-XX:ActiveProcessorCount=2"] (rounded up).
- **Other test coverage (non-Holmgang, informational only)**: `PortableJvmFlagsResourceLimiterTest` (supports tier 1/2 not 3, prepare returns handle, jvm flags derive Xmx/ActiveProcessorCount, release no-op)
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/portable/PortableJvmFlagsResourceLimiter.java`

#### GIMLE-066 — Tier 3 (namespace isolation) — deliberately unsupported by the current limiter

- **Category**: Resource Limiting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given PortableJvmFlagsResourceLimiter, When supports(IsolationTier.TIER_3) is called, Then returns false.
- **Other test coverage (non-Holmgang, informational only)**: `PortableJvmFlagsResourceLimiterTest#supports_tier_1_and_tier_2_but_not_tier_3`
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/portable/PortableJvmFlagsResourceLimiter.java` (`supports`); rejection (GimleIsolationException.tierUnsupported) is invoked by callers outside this module (e.g. gimle-agent)

#### GIMLE-067 — Kernel-level (cgroup v2) resource enforcement — deferred

- **Category**: Resource Limiting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: N/A — not implemented
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: N/A — no cgroup-writing code exists anywhere in gimle-os; only PortableJvmFlagsResourceLimiter exists

#### GIMLE-068 — Pluggable persistent-volume-manager abstraction

- **Category**: Storage
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a caller holding only a VolumeManager reference, When it calls allocate/hostPath/release, Then behavior is identical regardless of concrete backend.
- **Other test coverage (non-Holmgang, informational only)**: exercised via `LocalDiskVolumeManagerTest`
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/VolumeManager.java`, `VolumeHandle.java`

#### GIMLE-069 — Local-disk persistent volume allocation for StatefulSet-shaped instances

- **Category**: Storage
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a volume request exceeding the target filesystem's usable space, When LocalDiskVolumeManager.allocate is called, Then GimleVolumeException reporting insufficient space; allocating twice for the same index is idempotent.
- **Other test coverage (non-Holmgang, informational only)**: `LocalDiskVolumeManagerTest` (creates keyed directory, idempotent for same index, distinct dirs per index/statefulset, throws when exceeding usable space, release deletes contents, release of never-allocated is no-op)
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/localdisk/LocalDiskVolumeManager.java`

### gimle-pki

#### GIMLE-070 — Self-signed cluster CA generation

- **Category**: PKI
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node-join CSR that self-declares a privileged group is stamped with the node group instead*
  - _Why this counts_: The whole mtls topology's PKI trust chain -- including this scenario's own successfully-signed certificate -- depends on CertificateAuthority.generateSelfSignedCa having produced a real, working cluster CA at cluster boot.
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node rotates its own certificate over mTLS and keeps its identity*
  - _Why this counts_: The rotated certificate is signed by, and chains to, the same self-signed cluster CA generated at boot.
- **Other test coverage (non-Holmgang, informational only)**: `CertificateAuthorityTest` (generated_ca_is_self_signed_and_marked_as_a_ca, generated_ca_can_be_loaded_back_via_of)
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/CertificateAuthority.java`

#### GIMLE-071 — CSR-to-leaf-certificate signing with signature verification

- **Category**: PKI
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node-join CSR that self-declares a privileged group is stamped with the node group instead*
  - _Why this counts_: A real CSR is signed into a leaf certificate by CertificateAuthority.signCertificateRequest, with a genuine RSA signature the CA verifies before signing.
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A CSR whose signature does not match its own declared key is rejected*
  - _Why this counts_: A CSR whose declared public key does not match the key that signed it is submitted; CertificateAuthority's own isSignatureValid check rejects it with a 400 before any signing happens -- the negative half of signature verification.
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node rotates its own certificate over mTLS and keeps its identity*
  - _Why this counts_: Exercises the CA's identical signing code path a second way: a rotation request authenticated by the caller's own still-valid certificate rather than a bootstrap token.
- **Other test coverage (non-Holmgang, informational only)**: `CertificateAuthorityTest` (signed leaf chains to CA, signing rejects bad self-signature, leaf doesn't verify against unrelated CA)
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/CertificateAuthority.java`

#### GIMLE-072 — Server-stamped Subject override on signing (prevents self-declared privileged group)

- **Category**: PKI / Security
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node-join CSR that self-declares a privileged group is stamped with the node group instead*
  - _Why this counts_: Submits a NODE_CLIENT CSR self-declaring O=gimle:operators and asserts the issued certificate's real organization is the server-stamped O=gimle:nodes instead, never the self-declared privileged group.
- **Other test coverage (non-Holmgang, informational only)**: `CertificateAuthorityTest` (subject_override_wins, still rejects bad self-signature)
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/CertificateAuthority.java`

#### GIMLE-073 — CSR generation with Subject Alternative Names

- **Category**: PKI
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node-join CSR that self-declares a privileged group is stamped with the node group instead*
  - _Why this counts_: Builds the CSR via CertificateSigningRequests.generate with a requested subjectAltName (localhost) and asserts the issued certificate actually carries it, proving the SAN request round-trips through signing.
- **Other test coverage (non-Holmgang, informational only)**: `CertificateSigningRequestsTest`; SAN propagation covered by `CertificateAuthorityTest#signed_leaf_certificate_carries_requested_subject_alternative_names`
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/CertificateSigningRequests.java`

#### GIMLE-074 — Hand-rolled PEM encode/decode for certs, CSRs, and private keys

- **Category**: PKI / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Hand-rolled PEM encode/decode for certs, CSRs, and private keys" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `pki`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: exercised indirectly throughout CertificateAuthorityTest (`generated_leaf_certificate_is_readable_by_openssl`, `certificate_survives_a_keystore_round_trip`); NONE dedicated PemTest
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/Pem.java`

#### GIMLE-075 — Randomized certificate-renewal scheduling (anti-thundering-herd)

- **Category**: PKI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a certificate's validity window, When RenewalSchedule.of(certificate) is called, Then renewAt falls within the last 20–30% of validity.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/RenewalSchedule.java`

#### GIMLE-076 — Own-certificate rotation over mTLS via CSR bootstrap endpoint

- **Category**: PKI
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node rotates its own certificate over mTLS and keeps its identity*
  - _Why this counts_: Runs the real `gimle cert renew --force` CLI flow as a node's own mTLS identity -- the identical same-subject rotation-CSR-over-current-mTLS-connection protocol OwnCertificateRotator/AgentMain drive on their own periodic renewal check -- and asserts the renewed certificate keeps its subject but gets a new serial number.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/OwnCertificateRotator.java`

#### GIMLE-077 — X.500 Subject utilities: server-side O= stamping and Principal derivation

- **Category**: PKI / Security
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A node-join CSR that self-declares a privileged group is stamped with the node group instead*
  - _Why this counts_: The server-side O= stamping half: Subjects.withOrganization is what ApiServer#handleNodeJoinRequest calls to override a self-declared organization.
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Fafnir independently authorizes node-scoped secret reads by tenant assignment*
  - _Why this counts_: The Principal-derivation half: Fafnir's own resolvePrincipal calls Subjects.principalFrom on the node's peer certificate for both the allowed and the denied direct read.
- **Other test coverage (non-Holmgang, informational only)**: `SubjectsTest` (replaces existing organization, adds organization to one with none, rejects subject with no common name)
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/Subjects.java`

#### GIMLE-078 — Cluster PKI bootstrap CLI (`mvn gimle:tls-init`)

- **Category**: PKI / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Cluster PKI bootstrap CLI (`mvn gimle:tls-init`)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `pki`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-pki/src/main/java/com/gimle/pki/PkiBootstrapMain.java`

### gimle-worker

#### GIMLE-079 — Worker JVM control-channel bootstrap

- **Category**: Worker Supervision / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Worker JVM control-channel bootstrap" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ControlChannelClientTest#connect_with_retry_succeeds_once_the_listener_is_up`, `#connect_with_retry_gives_up_after_its_timeout_if_nothing_ever_listens`, `AgentWorkerIntegrationTest#agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel` (gimle-agent)
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`main`), `ControlChannelClient.connectWithRetry`

#### GIMLE-080 — Newline-delimited control-channel wire protocol (worker side)

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Newline-delimited control-channel wire protocol (worker side)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ControlChannelClientTest#a_sent_message_is_received_intact_on_the_other_end`, `#receive_returns_empty_once_the_peer_closes_the_connection`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/ControlChannelClient.java`

#### GIMLE-081 — Module install/resolve/start/stop/uninstall command dispatch

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a worker receives ControlMessage.InstallModule with a valid artifact path
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`handle`, `runCommand`)

#### GIMLE-082 — Instance identity registration and rename-in-place

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module is already ACTIVE with a registered InstanceIdentity
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/InstanceIdentity.java`, `InstanceIdentityRegistry.java`, `WorkerMain.java` (`RenameInstance` case)

#### GIMLE-083 — Per-instance MDC log tagging for lifecycle/hook/probe/request-dispatch logging

- **Category**: Observability / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Per-instance MDC log tagging for lifecycle/hook/probe/request-dispatch logging" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `BoundedModuleSchedulerTest#mdc_tags_are_visible_inside_a_tagged_submission`, `#empty_mdc_tags_leave_the_submission_untagged`; `InstanceTaggingServiceRegistryTest#registers_untagged_when_no_identity_is_known_for_the_owner`, `#registers_a_tagging_proxy_when_identity_is_known`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`mdcTagsFor`, `runCommand`), `InstanceTaggingServiceRegistry.java`, `BoundedModuleScheduler.java` (probe-tick tagging)

#### GIMLE-084 — Durable InstanceEvent emission per lifecycle transition

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module with a registered InstanceIdentity transitions from ACTIVE to STOPPING
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`handleLifecycleEvent`, `instanceEventFor`)

#### GIMLE-085 — Classloader leak detection on undeploy

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module is uninstalled and its ModuleLayer's loader is disposed
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`buildControllerAndRuntime`, `LeakTracker` wiring); actual detection logic lives in `gimle-module`

#### GIMLE-086 — Per-module bounded virtual-thread scheduler

- **Category**: Worker Supervision
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module goes ACTIVE with defaultMaxConcurrency = 4
- **Other test coverage (non-Holmgang, informational only)**: `BoundedModuleSchedulerTest#concurrency_bound_limits_how_many_tasks_run_at_once`, `#closed_scheduler_rejects_further_submissions`, `#max_concurrency_below_one_is_rejected`, `#submitted_task_runs_and_returns_its_result`, `#a_thrown_exception_surfaces_through_the_future`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/BoundedModuleScheduler.java`

#### GIMLE-087 — OpenTelemetry context propagation across virtual-thread dispatch

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a caller has an active OpenTelemetry Context with a value bound
- **Other test coverage (non-Holmgang, informational only)**: `BoundedModuleSchedulerTest#the_callers_ambient_context_is_restored_inside_the_submitted_task`, `#a_submission_made_outside_any_context_scope_sees_no_value_for_that_key`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/BoundedModuleScheduler.java` (`submit`)

#### GIMLE-088 — Liveness/readiness probe loop with timeout and initial-delay

- **Category**: Health / Self-Healing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module declares health.liveness/readiness classes and an initialDelaySeconds
- **Other test coverage (non-Holmgang, informational only)**: `ProbeLoopTest#a_passing_check_reports_true_repeatedly`, `#a_failing_check_reports_false`, `#a_check_that_throws_is_reported_as_a_failure_not_propagated`, `#a_check_that_hangs_past_its_timeout_is_reported_as_a_failure`, `#no_tick_fires_before_the_initial_delay_elapses`, `#after_the_initial_delay_ticks_settle_onto_the_ordinary_interval`, `#stop_halts_further_invocations_of_that_key`, `#two_keys_are_scheduled_independently`, `#the_production_constructor_still_schedules_on_a_real_ticker`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/ProbeLoop.java`, `WorkerRuntime.java` (`onActive` probe wiring)

#### GIMLE-089 — Module-tier self-healing — restart on repeated liveness failure with backoff and budget exhaustion

- **Category**: Worker Supervision / Self-Healing
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/self-healing.feature` — Scenario: *A module that never passes liveness is escalated to FAILED for good*
  - _Why this counts_: Deploys a provider variant whose LivenessProbe always reports false and asserts it escalates to a terminal FAILED instance once its restart budget (RestartTracker) is exhausted.
- **Other test coverage (non-Holmgang, informational only)**: `WorkerRuntimeTest#repeated_liveness_failures_restart_the_module_and_it_stays_registered_and_active`, `#a_module_that_never_recovers_liveness_exhausts_its_restart_budget_and_is_marked_failed`, `#a_module_that_recovers_before_failing_again_gets_a_fresh_restart_budget`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`onLivenessResult`, `restartModule`, `scheduleModuleStabilityConfirmation`, `newRestartTracker`)

#### GIMLE-090 — Readiness-driven service registry availability (without restart)

- **Category**: Health / Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an ACTIVE module's readiness probe reports false
- **Other test coverage (non-Holmgang, informational only)**: `WorkerRuntimeTest#a_readiness_failure_marks_the_service_unready_without_stopping_the_module`, `#a_module_becomes_lookupable_again_when_its_readiness_probe_recovers`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`onReadinessResult`)

#### GIMLE-091 — Stopping/Uninstalled teardown of scheduler, probes, and service registry

- **Category**: Worker Supervision / Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module transitions to STOPPING
- **Other test coverage (non-Holmgang, informational only)**: `WorkerRuntimeTest#stopping_a_module_makes_its_service_unreachable_and_removes_it_from_the_registry`, `#on_uninstalled_fires_the_close_callback_exactly_once_with_the_registered_identity`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`onStopping`, `onUninstalled`)

#### GIMLE-092 — Job-kind module execution (run-to-completion, not probed)

- **Category**: Module System
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module descriptor declares a jobHooksClass and goes ACTIVE
- **Other test coverage (non-Holmgang, informational only)**: `JobHooksExecutionTest#a_succeeding_job_runs_its_hooks_and_reaches_completed`, `#a_failing_job_reaches_failed`, `#a_job_hooks_run_that_throws_is_treated_as_failed`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java` (`runJobHooks`)

#### GIMLE-093 — Fabric service registration, cross-worker/cross-machine invocation binding

- **Category**: Fabric
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A consumer completes a real fabric call to a provider*
  - _Why this counts_: Deploys greeter-provider and greeter-consumer on the single-node 'minimal' topology and asserts the consumer's log shows a real cross-worker (same-machine, UDS-tier) fabric call reply.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`buildFabricRegistry`, `bindFabricServer`), `InstanceTaggingServiceRegistry.java`

#### GIMLE-094 — Fabric TLS certificate rotation detection (mtime polling)

- **Category**: Internal-Infra / Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Fabric TLS certificate rotation detection (mtime polling)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServerTlsWatcherTest#detects_a_rotated_certificate_file_and_reloads_the_fabric_server`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/FabricServerTlsWatcher.java`

#### GIMLE-095 — Control-plane read relay for hosted modules (RelayControlPlaneRead/Result round trip)

- **Category**: Fabric / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Control-plane read relay for hosted modules (RelayControlPlaneRead/Result round trip)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ControlPlaneRelayTest#a_matching_response_completes_the_waiting_caller_and_leaves_no_pending_entry`, `#no_response_times_out_and_still_leaves_no_pending_entry`, `#a_late_response_after_the_caller_already_gave_up_is_dropped_without_error`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/ControlPlaneRelay.java`

#### GIMLE-096 — Worker-side trace relay to agent (no direct Muninn shipping)

- **Category**: Observability / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Worker-side trace relay to agent (no direct Muninn shipping)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `RelayingSpanExporterTest#a_real_span_batch_relays_as_a_traces_snapshot_with_the_given_worker_id`, `#export_never_throws_even_when_the_sink_throws`, `#flush_and_shutdown_always_report_success`
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/RelayingSpanExporter.java`

#### GIMLE-097 — Per-module CPU/memory/request-rate/error-rate metrics reporting (portable, no cgroup)

- **Category**: Observability / Cgroup Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a worker has one or more ACTIVE modules
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`metricsReportLoop`, `rateSince`)

#### GIMLE-098 — Worker-wide meter snapshot relay to Muninn (via agent)

- **Category**: Observability / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Worker-wide meter snapshot relay to Muninn (via agent)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java` (`muninnMetricsRelayLoop`, `StopModule` case)

#### GIMLE-099 — `module-info.java` platform-layer/observability/fabric wiring for the worker module

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "`module-info.java` platform-layer/observability/fabric wiring for the worker module" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-worker/src/main/java/module-info.java`

#### GIMLE-100 — Real bundled-hook/probe classloading against the platform layer

- **Category**: Module System / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Real bundled-hook/probe classloading against the platform layer" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `worker`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `RealBundledHookAndProbeInvocationTest#bundled_hooks_and_probes_load_and_cast_against_this_jvms_own_platform_types`, `#bundled_probes_instantiate_and_cast_cleanly`
- **Source location(s)**: `gimle-worker/src/test/java/com/gimle/worker/RealBundledHookAndProbeInvocationTest.java` (test validates production wiring in `gimle-module`'s `ModuleLayerFactory`)

### gimle-agent

#### GIMLE-101 — Node agent registration and repeating reconcile/heartbeat/rotate tick loop

- **Category**: Worker Supervision / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Node agent registration and repeating reconcile/heartbeat/rotate tick loop" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `agent`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AgentWorkerIntegrationTest#agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel`, `ControlPlaneAgentWorkerIntegrationTest#control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`main`, `register`, `reconcileAssignments`, `sendHeartbeat`)

#### GIMLE-102 — Worker JVM process spawn and command-line construction

- **Category**: Worker Supervision
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module descriptor with a resource limit and a node id
- **Other test coverage (non-Holmgang, informational only)**: `AgentMainTest#the_spawned_command_carries_the_manifests_limit_not_its_request`, `#the_spawned_command_always_carries_exit_on_out_of_memory_error`, `#the_spawned_command_always_suppresses_the_startup_banner`, `#the_spawned_command_always_forces_json_console_logging`, `#the_spawned_command_forwards_the_default_deny_cross_tenant_flag`, `#prepare_resource_limit_hands_the_limiter_the_descriptors_limit_not_its_request`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`buildWorkerCommand`, `prepareResourceLimit`, `startInstance`)

#### GIMLE-103 — Worker process crash detection, classification, and destroy-and-respawn

- **Category**: Worker Supervision / Self-Healing
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/self-healing.feature` — Scenario: *A killed worker JVM is respawned and the deployment returns to ACTIVE*
  - _Why this counts_: Hard-kills the worker JVM hosting a running instance and asserts a new worker respawns and hosts it, with the deployment returning to ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: `WorkerProcessSupervisorTest#backoff_delay_escalates_across_repeated_crashes_then_gives_up`, `#a_respawn_that_stays_up_past_the_stability_threshold_resets_the_backoff`, `#an_exit_with_a_fresh_crash_dump_is_classified_as_native_crash`, `#a_plain_exit_with_no_crash_dump_is_classified_as_unknown`; `SystemLogCaptureTest#system_log_capture_survives_a_respawn`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/WorkerProcessSupervisor.java` (`onExit`, `classifyCrash`, `spawn`), `CrashInfo.java`

#### GIMLE-104 — Deliberate-stop suppression of crash-respawn

- **Category**: Worker Supervision
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given the supervisor's stop() sets closed=true and calls process.destroyForcibly()
- **Other test coverage (non-Holmgang, informational only)**: Implicit in `WorkerProcessSupervisorTest` setup/teardown paths; no dedicated `@Test` name asserting this directly — NONE explicit
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/WorkerProcessSupervisor.java` (`stop`, `onExit`'s `closed` guard)

#### GIMLE-105 — Worker stdout draining, JSON-line de-duplication, and raw SYSTEM-line capture

- **Category**: Observability / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Worker stdout draining, JSON-line de-duplication, and raw SYSTEM-line capture" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `agent`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SystemLogCaptureTest#system_log_capture_survives_a_respawn`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/WorkerProcessSupervisor.java` (`drainOutput`, `isJsonLine`, `captureSystemLine`)

#### GIMLE-106 — Machine-level capacity tracking and admission (memory/CPU)

- **Category**: Worker Supervision / Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a machine's total memory/CPU capacity and a set of currently-assigned worker keys
- **Other test coverage (non-Holmgang, informational only)**: `CapacityTrackerTest#try_assign_succeeds_within_capacity_and_is_reflected_in_the_snapshot`, `#try_assign_fails_once_it_would_exceed_total_capacity`, `#try_assign_rejects_a_key_already_holding_a_reservation`, `#release_frees_the_reservation_for_reuse`, `#rekey_moves_the_reservation_to_the_new_key_without_changing_total_usage`, `#rekey_is_a_noop_when_the_old_key_holds_no_reservation`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/CapacityTracker.java`

#### GIMLE-107 — Portable JVM-flags resource limiting (Tier 1/2), cgroup enforcement deliberately deferred

- **Category**: Cgroup Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a ModuleDescriptor's resourceLimit (memoryBytes, cpuMillicores)
- **Other test coverage (non-Holmgang, informational only)**: `ResourceLimitEnforcementTest#a_spawned_jvm_honors_the_computed_memory_and_processor_ceiling` (gimle-agent, real subprocess); `PortableJvmFlagsResourceLimiterTest` (gimle-os)
- **Source location(s)**: `gimle-os/src/main/java/com/gimle/os/portable/PortableJvmFlagsResourceLimiter.java`, `ResourceLimiter.java` (interface javadoc explicitly names a future kernel-level cgroup v2 implementation as not yet built); consumed by `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`prepareResourceLimit`, `buildWorkerCommand`, `buildVesselCommand`)

#### GIMLE-108 — Tier 3 isolation rejection

- **Category**: Cgroup Management / Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module descriptor declares IsolationTier.TIER_3
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`reconcileAssignments`, `startVesselInstance` — both call `resourceLimiter.supports`/`GimleIsolationException.tierUnsupported`)

#### GIMLE-109 — Assignment reconciliation loop (fetch, start, replace, stop)

- **Category**: Worker Supervision
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given the control plane's current assignments for this node
- **Other test coverage (non-Holmgang, informational only)**: `AgentMainTest#a_module_id_change_at_the_same_key_requires_replacement`, `#an_artifact_path_change_with_the_same_module_id_requires_replacement`, `#an_unchanged_assignment_at_the_same_key_never_requires_replacement`; `ControlPlaneAgentWorkerIntegrationTest#control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`reconcileAssignments`, `requiresReplacement`, `stopInstance`)

#### GIMLE-110 — Tier 1 density — shared-worker reuse for multiple module instances

- **Category**: Worker Supervision
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an existing worker hosts only TIER_1 instances, all the same tenant, none running the incoming moduleId, and under the density cap
- **Other test coverage (non-Holmgang, informational only)**: `AgentMainTest#a_worker_already_hosting_the_same_module_is_never_reused_for_another_replica`, `#a_worker_at_the_density_cap_is_not_reused`, `#a_worker_with_no_established_connection_yet_is_never_reused`; `Tier1DensityIntegrationTest#two_modules_share_one_worker_process_and_survive_one_being_stopped`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`findReusableTier1Worker`, `installIntoExistingWorker`)

#### GIMLE-111 — Instance rename-in-place (no restart)

- **Category**: Worker Supervision
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an assignment carries a renamedFromInstanceIndex pointing at an already-supervised, matching-module instance
- **Other test coverage (non-Holmgang, informational only)**: `AgentMainTest#find_rename_source_finds_the_already_supervised_instance_at_the_hinted_index`, `#find_rename_source_is_empty_without_a_rename_hint`, `#find_rename_source_falls_back_when_the_hinted_source_key_is_not_supervised`, `#find_rename_source_falls_back_when_the_source_runs_a_different_module`, `#rename_in_place_rekeys_supervised_and_shippers_and_updates_the_assigned_identity`, `#rename_in_place_notifies_the_connected_worker_of_its_new_identity`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`findRenameSource`, `renameInPlace`)

#### GIMLE-112 — Worker respawn handshake re-drive after crash

- **Category**: Worker Supervision / Self-Healing
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/self-healing.feature` — Scenario: *A killed worker JVM is respawned and the deployment returns to ACTIVE*
  - _Why this counts_: Hard-kills the worker JVM hosting a running instance and asserts a new worker respawns and hosts it, with the deployment returning to ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`onWorkerRespawned`, `resetForRespawn`)

#### GIMLE-113 — Worker-crash-to-durable-InstanceEvent relay

- **Category**: Observability / Self-Healing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a worker crashes and is classified via CrashInfo
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`onWorkerCrash`)

#### GIMLE-114 — Install-phase Nack escalates to FAILED (closing the "stuck at INSTALLED" gap)

- **Category**: Worker Supervision / Self-Healing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an instance's lifecycleState is still "INSTALLED"
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`readLoop`, `ControlMessage.Nack` case)

#### GIMLE-115 — Artifact-registry coordinate resolution via ArtifactPullCache

- **Category**: Config / Internal-Infra
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/registry-deploy.feature` — Scenario: *A pushed module deploys by coordinate with no artifact path*
  - _Why this counts_: Pushes a real jar to Andvari, then submits a manifest with a coordinate and no artifactPath, asserting it resolves through the real agent pull-through cache and reaches ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`resolveArtifactReference`, `reconcileAssignments`)

#### GIMLE-116 — Instance-scoped log/config/secret delivery over the control channel

- **Category**: Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A tenant-scoped module deploys, reads its secret, and is cleanly removed*
  - _Why this counts_: Submits a real tenant-scoped Deployment manifest via the HTTP API, asserts the instance's own log shows a Fafnir-delivered secret value, then deletes the deployment and asserts it drains away completely -- exercising CRUD, placement reconciliation, secret delivery, and orphan cleanup together.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`deliverConfig`, `fetchConfigForTenant`, `fetchSecretsForTenant`, `sendInstallStartSequence`)

#### GIMLE-117 — Persistent volume allocation for StatefulSet-shaped instances

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a descriptor declares volume: and the instance is starting or being installed into an existing worker
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`allocateVolumeIfNeeded`, `sendInstallStartSequence`, `stopInstance`)

#### GIMLE-118 — Vessel process supervision (plain-jar workload as its own dedicated process)

- **Category**: Worker Supervision
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an assignment carries a VesselSpec
- **Other test coverage (non-Holmgang, informational only)**: `VesselProcessSupervisorTest#captures_stdout_lines_as_the_instance_application_log`, `#a_crashed_vessel_process_is_respawned`, `#exhausting_the_restart_budget_reports_it_and_stops_respawning`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/VesselProcessSupervisor.java`, `AgentMain.java` (`reconcileVesselAssignment`, `startVesselInstance`, `buildVesselCommand`)

#### GIMLE-119 — Vessel port allocation (dynamic/fixed) and env resolution (literal/port/secret)

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a VesselSpec declares env entries of each kind (Literal/PortAllocation/SecretRef)
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`allocateVesselPorts`, `resolveVesselEnv`, `fetchVesselSecretsByKey`)

#### GIMLE-120 — Vessel config-file rendering to disk

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a VesselSpec declares files: [{configKey, path}]
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`renderVesselFiles`)

#### GIMLE-121 — Vessel health probing (process-alive + TCP/HTTP rungs, initial-delay aware)

- **Category**: Health / Self-Healing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a vessel process is alive and declares an HTTP readiness probe
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`updateVesselHealth`, `evaluateProbe`), `VesselProber.java`

#### GIMLE-122 — Vessel crash respawn resets probe initial-delay clock

- **Category**: Self-Healing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a vessel process crashes and VesselProcessSupervisor respawns it
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`onVesselRespawned`)

#### GIMLE-123 — mTLS bootstrap CSR flow for node identity

- **Category**: Internal-Infra / Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *The cluster functions end to end over mutual TLS*
  - _Why this counts_: Boots the whole cluster under the 'mtls' topology (every hop mTLS, agent CSR bootstrap via token) and asserts a tenant secret still round-trips end to end.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`bootstrapCertificateIfNeeded`)

#### GIMLE-124 — Periodic certificate rotation check and hot-swap of outbound HttpClient

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Periodic certificate rotation check and hot-swap of outbound HttpClient" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `agent`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`rotateCertificateIfDue`)

#### GIMLE-125 — SWIM gossip membership integration with service catalog relay

- **Category**: Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an agent starts its GossipMember and attaches a ServiceCatalog
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`relayCatalogDelta`, `registerIntoCatalog`, `syncCatalogToWorker`, gossip wiring in `main`)

#### GIMLE-126 — Gossip membership read-only HTTP surface

- **Category**: Fabric / Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an agent's GossipMember tracks itself and any learned peers
- **Other test coverage (non-Holmgang, informational only)**: `AgentGossipServerTest#reports_the_lone_self_member_alive_at_incarnation_zero`, `#reflects_a_peer_learned_through_real_swim_convergence`, `#rejects_non_get_methods`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentGossipServer.java`

#### GIMLE-127 — Node/instance log-serving HTTP surface with tailing and follow

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a node or instance log file exists under this agent's logRoot
- **Other test coverage (non-Holmgang, informational only)**: `AgentLogServerTest#node_platform_logs_have_the_shape_the_console_and_cli_need`, `#instance_application_logs_are_scoped_to_the_right_deployment_and_index`, `#instance_logs_reject_a_deployment_name_containing_a_path_separator`, `#instance_logs_reject_a_deployment_name_that_would_escape_the_log_root`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentLogServer.java`

#### GIMLE-128 — Merged node-level SYSTEM log view

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given multiple instances on this node have their own -system.log capture files
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentLogServer.java` (`readMergedSystemLogs`, `handleNodeLogs`)

#### GIMLE-129 — `hs_err_pid*.log` crash-dump listing and fetch

- **Category**: Observability / Cgroup Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a worker JVM native-crashed and HotSpot wrote hs_err_pid<pid>.log under its workerLogRoot
- **Other test coverage (non-Holmgang, informational only)**: `AgentLogServerTest#crash_dumps_are_listed_from_the_right_worker_directory_only`, `#crash_dumps_list_is_empty_when_the_worker_never_crashed`, `#a_crash_dump_is_fetched_with_its_exact_content_and_a_plain_text_content_type`, `#crash_dump_fetch_rejects_a_filename_that_does_not_match_the_expected_pattern`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentLogServer.java` (`listCrashDumps`, `fetchCrashDump`, `CRASH_DUMP_FILENAME`)

#### GIMLE-130 — Node-agent log/metrics shipping to Muninn (own + supervised)

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given -Dgimle.agent.muninnEndpoint is configured
- **Other test coverage (non-Holmgang, informational only)**: `AgentMuninnShippingTest#a_null_muninn_endpoint_starts_no_shippers`, `#a_configured_endpoint_ships_the_instances_application_log_to_its_own_instance_scoped_path`, `#stopping_shipping_removes_the_key_and_closes_every_shipper_so_no_further_ticks_arrive`, `#a_null_muninn_endpoint_starts_no_worker_shippers`, `#a_configured_endpoint_starts_one_metrics_and_one_traces_shipper_keyed_by_worker_id`, `#starting_twice_for_the_same_worker_id_is_a_noop_not_a_second_pair`, `#stopping_removes_the_key_and_a_missing_worker_id_is_a_noop`, `#hello_then_metrics_and_traces_snapshots_relay_to_the_stub_muninn_server`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`startShippingInstanceLogs`, `stopShippingInstanceLogs`, `startShippingWorkerMetricsAndTraces`, `stopShippingWorkerMetricsAndTraces`, `WorkerShipperPair`)

#### GIMLE-131 — Whitelisted control-plane read relay (worker→agent→control plane) with independent re-validation

- **Category**: Fabric / Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a worker sends RelayControlPlaneRead for a whitelisted path
- **Other test coverage (non-Holmgang, informational only)**: `AgentRelayControlPlaneReadTest#a_non_whitelisted_path_is_rejected_locally_and_never_reaches_the_control_plane`, `#a_path_traversal_attempt_disguised_as_a_single_segment_is_rejected`, `#a_whitelisted_path_triggers_a_real_call_and_relays_the_response_back`; end-to-end via `RelayControlPlaneEndToEndTest#a_hosted_modules_relay_call_round_trips_through_a_real_worker_process`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`handleRelayRead`, `RELAY_WHITELIST_PATTERN`)

#### GIMLE-132 — Node capacity/instance-observation heartbeat reporting

- **Category**: Observability / Worker Supervision
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given supervised instances/vessels and a capacity snapshot
- **Other test coverage (non-Holmgang, informational only)**: `AgentMainTest#observation_json_reports_the_instances_real_self_reported_resource_usage`, `#observation_json_reports_the_instances_real_self_reported_request_and_error_rate`, `#observation_json_reports_a_completed_job_run_as_alive_but_not_ready`, `#observation_json_reports_a_failed_instance_as_not_alive`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`sendHeartbeat`, `observationJson`, `vesselObservationJson`)

#### GIMLE-133 — Instance-event forwarding (worker-reported and agent-originated) to control plane

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a worker-reported or agent-originated InstanceEvent
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`postInstanceEvent`)

#### GIMLE-134 — Node placement-label registration

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given -Dgimle.node.labels=gpu,ssd is set on the agent process
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`nodeLabels`, `register`)

#### GIMLE-135 — `module-info.java` wiring for the node agent module

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "`module-info.java` wiring for the node agent module" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `agent`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-agent/src/main/java/module-info.java`

#### GIMLE-568 — gimle-bifrost: per-node service proxy (kube-proxy analogue)

- **Category**: Service Fabric
- **Status**: New  _(newly added as part of the Service/Bifrost/Skald/gateway/fabric-tenant-check network model work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario enables -Dgimle.agent.bifrostEnabled=true and asserts a caller can reach a declared Service through the node-local loopback proxy end to end. To close: boot a topology with Bifrost enabled on at least one node, declare a Service backed by a real deployed module, and assert a connection to the synthesized 127.x.y.1 ClusterIP address is forwarded to that module's live instance.
- **Other test coverage (non-Holmgang, informational only)**: `BifrostProxyTest` (3 tests: round-robin across endpoints, listener closed on service disappearance, new listener bound on service appearance); `LoopbackAddressAllocatorTest`; `HttpServiceSourceTest`
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/bifrost/BifrostProxy.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/ServiceListener.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/LoopbackAddressAllocator.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/HttpServiceSource.java`, `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java`

#### GIMLE-575 — Bifrost fails closed for a NetworkPolicySpec-restricted Service

- **Category**: Networking/Security
- **Status**: New  _(closes the Bifrost-as-a-NetworkPolicySpec-bypass gap left open since the bifrost lane first landed -- resolved by fail-closed rather than by giving Bifrost a caller-identity mechanism, which its blind byte-relay design cannot support)_
- **Coverage**: Not Covered
- **Gap note**: Holmgang's Cucumber suite has no scenario proving Bifrost actually refuses a restricted Service end to end. To close: extend a network-policy feature file with a scenario that declares a NetworkPolicySpec against a real deployed module's tenant, dials its Service through a real Bifrost-enabled agent's synthesized ClusterIP, and asserts the connection is refused.
- **Other test coverage (non-Holmgang, informational only)**: `BifrostProxyTest` (3 new fail-closed scenarios), `HttpServiceSourceTest` -- see requirements-matrix.json for detail
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/bifrost/BifrostProxy.java`, `gimle-agent/src/main/java/com/gimle/agent/bifrost/ServiceListener.java`

#### GIMLE-583 — Narrowed config delivery to instances declaring `configMapRefs`

- **Category**: Configuration Management
- **Status**: New  _(newly added as part of the ConfigMap kind (optimistic concurrent writes) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Cucumber .feature scenario exercises narrowed config delivery to a real agent-supervised instance -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: Covered indirectly through `AssignedInstance`'s own back-compat-constructor tests and `ApiServerConfigMapTest`'s batch-get coverage; no dedicated `AgentMainTest` fixture exists for `fetchConfigMaps`/`deliverConfig`'s narrowed branch specifically (see gapNote in rtm.json).
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/protocol/AssignedInstance.java` (`configMapRefs`), `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java` (`handleAssignments`, `assignedInstanceToJson`), `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`fetchConfigMaps`, `deliverConfig`, `resolveArtifactReference`)

#### GIMLE-591 — Narrowed secret delivery via `secretMapRefs`

- **Category**: Secrets Management
- **Status**: New  _(newly added as part of the SecretMap kind (Fafnir-native, v1) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises secretMapRefs-narrowed secret delivery against a real running cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `AgentMainTest#secret_map_refs_narrows_delivery_to_only_the_named_secretmaps_keys` drives a real fake Fafnir + control-plane HTTP server pair and a real Unix-socket `WorkerConnection`, asserting only the named SecretMap's key arrives as `ConfigDelivered` and that the unscoped flat `/secrets/{tenantId}` listing is never even called once `secretMapRefs` is declared.
- **Source location(s)**: `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java` (`fetchSecretMaps`, `deliverConfig`, `fetchAssignments`)

#### GIMLE-603 — Sleipnir: agent-managed JDK AOT startup cache for worker JVMs

- **Category**: Worker Supervision
- **Status**: New
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/aot-cache.feature` — Scenario: *the agent logs ineligibility and the deployment still reaches ACTIVE normally*
  - _Why this counts_: Boots a real Holmgang cluster (whose own worker classpath mixes real jars with target/classes directories, exactly the shape JEP 483 disqualifies) and deploys a real module -- proves Sleipnir's ineligibility path fires exactly once, is observable in the agent's own platform log, and never blocks or breaks the deployment, which still reaches ACTIVE and never writes a cache file.
- **Other test coverage (non-Holmgang, informational only)**: `WorkerStartupBenchIT`, `SleipnirCacheTest`, `SleipnirTrainerTest`, `SleipnirTrainerRealRunIT`, `RedeployLoopFlatMetaspaceTest`'s AOTMode=auto variant
- **Source location(s)**: `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java`, `gimle-agent/src/main/java/com/gimle/agent/SleipnirCache.java`, `gimle-agent/src/main/java/com/gimle/agent/SleipnirTrainer.java`, `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java`

### gimle-mimir

#### GIMLE-136 — Raft Leader Election

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *The store leader dies mid-workload and nothing acknowledged is lost*
  - _Why this counts_: Kills the current Raft leader specifically (forcing re-election) mid-workload and asserts the identical no-lost-write property.
- **Other test coverage (non-Holmgang, informational only)**: `RaftClusterTest#leader_election_converges_to_exactly_one_leader`, `RaftNodeSafetyMechanicsTest#a_candidate_with_a_stale_log_never_wins_even_when_its_request_vote_arrives_first`
- **Source location(s)**: `com.gimle.mimir.raft.RaftNode` (`startElectionLocked`, `becomeLeaderLocked`, `onRequestVote`), `RequestVote`/`RequestVoteResponse`

#### GIMLE-137 — Log Replication (AppendEntries)

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *A store member dies mid-workload and nothing acknowledged is lost*
  - _Why this counts_: Kills store-0 mid-workload under a live write generator and asserts the cluster keeps accepting writes and every previously-acknowledged tenant write remains readable. NOTE: this .feature file is not referenced anywhere in the REQUIREMENTS_MATRIX.md baseline -- it existed at the baseline commit but the original scan missed cataloguing it; see header note.
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *The store leader dies mid-workload and nothing acknowledged is lost*
  - _Why this counts_: Kills the current Raft leader specifically (forcing re-election) mid-workload and asserts the identical no-lost-write property.
- **Other test coverage (non-Holmgang, informational only)**: `RaftClusterTest#a_submitted_write_becomes_visible_on_every_replica_after_the_next_append_entries_round`
- **Source location(s)**: `RaftNode#sendOnce`, `#onAppendEntries`, `AppendEntries`/`AppendEntriesResponse`

#### GIMLE-138 — Election Safety Restriction (log up-to-date check)

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *A stale, partitioned follower cannot win an election despite outracing the cluster's term*
  - _Why this counts_: Partitions a genuinely non-leader store away from its peers (resolved dynamically, never the leader itself) while a background writer keeps committing through the surviving majority, letting the isolated node's own term climb via repeated failed elections while its log falls behind; once healed, asserts the first leader reported afterward is never the stale node -- proving RequestVote's candidate-log-up-to-date check rejected its vote despite its higher term.
- **Other test coverage (non-Holmgang, informational only)**: `RaftNodeSafetyMechanicsTest#a_candidate_with_a_stale_log_never_wins_even_when_its_request_vote_arrives_first`
- **Source location(s)**: `RaftNode#onRequestVote` (`candidateUpToDate` check)

#### GIMLE-139 — Conflicting-Entry Truncation

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a follower with an entry at index N from an old term; When an AppendEntries request carries a different entry at index N; Then the follower truncates from index N onward before appending the leader's entries.
- **Other test coverage (non-Holmgang, informational only)**: `RaftNodeSafetyMechanicsTest#a_follower_truncates_a_conflicting_entry_and_everything_after_it_before_appending`
- **Source location(s)**: `RaftNode#onAppendEntries` (truncate-on-conflict loop), `RaftLog#truncateFrom`

#### GIMLE-140 — Leader-Only-Commits-Own-Term Rule (Figure 8)

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a leader whose matchIndex table reaches majority for an entry from an earlier term; When advanceCommitIndex evaluates that index; Then the entry is not committed unless a later same-term entry also reaches majority.
- **Other test coverage (non-Holmgang, informational only)**: `RaftNodeSafetyMechanicsTest#the_leader_only_commits_an_entry_from_its_own_current_term`
- **Source location(s)**: `RaftNode#advanceCommitIndexLocked`

#### GIMLE-141 — Strict Apply Ordering (commitIndex vs lastApplied)

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *A store member dies mid-workload and nothing acknowledged is lost*
  - _Why this counts_: Kills store-0 mid-workload under a live write generator and asserts the cluster keeps accepting writes and every previously-acknowledged tenant write remains readable. NOTE: this .feature file is not referenced anywhere in the REQUIREMENTS_MATRIX.md baseline -- it existed at the baseline commit but the original scan missed cataloguing it; see header note.
- **Other test coverage (non-Holmgang, informational only)**: `RaftNodeSafetyMechanicsTest#apply_never_runs_ahead_of_commit_index_and_never_skips_an_entry`
- **Source location(s)**: `RaftNode#applyCommittedLocked`

#### GIMLE-142 — Proposal Timeout with Ghost-Write Prevention

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` — Scenario: *A leader's write proposed while partitioned is truncated and never resurfaces*
  - _Why this counts_: Proposes a tenant write directly against the isolated (still self-believing) leader's own client port -- not the pooled, any-endpoint API client -- guaranteeing the proposal actually reaches the node under test; asserts the proposal is refused within the propose timeout (never hangs) and that the tenant never becomes readable even after the partition heals and the cluster recovers, proving the ghost write was truncated from the leader's own log rather than silently resurfacing.
- **Other test coverage (non-Holmgang, informational only)**: `RaftNodeSafetyMechanicsTest#a_timed_out_proposal_is_truncated_so_it_cannot_ghost_commit_once_quorum_returns`, `#a_proposal_that_commits_just_before_its_timeout_fires_is_not_truncated`
- **Source location(s)**: `RaftNode#awaitAppliedThrowing`, `#giveUpAndTruncateLocked`

#### GIMLE-143 — Chunked InstallSnapshot Transfer (Figure 13)

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *A learner catches up through a compacted leader's snapshot and only helps quorum once promoted*
  - _Why this counts_: Writes 10,500 entries directly through the real StoreClient production API, forcing the leader's log past its own compaction threshold before a brand-new, empty-log store joins as a learner; the joining node can only catch up via a chunked InstallSnapshot transfer at that point (plain AppendEntries replay is impossible past the compacted floor), and the scenario later confirms the bulk-written data (including the very last entry) is genuinely readable after the new store rejoins the working cluster.
- **Other test coverage (non-Holmgang, informational only)**: `RaftClusterTest#a_far_behind_follower_catches_up_via_install_snapshot_not_full_log_replay`, `RaftNodeSafetyMechanicsTest#an_install_snapshot_is_applied_only_once_the_final_done_chunk_arrives`, `#a_chunk_arriving_at_an_unexpected_offset_is_acknowledged_but_not_buffered`, `#an_offset_zero_chunk_discards_a_stale_in_progress_transfer_and_starts_a_fresh_one`
- **Source location(s)**: `RaftNode#sendInstallSnapshot`, `#onInstallSnapshot`, `InstallSnapshot`/`InstallSnapshotResponse`

#### GIMLE-144 — Local Log Compaction / Snapshotting

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *A learner catches up through a compacted leader's snapshot and only helps quorum once promoted*
  - _Why this counts_: The same 10,500-entry direct write forces RaftNode's own SNAPSHOT_THRESHOLD compaction to run automatically; the scenario's later steps (a store restart in the persistence scenario, and the joining learner's own forced snapshot-only catch-up here) are only possible because compaction genuinely ran and discarded entries below the floor, observed indirectly through the platform continuing to serve every bulk-written tenant correctly afterward.
- **Other test coverage (non-Holmgang, informational only)**: `RaftLogTest#install_snapshot_persists_and_discards_compacted_entries`
- **Source location(s)**: `RaftNode#maybeCompactLocked`, `RaftLog#installSnapshot`

#### GIMLE-145 — Check-Quorum Leader Self-Demotion

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` — Scenario: *A store leader silently partitioned from its peers steps down and writes stay bounded*
  - _Why this counts_: Uses a Loki fault proxy to isolate the Raft leader from its peers (no crash, just silence) and asserts check-quorum self-demotion within 10s, plus a submitted write completing (not hanging) within 30s.
- **Other test coverage (non-Holmgang, informational only)**: `RaftClusterTest#a_leader_partitioned_from_the_majority_steps_down_on_its_own_via_check_quorum`, `#a_leader_with_a_reachable_majority_never_self_demotes_via_check_quorum`
- **Source location(s)**: `RaftNode#checkQuorumTick`, `#CHECK_QUORUM_WINDOW`

#### GIMLE-146 — Etcd-Style Live Membership Change (AddServer/RemoveServer)

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/membership-change.feature` — Scenario: *A fourth store joins and then leaves, one server at a time*
  - _Why this counts_: Drives a real AddServer then RemoveServer against a live 3-node store cluster via the topology's own bring-up path, asserting member count and continued write acceptance at each step.
- **Other test coverage (non-Holmgang, informational only)**: `RaftMembershipChangeTest#adding_a_server_joins_it_and_a_subsequent_mutation_still_commits`, `#a_second_membership_change_is_rejected_while_an_earlier_one_is_still_uncommitted`, `#removing_a_server_drops_it_from_the_peer_set_and_the_lone_remaining_node_still_commits`, `RaftClusterTest#a_three_node_cluster_grows_to_five_live_and_writes_continue_succeeding`
- **Source location(s)**: `RaftNode#addServer`, `#removeServer`, `#appendMembershipChangeLocked`, `#reconfigurePeersLocked`, `MembershipChange`

#### GIMLE-147 — Non-Voting Learner & Automatic Promotion

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *A learner catches up through a compacted leader's snapshot and only helps quorum once promoted*
  - _Why this counts_: After the new store joins as a non-voting learner, the scenario immediately kills two of the three original voters (leaving only one original voter and the still-catching-up learner alive) and asserts the cluster refuses every write for a real window -- a majority of the original three-voter configuration is unreachable, and the learner's own vote/ack cannot substitute for a real voter's, proving it genuinely does not count toward quorum until promoted. Restarting one original voter afterward restores quorum and confirms recovery.
- **Other test coverage (non-Holmgang, informational only)**: `RaftMembershipChangeTest#a_freshly_added_learner_does_not_block_or_count_toward_commit_quorum`, `#a_learner_is_promoted_to_a_full_voting_member_once_its_log_catches_up`, `#a_never_caught_up_learner_stays_non_voting_indefinitely`
- **Source location(s)**: `RaftNode#learners`, `#maybePromoteLearnerLocked`, `#LEARNER_CATCH_UP_THRESHOLD`, `#votingPeersLocked`

#### GIMLE-148 — Durable Raft Log Persistence

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *A store member dies mid-workload and nothing acknowledged is lost*
  - _Why this counts_: Kills store-0 mid-workload under a live write generator and asserts the cluster keeps accepting writes and every previously-acknowledged tenant write remains readable. NOTE: this .feature file is not referenced anywhere in the REQUIREMENTS_MATRIX.md baseline -- it existed at the baseline commit but the original scan missed cataloguing it; see header note.
  - `gimle-holmgang/src/test/resources/features/raft-resilience.feature` — Scenario: *The store leader dies mid-workload and nothing acknowledged is lost*
  - _Why this counts_: Kills the current Raft leader specifically (forcing re-election) mid-workload and asserts the identical no-lost-write property.
- **Other test coverage (non-Holmgang, informational only)**: `RaftLogTest#term_and_vote_persist_across_reopen`, `#reopening_recovers_every_persisted_entry`, `#a_far_behind_node_recovers_the_snapshot_floor_and_bytes_across_reopen`, `#a_corrupted_log_entry_file_fails_loudly_at_construction`
- **Source location(s)**: `com.gimle.mimir.raft.RaftLog` (`append`, `setTermAndVote`, `loadState`, `loadEntries`)

#### GIMLE-149 — Raft Transport over Mutual TLS with Hot Cert Reload

- **Category**: Raft Consensus
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *The cluster functions end to end over mutual TLS*
  - _Why this counts_: Boots a genuine 3-replica store cluster entirely over mutual TLS and drives a real deployment to ACTIVE plus a real secret round-trip -- neither is reachable without real Raft leader election and log replication succeeding across every mTLS-secured RaftTransport/PeerConnection hop.
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *The audit trail records and filters real authorization decisions over mutual TLS*
  - _Why this counts_: Two real tenant writes against the same mTLS store cluster succeed and are independently observable via the audit trail, reinforcing that election and replication over mTLS are working, not merely assumed.
- **Other test coverage (non-Holmgang, informational only)**: `RaftClusterTlsTest#leader_election_and_write_replication_work_over_mtls`, `#leader_crash_triggers_re_election_over_mtls`, `#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_transport`, `#a_peer_cert_not_signed_by_the_configured_ca_is_rejected_at_handshake`
- **Source location(s)**: `com.gimle.mimir.raft.RaftTransport`, `PeerConnection`

#### GIMLE-150 — Raft RPC Wire Codec

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Raft RPC Wire Codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `RaftCodecTest#round_trips_through_streams`, `#rejects_an_oversized_length_prefix_before_allocating`, `#rejects_a_negative_length_prefix_before_allocating`, `#rejects_a_forged_huge_entry_count_without_preallocating`, `#round_trips_a_state_snapshot`, `#round_trips_a_log_entry_carrying_a_membership_change`
- **Source location(s)**: `com.gimle.mimir.raft.RaftCodec`

#### GIMLE-151 — Atomic Durable File Writes

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Atomic Durable File Writes" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AtomicFilesTest#writes_content_visible_under_the_final_name_with_no_leftover_tmp_file`, `#the_written_file_has_no_unflushed_dirty_state_after_writeatomically_returns`, `StateStoreTest#a_leftover_tmp_file_from_an_interrupted_write_is_never_read_back`
- **Source location(s)**: `com.gimle.mimir.store.AtomicFiles`

#### GIMLE-152 — File-Backed State Store Persistence Engine

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/state-store-persistence.feature` — Scenario: *Tenants, roles, role bindings, and accounts survive a store restart, snapshot included*
  - _Why this counts_: Writes a tenant, a role, a role binding, and an account, then kills and respawns the sole store process against the identical on-disk directory (the real GimleProcess#restart contract) and confirms every one of them, plus 10,500 bulk-written tenants, is still readable from the freshly-opened StateStore instance -- a genuine process restart against the same directory, not an in-process reopen.
- **Other test coverage (non-Holmgang, informational only)**: `StateStoreTest#a_fresh_store_creates_its_directory_layout`, `#deployment_round_trips_through_a_fresh_store_instance`, `#removed_deployment_is_gone_after_reload`, `#assignment_round_trips_and_is_scoped_to_its_deployment`, `#role_role_binding_and_account_round_trip_through_a_fresh_store_instance`
- **Source location(s)**: `com.gimle.mimir.store.StateStore`

#### GIMLE-153 — Full-State Snapshot / Restore

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/state-store-persistence.feature` — Scenario: *Tenants, roles, role bindings, and accounts survive a store restart, snapshot included*
  - _Why this counts_: The 10,500 direct writes exceed RaftNode's own compaction threshold before the restart, so the respawned store process can only rebuild its pre-restart state from its own persisted snapshot (the log below the compaction floor no longer exists to replay) -- and every resource kind written before the restart, including the snapshot-covered bulk tenants, comes back identical.
- **Other test coverage (non-Holmgang, informational only)**: `StateStoreTest#a_snapshot_carries_reconciler_instance_state_and_restores_it`, `#a_snapshot_carries_instance_events_and_restores_them`, `#a_snapshot_carries_audit_events_and_restores_them`, `RaftCodecTest#round_trips_a_state_snapshot`
- **Source location(s)**: `StateStore#snapshot`, `#restoreFromSnapshot`, `com.gimle.mimir.store.StateSnapshot`

#### GIMLE-154 — Replicated Mutation Catalog (StateMutation)

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Replicated Mutation Catalog (StateMutation)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `RaftCodecTest#round_trips_role_rolebinding_and_account_mutations_through_a_log_entry`, `#round_trips_an_append_instance_event_mutation_with_and_without_a_cause_summary`, `#round_trips_an_append_audit_event_mutation_allowed_and_denied_with_and_without_scope`
- **Source location(s)**: `com.gimle.mimir.raft.StateMutation` (sealed interface, ~40 record variants)

#### GIMLE-155 — Leader-Local Node Heartbeat Tracking

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/state-store-mechanics.feature` — Scenario: *Node heartbeats update continuously for a live node*
  - _Why this counts_: Confirms a real node agent's own heartbeat is observed and tracked (GET /nodes reflecting putNodeHeartbeat/getNodeHeartbeat) and that it continues to advance over real wall-clock time, proving the mechanism is a live, continuously-updated read path rather than a one-shot registration.
- **Other test coverage (non-Holmgang, informational only)**: `StoreNodeTest#a_leader_reads_back_a_heartbeat_it_just_accepted`, `rpc/StoreClientClusterTest#heartbeat_reads_are_leader_routed_and_never_answer_empty_from_a_stale_follower`
- **Source location(s)**: `StateStore#putNodeHeartbeat`, `#getNodeHeartbeat`, `com.gimle.mimir.rpc.StoreNode#handleGetNodeHeartbeat`

#### GIMLE-156 — Distributed Lease Coordination (Grant/Renew/Release)

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/state-store-mechanics.feature` — Scenario: *A lease is exclusive to its holder until it expires*
  - _Why this counts_: Drives the real StoreClient#tryAcquireOrRenewLease/#releaseLease production API directly: one holder acquires a lease, a second is denied while it is still held, and the second holder succeeds only once the TTL has actually expired -- the full grant/contention/expiry lifecycle against a live cluster.
- **Other test coverage (non-Holmgang, informational only)**: `StateStoreTest#a_free_lease_is_granted_to_the_first_caller`, `#the_current_holder_can_renew_its_own_lease`, `#a_different_holder_is_denied_while_the_lease_is_still_valid`, `#a_different_holder_is_granted_once_the_lease_has_expired`, `rpc/StoreClientClusterTest#leases_are_acquired_renewed_and_released_through_the_client`
- **Source location(s)**: `StateStore#tryAcquireOrRenewLease`, `#releaseLease`, `#getLeaseHolder`, `com.gimle.mimir.store.LeaseGrant`

#### GIMLE-157 — Per-Instance Lifecycle Event Log with Retention Cap

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/state-store-mechanics.feature` — Scenario: *An instance's event log is capped and returns newest first*
  - _Why this counts_: Relays 55 real InstanceEvents through the same POST /nodes/{nodeId}/events route a real node agent uses, pushing a live instance's event log past StateStore's MAX_EVENTS_PER_INSTANCE cap, then confirms exactly 50 events remain and are strictly newest-first -- proving both the retention prune and the ordering.
- **Other test coverage (non-Holmgang, informational only)**: `StateStoreTest#instance_events_round_trip_newest_first_through_a_fresh_store_instance`, `#instance_events_beyond_the_retention_cap_prune_the_oldest_first`
- **Source location(s)**: `StateStore#putInstanceEvent`, `#listInstanceEvents`, `StateMutation.AppendInstanceEvent`

#### GIMLE-158 — Cluster-Wide Audit Trail with Filtering

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *The audit trail records and filters real authorization decisions over mutual TLS*
  - _Why this counts_: Audit recording only happens over an authenticated (HTTPS) exchange, so this runs under the real mTLS topology: two tenant writes for two different tenants produce real audit events, and GET /audit filtered by tenant id both finds the matching entry and excludes the other tenant's -- proving listAuditEvents' own filtering against real, distinguishing data.
- **Other test coverage (non-Holmgang, informational only)**: `StateStoreTest#audit_events_filter_by_principal_resource_kind_tenant_and_since_independently`, `#audit_events_beyond_the_retention_cap_prune_the_oldest_first`, `#concurrent_audit_event_appends_never_exceed_the_cap_or_lose_or_duplicate_an_event`
- **Source location(s)**: `StateStore#putAuditEvent`, `#listAuditEvents`, `StateMutation.AppendAuditEvent`

#### GIMLE-159 — Deployment Rolling-Update & Surge Bookkeeping

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/rolling-update.feature` — Scenario: *Zero-downtime rollout under a surge budget*
  - _Why this counts_: Rolls a 2-replica deployment to a genuinely rebuilt v1.1.0 artifact under maxUnavailable=1/maxSurge=1, holding an invariant that at least 1 instance stays ACTIVE throughout, then asserts both instances end on the new version.
- **Other test coverage (non-Holmgang, informational only)**: Covered indirectly by `RaftCodecTest` mutation round-trips — NONE direct StateStoreTest method found
- **Source location(s)**: `StateStore#addRollingIndex`/`#getRollingIndices`, `#addSurgeIndex`/`#getSurgeIndices`, `StateMutation.AddRollingIndex`/`AddSurgeIndex`

#### GIMLE-160 — StatefulSet OrderedReady Index & Sticky Node Binding

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/state-store-mechanics.feature` — Scenario: *A StatefulSet ordinal index stays bound to the node it first lands on*
  - _Why this counts_: Deploys a real StatefulSet, records which of the topology's two nodes its sole ordinal index first landed on, kills the worker hosting it, and confirms the self-healing replacement instance is rescheduled onto the identical node -- putStatefulSetIndexNode's own sticky binding surviving a real reschedule, not just its own unit-level round trip.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `StateStore#putStatefulSetIndexNode`/`#getStatefulSetIndexNode`, `#putRollingStatefulSetIndex`, `StateMutation.PutStatefulSetIndexNode`

#### GIMLE-161 — Node Cordon (Scheduler Exclusion Flag)

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/scheduling.feature` — Scenario: *A cordoned node blocks placement until uncordoned*
  - _Why this counts_: Cordons the sole node, submits a deployment, asserts it stays unplaced for 10s, then uncordons and asserts it reaches ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: `StateStoreTest#node_cordon_round_trips_through_a_fresh_store_instance`, `#uncordoning_a_node_clears_it_and_is_gone_after_reload`, `#a_snapshot_carries_node_cordons_and_restores_them`
- **Source location(s)**: `StateStore#putNodeCordon`, `#isNodeCordoned`, `StateMutation.PutNodeCordon`

#### GIMLE-162 — Tenant Quota-Violation Flag Tracking

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/quota-and-admission.feature` — Scenario: *A retroactive quota violation is flagged but never evicts*
  - _Why this counts_: Lowers a tenant's quota below its already-running usage and asserts the deployment is flagged quota-violating while its instance keeps running for 10s.
- **Other test coverage (non-Holmgang, informational only)**: Covered indirectly via `rpc.StoreClientClusterTest`/`StoreNodeTest` — NONE direct
- **Source location(s)**: `StateStore#putQuotaViolation`, `#isQuotaViolating`

#### GIMLE-163 — RBAC Data Persistence (Roles, RoleBindings, Accounts)

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/state-store-persistence.feature` — Scenario: *Tenants, roles, role bindings, and accounts survive a store restart, snapshot included*
  - _Why this counts_: A custom Role, RoleBinding, and Account are each created through the real /roles, /rolebindings, and /accounts HTTP surface, then confirmed readable again after the store process is fully restarted against the same on-disk directory -- all three round-tripping through a real process restart, not merely an in-memory read-back.
- **Other test coverage (non-Holmgang, informational only)**: `StateStoreTest#role_role_binding_and_account_round_trip_through_a_fresh_store_instance`
- **Source location(s)**: `StateStore#putRole`/`#putRoleBinding`/`#putAccount`

#### GIMLE-164 — Client-Facing Store RPC with Leader Redirect & Follow

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *State written through one control-plane replica serves through another*
  - _Why this counts_: Submits a deployment against one control-plane replica of an HA topology and asserts it observes ACTIVE through a *different* replica -- proving reads are consistent across the stateless API tier.
- **Other test coverage (non-Holmgang, informational only)**: `StoreClientClusterTest#a_client_can_read_and_write_through_any_endpoint_once_a_leader_is_elected`, `#a_client_keeps_writing_successfully_across_a_forced_leader_failover`
- **Source location(s)**: `com.gimle.mimir.rpc.StoreClient#sendLeaderOnly`, `#followLeaderHint`

#### GIMLE-165 — Store Read Load Balancing Across Replicas

- **Category**: State Store
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *State written through one control-plane replica serves through another*
  - _Why this counts_: Submits a deployment against one control-plane replica of an HA topology and asserts it observes ACTIVE through a *different* replica -- proving reads are consistent across the stateless API tier.
- **Other test coverage (non-Holmgang, informational only)**: `StoreConnectionTimeoutTest#a_store_client_fails_over_past_a_silent_endpoint_to_one_that_answers`, `rpc/StoreRpcLatencyTest#many_sequential_store_reads_are_not_paying_a_per_call_nagle_stall`
- **Source location(s)**: `StoreClient#sendRead`

#### GIMLE-166 — Store Node Leader-Only Write Gating

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Store Node Leader-Only Write Gating" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `StoreNodeTest#a_non_leader_rejects_a_propose_with_not_leader_and_no_hint_yet`, `#a_non_leader_rejects_a_heartbeat_a_lease_acquire_and_a_lease_release`, `#a_non_leader_rejects_an_add_server_request_with_not_leader`
- **Source location(s)**: `com.gimle.mimir.rpc.StoreNode#handlePropose`, `#handlePutHeartbeat`, `#handleAcquireOrRenewLease`, `#handleAddServer`, `#notLeaderResponse`

#### GIMLE-167 — Store Client Connection Timeout Bounds

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Store Client Connection Timeout Bounds" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `StoreConnectionTimeoutTest#a_connection_that_accepts_but_never_responds_times_out_instead_of_blocking_forever`
- **Source location(s)**: `com.gimle.mimir.rpc.StoreConnection`

#### GIMLE-168 — Store RPC Wire Codec

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Store RPC Wire Codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `StoreCodecTest#round_trips_through_streams`, `#round_trips_a_weighted_autoscale_policy_with_every_weight_present`, `#round_trips_an_account_result_carrying_a_password_hash`
- **Source location(s)**: `com.gimle.mimir.rpc.StoreCodec`

#### GIMLE-169 — RBAC Authorization Engine

- **Category**: Internal-Infra
- **Status**: Modified  _(Node self-service extended to grant a gimle:nodes principal cluster-wide read-only access to ResourceKind.SERVICE and ResourceKind.NETWORK_POLICY (previously denied outright, which broke NetworkPolicyRelay's and Bifrost's own polling with a 403).)_
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "RBAC Authorization Engine" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AuthorizerTest#a_principal_with_no_binding_and_no_group_is_denied_everything`, `#an_operator_group_member_is_allowed_everything_via_the_implicit_cluster_admin_binding`, `#a_custom_role_bound_to_a_user_grants_exactly_its_declared_permissions`, `#a_tenant_scoped_permission_only_matches_its_own_tenant`, `#a_node_may_act_on_its_own_node_and_log_endpoints_with_no_role_binding_at_all`, `#a_node_is_denied_another_nodes_endpoints`, `#a_binding_referencing_a_role_that_no_longer_exists_grants_nothing`, `#a_node_may_read_the_cluster_wide_service_and_network_policy_sets_with_no_binding_at_all`, `#a_node_may_never_write_or_delete_a_service_or_network_policy`; `ApiServerNodeServiceAndNetworkPolicyAuthzTest` (`gimle-controlplane`) exercises the same grant through the real mTLS/RBAC HTTP layer.
- **Source location(s)**: `com.gimle.mimir.authz.Authorizer#authorize`, `#isNodeSelfService`, `#resolveRole`

#### GIMLE-170 — Node-Tenant Assignment Check

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Node-Tenant Assignment Check" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AuthorizerTest#a_node_with_an_active_assignment_for_the_tenant_is_assigned`, `#a_node_with_no_assignment_for_the_tenant_is_not_assigned`, `#a_node_with_no_assignments_at_all_is_not_assigned`
- **Source location(s)**: `Authorizer#isTenantAssignedToNode`

#### GIMLE-171 — Five-Field Cron Schedule Evaluator

- **Category**: Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/workload-manifests.feature` — Scenario: *A CronJob schedule fires on the day-of-month/day-of-week OR quirk*
  - _Why this counts_: Builds a schedule from today's own real UTC date -- today's weekday but a deliberately different day of month, both fields restricted -- and confirms the CronJobReconciler actually schedules a run from it, which is only possible if CronSchedule.mostRecentDueInstant applies the documented OR quirk (either restricted field matching is enough) rather than requiring both.
- **Other test coverage (non-Holmgang, informational only)**: `CronScheduleTest#day_of_month_and_day_of_week_both_restricted_combine_with_or`, `#range_and_step_combine`, `#comma_list_matches_any_listed_value`, `#wrong_field_count_throws`, `#inverted_range_throws`, `#zero_step_throws`
- **Source location(s)**: `com.gimle.mimir.cron.CronSchedule`

#### GIMLE-172 — Deployment Manifest Parsing (incl. Autoscale & Disruption Budget)

- **Category**: Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/workload-manifests.feature` — Scenario: *A weighted autoscale policy is accepted, an unreplaceable disruption budget is rejected*
  - _Why this counts_: Submits a Deployment manifest with a full weighted autoscale block (mode, per-signal weights) through the real /deployments admission surface and confirms it is accepted, then submits maxUnavailable: 0, maxSurge: 0 on another and confirms the real 400 rejection -- both DeploymentManifestParser's weighted-policy parsing and DisruptionBudget's own invariant, against the real HTTP API.
- **Other test coverage (non-Holmgang, informational only)**: `DeploymentManifestParserTest#parses_a_weighted_autoscale_block_with_per_signal_weights`, `#accepts_a_nonzero_max_surge`, `#rejects_max_unavailable_0_with_no_max_surge_to_rescue_it`, `#accepts_max_unavailable_0_paired_with_a_nonzero_max_surge_for_a_pure_surge_rollout`, `DisruptionBudgetTest#max_unavailable_and_max_surge_must_not_both_be_0`
- **Source location(s)**: `com.gimle.mimir.manifest.DeploymentManifestParser`, `DeploymentSpec`, `AutoscalePolicy`, `DisruptionBudget`

#### GIMLE-173 — DaemonSet Manifest Parsing (Anti-Affinity/Surge Rejection)

- **Category**: Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/workload-manifests.feature` — Scenario: *A DaemonSet rejects anti-affinity and nonzero surge but accepts zero surge*
  - _Why this counts_: Submits three DaemonSet manifests through the real /daemonsets admission surface: placement.antiAffinity: true and disruption.maxSurge: 1 are each rejected with 400, while maxSurge: 0 is accepted -- exercising DaemonSetManifestParser's own reject-outright posture for both fields against the real API, not just its unit tests.
- **Other test coverage (non-Holmgang, informational only)**: `DaemonSetManifestParserTest#placement_anti_affinity_field_is_rejected_outright`, `#disruption_max_surge_field_is_rejected_outright_if_nonzero`, `#disruption_max_surge_field_set_to_0_is_accepted`, `#rejects_a_max_unavailable_of_0`
- **Source location(s)**: `com.gimle.mimir.manifest.DaemonSetManifestParser`, `DaemonSetSpec`

#### GIMLE-174 — Job / CronJob Manifest Parsing

- **Category**: Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/workload-manifests.feature` — Scenario: *A CronJob applies sensible defaults and rejects an invalid schedule or concurrency policy*
  - _Why this counts_: Submits a CronJob manifest with no explicit backoffLimit through the real /cronjobs admission surface and confirms it is accepted (the parser's own default applies), then confirms a malformed cron expression and an unknown concurrencyPolicy value are each rejected with a real 400.
- **Other test coverage (non-Holmgang, informational only)**: `CronJobManifestParserTest#parses_a_minimal_manifest_defaulting_backoff_limit_and_concurrency_policy`, `#invalid_cron_schedule_throws`, `#unknown_concurrency_policy_throws`, `JobManifestParserTest#parses_a_minimal_manifest_defaulting_backoff_limit_to_six`
- **Source location(s)**: `com.gimle.mimir.manifest.CronJobManifestParser`, `JobManifestParser`, `CronJobSpec`, `ConcurrencyPolicy`

#### GIMLE-175 — StatefulSet Manifest Parsing

- **Category**: Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/workload-manifests.feature` — Scenario: *A StatefulSet accepts zero replicas and rejects a negative count*
  - _Why this counts_: Submits a StatefulSet manifest with replicas: 0 through the real /statefulsets admission surface and confirms it is accepted, then confirms replicas: -1 is rejected with a real 400 -- StatefulSetSpec's own boundary against the real HTTP API.
- **Other test coverage (non-Holmgang, informational only)**: `StatefulSetManifestParserTest#parses_a_minimal_manifest`, `#zero_replicas_is_legal`, `#negative_replicas_throws`, `#parses_a_vessel_block`
- **Source location(s)**: `com.gimle.mimir.manifest.StatefulSetManifestParser`, `StatefulSetSpec`

#### GIMLE-176 — Kind-Dispatching Manifest Parser

- **Category**: Config
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/workload-manifests.feature` — Scenario: *An unrecognized manifest kind is rejected via the dispatching parser*
  - _Why this counts_: Submits a manifest with kind: Bogus through the real /deployments admission surface and confirms the real 400 rejection, which can only come from ManifestParser's own kind-dispatch switch falling through to GimleManifestException.unknownKind.
- **Other test coverage (non-Holmgang, informational only)**: `ManifestParserTest#kind_deployment_dispatches_to_deployment_manifest_parser`
- **Source location(s)**: `com.gimle.mimir.manifest.ManifestParser`

#### GIMLE-177 — Shared Domain Binary Codec

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Shared Domain Binary Codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `DomainCodecTest#a_vessel_spec_round_trips_through_the_wire`, `#an_absent_vessel_spec_round_trips_as_empty`, `#a_deployment_spec_with_a_vessel_round_trips`
- **Source location(s)**: `com.gimle.mimir.codec.DomainCodec`

#### GIMLE-178 — Store Process Bootstrap with TLS Rotation Ticker

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Store Process Bootstrap with TLS Rotation Ticker" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `com.gimle.mimir.StoreMain`

#### GIMLE-179 — Store/Raft Metrics Instrumentation

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Store/Raft Metrics Instrumentation" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `StoreMain` (`instrumentedStoreNode`), `com.gimle.observability.StoreMetrics`

#### GIMLE-180 — module-info JPMS Boundary for gimle-mimir

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "module-info JPMS Boundary for gimle-mimir" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `mimir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `/home/user/gimle/gimle-mimir/src/main/java/module-info.java`

#### GIMLE-565 — Norn deterministic virtual-time Raft fault-injection simulation

- **Category**: Raft Consensus / Internal-Infra / Testing
- **Status**: New  _(newly discovered since the baseline scan; see commit `6bd450f`)_
- **Coverage**: Not Covered
- **Gap note**: Deliberately incompatible with Holmgang's own model, not merely untested by it: Norn is an in-process, virtual-time harness over real `RaftNode` objects with no sockets and no `RaftTransport` (see `NornCluster`'s own javadoc) -- its entire value is running far more election/partition/recovery activity per real second than a live-timer test could afford. Holmgang's model is the opposite: real, separately-spawned OS subprocesses talking over real sockets at real wall-clock speed (`raft-resilience.feature` already covers the real-process equivalent: killing a real store node/leader mid-workload). A Cucumber scenario cannot replay one of Norn's seeded virtual-time fault schedules against a real Holmgang cluster without rebuilding Norn's whole in-process/virtual-clock mechanism on top of real subprocesses -- at which point it would no longer be Norn. This gap is a permanent, structural one, not a backlog item.
- **Other test coverage (non-Holmgang, informational only)**: `NornRaftSimulationTest#raft_safety_invariants_hold_across_many_seeded_fault_schedules` — 20 seeds x 40 rounds, asserting Election Safety and Log Matching after every round plus eventual liveness after each seed's storm ends
- **Source location(s)**: `gimle-mimir/src/test/java/com/gimle/mimir/raft/NornCluster.java`, `NornScheduler.java`, `NornRaftSimulationTest.java` (added in commit `6bd450f`, "test: add Norn deterministic Raft fault-injection simulation")

#### GIMLE-572 — NetworkPolicySpec durable persistence through StoreClient

- **Category**: Networking/Security
- **Status**: New  _(newly added as part of the Service/Bifrost/Skald/gateway/fabric-tenant-check network model work)_
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/network-policy.feature` — Scenario: *A network policy created through one control-plane replica is visible through another*
  - _Why this counts_: POSTs a NetworkPolicySpec to control-plane replica 0's real /networkpolicies API, then reads it back through replica 1's own independent HTTP API -- both replicas share nothing but gimle-mimir, so the read only succeeds if NetworkPolicyRegistry is genuinely backed by the replicated store rather than an in-memory map private to the replica that handled the write, and the tenantId/allowedCallerTenantIds content is asserted to match, not just presence.
- **Other test coverage (non-Holmgang, informational only)**: `NetworkPolicyRegistryTest`, `ApiServerNetworkPoliciesTest` (multi-replica visibility test) -- see requirements-matrix.json for detail
- **Source location(s)**: `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/networkpolicy/NetworkPolicyRegistry.java`

#### GIMLE-582 — Deployment `configMapRefs` field with admission-time collision rejection

- **Category**: Configuration Management
- **Status**: New  _(newly added as part of the ConfigMap kind (optimistic concurrent writes) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Cucumber .feature scenario exercises configMapRefs admission against a real cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `DeploymentManifestParserTest` (parses `configMapRefs:`, absent field defaults to empty, non-string entry rejected); `DomainCodecTest` (`configMapRefs` round-trips through the wire); `ConfigMapRefsPluginTest` (empty refs allowed with no store reads, no-tenantId rejected, unknown reference rejected, two refs colliding rejected, a ref colliding with flat config rejected, a clean reference allowed)
- **Source location(s)**: `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentSpec.java`, `DeploymentManifestParser.java`, `ManifestFields.java` (`configMapRefs`), `gimle-mimir/src/main/java/com/gimle/mimir/codec/DomainCodec.java` (`configMapRefs` wire encoding), `gimle-controlplane/src/main/java/com/gimle/controlplane/admission/ConfigMapRefsPlugin.java`

#### GIMLE-589 — Deployment `secretMapRefs` field with admission-time collision rejection

- **Category**: Secrets Management
- **Status**: New  _(newly added as part of the SecretMap kind (Fafnir-native, v1) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises `secretMapRefs` admission or collision rejection against a real running cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `SecretMapRefsPluginTest` covers empty refs, no-tenant rejection, unknown-name rejection, cross-SecretMap key collision, SecretMap-vs-ConfigMap collision, SecretMap-vs-flat-config collision, and SecretMap-vs-flat-secret collision. `DomainCodecTest`/`DeploymentManifestParserTest` cover the wire/YAML round trip.
- **Source location(s)**: `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentSpec.java`, `DeploymentManifestParser.java`, `gimle-mimir/src/main/java/com/gimle/mimir/codec/DomainCodec.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/admission/SecretMapRefsPlugin.java`

#### GIMLE-601 — ControllerRevision history and Deployment/StatefulSet/DaemonSet rollback

- **Category**: Workload Lifecycle
- **Status**: New  _(newly added as part of the ControllerRevision revision-history and rollback work)_
- **Coverage**: Not Covered
- **Gap note**: A `rollback.feature` Cucumber scenario (`gimle-holmgang/src/test/resources/features/rollback.feature`, tag `@holmgang @rollback`) and its step definition (`DeploymentSteps.isRolledBackToThePreviousRevision`, `ClusterApi.rollbackDeployment`) were added alongside this work, but could not be executed to confirm coverage: gimle-holmgang transitively depends on gimle-hilmir, which uses the JDK 24+ `java.lang.classfile` API -- unavailable in the JDK 21 toolchain this scan ran under. Run `mvn -pl gimle-holmgang verify -Psmoke -Dcucumber.filter.tags="@rollback"` (per the project's JDK 25 toolchain) and flip this to Covered once it passes.
- **Other test coverage (non-Holmgang, informational only)**: `ControllerRevisionTest`, `StateStoreTest` (append/list/get, retention pruning, snapshot round-trip), `DomainCodecTest`/`RaftCodecTest` (wire round-trip for all three embedded spec kinds), `ApiServerDeploymentRollbackTest`, `ApiServerStatefulSetDaemonSetRollbackTest` -- all real, no mocks (real `StateStore`/`ApiServer`/`HttpClient`).
- **Source location(s)**: `gimle-mimir/src/main/java/com/gimle/mimir/store/ControllerRevision.java`, `gimle-mimir/src/main/java/com/gimle/mimir/raft/StateMutation.java` (`AppendControllerRevision`), `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java` (`putControllerRevision`/`listControllerRevisions`/`getControllerRevision`), `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java` (`handleRollbackDeployment`/`handleRollbackStatefulSet`/`handleRollbackDaemonSet`)

### gimle-fabric

#### GIMLE-181 — Same-Worker Direct Invocation Tier

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a service registered in the same worker's local registry; When lookup(Class) is called; Then the local registry's instance is returned directly, bypassing the catalog entirely.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServiceRegistryTest#same_worker_tier_wins_over_same_machine_and_remote`
- **Source location(s)**: `com.gimle.fabric.registry.FabricServiceRegistry#lookup`

#### GIMLE-182 — Same-Machine Unix-Domain-Socket Invocation Tier

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A consumer completes a real fabric call to a provider*
  - _Why this counts_: Deploys greeter-provider and greeter-consumer on the single-node 'minimal' topology and asserts the consumer's log shows a real cross-worker (same-machine, UDS-tier) fabric call reply.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServiceRegistryTest#same_machine_tier_wins_over_remote_when_both_are_idle`, `FabricTransportTlsTest#same_machine_unix_domain_socket_path_ignores_tls_config`
- **Source location(s)**: `FabricServiceRegistry#resolveAddress`, `FabricServer#listen`

#### GIMLE-183 — Cross-Machine TCP Invocation Tier

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a provider on a different node's worker; When lookup resolves to that endpoint; Then the call is dispatched over TCP, TLS when configured.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServiceRegistryTest#least_outstanding_requests_prefers_the_idle_endpoint`, `FabricTransportTlsTest#cross_machine_invocation_succeeds_over_mtls`
- **Source location(s)**: `FabricServiceRegistry#resolveAddress`, `FabricClient#call`, `FabricServer#listenTls`

#### GIMLE-184 — Locality-Aware Load Balancing with Spillover

- **Category**: Load Balancing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given several same-machine endpoints saturated and a remote endpoint with spare capacity; When lookup selects a candidate; Then the remote tier is admitted; when a same-machine endpoint is idle, remote is never consulted.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServiceRegistryTest#same_machine_tier_spills_over_to_remote_once_saturated`, `#an_open_breaker_on_every_same_machine_endpoint_spills_over_to_a_healthy_remote_endpoint`
- **Source location(s)**: `FabricServiceRegistry#localityAwareCandidates`, `#effectiveLoad`

#### GIMLE-185 — Least-Outstanding-Requests Selection

- **Category**: Load Balancing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given two candidates, one busier; When select is called; Then the less-loaded candidate is chosen; ties round-robin.
- **Other test coverage (non-Holmgang, informational only)**: `LeastOutstandingRequestsSelectorTest#selects_the_candidate_with_fewest_outstanding_requests`, `#ties_are_broken_round_robin`, `#end_never_goes_negative`, `FabricServiceRegistryTest#least_outstanding_requests_prefers_the_idle_endpoint`
- **Source location(s)**: `com.gimle.fabric.balance.LeastOutstandingRequestsSelector`

#### GIMLE-186 — Per-Endpoint Circuit Breaker

- **Category**: Circuit Breaking
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an endpoint's error rate crosses errorRateThreshold; When another call is attempted; Then the breaker opens and excludes it; after cooldown it half-opens for one trial call.
- **Other test coverage (non-Holmgang, informational only)**: `CircuitBreakerTest#opens_once_error_rate_crosses_threshold_over_the_window`, `#half_opens_after_cooldown_and_allows_exactly_one_trial`, `#half_open_success_closes_the_breaker`, `#half_open_failure_reopens_the_breaker`, `FabricServiceRegistryTest#a_failing_endpoints_breaker_opens_and_is_excluded`
- **Source location(s)**: `com.gimle.fabric.breaker.CircuitBreaker`

#### GIMLE-187 — Circuit Breaker Exponential Cooldown Backoff

- **Category**: Circuit Breaking
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a breaker that re-opens repeatedly; When each re-open occurs; Then the cooldown doubles up to a ceiling; a successful half-open trial resets it.
- **Other test coverage (non-Holmgang, informational only)**: `CircuitBreakerTest#repeated_reopens_double_the_effective_cooldown`, `#the_doubling_backoff_stops_at_its_documented_ceiling`, `#a_successful_half_open_trial_resets_the_backoff_to_the_base_cooldown`
- **Source location(s)**: `CircuitBreaker#effectiveCooldown`, `#MAX_BACKOFF_SHIFT`

#### GIMLE-188 — Panic-Mode Ejection Floor

- **Category**: Circuit Breaking
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a lookup whose candidates are more than maxEjectionPercent open-breaker; When selectAllowedCandidate runs; Then every candidate is admitted back in; no known exporter anywhere still throws GimleClusterException.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServiceRegistryTest#all_endpoints_failing_still_yields_a_candidate_once_the_panic_threshold_is_crossed`, `#no_known_exporter_anywhere_throws_gimle_cluster_exception`
- **Source location(s)**: `FabricServiceRegistry#selectAllowedCandidate`, `#DEFAULT_MAX_EJECTION_PERCENT`

#### GIMLE-189 — Application-Exception vs Transport-Failure Breaker Scoring

- **Category**: Circuit Breaking
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a remote endpoint whose method throws an application exception; When the call completes with InvokeError; Then the breaker records a success; only genuine transport failures count against it.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServiceRegistryTest#an_endpoint_whose_method_throws_an_application_exception_does_not_open_its_breaker`
- **Source location(s)**: `FabricServiceRegistry#invokeOverWire`

#### GIMLE-190 — Gossip-Propagated Service Catalog

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a local registration on one catalog; When its payload is applied to a second catalog's onReceived; Then the second reflects the registration; a stale delta at a lower version is ignored.
- **Other test coverage (non-Holmgang, informational only)**: `ServiceCatalogTest#a_local_registration_is_immediately_visible`, `#gossip_deltas_round_trip_and_merge_into_a_second_catalog`, `#a_stale_delta_at_a_lower_version_is_ignored`, `#two_different_workers_can_both_export_the_same_interface`
- **Source location(s)**: `com.gimle.fabric.catalog.ServiceCatalog`, `CatalogDelta`, `PiggybackExtension`

#### GIMLE-191 — Catalog Eviction on Gossip-Detected Node Death

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a member gossip converges on as DEAD; When ServiceCatalog#onMembershipChange receives it; Then endpointsForInterface no longer returns any of its endpoints; once ALIVE again, endpoints reappear with no re-registration.
- **Other test coverage (non-Holmgang, informational only)**: `GossipMemberTest#a_node_marked_dead_via_gossip_has_its_catalog_entries_evicted_without_a_breaker_trip`
- **Source location(s)**: `ServiceCatalog#onMembershipChange`, `#unavailableNodes`

#### GIMLE-192 — Cross-Tenant Service Export Access Control

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an export restricted to tenant "acme"; When a caller from a different tenant looks it up; Then lookup returns empty; with defaultDenyCrossTenant on, an unscoped export is reachable only by an untenanted caller.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServiceRegistryTest#a_caller_belonging_to_an_allowed_tenant_reaches_a_restricted_export`, `#a_caller_from_a_different_tenant_cannot_reach_a_restricted_export`, `#a_tenanted_caller_cannot_reach_an_unrestricted_export_with_default_deny_cross_tenant_on`, `FabricServiceRegistryInvokeByNameTest#a_caller_from_a_different_tenant_cannot_invoke_a_restricted_export_by_name`
- **Source location(s)**: `FabricServiceRegistry#permitsUnderTenantPolicy`, `#defaultDenyCrossTenant`

#### GIMLE-193 — Runtime Name-Driven Cross-Tier Invocation (invokeByName)

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a route naming an interface/version/method/param types; When invokeByName is called; Then it resolves the same tier/breaker/tenant logic dispatched by name; an unresolvable method name fails clearly.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServiceRegistryInvokeByNameTest#a_same_worker_registration_is_invoked_directly_by_name`, `#a_same_machine_registration_is_invoked_over_the_wire_by_name`, `#a_remote_registration_is_invoked_over_the_wire_by_name`, `#wrong_param_type_names_fail_clearly_rather_than_hanging_or_matching_a_wrong_overload`
- **Source location(s)**: `FabricServiceRegistry#invokeByName`, `#invokeLocalByName`, `com.gimle.fabric.transport.ReflectiveDispatch`

#### GIMLE-194 — Inbound Call Dispatch with Bounded Concurrency

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a target module with a bounded ModuleWorkExecutor; When more concurrent inbound calls arrive than allowed; Then extra calls queue; ModuleContext's in-flight counter reflects real inbound calls.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServerTest#a_real_inbound_call_is_visible_in_the_targets_in_flight_count_while_it_runs`, `#concurrent_calls_are_bounded_by_the_targets_executor_not_run_unbounded`, `#real_calls_are_recorded_in_the_targets_worker_metrics_including_errors`
- **Source location(s)**: `com.gimle.fabric.transport.FabricServer#invokeLocally`, `#invokeBounded`, `ModuleWorkExecutor`

#### GIMLE-195 — Distributed Trace Propagation Across Fabric Hops

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a caller with an active span and baggage; When it invokes a remote service; Then the callee starts a child span parented on the caller's real span, observing the same baggage.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServerTest#baggage_from_the_caller_survives_an_inbound_call_into_the_handler`, `#has_remote_span_distinguishes_a_real_caller_span_from_the_no_active_span_marker`, `FabricServerGlobalTracingTest#a_call_with_no_active_caller_span_starts_a_fresh_valid_trace_not_the_all_zero_marker`, `transport/FabricCodecTest#round_trips_a_non_empty_tracestate_and_baggage`
- **Source location(s)**: `FabricServiceRegistry#captureTrace`, `#encodeTraceState`, `#encodeBaggage`, `FabricServer#startChildSpanContext`, `com.gimle.fabric.trace.TraceContext`

#### GIMLE-196 — Fabric Transport over Mutual TLS with Hot Cert Reload

- **Category**: Service Fabric
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given fabric configured for mTLS; When a cross-machine invocation is made; Then it succeeds over TLS; a client trusting a different CA is rejected; reload lets a fresh connection succeed without restart.
- **Other test coverage (non-Holmgang, informational only)**: `FabricTransportTlsTest#cross_machine_invocation_succeeds_over_mtls`, `#cross_machine_call_is_rejected_when_client_trusts_a_different_ca`
- **Source location(s)**: `FabricServer#listenTls`, `#reloadTlsMaterial`, `FabricClient#call`

#### GIMLE-197 — Fabric Call Timeout Enforcement

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Fabric Call Timeout Enforcement" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fabric`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FabricClientTest#a_peer_that_accepts_but_never_responds_times_out_within_the_configured_bound`, `#a_refused_connection_fails_fast_without_waiting_out_the_timeout`
- **Source location(s)**: `com.gimle.fabric.transport.FabricClient#runBounded`, `#DEFAULT_TIMEOUT`

#### GIMLE-198 — Fabric Frame Wire Codec

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Fabric Frame Wire Codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fabric`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FabricCodecTest#round_trips_through_streams`, `#round_trips_a_non_empty_tracestate_and_baggage`, `#rejects_an_oversized_length_prefix_before_allocating`, `#rejects_a_forged_huge_param_count_before_allocating`
- **Source location(s)**: `com.gimle.fabric.transport.FabricCodec`

#### GIMLE-199 — Cross-JVM Object Marshalling

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Cross-JVM Object Marshalling" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fabric`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `com.gimle.fabric.transport.ObjectMarshalling`

#### GIMLE-200 — SWIM Gossip Membership Protocol (Ping/PingReq/Ack)

- **Category**: Gossip Membership
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given two node agents configured with each other as a seed; When they join; Then each discovers the other via direct ping/ack; a killed member converges to DEAD via direct probe timeout escalating to indirect PingReq relays.
- **Other test coverage (non-Holmgang, informational only)**: `GossipMemberTest#two_nodes_discover_each_other_via_join`, `#a_killed_member_converges_to_dead_across_the_rest`, `#a_lone_node_with_no_seeds_starts_as_a_new_cluster`, `#a_single_unreachable_seed_is_a_legitimate_bootstrap_not_an_error`, `#multiple_unreachable_seeds_throw_gimle_cluster_exception`
- **Source location(s)**: `com.gimle.fabric.cluster.GossipMember#tick`, `#pingRandomMember`, `#escalate`, `#handle`

#### GIMLE-201 — SWIM Self-Refutation via Incarnation Bump

- **Category**: Gossip Membership
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a member observes a piggyback entry naming itself as SUSPECT; When processed; Then it bumps its own incarnation and re-gossips as ALIVE; a stale suspicion below the current incarnation is ignored.
- **Other test coverage (non-Holmgang, informational only)**: `GossipMemberTest#a_member_refutes_a_suspicion_of_itself_by_bumping_incarnation`, `#a_stale_suspicion_below_the_current_incarnation_is_ignored`
- **Source location(s)**: `GossipMember#refuteIfNeeded`, `#mergeOne`

#### GIMLE-202 — Lifeguard-Style Local Health Multiplier

- **Category**: Gossip Membership
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a node whose probes repeatedly time out; When each timeout occurs; Then its local health multiplier increases (clamped at a ceiling); a successfully resolved probe decays it back down.
- **Other test coverage (non-Holmgang, informational only)**: `GossipMemberTest#the_local_health_multiplier_clamps_rather_than_growing_unbounded`
- **Source location(s)**: `GossipMember#bumpLocalHealthMultiplier`, `#decayLocalHealthMultiplier`, `#MAX_LOCAL_HEALTH_MULTIPLIER`

#### GIMLE-203 — Round-Robin Bounded-Coverage Probe Target Selection

- **Category**: Gossip Membership
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given N live members besides self; When N consecutive ticks each call nextProbeTarget; Then every live member is visited exactly once before the queue reshuffles.
- **Other test coverage (non-Holmgang, informational only)**: `GossipMemberTest#probe_target_selection_visits_every_live_member_within_one_cycle`
- **Source location(s)**: `GossipMember#nextProbeTarget`, `#probeOrder`

#### GIMLE-204 — Anti-Entropy Full-State Sync

- **Category**: Gossip Membership
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a change piggyback alone never delivered to a lagging node; When the antiEntropyInterval elapses and a sync fires to a random peer; Then the full table is exchanged and the lagging node picks up the missed change.
- **Other test coverage (non-Holmgang, informational only)**: `GossipMemberTest#anti_entropy_sync_delivers_a_change_piggyback_alone_cannot_carry`
- **Source location(s)**: `GossipMember#maybeSyncWithRandomMember`, `#currentFullState`, `SwimMessage.SyncRequest`/`SyncResponse`

#### GIMLE-205 — Dead-Member Reaping

- **Category**: Gossip Membership
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a member DEAD longer than deadMemberReapAfter; When the next tick's reapExpiredDeadMembers runs; Then the member is removed entirely.
- **Other test coverage (non-Holmgang, informational only)**: `GossipMemberTest#a_long_dead_member_is_eventually_forgotten_not_kept_forever`
- **Source location(s)**: `GossipMember#reapExpiredDeadMembers`

#### GIMLE-206 — Gossip over Mutual DTLS with Deterministic Initiator Selection

- **Category**: Gossip Membership
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given two nodes configured for DTLS gossip; When they exchange pings; Then they discover each other over mutual DTLS, with only the lexicographically-lower-addressed side originating the handshake; different-CA members never become mutually aware; reloaded material lets a node reach a new peer.
- **Other test coverage (non-Holmgang, informational only)**: `GossipMemberDtlsTest#two_nodes_discover_each_other_over_mutual_dtls`, `#a_killed_member_still_converges_to_dead_over_dtls`, `#members_trusting_different_cas_never_become_mutually_aware`, `#a_member_reaches_a_new_peer_over_dtls_after_reloading_rotated_material`
- **Source location(s)**: `GossipMember#sendSecure`, `#isDesignatedInitiator`, `#handleSecureDatagram`, `#reloadDtlsMaterial`, `com.gimle.fabric.cluster.DtlsPeerSession`

#### GIMLE-207 — SWIM Wire Codec

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "SWIM Wire Codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fabric`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SwimCodecTest#round_trips_through_a_datagram`, `#a_forged_huge_piggyback_count_fails_cleanly_instead_of_preallocating`, `#rejects_an_unrecognized_version_before_decoding_the_tag`
- **Source location(s)**: `com.gimle.fabric.cluster.SwimCodec`

#### GIMLE-208 — Service Catalog Delta Wire Codec

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Service Catalog Delta Wire Codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fabric`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ServiceCatalogCodecTest#round_trips_a_catalog_delta`, `#round_trips_an_empty_delta_list`, `#a_forged_huge_delta_count_fails_cleanly_instead_of_preallocating`
- **Source location(s)**: `com.gimle.fabric.catalog.ServiceCatalogCodec`

#### GIMLE-209 — Reflective Cross-Module Method Dispatch

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Reflective Cross-Module Method Dispatch" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fabric`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: Exercised indirectly through `FabricServiceRegistryInvokeByNameTest`/`FabricServerTest` — NONE dedicated
- **Source location(s)**: `com.gimle.fabric.transport.ReflectiveDispatch#findInterface`, `#resolveParamTypes`, `FabricServer#invokeLocally`

#### GIMLE-210 — module-info JPMS Boundary for gimle-fabric

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "module-info JPMS Boundary for gimle-fabric" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fabric`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `/home/user/gimle/gimle-fabric/src/main/java/module-info.java`

#### GIMLE-567 — Fabric listener-side tenant re-check on inbound service calls

- **Category**: Fabric / Multi-tenancy
- **Status**: New  _(newly added as part of the Service/Bifrost/Skald/gateway/fabric-tenant-check network model work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario drives two tenants' modules through a real cluster and asserts a cross-tenant fabric call that bypasses FabricServiceRegistry's own caller-side filter is still rejected by the receiving worker. To close: a scenario would need two tenant-scoped modules, one exporting a tenant-restricted service and one holding another tenant's identity, plus a step that dials the raw ServiceEndpoint address directly (not through the registry's own lookup) to prove FabricServer's own listener-side re-check independently rejects it -- something no current .feature file attempts.
- **Other test coverage (non-Holmgang, informational only)**: `FabricServerTest` (4 tests: direct-dial bypass rejected, untenanted caller rejected against a restricted export, allowed-tenant caller permitted, unrestricted export permits any caller); `FabricCodecTest`'s callerTenantId round-trip coverage
- **Source location(s)**: `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricServer.java`, `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricFrame.java`, `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricCodec.java`, `gimle-core/src/main/java/com/gimle/core/exception/GimleFabricAuthorizationException.java`

#### GIMLE-574 — Per-deployment-scoped NetworkPolicySpec enforcement

- **Category**: Networking/Security
- **Status**: New  _(closes the per-deployment-scoping gap left open by the earlier tenant-wide-only NetworkPolicySpec enforcement lane)_
- **Coverage**: Not Covered
- **Gap note**: Holmgang's Cucumber suite has no scenario proving deployment-scoped (as opposed to tenant-wide) NetworkPolicySpec enforcement end to end. To close: extend a network-policy feature file with a scenario declaring a policy scoped to one of two deployments in the same tenant and asserting only calls to that deployment are restricted.
- **Other test coverage (non-Holmgang, informational only)**: `NetworkPolicyRuleTest`, `HttpNetworkPolicySourceTest`, `FabricServerTest` (3 new deployment-scoping cases), `ControlMessageCodecTest` -- see requirements-matrix.json for detail
- **Source location(s)**: `gimle-core/src/main/java/com/gimle/core/tenant/NetworkPolicyRule.java`, `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricServer.java`

### gimle-controlplane

#### GIMLE-211 — First-fit-decreasing bin-packing scheduler

- **Category**: Scheduling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given multiple registered nodes with differing free memory/CPU; When a replica is placed for a deployment; Then the node with the most free memory (tie-broken by free CPU) among tier-eligible, uncordoned nodes is chosen.
- **Other test coverage (non-Holmgang, informational only)**: `SchedulerTest` — `places_on_the_only_feasible_node`, `prefers_the_node_with_more_free_capacity`, `throws_when_no_node_has_enough_free_capacity`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/schedule/Scheduler.java`, `NodeCandidate.java`

#### GIMLE-212 — Isolation-tier placement filtering

- **Category**: Scheduling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a node that does not declare support for TIER_2; When a TIER_2 replica is placed; Then that node is excluded and placement fails if no other node supports the tier.
- **Other test coverage (non-Holmgang, informational only)**: `SchedulerTest` — `rejects_a_node_that_does_not_support_the_requested_tier`, `throws_when_no_node_supports_the_requested_tier`
- **Source location(s)**: `Scheduler.filterByTier`

#### GIMLE-213 — Node cordon exclusion

- **Category**: Scheduling
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/scheduling.feature` — Scenario: *A cordoned node blocks placement until uncordoned*
  - _Why this counts_: Cordons the sole node, submits a deployment, asserts it stays unplaced for 10s, then uncordons and asserts it reaches ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: `SchedulerTest` — `cordon_excludes_a_cordoned_node_from_placement`, `cordon_fails_outright_when_every_capable_node_is_cordoned`; `DaemonSetReconcilerTest#cordoning_a_node_removes_its_assignment_on_the_next_tick`
- **Source location(s)**: `Scheduler.filterByCordon`; `ApiServer.handleCordon` (`POST /nodes/{id}/cordon|uncordon`)

#### GIMLE-214 — Strict anti-affinity across nodes

- **Category**: Scheduling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given anti-affinity is requested and every eligible node already runs a replica; When another replica is placed; Then placement fails outright rather than co-locating.
- **Other test coverage (non-Holmgang, informational only)**: `SchedulerTest` — `anti_affinity_excludes_nodes_already_running_a_replica_of_the_same_deployment`, `anti_affinity_fails_outright_rather_than_placing_on_an_occupied_node`
- **Source location(s)**: `Scheduler.filterByAntiAffinity`, `GimleSchedulingException.antiAffinityViolated`

#### GIMLE-215 — Tier 2/3 node-level tenant isolation

- **Category**: Scheduling / Multi-tenancy
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a node already running Tier 2 instances for tenant A; When a Tier 2 replica for tenant B is scheduled; Then that node is excluded; if every candidate hosts a different tenant, placement fails outright.
- **Other test coverage (non-Holmgang, informational only)**: `SchedulerTest` — `tenant_isolation_permits_a_node_already_running_the_same_tenant`, `tenant_isolation_fails_outright_when_every_capable_node_hosts_a_different_tenant`
- **Source location(s)**: `Scheduler.filterByTenant`/`enforcesTenantIsolation`; `DeploymentReconciler.buildCandidates`

#### GIMLE-216 — Required node-label placement constraint

- **Category**: Scheduling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a manifest declares placement.requiredLabels; When placement runs; Then only nodes carrying every required label are candidates; fails outright if none qualify.
- **Other test coverage (non-Holmgang, informational only)**: `SchedulerTest` — `required_labels_excludes_a_node_missing_one_of_them`, `required_labels_fails_outright_when_no_capable_node_carries_them`
- **Source location(s)**: `Scheduler.filterByLabels`

#### GIMLE-217 — StatefulSet sticky node placement

- **Category**: Scheduling / Orchestration
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given index 3 was previously placed on node-A; When index 3 needs re-placement; Then it is placed on node-A again if eligible, and fails outright (never relocated) if node-A is unavailable.
- **Other test coverage (non-Holmgang, informational only)**: `SchedulerTest` — `sticky_placement_returns_the_sticky_node_even_when_a_roomier_node_exists`, `sticky_placement_fails_outright_rather_than_choosing_a_different_node_when_sticky_is_gone`
- **Source location(s)**: `Scheduler.place` (stickyNodeId overload), `Scheduler.placeSticky`, `GimleSchedulingException.stickyNodeUnavailable`

#### GIMLE-218 — DaemonSet eligible-node enumeration (`eligibleNodes`)

- **Category**: Scheduling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given several nodes, some cordoned, some missing required labels; When DaemonSetReconciler computes eligible nodes; Then every node passing tier/cordon/tenant/label filters is returned, no single-winner pick.
- **Other test coverage (non-Holmgang, informational only)**: `SchedulerTest` — `eligible_nodes_returns_every_node_that_passes_every_filter`, `eligible_nodes_returns_an_empty_list_rather_than_throwing_when_nothing_qualifies`; `DaemonSetReconcilerTest#places_an_assignment_on_every_registered_node`
- **Source location(s)**: `Scheduler.eligibleNodes`; `DaemonSetReconciler.reconcileDaemonSet`

#### GIMLE-219 — Deployment replica reconciliation (level-triggered)

- **Category**: Reconciliation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A tenant-scoped module deploys, reads its secret, and is cleanly removed*
  - _Why this counts_: Submits a real tenant-scoped Deployment manifest via the HTTP API, asserts the instance's own log shows a Fafnir-delivered secret value, then deletes the deployment and asserts it drains away completely -- exercising CRUD, placement reconciliation, secret delivery, and orphan cleanup together.
  - `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` — Scenario: *A control plane cut off from the store stops serving and reconverges after heal*
  - _Why this counts_: Uses a Loki fault proxy to sever control-plane-replica-1's link to every store, asserts it stops serving within 30s while replica 0 keeps working, then asserts reconvergence through replica 1 on heal.
- **Other test coverage (non-Holmgang, informational only)**: `DeploymentReconcilerTest` — `creates_assignments_for_every_missing_index_when_capacity_exists`, `an_arbitrary_starting_snapshot_converges_the_same_as_a_fresh_reconcile`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/DeploymentReconciler.java`

#### GIMLE-220 — Deployment scale-down

- **Category**: Reconciliation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given 5 assignments and replicas lowered to 3; When DeploymentReconciler ticks; Then assignments for indices 3 and 4 are removed.
- **Other test coverage (non-Holmgang, informational only)**: `DeploymentReconcilerTest#scale_down_removes_assignments_at_or_beyond_the_new_replica_count`
- **Source location(s)**: `DeploymentReconciler.reclaimStaleAssignments`

#### GIMLE-221 — Artifact-hash drift detection at reconcile time

- **Category**: Reconciliation / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Artifact-hash drift detection at reconcile time" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `DeploymentReconcilerTest` — `places_new_instances_when_the_recorded_artifact_hash_still_matches_the_jar_on_disk`, `refuses_to_place_new_instances_once_the_jar_on_disk_no_longer_matches_the_recorded_hash`
- **Source location(s)**: `DeploymentReconciler.validateArtifact`

#### GIMLE-222 — Rolling update via mismatched-index migration

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/rolling-update.feature` — Scenario: *Zero-downtime rollout under a surge budget*
  - _Why this counts_: Rolls a 2-replica deployment to a genuinely rebuilt v1.1.0 artifact under maxUnavailable=1/maxSurge=1, holding an invariant that at least 1 instance stays ACTIVE throughout, then asserts both instances end on the new version.
- **Other test coverage (non-Holmgang, informational only)**: `DeploymentReconcilerRollingUpdateTest` (convergence-from-arbitrary-state coverage present)
- **Source location(s)**: `DeploymentReconciler.handleRollingUpdate`, `mismatchedAssignments`, `isReady`

#### GIMLE-223 — Rolling update surge (maxSurge)

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/rolling-update.feature` — Scenario: *Zero-downtime rollout under a surge budget*
  - _Why this counts_: Rolls a 2-replica deployment to a genuinely rebuilt v1.1.0 artifact under maxUnavailable=1/maxSurge=1, holding an invariant that at least 1 instance stays ACTIVE throughout, then asserts both instances end on the new version.
- **Other test coverage (non-Holmgang, informational only)**: `DeploymentReconcilerSurgeTest`
- **Source location(s)**: `DeploymentReconciler.handleSurge`, `nextFreeSurgeIndex`, `StateMutation.PutAssignment` (`renamedFromInstanceIndex`)

#### GIMLE-224 — Node-death instance reclamation (`ReplicaCountReconciler`)

- **Category**: Reconciliation / Self-healing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an assignment's node hasn't heartbeated within nodeDarkTimeout, persisted beyond placementGracePeriod; When ReplicaCountReconciler ticks; Then the assignment is removed, freeing re-placement.
- **Other test coverage (non-Holmgang, informational only)**: `ReplicaCountReconcilerTest` (grace-period and persisted-state convergence tests present)
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/ReplicaCountReconciler.java`

#### GIMLE-225 — Persisted grace-period bookkeeping (survives leader failover)

- **Category**: Reconciliation / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Persisted grace-period bookkeeping (survives leader failover)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ReplicaCountReconcilerTest`; `HealthReconcilerTest#backoff_state_survives_a_reconciler_reconstruction_against_the_same_store`
- **Source location(s)**: `ReplicaCountReconciler` via `ReconcilerInstanceState`/`StoreReader.getReconcilerInstanceState`

#### GIMLE-226 — Unhealthy-instance backoff-gated reschedule (`HealthReconciler`)

- **Category**: Reconciliation / Self-healing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an instance's heartbeat reports alive=false or lifecycleState=FAILED; When HealthReconciler ticks; Then it is rescheduled after growing delay; once maxAttemptsPerWindow is exhausted it's marked permanently failed.
- **Other test coverage (non-Holmgang, informational only)**: `HealthReconcilerTest` — `an_unhealthy_instance_is_rescheduled_once_its_backoff_elapses`, `repeated_failures_across_reschedules_eventually_exhaust_the_budget_and_stop_retrying`, `converges_correctly_from_an_arbitrary_mix_of_persisted_backoff_states`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/HealthReconciler.java`, `RestartTracker`

#### GIMLE-227 — Readiness-only failures never trigger reschedule

- **Category**: Reconciliation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an instance reports ready=false but alive=true and lifecycleState != FAILED; When HealthReconciler ticks; Then no reschedule action is taken.
- **Other test coverage (non-Holmgang, informational only)**: `HealthReconcilerTest#readiness_alone_never_triggers_a_reschedule`
- **Source location(s)**: `HealthReconciler.isHealthy`

#### GIMLE-228 — Tenant quota drift detection (`QuotaReconciler`)

- **Category**: Reconciliation / Multi-tenancy
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/quota-and-admission.feature` — Scenario: *A retroactive quota violation is flagged but never evicts*
  - _Why this counts_: Lowers a tenant's quota below its already-running usage and asserts the deployment is flagged quota-violating while its instance keeps running for 10s.
- **Other test coverage (non-Holmgang, informational only)**: `QuotaReconcilerTest` — `marks_a_deployment_violating_when_its_tenant_exceeds_quota`, `clears_a_violation_once_the_quota_is_raised_again_convergence_from_arbitrary_state`, `proposes_exactly_once_when_a_violation_is_introduced_then_nothing_more_while_it_persists`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/QuotaReconciler.java`, `TenantUsage`

#### GIMLE-229 — Horizontal autoscaling — multi-signal (`AutoscaleReconciler`)

- **Category**: Reconciliation / Scheduling
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/autoscale.feature` — Scenario: *Request-rate load scales the provider up*
  - _Why this counts_: Drives real Gatling HTTP load through the deployed greeter-load-generator, forcing AutoscaleReconciler's request-rate signal (CPU target deliberately unreachable) to scale the deployment from 1 to 2 replicas.
- **Other test coverage (non-Holmgang, informational only)**: `AutoscaleReconcilerTest` — `scales_up_by_one_replica_per_tick_under_sustained_high_utilization`, `queue_depth_alone_can_drive_scale_up_when_cpu_is_under_target`, `converges_correctly_from_an_arbitrary_out_of_range_persisted_replica_count`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/autoscale/AutoscaleReconciler.java`

#### GIMLE-230 — Autoscaling WEIGHTED combination mode

- **Category**: Reconciliation / Scheduling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given combinationMode=WEIGHTED with per-signal weights; When two signals disagree; Then effective count is driven by the weighted-average blended ratio.
- **Other test coverage (non-Holmgang, informational only)**: `AutoscaleReconcilerTest` — `weighted_mode_blends_two_signals_instead_of_taking_the_max`, `weighted_mode_with_no_weights_configured_behaves_like_an_unweighted_average`
- **Source location(s)**: `AutoscaleReconciler.computeWeightedIdeal`

#### GIMLE-231 — DaemonSet reconciliation and rolling update

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an old module version on 3 nodes; When the spec's moduleId changes; Then nodes migrate one at a time, waiting for readiness.
- **Other test coverage (non-Holmgang, informational only)**: `DaemonSetReconcilerTest#rolling_update_replaces_one_node_at_a_time_and_waits_for_readiness`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/DaemonSetReconciler.java`

#### GIMLE-232 — DaemonSet dark-node placement-safety grace period

- **Category**: Reconciliation / Self-healing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a node's heartbeat is stale but within nodeDarkTimeout+placementGracePeriod; When DaemonSetReconciler ticks; Then the assignment is left in place.
- **Other test coverage (non-Holmgang, informational only)**: `DaemonSetReconcilerTest#a_replica_on_a_dark_but_not_yet_timed_out_node_is_not_relocated`, `cordoning_a_dark_node_still_removes_its_assignment_immediately`
- **Source location(s)**: `DaemonSetReconciler.isMerelyDarkWithinGracePeriod`

#### GIMLE-233 — StatefulSet OrderedReady placement

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given index 0 is not yet ready; When StatefulSetReconciler ticks; Then index 1 is never placed.
- **Other test coverage (non-Holmgang, informational only)**: `StatefulSetReconcilerTest` — `does_not_place_index_one_until_index_zero_reports_ready`, `places_index_one_once_index_zero_becomes_ready`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/StatefulSetReconciler.java`

#### GIMLE-234 — StatefulSet one-index-at-a-time scale-down

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given 5 indices and replicas lowered to 2; When StatefulSetReconciler ticks; Then only index 4 is removed this tick.
- **Other test coverage (non-Holmgang, informational only)**: `StatefulSetReconcilerTest#scale_down_removes_the_highest_index_first_one_at_a_time`
- **Source location(s)**: `StatefulSetReconciler.scaleDownOneIndexIfNeeded`

#### GIMLE-235 — JobRun run-to-completion reconciliation

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a Job's current attempt fails and backoffLimit allows another; When JobReconciler ticks; Then a new attempt is placed; once exhausted, the Job is marked FAILED.
- **Other test coverage (non-Holmgang, informational only)**: `JobReconcilerTest` — `a_failed_observation_retries_the_next_attempt_when_backoff_budget_remains`, `exhausting_the_backoff_limit_marks_the_job_permanently_failed`, `an_arbitrary_starting_snapshot_with_two_coexisting_runs_converges_to_the_highest_attempt`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/JobReconciler.java`

#### GIMLE-236 — Job active-deadline enforcement

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given activeDeadline=10min and the run has been active 11min; When JobReconciler ticks; Then the run is removed and the Job marked FAILED mid-attempt.
- **Other test coverage (non-Holmgang, informational only)**: `JobReconcilerTest#exceeding_the_active_deadline_marks_the_job_permanently_failed_even_mid_attempt`
- **Source location(s)**: `JobReconciler.reconcileCurrentRun`

#### GIMLE-237 — CronJob schedule-driven Job materialization

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a CronJobSpec with schedule "* * * * *" and no prior lastSchedule; When first ticked; Then baseline is recorded with no retroactive burst; on the next due tick a Job named "{name}-{epochSeconds}" is materialized.
- **Other test coverage (non-Holmgang, informational only)**: `CronJobReconcilerTest` — `first_tick_records_a_baseline_and_materializes_nothing`, `a_due_firing_materializes_a_job_named_with_the_epoch_second_suffix`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/CronJobReconciler.java`

#### GIMLE-238 — CronJob concurrency policy (Allow/Forbid/Replace)

- **Category**: Reconciliation / Orchestration
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given the previous firing is still non-terminal and concurrencyPolicy=FORBID; When a new firing is due; Then it is skipped and logged.
- **Other test coverage (non-Holmgang, informational only)**: `CronJobReconcilerTest` — `concurrency_policy_forbid_skips_a_firing_while_the_previous_one_is_still_running`, `concurrency_policy_replace_removes_the_still_running_job_before_placing_the_new_one`, `concurrency_policy_allow_lets_a_new_firing_run_alongside_a_still_running_one`
- **Source location(s)**: `CronJobReconciler.materializeFiring`

#### GIMLE-239 — CronJob manual trigger (`gimle cronjob trigger`)

- **Category**: Reconciliation / API Server
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a CronJob exists; When POST /cronjobs/{name}/trigger is called; Then a Job is materialized immediately, and cronJobLastSchedule is left untouched.
- **Other test coverage (non-Holmgang, informational only)**: `CronJobReconcilerTest#trigger_now_fires_immediately_and_does_not_touch_last_schedule_time`; `ApiServerTest#trigger_fires_immediately_and_the_generated_job_appears_on_the_jobs_list`
- **Source location(s)**: `CronJobReconciler.triggerNow`; `ApiServer.handleCronJobTrigger`

#### GIMLE-240 — CronJob missed-schedule starting-deadline handling

- **Category**: Reconciliation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a firing's startingDeadline is exceeded by processing time; When CronJobReconciler ticks; Then the firing is logged as missed with no Job materialized.
- **Other test coverage (non-Holmgang, informational only)**: Covered indirectly by `CronJobReconcilerTest`'s convergence/missed-schedule handling
- **Source location(s)**: `CronJobReconciler.reconcileCronJob`

#### GIMLE-241 — Level-triggered orphan cleanup across every workload kind

- **Category**: Reconciliation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A tenant-scoped module deploys, reads its secret, and is cleanly removed*
  - _Why this counts_: Submits a real tenant-scoped Deployment manifest via the HTTP API, asserts the instance's own log shows a Fafnir-delivered secret value, then deletes the deployment and asserts it drains away completely -- exercising CRUD, placement reconciliation, secret delivery, and orphan cleanup together.
- **Other test coverage (non-Holmgang, informational only)**: `DeploymentReconcilerTest#deleting_a_deployment_removes_all_of_its_assignments`, `JobReconcilerTest#deleting_a_job_removes_its_orphaned_run_on_the_next_tick`, `DaemonSetReconcilerTest#deleting_a_daemonset_removes_its_orphaned_assignments`, `StatefulSetReconcilerTest#deleting_a_statefulset_removes_its_orphaned_assignment_and_sticky_binding`
- **Source location(s)**: each reconciler's orphan-sweep (`DeploymentReconciler`, `JobReconciler`, `DaemonSetReconciler`, `StatefulSetReconciler`)

#### GIMLE-242 — Reconciler-leader election via non-replicated lease

- **Category**: Orchestration / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Reconciler-leader election via non-replicated lease" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: Indirect (multi-replica smoke/holmgang tests)
- **Source location(s)**: `ControlPlaneMain` — `leaseTick`, `StoreClient.tryAcquireOrRenewLease`

#### GIMLE-243 — Independent-executor ticking (lease/reconcile/cert-rotation isolation)

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Independent-executor ticking (lease/reconcile/cert-rotation isolation)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ControlPlaneSchedulingTest` — `cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_is_blocked_forever`, `cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_throws_every_time`
- **Source location(s)**: `ControlPlaneMain.scheduleIndependentTickers`

#### GIMLE-244 — JPMS module boundary for gimle-controlplane

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "JPMS module boundary for gimle-controlplane" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-controlplane/src/main/java/module-info.java`

#### GIMLE-245 — Admission chain extension point

- **Category**: Admission / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Admission chain extension point" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AdmissionChainTest` — `empty_chain_allows_the_spec_unchanged`, `a_rejecting_plugin_short_circuits_every_later_plugin`, `a_later_plugin_sees_the_spec_an_earlier_plugin_mutated`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/admission/AdmissionChain.java`, `AdmissionPlugin.java`, `AdmissionDecision.java`

#### GIMLE-246 — Tenant resource quota admission check

- **Category**: Admission / Multi-tenancy
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/quota-and-admission.feature` — Scenario: *An over-quota deployment is rejected at admission*
  - _Why this counts_: Submits a deployment against a starved tenant's quota and asserts the submission is rejected outright with HTTP 409.
- **Other test coverage (non-Holmgang, informational only)**: `TenantQuotaPluginTest` — `deployment_exceeding_its_tenants_quota_is_rejected`, `a_deployment_fitting_at_replicas_alone_but_not_with_surge_is_rejected`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/admission/TenantQuotaPlugin.java`, `TenantUsage.java`

#### GIMLE-247 — Organization-specific policy-as-data admission (`policy.maxReplicasPerDeployment`)

- **Category**: Admission / Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given tenant T has policy.maxReplicasPerDeployment=10; When a submission requests 15 replicas; Then admission rejects citing the ceiling.
- **Other test coverage (non-Holmgang, informational only)**: `PolicyConfigPluginTest` — `a_deployment_exceeding_the_configured_ceiling_is_rejected`, `a_malformed_policy_value_is_rejected_rather_than_silently_ignored`, `exactly_at_the_ceiling_is_allowed`
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/admission/PolicyConfigPlugin.java`

#### GIMLE-248 — Registry-coordinate artifact admission (Andvari integration)

- **Category**: Admission / Artifact Registry
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/registry-deploy.feature` — Scenario: *A pushed module deploys by coordinate with no artifact path*
  - _Why this counts_: Pushes a real jar to Andvari, then submits a manifest with a coordinate and no artifactPath, asserting it resolves through the real agent pull-through cache and reaches ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariClientTest`; end-to-end in `gimle-smoke-tests/AndvariRegistryIT`
- **Source location(s)**: `ApiServer.admissionArtifact`; `AndvariClient.head`/`HeadOutcome`; `ArtifactResolver`

#### GIMLE-249 — PUT-time re-tenanting double-authorization

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given deployment D belongs to tenant A; When a caller with write access only to tenant B PUTs D with tenantId=B; Then the request is rejected unless the caller also has write access to tenant A.
- **Other test coverage (non-Holmgang, informational only)**: Embedded in `ApiServerAuthzTest`'s broader RBAC flow coverage
- **Source location(s)**: `ApiServer.dispatchResourceRequest` (PUT branch)

#### GIMLE-250 — RBAC-gated resource CRUD across every workload kind

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a principal with no grant for ResourceKind.JOB; When GET/PUT/DELETE against /jobs/{name}; Then every request is rejected with 403.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerAuthzTest`, `ApiServerEndpointsAuthzTest`
- **Source location(s)**: `ApiServer.requireAuthorized`, `dispatchResourceRequest`; `com.gimle.mimir.authz.Authorizer`

#### GIMLE-251 — WRITE/DELETE decisions durably audited (opt-in READ auditing)

- **Category**: Authorization / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "WRITE/DELETE decisions durably audited (opt-in READ auditing)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerAuthzTest#configured_read_resource_kinds_are_audited_allowed_and_denied_reads`
- **Source location(s)**: `ApiServer.requireAuthorized`, `recordAuditEvent`, `parseAuditReadResourceKinds`; `handleAudit`

#### GIMLE-252 — `gimle-system` reserved-tenant operator-only guard

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a caller holds a broad but non-operator-group grant; When writing under tenantId=gimle-system; Then rejected 403 regardless of ordinary RBAC outcome.
- **Other test coverage (non-Holmgang, informational only)**: Exercised within `ApiServerAuthzTest`'s broader RBAC test set
- **Source location(s)**: `ApiServer.rejectIfReservedSystemTenant`, `isOperatorCaller`

#### GIMLE-253 — Node-scoped self-service authorization (`gimle:nodes` group)

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a certificate carrying gimle:nodes and CN=node-42; When calling POST /nodes/node-42/heartbeat; Then it succeeds via the self-service short-circuit; a request against node-99 is rejected.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerAuthzTest`; `NodeBootstrapCsrTest#fresh_agent_obtains_a_signed_certificate_and_completes_mtls_handshake`
- **Source location(s)**: `ApiServer.handleNode`; `com.gimle.mimir.authz.Authorizer`

#### GIMLE-254 — Node-tenant-scoped `/endpoints/*` read access

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given node-42 has an active instance for tenant T; When it calls GET /endpoints/{workload-of-T}; Then access is granted; a request for a tenant it has no assignment for is rejected 403.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerEndpointsAuthzTest` — `a_node_with_an_active_assignment_for_the_deployments_tenant_may_read_its_endpoints`, `a_node_with_no_assignment_for_the_deployments_tenant_is_forbidden`
- **Source location(s)**: `ApiServer.authorizeEndpointsRead`, `Authorizer.isTenantAssignedToNode`

#### GIMLE-255 — mTLS-authenticated HTTP API server with client-cert principal resolution

- **Category**: Internal-Infra / API Server
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *The cluster functions end to end over mutual TLS*
  - _Why this counts_: Boots the whole cluster under the 'mtls' topology (every hop mTLS, agent CSR bootstrap via token) and asserts a tenant secret still round-trips end to end.
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *An anonymous client cannot write*
  - _Why this counts_: Asserts a request with no client certificate against the mTLS-mode API server is rejected with 401.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerTlsTest` — `https_request_with_a_valid_client_cert_succeeds`, `https_request_without_a_client_cert_is_rejected`
- **Source location(s)**: `ApiServer.createHttpServer`, `resolvePrincipal`, `peerCertificate`

#### GIMLE-256 — Console session login/logout/session cookie flow

- **Category**: Authorization / API Server
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a valid bootstrap Account; When POST /auth/login with correct credentials; Then a signed, HttpOnly, SameSite=Strict session cookie is set.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerAuthzTest#login_session_and_logout_round_trip_with_no_client_certificate_at_all`
- **Source location(s)**: `ApiServer.handleAuthLogin`/`handleAuthLogout`/`handleAuthSession`, `SessionTokens`

#### GIMLE-257 — Login throttling (address + username keyed)

- **Category**: Authorization / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Login throttling (address + username keyed)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: Exercised via shared `LoginThrottle` mechanics (`FafnirObservabilityTest`'s equivalent); no isolated ApiServer-level test method found
- **Source location(s)**: `ApiServer.loginThrottle`, `handleAuthLogin`; `com.gimle.core.throttle.LoginThrottle`

#### GIMLE-258 — Bootstrap node join via single-use token + CSR

- **Category**: Internal-Infra / API Server (PKI)
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Bootstrap node join via single-use token + CSR" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `NodeBootstrapCsrTest` — `fresh_agent_obtains_a_signed_certificate_and_completes_mtls_handshake`, `invalid_bootstrap_token_is_rejected`; `BootstrapTokenRegistryTest` — `issued_token_can_be_consumed_exactly_once`, `expired_token_cannot_be_consumed`
- **Source location(s)**: `ApiServer.handleNodeJoinRequest`, `BootstrapTokenRegistry`

#### GIMLE-259 — Operator-approval-gated CSR flow

- **Category**: Internal-Infra / API Server (PKI)
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Operator-approval-gated CSR flow" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `HumanOperatorCsrTest` — `operator_csr_sits_pending_until_an_existing_operator_approves_it`, `approve_without_a_client_certificate_is_rejected`
- **Source location(s)**: `ApiServer.handleOperatorJoinRequest`, `PendingCsrStore`, `handleApprove`

#### GIMLE-260 — Certificate rotation (self-rotation and subject-preserving renewal)

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Certificate rotation (self-rotation and subject-preserving renewal)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `CertificateRotationTest` — `rotation_issues_a_new_cert_for_the_same_subject_and_it_works_immediately`, `rotation_csr_with_a_mismatched_subject_is_rejected`
- **Source location(s)**: `ApiServer.handleRotationRequest`; `checkAndRotateOwnCertificateIfDue`, `reloadTlsMaterial`

#### GIMLE-261 — Zero-downtime TLS material reload

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Zero-downtime TLS material reload" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: Exercised via `CertificateRotationTest`; analogous pattern in `FafnirServerTlsTest`/`AndvariServerTlsTest`
- **Source location(s)**: `ApiServer.reloadTlsMaterial`

#### GIMLE-262 — `/secrets/*` byte-for-byte proxy to Fafnir

- **Category**: Secrets Management / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "`/secrets/*` byte-for-byte proxy to Fafnir" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerAuthzTest#config_and_secret_permissions_are_independently_enforced_and_filtered`, `a_secret_survives_key_rotation_and_new_secrets_use_the_rotated_key`
- **Source location(s)**: `ApiServer.handleSecretsProxy`; `FafnirClient.forward`

#### GIMLE-263 — Secrets key rotation trigger (proxied)

- **Category**: Secrets Management
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Rotating the secrets key re-encrypts an existing secret under the new key id*
  - _Why this counts_: Calls POST /secrets/rotate-key through the real control-plane proxy twice and asserts each call returns a different active key id.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerAuthzTest#a_secret_survives_key_rotation_and_new_secrets_use_the_rotated_key`
- **Source location(s)**: `ApiServer.handleRotateSecretsKey`; `FafnirClient.rotateKey`; `FafnirCrypto.rotate`

#### GIMLE-264 — CONFIG/SECRET resource-kind separation on one underlying store

- **Category**: Config / Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a caller has CONFIG:WRITE but not SECRET:WRITE; When PUT /config/{tenant}/{key} with encrypted=true; Then the write is rejected because it routes authorization through ResourceKind.SECRET.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerAuthzTest#config_and_secret_permissions_are_independently_enforced_and_filtered`
- **Source location(s)**: `ApiServer.handleConfig`, `isFafnirManagedSecretKey`

#### GIMLE-265 — `/artifacts/*` streaming proxy to Andvari

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "`/artifacts/*` streaming proxy to Andvari" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariClientTest`; end-to-end in `gimle-smoke-tests/AndvariRegistryIT`
- **Source location(s)**: `ApiServer.handleArtifactsProxy`; `AndvariClient.forward`

#### GIMLE-266 — Andvari-client multi-endpoint failover with rotation

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Andvari-client multi-endpoint failover with rotation" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariClientTest` — `a_head_call_fails_over_from_an_unreachable_endpoint_to_a_reachable_one`, `unreachable_on_every_configured_endpoint_answers_unreachable`
- **Source location(s)**: `AndvariClient.withEndpoints`, `head`, `forward`

#### GIMLE-267 — `/logs/*` proxy with Muninn fallback

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "`/logs/*` proxy with Muninn fallback" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerLogsFallbackTest` — `a_node_with_no_registration_falls_through_to_muninn_when_configured`, `a_registered_but_unreachable_agent_falls_through_to_muninn_when_configured`, `a_live_reachable_agent_is_still_served_directly_not_from_muninn`, `a_muninn_fallback_fails_over_to_a_second_configured_endpoint_when_the_first_is_unreachable`
- **Source location(s)**: `ApiServer.handleInstanceLogsProxy`/`handleNodeLogsProxy`, `MuninnClient.get`

#### GIMLE-268 — `/metrics-history/*` and `/traces-history/*` Muninn proxy

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "`/metrics-history/*` and `/traces-history/*` Muninn proxy" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerMetricsHistoryTest#proxies_to_muninn_forwarding_the_since_query_parameter`, `ApiServerTracesHistoryTest`
- **Source location(s)**: `ApiServer.handleMetricsHistory`/`handleTracesHistory`/`handleHistoryProxy`

#### GIMLE-269 — Node registration, heartbeat, and assignment-fetch API

- **Category**: API Server / Orchestration
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/module-system.feature` — Scenario: *A hook that always throws on start never reaches ACTIVE*
  - _Why this counts_: The scenario explicitly asserts node-1 is registered before deploying, and every scenario's successful placement across the whole track implicitly depends on the node's heartbeat/assignment-fetch loop continuing to report it as live and assignable.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerTest` (transitive); `gimle-agent` integration tests
- **Source location(s)**: `ApiServer.handleRegister`/`handleHeartbeat`/`handleAssignments`

#### GIMLE-270 — Unified `AssignedInstance` wire shape across every workload kind

- **Category**: Internal-Infra / API Server
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Unified `AssignedInstance` wire shape across every workload kind" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerEndpointsTest` — `a_job_run_is_listed_under_its_own_endpoints_route`, `a_daemonset_assignment_is_listed_under_its_own_endpoints_route`, `a_statefulset_assignment_is_listed_under_its_own_endpoints_route`
- **Source location(s)**: `ApiServer.handleAssignments`

#### GIMLE-271 — Reserved system-tenant auto-seeding

- **Category**: Multi-tenancy / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Reserved system-tenant auto-seeding" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: Implicit in test fixtures bootstrapping ApiServer
- **Source location(s)**: `ApiServer.seedReservedSystemTenantIfAbsent`

#### GIMLE-272 — Bundled web console static serving

- **Category**: API Server / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Bundled web console static serving" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerConsoleContractTest`
- **Source location(s)**: `ApiServer.serveConsole`, `com.gimle.core.web.BundledSpa`, `ControlPlaneMain`

#### GIMLE-273 — Per-endpoint request metrics instrumentation

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Per-endpoint request metrics instrumentation" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `controlplane`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerMetricsTest`
- **Source location(s)**: `ApiServer.instrument`, `com.gimle.observability.ApiServerMetrics`

#### GIMLE-274 — Deployment/Job/DaemonSet/StatefulSet CRUD manifest API

- **Category**: API Server
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A tenant-scoped module deploys, reads its secret, and is cleanly removed*
  - _Why this counts_: Submits a real tenant-scoped Deployment manifest via the HTTP API, asserts the instance's own log shows a Fafnir-delivered secret value, then deletes the deployment and asserts it drains away completely -- exercising CRUD, placement reconciliation, secret delivery, and orphan cleanup together.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerTest` — extensive PUT/GET/DELETE/list round-trip coverage
- **Source location(s)**: `ApiServer.handleDeployment`/`handleJob`/`handleDaemonSet`/`handleStatefulSet`

#### GIMLE-275 — Per-deployment and per-instance metrics rollup

- **Category**: API Server / Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a deployment has 3 ready instances reporting different request rates; When GET /metrics; Then a per-deployment row with the averaged rates is returned.
- **Other test coverage (non-Holmgang, informational only)**: Covered within `ApiServerConsoleContractTest`/`ApiServerTest`
- **Source location(s)**: `ApiServer.handleMetrics`, `average`

#### GIMLE-566 — Service abstraction: stable name, CRUD API, and endpoint reconciliation

- **Category**: Reconciliation / Service Fabric
- **Status**: New  _(newly added as part of the Service/Bifrost/Skald/gateway/fabric-tenant-check network model work)_
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/service-fabric.feature` — Scenario: *A Service resolves a live endpoint for a hosted module reporting its own port*
  - _Why this counts_: Declares a Service (POST /services) fronting a real deployed module, then polls GET /services/{name}/endpoints until it reports that module's live instance -- proving the Service CRUD surface and the reconciler-backed endpoint resolution genuinely converge against a real cluster, not just in ServiceReconcilerTest's simulated store snapshots.
- **Other test coverage (non-Holmgang, informational only)**: `ServiceReconcilerTest` (6 convergence tests from arbitrary starting states); `ApiServerServicesTest` (11 tests over the real HTTP surface); `ServiceRegistryTest`
- **Source location(s)**: `gimle-mimir/src/main/java/com/gimle/mimir/manifest/ServiceSpec.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/ServiceReconciler.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/service/ServiceRegistry.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/service/ServiceEndpointResolver.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java`

#### GIMLE-581 — ConfigMap store and API with optimistic-concurrency writes

- **Category**: Configuration Management
- **Status**: New  _(newly added as part of the ConfigMap kind (optimistic concurrent writes) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Cucumber .feature scenario exercises ConfigMap CRUD or its optimistic-concurrency write path against a real cluster -- coverage here requires a .feature scenario driving the real gimle binary, not the real JUnit/InProcessStore coverage that already exists (ConfigMapStoreTest's/ApiServerConfigMapTest's own real-store round trips do not count, per this file's own metadata.coverageRule).
- **Other test coverage (non-Holmgang, informational only)**: `ConfigMapStoreTest` (version bump by exactly one, PUT full-replace vs PATCH merge, PATCH `expectedVersion=0` create case, stale-`expectedVersion` conflict carries the right snapshot, delete, get-on-absent, `getMany` batch filtering, and a 6-thread concurrency regression proving no writer's key is silently dropped under contention); `ApiServerConfigMapTest` (full HTTP round trip, batch-get via `?names=`, 409 on stale `expectedVersion`, 400 on writing a `configmap:`-prefixed key through `/config/*`, a ConfigMap row never leaks into a plain `/config/*` listing); `ApiServerConfigMapAuthzTest` (RBAC gating via `ResourceKind.CONFIGMAP` over real mTLS)
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/configmap/ConfigMap.java`, `ConfigMapCodec.java`, `ConfigMapWriteResult.java`, `ConfigMapStore.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java` (`/configmaps/*` routes, the `configmap:` reserved-prefix guard on `/config/*`, the `?names=` batch-get shape), `gimle-core/src/main/java/com/gimle/core/authz/ResourceKind.java` (`CONFIGMAP`), `gimle-core/src/main/java/com/gimle/core/config/ConfigEntry.java` (javadoc noting the `configmap:` synthetic-key convention)

#### GIMLE-590 — `/secretmaps/*` proxy and `ResourceKind.SECRETMAP` RBAC

- **Category**: Secrets Management
- **Status**: New  _(newly added as part of the SecretMap kind (Fafnir-native, v1) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the `/secretmaps/*` proxy or its RBAC gate against a real running cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerSecretMapTest` (plaintext CRUD through the proxy to a real in-process Fafnir), `ApiServerSecretMapAuthzTest` (real mTLS: an operator role may write/read, a no-grant caller gets 403 on both).
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java` (`handleSecretMapsProxy`, `/secretmaps/` route registration, `deploymentAdmissionChain`)

#### GIMLE-599 — `/seal/*` and `/secrets/retire-key` proxy routes

- **Category**: Secrets Management
- **Status**: New  _(newly added as part of the Sealed SecretMap (v2) and key lifecycle work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the control plane's `/seal/*` proxy routes against a real running cluster -- see GIMLE-588's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `ApiServerSealTest` (plaintext proxy round-trip) and `ApiServerSealAuthzTest` (real mTLS/RBAC, including the deliberate no-auth public-key route) cover this in full.
- **Source location(s)**: `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java` (`handleSealPublicKeyProxy`, `handleSealRotateKeyProxy`, `handleSealRetireKeyProxy`, `handleRetireSecretsKeyProxy`, `forwardGlobalAdminRoute`)

### gimle-fafnir

#### GIMLE-276 — AES-256-GCM secret value encryption with versioned key IDs

- **Category**: Secrets Management
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A secret's versions round-trip and a soft delete behaves differently from a hard one*
  - _Why this counts_: Two versions of a secret are written and read back correctly, a real AES-256-GCM round trip through Fafnir's own encrypt/decrypt path.
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Rotating the secrets key re-encrypts an existing secret under the new key id*
  - _Why this counts_: Directly inspects the raw stored ciphertext's own key-id byte before and after two rotations, proving the versioned key id is real, not just that decryption happens to still work.
- **Other test coverage (non-Holmgang, informational only)**: `SecretCipherTest` — `round_trips_plaintext_through_encryption_and_decryption`, `round_trips_through_a_specific_key_id`, `ciphertext_never_contains_the_plaintext_bytes`, `the_same_plaintext_encrypts_differently_each_time_due_to_a_random_iv`
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/SecretCipher.java`

#### GIMLE-277 — Legacy pre-key-id ciphertext format fallback

- **Category**: Secrets Management
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A legacy pre-key-id secret ciphertext still decrypts correctly*
  - _Why this counts_: Plants a secret encrypted in the exact legacy pre-key-id layout (iv || ciphertext-with-tag, no version/key-id prefix) under Fafnir's real key id 0 and asserts it still decrypts correctly through the real read API.
- **Other test coverage (non-Holmgang, informational only)**: `SecretCipherTest` (legacy-format coverage per class javadoc)
- **Source location(s)**: `SecretCipher.decrypt`

#### GIMLE-278 — Local AES-256 key-file generation and loading

- **Category**: Secrets Management
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A secret's versions round-trip and a soft delete behaves differently from a hard one*
  - _Why this counts_: Every secret round trip in this scenario depends on Fafnir's own KeyFileManager.loadOrCreate having generated and loaded a real local AES-256 key at process startup.
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A legacy pre-key-id secret ciphertext still decrypts correctly*
  - _Why this counts_: Loads the same on-disk key file Fafnir itself created (KeyFileManager.loadOrCreate) to construct a legacy ciphertext, proving the loaded key material genuinely matches what the running process uses.
- **Other test coverage (non-Holmgang, informational only)**: `KeyFileManagerTest` — `generates_a_key_on_first_run_and_reuses_it_on_later_runs`, `a_key_loaded_via_a_second_manager_instance_can_decrypt_what_the_first_encrypted`
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/KeyFileManager.java`

#### GIMLE-279 — Key rotation with full-ring persistence (`KeyFileManager.rotate`)

- **Category**: Secrets Management
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Rotating the secrets key re-encrypts an existing secret under the new key id*
  - _Why this counts_: Rotates the key twice and confirms the secret written before either rotation still decrypts correctly afterward -- only possible because KeyFileManager.rotate keeps every prior key file on disk in the ring rather than discarding it.
- **Other test coverage (non-Holmgang, informational only)**: `KeyFileManagerTest#rotate_adds_a_new_active_key_while_keeping_the_old_one_loadable`
- **Source location(s)**: `KeyFileManager.rotate`

#### GIMLE-280 — Key-ring fingerprinting for cross-replica drift detection

- **Category**: Secrets Management / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Key-ring fingerprinting for cross-replica drift detection" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `KeyRingTest` — `fingerprint_does_not_depend_on_keysbyid_map_iteration_order`, `fingerprint_changes_when_key_material_differs`, `fingerprint_changes_after_a_real_rotation_via_keyfilemanager`
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/KeyRing.java`

#### GIMLE-281 — Full-key-rotation re-encryption sweep

- **Category**: Secrets Management
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Rotating the secrets key re-encrypts an existing secret under the new key id*
  - _Why this counts_: After each rotation, the secret's own stored ciphertext is inspected directly and asserted to now carry the newly active key id -- proof FafnirCrypto.rotate's re-encryption walk actually re-encrypted the existing entry, not merely that the old key remained available.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirCryptoTest` — `rotate_reencrypts_every_existing_encrypted_entry_under_the_new_active_key`, `rotate_never_loses_a_previously_encrypted_value_still_decryptable_after_multiple_rounds`, `a_plain_unencrypted_entry_is_untouched_by_rotation`
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirCrypto.java`

#### GIMLE-282 — Versioned secret storage layered over ConfigEntry

- **Category**: Secrets Management
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A secret's versions round-trip and a soft delete behaves differently from a hard one*
  - _Why this counts_: Writes two versions of one secret, lists its versions, and reads back both the latest and an explicit historical version -- the whole key@N/key@meta convention layered over ConfigEntry, driven end to end through the real API.
- **Other test coverage (non-Holmgang, informational only)**: `SecretStoreTest#list_returns_metadata_only_for_every_written_secret_in_the_tenant`; `FafnirServerTest#versions_lists_every_claimed_version_number`, `an_explicit_version_query_parameter_reads_that_historical_value`
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/SecretStore.java`

#### GIMLE-283 — Optimistic-write versioned put with narrow-lease serialization

- **Category**: Secrets Management / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Optimistic-write versioned put with narrow-lease serialization" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SecretStoreTest` (contention scenario per class javadoc)
- **Source location(s)**: `SecretStore.put`

#### GIMLE-284 — Soft delete vs hard delete (`?destroy=true`)

- **Category**: Secrets Management
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *A secret's versions round-trip and a soft delete behaves differently from a hard one*
  - _Why this counts_: Soft-deletes a secret and asserts the latest read is gone while an explicit historical version stays readable, then hard-deletes it (?destroy=true) and asserts even the historical version is gone -- the two delete modes' real, distinct behavior.
- **Other test coverage (non-Holmgang, informational only)**: `SecretStoreTest#soft_delete_marks_the_secret_deleted_but_keeps_every_version_readable_by_number`, `hard_delete_removes_every_version_and_the_metadata_entry_itself`; `FafnirServerTest#soft_deleting_a_secret_hides_it_from_a_default_get_but_versions_remain_readable`
- **Source location(s)**: `SecretStore.softDelete`/`hardDelete`; `FafnirServer.handleDeleteSecret`

#### GIMLE-285 — Fafnir's own independent RBAC re-check (defense-in-depth)

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Fafnir independently authorizes node-scoped secret reads by tenant assignment*
  - _Why this counts_: Hits Fafnir's own port directly, bypassing gimle-controlplane's /secrets/* proxy entirely, so the resulting allow/deny decisions can only come from Fafnir's own independent Authorizer.authorize/isTenantAssignedToNode check, never a forwarded claim.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirSecretsAuthzTest#a_forwarded_principal_who_does_not_actually_hold_the_permission_is_still_forbidden`
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirServer.java`

#### GIMLE-286 — Node-tenant-scoped secret reads (`gimle:nodes`)

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Fafnir independently authorizes node-scoped secret reads by tenant assignment*
  - _Why this counts_: A node's own certificate reads a secret for the tenant it currently has an active instance assignment for (allowed) and a secret for a tenant it has no assignment for (denied with 403), driving Authorizer.isTenantAssignedToNode's real decision both ways.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirSecretsAuthzTest` — `a_node_with_an_active_assignment_for_the_tenant_may_read_its_secrets`, `a_node_with_no_assignment_for_the_tenant_is_forbidden_regardless_of_key`, `a_node_may_never_write_a_secret_even_with_an_active_assignment`
- **Source location(s)**: `FafnirServer.decideAllowed`; `Authorizer.isTenantAssignedToNode`

#### GIMLE-287 — Authorization-failure throttling and dual audit logging

- **Category**: Authorization / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Authorization-failure throttling and dual audit logging" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirObservabilityTest` — `repeated_authorization_failures_from_the_same_principal_are_eventually_throttled`, `a_successful_authorization_clears_prior_recorded_failures`, `audit_log_records_the_decision_without_ever_logging_the_secret_value`
- **Source location(s)**: `FafnirServer.authorizeSecrets`, `recordAudit`, `authzThrottle`

#### GIMLE-288 — Three-tier principal resolution (forwarded header > peer cert > session cookie)

- **Category**: Internal-Infra / Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Three-tier principal resolution (forwarded header > peer cert > session cookie)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirSecretsAuthzTest`; `FafnirServerAuthTest`
- **Source location(s)**: `FafnirServer.resolvePrincipal`

#### GIMLE-289 — mTLS HTTP server with dynamic TLS material reload

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "mTLS HTTP server with dynamic TLS material reload" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirServerTlsTest` — `a_real_mtls_request_with_a_ca_signed_client_cert_succeeds`, `reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server`
- **Source location(s)**: `FafnirServer.reloadTlsMaterial`; `FafnirMain`'s cert-rotation ticker

#### GIMLE-290 — Console session login (Fafnir's own operator dashboard)

- **Category**: API Server
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Fafnir's console session login round-trips and the plaintext session falls back to anonymous*
  - _Why this counts_: A real seeded account logs in to Fafnir's own /auth/login, the session cookie is verified via /auth/session to report that account as logged in, and /auth/logout is confirmed to end the session -- the full login/logout/session round trip.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirServerAuthTest` — `login_session_and_logout_round_trip_with_no_client_certificate_at_all`, `a_wrong_password_is_rejected_with_no_cookie_set`
- **Source location(s)**: `FafnirServer.handleAuthLogin`/`handleAuthLogout`/`handleAuthSession`

#### GIMLE-291 — Plaintext-mode anonymous session carve-out

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/secrets-and-pki.feature` — Scenario: *Fafnir's console session login round-trips and the plaintext session falls back to anonymous*
  - _Why this counts_: Before any login and again after logout, /auth/session on a plaintext-transport Fafnir reports an anonymous session (never 401) -- the plaintext-mode carve-out, distinguished from the real logged-in state the same scenario also exercises.
- **Other test coverage (non-Holmgang, informational only)**: Implicit in `FafnirServerAuthTest`'s plaintext-mode coverage
- **Source location(s)**: `FafnirServer.handleAuthSession`

#### GIMLE-292 — Bundled web console static serving (Fafnir)

- **Category**: API Server / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Bundled web console static serving (Fafnir)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirServerConsoleTest` — `console_static_files_are_served_once_wired`, `the_real_bundled_console_jar_resolves_and_serves_its_own_index_html`
- **Source location(s)**: `FafnirServer.serveConsole`; `FafnirMain`

#### GIMLE-293 — Process status endpoint with key-ring fingerprint

- **Category**: API Server / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Process status endpoint with key-ring fingerprint" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirServerAuthTest#status_reports_uptime_active_key_and_transport_mode`
- **Source location(s)**: `FafnirServer.handleStatus`

#### GIMLE-294 — Muninn metrics/traces shipping

- **Category**: Internal-Infra / Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Muninn metrics/traces shipping" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirMain.java`

#### GIMLE-295 — Fafnir-metrics observability instrumentation

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Fafnir-metrics observability instrumentation" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirObservabilityTest#a_real_request_is_recorded_in_fafnir_metrics`
- **Source location(s)**: `FafnirServer.instrument`, `com.gimle.observability.FafnirMetrics`

#### GIMLE-296 — JPMS module boundary for gimle-fafnir

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "JPMS module boundary for gimle-fafnir" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `fafnir`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-fafnir/src/main/java/module-info.java`

#### GIMLE-588 — SecretMap store and `/secretmaps/*` API

- **Category**: Secrets Management
- **Status**: New  _(newly added as part of the SecretMap kind (Fafnir-native, v1) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the SecretMap store or its `/secretmaps/*` API against a real running cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `SecretMapCodecTest`, `SecretMapStoreTest` (including a concurrency regression test mirroring `ConfigMapStoreTest`'s own), `FafnirServerSecretMapTest` (HTTP-level CRUD, authz, and reserved-prefix rejection).
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secretmap/SecretMapCodec.java`, `SecretMapStore.java`, `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirServer.java` (`/secretmaps/*` routes, `authorizeSecrets` generalized to accept a `ResourceKind`, reserved-prefix guard), `gimle-core/src/main/java/com/gimle/core/authz/ResourceKind.java` (`SECRETMAP`)

#### GIMLE-594 — SecretMap group-version ledger and rollback

- **Category**: Secrets Management
- **Status**: New  _(newly added as part of the SecretMap group-level versioning and rollback work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises SecretMap group-version stamping or rollback against a real running cluster -- see GIMLE-588's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `SecretMapStoreTest` (group-version stamping on set/delete, skip-on-no-change, listGroupVersions ordering, rollback restoring live and deleted keys, leaving newer keys untouched, per-key failure on an unrecoverable hard-deleted key, unknown-target `TargetNotFound`, and a concurrency regression test asserting concurrent `setMany`/`rollback` calls on the same name never corrupt the group-version sequence), `SecretStoreTest` (`listLinearizable` parity with `list`), `FafnirServerSecretMapTest` (HTTP-level `/versions`/`/rollback`, 404 on an unknown group version, 400 on a non-integer body), `ApiServerSecretMapTest` (proxy round-trip for both new routes).
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secretmap/SecretMapStore.java` (`withWriteLease`, `stampGroupVersion`, `listGroupVersions`, `rollback`, `SecretMapGroupVersion`/`SecretMapKeySnapshot`/`RollbackOutcome`), `gimle-fafnir/src/main/java/com/gimle/fafnir/SecretStore.java` (`listLinearizable`), `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirServer.java` (`/versions`, `/rollback` routes and handlers), `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java` (`handleSecretMapsProxy` POST support)

#### GIMLE-597 — Sealed SecretMap envelope crypto and key retirement

- **Category**: Secrets Management
- **Status**: New  _(newly added as part of the Sealed SecretMap (v2) and key lifecycle work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises sealing/unsealing or key retirement against a real running cluster -- see GIMLE-588's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `SealCipherTest`, `SealingKeyRingTest`, `SealingKeyFileManagerTest`, and `KeyFileManagerTest`'s new retirement cases cover the round-trip, rotation, and destructive-retirement behavior in full.
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/SealCipher.java`, `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/SealingKeyRing.java`, `SealingKeyFileManager.java`, `gimle-fafnir/src/main/java/com/gimle/fafnir/secret/KeyFileManager.java` (`retire`), `gimle-fafnir/src/main/java/com/gimle/fafnir/SealingCrypto.java`, `FafnirCrypto.java` (`retire`)

#### GIMLE-598 — `/seal/*` and key-retirement HTTP routes

- **Category**: Secrets Management
- **Status**: New  _(newly added as part of the Sealed SecretMap (v2) and key lifecycle work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the `/seal/*` routes or `/secretmaps/*/seal` against a real running cluster -- see GIMLE-588's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `FafnirServerSealTest` covers the full exit criterion end to end: seal, commit, apply, wrong-tenant/name rejection, and retirement stopping trust.
- **Source location(s)**: `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirServer.java` (`handleSealPublicKey`, `handleSealRotateKey`, `handleSealRetireKey`, `handleRetireSecretsKey`, `handleSealSecretMap`)

### gimle-andvari

#### GIMLE-297 — Immutable, content-addressed artifact store

- **Category**: Artifact Registry
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given moduleId:version was never pushed; When pushed with jar bytes X; Then CREATED; re-pushing identical X is IDENTICAL (no-op); re-pushing different Y is CONFLICT (409).
- **Other test coverage (non-Holmgang, informational only)**: `ArtifactStoreTest` — `an_identical_re_push_is_idempotent`, `a_differing_re_push_is_a_conflict_and_the_stored_bytes_are_untouched`; `AndvariServerTest` — `a_differing_re_push_is_refused_as_immutable`, `an_identical_re_push_is_idempotent`
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/ArtifactStore.java`

#### GIMLE-298 — Streamed, digest-verified push with atomic commit

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Streamed, digest-verified push with atomic commit" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `ArtifactStoreTest` (push mechanics covered by round-trip tests)
- **Source location(s)**: `ArtifactStore.put` (DigestInputStream + ATOMIC_MOVE), `sweepOrphanedTempFiles`

#### GIMLE-299 — Size-limited streaming upload rejection

- **Category**: Artifact Registry
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given -Dgimle.andvari.maxArtifactBytes (default 500 MiB); When a push streams past that many bytes; Then aborted with 413 before writing excess bytes.
- **Other test coverage (non-Holmgang, informational only)**: Implicit in `ArtifactStoreTest`'s put-path coverage
- **Source location(s)**: `ArtifactStore.SizeLimitedInputStream`, `ArtifactTooLargeException`; `AndvariServer.handleUpload`

#### GIMLE-300 — On-disk corruption detection and quarantine

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "On-disk corruption detection and quarantine" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerTest#a_get_against_bytes_corrupted_on_disk_still_serves_them_but_quarantines_the_coordinate`
- **Source location(s)**: `AndvariServer.handleDownload`, `reportIntegrityFailure`; `ArtifactStore.quarantine`

#### GIMLE-301 — Periodic full-store integrity scrub

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Periodic full-store integrity scrub" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `IntegrityScrubberTest` — `a_coordinate_whose_bytes_no_longer_match_its_recorded_digest_is_reported`, `an_uncorrupted_coordinate_is_never_reported`, `a_version_missing_its_jar_is_skipped_rather_than_reported_as_corrupted`
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/IntegrityScrubber.java`; `AndvariMain`

#### GIMLE-302 — Version retention sweeping (count and age based)

- **Category**: Artifact Registry
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given -Dgimle.andvari.retention.enabled=true with maxVersionsPerModule=10; When a module has 15 versions; Then the 5 oldest-by-push-time versions are retired, dual-audited under a synthetic system principal.
- **Other test coverage (non-Holmgang, informational only)**: `ArtifactRetentionSweeperTest` — `retires_the_oldest_versions_once_a_module_exceeds_the_configured_count`, `retires_versions_older_than_the_configured_age`, `a_version_over_both_limits_is_reported_once_with_a_combined_reason`, `neither_policy_configured_retires_nothing`
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/ArtifactRetentionSweeper.java`

#### GIMLE-303 — Multi-replica peer synchronization (no consensus)

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/observability-registry-ha.feature` — Scenario: *Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces*
  - _Why this counts_: Pushes an artifact, strikes only Muninn/Andvari bounce faults via Fenrir, then asserts a coordinate-only deployment still reaches ACTIVE and control-plane metrics still ship to Muninn afterward.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariPeerSyncTest` — `a_sync_tick_pulls_an_artifact_that_only_exists_on_a_peer`, `a_push_to_one_replica_becomes_visible_through_another_after_a_sync_tick`, `an_already_present_coordinate_is_never_re_pulled`
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/AndvariPeerSync.java`

#### GIMLE-304 — Peer-sync conflict detection (irreconcilable divergence)

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Peer-sync conflict detection (irreconcilable divergence)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: Documented in class javadoc
- **Source location(s)**: `AndvariPeerSync.pullOne`

#### GIMLE-305 — Push/pull/list/delete `/artifacts/*` operational HTTP surface

- **Category**: Artifact Registry / API Server
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/observability-registry-ha.feature` — Scenario: *Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces*
  - _Why this counts_: Pushes an artifact, strikes only Muninn/Andvari bounce faults via Fenrir, then asserts a coordinate-only deployment still reaches ACTIVE and control-plane metrics still ship to Muninn afterward.
  - `gimle-holmgang/src/test/resources/features/registry-deploy.feature` — Scenario: *A pushed module deploys by coordinate with no artifact path*
  - _Why this counts_: Pushes a real jar to Andvari, then submits a manifest with a coordinate and no artifactPath, asserting it resolves through the real agent pull-through cache and reaches ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerTest` — `push_head_and_download_round_trip_with_the_digest_in_the_header`, `the_catalog_and_version_listing_reflect_pushed_artifacts`, `delete_removes_the_artifact`
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/AndvariServer.java`

#### GIMLE-306 — Maven-2-shaped `/repository/**` interop surface

- **Category**: Artifact Registry / API Server
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a jar is pushed via mvn deploy to /repository/com/gimle/.../provider-1.0.0.jar; When fetched via GET /artifacts/com.gimle.examples.greeter.provider/1.0.0; Then identical bytes are returned.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerMavenRepositoryTest` — `a_jar_pushed_through_the_repository_path_is_readable_from_the_operational_surface`, `a_jar_pushed_through_the_operational_surface_is_downloadable_via_the_repository_path`, `a_differing_re_push_through_the_repository_path_is_still_refused_as_immutable`
- **Source location(s)**: `AndvariServer.handleRepository`, `MavenCoordinates`

#### GIMLE-307 — Server-computed checksum sidecars (never trusting client uploads)

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Server-computed checksum sidecars (never trusting client uploads)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerMavenRepositoryTest#the_jar_checksum_is_always_server_computed_and_ignores_an_uploaded_sidecar`
- **Source location(s)**: `AndvariServer.handleRepositoryChecksum` vs `putSidecar`

#### GIMLE-308 — Generated `maven-metadata.xml` (never stored, always fresh)

- **Category**: Artifact Registry
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given three versions pushed out of order; When GET .../maven-metadata.xml; Then the document lists every version in semver order and names the correct latest/release.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerMavenRepositoryTest` — `maven_metadata_lists_every_pushed_version_and_names_the_latest`, `maven_metadata_checksum_is_computed_over_the_generated_document`, `a_single_segment_module_has_an_empty_group_id_in_the_generated_metadata`; `ArtifactStoreTest#versions_sort_semver_aware_not_lexicographically`
- **Source location(s)**: `AndvariServer.handleRepositoryMetadata`, `generateMavenMetadataXml`; `ArtifactStore.versions`/`compareVersions`

#### GIMLE-309 — Maven GAV coordinate translation

- **Category**: Artifact Registry / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Maven GAV coordinate translation" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `MavenCoordinatesTest` — `a_multi_segment_group_joins_with_the_artifact_id_by_dots`, `distinct_gavs_can_alias_to_the_same_module_coordinate`
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/MavenCoordinates.java`

#### GIMLE-310 — Defense-in-depth authorization (independent re-check, `ResourceKind.ARTIFACT`)

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a forwarded principal claims artifact access but doesn't hold ARTIFACT:WRITE; When Andvari independently re-checks; Then the push is rejected with 403.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerTlsTest#a_forwarded_principal_wins_over_the_peer_certificate_and_is_re_checked`, `an_ungrouped_certificate_is_refused_by_the_independent_rbac_check`
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/AndvariServer.java`

#### GIMLE-311 — Module-scoped permission grants

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a principal holds a permission scoped to only module com.example.foo; When it pushes/pulls that module; Then granted; catalog listing (whole registry) is denied.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerTlsTest` — `a_module_scoped_permission_grants_access_to_only_that_module`, `a_module_scoped_permission_cannot_list_the_full_catalog`
- **Source location(s)**: `AndvariServer.authorizeArtifacts`

#### GIMLE-312 — Node pull-only artifact access, scoped to active assignments

- **Category**: Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given node-42 holds an active assignment for module M:1.0.0; When it GETs /artifacts/M/1.0.0; Then granted; an unassigned coordinate, or any PUT/DELETE, is rejected.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerTlsTest#a_nodes_group_certificate_may_pull_only_coordinates_assigned_to_its_node`
- **Source location(s)**: `AndvariServer.authorizeArtifacts`, `nodeHasAssignmentFor`, `coordinateMatches`

#### GIMLE-313 — Dual audit logging for push/delete decisions

- **Category**: Internal-Infra / Authorization
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Dual audit logging for push/delete decisions" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: Exercised via `AndvariServerTlsTest`'s auth test set
- **Source location(s)**: `AndvariServer.recordAudit`, `authorizeArtifacts`

#### GIMLE-314 — Andvari's own console session story (`/auth/*`, bundled SPA)

- **Category**: API Server
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a valid Account exists; When logging in via Andvari's /auth/login; Then a distinct gimle_andvari_session cookie is issued and the bundled SPA is served at /console.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerAuthTest` — `login_session_and_logout_round_trip_with_no_client_certificate_at_all`, `a_wrong_password_is_rejected_with_no_cookie_set`
- **Source location(s)**: `AndvariServer.handleAuthLogin`/`handleAuthLogout`/`handleAuthSession`/`serveConsole`; `AndvariMain`

#### GIMLE-315 — mTLS server with dynamic TLS reload

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "mTLS server with dynamic TLS reload" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerTlsTest#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server`
- **Source location(s)**: `AndvariServer.reloadTlsMaterial`; `AndvariMain`'s cert-rotation ticker

#### GIMLE-316 — Plaintext-mode loud supply-chain warning

- **Category**: Internal-Infra / Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Plaintext-mode loud supply-chain warning" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/AndvariMain.java`

#### GIMLE-317 — Andvari observability instrumentation and Muninn shipping

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Andvari observability instrumentation and Muninn shipping" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `andvari`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariObservabilityTest` — `a_real_request_is_recorded_in_andvari_metrics`, `every_registered_route_is_independently_tagged`
- **Source location(s)**: `AndvariServer.instrument`; `com.gimle.observability.AndvariMetrics`; `AndvariMain`

#### GIMLE-318 — Process status endpoint (no RBAC gate)

- **Category**: API Server
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given Andvari is running; When GET /status; Then process-level status is returned with no RBAC check.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status`
- **Source location(s)**: `AndvariServer.handleStatus`

#### GIMLE-577 — Multi-jar publish with per-module tenant tagging (`kind: ArtifactSet`)

- **Category**: Artifact Registry
- **Status**: New
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Cucumber .feature scenario exercises an ArtifactSet publish against a real cluster -- coverage here requires a .feature scenario driving the real gimle binary, not the real JUnit/in-process coverage that already exists (ArtifactSetCommandTest's own real-AndvariServer-behind-ApiServer round trip does not count, per this file's own metadata.coverageRule).
- **Other test coverage (non-Holmgang, informational only)**: `ArtifactStoreTest` (tenant round-trip through `meta.json`, untenanted-to-tenanted backfill exactly once, a further tenant swap still conflicts); `AndvariServerTest` (tenant header round-trip on HEAD/GET/PUT, catalog listing includes `tenantId`); `AndvariServerTlsTest` (tenant-scoped RBAC grants, a push cannot claim a tenant the caller holds no permission for, reads/deletes check the stored tenant not a caller claim); `ArtifactSetManifestParserTest` (`tenant:`/`modules:` grouping, push-order preservation, duplicate-path rejection across tenants and against `modules`); `ArtifactSetCommandTest` (real end-to-end `gimle apply` against a real in-process `AndvariServer`: multi-tenant push, pre-flight digest-conflict abort before any push, idempotent resume on re-apply); `ArtifactSetMojoTest` (per-submodule tenant-property override, generated manifest content); `ArtifactSetCommandTest` (admission cross-check: a mismatched tenantId rejected with 400 naming both tenants, a matching tenantId admitted, an untenanted workload against a tenanted coordinate skips the check)
- **Source location(s)**: `gimle-andvari/src/main/java/com/gimle/andvari/ArtifactStore.java` (tenant field, untenanted-to-tenanted backfill, tenant-swap conflict), `AndvariServer.java` (`X-Gimle-Artifact-Tenant` header, dual-scope authorization), `AndvariPeerSync.java` (tenant propagated across replica sync), `gimle-controlplane/src/main/java/com/gimle/controlplane/andvari/AndvariClient.java`, `api/ApiServer.java` (`/artifacts/*` proxy header passthrough), `gimle-module/src/main/java/com/gimle/module/artifactset/ArtifactSetManifest.java`, `ArtifactSetModuleEntry.java`, `ArtifactSetManifestParser.java`, `gimle-cli/src/main/java/com/gimle/cli/ArtifactSetCommand.java`, `ArtifactCommand.java` (`--tenant`), `ControlPlaneClient.java` (`head`/`putFile` with headers), `GimleCli.java` (`kind: ArtifactSet` dispatch), `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/ArtifactSetMojo.java`, `gimle-controlplane/src/main/java/com/gimle/controlplane/andvari/AndvariClient.java` (`HeadOutcome.Found#tenantId`), `api/ApiServer.java` (`admissionArtifact`'s deployingTenantId cross-check, all four workload kinds)

### gimle-muninn

#### GIMLE-319 — Node platform-log ingest

- **Category**: Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a running MuninnServer in plaintext mode
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerLogsIngestTest#an_ingested_node_log_line_is_readable_back`, `#a_malformed_batch_is_rejected_entirely_and_nothing_from_it_is_readable`
- **Source location(s)**: `gimle-muninn/src/main/java/com/gimle/muninn/MuninnServer.java` (`handleIngestNodeLogs`, `ingest`), `MuninnDayFileStore.appendLines`

#### GIMLE-320 — Instance-log ingest

- **Category**: Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a running MuninnServer
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerLogsIngestTest#an_ingested_instance_log_line_is_readable_back`
- **Source location(s)**: `MuninnServer.java` (`handleIngestInstanceLogs`, `parseInstanceLogPath`)

#### GIMLE-321 — Node/instance log read with cursor paging

- **Category**: Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given logs previously ingested for a nodeId/category
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerLogsIngestTest`, `MuninnDayFileStoreTest#read_after_and_read_older_round_trip_through_a_fresh_store_instance`
- **Source location(s)**: `MuninnServer.java` (`read`, `handleReadNodeLogs`, `handleReadInstanceLogs`), `MuninnDayFileStore.readOlder`/`readAfter`

#### GIMLE-322 — `follow=true` rejection on Muninn reads

- **Category**: Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a running MuninnServer
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerLogsIngestTest#follow_true_is_rejected_since_muninn_only_serves_shipped_history`
- **Source location(s)**: `MuninnServer.java#read`

#### GIMLE-323 — Metrics ingest

- **Category**: Metrics
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a running MuninnServer
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerMetricsIngestTest#an_ingested_counter_and_timer_batch_round_trips_with_measurements_intact`, `#an_ingested_timer_with_percentiles_round_trips_the_percentiles_map`
- **Source location(s)**: `MuninnServer.java` (`handleIngestMetrics`)

#### GIMLE-324 — Metrics read

- **Category**: Metrics
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: same shape as #3, scoped to `/metrics/{processKind}/{processId}`
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerMetricsIngestTest`
- **Source location(s)**: `MuninnServer.java#handleReadMetrics`

#### GIMLE-325 — Traces ingest

- **Category**: Tracing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a running MuninnServer
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerTracesIngestTest#an_ingested_span_line_round_trips_with_attributes_intact`
- **Source location(s)**: `MuninnServer.java#handleIngestTraces`

#### GIMLE-326 — Traces read

- **Category**: Tracing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a running MuninnServer and traces previously ingested for a processKind/processId, When a client issues GET /traces/{processKind}/{processId}?cursor=...&limit=..., Then the response returns matching span lines, oldest-first, with paging cursors (the same shape as the node/instance log and metrics read endpoints).
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerTracesIngestTest`
- **Source location(s)**: `MuninnServer.java#handleReadTraces`

#### GIMLE-327 — Day-bucketed JSON-lines store with oldest-first cursor semantics

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Day-bucketed JSON-lines store with oldest-first cursor semantics" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `muninn`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `MuninnDayFileStoreTest#lines_spanning_two_days_land_in_two_separate_day_files`, `#a_late_arriving_line_appends_into_the_existing_day_file_rather_than_overwriting_it`
- **Source location(s)**: `MuninnDayFileStore.java` (`appendLines`, `readAllLinesSorted`)

#### GIMLE-328 — All-or-nothing batch validation on ingest

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "All-or-nothing batch validation on ingest" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `muninn`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `MuninnDayFileStoreTest#a_malformed_line_rejects_the_whole_batch_and_writes_nothing`; `MuninnServerLogsIngestTest#a_malformed_batch_is_rejected_entirely_and_nothing_from_it_is_readable`
- **Source location(s)**: `MuninnDayFileStore.java#appendLines`, `#requireTimestamp`

#### GIMLE-329 — Windows-safe on-disk path sanitization for colon-bearing processId

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Windows-safe on-disk path sanitization for colon-bearing processId" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `muninn`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `MuninnDayFileStoreTest#a_subtree_path_containing_a_colon_round_trips_without_an_invalid_path_error`
- **Source location(s)**: `MuninnDayFileStore.java#resolveSubtree`, `MuninnServer.java#PROCESS_ID_SEGMENT`

#### GIMLE-330 — Path-segment validation / directory-traversal defense

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Path-segment validation / directory-traversal defense" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `muninn`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerLogsIngestTest#an_invalid_node_id_path_segment_is_rejected_before_touching_the_filesystem`, `MuninnServerMetricsIngestTest#an_invalid_process_kind_path_segment_is_rejected_before_touching_the_filesystem`, `MuninnServerTracesIngestTest` (same)
- **Source location(s)**: `MuninnServer.java` (`PATH_SEGMENT`, `PROCESS_ID_SEGMENT`, `parseTwoSegments`, `parseInstanceLogPath`)

#### GIMLE-331 — Age-based retention sweep

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a day file older than the configured retentionDays
- **Other test coverage (non-Holmgang, informational only)**: `RetentionSweeperTest#a_day_file_older_than_the_retention_window_is_deleted`, `#a_day_file_within_the_retention_window_survives`, `#sweeping_twice_is_idempotent...`, `#sweeping_a_data_root_that_does_not_exist_yet_is_a_no_op`
- **Source location(s)**: `RetentionSweeper.java` (`sweep`, `sweepQuietly`); wired in `MuninnMain.java` via `-Dgimle.muninn.retentionDays`/`retentionSweepIntervalSeconds`

#### GIMLE-332 — Plaintext-default transport with loud unauthenticated-mode warning

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given gimle.transport.protocol is unset
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status`
- **Source location(s)**: `MuninnMain.java` (startup warning), `MuninnServer.java#createHttpServer`

#### GIMLE-333 — mTLS transport mode

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given gimle.transport.protocol=tls and valid cert/key/CA files
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerTlsTest#a_real_mtls_request_with_a_ca_signed_client_cert_succeeds`
- **Source location(s)**: `MuninnServer.java#createHttpServer` (HttpsServer + `wantClientAuth`)

#### GIMLE-334 — Zero-downtime TLS material reload on certificate rotation

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a running MuninnServer in TLS mode with an established client connection
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerTlsTest#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server`
- **Source location(s)**: `MuninnServer.java#reloadTlsMaterial`; ticker in `MuninnMain.java` (`OwnCertificateRotator.checkAndRotateIfDue`)

#### GIMLE-335 — Node-identity check on node-log ingest

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Node-identity check on node-log ingest" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `muninn`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `MuninnServer.java#identityAllowedToIngestAsNode`

#### GIMLE-336 — Instance-owner check on instance-log ingest

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Instance-owner check on instance-log ingest" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `muninn`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `MuninnServer.java#identityAllowedToIngestAsInstanceOwner`

#### GIMLE-337 — Verified-certificate-presence check on metrics/traces ingest

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Verified-certificate-presence check on metrics/traces ingest" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `muninn`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `MuninnServer.java#identityAllowedToIngestMetricsOrTraces`

#### GIMLE-338 — Read surface has no RBAC/authorization re-check (documented-vs-actual gap)

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Read surface has no RBAC/authorization re-check (documented-vs-actual gap)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `muninn`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `MuninnServer.java` (absence of any `Authorizer` import/call, contradicting its own field comment at lines 76-80); `com.gimle.mimir.authz.Authorizer` exists in `gimle-mimir` but is never referenced from `gimle-muninn`

#### GIMLE-339 — `/status` operational endpoint

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a running MuninnServer
- **Other test coverage (non-Holmgang, informational only)**: `MuninnServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status`, `#a_non_get_status_request_is_rejected`
- **Source location(s)**: `MuninnServer.java#handleStatus`

### gimle-observability

#### GIMLE-340 — Default OpenTelemetry tracer installation

- **Category**: Tracing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given no tracer provider has been installed yet in this JVM
- **Other test coverage (non-Holmgang, informational only)**: `GimleTracingTest#install_is_idempotent_and_yields_a_working_tracer`
- **Source location(s)**: `GimleTracing.java` (`installDefault`)

#### GIMLE-341 — Configurable, batched span exporter installation

- **Category**: Tracing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a custom SpanExporter (e.g. a capturing test double or MuninnSpanExporter)
- **Other test coverage (non-Holmgang, informational only)**: `GimleTracingInstallTest#install_swaps_in_the_given_exporter_and_a_real_span_reaches_it`
- **Source location(s)**: `GimleTracing.java#install`, `#installWithMuninnShipping`

#### GIMLE-342 — Bounded-wait tracer flush

- **Category**: Tracing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a tracer installed with a BatchSpanProcessor and a pending unexported span
- **Other test coverage (non-Holmgang, informational only)**: `GimleTracingInstallTest#flush_forces_the_batch_processor_to_export_before_the_next_periodic_tick`, `#flush_before_any_install_is_a_noop`
- **Source location(s)**: `GimleTracing.java#flush`

#### GIMLE-343 — Periodic log-file shipping to Muninn

- **Category**: Logging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given an active log file with new lines since the last shipped cursor
- **Other test coverage (non-Holmgang, informational only)**: `MuninnShipperTest#a_successful_tick_ships_new_log_lines_and_advances_the_cursor`, `#a_failed_tick_does_not_advance_the_cursor_and_retries_next_tick`
- **Source location(s)**: `MuninnShipper.java` (`startShippingLogFile`, `tickLogs`)

#### GIMLE-344 — Periodic Micrometer metrics shipping

- **Category**: Metrics
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/observability-registry-ha.feature` — Scenario: *Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces*
  - _Why this counts_: Pushes an artifact, strikes only Muninn/Andvari bounce faults via Fenrir, then asserts a coordinate-only deployment still reaches ACTIVE and control-plane metrics still ship to Muninn afterward.
- **Other test coverage (non-Holmgang, informational only)**: `MuninnShipperTest#a_metrics_tick_ships_one_ndjson_line_per_meter`
- **Source location(s)**: `MuninnShipper.java#startShippingMetrics`, `#tickMetrics`

#### GIMLE-345 — One-shot trace-batch and prepared-batch shipping

- **Category**: Tracing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a list of span lines, or a pre-serialized NDJSON body
- **Other test coverage (non-Holmgang, informational only)**: `MuninnShipperTest#ship_trace_batch_is_a_one_shot_post_with_no_periodic_ticking`, `#ship_prepared_batch_posts_the_given_body_verbatim_with_no_periodic_ticking`, `#ship_prepared_batch_is_a_noop_for_an_empty_body`
- **Source location(s)**: `MuninnShipper.java` (`shipTraceBatch`, `shipPreparedBatch`)

#### GIMLE-346 — Multi-endpoint best-effort fan-out shipping

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Multi-endpoint best-effort fan-out shipping" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `observability`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `MuninnShipperTest#a_batch_ships_to_every_configured_endpoint`, `#a_batch_still_lands_on_reachable_endpoints_when_one_configured_endpoint_is_down`
- **Source location(s)**: `MuninnShipper.java` (`postNdjsonBody`, `postToOne`), `#parseEndpoints`

#### GIMLE-347 — In-memory (non-persisted) log-shipping cursor

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "In-memory (non-persisted) log-shipping cursor" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `observability`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `MuninnShipper.java` (field `logCursor`, class javadoc)

#### GIMLE-348 — Micrometer meter → NDJSON codec

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Micrometer meter → NDJSON codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `observability`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `MeterSnapshotCodecTest#one_line_per_meter_with_the_meters_own_name`, `#a_timer_with_percentiles_ships_a_percentiles_map`, `#a_timer_without_percentiles_omits_the_percentiles_key`, `#an_empty_registry_produces_an_empty_string`
- **Source location(s)**: `MeterSnapshotCodec.java`

#### GIMLE-349 — OpenTelemetry span → NDJSON codec

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "OpenTelemetry span → NDJSON codec" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `observability`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SpanLineCodecTest#one_line_per_span_with_attributes_flattened_onto_it`, `#an_empty_batch_produces_an_empty_string`
- **Source location(s)**: `SpanLineCodec.java`

#### GIMLE-350 — `MuninnSpanExporter` (OpenTelemetry SDK integration)

- **Category**: Tracing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a MuninnSpanExporter wrapping a MuninnShipper pointed at a real ingest stub
- **Other test coverage (non-Holmgang, informational only)**: `MuninnSpanExporterTest#a_real_span_batch_reaches_the_stub_ingest_server_with_the_expected_shape`, `#export_never_throws_even_when_shipping_fails`
- **Source location(s)**: `MuninnSpanExporter.java`

#### GIMLE-351 — JFR-based per-module CPU/allocation attribution

- **Category**: Observability
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a module registered under a thread-name prefix "gimle-<module>-<version>-"
- **Other test coverage (non-Holmgang, informational only)**: `ThreadNameJfrAttributorTest#construction_and_shutdown_do_not_throw`, `#register_and_unregister_module_do_not_throw`, `#unregistering_a_module_never_registered_does_not_throw` (no test directly asserts a classified sample producing a counter increment — JFR event emission isn't driven from the test)
- **Source location(s)**: `ThreadNameJfrAttributor.java`

#### GIMLE-352 — Per-process tagged Micrometer metrics wrappers

- **Category**: Metrics
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a fresh metrics wrapper (e.g. ApiServerMetrics) with an in-memory SimpleMeterRegistry
- **Other test coverage (non-Holmgang, informational only)**: `AgentMetricsTest`, `ApiServerMetricsTest`, `WorkerMetricsTest`, `StoreMetricsTest`, `FafnirMetricsTest` (e.g. `#record_request_increments_count_and_records_latency`, `#request_latency_timer_publishes_percentiles_for_muninn_shipping`, `#error_counter_is_not_created_when_no_error_ever_recorded`, `#different_endpoints_and_verbs_are_tagged_independently`)
- **Source location(s)**: `AgentMetrics.java`, `ApiServerMetrics.java`, `WorkerMetrics.java`, `StoreMetrics.java`, `FafnirMetrics.java`, `AndvariMetrics.java`, shared helper `TaggedRequestMetrics.java`

#### GIMLE-353 — WorkerMetrics thread-count / metaspace gauges

- **Category**: Metrics
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given WorkerMetrics.recordThreadCount(moduleId, 5) then recordThreadCount(moduleId, 9)
- **Other test coverage (non-Holmgang, informational only)**: `WorkerMetricsTest#thread_count_gauge_reflects_the_latest_recorded_value_not_the_first`, `#metaspace_gauge_reflects_the_latest_recorded_value_not_the_first`
- **Source location(s)**: `WorkerMetrics.java` (`recordThreadCount`, `recordMetaspaceBytes`, `gaugeHolder`)

#### GIMLE-354 — Fafnir authz-failure counter (rate-limiting signal)

- **Category**: Metrics
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a FafnirMetrics instance
- **Other test coverage (non-Holmgang, informational only)**: `FafnirMetricsTest#authz_failures_are_recorded_and_tagged_by_verb_only`, `#authz_failure_count_is_zero_before_any_failure_is_recorded`
- **Source location(s)**: `FafnirMetrics.java` (`recordAuthzFailure`, `authzFailureCount`)

#### GIMLE-355 — Muninn endpoint list parsing from config

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a config value of "host1:9090, host2:9090,,host3:9090"
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `MuninnShipper.java#parseEndpoints`

### gimle-gateway

#### GIMLE-356 — Fabric-route HTTP-to-service dispatch

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a FabricRoute "/greet" bound to interface Greeter, version 1, method "greet", ParamType.STRING
- **Other test coverage (non-Holmgang, informational only)**: `GatewayDispatcherTest#a_string_argument_route_dispatches_and_returns_the_real_result`, `#a_no_argument_route_is_served_on_get`, `#an_int_argument_route_coerces_and_dispatches_correctly`
- **Source location(s)**: `GatewayDispatcher.java` (`dispatchFabric`), `GatewayRoute.java` (`FabricRoute`)

#### GIMLE-357 — Fabric-route argument coercion (`ParamType`)

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a FabricRoute with ParamType.INT
- **Other test coverage (non-Holmgang, informational only)**: `GatewayDispatcherTest#a_body_that_does_not_coerce_to_the_declared_param_type_returns_400`, `#the_wrong_http_method_for_a_fabric_route_returns_405`
- **Source location(s)**: `GatewayRoute.java` (`FabricRoute.ParamType`, `coerce`, `wireTypeName`)

#### GIMLE-358 — Vessel-route HTTP reverse-proxy dispatch

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a VesselRoute "/api/orders" -> deployment "orders-service", port "HTTP_PORT"
- **Other test coverage (non-Holmgang, informational only)**: `GatewayDispatcherTest#a_vessel_route_proxies_to_the_real_target_with_method_path_body_and_response_intact`
- **Source location(s)**: `GatewayDispatcher.java` (`dispatchVessel`), `GatewayRoute.java` (`VesselRoute`)

#### GIMLE-359 — Vessel-endpoint resolution with TTL cache

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a resolved endpoint list cached at time T with TTL=5s
- **Other test coverage (non-Holmgang, informational only)**: `VesselEndpointCacheTest#a_call_within_the_ttl_does_not_relay_again`, `#a_call_past_the_ttl_relays_again`
- **Source location(s)**: `VesselEndpointCache.java` (`resolve`, `endpointsFor`, `isStale`)

#### GIMLE-360 — Round-robin load balancing over ready vessel endpoints

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given two ready endpoints for a deployment
- **Other test coverage (non-Holmgang, informational only)**: `VesselEndpointCacheTest#round_robins_across_every_ready_endpoint_over_repeated_calls`, `#skips_endpoints_missing_the_named_port_or_the_host`; `GatewayDispatcherTest#a_vessel_route_round_robins_across_ready_instances_over_repeated_real_calls`
- **Source location(s)**: `VesselEndpointCache.java` (`resolve`, `readyTargets`, `roundRobinCursors`)

#### GIMLE-361 — Stale-cache fallback on endpoint-refresh failure

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a cached endpoint list from a prior successful refresh
- **Other test coverage (non-Holmgang, informational only)**: `VesselEndpointCacheTest#a_non_2xx_refresh_falls_back_to_the_stale_cached_list`, `#a_terminal_relay_status_with_nothing_cached_yet_is_a_clear_error`, `#an_unparsable_relay_body_with_nothing_cached_yet_is_a_clear_error`
- **Source location(s)**: `VesselEndpointCache.java#fallbackOrFail`

#### GIMLE-362 — Vessel-route error surfacing (no ready endpoint / connect failure)

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a deployment with zero live/ready endpoints
- **Other test coverage (non-Holmgang, informational only)**: `GatewayDispatcherTest#a_vessel_route_for_a_deployment_with_no_usable_endpoints_returns_a_clear_error_not_a_200`, `#a_vessel_route_reports_a_target_that_refuses_the_connection_as_a_clean_502`; `VesselEndpointCacheTest#an_empty_endpoint_list_is_a_clear_error_not_a_silent_200`
- **Source location(s)**: `VesselEndpointCache.java` (`resolve` — the 503 case), `VesselProxyClient.java` (`proxy` — IOException → 502)

#### GIMLE-363 — Route-table config DSL parsing

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a gateway.routes value mixing FABRIC and VESSEL lines, blank lines, and "#" comments
- **Other test coverage (non-Holmgang, informational only)**: `GatewayRouteConfigTest#parses_a_mix_of_fabric_and_vessel_routes_ignoring_blank_lines_and_comments`, `#an_unknown_kind_token_is_rejected`, `#a_fabric_line_with_the_wrong_number_of_fields_is_rejected`, `#a_non_integer_fabric_version_is_rejected`, `#a_fabric_param_type_outside_the_v1_restriction_is_rejected_at_parse_time`
- **Source location(s)**: `GatewayRouteConfig.java`

#### GIMLE-364 — Duplicate route-path rejection at config-parse time

- **Category**: Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a config with two lines both declaring path "/api/orders" (one FABRIC, one VESSEL, or same-kind duplicates)
- **Other test coverage (non-Holmgang, informational only)**: `GatewayRouteConfigTest#a_duplicate_route_path_across_fabric_and_vessel_is_rejected`, `#a_duplicate_fabric_route_path_is_rejected`, `#a_duplicate_vessel_route_path_is_rejected`
- **Source location(s)**: `GatewayRouteConfig.java#parse` (`seenPaths` check)

#### GIMLE-365 — Gateway HTTP server bootstrap via module lifecycle hooks

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given required config keys gateway.port and gateway.routes are present
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `GatewayHooks.java` (`onStart`, `onStop`, `requiredIntConfig`)

#### GIMLE-366 — Gateway liveness and readiness probes

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given GatewayHooks has not yet run onStart
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `GatewayLivenessProbe.java`, `GatewayReadinessProbe.java`, `GatewayHooks.java` (`ready` AtomicBoolean)

#### GIMLE-367 — HTTP status-code error mapping across the dispatcher

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a request to a path with no configured route
- **Other test coverage (non-Holmgang, informational only)**: `GatewayDispatcherTest#an_unknown_path_returns_404`, `#the_wrong_http_method_for_a_fabric_route_returns_405`, `#a_downstream_fabric_call_that_throws_returns_502`
- **Source location(s)**: `GatewayDispatcher.java#dispatch` and its `dispatchFabric`/`dispatchVessel` helpers

#### GIMLE-368 — Boot-only platform-layer JPMS workaround (`requires static`)

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Boot-only platform-layer JPMS workaround (`requires static`)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `gateway`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: Indirectly covered by `RealBundledHookAndProbeInvocationTest` in `gimle-worker` (per CLAUDE.md, established for the same pattern in `greeter-provider`/`greeter-consumer`); no dedicated gateway-specific test found in `gimle-gateway` itself
- **Source location(s)**: `gimle-gateway/src/main/java/module-info.java`

#### GIMLE-369 — Vessel proxy: no TLS, no header forwarding (v1 scope limitation)

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a vessel target that reads a custom request header
- **Other test coverage (non-Holmgang, informational only)**: `GatewayDispatcherTest#a_vessel_route_proxies_to_the_real_target_with_method_path_body_and_response_intact` confirms what *is* forwarded; no test exercises header forwarding since none exists
- **Source location(s)**: `VesselProxyClient.java` (class javadoc, `proxy` method — only method/path/body set on the outbound request)

#### GIMLE-370 — Fabric route "quiet success" ambiguity for a misrouted service name

- **Category**: Gateway/Routing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario exercises this. To close: add a scenario (extending an existing .feature file in the same problem area, or a new one) whose Given/When/Then drives a real cluster through the behavior the baseline already describes: Given a FabricRoute naming a service interface nothing currently exports
- **Other test coverage (non-Holmgang, informational only)**: `GatewayDispatcherTest#a_fabric_route_naming_a_service_nothing_exports_is_served_as_200_with_an_empty_body`
- **Source location(s)**: `GatewayDispatcher.java#dispatchFabric` (own javadoc explicitly documents this as a known v1 limitation)

#### GIMLE-570 — Gateway virtual-host routing and Service-backed (SERVICE) route kind

- **Category**: Gateway/Routing
- **Status**: New  _(newly added as part of the Service/Bifrost/Skald/gateway/fabric-tenant-check network model work)_
- **Coverage**: Not Covered
- **Gap note**: Holmgang's Cucumber suite has no coverage of gimle-gateway at all today -- no .feature file references it. To close: add a gateway.feature scenario that boots a gateway alongside a real cluster, declares two routes at the same path (one HOST-constrained, one not) plus a SERVICE route backed by a control-plane Service fronting a real deployed module, and asserts each Host header dispatches to the right target and the SERVICE route proxies to a live endpoint.
- **Other test coverage (non-Holmgang, informational only)**: `GatewayDispatcherTest` (6 relevant tests: host-constrained match, host mismatch 404, host-unconstrained route unaffected, fallthrough to host-unconstrained sibling, service route with no ready endpoint returns a clear error, cached endpoint list reused across dispatcher instances); `ServiceEndpointCacheTest` (11 tests: resolution, relay path, TTL caching/staleness fallback, error handling)
- **Source location(s)**: `gimle-gateway/src/main/java/com/gimle/gateway/GatewayDispatcher.java`, `gimle-gateway/src/main/java/com/gimle/gateway/GatewayRoute.java`, `gimle-gateway/src/main/java/com/gimle/gateway/GatewayRouteConfig.java`, `gimle-gateway/src/main/java/com/gimle/gateway/ServiceEndpointCache.java`

### gimle-cli

#### GIMLE-371 — Deployment resource management (get/apply/delete)

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Deployment resource management (get/apply/delete)".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.apply_then_get_deployments_round_trips`, `apply_then_delete_removes_the_deployment`, `apply_then_get_deployments_as_json_round_trips`, `apply_and_delete_deployment_produce_real_json_under_json_output_format`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/DeploymentsCommand.java`, `GimleCli.java`, `ManifestFiles.java`

#### GIMLE-372 — Job resource management (get/apply/delete)

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Job resource management (get/apply/delete)".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.apply_then_get_jobs_round_trips`, `apply_then_delete_removes_the_job`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/JobsCommand.java`

#### GIMLE-373 — CronJob management incl. manual trigger

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "CronJob management incl. manual trigger".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.apply_then_get_cronjobs_round_trips`, `cronjob_trigger_fires_immediately_and_the_generated_job_is_real`, `cronjob_trigger_on_an_unknown_cronjob_fails`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/CronJobsCommand.java`, `GimleCli.handleCronJobVerb`

#### GIMLE-374 — DaemonSet resource management

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "DaemonSet resource management".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.apply_then_get_daemonsets_round_trips`, `apply_then_delete_removes_the_daemonset`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/DaemonSetsCommand.java`

#### GIMLE-375 — StatefulSet resource management

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "StatefulSet resource management".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.apply_then_get_statefulsets_round_trips`, `apply_then_delete_removes_the_statefulset`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/StatefulSetsCommand.java`

#### GIMLE-376 — Node inventory and cordon/uncordon

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Node inventory and cordon/uncordon".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.get_nodes_lists_a_registered_node`, `get_nodes_as_json_includes_the_node_id_field`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/NodesCommand.java`

#### GIMLE-377 — Instance lifecycle event timeline

- **Category**: CLI
- **Status**: Modified  _(Added `--limit N`, applied client-side against the already-newest-first response.)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Instance lifecycle event timeline".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.events_with_no_limit_returns_every_event`, `events_with_limit_caps_the_returned_list`, `events_with_a_non_numeric_limit_fails`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/EventsCommand.java`

#### GIMLE-378 — Tenant management and quota configuration

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Tenant management and quota configuration".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.set_tenant_then_get_tenants_round_trips`, `set_and_delete_tenant_produce_real_json_under_json_output_format`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/TenantsCommand.java`

#### GIMLE-379 — Tenant plain configuration key/value store

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Tenant plain configuration key/value store".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.set_and_get_config_round_trips`, `set_and_delete_config_produce_real_json_under_json_output_format`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/ConfigCommand.java`

#### GIMLE-380 — Versioned secrets management (Fafnir proxy)

- **Category**: CLI / Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Versioned secrets management (Fafnir proxy)".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.secret_set_then_get_round_trips_the_plaintext_value`, `secret_list_shows_the_key_without_ever_printing_a_value`, `secret_versions_lists_every_claimed_version_after_two_writes`, `secret_get_with_an_explicit_version_reads_the_historical_value`, `secret_delete_then_get_returns_not_found`, `secret_rotate_key_returns_an_incrementing_active_key_id`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/SecretCommand.java`

#### GIMLE-381 — Artifact registry client (push/list/get/delete)

- **Category**: CLI / Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Artifact registry client (push/list/get/delete)".
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/ArtifactCommand.java`

#### GIMLE-382 — Log viewing and live tailing

- **Category**: CLI
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Log viewing and live tailing".
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/LogsCommand.java`

#### GIMLE-383 — Audit trail query

- **Category**: CLI / Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Audit trail query".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.audit_list_with_no_filters_succeeds_and_is_empty_in_plaintext_mode`, `audit_list_accepts_every_filter_flag_without_a_malformed_request`, `audit_command_without_the_list_verb_prints_usage_and_nonzero_exit`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/AuditCommand.java`

#### GIMLE-384 — RBAC role management

- **Category**: CLI / Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "RBAC role management".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.set_role_then_get_roles_round_trips_then_delete`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/RolesCommand.java`

#### GIMLE-385 — RBAC role binding management

- **Category**: CLI / Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "RBAC role binding management".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.set_rolebinding_then_get_rolebindings_round_trips_then_delete`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/RoleBindingsCommand.java`

#### GIMLE-386 — Operator account management

- **Category**: CLI / Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Operator account management".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.set_account_then_get_accounts_round_trips_and_never_leaks_the_password_hash`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/AccountsCommand.java`

#### GIMLE-387 — Certificate lifecycle management (bootstrap token, CSR request/status/approve, renewal)

- **Category**: CLI / Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Certificate lifecycle management (bootstrap token, CSR request/status/approve, renewal)".
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/CertCommand.java`

#### GIMLE-388 — Dual table/JSON output formatting

- **Category**: CLI / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Dual table/JSON output formatting".
- **Other test coverage (non-Holmgang, informational only)**: Exercised implicitly throughout GimleCliTest via -o json assertions
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/OutputFormat.java`

#### GIMLE-389 — kubectl-shaped global flag parsing, manifest-kind apply dispatch, and mTLS/leader-aware HTTP client

- **Category**: Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "kubectl-shaped global flag parsing, manifest-kind apply dispatch, and mTLS/leader-aware HTTP client".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.a_bare_invocation_with_no_verb_prints_usage_rather_than_a_server_configuration_error`, `missing_server_configuration_is_a_clear_error`, `an_unreachable_control_plane_produces_a_clear_error_and_nonzero_exit`, `a_malformed_server_response_produces_a_clear_error_not_a_stack_trace`, `a_404_produces_a_clear_error_and_nonzero_exit`, `unknown_verb_prints_usage_and_nonzero_exit`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/GimleCli.java`, `Flags.java`, `ManifestFiles.java`, `ControlPlaneClient.java`, `ApiResponse.java`, `CliException.java`

#### GIMLE-578 — Service CRUD and live endpoint lookup

- **Category**: CLI
- **Status**: New
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Service CRUD and live endpoint lookup".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.set_service_then_get_services_round_trips_then_delete`, `set_service_defaults_target_port_to_port_when_omitted`, `service_endpoints_reports_the_declared_port_shape_with_no_live_backing_instance`, `set_service_without_a_deployment_flag_fails`, `get_service_not_found_produces_a_clear_error`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/ServicesCommand.java`, `gimle-cli/src/main/java/com/gimle/cli/GimleCli.java` (`service`/`services` dispatch, including the `service endpoints` sub-verb)

#### GIMLE-579 — NetworkPolicy CRUD

- **Category**: CLI
- **Status**: New
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `gimle` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `gimle` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "NetworkPolicy CRUD".
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest.set_networkpolicy_then_get_networkpolicies_round_trips_then_delete`, `set_networkpolicy_without_a_tenant_flag_fails`, `get_networkpolicy_not_found_produces_a_clear_error`
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/NetworkPolicyCommand.java`, `gimle-cli/src/main/java/com/gimle/cli/GimleCli.java` (`networkpolicy`/`networkpolicies` dispatch)

#### GIMLE-584 — `gimle configmap` command

- **Category**: CLI
- **Status**: New  _(newly added as part of the ConfigMap kind (optimistic concurrent writes) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Cucumber .feature scenario exercises `gimle configmap` against a real cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: Exercised end-to-end by `ApiServerConfigMapTest`'s HTTP-level coverage of the same `/configmaps/*` surface `ConfigMapCommand` calls; no dedicated `ConfigMapCommandTest` fixture exists (see gapNote in rtm.json).
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/ConfigMapCommand.java`, `ControlPlaneClient.java` (`patch`), `GimleCli.java` (`configmap` verb dispatch)

#### GIMLE-592 — `gimle secretmap` command

- **Category**: CLI
- **Status**: New  _(newly added as part of the SecretMap kind (Fafnir-native, v1) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises `gimle secretmap` against a real running cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: Exercised end-to-end by `ApiServerSecretMapTest`'s HTTP-level coverage of the same `/secretmaps/*` surface `SecretMapCommand` calls; no dedicated `SecretMapCommandTest` fixture exists, the same gap `ConfigMapCommand` has (GIMLE-584).
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/SecretMapCommand.java`, `GimleCli.java` (`secretmap` verb dispatch)

#### GIMLE-595 — `secretmap versions`/`secretmap rollback` verbs

- **Category**: CLI
- **Status**: New  _(newly added as part of the SecretMap group-level versioning and rollback work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the `secretmap versions`/`secretmap rollback` CLI verbs against a real running cluster -- see GIMLE-588's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: Exercised indirectly through `ApiServerSecretMapTest`/`FafnirServerSecretMapTest`'s coverage of the underlying `/secretmaps/*/versions` and `/secretmaps/*/rollback` routes this command calls; no dedicated `SecretMapCommand` unit test file exists, matching the rest of that class's own untested-at-the-CLI-layer precedent (`ConfigMapCommand`/`SecretCommand` are the same).
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/SecretMapCommand.java` (`versions`, `rollback`), `gimle-cli/src/main/java/com/gimle/cli/GimleCli.java` (usage text)

#### GIMLE-600 — `gimle seal` command, `secret retire-key`, `secretmap seal` verbs

- **Category**: CLI
- **Status**: New  _(newly added as part of the Sealed SecretMap (v2) and key lifecycle work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the `seal`, `secret retire-key`, or `secretmap seal` CLI verbs against a real running cluster -- see GIMLE-588's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: Exercised indirectly through `FafnirServerSealTest`/`ApiServerSealTest`/`ApiServerSealAuthzTest`'s coverage of the underlying routes these verbs call; no dedicated `SealCommand`/`SecretCommand`/`SecretMapCommand` unit test file exists, matching this class family's own untested-at-the-CLI-layer precedent.
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/SealCommand.java`, `gimle-cli/src/main/java/com/gimle/cli/SecretCommand.java` (`retireKey`), `gimle-cli/src/main/java/com/gimle/cli/SecretMapCommand.java` (`seal`), `gimle-cli/src/main/java/com/gimle/cli/GimleCli.java` (usage text)

#### GIMLE-602 — `deployment`/`statefulset`/`daemonset` `revisions`/`rollback` verbs

- **Category**: CLI
- **Status**: New  _(newly added as part of the ControllerRevision revision-history and rollback work)_
- **Coverage**: Not Covered
- **Gap note**: A `rollback.feature` Cucumber scenario (`gimle-holmgang/src/test/resources/features/rollback.feature`, tag `@holmgang @rollback`) and its step definition (`DeploymentSteps.isRolledBackToThePreviousRevision`, `ClusterApi.rollbackDeployment`) were added alongside this work, but could not be executed to confirm coverage: gimle-holmgang transitively depends on gimle-hilmir, which uses the JDK 24+ `java.lang.classfile` API -- unavailable in the JDK 21 toolchain this scan ran under. Run `mvn -pl gimle-holmgang verify -Psmoke -Dcucumber.filter.tags="@rollback"` (per the project's JDK 25 toolchain) and flip this to Covered once it passes.
- **Other test coverage (non-Holmgang, informational only)**: `GimleCliTest` against a real `ApiServer` (not mocked): `deployment revisions`, `deployment rollback` with and without `--to-revision`, and the 404 failure path.
- **Source location(s)**: `gimle-cli/src/main/java/com/gimle/cli/DeploymentsCommand.java` (`revisions`, `rollback`), `gimle-cli/src/main/java/com/gimle/cli/StatefulSetsCommand.java` (`revisions`, `rollback`), `gimle-cli/src/main/java/com/gimle/cli/DaemonSetsCommand.java` (`revisions`, `rollback`), `gimle-cli/src/main/java/com/gimle/cli/GimleCli.java` (`handleDeploymentVerb`/`handleStatefulSetVerb`/`handleDaemonSetVerb`)

### gimle-hilmir

#### GIMLE-390 — Topology validation (`hilmir validate`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Topology validation (`hilmir validate`)".
- **Other test coverage (non-Holmgang, informational only)**: `TopologyValidatorTest` (extensive, ~25+ tests); `HilmirMainTest.validate_exits_zero_for_a_topology_with_no_error_severity_findings`, `validate_exits_one_and_lists_errors_before_warnings_for_a_broken_topology`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/validate/TopologyValidator.java`, `Finding.java`, `Severity.java`, `topology/Topology.java`, `TopologyParser.java`

#### GIMLE-391 — Cluster launch planning (`hilmir plan`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Cluster launch planning (`hilmir plan`)".
- **Other test coverage (non-Holmgang, informational only)**: `HilmirMainTest.plan_prints_the_resolved_commands_for_a_healthy_topology`, `plan_filters_to_one_machine_when_requested`, `plan_aborts_with_findings_and_exit_one_when_the_topology_has_an_error`; `LaunchPlannerTest` (multiple)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/plan/LaunchPlanner.java`, `ClusterPlan.java`, `MachinePlan.java`, `ProcessCommand.java`

#### GIMLE-392 — Real multi-process cluster bring-up (`hilmir up`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Real multi-process cluster bring-up (`hilmir up`)".
- **Other test coverage (non-Holmgang, informational only)**: `MachineLauncherIntegrationTest.up_waits_on_a_remote_prerequisite_then_down_and_status_reflect_the_real_processes`; `HilmirMainTest.up_requires_the_machine_flag`, `up_aborts_with_findings_before_launching_anything_when_the_topology_has_an_error`; `BootOrderTest`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/MachineLauncher.java`, `BootOrder.java`, `ReadinessPoller.java`, `RunLedger.java`, `JavaArgFile.java`

#### GIMLE-393 — Cluster teardown and status reporting (`hilmir down`/`status`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Cluster teardown and status reporting (`hilmir down`/`status`)".
- **Other test coverage (non-Holmgang, informational only)**: `MachineLauncherIntegrationTest.down_is_a_clean_no_op_for_an_already_dead_recorded_pid`, `status_reports_a_dead_pid_as_not_alive_and_a_never_bound_address_as_closed`; `HilmirCliDownStatusEndToEndTest`; `HilmirMainTest` (multiple)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/MachineLauncher.java`, `RunLedger.java`

#### GIMLE-394 — Cluster TLS/PKI bootstrap (`hilmir pki init`)

- **Category**: Release Management / Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Cluster TLS/PKI bootstrap (`hilmir pki init`)".
- **Other test coverage (non-Holmgang, informational only)**: `PkiInitTest` (multiple); `HilmirMainTest.pki_requires_the_init_subcommand`, `pki_init_requires_the_file_flag`, `pki_init_refuses_a_topology_with_no_tls_material_dir_dir`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/PkiInit.java`

#### GIMLE-395 — Raft store membership add (`hilmir store add`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Raft store membership add (`hilmir store add`)".
- **Other test coverage (non-Holmgang, informational only)**: `StoreCommandsClusterTest.add_joins_a_real_peer_and_it_becomes_a_visible_cluster_member`; `HilmirMainTest` (positional args, one-of-topology/server); `StoreEndpointsTest`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/store/StoreAddCommand.java`, `StoreEndpoints.java`, `StoreRetry.java`

#### GIMLE-396 — Raft store membership remove (`hilmir store remove`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Raft store membership remove (`hilmir store remove`)".
- **Other test coverage (non-Holmgang, informational only)**: `StoreCommandsClusterTest.remove_drops_a_previously_added_peer_from_the_membership`, `remove_of_a_never_added_peer_fails_fast_with_a_clean_error`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/store/StoreRemoveCommand.java`

#### GIMLE-397 — Per-machine platform binary rolling upgrade with quorum-safe store restart (`hilmir upgrade-cluster`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Per-machine platform binary rolling upgrade with quorum-safe store restart (`hilmir upgrade-cluster`)".
- **Other test coverage (non-Holmgang, informational only)**: `UpgradeClusterCommandTest` (multiple); `MachineLauncherRestartRoleIntegrationTest` (multiple); `MachineLauncherStoreQuorumGateTest` (multiple)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/upgrade/UpgradeClusterCommand.java`, `RoleRestarter.java`, `MachineLauncher.restartRole`/`requireStoreQuorumMaintained`

#### GIMLE-398 — Bundle-based fresh release deployment (`hilmir deploy`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Bundle-based fresh release deployment (`hilmir deploy`)".
- **Other test coverage (non-Holmgang, informational only)**: `DeployCommandTest` (multiple, incl. dry-run, unresolved value ref, json output, wait); `HilmirMainTest.deploy_requires_the_file_flag`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/DeployCommand.java`, `ReleaseReconciler.deployFresh`, `ReleasePlan.java`

#### GIMLE-399 — Bundle upgrade with automatic resource pruning (`hilmir upgrade`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Bundle upgrade with automatic resource pruning (`hilmir upgrade`)".
- **Other test coverage (non-Holmgang, informational only)**: `UpgradeCommandTest` (prunes workload, requires existing release, dry-run computes prune with no mutating call)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/UpgradeCommand.java`, `ReleaseReconciler.upgradeExisting`/`computePrune`

#### GIMLE-400 — Release rollback to a prior revision (`hilmir rollback`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Release rollback to a prior revision (`hilmir rollback`)".
- **Other test coverage (non-Holmgang, informational only)**: `RollbackCommandTest` (multiple); `HilmirMainTest.rollback_requires_the_release_flag`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/RollbackCommand.java`

#### GIMLE-401 — Full release teardown (`hilmir undeploy`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Full release teardown (`hilmir undeploy`)".
- **Other test coverage (non-Holmgang, informational only)**: `UndeployCommandTest` (multiple); `HilmirMainTest.undeploy_requires_the_release_flag`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/UndeployCommand.java`, `ReleaseReconciler.undeployRelease`

#### GIMLE-402 — Release listing (`hilmir releases`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Release listing (`hilmir releases`)".
- **Other test coverage (non-Holmgang, informational only)**: `ReleasesCommandTest` (2 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/ReleasesCommand.java`

#### GIMLE-403 — Release status inspection (`hilmir release-status`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Release status inspection (`hilmir release-status`)".
- **Other test coverage (non-Holmgang, informational only)**: `ReleaseStatusCommandTest`; `HilmirMainTest.release_status_requires_a_release_name`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/ReleaseStatusCommand.java`

#### GIMLE-404 — GitOps directory reconciliation (`hilmir sync`, incl. `--watch` and `--prune`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "GitOps directory reconciliation (`hilmir sync`, incl. `--watch` and `--prune`)".
- **Other test coverage (non-Holmgang, informational only)**: `SyncCommandTest` (11 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/sync/SyncCommand.java`

#### GIMLE-405 — `--watch` interval loop for sync

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "`--watch` interval loop for sync".
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/sync/SyncCommand.java`

#### GIMLE-406 — Bundle value templating and override precedence (`${values.*}` substitution)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Bundle value templating and override precedence (`${values.*}` substitution)".
- **Other test coverage (non-Holmgang, informational only)**: `BundleRendererTest` (6 tests); `ValueOverridesTest` (4 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/BundleRenderer.java`, `ValueOverrides.java`

#### GIMLE-407 — Bundle manifest schema parsing and validation

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Bundle manifest schema parsing and validation".
- **Other test coverage (non-Holmgang, informational only)**: `BundleParserTest` (8 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/BundleParser.java`

#### GIMLE-408 — Workload readiness polling for `--wait`

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Workload readiness polling for `--wait`".
- **Other test coverage (non-Holmgang, informational only)**: `DeployCommandTest.wait_polls_until_the_workloads_instances_report_active` (indirect); NONE dedicated
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/release/WaitPoller.java`

#### GIMLE-409 — Doctor static deployability diagnostics (`hilmir doctor`)

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Doctor static deployability diagnostics (`hilmir doctor`)".
- **Other test coverage (non-Holmgang, informational only)**: `DoctorAnalyzerTest` (10 tests); `DoctorCommandTest`; `BytecodeScannerTest`, `JarStructureInspectorTest`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/doctor/DoctorCommand.java`, `DoctorAnalyzer.java`, `DoctorFinding.java`, `analyze/*`

#### GIMLE-410 — Doctor cluster-aware checks (`--server`, `--tenant`)

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Doctor cluster-aware checks (`--server`, `--tenant`)".
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/doctor/DoctorClusterCheck.java`, `DoctorCommand.runClusterChecks`

#### GIMLE-411 — Manifest scaffolding (`hilmir init`)

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Manifest scaffolding (`hilmir init`)".
- **Other test coverage (non-Holmgang, informational only)**: `InitCommandTest` (3 tests); `ModuleYamlWriterTest` (2 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/init/InitCommand.java`, `ModuleYamlWriter.java`, `DeploymentYamlWriter.java`

#### GIMLE-412 — Gateway extension enable (`hilmir enable gateway`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Gateway extension enable (`hilmir enable gateway`)".
- **Other test coverage (non-Holmgang, informational only)**: `EnableGatewayCommandTest` (5 tests); `GatewayJarLocatorTest` (7 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/extension/EnableGatewayCommand.java`, `GatewayBundleTemplate.java`, `GatewayJarLocator.java`

#### GIMLE-413 — Gateway extension disable (`hilmir disable gateway`)

- **Category**: Release Management
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Gateway extension disable (`hilmir disable gateway`)".
- **Other test coverage (non-Holmgang, informational only)**: `DisableGatewayCommandTest` (2 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/extension/DisableGatewayCommand.java`

#### GIMLE-414 — Bundled JRE resolution for platform-binary launches

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Bundled JRE resolution for platform-binary launches".
- **Other test coverage (non-Holmgang, informational only)**: `BundledJreResolverTest` (6 tests); `LaunchPlannerTest` (2 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/plan/BundledJreResolver.java`

#### GIMLE-415 — `java @argfile` command-line rewriting

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "`java @argfile` command-line rewriting".
- **Other test coverage (non-Holmgang, informational only)**: `JavaArgFileTest` (2 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/JavaArgFile.java`, `gimle-hilmir/src/main/java/com/gimle/hilmir/plan/JavaArgFile.java`

#### GIMLE-416 — Run ledger persistence for `up`/`down`/`status`/`upgrade-cluster`

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "Run ledger persistence for `up`/`down`/`status`/`upgrade-cluster`".
- **Other test coverage (non-Holmgang, informational only)**: `RunLedgerTest` (9 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/RunLedger.java`, `RunRecord.java`

#### GIMLE-417 — TCP-connect readiness polling

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition shells out to the `hilmir` binary today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls instead. Closing this gap needs new step defs that spawn `hilmir` as a real subprocess against a live Holmgang cluster and assert on its stdout/exit code for "TCP-connect readiness polling".
- **Other test coverage (non-Holmgang, informational only)**: `ReadinessPollerTest` (4 tests)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/ReadinessPoller.java`

#### GIMLE-573 — Doctor advisory-only outbound-connection hazard detection

- **Category**: Build Tooling
- **Status**: New  _(newly added as part of the Service/Bifrost/Skald/gateway/fabric-tenant-check network model work)_
- **Coverage**: Not Covered
- **Gap note**: Holmgang's Cucumber suite has no coverage of `hilmir doctor` bytecode-hazard checks at all today -- no .feature file exercises it. To close: add a doctor.feature scenario running `hilmir doctor` against a fixture module jar that opens outbound connections and asserting MAKES_OUTBOUND_CALLS appears at INFO severity.
- **Other test coverage (non-Holmgang, informational only)**: `BytecodeScannerTest`, `DoctorAnalyzerTest` -- see requirements-matrix.json for detail
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/analyze/BytecodeScanner.java`, `gimle-hilmir/src/main/java/com/gimle/hilmir/doctor/DoctorAnalyzer.java`

#### GIMLE-576 — Remote (SSH) fleet bootstrap (`hilmir up/down/status --remote`)

- **Category**: Release Management
- **Status**: Modified  _(Real host-key verification, self-provisioning, and per-machine material distribution replaced the v1 non-goals; `upgrade-cluster --remote` split out as GIMLE-580.)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Cucumber scenario exercises --remote against a real cluster -- coverage here requires a .feature scenario driving the real `hilmir` binary, not a plain JUnit *IT class (see this file's own metadata.coverageRule). A real automated Docker+SSH round trip exists as gimle-holmgang's UtgardSshDeployIT (real sshd, an ephemeral authorized keypair, hilmir up/down/status --remote, a genuine deployment reaching ACTIVE) -- informational only, it does not change Coverage. Provisioning, host-key pinning/mismatch, and per-machine material distribution are covered at the unit level (RemoteDispatchTest) but not yet exercised by that same real Docker+SSH IT, since its fixture pre-installs hilmir and a plaintext topology on its container rather than starting from a bare, unprovisioned mtls one. Closing this gap for real needs both a new Holmgang Cucumber step definition driving --remote, and extending UtgardSshDeployIT (or a sibling) to start from an unprovisioned container.
- **Other test coverage (non-Holmgang, informational only)**: `RemoteDispatchTest` (provisioning, material distribution, host-key pinning incl. a simulated mismatch); `ResolvedSshTargetTest`; `SshProcessExecTest`; `PkiBootstrapMainTest`; `PkiInitTest`; `HilmirMainTest.up_with_remote_does_not_require_the_machine_flag`, `down_with_remote_requires_the_file_flag`, `status_with_remote_requires_the_file_flag`; `TopologyParserTest`; `UtgardSshDeployIT` (real Docker+SSH round trip against a genuine sshd)
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/remote/RemoteDispatch.java`, `SshProcessExec.java`, `ResolvedSshTarget.java`, `RemoteExec.java`, `RemoteOutput.java`, `SshCliFlags.java`, `SshSettings.java`, `Machine.java` (`sshHostKeyFingerprint`), `gimle-pki/src/main/java/com/gimle/pki/PkiBootstrapMain.java` (multi-hostname leaves), `gimle-hilmir/src/main/java/com/gimle/hilmir/launch/PkiInit.java` (Fafnir key generation), `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardSshDeployIT.java`, `UtgardSshMachine.java`

#### GIMLE-580 — `hilmir upgrade-cluster --remote` (SSH-dispatched platform binary rollout)

- **Category**: Release Management
- **Status**: New
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Cucumber scenario, and no real-cluster JUnit *IT either, exercises `upgrade-cluster --remote` against a genuine running fleet -- only the SSH dispatch shape itself is unit-tested (RemoteDispatchTest). Closing this gap needs either a new Utgard*IT class (mirroring UtgardSshDeployIT's real sshd fixture) or a Holmgang Cucumber scenario that boots a topology, runs `hilmir upgrade-cluster --remote` against it, and asserts the restarted processes come back healthy.
- **Other test coverage (non-Holmgang, informational only)**: `RemoteDispatchTest.upgrade_cluster_dispatches_the_new_classpath_and_roles_to_every_machine`
- **Source location(s)**: `gimle-hilmir/src/main/java/com/gimle/hilmir/remote/RemoteDispatch.java` (`upgradeCluster`), `gimle-hilmir/src/main/java/com/gimle/hilmir/HilmirMain.java` (`runUpgradeCluster`'s `--remote` branch), `gimle-hilmir/src/main/java/com/gimle/hilmir/upgrade/UpgradeClusterCommand.java`

### gimle-maven-plugin

#### GIMLE-418 — `mvn gimle:agent` — spawn a real node agent (plus its worker command tail)

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:agent` — spawn a real node agent (plus its worker command tail)" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/AgentMojo.java`, `AbstractGimleMojo.java`, `GimleProcesses.resolveRuntimeClasspath`

#### GIMLE-419 — `mvn gimle:bootstrap` — full local-dev cluster orchestration in one foreground command

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:bootstrap` — full local-dev cluster orchestration in one foreground command" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/BootstrapMojo.java`

#### GIMLE-420 — Process-launcher Maven goals for individual platform processes (`controlplane`/`store`/`fafnir`/`muninn`/`andvari`/`tls-init`)

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "Process-launcher Maven goals for individual platform processes (`controlplane`/`store`/`fafnir`/`muninn`/`andvari`/`tls-init`)" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/{ControlPlaneMojo,StoreMojo,FafnirMojo,MuninnMojo,AndvariMojo,TlsInitMojo}.java`, `AbstractGimleMojo.java`

#### GIMLE-421 — `mvn gimle:deploy` — apply a deployment manifest via a real CLI subprocess

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:deploy` — apply a deployment manifest via a real CLI subprocess" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/DeployMojo.java`

#### GIMLE-422 — `mvn gimle:doctor` — run hilmir doctor against the invoking project's own built jar

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:doctor` — run hilmir doctor against the invoking project's own built jar" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: `DoctorMojoTest` (4 tests, against the pure buildCommand seam)
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/DoctorMojo.java`

#### GIMLE-423 — `mvn gimle:init` — scaffold manifests for the invoking project's own built jar

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:init` — scaffold manifests for the invoking project's own built jar" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: `InitMojoTest` (3 tests)
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/InitMojo.java`

#### GIMLE-424 — `mvn gimle:publish` — push a built module jar to the artifact registry

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:publish` — push a built module jar to the artifact registry" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/PublishMojo.java`

#### GIMLE-425 — `mvn gimle:docs` — full documentation site build pipeline

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:docs` — full documentation site build pipeline" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/DocsMojo.java`

#### GIMLE-426 — `mvn gimle:flaky-tests` — run known-flaky-tagged tests in isolated standalone reactors

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:flaky-tests` — run known-flaky-tagged tests in isolated standalone reactors" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: `FlakyTestsMojoTest` (pure-function seams)
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/FlakyTestsMojo.java`

#### GIMLE-427 — `mvn gimle:saga` — ensure a Saga test-report server is running

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:saga` — ensure a Saga test-report server is running" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: `SagaServerTest`, `SagaClientTest`
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SagaMojo.java`, `SagaServer.java`, `SagaClient.java`, `AbstractGimleRootMojo.java`

#### GIMLE-428 — `mvn gimle:verify` — full build run under Saga tracking

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:verify` — full build run under Saga tracking" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: `SagaVerifyMojoTest` (pure-function seams); `SagaEventsTest`; `SurefireReportsTest`
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SagaVerifyMojo.java`, `SagaEvents.java`, `SurefireReports.java`, `GitInfo.java`

#### GIMLE-429 — `mvn gimle:saga-import` — standalone sweep-and-import of existing surefire reports

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:saga-import` — standalone sweep-and-import of existing surefire reports" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SagaImportMojo.java`

#### GIMLE-430 — `mvn gimle:saga-stop` — best-effort local Saga server shutdown

- **Category**: Build Tooling
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "`mvn gimle:saga-stop` — best-effort local Saga server shutdown" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SagaStopMojo.java`

#### GIMLE-431 — Internal — Aether-based cross-module runtime classpath resolution

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "Internal — Aether-based cross-module runtime classpath resolution" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/GimleProcesses.java`

#### GIMLE-432 — Internal — host-matching java/mvn executable resolution and subprocess supervision

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "Internal — host-matching java/mvn executable resolution and subprocess supervision" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: `GimleProcessesTest` (6 tests)
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/GimleProcesses.java`

#### GIMLE-433 — Internal — git commit/branch capture for run identification

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "Internal — git commit/branch capture for run identification" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/GitInfo.java`

#### GIMLE-434 — Internal — surefire report discovery and totals aggregation, including flaky-testcase counting

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Build-time tooling invoked via `mvn gimle:*`, not cluster runtime behavior -- there is no natural Holmgang cluster scenario for "Internal — surefire report discovery and totals aggregation, including flaky-testcase counting" at all; this is validated (or not) by the plugin's own Mojo tests, never by a real-cluster Gherkin scenario.
- **Other test coverage (non-Holmgang, informational only)**: `SurefireReportsTest`
- **Source location(s)**: `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/SurefireReports.java`

### gimle-console

#### GIMLE-435 — Operator session login / logout

- **Category**: Web Console / Auth
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Operator session login / logout" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/useAuthStore.test.ts` — "a successful login sets status authenticated and clears any previous error", "login failure surfaces a generic error and leaves status unauthenticated"
- **Source location(s)**: `gimle-console/src/routes/login.tsx`, `src/stores/useAuthStore.ts`, `src/repositories/http/auth.ts`

#### GIMLE-436 — Session bootstrap & 401 handling

- **Category**: Web Console / Auth
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Session bootstrap & 401 handling" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `useAuthStore.test.ts` — "init() only calls session() once even if invoked twice", "handleUnauthorized clears principal and sets status unauthenticated"
- **Source location(s)**: `src/stores/useAuthStore.ts`, `src/repositories/http/apiClient.ts`, `src/routes/__root.tsx`

#### GIMLE-437 — Cluster Overview dashboard

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Cluster Overview dashboard" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/routes/index.tsx`, `src/stores/useOverviewStore.ts`, `src/components/overview-signal.tsx`

#### GIMLE-438 — Tactical HUD / Signal display-mode toggle

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Tactical HUD / Signal display-mode toggle" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/stores/useDisplayStore.ts`, `src/components/overview-signal.tsx`, `src/routes/index.tsx`

#### GIMLE-439 — Deployments list/create/detail/delete

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Deployments list/create/detail/delete" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/useDeploymentsStore.test.ts`, `src/repositories/http/deployments.test.ts`
- **Source location(s)**: `src/routes/deployments.index.tsx`, `deployments.$name.tsx`, `deployments.new.tsx`, `src/stores/useDeploymentsStore.ts`, `src/repositories/http/deployments.ts`

#### GIMLE-440 — Jobs (run-to-completion workload) list

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Jobs (run-to-completion workload) list" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/jobs.test.ts`
- **Source location(s)**: `src/routes/jobs.index.tsx`, `jobs.$name.tsx`, `src/stores/useJobsStore.ts`, `src/repositories/http/jobs.ts`

#### GIMLE-441 — CronJobs list/detail

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "CronJobs list/detail" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/cronjobs.test.ts`
- **Source location(s)**: `src/routes/cronjobs.index.tsx`, `cronjobs.$name.tsx`, `src/stores/useCronJobsStore.ts`, `src/repositories/http/cronjobs.ts`

#### GIMLE-442 — DaemonSets list/detail

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "DaemonSets list/detail" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/daemonsets.test.ts`
- **Source location(s)**: `src/routes/daemonsets.index.tsx`, `daemonsets.$name.tsx`, `src/stores/useDaemonSetsStore.ts`, `src/repositories/http/daemonsets.ts`

#### GIMLE-443 — StatefulSets list/detail

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "StatefulSets list/detail" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/statefulsets.test.ts`
- **Source location(s)**: `src/routes/statefulsets.index.tsx`, `statefulsets.$name.tsx`, `src/stores/useStatefulSetsStore.ts`, `src/repositories/http/statefulsets.ts`

#### GIMLE-444 — Instances table with filtering (global + node/tenant-scoped)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Instances table with filtering (global + node/tenant-scoped)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/instances.test.ts`, `src/repositories/http/instances.test.ts`
- **Source location(s)**: `src/routes/instances.index.tsx`, `instances.$name.$idx.tsx`, `src/components/instances-table.tsx`, `src/stores/useInstancesStore.ts`, `src/repositories/http/instances.ts`

#### GIMLE-445 — Nodes list/detail with capacity bars and staleness

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Nodes list/detail with capacity bars and staleness" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/nodes.test.ts`, `src/stores/nodes.test.ts`
- **Source location(s)**: `src/routes/nodes.index.tsx`, `nodes.$nodeId.tsx`, `src/stores/useNodesStore.ts`, `src/repositories/http/nodes.ts`, `src/lib/format.ts`

#### GIMLE-446 — Tenants list/detail with quota management and delete

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Tenants list/detail with quota management and delete" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/tenants.test.ts`, `src/stores/tenants.test.ts`
- **Source location(s)**: `src/routes/tenants.index.tsx`, `tenants.$id.tsx`, `src/stores/useTenantsStore.ts`, `src/repositories/http/tenants.ts`

#### GIMLE-447 — Topology placement map

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Topology placement map" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/routes/topology.tsx`, `src/components/topology-drawer.tsx`

#### GIMLE-448 — Cluster metrics charts (lifecycle mix, capacity, quota pressure)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Cluster metrics charts (lifecycle mix, capacity, quota pressure)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/routes/metrics.tsx`, `src/components/chart-kit.tsx`, `src/components/metrics-history-panel.tsx`

#### GIMLE-449 — Per-process metrics history (Muninn-backed)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Per-process metrics history (Muninn-backed)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/metricsHistory.test.ts`
- **Source location(s)**: `src/components/metrics-history-panel.tsx`, `src/components/process-picker.tsx`, `src/stores/useMetricsHistoryStore.ts`, `src/repositories/http/metricsHistory.ts`

#### GIMLE-450 — Trace span history viewer

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Trace span history viewer" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/tracesHistory.test.ts`
- **Source location(s)**: `src/routes/traces.tsx`, `src/stores/useTracesStore.ts`, `src/repositories/http/tracesHistory.ts`

#### GIMLE-451 — Log explorer with live tailing

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Log explorer with live tailing" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/routes/logs.test.ts`, `src/repositories/http/logs.test.ts`
- **Source location(s)**: `src/routes/logs.tsx`, `src/stores/useLogStore.ts`, `src/repositories/http/logs.ts`

#### GIMLE-452 — Crash-dump (hs_err) listing on Logs screen

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Crash-dump (hs_err) listing on Logs screen" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/routes/logs.tsx`, `src/types/index.ts`

#### GIMLE-453 — Config entries management (per-tenant)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Config entries management (per-tenant)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/config.test.ts`
- **Source location(s)**: `src/routes/config.tsx`, `src/stores/useConfigStore.ts`, `src/repositories/http/config.ts`

#### GIMLE-454 — Secrets management (Fafnir-backed, versioned)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Secrets management (Fafnir-backed, versioned)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/useSecretsStore.test.ts`, `src/repositories/http/secrets.test.ts`
- **Source location(s)**: `src/routes/secrets.tsx`, `src/stores/useSecretsStore.ts`, `src/repositories/http/secrets.ts`

#### GIMLE-455 — Module artifact registry browser (Andvari-backed)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Module artifact registry browser (Andvari-backed)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/useArtifactsStore.test.ts`
- **Source location(s)**: `src/routes/artifacts.tsx`, `src/stores/useArtifactsStore.ts`, `src/repositories/http/artifacts.ts`

#### GIMLE-456 — RBAC access control (roles, role bindings, accounts)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "RBAC access control (roles, role bindings, accounts)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/roles.test.ts`, `roleBindings.test.ts`, `accounts.test.ts`
- **Source location(s)**: `src/routes/access-control.tsx`, `src/components/rbac/*`, `src/stores/useRolesStore.ts`, `useRoleBindingsStore.ts`, `useAccountsStore.ts`, `src/repositories/http/{roles,roleBindings,accounts}.ts`

#### GIMLE-457 — Audit trail viewer with filtering

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Audit trail viewer with filtering" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/useAuditStore.test.ts`
- **Source location(s)**: `src/routes/audit.tsx`, `src/stores/useAuditStore.ts`, `src/repositories/http/audit.ts`

#### GIMLE-458 — Control-plane status panel

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Control-plane status panel" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/routes/controlplane.tsx`

#### GIMLE-459 — Theme toggle (light/dark)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Theme toggle (light/dark)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/components/theme-toggle.tsx`, `src/stores/useThemeStore.ts`

#### GIMLE-460 — Playwright end-to-end smoke suite against a real cluster

- **Category**: Web Console / Testing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Playwright end-to-end smoke suite against a real cluster" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `e2e/greeter-smoke.spec.ts` (opt-in, `bun run test:e2e`, excluded from default Vitest run)
- **Source location(s)**: `gimle-console/e2e/greeter-smoke.spec.ts`, `playwright.config.ts`

#### GIMLE-585 — ConfigMaps screen

- **Category**: Web Console / Frontend
- **Status**: New  _(newly added as part of the ConfigMap kind (optimistic concurrent writes) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the ConfigMaps console screen against a real running cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `repositories/configmaps.test.ts` (Mock repository CRUD, stale-`expectedVersion` conflict, `expectedVersion=0` create case); `repositories/http/configmaps.test.ts` (HTTP repository request shapes, 409 mapped to `ConfigMapConflict`); `stores/useConfigMapsStore.test.ts` (store error surfacing, conflict state distinct from generic error, new-vs-selected `expectedVersion` selection)
- **Source location(s)**: `gimle-console/src/types/index.ts` (`ConfigMap`), `gimle-console/src/repositories/configmaps.ts`, `http/configmaps.ts`, `index.ts`, `gimle-console/src/stores/useConfigMapsStore.ts`, `gimle-console/src/routes/configmaps.tsx`, `components/app-sidebar.tsx`

#### GIMLE-586 — Service CRUD and live endpoint lookup (Networking screen)

- **Category**: Web Console / Frontend
- **Status**: New
- **Coverage**: Not Covered
- **Gap note**: No Holmgang step definition drives the console UI at all today -- every scenario drives the cluster through `ClusterApi`'s direct HTTP calls, and the console's own Playwright suite (`gimle-console/e2e/`) isn't wired into Holmgang. Closing this gap needs either a Holmgang step def that drives a headless browser against the console, or a Playwright scenario added to `e2e/` exercising the Networking screen's Services tab against a real cluster.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/services.test.ts`, `src/repositories/http/services.test.ts`
- **Source location(s)**: `gimle-console/src/routes/networking.tsx` (`ServicesTab`), `gimle-console/src/stores/useServicesStore.ts`, `gimle-console/src/repositories/http/services.ts`, `gimle-console/src/repositories/services.ts`

#### GIMLE-587 — NetworkPolicy CRUD (Networking screen)

- **Category**: Web Console / Frontend
- **Status**: New
- **Coverage**: Not Covered
- **Gap note**: Same gap as GIMLE-580: no Holmgang step definition drives the console UI, and the console's own Playwright suite doesn't cover the Networking screen yet. Closing this gap needs a Playwright scenario exercising the NetworkPolicies tab against a real cluster.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/networkPolicies.test.ts`, `src/repositories/http/networkPolicies.test.ts`
- **Source location(s)**: `gimle-console/src/routes/networking.tsx` (`NetworkPoliciesTab`), `gimle-console/src/stores/useNetworkPoliciesStore.ts`, `gimle-console/src/repositories/http/networkPolicies.ts`, `gimle-console/src/repositories/networkPolicies.ts`

#### GIMLE-593 — SecretMaps screen

- **Category**: Web Console / Frontend
- **Status**: New  _(newly added as part of the SecretMap kind (Fafnir-native, v1) work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the SecretMaps console screen against a real running cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `repositories/secretmaps.test.ts` (Mock repository CRUD, per-key independent versioning), `repositories/http/secretmaps.test.ts` (HTTP repository request shapes, base64 encoding), `stores/useSecretMapsStore.test.ts` (store error surfacing, per-key failure reporting distinct from a repository-level rejection).
- **Source location(s)**: `gimle-console/src/types/index.ts` (`SecretMap`, `SecretMapKeyMetadata`, `SecretMapKeyResult`), `gimle-console/src/repositories/secretmaps.ts`, `http/secretmaps.ts`, `index.ts`, `gimle-console/src/stores/useSecretMapsStore.ts`, `gimle-console/src/routes/secretmaps.tsx`, `components/app-sidebar.tsx`

#### GIMLE-596 — SecretMaps screen History panel

- **Category**: Web Console / Frontend
- **Status**: New  _(newly added as part of the SecretMap group-level versioning and rollback work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang Playwright/Cucumber scenario exercises the SecretMaps screen's History panel against a real running cluster -- see GIMLE-578's identical gapNote.
- **Other test coverage (non-Holmgang, informational only)**: `repositories/secretmaps.test.ts` (Mock repository group-version stamping and rollback), `repositories/http/secretmaps.test.ts` (HTTP request shapes for both new endpoints), `stores/useSecretMapsStore.test.ts` (`select` loading history, `rollback` refreshing both the SecretMap and its history, repository-level rejection surfaced as `store.error`).
- **Source location(s)**: `gimle-console/src/types/index.ts` (`SecretMapGroupVersion`, `SecretMapRollbackResult`), `gimle-console/src/repositories/secretmaps.ts`, `http/secretmaps.ts`, `fixture.ts`, `gimle-console/src/stores/useSecretMapsStore.ts`, `gimle-console/src/routes/secretmaps.tsx`

### gimle-fafnir-console

#### GIMLE-461 — Vault operator login/logout (session-cookie auth)

- **Category**: Web Console / Auth
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Vault operator login/logout (session-cookie auth)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/useAuthStore.test.ts`
- **Source location(s)**: `gimle-fafnir-console/src/routes/login.tsx`, `src/stores/useAuthStore.ts`, `src/repositories/http/auth.ts`; backend `FafnirServer.java`

#### GIMLE-462 — Vault status overview (uptime, active key, transport mode, tenants)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Vault status overview (uptime, active key, transport mode, tenants)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/routes/_shell.index.tsx`, `src/stores/useStatusStore.ts`, `src/repositories/http/status.ts`

#### GIMLE-463 — Secrets browsing/reveal/version/write/destroy (vault-native UI)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Secrets browsing/reveal/version/write/destroy (vault-native UI)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/secrets.test.ts`, `src/repositories/http/secrets.test.ts`
- **Source location(s)**: `src/routes/_shell.secrets.tsx`, `src/components/vault/SecretDialog.tsx`, `src/stores/useSecretsStore.ts`, `src/repositories/http/secrets.ts`

#### GIMLE-464 — Tenant filter via URL search param

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Tenant filter via URL search param" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/routes/_shell.secrets.tsx`

#### GIMLE-465 — Key rotation trigger

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Key rotation trigger" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `secrets.test.ts`, `http/secrets.test.ts`
- **Source location(s)**: `src/repositories/secrets.ts`/`http/secrets.ts`, `src/components/vault/StatusPill.tsx`

#### GIMLE-466 — Fafnir console error banner / global error capture

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Fafnir console error banner / global error capture" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/components/vault/ErrorBanner.tsx`, `src/lib/errors.ts`, `src/lib/error-capture.ts`

### gimle-andvari-console

#### GIMLE-467 — Andvari operator login/logout (session-cookie auth)

- **Category**: Web Console / Auth
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Andvari operator login/logout (session-cookie auth)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/__tests__/repositories.test.ts` — "returns an anonymous principal by default", "rejects empty credentials"
- **Source location(s)**: `gimle-andvari-console/src/routes/login.tsx`, `src/stores/authStore.ts`, `src/repositories/http/authRepository.ts`; backend `AndvariServer.java`

#### GIMLE-468 — Registry status overview (uptime, transport, recent pushes)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Registry status overview (uptime, transport, recent pushes)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/artifactsStore.test.ts` (partial)
- **Source location(s)**: `src/routes/_shell.index.tsx`, `src/stores/statusStore.ts`, `src/stores/artifactsStore.ts`, `src/repositories/http/statusRepository.ts`

#### GIMLE-469 — Artifact catalog browsing & search

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Artifact catalog browsing & search" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `repositories.test.ts` — "returns a sorted catalog of module ids"
- **Source location(s)**: `src/routes/_shell.artifacts.index.tsx`, `src/stores/artifactsStore.ts`, `src/repositories/http/artifactsRepository.ts`

#### GIMLE-470 — Artifact version detail (download, checksum display, delete)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Artifact version detail (download, checksum display, delete)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/stores/artifactsStore.test.ts`, `repositories.test.ts`
- **Source location(s)**: `src/routes/_shell.artifacts.$moduleId.tsx`, `src/stores/artifactsStore.ts`, `src/repositories/http/artifactsRepository.ts`

#### GIMLE-471 — Client-side SHA-256 checksum verification on download

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Client-side SHA-256 checksum verification on download" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/lib/hash.test.ts`
- **Source location(s)**: `src/lib/hash.ts` (Web Crypto API)

#### GIMLE-472 — Push artifact dialog (drag-and-drop upload)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Push artifact dialog (drag-and-drop upload)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `repositories.test.ts` — "rejects re-pushing an existing version with 409"
- **Source location(s)**: `src/components/PushArtifactDialog.tsx`, `src/stores/artifactsStore.ts`

#### GIMLE-473 — Maven-2 repository interop view

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Maven-2 repository interop view" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/routes/_shell.repository.tsx`, `src/lib/format.ts`

#### GIMLE-474 — Andvari copy-to-clipboard utility

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Andvari copy-to-clipboard utility" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `src/components/CopyButton.tsx`

### gimle-saga-console

#### GIMLE-475 — Runs list (no authentication)

- **Category**: Web Console / Reporting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Runs list (no authentication)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/runs.test.ts` — "listRuns fetches /api/runs and maps every entry"
- **Source location(s)**: `gimle-saga-console/src/routes/index.tsx`, `src/stores/runsStore.ts`, `src/repositories/http/runs.ts`

#### GIMLE-476 — Live run detail with streaming test feed

- **Category**: Web Console / Reporting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Live run detail with streaming test feed" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/runs.test.ts` — "followRunEvents streams new finished-test events and skips the already-known count"
- **Source location(s)**: `src/routes/runs.$runId.tsx`, `src/components/saga/RunFeed.tsx`, `src/stores/runDetailStore.ts`, `src/repositories/http/eventsClient.ts`, `src/repositories/http/runs.ts`

#### GIMLE-477 — Run attachments: Gherkin scenario tree, Chaos ledger, Surtr phase table

- **Category**: Web Console / Reporting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Run attachments: Gherkin scenario tree, Chaos ledger, Surtr phase table" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/mapping.test.ts` — "groups attachment events by kind and skips unparseable or unrecognized payloads", "accepts a payload shipped as an array of the shape"
- **Source location(s)**: `src/components/saga/RunAttachments.tsx`, `src/routes/runs.$runId.tsx`

#### GIMLE-478 — Test detail / per-test history

- **Category**: Web Console / Reporting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Test detail / per-test history" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/testHistory.test.ts`
- **Source location(s)**: `src/routes/tests.$testId.tsx`, `src/stores/testHistoryStore.ts`, `src/repositories/http/testHistory.ts`

#### GIMLE-479 — Compare two runs (diff view)

- **Category**: Web Console / Reporting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Compare two runs (diff view)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/mapping.test.ts`, `runs.test.ts`
- **Source location(s)**: `src/routes/compare.tsx`, `src/stores/compareStore.ts`, `src/repositories/http/mapping.ts`, `src/repositories/http/runs.ts`

#### GIMLE-480 — Gjallarhorn flake scoreboard

- **Category**: Web Console / Reporting
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Gjallarhorn flake scoreboard" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `src/repositories/http/flaky.test.ts`
- **Source location(s)**: `src/routes/gjallarhorn.tsx`, `src/stores/flakyStore.ts`, `src/repositories/http/flaky.ts`

#### GIMLE-481 — Saga console theming (no auth surface)

- **Category**: Web Console / Frontend
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Holmgang drives the cluster's HTTP API directly and never opens a browser -- console-level behavior is structurally outside its reach. Verifying "Saga console theming (no auth surface)" end to end would need a browser-driven scenario (Playwright, as `gimle-console/e2e/` already does for one flow), not a Cucumber/step-definition one; it does not belong in Holmgang.
- **Other test coverage (non-Holmgang, informational only)**: `SagaServerTest.java` — "the_bundled_console_is_served_at_console"
- **Source location(s)**: `gimle-saga/src/main/java/com/gimle/saga/SagaServer.java` (no `/auth/*` contexts), `gimle-saga-console`

### gimle-saga

#### GIMLE-482 — NDJSON event ingest API

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "NDJSON event ingest API" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaServerTest.java` — "ingested_events_round_trip_through_the_runs_and_events_apis", "a_malformed_ingest_line_is_rejected_with_its_line_number"; `SagaStoreTest.java#ingest_then_read_round_trips_events_and_meta`
- **Source location(s)**: `gimle-saga/src/main/java/com/gimle/saga/SagaServer.java`, `SagaStore.ingest`

#### GIMLE-483 — Idempotent per-run ingest / re-ingest replacement

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Idempotent per-run ingest / re-ingest replacement" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#re_ingesting_a_whole_run_replaces_it_without_double_counting_the_ledger`
- **Source location(s)**: `SagaStore.ingestRun`

#### GIMLE-484 — Crash-safe append (torn-tail recovery)

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Crash-safe append (torn-tail recovery)" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#a_torn_trailing_line_is_skipped_on_read`, `#an_append_after_a_torn_line_never_fuses_two_events_into_one`
- **Source location(s)**: `SagaStore.truncateTornTail`, `SagaStore.completeLines`

#### GIMLE-485 — Surefire/Failsafe XML import

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Surefire/Failsafe XML import" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SurefireXmlImporterTest.java`, `SagaServerTest.java#importing_surefire_xml_with_a_flaky_failure_lands_a_run_and_a_flake_observation`
- **Source location(s)**: `SurefireXmlImporter.java`, `SagaServer.handleImport`

#### GIMLE-486 — Fold-import safety net for a live run's gap

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Fold-import safety net for a live run's gap" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#fold_appends_only_test_ids_the_live_stream_never_finished_and_drops_framing`, `#fold_without_an_existing_run_ingests_the_batch_unmodified`
- **Source location(s)**: `SagaStore.fold`, `SagaServer.handleImport`

#### GIMLE-487 — Run listing, detail, and cursor-paginated event reads

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Run listing, detail, and cursor-paginated event reads" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#runs_list_newest_first_and_honors_the_limit`, `#a_run_with_events_but_no_meta_file_is_reconstructed_from_its_events`, `#the_events_cursor_resumes_from_a_line_offset`; `SagaServerTest.java#an_unknown_run_returns_404`
- **Source location(s)**: `SagaServer.handleRuns`, `SagaStore.listRuns`, `SagaStore.run`, `SagaStore.readEvents`, `RunMeta.fold`

#### GIMLE-488 — Live NDJSON tail (`follow=true`) of a run's event stream

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Live NDJSON tail (`follow=true`) of a run's event stream" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaServerTest.java#follow_streams_new_lines_as_they_arrive_and_ends_when_the_run_finishes`
- **Source location(s)**: `SagaServer.streamFollow`, `SagaServer.isTerminal`

#### GIMLE-489 — Abandoned-run detection on restart

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Abandoned-run detection on restart" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#a_live_run_is_marked_abandoned_at_startup`
- **Source location(s)**: `SagaStore.markLiveRunsAbandoned`, `RunMeta.RunStatus.ABANDONED`

#### GIMLE-490 — Flake ledger derivation (fail-then-pass rule) and rebuild

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Flake ledger derivation (fail-then-pass rule) and rebuild" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#a_failed_attempt_followed_by_a_passing_retry_yields_one_flake_observation`, `#a_test_that_fails_every_attempt_yields_no_flake_observation`, `#rebuild_ledger_reproduces_the_derived_observations_from_scratch`, `#an_unparseable_ledger_line_is_skipped_not_fatal`
- **Source location(s)**: `SagaStore.deriveFlakeObservations`, `SagaStore.rebuildLedger`, `FlakeObservation.java`

#### GIMLE-491 — Flaky scoreboard with time-window ranking

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Flaky scoreboard with time-window ranking" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#the_flaky_scoreboard_counts_runs_seen_and_ranks_by_score`, `#the_flaky_scoreboard_window_excludes_older_observations`; `SagaServerTest.java#flaky_entries_carry_quarantine_status_and_the_response_carries_the_budget_allowance`
- **Source location(s)**: `SagaServer.handleFlaky`, `SagaStore.flakyScoreboard`

#### GIMLE-492 — Test-tag index and quarantine status

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Test-tag index and quarantine status" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#a_test_tagged_flaky_is_quarantined_and_an_untagged_one_is_not`, `#the_latest_tag_set_for_a_test_id_overwrites_an_earlier_one`, `#the_test_tags_index_survives_a_store_restart`
- **Source location(s)**: `SagaStore.updateTestTags`, `SagaStore.quarantined`, `SagaStore.loadTestTags`

#### GIMLE-493 — Per-test history endpoint

- **Category**: Reporting backend / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Per-test history endpoint" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#test_history_reports_final_outcome_and_flakiness_per_run_newest_first`
- **Source location(s)**: `SagaServer.handleTestHistory`, `SagaStore.testHistory`

#### GIMLE-494 — Path traversal protection on run IDs

- **Category**: Internal-Infra / Security
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Path traversal protection on run IDs" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaStoreTest.java#a_run_id_that_could_escape_the_store_directory_is_rejected`
- **Source location(s)**: `SagaStore.RUN_ID`, `SagaStore.validateRunId`

#### GIMLE-495 — Bundled console static serving

- **Category**: Internal-Infra / Config
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Unit-level/wire-format/internal-infra mechanism -- not independently observable as a black-box cluster assertion. A Holmgang scenario could at best exercise "Bundled console static serving" *indirectly* by driving a higher-level behavior that happens to depend on it (as several existing scenarios already do for the RPC/codec layers under them), but could not verify this specific mechanism the way `saga`'s own unit test does.
- **Other test coverage (non-Holmgang, informational only)**: `SagaServerTest.java#the_bundled_console_is_served_at_console`
- **Source location(s)**: `SagaMain.java`, `SagaServer.serveConsole`

### gimle-testkit

#### GIMLE-496 — Poll-until-condition primitive (`Await`)

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Poll-until-condition primitive (`Await`) is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/Await.java`

#### GIMLE-497 — Kernel-assigned loopback port leasing (`PortLease`)

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Kernel-assigned loopback port leasing (`PortLease`) is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `PortLeaseTest`
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/PortLease.java`

#### GIMLE-498 — Heimdall event-driven cluster condition harness

- **Category**: Test Infrastructure
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Heimdall event-driven cluster condition harness is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/Heimdall.java`, `HeimdallScope.java`, `HeimdallCondition.java`

#### GIMLE-499 — Replica-scoped condition observation

- **Category**: Test Infrastructure
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Replica-scoped condition observation is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: Exercised by `HaTopologyIT.deployments_written_via_one_replica_are_observed_active_via_the_other`, `deployment-lifecycle.feature`
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/HeimdallScope.java`, `Heimdall.java`

#### GIMLE-500 — Deployment/node/log condition builders

- **Category**: Test Infrastructure
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Deployment/node/log condition builders is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: Exercised throughout gimle-holmgang `*.feature` files and `HaTopologyIT`/`MinimalTopologyIT`
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/DeploymentConditions.java`, `NodeConditions.java`, `LogConditions.java`

#### GIMLE-501 — Time-windowed negative invariants (`Invariant`/`InvariantGuard`)

- **Category**: Test Infrastructure
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Time-windowed negative invariants (`Invariant`/`InvariantGuard`) is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `InvariantTest`; `rolling-update.feature`, `quota-and-admission.feature`
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/Invariants.java`, `InvariantGuard.java`, `Invariant.java`

#### GIMLE-502 — Forensic failure reporting

- **Category**: Test Infrastructure
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Forensic failure reporting is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `ForensicReportTest`; `MinimalTopologyIT.a_failed_condition_reports_the_cluster_state_it_gave_up_on`
- **Source location(s)**: `gimle-testkit/src/main/java/com/gimle/testkit/heimdall/ForensicReport.java`, `HeimdallConditionError.java`

### gimle-examples

#### GIMLE-503 — `hello-module` — minimal inert deployable fixture

- **Category**: Sample Module
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- `hello-module` — minimal inert deployable fixture is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-examples/hello-module/src/main/resources/META-INF/gimle/gimle-module.yaml`, `Hello.java`, `deployment.yaml`

#### GIMLE-504 — `greeter-provider` — real fabric service export with lifecycle hooks and health probes

- **Category**: Sample Module
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A consumer completes a real fabric call to a provider*
  - _Why this counts_: Deploys greeter-provider and greeter-consumer on the single-node 'minimal' topology and asserts the consumer's log shows a real cross-worker (same-machine, UDS-tier) fabric call reply.
- **Other test coverage (non-Holmgang, informational only)**: `GreeterClusterTopologyIT`; multiple gimle-holmgang `*.feature`/`*IT`
- **Source location(s)**: `gimle-examples/greeter-provider/src/main/java/com/gimle/examples/greeter/provider/GreeterProviderHooks.java`, probes, `gimle-module.yaml`

#### GIMLE-505 — `greeter-consumer` — real cross-worker fabric call with MDC-tagged background caller

- **Category**: Sample Module
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A consumer completes a real fabric call to a provider*
  - _Why this counts_: Deploys greeter-provider and greeter-consumer on the single-node 'minimal' topology and asserts the consumer's log shows a real cross-worker (same-machine, UDS-tier) fabric call reply.
- **Other test coverage (non-Holmgang, informational only)**: `GreeterClusterTopologyIT`; `deployment-lifecycle.feature`
- **Source location(s)**: `gimle-examples/greeter-consumer/src/main/java/com/gimle/examples/greeter/consumer/GreeterConsumerHooks.java`, probes, `gimle-module.yaml`

#### GIMLE-506 — `greeter-load-generator` — HTTP bridge for external load tools driving real fabric traffic

- **Category**: Sample Module / Load Testing
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/autoscale.feature` — Scenario: *Request-rate load scales the provider up*
  - _Why this counts_: Drives real Gatling HTTP load through the deployed greeter-load-generator, forcing AutoscaleReconciler's request-rate signal (CPU target deliberately unreachable) to scale the deployment from 1 to 2 replicas.
- **Other test coverage (non-Holmgang, informational only)**: `AutoscaleIT.a_deployment_scales_up_under_real_gatling_generated_request_rate_load`; `autoscale.feature`
- **Source location(s)**: `gimle-examples/greeter-load-generator/src/main/java/com/gimle/examples/greeter/loadgen/GreeterLoadGeneratorHooks.java`, `gimle-module.yaml`

### gimle-smoke-tests

#### GIMLE-507 — Real multi-process cluster fixture (store/control-plane/agent/Fafnir/Muninn)

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Real multi-process cluster fixture (store/control-plane/agent/Fafnir/Muninn) is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: Base fixture for every `*IT` in this module (24 concrete IT classes)
- **Source location(s)**: `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterSmokeClusterSupport.java`

#### GIMLE-508 — On-the-fly compiled module variants via `TestModuleBuilder`

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- On-the-fly compiled module variants via `TestModuleBuilder` is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: Used by ClassloaderLeakIT, RedeployStabilityIT, SelfHealingIT, ServiceFabricIT, Tier1DensityIT, JobLifecycleIT, StatefulSetPersistenceIT, AutoscaleIT, RollingUpdateIT, SurgePromotionIT
- **Source location(s)**: `GreeterSmokeClusterSupport.java` (buildGreeterProviderVariant, buildProviderV2Jar, buildFaultyProviderJar, buildAlwaysBrokenProviderJar, buildAlwaysUnhealthyProviderJar, buildLeakyProviderJar, buildInertTier1ModuleJar, buildQuickSucceedingJobModuleJar, buildStatefulModuleJar, buildSlowProviderJar)

#### GIMLE-509 — Base cluster topology deploy across store cluster and multiple CP replicas

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Base cluster topology deploy across store cluster and multiple CP replicas is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `GreeterClusterTopologyIT.greeter_modules_deploy_across_a_store_cluster_and_multiple_control_plane_replicas`
- **Source location(s)**: `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterClusterTopologyIT.java`

#### GIMLE-510 — Raft store resilience (member loss, leader failover, live membership change)

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Raft store resilience (member loss, leader failover, live membership change) is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `RaftResilienceIT.cluster_tolerates_losing_one_store_node_mid_deployment`, `a_leader_failover_loses_no_acknowledged_write_under_concurrent_load`, `a_new_store_node_joins_via_live_membership_change_and_is_then_removed`
- **Source location(s)**: `gimle-smoke-tests/src/test/java/com/gimle/smoketests/RaftResilienceIT.java`

#### GIMLE-511 — Tiered self-healing (worker respawn, liveness-exhaustion escalation to FAILED)

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Tiered self-healing (worker respawn, liveness-exhaustion escalation to FAILED) is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `SelfHealingIT.a_crashed_workers_instance_is_respawned_and_returns_to_active`, `a_module_that_never_passes_its_own_liveness_check_exhausts_its_restart_budget_and_fails`
- **Source location(s)**: `gimle-smoke-tests/src/test/java/com/gimle/smoketests/SelfHealingIT.java`

#### GIMLE-512 — Classloader leak detection wired into a real worker

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Classloader leak detection wired into a real worker is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `ClassloaderLeakIT.a_module_that_leaks_its_own_classloader_on_redeploy_is_reported_by_leak_tracker`
- **Source location(s)**: `ClassloaderLeakIT.java` (also fixed a real gap: WorkerMain previously never wired LeakTracker into the real ModuleController)

#### GIMLE-513 — Repeated redeploy stability without false-positive leaks

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Repeated redeploy stability without false-positive leaks is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `RedeployStabilityIT.a_well_behaved_module_survives_repeated_redeploys_without_ever_reporting_a_leak`
- **Source location(s)**: `RedeployStabilityIT.java`

#### GIMLE-514 — Tier 1 worker density packing and its cap

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Tier 1 worker density packing and its cap is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `Tier1DensityIT` (density-packing test)
- **Source location(s)**: `Tier1DensityIT.java`

#### GIMLE-515 — Node cordoning blocks new placement without evicting running instances

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Node cordoning blocks new placement without evicting running instances is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `NodeCordoningIT.a_cordoned_node_blocks_new_placement_but_never_evicts_an_already_running_instance`
- **Source location(s)**: `NodeCordoningIT.java`

#### GIMLE-516 — DaemonSet per-node fan-out and dead-node assignment cleanup

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- DaemonSet per-node fan-out and dead-node assignment cleanup is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `DaemonSetLifecycleIT.a_daemonset_places_on_every_node_and_a_dead_nodes_assignment_is_cleaned_up`
- **Source location(s)**: `DaemonSetLifecycleIT.java`

#### GIMLE-517 — Job and CronJob real-cluster lifecycle

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Job and CronJob real-cluster lifecycle is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `JobLifecycleIT.a_job_that_succeeds_reaches_the_succeeded_phase_and_stays_there`, `a_triggered_cronjob_generates_a_real_job_that_reaches_succeeded`
- **Source location(s)**: `JobLifecycleIT.java`

#### GIMLE-518 — StatefulSet sticky placement and volume persistence across worker restart

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- StatefulSet sticky placement and volume persistence across worker restart is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `StatefulSetPersistenceIT.a_statefulset_instance_keeps_its_sticky_node_and_its_volume_data_across_a_worker_restart`
- **Source location(s)**: `StatefulSetPersistenceIT.java`

#### GIMLE-519 — Rolling update preserves serving capacity and reaches new version

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Rolling update preserves serving capacity and reaches new version is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `RollingUpdateIT.a_rolling_update_keeps_at_least_one_instance_serving_traffic_throughout`, `a_single_replica_rolling_update_has_real_observed_downtime`
- **Source location(s)**: `RollingUpdateIT.java`

#### GIMLE-520 — Surge worker promotion carries out via in-place retarget, not respawn

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Surge worker promotion carries out via in-place retarget, not respawn is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `SurgePromotionIT.a_promoted_surge_worker_keeps_the_same_process_while_the_restarted_one_does_not`
- **Source location(s)**: `SurgePromotionIT.java`

#### GIMLE-521 — Autoscaling under real request-rate, error-rate, queue-depth, and weighted-blended load

- **Category**: Load Testing / Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Autoscaling under real request-rate, error-rate, queue-depth, and weighted-blended load is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `AutoscaleIT.a_deployment_scales_up_under_real_gatling_generated_request_rate_load`, `a_deployment_scales_up_under_real_error_rate_load`, `a_deployment_scales_up_under_real_queue_depth_load`, `a_weighted_policy_blends_request_rate_and_queue_depth_signals_under_real_load`
- **Source location(s)**: `AutoscaleIT.java`

#### GIMLE-522 — Multi-tenant quota enforcement (flag-not-evict, and admission rejection)

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Multi-tenant quota enforcement (flag-not-evict, and admission rejection) is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `QuotaIT.a_tenant_over_quota_deployment_is_flagged_but_not_evicted`, `a_deployment_that_would_exceed_tenant_quota_is_rejected_at_admission`
- **Source location(s)**: `QuotaIT.java`

#### GIMLE-523 — Circuit breaker excludes a consistently-failing replica

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Circuit breaker excludes a consistently-failing replica is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `ServiceFabricIT.a_circuit_breaker_excludes_a_consistently_failing_replica_after_real_failures`
- **Source location(s)**: `ServiceFabricIT.java`

#### GIMLE-524 — Gossip/SWIM failure detection across real separate agent processes

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Gossip/SWIM failure detection across real separate agent processes is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `GossipFailureDetectionIT.a_hard_killed_member_converges_to_dead_on_both_surviving_real_agents`
- **Source location(s)**: `GossipFailureDetectionIT.java`

#### GIMLE-525 — Observability data survives agent death (Muninn fallback) and control-plane metrics round-trip

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Observability data survives agent death (Muninn fallback) and control-plane metrics round-trip is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `ObservabilityIT.a_deployed_instances_log_survives_its_owning_agent_dying`, `a_control_planes_own_request_metrics_round_trip_through_muninn`
- **Source location(s)**: `ObservabilityIT.java`

#### GIMLE-526 — Worker-tier metrics/trace relay to Muninn via the agent

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Worker-tier metrics/trace relay to Muninn via the agent is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: single @Test in WorkerObservabilityIT
- **Source location(s)**: `WorkerObservabilityIT.java`

#### GIMLE-527 — Artifact registry (Andvari) resolution path end to end

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- Artifact registry (Andvari) resolution path end to end is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `AndvariRegistryIT.a_coordinate_only_deployment_pulls_its_jar_from_andvari_through_the_agent_cache`
- **Source location(s)**: `AndvariRegistryIT.java`

#### GIMLE-528 — External HTTP request reaches a fabric service through the gateway

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is itself JUnit-based test/example infrastructure -- External HTTP request reaches a fabric service through the gateway is exercised by `gimle-smoke-tests`'/`gimle-testkit`'s own real-process tests (see Other test coverage), never by a Holmgang Cucumber scenario; per the strict rule that does not count as Covered.
- **Other test coverage (non-Holmgang, informational only)**: `GatewayFabricRouteIT.an_external_http_request_reaches_a_real_fabric_service_through_the_gateway`
- **Source location(s)**: `GatewayFabricRouteIT.java`

### gimle-holmgang

#### GIMLE-529 — Declarative cluster topology DSL/YAML parsing and validation

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `ClusterTopologyDslTest`, `ClusterTopologyParserTest`, `GimleClusterStartRejectionTest.a_fault_proxied_mtls_topology_is_rejected_at_model_construction`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/topology/ClusterTopology.java`, `ClusterTopologyParser.java`, `ClusterSpec.java`, `NodeSpec.java`, `Transport.java`

#### GIMLE-530 — Real subprocess cluster orchestration (`GimleCluster`)

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: Foundation for every scenario in this module
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/cluster/GimleCluster.java`, `GimleProcess.java`, `ManagedProcess.java`, `ClusterApi.java`

#### GIMLE-531 — Cluster pooling per topology with destructive-scenario isolation

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: Exercised implicitly by every HolmgangIT-run Gherkin scenario
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/steps/ClusterPool.java`

#### GIMLE-532 — JUnit `@Holmgang`/`@HolmgangCluster` extension for plain-JUnit cluster tests

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `HaTopologyIT`, `MinimalTopologyIT` both use it directly
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/junit/Holmgang.java`, `HolmgangCluster.java`, `HolmgangExtension.java`

#### GIMLE-533 — Fenrir randomized chaos-fault soak executor

- **Category**: Chaos Engineering
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/chaos-soak.feature` — Scenario: *The cluster survives a randomized fault soak with no lost writes*
  - _Why this counts_: Unleashes Fenrir's weighted fault palette against a live write workload for 60s, asserting the chaos ledger records >=3 executed+recovered faults and every acknowledged write is readable.
  - `gimle-holmgang/src/test/resources/features/observability-registry-ha.feature` — Scenario: *Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces*
  - _Why this counts_: Pushes an artifact, strikes only Muninn/Andvari bounce faults via Fenrir, then asserts a coordinate-only deployment still reaches ACTIVE and control-plane metrics still ship to Muninn afterward.
- **Other test coverage (non-Holmgang, informational only)**: `FenrirPlanTest`, `ChaosScheduleTest`; end-to-end via `chaos-soak.feature`/`observability-registry-ha.feature`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/fenrir/Fenrir.java`, `FaultKind.java`, `FenrirPlan.java`, `ChaosSchedule.java`, `Pool.java`

#### GIMLE-534 — Chaos ledger recording and rendering

- **Category**: Chaos Engineering
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/chaos-soak.feature` — Scenario: *The cluster survives a randomized fault soak with no lost writes*
  - _Why this counts_: Unleashes Fenrir's weighted fault palette against a live write workload for 60s, asserting the chaos ledger records >=3 executed+recovered faults and every acknowledged write is readable.
- **Other test coverage (non-Holmgang, informational only)**: `ChaosLedgerTest`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/fenrir/ChaosLedger.java`

#### GIMLE-535 — Randomized fault soak with no lost writes (basic and compound-fault modes)

- **Category**: Chaos Engineering
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/chaos-soak.feature` — Scenario: *The cluster survives a randomized fault soak with no lost writes*
  - _Why this counts_: Unleashes Fenrir's weighted fault palette against a live write workload for 60s, asserting the chaos ledger records >=3 executed+recovered faults and every acknowledged write is readable.
  - `gimle-holmgang/src/test/resources/features/chaos-soak.feature` — Scenario: *The cluster survives a compound-fault soak with overlapping faults and no lost writes*
  - _Why this counts_: Same as above but in compound-fault mode (a later strike can fire before the prior one's recovery gate clears), asserting >=4 executed faults and no lost writes.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `chaos-soak.feature`'s two scenarios
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/chaos-soak.feature`; `ChaosSteps.java`, `WorkloadSteps.java`

#### GIMLE-536 — Muninn/Andvari replica-bounce resilience soak

- **Category**: Chaos Engineering
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/observability-registry-ha.feature` — Scenario: *Artifact push/pull and shipped metrics survive Muninn and Andvari replica bounces*
  - _Why this counts_: Pushes an artifact, strikes only Muninn/Andvari bounce faults via Fenrir, then asserts a coordinate-only deployment still reaches ACTIVE and control-plane metrics still ship to Muninn afterward.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `observability-registry-ha.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/observability-registry-ha.feature`; `Fenrir.muninnBounce`/`andvariBounce`

#### GIMLE-537 — Live store membership change (AddServer/RemoveServer)

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/membership-change.feature` — Scenario: *A fourth store joins and then leaves, one server at a time*
  - _Why this counts_: Drives a real AddServer then RemoveServer against a live 3-node store cluster via the topology's own bring-up path, asserting member count and continued write acceptance at each step.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `membership-change.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/membership-change.feature`; `ClusterSteps.java`

#### GIMLE-538 — Mutual TLS end-to-end operation and anonymous-client rejection

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *The cluster functions end to end over mutual TLS*
  - _Why this counts_: Boots the whole cluster under the 'mtls' topology (every hop mTLS, agent CSR bootstrap via token) and asserts a tenant secret still round-trips end to end.
  - `gimle-holmgang/src/test/resources/features/mtls.feature` — Scenario: *An anonymous client cannot write*
  - _Why this counts_: Asserts a request with no client certificate against the mTLS-mode API server is rejected with 401.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `mtls.feature`'s two scenarios
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/mtls.feature`; `SecuritySteps.java`

#### GIMLE-539 — Control-plane partition tolerance (store-side) and reconvergence on heal

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` — Scenario: *A control plane cut off from the store stops serving and reconverges after heal*
  - _Why this counts_: Uses a Loki fault proxy to sever control-plane-replica-1's link to every store, asserts it stops serving within 30s while replica 0 keeps working, then asserts reconvergence through replica 1 on heal.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `partition-tolerance.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` (scenario 1)

#### GIMLE-540 — Store leader self-demotion under silent peer partition; bounded write latency

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` — Scenario: *A store leader silently partitioned from its peers steps down and writes stay bounded*
  - _Why this counts_: Uses a Loki fault proxy to isolate the Raft leader from its peers (no crash, just silence) and asserts check-quorum self-demotion within 10s, plus a submitted write completing (not hanging) within 30s.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `partition-tolerance.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` (scenario 2); `WorkloadSteps.java`

#### GIMLE-541 — Tenant deployment lifecycle with secret delivery and clean deletion

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature` — Scenario: *A tenant-scoped module deploys, reads its secret, and is cleanly removed*
  - _Why this counts_: Submits a real tenant-scoped Deployment manifest via the HTTP API, asserts the instance's own log shows a Fafnir-delivered secret value, then deletes the deployment and asserts it drains away completely -- exercising CRUD, placement reconciliation, secret delivery, and orphan cleanup together.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `deployment-lifecycle.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/deployment-lifecycle.feature`

#### GIMLE-542 — Tenant quota retroactive violation (flag, not evict) and admission rejection

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/quota-and-admission.feature` — Scenario: *A retroactive quota violation is flagged but never evicts*
  - _Why this counts_: Lowers a tenant's quota below its already-running usage and asserts the deployment is flagged quota-violating while its instance keeps running for 10s.
  - `gimle-holmgang/src/test/resources/features/quota-and-admission.feature` — Scenario: *An over-quota deployment is rejected at admission*
  - _Why this counts_: Submits a deployment against a starved tenant's quota and asserts the submission is rejected outright with HTTP 409.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `quota-and-admission.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/quota-and-admission.feature`

#### GIMLE-543 — Node cordoning blocks placement until uncordoned

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/scheduling.feature` — Scenario: *A cordoned node blocks placement until uncordoned*
  - _Why this counts_: Cordons the sole node, submits a deployment, asserts it stays unplaced for 10s, then uncordons and asserts it reaches ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `scheduling.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/scheduling.feature`

#### GIMLE-544 — Worker-tier self-healing and liveness-exhaustion escalation (Gherkin coverage)

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/self-healing.feature` — Scenario: *A killed worker JVM is respawned and the deployment returns to ACTIVE*
  - _Why this counts_: Hard-kills the worker JVM hosting a running instance and asserts a new worker respawns and hosts it, with the deployment returning to ACTIVE.
  - `gimle-holmgang/src/test/resources/features/self-healing.feature` — Scenario: *A module that never passes liveness is escalated to FAILED for good*
  - _Why this counts_: Deploys a provider variant whose LivenessProbe always reports false and asserts it escalates to a terminal FAILED instance once its restart budget (RestartTracker) is exhausted.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `self-healing.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/self-healing.feature`

#### GIMLE-545 — Zero-downtime rolling update under surge budget (Gherkin coverage)

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/rolling-update.feature` — Scenario: *Zero-downtime rollout under a surge budget*
  - _Why this counts_: Rolls a 2-replica deployment to a genuinely rebuilt v1.1.0 artifact under maxUnavailable=1/maxSurge=1, holding an invariant that at least 1 instance stays ACTIVE throughout, then asserts both instances end on the new version.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `rolling-update.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/rolling-update.feature`

#### GIMLE-546 — Request-rate autoscaling under real Gatling-driven fabric load (Gherkin coverage)

- **Category**: Load Testing
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/autoscale.feature` — Scenario: *Request-rate load scales the provider up*
  - _Why this counts_: Drives real Gatling HTTP load through the deployed greeter-load-generator, forcing AutoscaleReconciler's request-rate signal (CPU target deliberately unreachable) to scale the deployment from 1 to 2 replicas.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `autoscale.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/autoscale.feature`; `LoadGenerator.java`, `GreeterLoadSimulation.java`, `LoadSteps.java`

#### GIMLE-547 — Artifact registry coordinate-only deployment (Gherkin coverage)

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/registry-deploy.feature` — Scenario: *A pushed module deploys by coordinate with no artifact path*
  - _Why this counts_: Pushes a real jar to Andvari, then submits a manifest with a coordinate and no artifactPath, asserting it resolves through the real agent pull-through cache and reaches ACTIVE.
- **Other test coverage (non-Holmgang, informational only)**: `HolmgangIT` executing `registry-deploy.feature`
- **Source location(s)**: `gimle-holmgang/src/test/resources/features/registry-deploy.feature`; `RegistrySteps.java`

#### GIMLE-548 — Surtr scale/churn/performance workload runner

- **Category**: Load Testing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `SurtrWorkloadParserTest`, `SurtrUnitTest`; `SurtrIT.runs_the_configured_surtr_workload` (opt-in via `-Dgimle.surtr.workload=<name|path>`)
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/surtr/SurtrRunner.java`, `SurtrWorkload.java`, `SurtrJob.java`, `TokenBucket.java`, `Measurements.java`

#### GIMLE-549 — Surtr Muninn-window measurement (documented gap)

- **Category**: Load Testing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/surtr/SurtrRunner.java` (`skippedMuninn()`)

#### GIMLE-550 — Module-density Tier 1 packing Surtr reference workload

- **Category**: Load Testing
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `SurtrIT` (opt-in, `-Dgimle.surtr.workload=module-density`)
- **Source location(s)**: `gimle-holmgang/src/test/resources/workloads/module-density.yaml`, `topologies/surtr-density.yaml`

#### GIMLE-551 — Saga unified run reporting (Gherkin + JUnit + Fenrir + Surtr)

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `SagaWriterTest`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/saga/SagaCollector.java`, `SagaWriter.java`, `SagaCucumberPlugin.java`, `SagaJUnitListener.java`

#### GIMLE-552 — Saga best-effort shipping to a remote report server

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/saga/SagaShipper.java`

#### GIMLE-553 — Loki fault-injection proxy for store/control-plane link partitions

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Covered
- **Holmgang feature file(s) + scenario(s)**:
  - `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` — Scenario: *A control plane cut off from the store stops serving and reconverges after heal*
  - _Why this counts_: Uses a Loki fault proxy to sever control-plane-replica-1's link to every store, asserts it stops serving within 30s while replica 0 keeps working, then asserts reconvergence through replica 1 on heal.
  - `gimle-holmgang/src/test/resources/features/partition-tolerance.feature` — Scenario: *A store leader silently partitioned from its peers steps down and writes stay bounded*
  - _Why this counts_: Uses a Loki fault proxy to isolate the Raft leader from its peers (no crash, just silence) and asserts check-quorum self-demotion within 10s, plus a submitted write completing (not hanging) within 30s.
- **Other test coverage (non-Holmgang, informational only)**: Exercised via `partition-tolerance.feature`; no dedicated LokiTest
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/loki/Loki.java`, `LokiProxy.java`

#### GIMLE-554 — Utgard multi-container distributed boot ordering

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `UtgardDistributedBootIT.a_machine_started_out_of_dependency_order_blocks_then_completes_once_its_prerequisites_are_up`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardDistributedBootIT.java`

#### GIMLE-555 — Utgard real machine loss (hard container kill) and rejoin

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `UtgardMachineLossIT.a_killed_machine_is_rescheduled_around_and_can_rejoin_after_restart`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardMachineLossIT.java`

#### GIMLE-556 — Utgard network partition (vs hard kill) with reconvergence

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `UtgardPartitionIT.a_partitioned_machine_is_rescheduled_around_then_the_cluster_converges_on_reconnect`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardPartitionIT.java`

#### GIMLE-557 — Utgard real-hostname mTLS bootstrap across containers

- **Category**: Cluster Validation
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `UtgardMtlsIT.an_mtls_cluster_bootstraps_across_containers_addressed_by_real_hostnames`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardMtlsIT.java`

#### GIMLE-558 — Utgard Docker container fleet management primitives

- **Category**: Internal/Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: `UtgardExecTest`, `UtgardPollTest`, `UtgardTopologiesTest`
- **Source location(s)**: `gimle-holmgang/src/test/java/com/gimle/holmgang/utgard/UtgardMachines.java`, `UtgardExec.java`, `UtgardForensics.java`, `UtgardPoll.java`, `UtgardTopologies.java`

#### GIMLE-559 — Docker Compose manual validation topologies (bundled-JRE and full-JRE)

- **Category**: Packaging / Internal-Infra
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: This is Holmgang's own test-infrastructure/tooling (used to *run* other scenarios), not a platform behavior a Cucumber scenario asserts on directly -- there is no meaningful "scenario that would exercise this" beyond the harness working at all, which every passing `.feature` file already demonstrates indirectly.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-holmgang/compose/docker-compose.bundled-jre.yml`, `docker-compose.full-jre.yml`, `topology-bundled-jre.yaml`, `topology-full-jre.yaml`

### gimle-dist

#### GIMLE-560 — Standalone CLI distribution archive

- **Category**: Packaging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Packaging/distribution-archive concern (`Standalone CLI distribution archive`). Holmgang boots processes straight off the reactor's own classpath, never the built `gimle-*.tar.gz`; a scenario proving this would first need Holmgang to unpack and launch from a real distribution archive, which `docker-compose.bundled-jre.yml`'s manual validation flow does today, not the Cucumber suite.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-dist/src/main/assembly/cli.xml`, `gimle-dist/pom.xml`, `gimle-dist/src/main/dist/bin/gimle`, `gimle-dist/src/main/dist/bin/gimle.cmd`

#### GIMLE-561 — Standalone Hilmir bootstrap-tool distribution archive

- **Category**: Packaging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Packaging/distribution-archive concern (`Standalone Hilmir bootstrap-tool distribution archive`). Holmgang boots processes straight off the reactor's own classpath, never the built `gimle-*.tar.gz`; a scenario proving this would first need Holmgang to unpack and launch from a real distribution archive, which `docker-compose.bundled-jre.yml`'s manual validation flow does today, not the Cucumber suite.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-dist/src/main/assembly/hilmir.xml`, `gimle-dist/pom.xml`, `gimle-dist/src/main/dist/bin/hilmir`, `gimle-dist/src/main/dist/bin/hilmir.cmd`

#### GIMLE-562 — Cluster-machine platform distribution archive

- **Category**: Packaging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Packaging/distribution-archive concern (`Cluster-machine platform distribution archive`). Holmgang boots processes straight off the reactor's own classpath, never the built `gimle-*.tar.gz`; a scenario proving this would first need Holmgang to unpack and launch from a real distribution archive, which `docker-compose.bundled-jre.yml`'s manual validation flow does today, not the Cucumber suite.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-dist/src/main/assembly/platform.xml`, `gimle-dist/pom.xml`, `gimle-dist/src/main/dist/bin/gimle.cmd`, `gimle-dist/src/main/dist/bin/hilmir.cmd`

#### GIMLE-563 — Opt-in bundled-JRE distribution variant (`dist-with-jre` profile)

- **Category**: Packaging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Packaging/distribution-archive concern (`Opt-in bundled-JRE distribution variant (`dist-with-jre` profile)`). Holmgang boots processes straight off the reactor's own classpath, never the built `gimle-*.tar.gz`; a scenario proving this would first need Holmgang to unpack and launch from a real distribution archive, which `docker-compose.bundled-jre.yml`'s manual validation flow does today, not the Cucumber suite.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-dist/pom.xml` (`dist-with-jre` profile), `platform-with-jre.xml`, `cli-with-jre.xml`, `hilmir-with-jre.xml`

#### GIMLE-564 — Distribution archive checksums and SBOM generation

- **Category**: Packaging
- **Status**: Active
- **Coverage**: Not Covered
- **Gap note**: Packaging/distribution-archive concern (`Distribution archive checksums and SBOM generation`). Holmgang boots processes straight off the reactor's own classpath, never the built `gimle-*.tar.gz`; a scenario proving this would first need Holmgang to unpack and launch from a real distribution archive, which `docker-compose.bundled-jre.yml`'s manual validation flow does today, not the Cucumber suite.
- **Other test coverage (non-Holmgang, informational only)**: NONE recorded in the baseline
- **Source location(s)**: `gimle-dist/pom.xml` (cyclonedx-maven-plugin and maven-antrun-plugin executions)

### gimle-skald

#### GIMLE-569 — gimle-skald: cluster DNS server resolving Service names to live endpoints

- **Category**: Service Fabric
- **Status**: New  _(newly added as part of the Service/Bifrost/Skald/gateway/fabric-tenant-check network model work)_
- **Coverage**: Not Covered
- **Gap note**: No Holmgang scenario boots a SkaldMain replica and asserts a DNS query against <service>.svc.gimle.local resolves to a live endpoint of a real deployed module. To close: boot Skald alongside the control plane in a topology, declare a Service backed by a real deployed module, and assert a standard A-record UDP query resolves to that module's live address -- Holmgang's topology support has no Skald process kind wired in yet at all.
- **Other test coverage (non-Holmgang, informational only)**: `SkaldServerTest` (6 tests over the real UDP responder: tenant-scoped hit, untenanted-hit round-robin, NXDOMAIN for unknown name, NOTIMP for unsupported query type/opcode, malformed datagram dropped); `CachingServiceDirectoryTest`; `ControlPlaneServicePollerTest`; `DnsCodecTest`; `ServiceDnsNamesTest`
- **Source location(s)**: `gimle-skald/src/main/java/com/gimle/skald/SkaldMain.java`, `gimle-skald/src/main/java/com/gimle/skald/SkaldServer.java`, `gimle-skald/src/main/java/com/gimle/skald/directory/CachingServiceDirectory.java`, `gimle-skald/src/main/java/com/gimle/skald/directory/ControlPlaneServicePoller.java`, `gimle-skald/src/main/java/com/gimle/skald/dns/DnsCodec.java`, `gimle-skald/src/main/java/com/gimle/skald/dns/ServiceDnsNames.java`

## Coverage Gaps — Release-Readiness Checklist

Every requirement below has **no** Holmgang Cucumber scenario exercising it, per the strict rule. Sorted by Category. This is the checklist: closing a row means either adding/extending a Holmgang scenario (see each row's Gap note for the shape) or making a deliberate, recorded decision that a given capability does not warrant real-cluster Cucumber coverage (e.g. pure build tooling, console frontend behavior, or low-level wire-codec internals — flagged as such in the Gap note itself).

**483 of 603 requirements are Not Covered.**

| ID | Module | Feature | Category | Other test coverage (non-Holmgang) |
|---|---|---|---|---|
| GIMLE-314 | gimle-andvari | Andvari's own console session story (`/auth/*`, bundled SPA) | API Server | `AndvariServerAuthTest` — `login_session_and_logout_round_trip_with_no_client_certificate_at_all`, `a_wrong_password_is_rejected_with_no_cookie_set` |
| GIMLE-318 | gimle-andvari | Process status endpoint (no RBAC gate) | API Server | `AndvariServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status` |
| GIMLE-272 | gimle-controlplane | Bundled web console static serving | API Server / Internal-Infra | `ApiServerConsoleContractTest` |
| GIMLE-292 | gimle-fafnir | Bundled web console static serving (Fafnir) | API Server / Internal-Infra | `FafnirServerConsoleTest` — `console_static_files_are_served_once_wired`, `the_real_bundled_console_jar_resolves_and_serves_its_own_index_html` |
| GIMLE-293 | gimle-fafnir | Process status endpoint with key-ring fingerprint | API Server / Internal-Infra | `FafnirServerAuthTest#status_reports_uptime_active_key_and_transport_mode` |
| GIMLE-275 | gimle-controlplane | Per-deployment and per-instance metrics rollup | API Server / Observability | Covered within `ApiServerConsoleContractTest`/`ApiServerTest` |
| GIMLE-247 | gimle-controlplane | Organization-specific policy-as-data admission (`policy.maxReplicasPerDeployment`) | Admission / Config | `PolicyConfigPluginTest` — `a_deployment_exceeding_the_configured_ceiling_is_rejected`, `a_malformed_policy_value_is_rejected_rather_than_silently_ignored`, `exactly_at_the_ceiling_is_allowed` |
| GIMLE-245 | gimle-controlplane | Admission chain extension point | Admission / Internal-Infra | `AdmissionChainTest` — `empty_chain_allows_the_spec_unchanged`, `a_rejecting_plugin_short_circuits_every_later_plugin`, `a_later_plugin_sees_the_spec_an_earlier_plugin_mutated` |
| GIMLE-297 | gimle-andvari | Immutable, content-addressed artifact store | Artifact Registry | `ArtifactStoreTest` — `an_identical_re_push_is_idempotent`, `a_differing_re_push_is_a_conflict_and_the_stored_bytes_are_untouched`; `AndvariServerTest` — `a_differing_re_push_is_refused_as_immutable`, `an_identical_re_push_is_idempotent` |
| GIMLE-299 | gimle-andvari | Size-limited streaming upload rejection | Artifact Registry | Implicit in `ArtifactStoreTest`'s put-path coverage |
| GIMLE-302 | gimle-andvari | Version retention sweeping (count and age based) | Artifact Registry | `ArtifactRetentionSweeperTest` — `retires_the_oldest_versions_once_a_module_exceeds_the_configured_count`, `retires_versions_older_than_the_configured_age`, `a_version_over_both_limits_is_reported_once_with_a_combined_reason`, `neither_policy_configured_retires_nothing` |
| GIMLE-308 | gimle-andvari | Generated `maven-metadata.xml` (never stored, always fresh) | Artifact Registry | `AndvariServerMavenRepositoryTest` — `maven_metadata_lists_every_pushed_version_and_names_the_latest`, `maven_metadata_checksum_is_computed_over_the_generated_document`, `a_single_segment_module_has_an_empty_group_id_in_the_generated_metadata`; `ArtifactStoreTest#versions_sort_semver_aware_not_lexicographically` |
| GIMLE-577 | gimle-andvari | Multi-jar publish with per-module tenant tagging (`kind: ArtifactSet`) | Artifact Registry | `ArtifactStoreTest` (tenant round-trip through `meta.json`, untenanted-to-tenanted backfill exactly once, a further tenant swap still conflicts); `AndvariServerTest` (tenant header round-trip on HEAD/GET/PUT, catalog listing includes `tenantId`); `AndvariServerTlsTest` (tenant-scoped RBAC grants, a push cannot claim a tenant the caller holds no permission for, reads/deletes check the stored tenant not a caller claim); `ArtifactSetManifestParserTest` (`tenant:`/`modules:` grouping, push-order preservation, duplicate-path rejection across tenants and against `modules`); `ArtifactSetCommandTest` (real end-to-end `gimle apply` against a real in-process `AndvariServer`: multi-tenant push, pre-flight digest-conflict abort before any push, idempotent resume on re-apply); `ArtifactSetMojoTest` (per-submodule tenant-property override, generated manifest content); `ArtifactSetCommandTest` (admission cross-check: a mismatched tenantId rejected with 400 naming both tenants, a matching tenantId admitted, an untenanted workload against a tenanted coordinate skips the check) |
| GIMLE-306 | gimle-andvari | Maven-2-shaped `/repository/**` interop surface | Artifact Registry / API Server | `AndvariServerMavenRepositoryTest` — `a_jar_pushed_through_the_repository_path_is_readable_from_the_operational_surface`, `a_jar_pushed_through_the_operational_surface_is_downloadable_via_the_repository_path`, `a_differing_re_push_through_the_repository_path_is_still_refused_as_immutable` |
| GIMLE-265 | gimle-controlplane | `/artifacts/*` streaming proxy to Andvari | Artifact Registry / Internal-Infra | `AndvariClientTest`; end-to-end in `gimle-smoke-tests/AndvariRegistryIT` |
| GIMLE-266 | gimle-controlplane | Andvari-client multi-endpoint failover with rotation | Artifact Registry / Internal-Infra | `AndvariClientTest` — `a_head_call_fails_over_from_an_unreachable_endpoint_to_a_reachable_one`, `unreachable_on_every_configured_endpoint_answers_unreachable` |
| GIMLE-298 | gimle-andvari | Streamed, digest-verified push with atomic commit | Artifact Registry / Internal-Infra | `ArtifactStoreTest` (push mechanics covered by round-trip tests) |
| GIMLE-300 | gimle-andvari | On-disk corruption detection and quarantine | Artifact Registry / Internal-Infra | `AndvariServerTest#a_get_against_bytes_corrupted_on_disk_still_serves_them_but_quarantines_the_coordinate` |
| GIMLE-301 | gimle-andvari | Periodic full-store integrity scrub | Artifact Registry / Internal-Infra | `IntegrityScrubberTest` — `a_coordinate_whose_bytes_no_longer_match_its_recorded_digest_is_reported`, `an_uncorrupted_coordinate_is_never_reported`, `a_version_missing_its_jar_is_skipped_rather_than_reported_as_corrupted` |
| GIMLE-304 | gimle-andvari | Peer-sync conflict detection (irreconcilable divergence) | Artifact Registry / Internal-Infra | Documented in class javadoc |
| GIMLE-307 | gimle-andvari | Server-computed checksum sidecars (never trusting client uploads) | Artifact Registry / Internal-Infra | `AndvariServerMavenRepositoryTest#the_jar_checksum_is_always_server_computed_and_ignores_an_uploaded_sidecar` |
| GIMLE-309 | gimle-andvari | Maven GAV coordinate translation | Artifact Registry / Internal-Infra | `MavenCoordinatesTest` — `a_multi_segment_group_joins_with_the_artifact_id_by_dots`, `distinct_gavs_can_alias_to_the_same_module_coordinate` |
| GIMLE-249 | gimle-controlplane | PUT-time re-tenanting double-authorization | Authorization | Embedded in `ApiServerAuthzTest`'s broader RBAC flow coverage |
| GIMLE-250 | gimle-controlplane | RBAC-gated resource CRUD across every workload kind | Authorization | `ApiServerAuthzTest`, `ApiServerEndpointsAuthzTest` |
| GIMLE-252 | gimle-controlplane | `gimle-system` reserved-tenant operator-only guard | Authorization | Exercised within `ApiServerAuthzTest`'s broader RBAC test set |
| GIMLE-253 | gimle-controlplane | Node-scoped self-service authorization (`gimle:nodes` group) | Authorization | `ApiServerAuthzTest`; `NodeBootstrapCsrTest#fresh_agent_obtains_a_signed_certificate_and_completes_mtls_handshake` |
| GIMLE-254 | gimle-controlplane | Node-tenant-scoped `/endpoints/*` read access | Authorization | `ApiServerEndpointsAuthzTest` — `a_node_with_an_active_assignment_for_the_deployments_tenant_may_read_its_endpoints`, `a_node_with_no_assignment_for_the_deployments_tenant_is_forbidden` |
| GIMLE-310 | gimle-andvari | Defense-in-depth authorization (independent re-check, `ResourceKind.ARTIFACT`) | Authorization | `AndvariServerTlsTest#a_forwarded_principal_wins_over_the_peer_certificate_and_is_re_checked`, `an_ungrouped_certificate_is_refused_by_the_independent_rbac_check` |
| GIMLE-311 | gimle-andvari | Module-scoped permission grants | Authorization | `AndvariServerTlsTest` — `a_module_scoped_permission_grants_access_to_only_that_module`, `a_module_scoped_permission_cannot_list_the_full_catalog` |
| GIMLE-312 | gimle-andvari | Node pull-only artifact access, scoped to active assignments | Authorization | `AndvariServerTlsTest#a_nodes_group_certificate_may_pull_only_coordinates_assigned_to_its_node` |
| GIMLE-256 | gimle-controlplane | Console session login/logout/session cookie flow | Authorization / API Server | `ApiServerAuthzTest#login_session_and_logout_round_trip_with_no_client_certificate_at_all` |
| GIMLE-251 | gimle-controlplane | WRITE/DELETE decisions durably audited (opt-in READ auditing) | Authorization / Internal-Infra | `ApiServerAuthzTest#configured_read_resource_kinds_are_audited_allowed_and_denied_reads` |
| GIMLE-257 | gimle-controlplane | Login throttling (address + username keyed) | Authorization / Internal-Infra | Exercised via shared `LoginThrottle` mechanics (`FafnirObservabilityTest`'s equivalent); no isolated ApiServer-level test method found |
| GIMLE-287 | gimle-fafnir | Authorization-failure throttling and dual audit logging | Authorization / Internal-Infra | `FafnirObservabilityTest` — `repeated_authorization_failures_from_the_same_principal_are_eventually_throttled`, `a_successful_authorization_clears_prior_recorded_failures`, `audit_log_records_the_decision_without_ever_logging_the_secret_value` |
| GIMLE-409 | gimle-hilmir | Doctor static deployability diagnostics (`hilmir doctor`) | Build Tooling | `DoctorAnalyzerTest` (10 tests); `DoctorCommandTest`; `BytecodeScannerTest`, `JarStructureInspectorTest` |
| GIMLE-410 | gimle-hilmir | Doctor cluster-aware checks (`--server`, `--tenant`) | Build Tooling | NONE recorded in the baseline |
| GIMLE-411 | gimle-hilmir | Manifest scaffolding (`hilmir init`) | Build Tooling | `InitCommandTest` (3 tests); `ModuleYamlWriterTest` (2 tests) |
| GIMLE-418 | gimle-maven-plugin | `mvn gimle:agent` — spawn a real node agent (plus its worker command tail) | Build Tooling | NONE recorded in the baseline |
| GIMLE-419 | gimle-maven-plugin | `mvn gimle:bootstrap` — full local-dev cluster orchestration in one foreground command | Build Tooling | NONE recorded in the baseline |
| GIMLE-420 | gimle-maven-plugin | Process-launcher Maven goals for individual platform processes (`controlplane`/`store`/`fafnir`/`muninn`/`andvari`/`tls-init`) | Build Tooling | NONE recorded in the baseline |
| GIMLE-421 | gimle-maven-plugin | `mvn gimle:deploy` — apply a deployment manifest via a real CLI subprocess | Build Tooling | NONE recorded in the baseline |
| GIMLE-422 | gimle-maven-plugin | `mvn gimle:doctor` — run hilmir doctor against the invoking project's own built jar | Build Tooling | `DoctorMojoTest` (4 tests, against the pure buildCommand seam) |
| GIMLE-423 | gimle-maven-plugin | `mvn gimle:init` — scaffold manifests for the invoking project's own built jar | Build Tooling | `InitMojoTest` (3 tests) |
| GIMLE-424 | gimle-maven-plugin | `mvn gimle:publish` — push a built module jar to the artifact registry | Build Tooling | NONE recorded in the baseline |
| GIMLE-425 | gimle-maven-plugin | `mvn gimle:docs` — full documentation site build pipeline | Build Tooling | NONE recorded in the baseline |
| GIMLE-426 | gimle-maven-plugin | `mvn gimle:flaky-tests` — run known-flaky-tagged tests in isolated standalone reactors | Build Tooling | `FlakyTestsMojoTest` (pure-function seams) |
| GIMLE-427 | gimle-maven-plugin | `mvn gimle:saga` — ensure a Saga test-report server is running | Build Tooling | `SagaServerTest`, `SagaClientTest` |
| GIMLE-428 | gimle-maven-plugin | `mvn gimle:verify` — full build run under Saga tracking | Build Tooling | `SagaVerifyMojoTest` (pure-function seams); `SagaEventsTest`; `SurefireReportsTest` |
| GIMLE-429 | gimle-maven-plugin | `mvn gimle:saga-import` — standalone sweep-and-import of existing surefire reports | Build Tooling | NONE recorded in the baseline |
| GIMLE-430 | gimle-maven-plugin | `mvn gimle:saga-stop` — best-effort local Saga server shutdown | Build Tooling | NONE recorded in the baseline |
| GIMLE-573 | gimle-hilmir | Doctor advisory-only outbound-connection hazard detection | Build Tooling | `BytecodeScannerTest`, `DoctorAnalyzerTest` -- see requirements-matrix.json for detail |
| GIMLE-371 | gimle-cli | Deployment resource management (get/apply/delete) | CLI | `GimleCliTest.apply_then_get_deployments_round_trips`, `apply_then_delete_removes_the_deployment`, `apply_then_get_deployments_as_json_round_trips`, `apply_and_delete_deployment_produce_real_json_under_json_output_format` |
| GIMLE-372 | gimle-cli | Job resource management (get/apply/delete) | CLI | `GimleCliTest.apply_then_get_jobs_round_trips`, `apply_then_delete_removes_the_job` |
| GIMLE-373 | gimle-cli | CronJob management incl. manual trigger | CLI | `GimleCliTest.apply_then_get_cronjobs_round_trips`, `cronjob_trigger_fires_immediately_and_the_generated_job_is_real`, `cronjob_trigger_on_an_unknown_cronjob_fails` |
| GIMLE-374 | gimle-cli | DaemonSet resource management | CLI | `GimleCliTest.apply_then_get_daemonsets_round_trips`, `apply_then_delete_removes_the_daemonset` |
| GIMLE-375 | gimle-cli | StatefulSet resource management | CLI | `GimleCliTest.apply_then_get_statefulsets_round_trips`, `apply_then_delete_removes_the_statefulset` |
| GIMLE-376 | gimle-cli | Node inventory and cordon/uncordon | CLI | `GimleCliTest.get_nodes_lists_a_registered_node`, `get_nodes_as_json_includes_the_node_id_field` |
| GIMLE-377 | gimle-cli | Instance lifecycle event timeline | CLI | `GimleCliTest.events_with_no_limit_returns_every_event`, `events_with_limit_caps_the_returned_list`, `events_with_a_non_numeric_limit_fails` |
| GIMLE-378 | gimle-cli | Tenant management and quota configuration | CLI | `GimleCliTest.set_tenant_then_get_tenants_round_trips`, `set_and_delete_tenant_produce_real_json_under_json_output_format` |
| GIMLE-379 | gimle-cli | Tenant plain configuration key/value store | CLI | `GimleCliTest.set_and_get_config_round_trips`, `set_and_delete_config_produce_real_json_under_json_output_format` |
| GIMLE-382 | gimle-cli | Log viewing and live tailing | CLI | NONE recorded in the baseline |
| GIMLE-578 | gimle-cli | Service CRUD and live endpoint lookup | CLI | `GimleCliTest.set_service_then_get_services_round_trips_then_delete`, `set_service_defaults_target_port_to_port_when_omitted`, `service_endpoints_reports_the_declared_port_shape_with_no_live_backing_instance`, `set_service_without_a_deployment_flag_fails`, `get_service_not_found_produces_a_clear_error` |
| GIMLE-579 | gimle-cli | NetworkPolicy CRUD | CLI | `GimleCliTest.set_networkpolicy_then_get_networkpolicies_round_trips_then_delete`, `set_networkpolicy_without_a_tenant_flag_fails`, `get_networkpolicy_not_found_produces_a_clear_error` |
| GIMLE-584 | gimle-cli | `gimle configmap` command | CLI | Exercised end-to-end by `ApiServerConfigMapTest`'s HTTP-level coverage of the same `/configmaps/*` surface `ConfigMapCommand` calls; no dedicated `ConfigMapCommandTest` fixture exists (see gapNote in rtm.json). |
| GIMLE-592 | gimle-cli | `gimle secretmap` command | CLI | Exercised end-to-end by `ApiServerSecretMapTest`'s HTTP-level coverage of the same `/secretmaps/*` surface `SecretMapCommand` calls; no dedicated `SecretMapCommandTest` fixture exists, the same gap `ConfigMapCommand` has (GIMLE-584). |
| GIMLE-595 | gimle-cli | `secretmap versions`/`secretmap rollback` verbs | CLI | Exercised indirectly through `ApiServerSecretMapTest`/`FafnirServerSecretMapTest`'s coverage of the underlying `/secretmaps/*/versions` and `/secretmaps/*/rollback` routes this command calls; no dedicated `SecretMapCommand` unit test file exists, matching the rest of that class's own untested-at-the-CLI-layer precedent (`ConfigMapCommand`/`SecretCommand` are the same). |
| GIMLE-600 | gimle-cli | `gimle seal` command, `secret retire-key`, `secretmap seal` verbs | CLI | Exercised indirectly through `FafnirServerSealTest`/`ApiServerSealTest`/`ApiServerSealAuthzTest`'s coverage of the underlying routes these verbs call; no dedicated `SealCommand`/`SecretCommand`/`SecretMapCommand` unit test file exists, matching this class family's own untested-at-the-CLI-layer precedent. |
| GIMLE-602 | gimle-cli | `deployment`/`statefulset`/`daemonset` `revisions`/`rollback` verbs | CLI | `GimleCliTest` against a real `ApiServer` (not mocked): `deployment revisions`, `deployment rollback` with and without `--to-revision`, and the 404 failure path. |
| GIMLE-381 | gimle-cli | Artifact registry client (push/list/get/delete) | CLI / Build Tooling | NONE recorded in the baseline |
| GIMLE-388 | gimle-cli | Dual table/JSON output formatting | CLI / Internal-Infra | Exercised implicitly throughout GimleCliTest via -o json assertions |
| GIMLE-380 | gimle-cli | Versioned secrets management (Fafnir proxy) | CLI / Security | `GimleCliTest.secret_set_then_get_round_trips_the_plaintext_value`, `secret_list_shows_the_key_without_ever_printing_a_value`, `secret_versions_lists_every_claimed_version_after_two_writes`, `secret_get_with_an_explicit_version_reads_the_historical_value`, `secret_delete_then_get_returns_not_found`, `secret_rotate_key_returns_an_incrementing_active_key_id` |
| GIMLE-383 | gimle-cli | Audit trail query | CLI / Security | `GimleCliTest.audit_list_with_no_filters_succeeds_and_is_empty_in_plaintext_mode`, `audit_list_accepts_every_filter_flag_without_a_malformed_request`, `audit_command_without_the_list_verb_prints_usage_and_nonzero_exit` |
| GIMLE-384 | gimle-cli | RBAC role management | CLI / Security | `GimleCliTest.set_role_then_get_roles_round_trips_then_delete` |
| GIMLE-385 | gimle-cli | RBAC role binding management | CLI / Security | `GimleCliTest.set_rolebinding_then_get_rolebindings_round_trips_then_delete` |
| GIMLE-386 | gimle-cli | Operator account management | CLI / Security | `GimleCliTest.set_account_then_get_accounts_round_trips_and_never_leaks_the_password_hash` |
| GIMLE-387 | gimle-cli | Certificate lifecycle management (bootstrap token, CSR request/status/approve, renewal) | CLI / Security | NONE recorded in the baseline |
| GIMLE-107 | gimle-agent | Portable JVM-flags resource limiting (Tier 1/2), cgroup enforcement deliberately deferred | Cgroup Management | `ResourceLimitEnforcementTest#a_spawned_jvm_honors_the_computed_memory_and_processor_ceiling` (gimle-agent, real subprocess); `PortableJvmFlagsResourceLimiterTest` (gimle-os) |
| GIMLE-108 | gimle-agent | Tier 3 isolation rejection | Cgroup Management / Config | NONE recorded in the baseline |
| GIMLE-186 | gimle-fabric | Per-Endpoint Circuit Breaker | Circuit Breaking | `CircuitBreakerTest#opens_once_error_rate_crosses_threshold_over_the_window`, `#half_opens_after_cooldown_and_allows_exactly_one_trial`, `#half_open_success_closes_the_breaker`, `#half_open_failure_reopens_the_breaker`, `FabricServiceRegistryTest#a_failing_endpoints_breaker_opens_and_is_excluded` |
| GIMLE-187 | gimle-fabric | Circuit Breaker Exponential Cooldown Backoff | Circuit Breaking | `CircuitBreakerTest#repeated_reopens_double_the_effective_cooldown`, `#the_doubling_backoff_stops_at_its_documented_ceiling`, `#a_successful_half_open_trial_resets_the_backoff_to_the_base_cooldown` |
| GIMLE-188 | gimle-fabric | Panic-Mode Ejection Floor | Circuit Breaking | `FabricServiceRegistryTest#all_endpoints_failing_still_yields_a_candidate_once_the_panic_threshold_is_crossed`, `#no_known_exporter_anywhere_throws_gimle_cluster_exception` |
| GIMLE-189 | gimle-fabric | Application-Exception vs Transport-Failure Breaker Scoring | Circuit Breaking | `FabricServiceRegistryTest#an_endpoint_whose_method_throws_an_application_exception_does_not_open_its_breaker` |
| GIMLE-509 | gimle-smoke-tests | Base cluster topology deploy across store cluster and multiple CP replicas | Cluster Validation | `GreeterClusterTopologyIT.greeter_modules_deploy_across_a_store_cluster_and_multiple_control_plane_replicas` |
| GIMLE-510 | gimle-smoke-tests | Raft store resilience (member loss, leader failover, live membership change) | Cluster Validation | `RaftResilienceIT.cluster_tolerates_losing_one_store_node_mid_deployment`, `a_leader_failover_loses_no_acknowledged_write_under_concurrent_load`, `a_new_store_node_joins_via_live_membership_change_and_is_then_removed` |
| GIMLE-511 | gimle-smoke-tests | Tiered self-healing (worker respawn, liveness-exhaustion escalation to FAILED) | Cluster Validation | `SelfHealingIT.a_crashed_workers_instance_is_respawned_and_returns_to_active`, `a_module_that_never_passes_its_own_liveness_check_exhausts_its_restart_budget_and_fails` |
| GIMLE-512 | gimle-smoke-tests | Classloader leak detection wired into a real worker | Cluster Validation | `ClassloaderLeakIT.a_module_that_leaks_its_own_classloader_on_redeploy_is_reported_by_leak_tracker` |
| GIMLE-513 | gimle-smoke-tests | Repeated redeploy stability without false-positive leaks | Cluster Validation | `RedeployStabilityIT.a_well_behaved_module_survives_repeated_redeploys_without_ever_reporting_a_leak` |
| GIMLE-514 | gimle-smoke-tests | Tier 1 worker density packing and its cap | Cluster Validation | `Tier1DensityIT` (density-packing test) |
| GIMLE-515 | gimle-smoke-tests | Node cordoning blocks new placement without evicting running instances | Cluster Validation | `NodeCordoningIT.a_cordoned_node_blocks_new_placement_but_never_evicts_an_already_running_instance` |
| GIMLE-516 | gimle-smoke-tests | DaemonSet per-node fan-out and dead-node assignment cleanup | Cluster Validation | `DaemonSetLifecycleIT.a_daemonset_places_on_every_node_and_a_dead_nodes_assignment_is_cleaned_up` |
| GIMLE-517 | gimle-smoke-tests | Job and CronJob real-cluster lifecycle | Cluster Validation | `JobLifecycleIT.a_job_that_succeeds_reaches_the_succeeded_phase_and_stays_there`, `a_triggered_cronjob_generates_a_real_job_that_reaches_succeeded` |
| GIMLE-518 | gimle-smoke-tests | StatefulSet sticky placement and volume persistence across worker restart | Cluster Validation | `StatefulSetPersistenceIT.a_statefulset_instance_keeps_its_sticky_node_and_its_volume_data_across_a_worker_restart` |
| GIMLE-519 | gimle-smoke-tests | Rolling update preserves serving capacity and reaches new version | Cluster Validation | `RollingUpdateIT.a_rolling_update_keeps_at_least_one_instance_serving_traffic_throughout`, `a_single_replica_rolling_update_has_real_observed_downtime` |
| GIMLE-520 | gimle-smoke-tests | Surge worker promotion carries out via in-place retarget, not respawn | Cluster Validation | `SurgePromotionIT.a_promoted_surge_worker_keeps_the_same_process_while_the_restarted_one_does_not` |
| GIMLE-522 | gimle-smoke-tests | Multi-tenant quota enforcement (flag-not-evict, and admission rejection) | Cluster Validation | `QuotaIT.a_tenant_over_quota_deployment_is_flagged_but_not_evicted`, `a_deployment_that_would_exceed_tenant_quota_is_rejected_at_admission` |
| GIMLE-523 | gimle-smoke-tests | Circuit breaker excludes a consistently-failing replica | Cluster Validation | `ServiceFabricIT.a_circuit_breaker_excludes_a_consistently_failing_replica_after_real_failures` |
| GIMLE-524 | gimle-smoke-tests | Gossip/SWIM failure detection across real separate agent processes | Cluster Validation | `GossipFailureDetectionIT.a_hard_killed_member_converges_to_dead_on_both_surviving_real_agents` |
| GIMLE-525 | gimle-smoke-tests | Observability data survives agent death (Muninn fallback) and control-plane metrics round-trip | Cluster Validation | `ObservabilityIT.a_deployed_instances_log_survives_its_owning_agent_dying`, `a_control_planes_own_request_metrics_round_trip_through_muninn` |
| GIMLE-526 | gimle-smoke-tests | Worker-tier metrics/trace relay to Muninn via the agent | Cluster Validation | single @Test in WorkerObservabilityIT |
| GIMLE-527 | gimle-smoke-tests | Artifact registry (Andvari) resolution path end to end | Cluster Validation | `AndvariRegistryIT.a_coordinate_only_deployment_pulls_its_jar_from_andvari_through_the_agent_cache` |
| GIMLE-528 | gimle-smoke-tests | External HTTP request reaches a fabric service through the gateway | Cluster Validation | `GatewayFabricRouteIT.an_external_http_request_reaches_a_real_fabric_service_through_the_gateway` |
| GIMLE-554 | gimle-holmgang | Utgard multi-container distributed boot ordering | Cluster Validation | `UtgardDistributedBootIT.a_machine_started_out_of_dependency_order_blocks_then_completes_once_its_prerequisites_are_up` |
| GIMLE-555 | gimle-holmgang | Utgard real machine loss (hard container kill) and rejoin | Cluster Validation | `UtgardMachineLossIT.a_killed_machine_is_rescheduled_around_and_can_rejoin_after_restart` |
| GIMLE-556 | gimle-holmgang | Utgard network partition (vs hard kill) with reconvergence | Cluster Validation | `UtgardPartitionIT.a_partitioned_machine_is_rescheduled_around_then_the_cluster_converges_on_reconnect` |
| GIMLE-557 | gimle-holmgang | Utgard real-hostname mTLS bootstrap across containers | Cluster Validation | `UtgardMtlsIT.an_mtls_cluster_bootstraps_across_containers_addressed_by_real_hostnames` |
| GIMLE-117 | gimle-agent | Persistent volume allocation for StatefulSet-shaped instances | Config | NONE recorded in the baseline |
| GIMLE-119 | gimle-agent | Vessel port allocation (dynamic/fixed) and env resolution (literal/port/secret) | Config | NONE recorded in the baseline |
| GIMLE-120 | gimle-agent | Vessel config-file rendering to disk | Config | NONE recorded in the baseline |
| GIMLE-134 | gimle-agent | Node placement-label registration | Config | NONE recorded in the baseline |
| GIMLE-332 | gimle-muninn | Plaintext-default transport with loud unauthenticated-mode warning | Config | `MuninnServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status` |
| GIMLE-333 | gimle-muninn | mTLS transport mode | Config | `MuninnServerTlsTest#a_real_mtls_request_with_a_ca_signed_client_cert_succeeds` |
| GIMLE-334 | gimle-muninn | Zero-downtime TLS material reload on certificate rotation | Config | `MuninnServerTlsTest#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server` |
| GIMLE-355 | gimle-observability | Muninn endpoint list parsing from config | Config | NONE recorded in the baseline |
| GIMLE-363 | gimle-gateway | Route-table config DSL parsing | Config | `GatewayRouteConfigTest#parses_a_mix_of_fabric_and_vessel_routes_ignoring_blank_lines_and_comments`, `#an_unknown_kind_token_is_rejected`, `#a_fabric_line_with_the_wrong_number_of_fields_is_rejected`, `#a_non_integer_fabric_version_is_rejected`, `#a_fabric_param_type_outside_the_v1_restriction_is_rejected_at_parse_time` |
| GIMLE-364 | gimle-gateway | Duplicate route-path rejection at config-parse time | Config | `GatewayRouteConfigTest#a_duplicate_route_path_across_fabric_and_vessel_is_rejected`, `#a_duplicate_fabric_route_path_is_rejected`, `#a_duplicate_vessel_route_path_is_rejected` |
| GIMLE-264 | gimle-controlplane | CONFIG/SECRET resource-kind separation on one underlying store | Config / Authorization | `ApiServerAuthzTest#config_and_secret_permissions_are_independently_enforced_and_filtered` |
| GIMLE-581 | gimle-controlplane | ConfigMap store and API with optimistic-concurrency writes | Configuration Management | `ConfigMapStoreTest` (version bump by exactly one, PUT full-replace vs PATCH merge, PATCH `expectedVersion=0` create case, stale-`expectedVersion` conflict carries the right snapshot, delete, get-on-absent, `getMany` batch filtering, and a 6-thread concurrency regression proving no writer's key is silently dropped under contention); `ApiServerConfigMapTest` (full HTTP round trip, batch-get via `?names=`, 409 on stale `expectedVersion`, 400 on writing a `configmap:`-prefixed key through `/config/*`, a ConfigMap row never leaks into a plain `/config/*` listing); `ApiServerConfigMapAuthzTest` (RBAC gating via `ResourceKind.CONFIGMAP` over real mTLS) |
| GIMLE-582 | gimle-mimir | Deployment `configMapRefs` field with admission-time collision rejection | Configuration Management | `DeploymentManifestParserTest` (parses `configMapRefs:`, absent field defaults to empty, non-string entry rejected); `DomainCodecTest` (`configMapRefs` round-trips through the wire); `ConfigMapRefsPluginTest` (empty refs allowed with no store reads, no-tenantId rejected, unknown reference rejected, two refs colliding rejected, a ref colliding with flat config rejected, a clean reference allowed) |
| GIMLE-583 | gimle-agent | Narrowed config delivery to instances declaring `configMapRefs` | Configuration Management | Covered indirectly through `AssignedInstance`'s own back-compat-constructor tests and `ApiServerConfigMapTest`'s batch-get coverage; no dedicated `AgentMainTest` fixture exists for `fetchConfigMaps`/`deliverConfig`'s narrowed branch specifically (see gapNote in rtm.json). |
| GIMLE-125 | gimle-agent | SWIM gossip membership integration with service catalog relay | Fabric | NONE recorded in the baseline |
| GIMLE-131 | gimle-agent | Whitelisted control-plane read relay (worker→agent→control plane) with independent re-validation | Fabric / Config | `AgentRelayControlPlaneReadTest#a_non_whitelisted_path_is_rejected_locally_and_never_reaches_the_control_plane`, `#a_path_traversal_attempt_disguised_as_a_single_segment_is_rejected`, `#a_whitelisted_path_triggers_a_real_call_and_relays_the_response_back`; end-to-end via `RelayControlPlaneEndToEndTest#a_hosted_modules_relay_call_round_trips_through_a_real_worker_process` |
| GIMLE-095 | gimle-worker | Control-plane read relay for hosted modules (RelayControlPlaneRead/Result round trip) | Fabric / Internal-Infra | `ControlPlaneRelayTest#a_matching_response_completes_the_waiting_caller_and_leaves_no_pending_entry`, `#no_response_times_out_and_still_leaves_no_pending_entry`, `#a_late_response_after_the_caller_already_gave_up_is_dropped_without_error` |
| GIMLE-567 | gimle-fabric | Fabric listener-side tenant re-check on inbound service calls | Fabric / Multi-tenancy | `FabricServerTest` (4 tests: direct-dial bypass rejected, untenanted caller rejected against a restricted export, allowed-tenant caller permitted, unrestricted export permits any caller); `FabricCodecTest`'s callerTenantId round-trip coverage |
| GIMLE-126 | gimle-agent | Gossip membership read-only HTTP surface | Fabric / Observability | `AgentGossipServerTest#reports_the_lone_self_member_alive_at_incarnation_zero`, `#reflects_a_peer_learned_through_real_swim_convergence`, `#rejects_non_get_methods` |
| GIMLE-356 | gimle-gateway | Fabric-route HTTP-to-service dispatch | Gateway/Routing | `GatewayDispatcherTest#a_string_argument_route_dispatches_and_returns_the_real_result`, `#a_no_argument_route_is_served_on_get`, `#an_int_argument_route_coerces_and_dispatches_correctly` |
| GIMLE-357 | gimle-gateway | Fabric-route argument coercion (`ParamType`) | Gateway/Routing | `GatewayDispatcherTest#a_body_that_does_not_coerce_to_the_declared_param_type_returns_400`, `#the_wrong_http_method_for_a_fabric_route_returns_405` |
| GIMLE-358 | gimle-gateway | Vessel-route HTTP reverse-proxy dispatch | Gateway/Routing | `GatewayDispatcherTest#a_vessel_route_proxies_to_the_real_target_with_method_path_body_and_response_intact` |
| GIMLE-359 | gimle-gateway | Vessel-endpoint resolution with TTL cache | Gateway/Routing | `VesselEndpointCacheTest#a_call_within_the_ttl_does_not_relay_again`, `#a_call_past_the_ttl_relays_again` |
| GIMLE-360 | gimle-gateway | Round-robin load balancing over ready vessel endpoints | Gateway/Routing | `VesselEndpointCacheTest#round_robins_across_every_ready_endpoint_over_repeated_calls`, `#skips_endpoints_missing_the_named_port_or_the_host`; `GatewayDispatcherTest#a_vessel_route_round_robins_across_ready_instances_over_repeated_real_calls` |
| GIMLE-361 | gimle-gateway | Stale-cache fallback on endpoint-refresh failure | Gateway/Routing | `VesselEndpointCacheTest#a_non_2xx_refresh_falls_back_to_the_stale_cached_list`, `#a_terminal_relay_status_with_nothing_cached_yet_is_a_clear_error`, `#an_unparsable_relay_body_with_nothing_cached_yet_is_a_clear_error` |
| GIMLE-362 | gimle-gateway | Vessel-route error surfacing (no ready endpoint / connect failure) | Gateway/Routing | `GatewayDispatcherTest#a_vessel_route_for_a_deployment_with_no_usable_endpoints_returns_a_clear_error_not_a_200`, `#a_vessel_route_reports_a_target_that_refuses_the_connection_as_a_clean_502`; `VesselEndpointCacheTest#an_empty_endpoint_list_is_a_clear_error_not_a_silent_200` |
| GIMLE-365 | gimle-gateway | Gateway HTTP server bootstrap via module lifecycle hooks | Gateway/Routing | NONE recorded in the baseline |
| GIMLE-366 | gimle-gateway | Gateway liveness and readiness probes | Gateway/Routing | NONE recorded in the baseline |
| GIMLE-367 | gimle-gateway | HTTP status-code error mapping across the dispatcher | Gateway/Routing | `GatewayDispatcherTest#an_unknown_path_returns_404`, `#the_wrong_http_method_for_a_fabric_route_returns_405`, `#a_downstream_fabric_call_that_throws_returns_502` |
| GIMLE-369 | gimle-gateway | Vessel proxy: no TLS, no header forwarding (v1 scope limitation) | Gateway/Routing | `GatewayDispatcherTest#a_vessel_route_proxies_to_the_real_target_with_method_path_body_and_response_intact` confirms what *is* forwarded; no test exercises header forwarding since none exists |
| GIMLE-370 | gimle-gateway | Fabric route "quiet success" ambiguity for a misrouted service name | Gateway/Routing | `GatewayDispatcherTest#a_fabric_route_naming_a_service_nothing_exports_is_served_as_200_with_an_empty_body` |
| GIMLE-570 | gimle-gateway | Gateway virtual-host routing and Service-backed (SERVICE) route kind | Gateway/Routing | `GatewayDispatcherTest` (6 relevant tests: host-constrained match, host mismatch 404, host-unconstrained route unaffected, fallthrough to host-unconstrained sibling, service route with no ready endpoint returns a clear error, cached endpoint list reused across dispatcher instances); `ServiceEndpointCacheTest` (11 tests: resolution, relay path, TTL caching/staleness fallback, error handling) |
| GIMLE-200 | gimle-fabric | SWIM Gossip Membership Protocol (Ping/PingReq/Ack) | Gossip Membership | `GossipMemberTest#two_nodes_discover_each_other_via_join`, `#a_killed_member_converges_to_dead_across_the_rest`, `#a_lone_node_with_no_seeds_starts_as_a_new_cluster`, `#a_single_unreachable_seed_is_a_legitimate_bootstrap_not_an_error`, `#multiple_unreachable_seeds_throw_gimle_cluster_exception` |
| GIMLE-201 | gimle-fabric | SWIM Self-Refutation via Incarnation Bump | Gossip Membership | `GossipMemberTest#a_member_refutes_a_suspicion_of_itself_by_bumping_incarnation`, `#a_stale_suspicion_below_the_current_incarnation_is_ignored` |
| GIMLE-202 | gimle-fabric | Lifeguard-Style Local Health Multiplier | Gossip Membership | `GossipMemberTest#the_local_health_multiplier_clamps_rather_than_growing_unbounded` |
| GIMLE-203 | gimle-fabric | Round-Robin Bounded-Coverage Probe Target Selection | Gossip Membership | `GossipMemberTest#probe_target_selection_visits_every_live_member_within_one_cycle` |
| GIMLE-204 | gimle-fabric | Anti-Entropy Full-State Sync | Gossip Membership | `GossipMemberTest#anti_entropy_sync_delivers_a_change_piggyback_alone_cannot_carry` |
| GIMLE-205 | gimle-fabric | Dead-Member Reaping | Gossip Membership | `GossipMemberTest#a_long_dead_member_is_eventually_forgotten_not_kept_forever` |
| GIMLE-206 | gimle-fabric | Gossip over Mutual DTLS with Deterministic Initiator Selection | Gossip Membership | `GossipMemberDtlsTest#two_nodes_discover_each_other_over_mutual_dtls`, `#a_killed_member_still_converges_to_dead_over_dtls`, `#members_trusting_different_cas_never_become_mutually_aware`, `#a_member_reaches_a_new_peer_over_dtls_after_reloading_rotated_material` |
| GIMLE-090 | gimle-worker | Readiness-driven service registry availability (without restart) | Health / Fabric | `WorkerRuntimeTest#a_readiness_failure_marks_the_service_unready_without_stopping_the_module`, `#a_module_becomes_lookupable_again_when_its_readiness_probe_recovers` |
| GIMLE-088 | gimle-worker | Liveness/readiness probe loop with timeout and initial-delay | Health / Self-Healing | `ProbeLoopTest#a_passing_check_reports_true_repeatedly`, `#a_failing_check_reports_false`, `#a_check_that_throws_is_reported_as_a_failure_not_propagated`, `#a_check_that_hangs_past_its_timeout_is_reported_as_a_failure`, `#no_tick_fires_before_the_initial_delay_elapses`, `#after_the_initial_delay_ticks_settle_onto_the_ordinary_interval`, `#stop_halts_further_invocations_of_that_key`, `#two_keys_are_scheduled_independently`, `#the_production_constructor_still_schedules_on_a_real_ticker` |
| GIMLE-121 | gimle-agent | Vessel health probing (process-alive + TCP/HTTP rungs, initial-delay aware) | Health / Self-Healing | NONE recorded in the baseline |
| GIMLE-080 | gimle-worker | Newline-delimited control-channel wire protocol (worker side) | Internal-Infra | `ControlChannelClientTest#a_sent_message_is_received_intact_on_the_other_end`, `#receive_returns_empty_once_the_peer_closes_the_connection` |
| GIMLE-099 | gimle-worker | `module-info.java` platform-layer/observability/fabric wiring for the worker module | Internal-Infra | NONE recorded in the baseline |
| GIMLE-124 | gimle-agent | Periodic certificate rotation check and hot-swap of outbound HttpClient | Internal-Infra | NONE recorded in the baseline |
| GIMLE-135 | gimle-agent | `module-info.java` wiring for the node agent module | Internal-Infra | NONE recorded in the baseline |
| GIMLE-169 | gimle-mimir | RBAC Authorization Engine | Internal-Infra | `AuthorizerTest#a_principal_with_no_binding_and_no_group_is_denied_everything`, `#an_operator_group_member_is_allowed_everything_via_the_implicit_cluster_admin_binding`, `#a_custom_role_bound_to_a_user_grants_exactly_its_declared_permissions`, `#a_tenant_scoped_permission_only_matches_its_own_tenant`, `#a_node_may_act_on_its_own_node_and_log_endpoints_with_no_role_binding_at_all`, `#a_node_is_denied_another_nodes_endpoints`, `#a_binding_referencing_a_role_that_no_longer_exists_grants_nothing`, `#a_node_may_read_the_cluster_wide_service_and_network_policy_sets_with_no_binding_at_all`, `#a_node_may_never_write_or_delete_a_service_or_network_policy`; `ApiServerNodeServiceAndNetworkPolicyAuthzTest` (`gimle-controlplane`) exercises the same grant through the real mTLS/RBAC HTTP layer. |
| GIMLE-170 | gimle-mimir | Node-Tenant Assignment Check | Internal-Infra | `AuthorizerTest#a_node_with_an_active_assignment_for_the_tenant_is_assigned`, `#a_node_with_no_assignment_for_the_tenant_is_not_assigned`, `#a_node_with_no_assignments_at_all_is_not_assigned` |
| GIMLE-243 | gimle-controlplane | Independent-executor ticking (lease/reconcile/cert-rotation isolation) | Internal-Infra | `ControlPlaneSchedulingTest` — `cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_is_blocked_forever`, `cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_throws_every_time` |
| GIMLE-244 | gimle-controlplane | JPMS module boundary for gimle-controlplane | Internal-Infra | NONE recorded in the baseline |
| GIMLE-260 | gimle-controlplane | Certificate rotation (self-rotation and subject-preserving renewal) | Internal-Infra | `CertificateRotationTest` — `rotation_issues_a_new_cert_for_the_same_subject_and_it_works_immediately`, `rotation_csr_with_a_mismatched_subject_is_rejected` |
| GIMLE-261 | gimle-controlplane | Zero-downtime TLS material reload | Internal-Infra | Exercised via `CertificateRotationTest`; analogous pattern in `FafnirServerTlsTest`/`AndvariServerTlsTest` |
| GIMLE-267 | gimle-controlplane | `/logs/*` proxy with Muninn fallback | Internal-Infra | `ApiServerLogsFallbackTest` — `a_node_with_no_registration_falls_through_to_muninn_when_configured`, `a_registered_but_unreachable_agent_falls_through_to_muninn_when_configured`, `a_live_reachable_agent_is_still_served_directly_not_from_muninn`, `a_muninn_fallback_fails_over_to_a_second_configured_endpoint_when_the_first_is_unreachable` |
| GIMLE-268 | gimle-controlplane | `/metrics-history/*` and `/traces-history/*` Muninn proxy | Internal-Infra | `ApiServerMetricsHistoryTest#proxies_to_muninn_forwarding_the_since_query_parameter`, `ApiServerTracesHistoryTest` |
| GIMLE-273 | gimle-controlplane | Per-endpoint request metrics instrumentation | Internal-Infra | `ApiServerMetricsTest` |
| GIMLE-289 | gimle-fafnir | mTLS HTTP server with dynamic TLS material reload | Internal-Infra | `FafnirServerTlsTest` — `a_real_mtls_request_with_a_ca_signed_client_cert_succeeds`, `reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server` |
| GIMLE-295 | gimle-fafnir | Fafnir-metrics observability instrumentation | Internal-Infra | `FafnirObservabilityTest#a_real_request_is_recorded_in_fafnir_metrics` |
| GIMLE-296 | gimle-fafnir | JPMS module boundary for gimle-fafnir | Internal-Infra | NONE recorded in the baseline |
| GIMLE-315 | gimle-andvari | mTLS server with dynamic TLS reload | Internal-Infra | `AndvariServerTlsTest#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server` |
| GIMLE-317 | gimle-andvari | Andvari observability instrumentation and Muninn shipping | Internal-Infra | `AndvariObservabilityTest` — `a_real_request_is_recorded_in_andvari_metrics`, `every_registered_route_is_independently_tagged` |
| GIMLE-389 | gimle-cli | kubectl-shaped global flag parsing, manifest-kind apply dispatch, and mTLS/leader-aware HTTP client | Internal-Infra | `GimleCliTest.a_bare_invocation_with_no_verb_prints_usage_rather_than_a_server_configuration_error`, `missing_server_configuration_is_a_clear_error`, `an_unreachable_control_plane_produces_a_clear_error_and_nonzero_exit`, `a_malformed_server_response_produces_a_clear_error_not_a_stack_trace`, `a_404_produces_a_clear_error_and_nonzero_exit`, `unknown_verb_prints_usage_and_nonzero_exit` |
| GIMLE-270 | gimle-controlplane | Unified `AssignedInstance` wire shape across every workload kind | Internal-Infra / API Server | `ApiServerEndpointsTest` — `a_job_run_is_listed_under_its_own_endpoints_route`, `a_daemonset_assignment_is_listed_under_its_own_endpoints_route`, `a_statefulset_assignment_is_listed_under_its_own_endpoints_route` |
| GIMLE-258 | gimle-controlplane | Bootstrap node join via single-use token + CSR | Internal-Infra / API Server (PKI) | `NodeBootstrapCsrTest` — `fresh_agent_obtains_a_signed_certificate_and_completes_mtls_handshake`, `invalid_bootstrap_token_is_rejected`; `BootstrapTokenRegistryTest` — `issued_token_can_be_consumed_exactly_once`, `expired_token_cannot_be_consumed` |
| GIMLE-259 | gimle-controlplane | Operator-approval-gated CSR flow | Internal-Infra / API Server (PKI) | `HumanOperatorCsrTest` — `operator_csr_sits_pending_until_an_existing_operator_approves_it`, `approve_without_a_client_certificate_is_rejected` |
| GIMLE-288 | gimle-fafnir | Three-tier principal resolution (forwarded header > peer cert > session cookie) | Internal-Infra / Authorization | `FafnirSecretsAuthzTest`; `FafnirServerAuthTest` |
| GIMLE-313 | gimle-andvari | Dual audit logging for push/delete decisions | Internal-Infra / Authorization | Exercised via `AndvariServerTlsTest`'s auth test set |
| GIMLE-294 | gimle-fafnir | Muninn metrics/traces shipping | Internal-Infra / Config | NONE recorded in the baseline |
| GIMLE-316 | gimle-andvari | Plaintext-mode loud supply-chain warning | Internal-Infra / Config | NONE recorded in the baseline |
| GIMLE-495 | gimle-saga | Bundled console static serving | Internal-Infra / Config | `SagaServerTest.java#the_bundled_console_is_served_at_console` |
| GIMLE-094 | gimle-worker | Fabric TLS certificate rotation detection (mtime polling) | Internal-Infra / Fabric | `FabricServerTlsWatcherTest#detects_a_rotated_certificate_file_and_reloads_the_fabric_server` |
| GIMLE-494 | gimle-saga | Path traversal protection on run IDs | Internal-Infra / Security | `SagaStoreTest.java#a_run_id_that_could_escape_the_store_directory_is_rejected` |
| GIMLE-010 | gimle-core | Artifact-registry vs local-path reference resolution | Internal/Infra | NONE recorded in the baseline |
| GIMLE-027 | gimle-core | Startup banner rendering with terminal color/Unicode auto-detection | Internal/Infra | `GimleBannerTest`, `GimleVersionTest` |
| GIMLE-028 | gimle-core | Single-write length-prefixed wire framing | Internal/Infra | NONE recorded in the baseline |
| GIMLE-029 | gimle-core | Hand-rolled JSON parser/writer | Internal/Infra | `JsonTest` (nested objects/arrays, negative/exponent numbers, escaped strings, round trip, escapes special chars, malformed throws) |
| GIMLE-150 | gimle-mimir | Raft RPC Wire Codec | Internal/Infra | `RaftCodecTest#round_trips_through_streams`, `#rejects_an_oversized_length_prefix_before_allocating`, `#rejects_a_negative_length_prefix_before_allocating`, `#rejects_a_forged_huge_entry_count_without_preallocating`, `#round_trips_a_state_snapshot`, `#round_trips_a_log_entry_carrying_a_membership_change` |
| GIMLE-151 | gimle-mimir | Atomic Durable File Writes | Internal/Infra | `AtomicFilesTest#writes_content_visible_under_the_final_name_with_no_leftover_tmp_file`, `#the_written_file_has_no_unflushed_dirty_state_after_writeatomically_returns`, `StateStoreTest#a_leftover_tmp_file_from_an_interrupted_write_is_never_read_back` |
| GIMLE-154 | gimle-mimir | Replicated Mutation Catalog (StateMutation) | Internal/Infra | `RaftCodecTest#round_trips_role_rolebinding_and_account_mutations_through_a_log_entry`, `#round_trips_an_append_instance_event_mutation_with_and_without_a_cause_summary`, `#round_trips_an_append_audit_event_mutation_allowed_and_denied_with_and_without_scope` |
| GIMLE-166 | gimle-mimir | Store Node Leader-Only Write Gating | Internal/Infra | `StoreNodeTest#a_non_leader_rejects_a_propose_with_not_leader_and_no_hint_yet`, `#a_non_leader_rejects_a_heartbeat_a_lease_acquire_and_a_lease_release`, `#a_non_leader_rejects_an_add_server_request_with_not_leader` |
| GIMLE-167 | gimle-mimir | Store Client Connection Timeout Bounds | Internal/Infra | `StoreConnectionTimeoutTest#a_connection_that_accepts_but_never_responds_times_out_instead_of_blocking_forever` |
| GIMLE-168 | gimle-mimir | Store RPC Wire Codec | Internal/Infra | `StoreCodecTest#round_trips_through_streams`, `#round_trips_a_weighted_autoscale_policy_with_every_weight_present`, `#round_trips_an_account_result_carrying_a_password_hash` |
| GIMLE-177 | gimle-mimir | Shared Domain Binary Codec | Internal/Infra | `DomainCodecTest#a_vessel_spec_round_trips_through_the_wire`, `#an_absent_vessel_spec_round_trips_as_empty`, `#a_deployment_spec_with_a_vessel_round_trips` |
| GIMLE-178 | gimle-mimir | Store Process Bootstrap with TLS Rotation Ticker | Internal/Infra | NONE recorded in the baseline |
| GIMLE-179 | gimle-mimir | Store/Raft Metrics Instrumentation | Internal/Infra | NONE recorded in the baseline |
| GIMLE-180 | gimle-mimir | module-info JPMS Boundary for gimle-mimir | Internal/Infra | NONE recorded in the baseline |
| GIMLE-197 | gimle-fabric | Fabric Call Timeout Enforcement | Internal/Infra | `FabricClientTest#a_peer_that_accepts_but_never_responds_times_out_within_the_configured_bound`, `#a_refused_connection_fails_fast_without_waiting_out_the_timeout` |
| GIMLE-198 | gimle-fabric | Fabric Frame Wire Codec | Internal/Infra | `FabricCodecTest#round_trips_through_streams`, `#round_trips_a_non_empty_tracestate_and_baggage`, `#rejects_an_oversized_length_prefix_before_allocating`, `#rejects_a_forged_huge_param_count_before_allocating` |
| GIMLE-199 | gimle-fabric | Cross-JVM Object Marshalling | Internal/Infra | NONE recorded in the baseline |
| GIMLE-207 | gimle-fabric | SWIM Wire Codec | Internal/Infra | `SwimCodecTest#round_trips_through_a_datagram`, `#a_forged_huge_piggyback_count_fails_cleanly_instead_of_preallocating`, `#rejects_an_unrecognized_version_before_decoding_the_tag` |
| GIMLE-208 | gimle-fabric | Service Catalog Delta Wire Codec | Internal/Infra | `ServiceCatalogCodecTest#round_trips_a_catalog_delta`, `#round_trips_an_empty_delta_list`, `#a_forged_huge_delta_count_fails_cleanly_instead_of_preallocating` |
| GIMLE-209 | gimle-fabric | Reflective Cross-Module Method Dispatch | Internal/Infra | Exercised indirectly through `FabricServiceRegistryInvokeByNameTest`/`FabricServerTest` — NONE dedicated |
| GIMLE-210 | gimle-fabric | module-info JPMS Boundary for gimle-fabric | Internal/Infra | NONE recorded in the baseline |
| GIMLE-327 | gimle-muninn | Day-bucketed JSON-lines store with oldest-first cursor semantics | Internal/Infra | `MuninnDayFileStoreTest#lines_spanning_two_days_land_in_two_separate_day_files`, `#a_late_arriving_line_appends_into_the_existing_day_file_rather_than_overwriting_it` |
| GIMLE-328 | gimle-muninn | All-or-nothing batch validation on ingest | Internal/Infra | `MuninnDayFileStoreTest#a_malformed_line_rejects_the_whole_batch_and_writes_nothing`; `MuninnServerLogsIngestTest#a_malformed_batch_is_rejected_entirely_and_nothing_from_it_is_readable` |
| GIMLE-329 | gimle-muninn | Windows-safe on-disk path sanitization for colon-bearing processId | Internal/Infra | `MuninnDayFileStoreTest#a_subtree_path_containing_a_colon_round_trips_without_an_invalid_path_error` |
| GIMLE-330 | gimle-muninn | Path-segment validation / directory-traversal defense | Internal/Infra | `MuninnServerLogsIngestTest#an_invalid_node_id_path_segment_is_rejected_before_touching_the_filesystem`, `MuninnServerMetricsIngestTest#an_invalid_process_kind_path_segment_is_rejected_before_touching_the_filesystem`, `MuninnServerTracesIngestTest` (same) |
| GIMLE-335 | gimle-muninn | Node-identity check on node-log ingest | Internal/Infra | NONE recorded in the baseline |
| GIMLE-336 | gimle-muninn | Instance-owner check on instance-log ingest | Internal/Infra | NONE recorded in the baseline |
| GIMLE-337 | gimle-muninn | Verified-certificate-presence check on metrics/traces ingest | Internal/Infra | NONE recorded in the baseline |
| GIMLE-338 | gimle-muninn | Read surface has no RBAC/authorization re-check (documented-vs-actual gap) | Internal/Infra | NONE recorded in the baseline |
| GIMLE-346 | gimle-observability | Multi-endpoint best-effort fan-out shipping | Internal/Infra | `MuninnShipperTest#a_batch_ships_to_every_configured_endpoint`, `#a_batch_still_lands_on_reachable_endpoints_when_one_configured_endpoint_is_down` |
| GIMLE-347 | gimle-observability | In-memory (non-persisted) log-shipping cursor | Internal/Infra | NONE recorded in the baseline |
| GIMLE-348 | gimle-observability | Micrometer meter → NDJSON codec | Internal/Infra | `MeterSnapshotCodecTest#one_line_per_meter_with_the_meters_own_name`, `#a_timer_with_percentiles_ships_a_percentiles_map`, `#a_timer_without_percentiles_omits_the_percentiles_key`, `#an_empty_registry_produces_an_empty_string` |
| GIMLE-349 | gimle-observability | OpenTelemetry span → NDJSON codec | Internal/Infra | `SpanLineCodecTest#one_line_per_span_with_attributes_flattened_onto_it`, `#an_empty_batch_produces_an_empty_string` |
| GIMLE-368 | gimle-gateway | Boot-only platform-layer JPMS workaround (`requires static`) | Internal/Infra | Indirectly covered by `RealBundledHookAndProbeInvocationTest` in `gimle-worker` (per CLAUDE.md, established for the same pattern in `greeter-provider`/`greeter-consumer`); no dedicated gateway-specific test found in `gimle-gateway` itself |
| GIMLE-414 | gimle-hilmir | Bundled JRE resolution for platform-binary launches | Internal/Infra | `BundledJreResolverTest` (6 tests); `LaunchPlannerTest` (2 tests) |
| GIMLE-415 | gimle-hilmir | `java @argfile` command-line rewriting | Internal/Infra | `JavaArgFileTest` (2 tests) |
| GIMLE-416 | gimle-hilmir | Run ledger persistence for `up`/`down`/`status`/`upgrade-cluster` | Internal/Infra | `RunLedgerTest` (9 tests) |
| GIMLE-417 | gimle-hilmir | TCP-connect readiness polling | Internal/Infra | `ReadinessPollerTest` (4 tests) |
| GIMLE-431 | gimle-maven-plugin | Internal — Aether-based cross-module runtime classpath resolution | Internal/Infra | NONE recorded in the baseline |
| GIMLE-432 | gimle-maven-plugin | Internal — host-matching java/mvn executable resolution and subprocess supervision | Internal/Infra | `GimleProcessesTest` (6 tests) |
| GIMLE-433 | gimle-maven-plugin | Internal — git commit/branch capture for run identification | Internal/Infra | NONE recorded in the baseline |
| GIMLE-434 | gimle-maven-plugin | Internal — surefire report discovery and totals aggregation, including flaky-testcase counting | Internal/Infra | `SurefireReportsTest` |
| GIMLE-496 | gimle-testkit | Poll-until-condition primitive (`Await`) | Internal/Infra | NONE recorded in the baseline |
| GIMLE-497 | gimle-testkit | Kernel-assigned loopback port leasing (`PortLease`) | Internal/Infra | `PortLeaseTest` |
| GIMLE-507 | gimle-smoke-tests | Real multi-process cluster fixture (store/control-plane/agent/Fafnir/Muninn) | Internal/Infra | Base fixture for every `*IT` in this module (24 concrete IT classes) |
| GIMLE-508 | gimle-smoke-tests | On-the-fly compiled module variants via `TestModuleBuilder` | Internal/Infra | Used by ClassloaderLeakIT, RedeployStabilityIT, SelfHealingIT, ServiceFabricIT, Tier1DensityIT, JobLifecycleIT, StatefulSetPersistenceIT, AutoscaleIT, RollingUpdateIT, SurgePromotionIT |
| GIMLE-529 | gimle-holmgang | Declarative cluster topology DSL/YAML parsing and validation | Internal/Infra | `ClusterTopologyDslTest`, `ClusterTopologyParserTest`, `GimleClusterStartRejectionTest.a_fault_proxied_mtls_topology_is_rejected_at_model_construction` |
| GIMLE-530 | gimle-holmgang | Real subprocess cluster orchestration (`GimleCluster`) | Internal/Infra | Foundation for every scenario in this module |
| GIMLE-531 | gimle-holmgang | Cluster pooling per topology with destructive-scenario isolation | Internal/Infra | Exercised implicitly by every HolmgangIT-run Gherkin scenario |
| GIMLE-532 | gimle-holmgang | JUnit `@Holmgang`/`@HolmgangCluster` extension for plain-JUnit cluster tests | Internal/Infra | `HaTopologyIT`, `MinimalTopologyIT` both use it directly |
| GIMLE-551 | gimle-holmgang | Saga unified run reporting (Gherkin + JUnit + Fenrir + Surtr) | Internal/Infra | `SagaWriterTest` |
| GIMLE-552 | gimle-holmgang | Saga best-effort shipping to a remote report server | Internal/Infra | NONE recorded in the baseline |
| GIMLE-558 | gimle-holmgang | Utgard Docker container fleet management primitives | Internal/Infra | `UtgardExecTest`, `UtgardPollTest`, `UtgardTopologiesTest` |
| GIMLE-030 | gimle-core | Agent↔worker control-channel protocol and codec | Internal/Infra / Protocol | `ControlMessageCodecTest` (module id with qualifier round trips, rejects empty line/unknown type/missing fields/malformed module id) |
| GIMLE-031 | gimle-core | Node registration/heartbeat/capacity-reporting protocol | Internal/Infra / Protocol | NONE recorded in the baseline |
| GIMLE-041 | gimle-core | Saga test-run event model and NDJSON codec | Internal/Infra / Testing | `SagaEventCodecTest` (single line naming type first, absent fields omitted) |
| GIMLE-042 | gimle-core | Stable failure-signature hashing for flaky-test clustering | Internal/Infra / Testing | `FailureSignatureTest` (run-specific numbers don't change signature, hex ids don't change it, different exception types differ, different messages differ, oversized messages truncated) |
| GIMLE-039 | gimle-core | Bundled SPA static-asset resolution from classpath | Internal/Infra / Web | `BundledSpaTest` (file-scheme, jar-scheme, empty when absent, resolves different markers for different consoles) |
| GIMLE-040 | gimle-core | SPA static file serving with client-side-route fallback | Internal/Infra / Web | `SpaStaticHandlerTest` (serves real static file, falls back to shell, missing asset 404s, rejects traversal, rejects symlink escape) |
| GIMLE-184 | gimle-fabric | Locality-Aware Load Balancing with Spillover | Load Balancing | `FabricServiceRegistryTest#same_machine_tier_spills_over_to_remote_once_saturated`, `#an_open_breaker_on_every_same_machine_endpoint_spills_over_to_a_healthy_remote_endpoint` |
| GIMLE-185 | gimle-fabric | Least-Outstanding-Requests Selection | Load Balancing | `LeastOutstandingRequestsSelectorTest#selects_the_candidate_with_fewest_outstanding_requests`, `#ties_are_broken_round_robin`, `#end_never_goes_negative`, `FabricServiceRegistryTest#least_outstanding_requests_prefers_the_idle_endpoint` |
| GIMLE-548 | gimle-holmgang | Surtr scale/churn/performance workload runner | Load Testing | `SurtrWorkloadParserTest`, `SurtrUnitTest`; `SurtrIT.runs_the_configured_surtr_workload` (opt-in via `-Dgimle.surtr.workload=<name\|path>`) |
| GIMLE-549 | gimle-holmgang | Surtr Muninn-window measurement (documented gap) | Load Testing | NONE recorded in the baseline |
| GIMLE-550 | gimle-holmgang | Module-density Tier 1 packing Surtr reference workload | Load Testing | `SurtrIT` (opt-in, `-Dgimle.surtr.workload=module-density`) |
| GIMLE-521 | gimle-smoke-tests | Autoscaling under real request-rate, error-rate, queue-depth, and weighted-blended load | Load Testing / Cluster Validation | `AutoscaleIT.a_deployment_scales_up_under_real_gatling_generated_request_rate_load`, `a_deployment_scales_up_under_real_error_rate_load`, `a_deployment_scales_up_under_real_queue_depth_load`, `a_weighted_policy_blends_request_rate_and_queue_depth_signals_under_real_load` |
| GIMLE-319 | gimle-muninn | Node platform-log ingest | Logging | `MuninnServerLogsIngestTest#an_ingested_node_log_line_is_readable_back`, `#a_malformed_batch_is_rejected_entirely_and_nothing_from_it_is_readable` |
| GIMLE-320 | gimle-muninn | Instance-log ingest | Logging | `MuninnServerLogsIngestTest#an_ingested_instance_log_line_is_readable_back` |
| GIMLE-321 | gimle-muninn | Node/instance log read with cursor paging | Logging | `MuninnServerLogsIngestTest`, `MuninnDayFileStoreTest#read_after_and_read_older_round_trip_through_a_fresh_store_instance` |
| GIMLE-322 | gimle-muninn | `follow=true` rejection on Muninn reads | Logging | `MuninnServerLogsIngestTest#follow_true_is_rejected_since_muninn_only_serves_shipped_history` |
| GIMLE-343 | gimle-observability | Periodic log-file shipping to Muninn | Logging | `MuninnShipperTest#a_successful_tick_ships_new_log_lines_and_advances_the_cursor`, `#a_failed_tick_does_not_advance_the_cursor_and_retries_next_tick` |
| GIMLE-323 | gimle-muninn | Metrics ingest | Metrics | `MuninnServerMetricsIngestTest#an_ingested_counter_and_timer_batch_round_trips_with_measurements_intact`, `#an_ingested_timer_with_percentiles_round_trips_the_percentiles_map` |
| GIMLE-324 | gimle-muninn | Metrics read | Metrics | `MuninnServerMetricsIngestTest` |
| GIMLE-352 | gimle-observability | Per-process tagged Micrometer metrics wrappers | Metrics | `AgentMetricsTest`, `ApiServerMetricsTest`, `WorkerMetricsTest`, `StoreMetricsTest`, `FafnirMetricsTest` (e.g. `#record_request_increments_count_and_records_latency`, `#request_latency_timer_publishes_percentiles_for_muninn_shipping`, `#error_counter_is_not_created_when_no_error_ever_recorded`, `#different_endpoints_and_verbs_are_tagged_independently`) |
| GIMLE-353 | gimle-observability | WorkerMetrics thread-count / metaspace gauges | Metrics | `WorkerMetricsTest#thread_count_gauge_reflects_the_latest_recorded_value_not_the_first`, `#metaspace_gauge_reflects_the_latest_recorded_value_not_the_first` |
| GIMLE-354 | gimle-observability | Fafnir authz-failure counter (rate-limiting signal) | Metrics | `FafnirMetricsTest#authz_failures_are_recorded_and_tagged_by_verb_only`, `#authz_failure_count_is_zero_before_any_failure_is_recorded` |
| GIMLE-001 | gimle-core | Semantic module versioning | Module System | `VersionTest` (parses_major_minor_patch, orders_by_major_then_minor_then_patch, unqualified_outranks_qualified, qualifiers_compare_lexicographically, rejects_negative_components) |
| GIMLE-044 | gimle-module | Module registry (install bookkeeping, idempotent re-install, content-mismatch rejection) | Module System | `ModuleRegistryTest` (register stores as installed, idempotent identical re-register, rejects differing re-register, unknown module id throws, named transitions update state, mark_failed reachable) |
| GIMLE-046 | gimle-module | Dynamic per-module-version JPMS ModuleLayer construction | Module System | `ModuleLayerFactoryTest` (builds dependency-free layer, dependent layer calls into exported API, two versions get distinct layers, missing parent layer fails with GimleResolutionException) |
| GIMLE-049 | gimle-module | Repeated-redeploy flat-metaspace acceptance test | Module System | `RedeployLoopFlatMetaspaceTest#redeploy_loop_keeps_metaspace_flat` |
| GIMLE-051 | gimle-module | Module lifecycle hooks (reflectively instantiated, JPMS-exported) | Module System | `RealHookInvocationTest#hooks_fire_in_order_with_a_dynamically_loaded_module` |
| GIMLE-052 | gimle-module | Job-kind run-to-completion hooks | Module System | `ModuleControllerTest` (complete_succeeded/complete_failed/complete_rejects_non_active) |
| GIMLE-058 | gimle-module | Hot redeploy (old/new version coexistence with pinned dependent wiring) | Module System | `HotRedeployTest#old_and_new_versions_coexist_with_dependents_pinned_to_their_own_wiring` |
| GIMLE-060 | gimle-module | Module artifact reading — real-JPMS-module and descriptor-presence validation | Module System | exercised via `TestModuleBuilderTest`; NONE dedicated `ModuleArtifactReaderTest` found |
| GIMLE-081 | gimle-worker | Module install/resolve/start/stop/uninstall command dispatch | Module System | NONE recorded in the baseline |
| GIMLE-082 | gimle-worker | Instance identity registration and rename-in-place | Module System | NONE recorded in the baseline |
| GIMLE-085 | gimle-worker | Classloader leak detection on undeploy | Module System | NONE recorded in the baseline |
| GIMLE-092 | gimle-worker | Job-kind module execution (run-to-completion, not probed) | Module System | `JobHooksExecutionTest#a_succeeding_job_runs_its_hooks_and_reaches_completed`, `#a_failing_job_reaches_failed`, `#a_job_hooks_run_that_throws_is_treated_as_failed` |
| GIMLE-047 | gimle-module | Unnamed-module readability grant for bundled hooks/probes | Module System / Internal-Infra | gimle-worker's `RealBundledHookAndProbeInvocationTest`; this module's own `ModuleLayerFactoryTest` exercises the general mechanism |
| GIMLE-048 | gimle-module | Classloader leak detection via PhantomReference | Module System / Internal-Infra | `LeakTrackerTest` (no leak when collected, leak reported when retained, wired through ModuleController reports no leak on clean stop) |
| GIMLE-050 | gimle-module | Best-effort leak retaining-path attribution via JFR OldObjectSample | Module System / Internal-Infra | `RetainingPathAttributionTest#leak_detector_surfaces_a_retaining_path_when_the_worker_jvm_enables_path_to_gc_roots` |
| GIMLE-062 | gimle-module | Multi-endpoint Andvari failover on pull | Module System / Internal-Infra | NONE recorded in the baseline |
| GIMLE-100 | gimle-worker | Real bundled-hook/probe classloading against the platform layer | Module System / Internal-Infra | `RealBundledHookAndProbeInvocationTest#bundled_hooks_and_probes_load_and_cast_against_this_jvms_own_platform_types`, `#bundled_probes_instantiate_and_cast_cleanly` |
| GIMLE-006 | gimle-core | Tenant-scoped service export | Module System / Multi-tenancy | `ServiceExportTenantTest` (unrestricted permits any, restricted permits only listed, never permits untenanted caller, empty allow list permits no one) |
| GIMLE-007 | gimle-core | StatefulSet-shaped persistent volume declaration | Module System / Storage | `ModuleDescriptorParserTest` (no_volume_leaves_it_empty, parses_volume_size_and_mount_path, volume_with_missing_mount_path_throws, volume_with_non_positive_size_bytes_throws) |
| GIMLE-009 | gimle-core | Vessel hosting mode (plain-process workload) | Module System / Vessel Hosting | `VesselSpecTest` (no probes/ports is valid, TCP readiness requires a declared port, fixed port allocation carries its number, negative fixed port rejected); VesselArtifacts NONE dedicated |
| GIMLE-037 | gimle-core | Tenant identity and resource quota model | Multi-tenancy | NONE recorded in the baseline |
| GIMLE-271 | gimle-controlplane | Reserved system-tenant auto-seeding | Multi-tenancy / Internal-Infra | Implicit in test fixtures bootstrapping ApiServer |
| GIMLE-574 | gimle-fabric | Per-deployment-scoped NetworkPolicySpec enforcement | Networking/Security | `NetworkPolicyRuleTest`, `HttpNetworkPolicySourceTest`, `FabricServerTest` (3 new deployment-scoping cases), `ControlMessageCodecTest` -- see requirements-matrix.json for detail |
| GIMLE-575 | gimle-agent | Bifrost fails closed for a NetworkPolicySpec-restricted Service | Networking/Security | `BifrostProxyTest` (3 new fail-closed scenarios), `HttpServiceSourceTest` -- see requirements-matrix.json for detail |
| GIMLE-032 | gimle-core | Instance lifecycle event log model | Observability | NONE recorded in the baseline |
| GIMLE-084 | gimle-worker | Durable InstanceEvent emission per lifecycle transition | Observability | NONE recorded in the baseline |
| GIMLE-087 | gimle-worker | OpenTelemetry context propagation across virtual-thread dispatch | Observability | `BoundedModuleSchedulerTest#the_callers_ambient_context_is_restored_inside_the_submitted_task`, `#a_submission_made_outside_any_context_scope_sees_no_value_for_that_key` |
| GIMLE-127 | gimle-agent | Node/instance log-serving HTTP surface with tailing and follow | Observability | `AgentLogServerTest#node_platform_logs_have_the_shape_the_console_and_cli_need`, `#instance_application_logs_are_scoped_to_the_right_deployment_and_index`, `#instance_logs_reject_a_deployment_name_containing_a_path_separator`, `#instance_logs_reject_a_deployment_name_that_would_escape_the_log_root` |
| GIMLE-128 | gimle-agent | Merged node-level SYSTEM log view | Observability | NONE recorded in the baseline |
| GIMLE-130 | gimle-agent | Node-agent log/metrics shipping to Muninn (own + supervised) | Observability | `AgentMuninnShippingTest#a_null_muninn_endpoint_starts_no_shippers`, `#a_configured_endpoint_ships_the_instances_application_log_to_its_own_instance_scoped_path`, `#stopping_shipping_removes_the_key_and_closes_every_shipper_so_no_further_ticks_arrive`, `#a_null_muninn_endpoint_starts_no_worker_shippers`, `#a_configured_endpoint_starts_one_metrics_and_one_traces_shipper_keyed_by_worker_id`, `#starting_twice_for_the_same_worker_id_is_a_noop_not_a_second_pair`, `#stopping_removes_the_key_and_a_missing_worker_id_is_a_noop`, `#hello_then_metrics_and_traces_snapshots_relay_to_the_stub_muninn_server` |
| GIMLE-133 | gimle-agent | Instance-event forwarding (worker-reported and agent-originated) to control plane | Observability | NONE recorded in the baseline |
| GIMLE-331 | gimle-muninn | Age-based retention sweep | Observability | `RetentionSweeperTest#a_day_file_older_than_the_retention_window_is_deleted`, `#a_day_file_within_the_retention_window_survives`, `#sweeping_twice_is_idempotent...`, `#sweeping_a_data_root_that_does_not_exist_yet_is_a_no_op` |
| GIMLE-339 | gimle-muninn | `/status` operational endpoint | Observability | `MuninnServerTest#a_fresh_server_defaults_to_plaintext_and_answers_status`, `#a_non_get_status_request_is_rejected` |
| GIMLE-351 | gimle-observability | JFR-based per-module CPU/allocation attribution | Observability | `ThreadNameJfrAttributorTest#construction_and_shutdown_do_not_throw`, `#register_and_unregister_module_do_not_throw`, `#unregistering_a_module_never_registered_does_not_throw` (no test directly asserts a classified sample producing a counter increment — JFR event emission isn't driven from the test) |
| GIMLE-097 | gimle-worker | Per-module CPU/memory/request-rate/error-rate metrics reporting (portable, no cgroup) | Observability / Cgroup Management | NONE recorded in the baseline |
| GIMLE-129 | gimle-agent | `hs_err_pid*.log` crash-dump listing and fetch | Observability / Cgroup Management | `AgentLogServerTest#crash_dumps_are_listed_from_the_right_worker_directory_only`, `#crash_dumps_list_is_empty_when_the_worker_never_crashed`, `#a_crash_dump_is_fetched_with_its_exact_content_and_a_plain_text_content_type`, `#crash_dump_fetch_rejects_a_filename_that_does_not_match_the_expected_pattern` |
| GIMLE-083 | gimle-worker | Per-instance MDC log tagging for lifecycle/hook/probe/request-dispatch logging | Observability / Internal-Infra | `BoundedModuleSchedulerTest#mdc_tags_are_visible_inside_a_tagged_submission`, `#empty_mdc_tags_leave_the_submission_untagged`; `InstanceTaggingServiceRegistryTest#registers_untagged_when_no_identity_is_known_for_the_owner`, `#registers_a_tagging_proxy_when_identity_is_known` |
| GIMLE-096 | gimle-worker | Worker-side trace relay to agent (no direct Muninn shipping) | Observability / Internal-Infra | `RelayingSpanExporterTest#a_real_span_batch_relays_as_a_traces_snapshot_with_the_given_worker_id`, `#export_never_throws_even_when_the_sink_throws`, `#flush_and_shutdown_always_report_success` |
| GIMLE-098 | gimle-worker | Worker-wide meter snapshot relay to Muninn (via agent) | Observability / Internal-Infra | NONE recorded in the baseline |
| GIMLE-105 | gimle-agent | Worker stdout draining, JSON-line de-duplication, and raw SYSTEM-line capture | Observability / Internal-Infra | `SystemLogCaptureTest#system_log_capture_survives_a_respawn` |
| GIMLE-019 | gimle-core | Structured JSON log encoding with APPLICATION/PLATFORM categorization | Observability / Logging | `JsonLogEncoderTest` (categorizes platform/application, tenant id included only when present, process role/node id read fresh) |
| GIMLE-020 | gimle-core | Human-readable colored console log encoding | Observability / Logging | `TextLogEncoderTest`, `AnsiPaletteTest` (override wins regardless of environment) |
| GIMLE-021 | gimle-core | Runtime-switchable console log format (text default, JSON opt-in) | Observability / Logging | `ConsoleLogEncoderTest` (explicit json/text override, no override defaults to text) |
| GIMLE-022 | gimle-core | MDC-tagged proxying for same-worker and probe-loop invocations | Observability / Logging | `InstanceMdcContextTest` (tag_proxy sets/restores MDC, restores on throw, run_tagged restores previous value) |
| GIMLE-023 | gimle-core | Per-instance sifted log files | Observability / Logging | `InstanceSiftingFileAppenderTest` (routes application lines by deployment/instance, skips platform lines, never leaks across instances, reopens after close) |
| GIMLE-024 | gimle-core | Platform (non-instance) log file appender | Observability / Logging | Exercised via `LogRotationTest`; no dedicated unit test class |
| GIMLE-025 | gimle-core | Kubelet-style size/count log rotation | Observability / Logging | `LogRotationTest` (rolls over by size and evicts oldest, cursor paging/follow resolve correctly across rotation) |
| GIMLE-026 | gimle-core | Cursor-based log paging and live-follow streaming | Observability / Logging | `LogRotationTest#cursor_paging_and_follow_resolve_correctly_across_a_rotation_boundary` |
| GIMLE-113 | gimle-agent | Worker-crash-to-durable-InstanceEvent relay | Observability / Self-Healing | NONE recorded in the baseline |
| GIMLE-132 | gimle-agent | Node capacity/instance-observation heartbeat reporting | Observability / Worker Supervision | `AgentMainTest#observation_json_reports_the_instances_real_self_reported_resource_usage`, `#observation_json_reports_the_instances_real_self_reported_request_and_error_rate`, `#observation_json_reports_a_completed_job_run_as_alive_but_not_ready`, `#observation_json_reports_a_failed_instance_as_not_alive` |
| GIMLE-242 | gimle-controlplane | Reconciler-leader election via non-replicated lease | Orchestration / Internal-Infra | Indirect (multi-replica smoke/holmgang tests) |
| GIMLE-075 | gimle-pki | Randomized certificate-renewal scheduling (anti-thundering-herd) | PKI | NONE recorded in the baseline |
| GIMLE-074 | gimle-pki | Hand-rolled PEM encode/decode for certs, CSRs, and private keys | PKI / Internal-Infra | exercised indirectly throughout CertificateAuthorityTest (`generated_leaf_certificate_is_readable_by_openssl`, `certificate_survives_a_keystore_round_trip`); NONE dedicated PemTest |
| GIMLE-078 | gimle-pki | Cluster PKI bootstrap CLI (`mvn gimle:tls-init`) | PKI / Internal-Infra | NONE recorded in the baseline |
| GIMLE-560 | gimle-dist | Standalone CLI distribution archive | Packaging | NONE recorded in the baseline |
| GIMLE-561 | gimle-dist | Standalone Hilmir bootstrap-tool distribution archive | Packaging | NONE recorded in the baseline |
| GIMLE-562 | gimle-dist | Cluster-machine platform distribution archive | Packaging | NONE recorded in the baseline |
| GIMLE-563 | gimle-dist | Opt-in bundled-JRE distribution variant (`dist-with-jre` profile) | Packaging | NONE recorded in the baseline |
| GIMLE-564 | gimle-dist | Distribution archive checksums and SBOM generation | Packaging | NONE recorded in the baseline |
| GIMLE-559 | gimle-holmgang | Docker Compose manual validation topologies (bundled-JRE and full-JRE) | Packaging / Internal-Infra | NONE recorded in the baseline |
| GIMLE-139 | gimle-mimir | Conflicting-Entry Truncation | Raft Consensus | `RaftNodeSafetyMechanicsTest#a_follower_truncates_a_conflicting_entry_and_everything_after_it_before_appending` |
| GIMLE-140 | gimle-mimir | Leader-Only-Commits-Own-Term Rule (Figure 8) | Raft Consensus | `RaftNodeSafetyMechanicsTest#the_leader_only_commits_an_entry_from_its_own_current_term` |
| GIMLE-565 | gimle-mimir | Norn deterministic virtual-time Raft fault-injection simulation | Raft Consensus / Internal-Infra / Testing | `NornRaftSimulationTest#raft_safety_invariants_hold_across_many_seeded_fault_schedules` — 20 seeds x 40 rounds, asserting Election Safety and Log Matching after every round plus eventual liveness after each seed's storm ends |
| GIMLE-220 | gimle-controlplane | Deployment scale-down | Reconciliation | `DeploymentReconcilerTest#scale_down_removes_assignments_at_or_beyond_the_new_replica_count` |
| GIMLE-227 | gimle-controlplane | Readiness-only failures never trigger reschedule | Reconciliation | `HealthReconcilerTest#readiness_alone_never_triggers_a_reschedule` |
| GIMLE-240 | gimle-controlplane | CronJob missed-schedule starting-deadline handling | Reconciliation | Covered indirectly by `CronJobReconcilerTest`'s convergence/missed-schedule handling |
| GIMLE-239 | gimle-controlplane | CronJob manual trigger (`gimle cronjob trigger`) | Reconciliation / API Server | `CronJobReconcilerTest#trigger_now_fires_immediately_and_does_not_touch_last_schedule_time`; `ApiServerTest#trigger_fires_immediately_and_the_generated_job_appears_on_the_jobs_list` |
| GIMLE-221 | gimle-controlplane | Artifact-hash drift detection at reconcile time | Reconciliation / Internal-Infra | `DeploymentReconcilerTest` — `places_new_instances_when_the_recorded_artifact_hash_still_matches_the_jar_on_disk`, `refuses_to_place_new_instances_once_the_jar_on_disk_no_longer_matches_the_recorded_hash` |
| GIMLE-225 | gimle-controlplane | Persisted grace-period bookkeeping (survives leader failover) | Reconciliation / Internal-Infra | `ReplicaCountReconcilerTest`; `HealthReconcilerTest#backoff_state_survives_a_reconciler_reconstruction_against_the_same_store` |
| GIMLE-231 | gimle-controlplane | DaemonSet reconciliation and rolling update | Reconciliation / Orchestration | `DaemonSetReconcilerTest#rolling_update_replaces_one_node_at_a_time_and_waits_for_readiness` |
| GIMLE-233 | gimle-controlplane | StatefulSet OrderedReady placement | Reconciliation / Orchestration | `StatefulSetReconcilerTest` — `does_not_place_index_one_until_index_zero_reports_ready`, `places_index_one_once_index_zero_becomes_ready` |
| GIMLE-234 | gimle-controlplane | StatefulSet one-index-at-a-time scale-down | Reconciliation / Orchestration | `StatefulSetReconcilerTest#scale_down_removes_the_highest_index_first_one_at_a_time` |
| GIMLE-235 | gimle-controlplane | JobRun run-to-completion reconciliation | Reconciliation / Orchestration | `JobReconcilerTest` — `a_failed_observation_retries_the_next_attempt_when_backoff_budget_remains`, `exhausting_the_backoff_limit_marks_the_job_permanently_failed`, `an_arbitrary_starting_snapshot_with_two_coexisting_runs_converges_to_the_highest_attempt` |
| GIMLE-236 | gimle-controlplane | Job active-deadline enforcement | Reconciliation / Orchestration | `JobReconcilerTest#exceeding_the_active_deadline_marks_the_job_permanently_failed_even_mid_attempt` |
| GIMLE-237 | gimle-controlplane | CronJob schedule-driven Job materialization | Reconciliation / Orchestration | `CronJobReconcilerTest` — `first_tick_records_a_baseline_and_materializes_nothing`, `a_due_firing_materializes_a_job_named_with_the_epoch_second_suffix` |
| GIMLE-238 | gimle-controlplane | CronJob concurrency policy (Allow/Forbid/Replace) | Reconciliation / Orchestration | `CronJobReconcilerTest` — `concurrency_policy_forbid_skips_a_firing_while_the_previous_one_is_still_running`, `concurrency_policy_replace_removes_the_still_running_job_before_placing_the_new_one`, `concurrency_policy_allow_lets_a_new_firing_run_alongside_a_still_running_one` |
| GIMLE-230 | gimle-controlplane | Autoscaling WEIGHTED combination mode | Reconciliation / Scheduling | `AutoscaleReconcilerTest` — `weighted_mode_blends_two_signals_instead_of_taking_the_max`, `weighted_mode_with_no_weights_configured_behaves_like_an_unweighted_average` |
| GIMLE-224 | gimle-controlplane | Node-death instance reclamation (`ReplicaCountReconciler`) | Reconciliation / Self-healing | `ReplicaCountReconcilerTest` (grace-period and persisted-state convergence tests present) |
| GIMLE-226 | gimle-controlplane | Unhealthy-instance backoff-gated reschedule (`HealthReconciler`) | Reconciliation / Self-healing | `HealthReconcilerTest` — `an_unhealthy_instance_is_rescheduled_once_its_backoff_elapses`, `repeated_failures_across_reschedules_eventually_exhaust_the_budget_and_stop_retrying`, `converges_correctly_from_an_arbitrary_mix_of_persisted_backoff_states` |
| GIMLE-232 | gimle-controlplane | DaemonSet dark-node placement-safety grace period | Reconciliation / Self-healing | `DaemonSetReconcilerTest#a_replica_on_a_dark_but_not_yet_timed_out_node_is_not_relocated`, `cordoning_a_dark_node_still_removes_its_assignment_immediately` |
| GIMLE-390 | gimle-hilmir | Topology validation (`hilmir validate`) | Release Management | `TopologyValidatorTest` (extensive, ~25+ tests); `HilmirMainTest.validate_exits_zero_for_a_topology_with_no_error_severity_findings`, `validate_exits_one_and_lists_errors_before_warnings_for_a_broken_topology` |
| GIMLE-391 | gimle-hilmir | Cluster launch planning (`hilmir plan`) | Release Management | `HilmirMainTest.plan_prints_the_resolved_commands_for_a_healthy_topology`, `plan_filters_to_one_machine_when_requested`, `plan_aborts_with_findings_and_exit_one_when_the_topology_has_an_error`; `LaunchPlannerTest` (multiple) |
| GIMLE-392 | gimle-hilmir | Real multi-process cluster bring-up (`hilmir up`) | Release Management | `MachineLauncherIntegrationTest.up_waits_on_a_remote_prerequisite_then_down_and_status_reflect_the_real_processes`; `HilmirMainTest.up_requires_the_machine_flag`, `up_aborts_with_findings_before_launching_anything_when_the_topology_has_an_error`; `BootOrderTest` |
| GIMLE-393 | gimle-hilmir | Cluster teardown and status reporting (`hilmir down`/`status`) | Release Management | `MachineLauncherIntegrationTest.down_is_a_clean_no_op_for_an_already_dead_recorded_pid`, `status_reports_a_dead_pid_as_not_alive_and_a_never_bound_address_as_closed`; `HilmirCliDownStatusEndToEndTest`; `HilmirMainTest` (multiple) |
| GIMLE-395 | gimle-hilmir | Raft store membership add (`hilmir store add`) | Release Management | `StoreCommandsClusterTest.add_joins_a_real_peer_and_it_becomes_a_visible_cluster_member`; `HilmirMainTest` (positional args, one-of-topology/server); `StoreEndpointsTest` |
| GIMLE-396 | gimle-hilmir | Raft store membership remove (`hilmir store remove`) | Release Management | `StoreCommandsClusterTest.remove_drops_a_previously_added_peer_from_the_membership`, `remove_of_a_never_added_peer_fails_fast_with_a_clean_error` |
| GIMLE-397 | gimle-hilmir | Per-machine platform binary rolling upgrade with quorum-safe store restart (`hilmir upgrade-cluster`) | Release Management | `UpgradeClusterCommandTest` (multiple); `MachineLauncherRestartRoleIntegrationTest` (multiple); `MachineLauncherStoreQuorumGateTest` (multiple) |
| GIMLE-398 | gimle-hilmir | Bundle-based fresh release deployment (`hilmir deploy`) | Release Management | `DeployCommandTest` (multiple, incl. dry-run, unresolved value ref, json output, wait); `HilmirMainTest.deploy_requires_the_file_flag` |
| GIMLE-399 | gimle-hilmir | Bundle upgrade with automatic resource pruning (`hilmir upgrade`) | Release Management | `UpgradeCommandTest` (prunes workload, requires existing release, dry-run computes prune with no mutating call) |
| GIMLE-400 | gimle-hilmir | Release rollback to a prior revision (`hilmir rollback`) | Release Management | `RollbackCommandTest` (multiple); `HilmirMainTest.rollback_requires_the_release_flag` |
| GIMLE-401 | gimle-hilmir | Full release teardown (`hilmir undeploy`) | Release Management | `UndeployCommandTest` (multiple); `HilmirMainTest.undeploy_requires_the_release_flag` |
| GIMLE-402 | gimle-hilmir | Release listing (`hilmir releases`) | Release Management | `ReleasesCommandTest` (2 tests) |
| GIMLE-403 | gimle-hilmir | Release status inspection (`hilmir release-status`) | Release Management | `ReleaseStatusCommandTest`; `HilmirMainTest.release_status_requires_a_release_name` |
| GIMLE-404 | gimle-hilmir | GitOps directory reconciliation (`hilmir sync`, incl. `--watch` and `--prune`) | Release Management | `SyncCommandTest` (11 tests) |
| GIMLE-405 | gimle-hilmir | `--watch` interval loop for sync | Release Management | NONE recorded in the baseline |
| GIMLE-406 | gimle-hilmir | Bundle value templating and override precedence (`${values.*}` substitution) | Release Management | `BundleRendererTest` (6 tests); `ValueOverridesTest` (4 tests) |
| GIMLE-407 | gimle-hilmir | Bundle manifest schema parsing and validation | Release Management | `BundleParserTest` (8 tests) |
| GIMLE-408 | gimle-hilmir | Workload readiness polling for `--wait` | Release Management | `DeployCommandTest.wait_polls_until_the_workloads_instances_report_active` (indirect); NONE dedicated |
| GIMLE-412 | gimle-hilmir | Gateway extension enable (`hilmir enable gateway`) | Release Management | `EnableGatewayCommandTest` (5 tests); `GatewayJarLocatorTest` (7 tests) |
| GIMLE-413 | gimle-hilmir | Gateway extension disable (`hilmir disable gateway`) | Release Management | `DisableGatewayCommandTest` (2 tests) |
| GIMLE-576 | gimle-hilmir | Remote (SSH) fleet bootstrap (`hilmir up/down/status --remote`) | Release Management | `RemoteDispatchTest` (provisioning, material distribution, host-key pinning incl. a simulated mismatch); `ResolvedSshTargetTest`; `SshProcessExecTest`; `PkiBootstrapMainTest`; `PkiInitTest`; `HilmirMainTest.up_with_remote_does_not_require_the_machine_flag`, `down_with_remote_requires_the_file_flag`, `status_with_remote_requires_the_file_flag`; `TopologyParserTest`; `UtgardSshDeployIT` (real Docker+SSH round trip against a genuine sshd) |
| GIMLE-580 | gimle-hilmir | `hilmir upgrade-cluster --remote` (SSH-dispatched platform binary rollout) | Release Management | `RemoteDispatchTest.upgrade_cluster_dispatches_the_new_classpath_and_roles_to_every_machine` |
| GIMLE-394 | gimle-hilmir | Cluster TLS/PKI bootstrap (`hilmir pki init`) | Release Management / Security | `PkiInitTest` (multiple); `HilmirMainTest.pki_requires_the_init_subcommand`, `pki_init_requires_the_file_flag`, `pki_init_refuses_a_topology_with_no_tls_material_dir_dir` |
| GIMLE-482 | gimle-saga | NDJSON event ingest API | Reporting backend / Internal-Infra | `SagaServerTest.java` — "ingested_events_round_trip_through_the_runs_and_events_apis", "a_malformed_ingest_line_is_rejected_with_its_line_number"; `SagaStoreTest.java#ingest_then_read_round_trips_events_and_meta` |
| GIMLE-483 | gimle-saga | Idempotent per-run ingest / re-ingest replacement | Reporting backend / Internal-Infra | `SagaStoreTest.java#re_ingesting_a_whole_run_replaces_it_without_double_counting_the_ledger` |
| GIMLE-484 | gimle-saga | Crash-safe append (torn-tail recovery) | Reporting backend / Internal-Infra | `SagaStoreTest.java#a_torn_trailing_line_is_skipped_on_read`, `#an_append_after_a_torn_line_never_fuses_two_events_into_one` |
| GIMLE-485 | gimle-saga | Surefire/Failsafe XML import | Reporting backend / Internal-Infra | `SurefireXmlImporterTest.java`, `SagaServerTest.java#importing_surefire_xml_with_a_flaky_failure_lands_a_run_and_a_flake_observation` |
| GIMLE-486 | gimle-saga | Fold-import safety net for a live run's gap | Reporting backend / Internal-Infra | `SagaStoreTest.java#fold_appends_only_test_ids_the_live_stream_never_finished_and_drops_framing`, `#fold_without_an_existing_run_ingests_the_batch_unmodified` |
| GIMLE-487 | gimle-saga | Run listing, detail, and cursor-paginated event reads | Reporting backend / Internal-Infra | `SagaStoreTest.java#runs_list_newest_first_and_honors_the_limit`, `#a_run_with_events_but_no_meta_file_is_reconstructed_from_its_events`, `#the_events_cursor_resumes_from_a_line_offset`; `SagaServerTest.java#an_unknown_run_returns_404` |
| GIMLE-488 | gimle-saga | Live NDJSON tail (`follow=true`) of a run's event stream | Reporting backend / Internal-Infra | `SagaServerTest.java#follow_streams_new_lines_as_they_arrive_and_ends_when_the_run_finishes` |
| GIMLE-489 | gimle-saga | Abandoned-run detection on restart | Reporting backend / Internal-Infra | `SagaStoreTest.java#a_live_run_is_marked_abandoned_at_startup` |
| GIMLE-490 | gimle-saga | Flake ledger derivation (fail-then-pass rule) and rebuild | Reporting backend / Internal-Infra | `SagaStoreTest.java#a_failed_attempt_followed_by_a_passing_retry_yields_one_flake_observation`, `#a_test_that_fails_every_attempt_yields_no_flake_observation`, `#rebuild_ledger_reproduces_the_derived_observations_from_scratch`, `#an_unparseable_ledger_line_is_skipped_not_fatal` |
| GIMLE-491 | gimle-saga | Flaky scoreboard with time-window ranking | Reporting backend / Internal-Infra | `SagaStoreTest.java#the_flaky_scoreboard_counts_runs_seen_and_ranks_by_score`, `#the_flaky_scoreboard_window_excludes_older_observations`; `SagaServerTest.java#flaky_entries_carry_quarantine_status_and_the_response_carries_the_budget_allowance` |
| GIMLE-492 | gimle-saga | Test-tag index and quarantine status | Reporting backend / Internal-Infra | `SagaStoreTest.java#a_test_tagged_flaky_is_quarantined_and_an_untagged_one_is_not`, `#the_latest_tag_set_for_a_test_id_overwrites_an_earlier_one`, `#the_test_tags_index_survives_a_store_restart` |
| GIMLE-493 | gimle-saga | Per-test history endpoint | Reporting backend / Internal-Infra | `SagaStoreTest.java#test_history_reports_final_outcome_and_flakiness_per_run_newest_first` |
| GIMLE-005 | gimle-core | Kubernetes-shaped resource quantity parsing | Resource Limiting | Indirect via ModuleDescriptorTest, VesselSpecTest — NONE direct |
| GIMLE-064 | gimle-os | Pluggable resource-limiter abstraction | Resource Limiting | exercised via `PortableJvmFlagsResourceLimiterTest` |
| GIMLE-065 | gimle-os | Portable JVM-flags resource enforcement (Tier 1/Tier 2) | Resource Limiting | `PortableJvmFlagsResourceLimiterTest` (supports tier 1/2 not 3, prepare returns handle, jvm flags derive Xmx/ActiveProcessorCount, release no-op) |
| GIMLE-066 | gimle-os | Tier 3 (namespace isolation) — deliberately unsupported by the current limiter | Resource Limiting | `PortableJvmFlagsResourceLimiterTest#supports_tier_1_and_tier_2_but_not_tier_3` |
| GIMLE-067 | gimle-os | Kernel-level (cgroup v2) resource enforcement — deferred | Resource Limiting | NONE recorded in the baseline |
| GIMLE-503 | gimle-examples | `hello-module` — minimal inert deployable fixture | Sample Module | NONE recorded in the baseline |
| GIMLE-035 | gimle-core | Assigned-instance work-order model (incl. in-place rename and vessel dispatch) | Scheduling | NONE recorded in the baseline |
| GIMLE-211 | gimle-controlplane | First-fit-decreasing bin-packing scheduler | Scheduling | `SchedulerTest` — `places_on_the_only_feasible_node`, `prefers_the_node_with_more_free_capacity`, `throws_when_no_node_has_enough_free_capacity` |
| GIMLE-212 | gimle-controlplane | Isolation-tier placement filtering | Scheduling | `SchedulerTest` — `rejects_a_node_that_does_not_support_the_requested_tier`, `throws_when_no_node_supports_the_requested_tier` |
| GIMLE-214 | gimle-controlplane | Strict anti-affinity across nodes | Scheduling | `SchedulerTest` — `anti_affinity_excludes_nodes_already_running_a_replica_of_the_same_deployment`, `anti_affinity_fails_outright_rather_than_placing_on_an_occupied_node` |
| GIMLE-216 | gimle-controlplane | Required node-label placement constraint | Scheduling | `SchedulerTest` — `required_labels_excludes_a_node_missing_one_of_them`, `required_labels_fails_outright_when_no_capable_node_carries_them` |
| GIMLE-218 | gimle-controlplane | DaemonSet eligible-node enumeration (`eligibleNodes`) | Scheduling | `SchedulerTest` — `eligible_nodes_returns_every_node_that_passes_every_filter`, `eligible_nodes_returns_an_empty_list_rather_than_throwing_when_nothing_qualifies`; `DaemonSetReconcilerTest#places_an_assignment_on_every_registered_node` |
| GIMLE-215 | gimle-controlplane | Tier 2/3 node-level tenant isolation | Scheduling / Multi-tenancy | `SchedulerTest` — `tenant_isolation_permits_a_node_already_running_the_same_tenant`, `tenant_isolation_fails_outright_when_every_capable_node_hosts_a_different_tenant` |
| GIMLE-217 | gimle-controlplane | StatefulSet sticky node placement | Scheduling / Orchestration | `SchedulerTest` — `sticky_placement_returns_the_sticky_node_even_when_a_roomier_node_exists`, `sticky_placement_fails_outright_rather_than_choosing_a_different_node_when_sticky_is_gone` |
| GIMLE-588 | gimle-fafnir | SecretMap store and `/secretmaps/*` API | Secrets Management | `SecretMapCodecTest`, `SecretMapStoreTest` (including a concurrency regression test mirroring `ConfigMapStoreTest`'s own), `FafnirServerSecretMapTest` (HTTP-level CRUD, authz, and reserved-prefix rejection). |
| GIMLE-589 | gimle-mimir | Deployment `secretMapRefs` field with admission-time collision rejection | Secrets Management | `SecretMapRefsPluginTest` covers empty refs, no-tenant rejection, unknown-name rejection, cross-SecretMap key collision, SecretMap-vs-ConfigMap collision, SecretMap-vs-flat-config collision, and SecretMap-vs-flat-secret collision. `DomainCodecTest`/`DeploymentManifestParserTest` cover the wire/YAML round trip. |
| GIMLE-590 | gimle-controlplane | `/secretmaps/*` proxy and `ResourceKind.SECRETMAP` RBAC | Secrets Management | `ApiServerSecretMapTest` (plaintext CRUD through the proxy to a real in-process Fafnir), `ApiServerSecretMapAuthzTest` (real mTLS: an operator role may write/read, a no-grant caller gets 403 on both). |
| GIMLE-591 | gimle-agent | Narrowed secret delivery via `secretMapRefs` | Secrets Management | `AgentMainTest#secret_map_refs_narrows_delivery_to_only_the_named_secretmaps_keys` drives a real fake Fafnir + control-plane HTTP server pair and a real Unix-socket `WorkerConnection`, asserting only the named SecretMap's key arrives as `ConfigDelivered` and that the unscoped flat `/secrets/{tenantId}` listing is never even called once `secretMapRefs` is declared. |
| GIMLE-594 | gimle-fafnir | SecretMap group-version ledger and rollback | Secrets Management | `SecretMapStoreTest` (group-version stamping on set/delete, skip-on-no-change, listGroupVersions ordering, rollback restoring live and deleted keys, leaving newer keys untouched, per-key failure on an unrecoverable hard-deleted key, unknown-target `TargetNotFound`, and a concurrency regression test asserting concurrent `setMany`/`rollback` calls on the same name never corrupt the group-version sequence), `SecretStoreTest` (`listLinearizable` parity with `list`), `FafnirServerSecretMapTest` (HTTP-level `/versions`/`/rollback`, 404 on an unknown group version, 400 on a non-integer body), `ApiServerSecretMapTest` (proxy round-trip for both new routes). |
| GIMLE-597 | gimle-fafnir | Sealed SecretMap envelope crypto and key retirement | Secrets Management | `SealCipherTest`, `SealingKeyRingTest`, `SealingKeyFileManagerTest`, and `KeyFileManagerTest`'s new retirement cases cover the round-trip, rotation, and destructive-retirement behavior in full. |
| GIMLE-598 | gimle-fafnir | `/seal/*` and key-retirement HTTP routes | Secrets Management | `FafnirServerSealTest` covers the full exit criterion end to end: seal, commit, apply, wrong-tenant/name rejection, and retirement stopping trust. |
| GIMLE-599 | gimle-controlplane | `/seal/*` and `/secrets/retire-key` proxy routes | Secrets Management | `ApiServerSealTest` (plaintext proxy round-trip) and `ApiServerSealAuthzTest` (real mTLS/RBAC, including the deliberate no-auth public-key route) cover this in full. |
| GIMLE-262 | gimle-controlplane | `/secrets/*` byte-for-byte proxy to Fafnir | Secrets Management / Internal-Infra | `ApiServerAuthzTest#config_and_secret_permissions_are_independently_enforced_and_filtered`, `a_secret_survives_key_rotation_and_new_secrets_use_the_rotated_key` |
| GIMLE-280 | gimle-fafnir | Key-ring fingerprinting for cross-replica drift detection | Secrets Management / Internal-Infra | `KeyRingTest` — `fingerprint_does_not_depend_on_keysbyid_map_iteration_order`, `fingerprint_changes_when_key_material_differs`, `fingerprint_changes_after_a_real_rotation_via_keyfilemanager` |
| GIMLE-283 | gimle-fafnir | Optimistic-write versioned put with narrow-lease serialization | Secrets Management / Internal-Infra | `SecretStoreTest` (contention scenario per class javadoc) |
| GIMLE-017 | gimle-core | Session-signing key file load-or-create with owner-only permissions | Security | `SessionKeyFileManagerTest` (generates_on_first_run_reuses_on_later, rejects corrupted/empty key file) |
| GIMLE-122 | gimle-agent | Vessel crash respawn resets probe initial-delay clock | Self-Healing | NONE recorded in the baseline |
| GIMLE-181 | gimle-fabric | Same-Worker Direct Invocation Tier | Service Fabric | `FabricServiceRegistryTest#same_worker_tier_wins_over_same_machine_and_remote` |
| GIMLE-183 | gimle-fabric | Cross-Machine TCP Invocation Tier | Service Fabric | `FabricServiceRegistryTest#least_outstanding_requests_prefers_the_idle_endpoint`, `FabricTransportTlsTest#cross_machine_invocation_succeeds_over_mtls` |
| GIMLE-190 | gimle-fabric | Gossip-Propagated Service Catalog | Service Fabric | `ServiceCatalogTest#a_local_registration_is_immediately_visible`, `#gossip_deltas_round_trip_and_merge_into_a_second_catalog`, `#a_stale_delta_at_a_lower_version_is_ignored`, `#two_different_workers_can_both_export_the_same_interface` |
| GIMLE-191 | gimle-fabric | Catalog Eviction on Gossip-Detected Node Death | Service Fabric | `GossipMemberTest#a_node_marked_dead_via_gossip_has_its_catalog_entries_evicted_without_a_breaker_trip` |
| GIMLE-192 | gimle-fabric | Cross-Tenant Service Export Access Control | Service Fabric | `FabricServiceRegistryTest#a_caller_belonging_to_an_allowed_tenant_reaches_a_restricted_export`, `#a_caller_from_a_different_tenant_cannot_reach_a_restricted_export`, `#a_tenanted_caller_cannot_reach_an_unrestricted_export_with_default_deny_cross_tenant_on`, `FabricServiceRegistryInvokeByNameTest#a_caller_from_a_different_tenant_cannot_invoke_a_restricted_export_by_name` |
| GIMLE-193 | gimle-fabric | Runtime Name-Driven Cross-Tier Invocation (invokeByName) | Service Fabric | `FabricServiceRegistryInvokeByNameTest#a_same_worker_registration_is_invoked_directly_by_name`, `#a_same_machine_registration_is_invoked_over_the_wire_by_name`, `#a_remote_registration_is_invoked_over_the_wire_by_name`, `#wrong_param_type_names_fail_clearly_rather_than_hanging_or_matching_a_wrong_overload` |
| GIMLE-194 | gimle-fabric | Inbound Call Dispatch with Bounded Concurrency | Service Fabric | `FabricServerTest#a_real_inbound_call_is_visible_in_the_targets_in_flight_count_while_it_runs`, `#concurrent_calls_are_bounded_by_the_targets_executor_not_run_unbounded`, `#real_calls_are_recorded_in_the_targets_worker_metrics_including_errors` |
| GIMLE-195 | gimle-fabric | Distributed Trace Propagation Across Fabric Hops | Service Fabric | `FabricServerTest#baggage_from_the_caller_survives_an_inbound_call_into_the_handler`, `#has_remote_span_distinguishes_a_real_caller_span_from_the_no_active_span_marker`, `FabricServerGlobalTracingTest#a_call_with_no_active_caller_span_starts_a_fresh_valid_trace_not_the_all_zero_marker`, `transport/FabricCodecTest#round_trips_a_non_empty_tracestate_and_baggage` |
| GIMLE-196 | gimle-fabric | Fabric Transport over Mutual TLS with Hot Cert Reload | Service Fabric | `FabricTransportTlsTest#cross_machine_invocation_succeeds_over_mtls`, `#cross_machine_call_is_rejected_when_client_trusts_a_different_ca` |
| GIMLE-568 | gimle-agent | gimle-bifrost: per-node service proxy (kube-proxy analogue) | Service Fabric | `BifrostProxyTest` (3 tests: round-robin across endpoints, listener closed on service disappearance, new listener bound on service appearance); `LoopbackAddressAllocatorTest`; `HttpServiceSourceTest` |
| GIMLE-569 | gimle-skald | gimle-skald: cluster DNS server resolving Service names to live endpoints | Service Fabric | `SkaldServerTest` (6 tests over the real UDP responder: tenant-scoped hit, untenanted-hit round-robin, NXDOMAIN for unknown name, NOTIMP for unsupported query type/opcode, malformed datagram dropped); `CachingServiceDirectoryTest`; `ControlPlaneServicePollerTest`; `DnsCodecTest`; `ServiceDnsNamesTest` |
| GIMLE-068 | gimle-os | Pluggable persistent-volume-manager abstraction | Storage | exercised via `LocalDiskVolumeManagerTest` |
| GIMLE-069 | gimle-os | Local-disk persistent volume allocation for StatefulSet-shaped instances | Storage | `LocalDiskVolumeManagerTest` (creates keyed directory, idempotent for same index, distinct dirs per index/statefulset, throws when exceeding usable space, release deletes contents, release of never-allocated is no-op) |
| GIMLE-498 | gimle-testkit | Heimdall event-driven cluster condition harness | Test Infrastructure | NONE recorded in the baseline |
| GIMLE-499 | gimle-testkit | Replica-scoped condition observation | Test Infrastructure | Exercised by `HaTopologyIT.deployments_written_via_one_replica_are_observed_active_via_the_other`, `deployment-lifecycle.feature` |
| GIMLE-500 | gimle-testkit | Deployment/node/log condition builders | Test Infrastructure | Exercised throughout gimle-holmgang `*.feature` files and `HaTopologyIT`/`MinimalTopologyIT` |
| GIMLE-501 | gimle-testkit | Time-windowed negative invariants (`Invariant`/`InvariantGuard`) | Test Infrastructure | `InvariantTest`; `rolling-update.feature`, `quota-and-admission.feature` |
| GIMLE-502 | gimle-testkit | Forensic failure reporting | Test Infrastructure | `ForensicReportTest`; `MinimalTopologyIT.a_failed_condition_reports_the_cluster_state_it_gave_up_on` |
| GIMLE-325 | gimle-muninn | Traces ingest | Tracing | `MuninnServerTracesIngestTest#an_ingested_span_line_round_trips_with_attributes_intact` |
| GIMLE-326 | gimle-muninn | Traces read | Tracing | `MuninnServerTracesIngestTest` |
| GIMLE-340 | gimle-observability | Default OpenTelemetry tracer installation | Tracing | `GimleTracingTest#install_is_idempotent_and_yields_a_working_tracer` |
| GIMLE-341 | gimle-observability | Configurable, batched span exporter installation | Tracing | `GimleTracingInstallTest#install_swaps_in_the_given_exporter_and_a_real_span_reaches_it` |
| GIMLE-342 | gimle-observability | Bounded-wait tracer flush | Tracing | `GimleTracingInstallTest#flush_forces_the_batch_processor_to_export_before_the_next_periodic_tick`, `#flush_before_any_install_is_a_noop` |
| GIMLE-345 | gimle-observability | One-shot trace-batch and prepared-batch shipping | Tracing | `MuninnShipperTest#ship_trace_batch_is_a_one_shot_post_with_no_periodic_ticking`, `#ship_prepared_batch_posts_the_given_body_verbatim_with_no_periodic_ticking`, `#ship_prepared_batch_is_a_noop_for_an_empty_body` |
| GIMLE-350 | gimle-observability | `MuninnSpanExporter` (OpenTelemetry SDK integration) | Tracing | `MuninnSpanExporterTest#a_real_span_batch_reaches_the_stub_ingest_server_with_the_expected_shape`, `#export_never_throws_even_when_shipping_fails` |
| GIMLE-435 | gimle-console | Operator session login / logout | Web Console / Auth | `src/stores/useAuthStore.test.ts` — "a successful login sets status authenticated and clears any previous error", "login failure surfaces a generic error and leaves status unauthenticated" |
| GIMLE-436 | gimle-console | Session bootstrap & 401 handling | Web Console / Auth | `useAuthStore.test.ts` — "init() only calls session() once even if invoked twice", "handleUnauthorized clears principal and sets status unauthenticated" |
| GIMLE-461 | gimle-fafnir-console | Vault operator login/logout (session-cookie auth) | Web Console / Auth | `src/stores/useAuthStore.test.ts` |
| GIMLE-467 | gimle-andvari-console | Andvari operator login/logout (session-cookie auth) | Web Console / Auth | `src/repositories/__tests__/repositories.test.ts` — "returns an anonymous principal by default", "rejects empty credentials" |
| GIMLE-437 | gimle-console | Cluster Overview dashboard | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-438 | gimle-console | Tactical HUD / Signal display-mode toggle | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-439 | gimle-console | Deployments list/create/detail/delete | Web Console / Frontend | `src/stores/useDeploymentsStore.test.ts`, `src/repositories/http/deployments.test.ts` |
| GIMLE-440 | gimle-console | Jobs (run-to-completion workload) list | Web Console / Frontend | `src/repositories/http/jobs.test.ts` |
| GIMLE-441 | gimle-console | CronJobs list/detail | Web Console / Frontend | `src/repositories/http/cronjobs.test.ts` |
| GIMLE-442 | gimle-console | DaemonSets list/detail | Web Console / Frontend | `src/repositories/http/daemonsets.test.ts` |
| GIMLE-443 | gimle-console | StatefulSets list/detail | Web Console / Frontend | `src/repositories/http/statefulsets.test.ts` |
| GIMLE-444 | gimle-console | Instances table with filtering (global + node/tenant-scoped) | Web Console / Frontend | `src/stores/instances.test.ts`, `src/repositories/http/instances.test.ts` |
| GIMLE-445 | gimle-console | Nodes list/detail with capacity bars and staleness | Web Console / Frontend | `src/repositories/http/nodes.test.ts`, `src/stores/nodes.test.ts` |
| GIMLE-446 | gimle-console | Tenants list/detail with quota management and delete | Web Console / Frontend | `src/repositories/http/tenants.test.ts`, `src/stores/tenants.test.ts` |
| GIMLE-447 | gimle-console | Topology placement map | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-448 | gimle-console | Cluster metrics charts (lifecycle mix, capacity, quota pressure) | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-449 | gimle-console | Per-process metrics history (Muninn-backed) | Web Console / Frontend | `src/repositories/http/metricsHistory.test.ts` |
| GIMLE-450 | gimle-console | Trace span history viewer | Web Console / Frontend | `src/repositories/http/tracesHistory.test.ts` |
| GIMLE-451 | gimle-console | Log explorer with live tailing | Web Console / Frontend | `src/routes/logs.test.ts`, `src/repositories/http/logs.test.ts` |
| GIMLE-452 | gimle-console | Crash-dump (hs_err) listing on Logs screen | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-453 | gimle-console | Config entries management (per-tenant) | Web Console / Frontend | `src/repositories/http/config.test.ts` |
| GIMLE-454 | gimle-console | Secrets management (Fafnir-backed, versioned) | Web Console / Frontend | `src/stores/useSecretsStore.test.ts`, `src/repositories/http/secrets.test.ts` |
| GIMLE-455 | gimle-console | Module artifact registry browser (Andvari-backed) | Web Console / Frontend | `src/stores/useArtifactsStore.test.ts` |
| GIMLE-456 | gimle-console | RBAC access control (roles, role bindings, accounts) | Web Console / Frontend | `src/repositories/http/roles.test.ts`, `roleBindings.test.ts`, `accounts.test.ts` |
| GIMLE-457 | gimle-console | Audit trail viewer with filtering | Web Console / Frontend | `src/stores/useAuditStore.test.ts` |
| GIMLE-458 | gimle-console | Control-plane status panel | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-459 | gimle-console | Theme toggle (light/dark) | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-462 | gimle-fafnir-console | Vault status overview (uptime, active key, transport mode, tenants) | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-463 | gimle-fafnir-console | Secrets browsing/reveal/version/write/destroy (vault-native UI) | Web Console / Frontend | `src/repositories/secrets.test.ts`, `src/repositories/http/secrets.test.ts` |
| GIMLE-464 | gimle-fafnir-console | Tenant filter via URL search param | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-465 | gimle-fafnir-console | Key rotation trigger | Web Console / Frontend | `secrets.test.ts`, `http/secrets.test.ts` |
| GIMLE-466 | gimle-fafnir-console | Fafnir console error banner / global error capture | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-468 | gimle-andvari-console | Registry status overview (uptime, transport, recent pushes) | Web Console / Frontend | `src/stores/artifactsStore.test.ts` (partial) |
| GIMLE-469 | gimle-andvari-console | Artifact catalog browsing & search | Web Console / Frontend | `repositories.test.ts` — "returns a sorted catalog of module ids" |
| GIMLE-470 | gimle-andvari-console | Artifact version detail (download, checksum display, delete) | Web Console / Frontend | `src/stores/artifactsStore.test.ts`, `repositories.test.ts` |
| GIMLE-471 | gimle-andvari-console | Client-side SHA-256 checksum verification on download | Web Console / Frontend | `src/lib/hash.test.ts` |
| GIMLE-472 | gimle-andvari-console | Push artifact dialog (drag-and-drop upload) | Web Console / Frontend | `repositories.test.ts` — "rejects re-pushing an existing version with 409" |
| GIMLE-473 | gimle-andvari-console | Maven-2 repository interop view | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-474 | gimle-andvari-console | Andvari copy-to-clipboard utility | Web Console / Frontend | NONE recorded in the baseline |
| GIMLE-481 | gimle-saga-console | Saga console theming (no auth surface) | Web Console / Frontend | `SagaServerTest.java` — "the_bundled_console_is_served_at_console" |
| GIMLE-585 | gimle-console | ConfigMaps screen | Web Console / Frontend | `repositories/configmaps.test.ts` (Mock repository CRUD, stale-`expectedVersion` conflict, `expectedVersion=0` create case); `repositories/http/configmaps.test.ts` (HTTP repository request shapes, 409 mapped to `ConfigMapConflict`); `stores/useConfigMapsStore.test.ts` (store error surfacing, conflict state distinct from generic error, new-vs-selected `expectedVersion` selection) |
| GIMLE-586 | gimle-console | Service CRUD and live endpoint lookup (Networking screen) | Web Console / Frontend | `src/repositories/services.test.ts`, `src/repositories/http/services.test.ts` |
| GIMLE-587 | gimle-console | NetworkPolicy CRUD (Networking screen) | Web Console / Frontend | `src/repositories/networkPolicies.test.ts`, `src/repositories/http/networkPolicies.test.ts` |
| GIMLE-593 | gimle-console | SecretMaps screen | Web Console / Frontend | `repositories/secretmaps.test.ts` (Mock repository CRUD, per-key independent versioning), `repositories/http/secretmaps.test.ts` (HTTP repository request shapes, base64 encoding), `stores/useSecretMapsStore.test.ts` (store error surfacing, per-key failure reporting distinct from a repository-level rejection). |
| GIMLE-596 | gimle-console | SecretMaps screen History panel | Web Console / Frontend | `repositories/secretmaps.test.ts` (Mock repository group-version stamping and rollback), `repositories/http/secretmaps.test.ts` (HTTP request shapes for both new endpoints), `stores/useSecretMapsStore.test.ts` (`select` loading history, `rollback` refreshing both the SecretMap and its history, repository-level rejection surfaced as `store.error`). |
| GIMLE-475 | gimle-saga-console | Runs list (no authentication) | Web Console / Reporting | `src/repositories/http/runs.test.ts` — "listRuns fetches /api/runs and maps every entry" |
| GIMLE-476 | gimle-saga-console | Live run detail with streaming test feed | Web Console / Reporting | `src/repositories/http/runs.test.ts` — "followRunEvents streams new finished-test events and skips the already-known count" |
| GIMLE-477 | gimle-saga-console | Run attachments: Gherkin scenario tree, Chaos ledger, Surtr phase table | Web Console / Reporting | `src/repositories/http/mapping.test.ts` — "groups attachment events by kind and skips unparseable or unrecognized payloads", "accepts a payload shipped as an array of the shape" |
| GIMLE-478 | gimle-saga-console | Test detail / per-test history | Web Console / Reporting | `src/repositories/http/testHistory.test.ts` |
| GIMLE-479 | gimle-saga-console | Compare two runs (diff view) | Web Console / Reporting | `src/repositories/http/mapping.test.ts`, `runs.test.ts` |
| GIMLE-480 | gimle-saga-console | Gjallarhorn flake scoreboard | Web Console / Reporting | `src/repositories/http/flaky.test.ts` |
| GIMLE-460 | gimle-console | Playwright end-to-end smoke suite against a real cluster | Web Console / Testing | `e2e/greeter-smoke.spec.ts` (opt-in, `bun run test:e2e`, excluded from default Vitest run) |
| GIMLE-086 | gimle-worker | Per-module bounded virtual-thread scheduler | Worker Supervision | `BoundedModuleSchedulerTest#concurrency_bound_limits_how_many_tasks_run_at_once`, `#closed_scheduler_rejects_further_submissions`, `#max_concurrency_below_one_is_rejected`, `#submitted_task_runs_and_returns_its_result`, `#a_thrown_exception_surfaces_through_the_future` |
| GIMLE-102 | gimle-agent | Worker JVM process spawn and command-line construction | Worker Supervision | `AgentMainTest#the_spawned_command_carries_the_manifests_limit_not_its_request`, `#the_spawned_command_always_carries_exit_on_out_of_memory_error`, `#the_spawned_command_always_suppresses_the_startup_banner`, `#the_spawned_command_always_forces_json_console_logging`, `#the_spawned_command_forwards_the_default_deny_cross_tenant_flag`, `#prepare_resource_limit_hands_the_limiter_the_descriptors_limit_not_its_request` |
| GIMLE-104 | gimle-agent | Deliberate-stop suppression of crash-respawn | Worker Supervision | Implicit in `WorkerProcessSupervisorTest` setup/teardown paths; no dedicated `@Test` name asserting this directly — NONE explicit |
| GIMLE-109 | gimle-agent | Assignment reconciliation loop (fetch, start, replace, stop) | Worker Supervision | `AgentMainTest#a_module_id_change_at_the_same_key_requires_replacement`, `#an_artifact_path_change_with_the_same_module_id_requires_replacement`, `#an_unchanged_assignment_at_the_same_key_never_requires_replacement`; `ControlPlaneAgentWorkerIntegrationTest#control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed` |
| GIMLE-110 | gimle-agent | Tier 1 density — shared-worker reuse for multiple module instances | Worker Supervision | `AgentMainTest#a_worker_already_hosting_the_same_module_is_never_reused_for_another_replica`, `#a_worker_at_the_density_cap_is_not_reused`, `#a_worker_with_no_established_connection_yet_is_never_reused`; `Tier1DensityIntegrationTest#two_modules_share_one_worker_process_and_survive_one_being_stopped` |
| GIMLE-111 | gimle-agent | Instance rename-in-place (no restart) | Worker Supervision | `AgentMainTest#find_rename_source_finds_the_already_supervised_instance_at_the_hinted_index`, `#find_rename_source_is_empty_without_a_rename_hint`, `#find_rename_source_falls_back_when_the_hinted_source_key_is_not_supervised`, `#find_rename_source_falls_back_when_the_source_runs_a_different_module`, `#rename_in_place_rekeys_supervised_and_shippers_and_updates_the_assigned_identity`, `#rename_in_place_notifies_the_connected_worker_of_its_new_identity` |
| GIMLE-118 | gimle-agent | Vessel process supervision (plain-jar workload as its own dedicated process) | Worker Supervision | `VesselProcessSupervisorTest#captures_stdout_lines_as_the_instance_application_log`, `#a_crashed_vessel_process_is_respawned`, `#exhausting_the_restart_budget_reports_it_and_stops_respawning` |
| GIMLE-106 | gimle-agent | Machine-level capacity tracking and admission (memory/CPU) | Worker Supervision / Config | `CapacityTrackerTest#try_assign_succeeds_within_capacity_and_is_reflected_in_the_snapshot`, `#try_assign_fails_once_it_would_exceed_total_capacity`, `#try_assign_rejects_a_key_already_holding_a_reservation`, `#release_frees_the_reservation_for_reuse`, `#rekey_moves_the_reservation_to_the_new_key_without_changing_total_usage`, `#rekey_is_a_noop_when_the_old_key_holds_no_reservation` |
| GIMLE-079 | gimle-worker | Worker JVM control-channel bootstrap | Worker Supervision / Internal-Infra | `ControlChannelClientTest#connect_with_retry_succeeds_once_the_listener_is_up`, `#connect_with_retry_gives_up_after_its_timeout_if_nothing_ever_listens`, `AgentWorkerIntegrationTest#agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel` (gimle-agent) |
| GIMLE-101 | gimle-agent | Node agent registration and repeating reconcile/heartbeat/rotate tick loop | Worker Supervision / Internal-Infra | `AgentWorkerIntegrationTest#agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel`, `ControlPlaneAgentWorkerIntegrationTest#control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed` |
| GIMLE-091 | gimle-worker | Stopping/Uninstalled teardown of scheduler, probes, and service registry | Worker Supervision / Module System | `WorkerRuntimeTest#stopping_a_module_makes_its_service_unreachable_and_removes_it_from_the_registry`, `#on_uninstalled_fires_the_close_callback_exactly_once_with_the_registered_identity` |
| GIMLE-114 | gimle-agent | Install-phase Nack escalates to FAILED (closing the "stuck at INSTALLED" gap) | Worker Supervision / Self-Healing | NONE recorded in the baseline |
| GIMLE-601 | gimle-mimir | ControllerRevision history and Deployment/StatefulSet/DaemonSet rollback | Workload Lifecycle | `ControllerRevisionTest`, `StateStoreTest` (append/list/get, retention pruning, snapshot round-trip), `DomainCodecTest`/`RaftCodecTest` (wire round-trip for all three embedded spec kinds), `ApiServerDeploymentRollbackTest`, `ApiServerStatefulSetDaemonSetRollbackTest` -- all real, no mocks (real `StateStore`/`ApiServer`/`HttpClient`). |
