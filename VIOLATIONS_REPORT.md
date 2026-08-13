# Design/plan reference violations -- CLAUDE.md Conventions (v2, full re-review)

Not committed to the repo -- this is itself a "generated report," the category CLAUDE.md's repo-hygiene rule excludes. Supersedes the earlier, keyword-only version of this report: this pass combines the original literal grep for `claudedocs`/`Phase N`/`design doc` with a genuine semantic re-review (six parallel passes, one per module group, judgment-based rather than pattern-matched) that also covers `pom.xml`/`*.xml`/`*.yaml`, which the first pass missed entirely, plus paraphrased citations no fixed keyword list would catch.

**Totals:** 160 files, 348 flagged lines (123 of which only the semantic re-review found -- invisible to the original keyword grep). Plus 10 additional lines flagged as uncertain / needing your own judgment call, listed separately at the end.

**New categories the semantic pass surfaced, beyond the original three keywords:**

- **`P#-#` / `P2-N` internal ticket-IDs** (e.g. `P2-14`, `P1-5`) used as load-bearing citations -- by far the largest new category, concentrated in gimle-fabric, gimle-mimir, gimle-controlplane.
- **Bare `§N` section citations** with no "design doc"/"claudedocs"/"Phase" wording anywhere nearby (e.g. `SecretStore.java`, `ApiServer.java`, `AgentMain.java`) -- reads as citing an external doc's section without ever naming the doc.
- **"roadmap item N"** citations (ApiServer.java, ControlPlaneMojo.java, AutoscaleIT.java, and others).
- **Internal task-tracker IDs** (`Task 20/22`, `task #28`) with no design-doc wording at all.
- **"design doc" split across a line-wrap** -- present in the file but invisible to a single-line grep (ApiServer.java x2, FafnirServerTest.java).
- **Bare design-doc filenames without the `claudedocs/` prefix** (e.g. `log-explorer-design.md §5`).
- **`pom.xml` comments** -- the original pass only scanned `*.java`; pom.xml carries the same citation style throughout (`design doc`, `P2-13`, `task #28`, etc.).

## By module

- **gimle-agent** -- 6 files, 23 lines
- **gimle-cli** -- 5 files, 8 lines
- **gimle-controlplane** -- 27 files, 81 lines
- **gimle-core** -- 16 files, 20 lines
- **gimle-examples** -- 5 files, 5 lines
- **gimle-fabric** -- 13 files, 26 lines
- **gimle-fafnir** -- 11 files, 49 lines
- **gimle-maven-plugin** -- 8 files, 15 lines
- **gimle-mimir** -- 20 files, 47 lines
- **gimle-module** -- 7 files, 7 lines
- **gimle-muninn** -- 5 files, 5 lines
- **gimle-observability** -- 8 files, 11 lines
- **gimle-os** -- 1 files, 1 lines
- **gimle-pki** -- 10 files, 13 lines
- **gimle-smoke-tests** -- 11 files, 26 lines
- **gimle-worker** -- 5 files, 7 lines
- **pom.xml** -- 1 files, 3 lines
- **spotbugs-exclude.xml** -- 1 files, 1 lines

---

## gimle-agent

### `gimle-agent/pom.xml`

- L53 [design doc]: Generates this agent's own CSR at first bootstrap and its rotation CSRs later (design doc
- L60 [design doc]: when -Dgimle.agent.muninnEndpoint is configured (design doc Part B/O-10). The agent's

### `gimle-agent/src/main/java/com/gimle/agent/AgentLogServer.java`

- L100 [design doc]: // design doc this implements.

### `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java`

- L152 [design doc]: // design doc Part B/O-10.
- L160 [design doc]: // One Timer/Counter pair around this agent's own tick body (design doc Part B/O-10) --
- L329 [bare §N] *(semantic re-review)*: // ---- TLS bootstrap (§4) and rotation (§4b) ----
- L349 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §4. Reachable over server-authenticated-only TLS
- L405 [bare §N] *(semantic re-review)*: §6's "did rotation actually happen this tick" signal
- L413 [bare §N] *(semantic re-review)*: Checked once per tick (§4b): if the agent's currently-loaded leaf certificate is due for
- L454 [bare §N] *(semantic re-review)*: Key written *before* cert, deliberately: gimle-worker's FabricServerTlsWatcher (§6.2)
- L640 [design doc]: * secret-versioning entries are filtered out of this endpoint's response server-side (design doc
- L664 [design doc]: * Fetches this tenant's Fafnir-native secrets (design doc §9, §11 Phase C) -- talked to directly,
- L668 [bare §N] *(semantic re-review)*: Soft-deleted secrets are skipped (§7d: a soft-deleted
- L1133 [design doc]: * server-side exactly as before (design doc §11, Phase C: "only where decryption happens

### `gimle-agent/src/test/java/com/gimle/agent/AgentWorkerIntegrationTest.java`

- L24 [design doc]: * The design's mandatory Phase 2 integration test (§9, test plan): a real agent-side {@link
- L63 [Phase N]: // WorkerMain's third argument is tenantId-or-empty (Phase 5 design §5.1); this scenario doesn't

### `gimle-agent/src/test/java/com/gimle/agent/ControlPlaneAgentWorkerIntegrationTest.java`

- L44 [design doc]: * The design's mandatory Phase 3 integration test (§10): a real control plane (state store,
- L134 [paraphrase] *(semantic re-review)*: nodeDarkTimeout must comfortably exceed the agent's own 5s heartbeat cadence (design §11.3
- L145 [design doc]: // design doc); the reconcilers above still read/write it directly, same as always.

### `gimle-agent/src/test/java/com/gimle/agent/FabricCrossProcessIntegrationTest.java`

- L44 [design doc]: * The design's mandatory Phase 4 integration test (§13): two real {@code gimle-agent} subprocesses
- L47 [Phase N]: * ControlPlaneAgentWorkerIntegrationTest} already established for Phase 3), each spawning its own
- L141 [design doc]: // design doc); the reconcilers above still read/write it directly, same as always.
- L223 [bare §N] *(semantic re-review)*: whole lookup-retry-then-call-loop duration (§9's gating-hook semantics

## gimle-cli

### `gimle-cli/pom.xml`

- L37 [design doc]: CertCommand generates its own CSRs locally (design doc §4/§4a/§4b's operator-facing
- L48 [design doc]: ApiServer no longer performs crypto in-process (design doc Phase A): GimleCliTest needs

### `gimle-cli/src/main/java/com/gimle/cli/CertCommand.java`

- L32 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §4/§4a/§4b's join and rotation flows.
- L56 [bare §N] *(semantic re-review)*: per §4b, gimle-cli never renews silently -- it only warns

### `gimle-cli/src/main/java/com/gimle/cli/ControlPlaneClient.java`

- L52 [claudedocs/]: * For the two pre-certificate flows in {@code claudedocs/tls-transport-security-design.md} §4:

### `gimle-cli/src/main/java/com/gimle/cli/SecretCommand.java`

- L16 [design doc]: * surface (design doc §6e/§7), reached through {@code gimle-controlplane}'s proxy to Fafnir, never
- L17 [bare §N] *(semantic re-review)*: Fafnir directly (matching the console's own routing decision, §12).

### `gimle-cli/src/test/java/com/gimle/cli/GimleCliTest.java`

- L65 [design doc]: // design doc), so exercising it now always means standing up at least this much of a store.

## gimle-controlplane

### `gimle-controlplane/pom.xml`

- L67 [design doc]: Real, main-scope dependency: ApiServer signs incoming CSRs at /bootstrap/csr (design doc
- L74 [claudedocs/]: The Raft-replicated store as its own process (claudedocs/etcd-store-extraction-design.md):
- L95 [design doc]: -Dgimle.controlplane.muninnEndpoint is configured, ships them to Muninn (design doc Part

### `gimle-controlplane/src/main/java/com/gimle/controlplane/ControlPlaneMain.java`

- L39 [design doc]: * The control plane's entry point: wires a {@link StoreClient} (etcd-store-extraction design doc --
- L54 [design doc]: * {@code reconciler-leader} lease -- a lease-based election (design decision made when the store
- L166 [design doc]: // Same --muninn-endpoint value doing double duty (design doc Part B/O-10): the MuninnClient
- L180 [design doc]: // Design doc Part B/O-13: a genuine RPC-serving process, unlike gimle-agent (see AgentMain's
- L227 [claudedocs/]: // per claudedocs/tls-transport-security-design.md §4b. No-op in plaintext mode.

### `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java`

- L125 [design doc]: // client is a thin, pure-HTTP caller against it (design doc Phase A), replacing the in-process
- L128 [design doc]: // Nullable, unlike fafnirClient: Muninn's /logs/* fallback (design doc Part B/O-11) is
- L133 [design doc]: // Per-endpoint request/error/latency metrics (design doc Part B/O-10), the same shape
- L145 [roadmap item] *(semantic re-review)*: Per-resource-kind opt-in for auditing READ decisions too (roadmap item 8)
- L159 [P#-#] *(semantic re-review)*: // P2-11: throttles /auth/login by username and by remote address independently
- L170 [bare §N] *(semantic re-review)*: Not final: §4b rotation of this node's own leaf certificate needs to stop and rebuild
- L209 [line-wrap split] *(semantic re-review)*: against the store cluster (etcd-store-extraction design / doc) -- words split across a line break
- L297 [design doc]: * Wraps a handler with request-count/latency/error Micrometer recording (design doc Part B/O-10),
- L321 [design doc]: * {@code --muninn-endpoint} is configured (design doc Part B/O-10), and so a same-package test
- L364 [design doc]: * {@link HttpsServer} instead -- the JDK-bundled, direct drop-in the design doc calls out as the
- L366 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §2). {@code wantClientAuth}, not {@code
- L408 [bare §N] *(semantic re-review)*: §4b rotation's hot-swap point for this node's own leaf certificate
- L434 [bare §N] *(semantic re-review)*: leader-gated -- a follower needs its own cert fresh too), per §4b.
- L467 [design doc]: * already follows the store's current leader internally (etcd-store-extraction design doc
- L491 [claudedocs/]: // a known, deliberate gap, not a silent omission (claudedocs/authn-authz-design.md
- L532 [P#-#] *(semantic re-review)*: // P2-18: computed here, once, regardless of tenancy
- L676 [roadmap item] *(semantic re-review)*: Serializes an AutoscalePolicy onto the wire (roadmap item 9
- L833 [line-wrap split] *(semantic re-review)*: follows the leader internally (etcd-store-extraction design / doc §4.4/§4.6) -- words split across a line break
- L1344 [design doc]: * owns -- {@code <key>@meta} or {@code <key>@N} (design doc §7a) -- rather than one written
- L1659 [design doc]: * process's own authorization check, not yet Fafnir's own independent one (design doc §9's
- L1683 [design doc]: // ---- /secrets/{tenantId}/... (design doc §6e/§7) -- a byte-for-byte proxy to Fafnir ----
- L1689 [bare §N] *(semantic re-review)*: this endpoint's body/response shape is Fafnir's own evolving API (§6e)
- L1856 [claudedocs/]: * claudedocs/authn-authz-design.md} §6a), and {@code /auth/logout} only ever clears whatever
- L1984 [bare doc name] *(semantic re-review)*: no write/consensus involved, so §5's leader-redirect handling doesn't apply here (matches log-explorer-design.md §6).
- L2025 [design doc]: * {@code GET /metrics-history/{processKind}/{processId}} (design doc Part B/O-10) -- a thin,
- L2028 [design doc]: * surface uses (design doc §5c: "these are all the same shape of thing" -- no dedicated {@code
- L2030 [design doc]: * scope-narrowing, not an oversight -- see the design doc's own O-10 note). Unlike {@code
- L2070 [design doc]: * {@code GET /traces-history/{processKind}/{processId}} (design doc Part B/O-13) -- structurally
- L2156 [design doc]: // Muninn only ever ingested the plain PLATFORM/APPLICATION shape (design doc §5c) -- a
- L2187 [design doc]: * (design doc §5c), translated from this surface's own query-parameter convention (matching
- L2254 [design doc]: * falling back to Muninn (design doc Part B/O-11) whenever a live agent genuinely isn't
- L2433 [bare §N] *(semantic re-review)*: this is the one endpoint that by design must be reachable without a client certificate (§4)
- L2513 [claudedocs/]: // Server-stamped O=, never the CSR's own -- claudedocs/authn-authz-design.md §2a: a
- L2589 [bare §N] *(semantic re-review)*: §4a: handleBootstrapCsrSubResource's /approve branch already requires
- L2685 [claudedocs/]: * claudedocs/authn-authz-design.md} §7), else resolves a {@link Principal} from either a verified
- L2870 [design doc]: * simplification of the client contract, not a lesser response, per the design doc's own framing.

### `gimle-controlplane/src/main/java/com/gimle/controlplane/authz/BootstrapAccountFile.java`

- L24 [claudedocs/]: * property may deliberately stay set across restarts (see {@code claudedocs/authn-authz-design.md}

### `gimle-controlplane/src/main/java/com/gimle/controlplane/fafnir/FafnirClient.java`

- L22 [design doc]: * {@code gimle-controlplane}'s HTTP calling logic for Fafnir's internal crypto surface (design doc
- L27 [design doc]: * the eventual {@code /secrets/*} proxy to Fafnir uses (design doc §6d), just with typed request/
- L81 [design doc]: * A byte-for-byte proxy hop for the versioned {@code /secrets/*} surface (design doc §6e) --

### `gimle-controlplane/src/main/java/com/gimle/controlplane/muninn/MuninnClient.java`

- L17 [design doc]: * (design doc Part B/O-11) -- a thin, purpose-built client mirroring {@code FafnirClient}'s own

### `gimle-controlplane/src/main/java/com/gimle/controlplane/pki/BootstrapTokenRegistry.java`

- L13 [claudedocs/]: * claudedocs/tls-transport-security-design.md}: a plain random secret an operator hands to a new

### `gimle-controlplane/src/main/java/com/gimle/controlplane/pki/CaKeyMaterial.java`

- L15 [claudedocs/]: * /bootstrap/csr}, per {@code claudedocs/tls-transport-security-design.md} §4. Read from a new,

### `gimle-controlplane/src/main/java/com/gimle/controlplane/pki/PendingCsrStore.java`

- L13 [claudedocs/]: * per {@code claudedocs/tls-transport-security-design.md} §4a. In-memory only, same reasoning as

### `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/DeploymentReconciler.java`

- L153 [P#-#] *(semantic re-review)*: // P2-18: a spec admitted with a recorded hash

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerAuthzTest.java`

- L53 [claudedocs/]: * The RBAC behaviors that only exist at the real HTTP/mTLS layer -- {@code claudedocs/
- L87 [claudedocs/]: * The specific privilege-escalation regression {@code claudedocs/authn-authz-design.md} §2a
- L282 [P#-#] *(semantic re-review)*: P2-11: repeated failed logins against the same username eventually get throttled
- L497 [roadmap item] *(semantic re-review)*: Roadmap item 8: gimle.controlplane.audit.readResourceKinds opts a resource kind into
- L599 [P#-#] *(semantic re-review)*: P2-16 end to end: a secret written before rotation stays readable afterward

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerConsoleContractTest.java`

- L27 [design doc]: * Design doc §12: scoped field-presence assertions against the exact JSON shapes {@code

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerLogsFallbackTest.java`

- L36 [design doc]: * {@code ApiServer}'s {@code /logs/*} Muninn fallback (design doc Part B/O-11): a gone node or

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerMetricsHistoryTest.java`

- L52 [design doc]: * {@code GET /metrics-history/{processKind}/{processId}} (design doc Part B/O-10): a thin, {@code

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerMetricsTest.java`

- L24 [design doc]: * {@link ApiServer}'s per-endpoint request/error/latency metrics (design doc Part B/O-10),

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerRaftTest.java`

- L43 [design doc]: * -- the decoupled N:M topology the etcd-store-extraction design doc exists for. A write through
- L45 [design doc]: * Raft leadership: {@code StoreClient} follows the leader internally (design doc §4.4/§4.6), so

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerTest.java`

- L147 [roadmap item] *(semantic re-review)*: // Roadmap item 9: the console reads the deployment JSON this test asserts on
- L604 [Phase N]: // ---- tenants (Phase 5 design §5.1) ----
- L651 [Phase N]: // ---- tenant quota admission (Phase 5 design §5.2) ----
- L743 [Phase N]: // ---- config/secrets distribution (Phase 5 design §6) ----
- L807 [design doc]: // ---- /secrets/{tenantId}/... proxy to Fafnir (design doc §6e) ----

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerTlsTest.java`

- L41 [design doc]: * itself (design doc §4).

### `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerTracesHistoryTest.java`

- L52 [design doc]: * {@code GET /traces-history/{processKind}/{processId}} (design doc Part B/O-13): structurally

### `gimle-controlplane/src/test/java/com/gimle/controlplane/autoscale/AutoscaleReconcilerTest.java`

- L28 [Phase N]: * Phase 4 §10's autoscaling formula: average observed CPU utilization against the module's own

### `gimle-controlplane/src/test/java/com/gimle/controlplane/pki/CertificateRotationTest.java`

- L42 [bare §N] *(semantic re-review)*: §4b's rotation flow: a component holding a still-valid certificate requests rotation

### `gimle-controlplane/src/test/java/com/gimle/controlplane/pki/HumanOperatorCsrTest.java`

- L40 [bare §N] *(semantic re-review)*: §4a's human-operator flow: an OPERATOR_CLIENT CSR sits PENDING

### `gimle-controlplane/src/test/java/com/gimle/controlplane/pki/NodeBootstrapCsrTest.java`

- L41 [bare §N] *(semantic re-review)*: §4's node-join flow end to end: a brand-new agent with no pre-provisioned certificate

### `gimle-controlplane/src/test/java/com/gimle/controlplane/reconcile/DeploymentReconcilerRollingUpdateTest.java`

- L29 [Phase N]: * Phase 4 §9's rolling-update behavior, layered onto {@link DeploymentReconciler}'s existing
- L198 [Phase N]: * QA Phase 3 finding: a real node agent that has fetched a rolled-forward assignment but hasn't

### `gimle-controlplane/src/test/java/com/gimle/controlplane/reconcile/QuotaReconcilerTest.java`

- L25 [Phase N]: * / 10m cpu (Phase 5 design §5.2, §8's required convergence-from-arbitrary-state coverage).

### `gimle-controlplane/src/test/java/com/gimle/controlplane/schedule/SchedulerTest.java`

- L132 [Phase N]: // ---- tenant node-level isolation, Phase 5 design §5.4 ----
- L196 [paraphrase] *(semantic re-review)*: // codebase (design §5.4's own correction) -- node-level exclusion is deliberately a no-op

### `gimle-controlplane/src/test/java/com/gimle/controlplane/testsupport/InProcessFafnir.java`

- L15 [design doc]: * ApiServer} itself no longer does any crypto in-process (design doc Phase A) -- a genuine HTTP

## gimle-core

### `gimle-core/pom.xml`

- L21 [bare doc name] *(semantic re-review)*: (log-explorer-design.md §4/§5) hand-rolls a JSON Encoder and two file Appenders directly

### `gimle-core/src/main/java/com/gimle/core/authz/Account.java`

- L7 [claudedocs/]: * claudedocs/authn-authz-design.md} §6) has no certificate to prove itself with. {@code

### `gimle-core/src/main/java/com/gimle/core/exception/GimleRaftException.java`

- L43 [design doc]: * extraction design doc §4.4/§4.6) exhausted every configured store endpoint -- including one

### `gimle-core/src/main/java/com/gimle/core/exception/GimleSecretsException.java`

- L41 [design doc]: * {@code gimle-fafnir}'s own versioned write path (design doc §7b) lost the optimistic

### `gimle-core/src/main/java/com/gimle/core/logging/LogFileReader.java`

- L24 [bare doc name] *(semantic re-review)*: fails to parse as JSON (raw SYSTEM capture, per log-explorer-design.md §5) is

### `gimle-core/src/main/java/com/gimle/core/module/HealthProbes.java`

- L10 [P#-#] *(semantic re-review)*: initialDelay (P2-4) is the manifest's health.initialDelaySeconds
- L12 [P#-#] *(semantic re-review)*: probe's own intervalSeconds. Absent means the pre-P2-4 behavior

### `gimle-core/src/main/java/com/gimle/core/protocol/AuditEvent.java`

- L8 [paraphrase] *(semantic re-review)*: InstanceEvent's own javadoc pointed at as "the general audit-logging item still on the roadmap."

### `gimle-core/src/main/java/com/gimle/core/protocol/CsrRequestStatus.java`

- L5 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §4a. {@link CsrPurpose#NODE_CLIENT} requests and
- L9 [paraphrase] *(semantic re-review)*: no rejected state -- the design has no reject action, only "sits pending until approved".

### `gimle-core/src/main/java/com/gimle/core/protocol/CsrResult.java`

- L7 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §4/§4a/§4b: returned synchronously by {@code POST

### `gimle-core/src/main/java/com/gimle/core/protocol/CsrSubmission.java`

- L7 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §4/§4a/§4b: a PEM-encoded PKCS#10 CSR, what it's

### `gimle-core/src/main/java/com/gimle/core/session/SessionTokens.java`

- L22 [claudedocs/]: * Stateless, HMAC-SHA256-signed console session tokens -- {@code claudedocs/authn-authz-design.md}

### `gimle-core/src/main/java/com/gimle/core/throttle/LoginThrottle.java`

- L11 [P#-#] *(semantic re-review)*: choosing (P2-11). gimle-controlplane's ApiServer tracks one key per username and

### `gimle-core/src/main/java/com/gimle/core/tls/SslContexts.java`

- L27 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §3's own point that only issuance was ever the JDK's
- L59 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §4/§4a that genuinely have no certificate yet: an
- L143 [paraphrase] *(semantic re-review)*: The design's leaf certificates are always RSA-signed (SHA256withRSA, see gimle-pki's

### `gimle-core/src/main/java/com/gimle/core/tls/TlsSettings.java`

- L9 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §1: its own leaf certificate and private key (both

### `gimle-core/src/main/java/com/gimle/core/tls/TransportProtocol.java`

- L5 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §1): a single switch for every network-exposed

### `gimle-core/src/test/java/com/gimle/core/module/ServiceExportTenantTest.java`

- L10 [Phase N]: /** {@code allowedTenantIds}/{@code permitsTenant} (Phase 5 design §5.3). */

## gimle-examples

### `gimle-examples/greeter-consumer/pom.xml`

- L32 [claudedocs/]: entry and claudedocs/docs-site-design.md). -->

### `gimle-examples/greeter-load-generator/pom.xml`

- L33 [claudedocs/]: entry and claudedocs/docs-site-design.md). -->

### `gimle-examples/greeter-provider/pom.xml`

- L35 [claudedocs/]: entry and claudedocs/docs-site-design.md). -->

### `gimle-examples/greeter-provider/src/main/java/com/gimle/examples/greeter/provider/GreeterProviderHooks.java`

- L30 [design doc]: // Exercises the real config/secrets delivery path end to end (design doc §11 Phase C): the

### `gimle-examples/hello-module/pom.xml`

- L32 [claudedocs/]: entry and claudedocs/docs-site-design.md). -->

## gimle-fabric

### `gimle-fabric/pom.xml`

- L60 [internal task ID] *(semantic re-review)*: (task #28), the same reason gimle-controlplane's pom.xml carries this.

### `gimle-fabric/src/main/java/com/gimle/fabric/cluster/DtlsPeerSession.java`

- L16 [claudedocs/]: * SSLSocket}, per {@code claudedocs/tls-transport-security-design.md} §2). All access is {@code

### `gimle-fabric/src/main/java/com/gimle/fabric/cluster/GossipMember.java`

- L54 [claudedocs/]: * {@code claudedocs/tls-transport-security-design.md} §2), driven per-peer through {@link
- L98 [P#-#] *(semantic re-review)*: Clamp on localHealthMultiplier (P2-9, a simplified Lifeguard-style local-health
- L298 [P#-#] *(semantic re-review)*: Periodic full-state push-pull (P2-8): fires roughly every
- L946 [bare §N] *(semantic re-review)*: §6 rotation hot-swap: rebuilds the DTLS SSLContext from whatever certificate material

### `gimle-fabric/src/main/java/com/gimle/fabric/cluster/SwimMessage.java`

- L55 [P#-#] *(semantic re-review)*: Periodic anti-entropy push (P2-8): unlike every other message type here, piggyback

### `gimle-fabric/src/main/java/com/gimle/fabric/registry/FabricServiceRegistry.java`

- L62 [P#-#] *(semantic re-review)*: Default panic-mode ejection floor (P2-7): once more than this fraction of a lookup's own
- L148 [P#-#] *(semantic re-review)*: maxEjectionPercent (P2-7) is the panic-mode floor described on
- L183 [P#-#] *(semantic re-review)*: defaultDenyCrossTenant (P2-17) flips ServiceExport#permitsTenant's own

### `gimle-fabric/src/main/java/com/gimle/fabric/trace/TraceContext.java`

- L13 [P#-#] *(semantic re-review)*: tracestate/baggage (P2-10) are the W3C tracestate and baggage
- L34 [P#-#] *(semantic re-review)*: Back-compat: defaults tracestate and baggage to empty (added by P2-10).

### `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricCodec.java`

- L32 [P#-#] *(semantic re-review)*: Bumped 1 -> 2 by P2-10's tracestate/baggage additions to TraceContext.

### `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricServer.java`

- L54 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §1's own table), another bound to a TCP {@link
- L165 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §6.2. No-op in plaintext mode.
- L391 [P#-#] *(semantic re-review)*: baggage (P2-10), so invokeLocally's handler sees both Span#current() and

### `gimle-fabric/src/test/java/com/gimle/fabric/cluster/GossipMemberDtlsTest.java`

- L33 [claudedocs/]: * compiles" -- per {@code claudedocs/tls-transport-security-design.md} §2's gossip row. Uses fresh
- L151 [bare §N] *(semantic re-review)*: // exactly what §4b's own rotation does -- then reload.
- L175 [bare §N] *(semantic re-review)*: the specific asymmetry §6.1

### `gimle-fabric/src/test/java/com/gimle/fabric/cluster/GossipMemberTest.java`

- L182 [P#-#] *(semantic re-review)*: // P2-9: being suspected is a local-health signal too
- L204 [P#-#] *(semantic re-review)*: // Round-robin coverage (P2-9): a pure-random pick gives no such guarantee

### `gimle-fabric/src/test/java/com/gimle/fabric/registry/FabricServiceRegistryTest.java`

- L299 [P#-#] *(semantic re-review)*: Without the P2-7 panic floor, once all three breakers open, lookup() would
- L336 [Phase N]: // ---- tenant permission filtering, Phase 5 design §5.3 ----
- L465 [P#-#] *(semantic re-review)*: // ---- P2-17: defaultDenyCrossTenant flips an unscoped export's default

### `gimle-fabric/src/test/java/com/gimle/fabric/transport/FabricServerTest.java`

- L96 [P#-#] *(semantic re-review)*: // P2-10: baggage isn't just decoded and discarded

### `gimle-fabric/src/test/java/com/gimle/fabric/transport/FabricTransportTlsTest.java`

- L35 [claudedocs/]: * plaintext regardless, per {@code claudedocs/tls-transport-security-design.md} §1's own table

## gimle-fafnir

### `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirCrypto.java`

- L14 [paraphrase] *(semantic re-review)*: the only object in the whole process (and, per the design this module implements, ...)

### `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirMain.java`

- L102 [design doc]: // (design doc Part B/O-10) -- null means "ship nowhere," this replica's own request metrics
- L115 [design doc]: // Design doc Part B/O-13: a genuine RPC-serving process, unlike gimle-agent (see AgentMain's

### `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirServer.java`

- L69 [design doc]: * public, versioned {@code /secrets/{tenantId}/...} surface (design doc §6e/§7), proxied to by
- L70 [bare §N] *(semantic re-review)*: authorized independently here (§9's corrected defense-in-depth).
- L75 [design doc]: // A dedicated logger, not `log` -- design doc §9's Observability subsection: every /secrets/*
- L195 [design doc]: * Wraps a handler with request-count/latency/error Micrometer recording (design doc §9's
- L228 [design doc]: * -Dgimle.fafnir.muninnEndpoint} is configured (design doc Part B/O-10) -- the same shape {@code
- L308 [bare §N] *(semantic re-review)*: requireAuthorized check; §9's independent Fafnir-side re-check is wired for the versioned
- L328 [design doc]: // ---- /secrets/{tenantId}, /secrets/{tenantId}/{key}[/versions] (design doc §6e) ----
- L468 [design doc]: * Fafnir's own, independent authorization decision (design doc §9's corrected defense-in-depth):
- L473 [bare §N] *(semantic re-review)*: every other Gimle process (§9): no TLS means no identity to check in the first place
- L478 [bare §N] *(semantic re-review)*: so this is where §9's rate limiting
- L479 [bare §N] *(semantic re-review)*: keyed by principal, incrementing on a denial) and the audit log entry (§9's
- L546 [bare §N] *(semantic re-review)*: §9's node-authorization mode, mirroring Kubernetes' own Node authorization + NodeRestriction
- L560 [bare §N] *(semantic re-review)*: Honest limitation, stated rather than hidden (§9's own caveat)
- L579 [design doc]: * through the proxy at all (a node agent's own direct fetch, design doc §9's third subsection, or

### `gimle-fafnir/src/main/java/com/gimle/fafnir/SecretMetadata.java`

- L4 [design doc]: * One row of {@code GET /secrets/{tenantId}}'s list response (design doc §6e) -- metadata only,
- L6 [bare §N] *(semantic re-review)*: being distinct from get. Derived from a secret's key@meta pointer entry (§7a);

### `gimle-fafnir/src/main/java/com/gimle/fafnir/SecretStore.java`

- L20 [design doc]: * Fafnir's versioned secret storage -- the synthetic-key convention from design doc §7. {@code
- L25 [bare §N] *(semantic re-review)*: store schema change, just a key-naming convention only this class ever interprets (§7's own
- L32 [bare §N] *(semantic re-review)*: // Bounds §7b's optimistic write-verify-retry loop
- L34 [design doc]: // shouldn't spin forever. Generous on purpose: the design doc's own framing is "a human/CLI-
- L39 [bare §N] *(semantic re-review)*: // javadoc for why a lease is needed there at all, despite §7b's explicit "no lock" framing.
- L50 [bare §N] *(semantic re-review)*: §6e's list endpoint: every logical secret's metadata for tenantId, never a value.
- L64 [bare §N] *(semantic re-review)*: §7c: key@1 .. key@latestVersion -- every version always exists once claimed.
- L76 [bare §N] *(semantic re-review)*: §7c's read path: an explicit version reads key@N directly, bypassing
- L104 [bare §N] *(semantic re-review)*: §7b's write path: optimistic insert, not a lock
- L105 [design doc]: * entry (steps 1-2) is fully lock-free, exactly as the design doc specifies: two writers racing
- L110 [design doc]: * <p><b>Correction to the design doc's literal §7b sequence</b>, found empirically (a concurrent-
- L115 [bare §N] *(semantic re-review)*: own "after" read (a classic TOCTOU window). §7b's own
- L121 [design doc]: * around the whole operation the design doc correctly argued against.
- L163 [bare §N] *(semantic re-review)*: §7d soft delete: every @N entry stays on disk, recoverable by a future undelete.
- L174 [bare §N] *(semantic re-review)*: §7d hard delete (?destroy=true): removes @meta and every @N.
- L215 [bare §N] *(semantic re-review)*: hierarchical path segment (§7a); @ because it's this scheme's own reserved separator

### `gimle-fafnir/src/test/java/com/gimle/fafnir/FafnirObservabilityTest.java`

- L43 [design doc]: /** Design doc §9's rate limiting and Micrometer request metrics for {@code gimle-fafnir}. */
- L193 [design doc]: // Structured-log-based audit trail (design doc §9's Observability subsection) -- verified

### `gimle-fafnir/src/test/java/com/gimle/fafnir/FafnirSecretsAuthzTest.java`

- L46 [design doc]: * Design doc §9's corrected defense-in-depth: Fafnir runs its own, independent {@code
- L175 [design doc]: // is not what's actually protecting this endpoint (design doc §9's corrected
- L491 [bare §N] *(semantic re-review)*: exercises §9's node-authorization path

### `gimle-fafnir/src/test/java/com/gimle/fafnir/FafnirServerTest.java`

- L25 [line-wrap split] *(semantic re-review)*: gimle-controlplane's own FafnirClient calls, per the design doc's Phase A scope (words split across a line break)
- L26 [design doc]: * rotate-key surface {@code gimle-controlplane}'s own {@code FafnirClient} calls, per the design
- L173 [design doc]: // ---- /secrets/{tenantId}/... (design doc §6e/§7) ----

### `gimle-fafnir/src/test/java/com/gimle/fafnir/SecretStoreTest.java`

- L28 [design doc]: * Design doc §7's synthetic-key versioning scheme, exercised directly against a real store. {@code
- L99 [bare §N] *(semantic re-review)*: enforces §6e's "list vs get" distinction.
- L141 [bare §N] *(semantic re-review)*: proves §7a's "@N holds ciphertext" claim, not just
- L191 [bare §N] *(semantic re-review)*: // Every writer claimed a distinct version number -- §7b's optimistic write-verify-retry

### `gimle-fafnir/src/test/java/com/gimle/fafnir/secret/KeyFileManagerTest.java`

- L16 [Phase N]: /** Platform-generated local key file (Phase 5 design §6.1). */

### `gimle-fafnir/src/test/java/com/gimle/fafnir/secret/SecretCipherTest.java`

- L14 [Phase N]: /** AES-256-GCM round-trip (Phase 5 design §6.2). */

## gimle-maven-plugin

### `gimle-maven-plugin/pom.xml`

- L30 [claudedocs/]: pluginManagement entry and claudedocs/docs-site-design.md). -->

### `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/AgentMojo.java`

- L45 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §1 -- same shape as {@code

### `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/BootstrapMojo.java`

- L57 [design doc]: * its audit story (design doc §6f), unlike the store's own Raft/client RPC transports. A real
- L280 [design doc]: // Optional (design doc Part B/O-10) -- see AgentMain's own javadoc on
- L307 [design doc]: // Optional (design doc Part B/O-10) -- see AgentMain's own javadoc on
- L373 [design doc]: // Optional (design doc Part B/O-11) -- lets this replica's /logs/* proxy fall back to
- L398 [design doc]: // Lets this agent fetch secret values straight from Fafnir (design doc §9/§11 Phase C)
- L404 [design doc]: // (design doc Part B) -- see AgentMain's own javadoc on gimle.agent.muninnEndpoint.

### `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/ControlPlaneMojo.java`

- L13 [design doc]: * cluster over the network rather than embedding one (etcd-store-extraction design doc) -- {@code
- L44 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §1 -- unset by default (plaintext, matching {@code
- L53 [roadmap item] *(semantic re-review)*: READ-decision audit-trail coverage (roadmap item 8)

### `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/DocsMojo.java`

- L32 [claudedocs/]: * <p>See {@code claudedocs/docs-site-design.md} and {@code gimle-docs/pom.xml}'s own description

### `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/MuninnMojo.java`

- L13 [design doc]: * process (design doc Part B), talking to a {@code gimle-mimir} store cluster over the network for

### `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/StoreMojo.java`

- L13 [design doc]: * (etcd-store-extraction design doc), what {@code mvn gimle:controlplane} used to embed directly

### `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/TlsInitMojo.java`

- L13 [claudedocs/]: * subprocess, per {@code claudedocs/tls-transport-security-design.md} §3/§4a. Unlike {@code

## gimle-mimir

### `gimle-mimir/pom.xml`

- L21 [claudedocs/]: machinery in-process. See claudedocs/etcd-store-extraction-design.md.
- L42 [design doc]: today, and by StoreMain's own certificate-rotation ticker once it exists (design doc §4.7:
- L59 [design doc]: -Dgimle.store.muninnEndpoint is configured (design doc Part B/O-10). -->

### `gimle-mimir/src/main/java/com/gimle/mimir/StoreMain.java`

- L39 [design doc]: * The store process's entry point (etcd-store-extraction design doc): wires the Raft-replicated
- L89 [design doc]: // (design doc Part B/O-10) -- null means "ship nowhere," this replica's own request metrics
- L101 [P#-#] *(semantic re-review)*: Bootstrap configuration only, per P1-5's etcd-style membership change: peers is where a
- L133 [design doc]: // Per-RPC-kind request/error/latency metrics (design doc Part B/O-10), wrapping the handler
- L163 [design doc]: // Design doc Part B/O-13: a genuine RPC-serving process, unlike gimle-agent (see AgentMain's

### `gimle-mimir/src/main/java/com/gimle/mimir/codec/DomainCodec.java`

- L49 [design doc]: * etcd-store-extraction design doc §4.3) -- this is data encoding, not networking, and the DRY case
- L148 [roadmap item] *(semantic re-review)*: combinationMode + the four per-signal weights (roadmap item 10)
- L372 [design doc]: // etcd-store-extraction design doc call out as leader-only but non-replicated, same as today.

### `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentManifestParser.java`

- L138 [P#-#] *(semantic re-review)*: artifactSha256 (P2-18) is never trusted from an operator-submitted manifest

### `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentSpec.java`

- L26 [P#-#] *(semantic re-review)*: artifactSha256 (P2-18) is the SHA-256 ApiServer computed from the artifact at

### `gimle-mimir/src/main/java/com/gimle/mimir/raft/MembershipChange.java`

- L13 [paraphrase] *(semantic re-review)*: C_old,new overlap state, which the production-hardening backlog explicitly scopes out of this

### `gimle-mimir/src/main/java/com/gimle/mimir/raft/RaftTransport.java`

- L29 [claudedocs/]: * per {@code claudedocs/tls-transport-security-design.md} §2.
- L72 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §6.2. No-op in plaintext mode.

### `gimle-mimir/src/main/java/com/gimle/mimir/rpc/StoreClient.java`

- L36 [design doc]: * design doc, replacing the module's former in-process {@code StateStore}/{@code RaftNode} fields).
- L42 [design doc]: * <p>Reads (design doc §4.5) go to any configured endpoint, rotating on transport failure -- no
- L90 [P#-#] *(semantic re-review)*: (P1-5). Leader-only, same redirect-and-retry posture as propose: a rejection for any
- L108 [P#-#] *(semantic re-review)*: Leader-routed, unlike every other read below (P2-14): node heartbeats are deliberately never

### `gimle-mimir/src/main/java/com/gimle/mimir/rpc/StoreCodec.java`

- L32 [design doc]: * (design doc §4.3, deferred), but both delegate domain-type (de)serialization to {@link

### `gimle-mimir/src/main/java/com/gimle/mimir/rpc/StoreConnection.java`

- L19 [design doc]: * is (design doc §4.3). {@code StoreClient} (not this class) owns the pool of endpoints and the

### `gimle-mimir/src/main/java/com/gimle/mimir/rpc/StoreNode.java`

- L15 [design doc]: * may answer -- design doc §4.5) or, for {@link StoreRpc.Propose}/{@link
- L25 [P#-#] *(semantic re-review)*: routes it the same leader-only way as everything else that's leader-local state (P2-14). Unlike
- L130 [P#-#] *(semantic re-review)*: Leader-only, per this class's own javadoc (P2-14): a follower's local heartbeat map is never

### `gimle-mimir/src/main/java/com/gimle/mimir/rpc/StoreRpc.java`

- L23 [claudedocs/]: * etcd-store-extraction (see {@code claudedocs/etcd-store-extraction-design.md}). One request/
- L32 [P#-#] *(semantic re-review)*: specifically; GetNodeHeartbeat is a leader-only *read* for a different reason (P2-14) --
- L36 [design doc]: * (design doc §4.5 -- reads stay exactly as loose as today, no linearizability requirement). Every
- L39 [design doc]: * silently forwarding (design doc §4.6): {@code StoreClient} follows the returned leader address
- L114 [design doc]: * cluster's Raft membership -- etcd-style, one server at a time (design doc: production-hardening
- L126 [P#-#] *(semantic re-review)*: The one leader-only *read* in this group (P2-14): node heartbeats are deliberately never
- L202 [design doc]: * 307-redirect path handled an absent {@code leaderHint()} (design doc §4.6).

### `gimle-mimir/src/main/java/com/gimle/mimir/rpc/StoreTransport.java`

- L23 [design doc]: * FabricServer}), deliberately a separate copy rather than a shared base class (design doc §4.3:

### `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java`

- L268 [P#-#] *(semantic re-review)*: GetNodeHeartbeat through the current leader specifically for this reason (P2-14) -- a
- L293 [claudedocs/]: * (claudedocs/etcd-store-extraction-design.md's lease-based-election resolution) -- the same

### `gimle-mimir/src/main/java/com/gimle/mimir/store/StoreReader.java`

- L69 [P#-#] *(semantic re-review)*: specifically, unlike every other read here (P2-14) -- see StateStore.putNodeHeartbeat's

### `gimle-mimir/src/test/java/com/gimle/mimir/manifest/AutoscalePolicyTest.java`

- L11 [roadmap item] *(semantic re-review)*: CombinationMode/per-signal-weight validation and defaulting (roadmap item

### `gimle-mimir/src/test/java/com/gimle/mimir/raft/RaftClusterTest.java`

- L37 [P#-#] *(semantic re-review)*: plan P1-5), so any node, once elected leader, can call RaftNode#addServer
- L232 [P#-#] *(semantic re-review)*: matching P1-5's etcd-style one-at-a-time membership change. Deliberately never calls
- L419 [P#-#] *(semantic re-review)*: // ---- P1-5: etcd-style live membership change ----

### `gimle-mimir/src/test/java/com/gimle/mimir/raft/RaftClusterTlsTest.java`

- L45 [design doc]: * mechanics -- design doc §5 verification item 2. Every simulated node shares one CA-issued

### `gimle-mimir/src/test/java/com/gimle/mimir/rpc/StoreClientClusterTest.java`

- L46 [paraphrase] *(semantic re-review)*: The load-bearing checkpoint for the etcd-store-extraction design's step 7
- L180 [design doc]: * round-robin across every node (design doc §4.5, no linearizability requirement), so a follow-up
- L270 [P#-#] *(semantic re-review)*: // Heartbeats are deliberately leader-local, never replicated (P2-14)

### `gimle-mimir/src/test/java/com/gimle/mimir/rpc/StoreNodeTest.java`

- L40 [paraphrase] *(semantic re-review)*: separately by StoreClient's own integration test (design plan step 7)
- L161 [P#-#] *(semantic re-review)*: // GetNodeHeartbeat is the one leader-only *read* in this group (P2-14)
- L287 [P#-#] *(semantic re-review)*: // ---- AddServer: etcd-style membership change (P1-5) ----

## gimle-module

### `gimle-module/src/main/java/com/gimle/module/descriptor/ModuleDescriptorParser.java`

- L178 [P#-#] *(semantic re-review)*: initialDelaySeconds (P2-4): how long after ACTIVE before the first probe tick

### `gimle-module/src/main/java/com/gimle/module/lifecycle/SimpleServiceRegistry.java`

- L92 [P#-#] *(semantic re-review)*: Hot redeploy (P2-5) can leave both the old and new version of a module registered under the

### `gimle-module/src/test/java/com/gimle/module/descriptor/ModuleDescriptorParserTest.java`

- L16 [P#-#] *(semantic re-review)*: Covers health.initialDelaySeconds (P2-4); other fields are exercised end to end

### `gimle-module/src/test/java/com/gimle/module/integration/HotRedeployTest.java`

- L21 [paraphrase] *(semantic re-review)*: Hot redeploy is deliberately not a distinct state-machine path (see the design notes)

### `gimle-module/src/test/java/com/gimle/module/leak/RedeployLoopFlatMetaspaceTest.java`

- L26 [Phase N]: * The mandatory Phase 1 acceptance test: redeploy-in-a-loop with flat metaspace. Runs {@link

### `gimle-module/src/test/java/com/gimle/module/lifecycle/ModuleControllerTest.java`

- L192 [P#-#] *(semantic re-review)*: // FAILED (P2-19) has no in-worker retry path

### `gimle-module/src/test/java/com/gimle/module/lifecycle/SimpleServiceRegistryTest.java`

- L154 [P#-#] *(semantic re-review)*: // P2-5: a hot redeploy leaves both the old and new version registered under the same

## gimle-muninn

### `gimle-muninn/src/main/java/com/gimle/muninn/MuninnDayFileStore.java`

- L46 [design doc]: * {@code subtreePath} segments are built from a {@code processId} (design doc Part B/O-9/O-11),

### `gimle-muninn/src/main/java/com/gimle/muninn/MuninnServer.java`

- L525 [design doc]: * Metrics ingest (design doc Part B/O-9/O-12, and traces in O-13) comes from every process kind

### `gimle-muninn/src/test/java/com/gimle/muninn/MuninnDayFileStoreTest.java`

- L104 [design doc]: // A processId is a host:port string for every process kind except AGENT (design doc Part

### `gimle-muninn/src/test/java/com/gimle/muninn/MuninnServerMetricsIngestTest.java`

- L26 [design doc]: * exercising the {@code /ingest/metrics/*} and {@code /metrics/*} routes (design doc Part B/O-9) --

### `gimle-muninn/src/test/java/com/gimle/muninn/MuninnServerTracesIngestTest.java`

- L26 [design doc]: * exercising the {@code /ingest/traces/*} and {@code /traces/*} routes (design doc Part B/O-13) --

## gimle-observability

### `gimle-observability/src/main/java/com/gimle/observability/AgentMetrics.java`

- L10 [design doc]: * A single Timer/Counter pair around {@code gimle-agent}'s own tick loop body (design doc Part

### `gimle-observability/src/main/java/com/gimle/observability/ApiServerMetrics.java`

- L11 [design doc]: * Per-endpoint Micrometer wiring for {@code gimle-controlplane}'s {@code ApiServer} (design doc

### `gimle-observability/src/main/java/com/gimle/observability/FafnirMetrics.java`

- L11 [design doc]: * Per-endpoint Micrometer wiring for {@code gimle-fafnir} (design doc §9's Observability
- L62 [design doc]: * Feeds {@code LoginThrottle}-based rate limiting on the {@code /secrets/*} surface (design doc

### `gimle-observability/src/main/java/com/gimle/observability/GimleTracing.java`

- L34 [design doc]: * pre-configured one) is left alone rather than double-registering. Unchanged by design doc Part
- L55 [design doc]: * Generalizes {@link #installDefault()} to an arbitrary {@link SpanExporter} (design doc Part
- L77 [design doc]: * Convenience for the common case (design doc Part B/O-13): a process with a configured Muninn

### `gimle-observability/src/main/java/com/gimle/observability/MuninnShipper.java`

- L125 [design doc]: * NDJSON line per meter -- a periodic push, not a pull-based scrape endpoint (design doc §5b/§5f:

### `gimle-observability/src/main/java/com/gimle/observability/MuninnSpanExporter.java`

- L15 [design doc]: * Ships every exported span batch to Muninn (design doc Part B/O-13), serializing each {@link

### `gimle-observability/src/main/java/com/gimle/observability/StoreMetrics.java`

- L11 [design doc]: * Per-RPC-kind Micrometer wiring for {@code gimle-mimir}'s {@code StoreNode} (design doc Part

### `gimle-observability/src/test/java/com/gimle/observability/ThreadNameJfrAttributorTest.java`

- L12 [Phase N]: * posture as Phase 1's {@code OldObjectSampleCorrelator} tests — this verifies the deterministic

## gimle-os

### `gimle-os/src/main/java/com/gimle/os/ResourceLimiter.java`

- L10 [paraphrase] *(semantic re-review)*: identically on every OS. This interface exists because the spec's own architecture names multiple

## gimle-pki

### `gimle-pki/pom.xml`

- L19 [claudedocs/]: claudedocs/tls-transport-security-design.md), via Bouncy Castle -- confirmed to use only

### `gimle-pki/src/main/java/com/gimle/pki/CertificateAuthority.java`

- L43 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §3/§4/§4a/§4b, {@link #signCertificateRequest} is
- L120 [design doc]: * see the design doc's own note on it).

### `gimle-pki/src/main/java/com/gimle/pki/CertificateSigningRequests.java`

- L21 [claudedocs/]: * (or human-operator-side) half of {@code claudedocs/tls-transport-security-design.md} §4's join

### `gimle-pki/src/main/java/com/gimle/pki/OwnCertificateRotator.java`

- L36 [design doc]: * StoreMain} needed the identical logic a second caller (etcd-store-extraction design doc §4.7/§9:

### `gimle-pki/src/main/java/com/gimle/pki/Pem.java`

- L19 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §4/§4a/§4b's CSR flow: a leaf certificate, a CSR,

### `gimle-pki/src/main/java/com/gimle/pki/PkiBootstrapMain.java`

- L27 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §4's CSR bootstrap flow, the same reason {@code
- L63 [design doc]: // local dev, per that class's own code comment) -- see PROJECT design doc §6f: every action

### `gimle-pki/src/main/java/com/gimle/pki/RenewalSchedule.java`

- L9 [claudedocs/]: * When a certificate should be renewed, per {@code claudedocs/tls-transport-security-design.md}

### `gimle-pki/src/main/java/com/gimle/pki/Subjects.java`

- L46 [design doc]: * authorization check (design doc §9's corrected defense-in-depth) can derive the identical

### `gimle-pki/src/test/java/com/gimle/pki/CertificateAuthorityTest.java`

- L208 [design doc]: * Standards-compliance check per the design doc's verification plan: a real, external X.509 tool

### `gimle-pki/src/test/java/com/gimle/pki/SslContextsIntegrationTest.java`

- L38 [design doc]: * doesn't depend on (see the design doc's own module-placement rationale). Asserts on handshake
- L72 [paraphrase] *(semantic re-review)*: // plan §5 item 3 calls for: "a peer presenting a cert not signed by the configured CA."

## gimle-smoke-tests

### `gimle-smoke-tests/pom.xml`

- L35 [claudedocs/]: entry and claudedocs/docs-site-design.md). -->
- L78 [design doc]: Same reasoning as gimle-fafnir above, for MuninnMain (design doc Part B/O-14). -->

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/AutoscaleIT.java`

- L115 [Phase N]: * QA Phase 3 continuation: the error-rate autoscaling signal under real load. {@code
- L197 [Phase N]: * QA Phase 3 continuation: the queue-depth autoscaling signal under real load. Deploys {@link
- L278 [roadmap item] *(semantic re-review)*: Roadmap item 10: CombinationMode.WEIGHTED under real load

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/ClassloaderLeakIT.java`

- L14 [Phase N]: * QA hardening pass, Phase 3: classloader leak detection (CLAUDE.md's own framing -- "first-class",

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterClusterTopologyIT.java`

- L85 [design doc]: // The real secret round trip (design doc §9/§11 Phase C): written via the API above, fetched
- L98 [design doc]: // M-node store cluster (design doc §4.5, no linearizability requirement), so a replica that

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterSmokeClusterSupport.java`

- L1091 [roadmap item] *(semantic re-review)*: "worst-signal"/"weighted", roadmap item 10) and its four per-signal weights
- L1302 [design doc]: // (design doc Part B/O-14).
- L1313 [design doc]: // A single key file shared across every Fafnir replica -- the design doc §8 multi-replica
- L1436 [design doc]: // spawnStore wiring (design doc Part B/O-10).
- L1639 [design doc]: * (design doc §6e) before any deployment references the tenant. A deployment's {@code tenantId}
- L1801 [design doc]: * Fafnir replica (design doc §9/§11 Phase C), delivered down to the worker, and read back out by

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/ObservabilityIT.java`

- L17 [design doc]: * Observability round trips through Muninn (design doc Part B): a deployed instance's own log line
- L26 [design doc]: * The Muninn logs fallback (design doc Part B/O-11), end to end: a real deployed instance's own
- L81 [design doc]: * The metrics round trip (design doc Part B/O-10): a real request against a real control-plane

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/QuotaIT.java`

- L28 [Phase N]: * QA hardening pass, Phase 3 continuation: {@code QuotaReconciler}'s own class javadoc states it
- L103 [Phase N]: * QA Phase 3 continuation: the admission-time counterpart to the flag-but-don't-evict scenario

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/RaftResilienceIT.java`

- L88 [Phase N]: * QA Phase 3 continuation: failover under real *concurrent* writes, not a write submitted only
- L206 [design doc]: * its own membership (a real, harder Raft edge case -- see the design's own single-server-change

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/RollingUpdateIT.java`

- L29 [Phase N]: * QA Phase 3: rolling update / version-aware traffic cutover under real load. Deploys 2 replicas
- L150 [Phase N]: * QA Phase 3 continuation: the single-replica counterpart to the 2-replica test above -- confirms

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/SelfHealingIT.java`

- L26 [Phase N]: * QA hardening pass, Phase 3: the agent-death test above (and every other existing scenario in
- L85 [Phase N]: * QA hardening pass, Phase 3: the module tier of the same escalation chain the test above

### `gimle-smoke-tests/src/test/java/com/gimle/smoketests/ServiceFabricIT.java`

- L17 [Phase N]: * QA hardening pass, Phase 3: the service fabric's circuit breaker (CLAUDE.md's own framing:

## gimle-worker

### `gimle-worker/src/main/java/com/gimle/worker/FabricServerTlsWatcher.java`

- L18 [claudedocs/]: * by the agent's own rotation, per {@code claudedocs/tls-transport-security-design.md} §6.2 -- by

### `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java`

- L553 [bare §N] *(semantic re-review)*: before §6, nothing past bindFabricServer ever needed to hold a

### `gimle-worker/src/test/java/com/gimle/worker/ControlChannelClientTest.java`

- L23 [internal task ID] *(semantic re-review)*: the agent side (§4.2's "server side") is gimle-agent's own Task 20/22 concern.

### `gimle-worker/src/test/java/com/gimle/worker/FabricServerTlsWatcherTest.java`

- L40 [claudedocs/]: * claudedocs/tls-transport-security-design.md} §6.2/§6.4 item 2. Uses a fast poll interval so the
- L112 [bare §N] *(semantic re-review)*: §4b's own rotation does, and what an agent-managed worker sees happen underneath it.
- L118 [bare §N] *(semantic re-review)*: Key before cert, matching AgentMain's own write order (§6.2)

### `gimle-worker/src/test/java/com/gimle/worker/testsupport/ControllableLivenessProbe.java`

- L20 [paraphrase] *(semantic re-review)*: BoundedModuleScheduler tags that thread's MDC per-submission (design log-explorer §3)

## pom.xml

### `pom.xml`

- L359 [P#-#] *(semantic re-review)*: P2-13: CycloneDX SBOM generation, offline only
- L441 [Phase N]: Opt-in per-role runtime image (Phase 6 stretch goal, CLAUDE.md's own "jlink/jpackage:
- L539 [claudedocs/]: see claudedocs/docs-site-design.md). Generating an aggregate Javadoc across nine modules plus

## spotbugs-exclude.xml

### `spotbugs-exclude.xml`

- L3 [P#-#] *(semantic re-review)*: SpotBugs (P2-12) baseline exclusions.

---

## Uncertain -- needs your own judgment call

These came up during the semantic re-review but are genuinely ambiguous (legitimate external-standard citation vs. internal doc, self-contained reasoning that merely echoes banned phrasing, redundant with an already-caught hit in the same comment block, etc.) -- not auto-included above.

- `gimle-mimir/src/test/java/com/gimle/mimir/raft/RaftClusterTlsTest.java`:L55 -- same javadoc block as an already-caught design-doc citation 10 lines earlier
- `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleState.java`:L4 -- "the spec" likely means the real OSGi lifecycle spec (legitimate), not an internal doc -- unqualified wording makes it ambiguous
- `gimle-core/src/test/java/com/gimle/core/logging/LogRotationTest.java`:L27 -- dangling '§-mirroring-kubelet' HTML entity with no visible referent -- possible leftover fragment of an edit
- `gimle-core/src/main/resources/logback.xml`:L3 -- points at CLAUDE.md (checked in, not gitignored) describing a coding convention, not a plan/design artifact
- `gimle-worker/src/main/java/com/gimle/worker/FabricServerTlsWatcher.java`:L21 -- same javadoc block as an already-caught claudedocs/ citation 3 lines earlier
- `pom.xml`:L361 -- "documented deferral" is vague -- may just mean 'explained right here,' or may allude to an external decision record
- `gimle-smoke-tests/pom.xml`:L116 -- "this repo's own session history" -- unusual meta-phrasing, possibly alluding to dev/AI session logs rather than the code
- `gimle-smoke-tests/pom.xml`:L120 -- cites FLAKY_TESTS.md -- a checked-in tracking log, not a design/roadmap doc, but arguably a 'process artifact'
- `gimle-controlplane/src/main/java/com/gimle/controlplane/ControlPlaneMain.java`:L54 -- "design decision made when..." -- fully self-contained reasoning, but phrasing echoes the banned 'decision log' pattern
- `gimle-controlplane/src/test/java/com/gimle/controlplane/api/ApiServerConsoleContractTest.java`:L27 -- "Design doc §12" (capitalized) -- redundant with an already-caught lowercase hit elsewhere, flagged only for completeness
