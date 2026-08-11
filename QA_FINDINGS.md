# QA hardening findings — 2026-08-11

A dedicated QA pass (`qa-hardening` branch) covering three phases: stabilize the build by fixing
reported flaky tests, look for ways to speed up `mvn verify`, and hunt bugs via new/enhanced tests
including a real end-to-end cluster. This doc records what was found and fixed, what was
investigated and found not to be a bug, and what's still open for a follow-up session. Phase 3 was
continued across several sessions on this same branch; see its own subsections below for the
running list of scenarios added.

## Phase 1 — flaky tests: 9 real bugs found and fixed, not just excluded

Every entry that was previously only carried on `FLAKY_TESTS.md`'s standing exclusion list (or
newly observed, undiagnosed) got a real root-cause diagnosis. Full detail, reproduction notes, and
verification counts are in `FLAKY_TESTS.md` itself — this is the short version:

| Test | Real bug | Fix |
|---|---|---|
| `RaftClusterTlsTest#leader_election_and_write_replication_work_over_mtls` | Missing `@Isolated`, unlike its sibling `RaftClusterTest` | Added `@Isolated` |
| `RaftClusterTest#a_redirected_write_to_a_follower_returns_the_correct_leader_address` | Test raced a just-elected leader's first heartbeat (`leaderHint` still unset) | Wait for `leaderHint()` before exercising the redirect path |
| `RaftClusterTest#a_far_behind_follower_catches_up_via_install_snapshot...` | Genuine JMM visibility gap: read a plain, deliberately-unsynchronized `RaftLog` field from the test thread with no happens-before edge | Fold into the same polling predicate instead of a one-shot read |
| `SessionTokensTest#a_tampered_token_is_rejected` | Flipping the token's *last* base64url char only corrupts 2 significant bits, occasionally decoding to the same byte | Flip a middle character instead |
| `SystemLogCaptureTest#system_log_capture_survives_a_respawn` | Two bugs: sandbox's `JAVA_TOOL_OPTIONS` stdout line broke a "every line matches" assertion, **and** the wait helper counted *any* 2 lines (satisfied before the actual respawn happened) | Wait for 2 banner lines specifically; assert on banner-line count |
| `GreeterSmokeTestIT` running under plain `mvn verify` | An exclusion-only `-Dtest='!A,!B,...'` overrides a plain Surefire `<excludes>` and broadens class discovery | `@Tag("smoke")` + `excludedGroups=smoke` (JUnit 5 tag filtering, a separate mechanism `-Dtest` doesn't affect) |
| `SecretStoreTest#concurrent_writers_to_the_same_key...` | Same CPU-contention-under-class-concurrency shape as the Raft entries; `gimle-fafnir` had no `@Isolated` usage anywhere | Added `@Isolated` |
| `LogRotationTest#cursor_paging_and_follow_resolve_correctly_across_a_rotation_boundary` | Previously undiagnosed. Only the *last* ~18 of 2000 written lines survive rotation eviction; a tight write loop let every survivor land on one millisecond, and `LogFileReader#readOlder`'s strict `isBefore` cursor legitimately returned nothing "older" | Sleep 1ms per write for just the tail lines that actually survive rotation |
| `CertificateAuthorityTest#generated_leaf_certificate_is_readable_by_openssl` | Previously undiagnosed. OpenSSL 3.x renders `CN = node-1` (spaces), the assertion checked `CN=node-1` (no spaces) — confirmed by generating a cert directly | Strip whitespace before comparing |
| `FabricServiceRegistryTest#a_failing_endpoints_breaker_opens_and_is_excluded` | Fixed warmup count (30 calls) wasn't always enough for the breaker to actually open | Poll until 5 consecutive calls land on the healthy endpoint (bounded to 500 attempts) |
| `GossipMemberTest` (whole class) | Same class-concurrency contention; newly observed *during this session's own* background runs, not previously tracked | Added `@Isolated` |

Net effect: the standing exclusion list shrank from 7 entries to 2
(`RaftClusterTest#removing_a_server_shrinks_the_quorum_requirement...` and
`StoreClientClusterTest#a_client_keeps_writing_successfully_across_a_forced_leader_failover`), both
confirmed to pass reliably outside full-reactor cross-*module* contention (an axis `@Isolated`
can't reach — see `FLAKY_TESTS.md` for why). A final full-reactor `mvn verify` with just those two
exclusions came back **BUILD SUCCESS** in 6:03 wall-clock.

## Phase 2 — `mvn verify` speed: investigated, no change kept

Tried tuning `junit.jupiter.execution.parallel.config.dynamic.factor` down from `1.0` (a real,
plausible oversubscription mechanism: root `.mvn/maven.config`'s `-T 1C` reactor parallelism times
this per-module setting can exceed the sandbox's 4 cores). A first measurement at `0.5` looked like
a genuine win (5:29 vs. a 6:03 baseline, fewer flakes) but a same-command confirmation run came back
at 6:39 — worse than baseline. Run-to-run wall-clock variance in this shared sandbox turned out to
be larger than the effect being measured, so the change was reverted rather than kept on unproven
evidence. `maven-build-cache-config.xml`'s caching stays disabled for the reason already documented
in that file's own top comment (the pinned extension version has no per-project override in its
schema); not re-litigated here since nothing changed to justify revisiting it, and only that one
version is available in this sandbox's local repository to check against.

**Worth a follow-up**: a controlled speed measurement needs either a quieter environment than this
shared sandbox, or a methodology that averages several runs per configuration rather than trusting
a single wall-clock number — the four data points gathered here (6:03 / 5:29 / 6:24 / 6:39) don't
even move monotonically with the setting being tuned.

## Phase 3 — real-cluster QA: one new scenario added, platform behaves correctly

Extended `gimle-smoke-tests`' `GreeterSmokeTestIT` (real `ControlPlaneMain`+`AgentMain`+
`WorkerMain`+`FafnirMain`+`MuninnMain`+`StoreMain` cluster) with
`a_crashed_workers_instance_is_respawned_and_returns_to_active`: kills the `WorkerMain` process
directly (not the agent — a distinct failure domain per CLAUDE.md's own tiered self-healing
framing: module dispose+reinstantiate / worker `destroyForcibly`+respawn / machine-level
reschedule are three separate recovery paths, and no existing test exercised the middle one).
Asserts a genuinely *new* worker process (different pid) replaces the killed one and the deployment
returns to `ACTIVE`. **Result: passes reliably (3/3 isolated runs, clean in a full-suite run) — the
worker-tier self-healing guarantee holds.** No bug found in the platform itself here, which is
itself a useful, positive QA result, not a non-finding.

**A real bug was found and fixed along the way**, worth flagging on its own since it'll bite anyone
else writing a similar process-identification helper in this repo: `ProcessHandle.Info#commandLine()`
silently truncates a very long command line before reaching a trailing argument. This repo's own
multi-module classpath easily produces a command line long enough to lose the main-class token
(`com.gimle.worker.WorkerMain`) entirely — a naive `.contains("com.gimle.worker.WorkerMain")` check
never matches, even though the process is real and running. Match on an *early* argument instead
(here, the worker-specific `-Dgimle.log.root=.../workers/...` flag). Not filed as a platform bug
(nothing in `gimle-agent`/`gimle-worker` is affected — this is purely a JDK `ProcessHandle`
behavior a *test* has to work around), but recorded here since it's exactly the kind of thing that
would otherwise cost a future session real time rediscovering.

**A second scenario was added in a follow-up continuation of this same session**:
`a_tenant_over_quota_deployment_is_flagged_but_not_evicted` — deploys `greeter-provider` under a
tenant quota it comfortably satisfies (asserts `quotaViolating` is `false` once `ACTIVE`), then
retroactively lowers that same tenant's quota (via `PUT /tenants/{id}`) to a level the already-running
deployment now exceeds. Asserts `QuotaReconciler` flags it (`quotaViolating` becomes `true` within
30s, matching its `RECONCILE_INTERVAL = Duration.ofSeconds(2)`) **and** that the deployment stays
`ACTIVE` — i.e. `QuotaReconciler`'s documented "flag, never evict" contract (it only ever calls
`StateStore#putQuotaViolation`, never touches instance count) holds against a real running cluster,
not just its own unit test (`QuotaReconcilerTest`). **Result: passes reliably (3/3 isolated runs
clean, 2/3 full-suite runs clean).** No bug found — this is a second positive QA result confirming
existing platform behavior rather than a new defect.

The one full-suite run with a failure showed **both** this new test and an unrelated, untouched
pre-existing test (`cluster_tolerates_losing_one_store_node_mid_deployment`) fail in the same run —
evidence pointing at shared sandbox resource pressure from running all 6 heavy real-cluster tests
back-to-back (that single run took 159.8s), not a defect specific to either test. Two follow-up
full-suite runs after that were both 6/6 clean, consistent with that read.

### Real bug found while setting up the next scenario: every process's own logging config was silently broken

Before even getting to the membership-change scenario below, the first attempt to run it surfaced
a real, independent bug: every store/control-plane/etc. process's own startup log showed
`ch.qos.logback.core.joran.spi.JoranException: ... The string "--" is not permitted within
comments`, pointing at `gimle-core/src/main/resources/logback.xml` (shipped in `gimle-core`'s own
jar, so every process on the classpath hits this identically). Line 9's comment contained a literal
`--` mid-sentence (`"unconditionally -- every process already ..."`), the exact same class of XML
rule already hit twice this session in `pom.xml` comments (see `FLAKY_TESTS.md`), except this time
in a real production resource, not a build file. The practical effect: Logback's XML parse fails on
every single process startup, silently falling back to its own bare default configuration instead
of the real one this repo ships (`ConsoleLogEncoder`, `PLATFORM.log`/JSON file appenders, etc.) —
console output still appears (Logback's fallback default does emit *something*), which is exactly
why this had gone unnoticed: nothing crashes, nothing looks obviously wrong at a glance, but the
real logging configuration was never actually in effect. **Fixed** by rephrasing the comment to
avoid the literal `--` (same style already used for the two `pom.xml` fixes). Confirmed via
`python3 -c "import xml.dom.minidom as m; m.parse(...)"` on the file directly, and by grepping
every other tracked `*.xml` file in the repo the same way — no other occurrences.

### Real bug found and fixed: a standalone store node can self-elect as a phantom leader that never steps down

Extending `GreeterSmokeTestIT` with a live Raft membership-change scenario
(`a_new_store_node_joins_via_live_membership_change_and_is_then_removed` — see below for its
current `@Disabled` status) surfaced a genuine split-brain bug in `RaftNode`, not a test bug.

**Reproduction**: start a `StoreMain` process standalone, with no `--peers` (`StoreMain`'s own
documented pattern for a node that will join an *existing* cluster later via
`StoreClient#addServer`, the P1-5 etcd-style live-membership-change feature). Once that node is
added to a real 3-node cluster already serving writes, the control plane's own reconcile loop
starts failing continuously (`no reachable store leader could serve propose/getNodeHeartbeat/...
after retrying every endpoint`) for a real, sustained window (observed 85s-180s+ across 5 runs).

**Root cause**: `RaftNode.start()` deliberately self-elects a node instantly, skipping the normal
150-300ms election timer, whenever it's constructed with an empty peer set (`start()`'s own
comment: "a single-node cluster's majority is 1 -- self alone -- so there is nothing to elect").
That's correct for bootstrapping a brand-new single-node cluster, but a node standing by to
*join* an existing cluster via a later `addServer` also starts with an empty peer set — there is
no way to tell the two apart at `RaftNode`/`StoreMain` construction time. Such a node immediately
becomes `Role.LEADER` of a phantom one-node "cluster" at term 1. Compounding this:
`RaftNode.onAppendEntries`/`onInstallSnapshot` only ever demoted a `Role.CANDIDATE` back to
`FOLLOWER` on an *equal*-term message (`else if (role == Role.CANDIDATE)`) — there was no case for
an already-`Role.LEADER` node. Since a freshly-bootstrapped 3-node cluster and a freshly-
self-elected standalone node both very plausibly land on term 1 independently, the phantom leader
was never told to step down by the real leader's own heartbeats at that same term. Once `addServer`
committed and replicated the new membership config to it, `reconfigurePeersLocked`'s own `if (role
== Role.LEADER) startPeerSenderThreadLocked(...)` check — still true, since the phantom leader was
never demoted — made it start broadcasting *its own* competing heartbeats to the real cluster's
other members at the genuine leader's own term: a real, sustained split-brain, not a self-resolving
glitch.

**Fixed**: `gimle-mimir/src/main/java/com/gimle/mimir/raft/RaftNode.java` — both
`onAppendEntries`/`onInstallSnapshot`'s equal-term branch now demotes on `role != Role.FOLLOWER`
(covers both `CANDIDATE` and `LEADER`, the only three `Role` values), via a new
`demoteToFollowerLocked()` helper split out of `stepDownLocked` so a demoted phantom leader's
already-started `peerSenderThreads` are actually interrupted too, not just its `role` field
flipped. Verified against the *entire* `gimle-mimir` module test suite (216+ tests across
`gimle-core`/`gimle-observability`/`gimle-pki`/`gimle-mimir`, `mvn -pl gimle-mimir -am verify`) —
**zero regressions**, only the already-documented `StoreClientClusterTest` flake (see
`FLAKY_TESTS.md`) hiccuped once and passed on its own rerun.

**Still open**: fixing this real correctness bug did *not* eliminate the smoke test's own
post-membership-change instability window — it dropped from a consistent ~90s+ to a *variable*
85s-180s+ across runs after the fix, evidence the phantom-leader bug was a real contributing factor
but not the sole one. The remainder is most plausibly genuine election/log-catchup delay under this
sandbox's own load (4 cores, 12 concurrent JVMs for this one scenario alone — the heaviest in the
whole suite), consistent with the *already-documented* sandbox-load sensitivity of Raft
membership-change tests even at the much lighter `RaftClusterTest` tier (see `FLAKY_TESTS.md`'s
`removing_a_server_shrinks_the_quorum_requirement...` entry) — just amplified here by a much larger
process count. No fixed timeout this suite tried (90s, 150s, 300s) both passed reliably *and*
stayed honest about a real upper bound, so **the new test is left in place but `@Disabled`**, with
a class-level javadoc pointing back to this section, as a deterministic reproduction for whoever
picks this up next. Two directions worth trying then, neither attempted here given time already
spent: (a) run it on a quieter/dedicated CI runner rather than this shared sandbox, to see whether
the variability itself is sandbox-specific; (b) a real Raft-level improvement — a non-voting
"learner" catch-up phase before a new peer becomes a full voting member (the classic Raft answer to
new-member disruption, and a further hardening beyond the phantom-leader fix above) would reduce
the disruption window's root cause rather than just tolerating it with a longer timeout.

### Console Playwright coverage extended to six more real screens

`gimle-console/e2e/greeter-smoke.spec.ts` previously covered only Deployments and Logs. Extended it
with six more screens, all asserting genuine deployed-cluster state (real browser, real backend, no
mocked repositories, same as the existing two): Overview (recent deployments/node registry),
Nodes (`smoke-node-1` shows `healthy`), Tenants (`smoke-tenant`, provisioned via
`provisionTenantAndSecret`), Instances (both greeter deployments' real running instances), Secrets
(the real key written through Fafnir is visible, its **value** is asserted absent — the masked-
until-revealed contract, not just presence of the key), and Topology (both deployments placed on
the real node). A shared `expectVisibleEventually` helper generalizes the existing
`expectDeploymentActive` retry pattern (reload-and-recheck, not re-poll-same-DOM) to every one of
these, for the same non-linearizable-store-read staleness reason documented on the original helper.
**Result: 8/8 passes on both of two full runs (20.2s/22.7s wall-clock for the Playwright leg) — no
bug found, a second useful positive QA result** (the console genuinely reflects real cluster state
across every screen exercised, not just the two already covered). Still not covered: Config screen,
and the Metrics screen specifically (real data now exists via Muninn per Part B, but wiring a
meaningful assertion there — a specific metric name/value rather than just "the page loaded" — was
left for a follow-up given its more involved chart-rendering surface).

### Real bug found and fixed: multi-signal autoscaling never actually worked outside unit tests

Continuing the QA mission with a real load-generation tool (Gatling) against a real cluster
surfaced a serious Part C regression: `DomainCodec.writeOptionalAutoscalePolicy`/
`readOptionalAutoscalePolicy` were never updated when `AutoscalePolicy` gained its three optional
multi-signal fields (`targetRequestRatePerSecond`/`targetErrorRatePercent`/`targetQueueDepth`).
Both methods still only wrote/read the original three CPU-only fields, so **any autoscale policy
configuring a non-CPU signal was silently truncated back to CPU-only the instant it crossed either
wire this codec backs** — `StoreClient.propose` on the way in from `ApiServer`, and every Raft
log/snapshot replication after that. In other words: Part C shipped, was unit-tested, and merged,
but multi-signal autoscaling had never actually worked against a real deployed cluster, only
against the in-process `StateStore` bypass `AutoscaleReconcilerTest` uses.

**Reproduction, in three layers**:

1. **New example module**: `gimle-examples/greeter-load-generator`, a real hosted module bridging
   external HTTP load into the fabric — the wire protocol `ModuleContext#lookupService`'s
   cross-worker proxy speaks has no client outside a hosted module, so an external tool can't call
   `Greeter` directly. Every inbound HTTP request to `/call` on its fixed port (19077) does one
   real, synchronous `lookupService(Greeter.class)` + `greet(...)` call against greeter-provider.
2. **A real Gatling simulation**: `gimle-smoke-tests`' new `GreeterAutoscaleSimulation` (Java DSL,
   `io.gatling:gatling-http-java`/`gatling-charts-highcharts` test-scope dependencies) drives
   controllable-rate HTTP load at that bridge, spawned as its own JVM process
   (`io.gatling.app.Gatling -s ...`, needing `--add-opens java.base/java.lang=ALL-UNNAMED` — a real,
   documented Gatling requirement on modern JDKs, not optional) the same way every other cluster
   component in this suite is spawned, mirroring the existing Playwright-as-subprocess precedent.
3. **The new smoke test**: `a_deployment_scales_up_under_real_gatling_generated_request_rate_load`
   deploys greeter-provider with `targetCpuUtilizationPercent: 200` (deliberately unreachable by
   this workload, so CPU alone can never explain a scale-up) and `targetRequestRatePerSecond: 5.0`,
   drives 20 req/s of real load for 60s, and asserts a second real instance gets placed.

**Diagnosis**: a live probe script polling `GET /deployments/*` mid-run showed
`requestRatePerSecond` genuinely reaching the real 20.0/s Gatling target (worker-measured, agent-
heartbeated, control-plane-observed — the whole pipeline up to that point was correct), sustained
for 48+ seconds — yet `effectiveReplicas` never moved off 1, and `AutoscaleReconciler`'s own
change-only log line never fired even once. Tracing `AutoscaleReconciler` → `StoreReader`/
`MutationSink` → `StoreClient`/`RaftNode` → `DomainCodec` (shared by both `RaftCodec`, the Raft log
wire format, and `StoreCodec`, the client-facing wire format) found the exact drop point.

**Fixed**: `gimle-mimir/src/main/java/com/gimle/mimir/codec/DomainCodec.java` now writes/reads all
three optional fields (new `writeOptionalDouble`/`readOptionalDouble`/`writeOptionalInt`/
`readOptionalInt` helpers, the same presence-boolean-then-value idiom `writeOptionalString` already
used). **Also fixed the test gap that let this ship**: `StoreCodecTest`/`RaftCodecTest`'s shared
`deploymentSpec()` fixtures used only the CPU-only 3-arg `AutoscalePolicy` constructor, so their
existing generic round-trip equality check passed identically whether or not the codec touched the
three new fields at all (`empty` round-trips correctly either way) — both now construct a policy
with all three fields present, so a future regression here fails immediately in `gimle-mimir`'s own
test suite rather than needing a real Gatling run to surface again.

**Verification**: full `mvn -pl gimle-mimir -am verify` clean (only the two already-documented
sandbox-load flakes, both self-resolved on rerun); the new smoke test passed 2/2 clean runs after
the fix (53s/56s wall-clock) after failing consistently before it (both the original CPU-only
symptom and one unrelated single-run resource-pressure stall placing the load-generator, which
cleared on retry, consistent with this session's other real-cluster-under-load findings). Full
`gimle-smoke-tests -Psmoke` run stays green with the new test included.

### Real bug found and fixed: rolling updates never actually reached the worker

Continuing the QA mission onto rolling update / version-aware traffic cutover under real load
surfaced a second serious real-cluster-only regression, this time in `gimle-agent`: a moduleId
change at a fixed replica index — exactly what `DeploymentReconciler.handleRollingUpdate` does,
removing then immediately re-placing the same index with the new `moduleId` — was **silently never
applied**. The old worker process kept running the old code forever, and every heartbeat kept
reporting the stale moduleId as if nothing had happened, so `DeploymentReconciler`'s own
`rollingIndex` guard was fooled into thinking each migration had already completed.

**Reproduction**: deployed 2 replicas of `greeter-provider` at v1.0.0, held sustained real Gatling
HTTP load through `greeter-load-generator`'s fabric bridge the whole time, then submitted a real
v1.1.0 build compiled on the fly by `TestModuleBuilder` (same technique
`RealBundledHookAndProbeInvocationTest` already uses, avoiding a second committed example module
just to bump one version number) under the same 2-replica deployment name. The test asserted both
replicas eventually report `observation.moduleId.version == "1.1.0"` and that at least one instance
stayed `ACTIVE` at every sampled moment throughout — genuine rolling behavior, not a full outage.

**Diagnosis**: the control plane's own log showed both indices being flagged "on an old module
version; rolling it forward" within seconds of each other, and both `PutAssignment` mutations
landing with the new `moduleId` — but `agent.log` showed the exact same two worker PIDs, spawned
once at cluster start, still alive and still answering `Greeter#greet` calls two minutes later,
never respawned. Root cause: `AgentMain.reconcileAssignments` keys its supervised-instance map by
`instanceKey` alone — `deploymentName + "#" + instanceIndex"`, deliberately omitting `moduleId` — so
a re-fetched assignment whose `moduleId`/`artifactPath` changed but whose key stayed the same read
as `supervised.containsKey(key) == true` and the loop did nothing further: no stop, no restart. The
worker's `SupervisedInstance.assigned` field is `final`, so nothing else in the agent would ever
notice the swap either, including the heartbeat builder, which reads `instance.assigned.moduleId()`
straight off that same never-updated record.

A second, independent gap compounded this: `DeploymentReconciler.isReady` only checked the
heartbeat's `ready` flag for an index, not the `moduleId` it was actually reporting. Even with the
agent bug fixed, a stale-but-present `ready` observation from the outgoing old instance — arriving
on the exact heartbeat cycle a rollout starts, before the replacement has even been placed — could
be mistaken for the new one having already landed, letting `handleRollingUpdate` clear
`rollingIndex` and move on to the next index prematurely.

**Fixed**: `AgentMain.reconcileAssignments` now detects a `moduleId`/`artifactPath` change at an
already-supervised key (`requiresReplacement`, a small extracted, directly-testable predicate) and
stops the old worker before falling through to the ordinary start path. `DeploymentReconciler
.isReady` now also requires the observation's own `moduleId` to match the assignment's, not just
its `ready` flag. New regression coverage: three `AgentMainTest` cases exercising
`requiresReplacement` directly (moduleId change, artifactPath change, unchanged assignment), and a
new `DeploymentReconcilerRollingUpdateTest` case constructing the exact stale-ready-wrong-moduleId
heartbeat race and asserting `rollingIndex` is not cleared by it.

**Verification**: `gimle-agent`/`gimle-controlplane` full module `mvn verify` both green (one
unrelated, already-flaky `ApiServerMetricsTest` case self-resolved via Surefire's own automatic
rerun, consistent with this session's other findings of pre-existing sandbox flakiness); the new
smoke test passed 2/2 clean runs after the fix (67s/54s wall-clock) after hanging to a 3-minute
timeout consistently before it. Full `gimle-smoke-tests -Psmoke` run stays green with the new test
included.

### Error-rate and queue-depth autoscaling signals under real load: both work, platform behaves correctly

Continuing the QA mission onto the last two Part C signals request rate's own real-cluster exercise
had left unproven (see above -- CPU and request rate were both already covered, error rate and
queue depth were still unit/integration-only). Unlike the previous two real-cluster autoscaling
scenarios, this one found no new bug: both signals genuinely work end to end against a real
cluster, first try, 2/2 clean runs each.

**Error rate**: `buildFaultyProviderJar()` compiles a real `greeter-provider` on the fly (via
`TestModuleBuilder`, same technique the rolling-update scenario's `buildProviderV2Jar()` already
uses, rather than injecting fault behavior into the real, committed example module) whose `greet`
deterministically throws every other call (~50%). The fabric server's own dispatch (`FabricServer
#dispatch`) records each thrown exception as a real error against that instance's own
`WorkerMetrics` -- exactly the `errorRatePerSecond` heartbeat signal `AutoscaleReconciler`'s
`errorRatePercent` helper (errors/requests, not a raw count) reads. Deployed with
`targetCpuUtilizationPercent: 200` (unreachable) and no request-rate target configured, so only
`targetErrorRatePercent: 20.0` (comfortably under the real ~50%) can explain a scale-up. Driven by
the existing open-model Gatling injection at a steady 10 req/s for 60s.

**Queue depth**: `buildSlowProviderJar()` compiles a real provider whose `greet` always sleeps
~300ms. A fixed request rate alone says nothing about how many requests are in flight at once, so
building a real backlog on `WorkerRuntime`'s per-module `BoundedModuleScheduler` (concurrency bound
4) needed a different load shape: `GreeterAutoscaleSimulation` gained a `gimle.load.concurrentUsers`
knob switching it from Gatling's open model (fixed rate) to the closed model
(`constantConcurrentUsers(n).during(...)`, holding exactly `n` requests continuously in flight,
Gatling re-injecting a replacement the instant one completes). Driven at 20 concurrent users against
the bound of 4, which sustains a real ~16-deep backlog -- `queueDepth` is reported straight off that
scheduler (`WorkerMain#metricsReportLoop`). Deployed with the same unreachable CPU target, no
request-rate or error-rate targets, and `targetQueueDepth: 2` (comfortably under the real backlog).

**Verification**: both new smoke tests
(`a_deployment_scales_up_under_real_error_rate_load`/`a_deployment_scales_up_under_real_queue_depth
_load`) passed 2/2 clean isolated runs (52-54s wall-clock each), plus a full `gimle-smoke-tests
-Psmoke` run alongside every other test in the suite. All four Part C signals (CPU, request rate,
error rate, queue depth) are now proven end to end against a real cluster, not just
`AutoscaleReconcilerTest`'s in-process bypass.

### Single-replica rolling-update downtime, quota-at-admission, and leader failover under load: all three confirmed correct

Continuing sequentially down the remaining open scope. None of these three found a new bug — each
confirms a real, already-implemented guarantee actually holds against a real cluster, not just at
the unit/integration tier.

**Single-replica rolling update has real, observed downtime**: the 2-replica rolling-update test
(see above) proves continuous availability; `DeploymentReconciler.handleRollingUpdate`'s own javadoc
is explicit that this is in-place index replacement, never additive surge-then-drain, so a single
replica *should* see a real gap. `a_single_replica_rolling_update_has_real_observed_downtime`
deploys 1 replica, rolls it from v1.0.0 to a real v1.1.0 (`TestModuleBuilder`-compiled, same as the
2-replica test), and asserts the opposite inequality: the background sampler must observe at least
one moment with zero `ACTIVE` instances, and the deployment must still fully converge afterward.
Confirmed: the documented tradeoff is exactly as costly as documented, not silently worse (never
recovering) or silently better (an undocumented surge).

**A deployment that would exceed tenant quota is rejected at admission**: `ApiServer
#checkTenantQuota` is a real, already-implemented 409 rejection at submission time, distinct from
`QuotaReconciler`'s own after-the-fact flag-but-don't-evict (already covered at the smoke tier, see
above) for a quota *retroactively lowered* below what's already running. This scenario sizes a
tenant's quota to fit exactly one `greeter-provider` replica and submits a second deployment for the
same tenant once the first is `ACTIVE` — confirmed: real `409`, the rejected deployment is never
durably created at all (`GET /deployments/*` returns `404`, not an empty/pending record), and the
first, already-compliant deployment is completely unaffected.

**Raft leader failover loses no acknowledged write under concurrent load**: the existing
single-node-loss scenario (`cluster_tolerates_losing_one_store_node_mid_deployment`) only submits a
new write *after* the kill — this scenario runs a continuous background writer (a new, distinct
tenant `PUT` roughly every 200ms — a real `StoreClient#propose` write against the same 3-node Raft
cluster, deliberately using the lightweight tenant API rather than real module deployments so no
scheduler/agent/worker side effects confound the signal) *before, during, and after* one store node
is killed, deliberately not leader-targeted (`StoreRpc` doesn't expose "who is leader" to a client,
same reasoning the existing test already documents — Raft's own commit-before-acknowledge guarantee
must hold regardless of which node is lost). Confirmed: writes kept succeeding after the kill (real
recovery under load), and every single acknowledged (`200`) write was still durably readable
afterward — none lost.

**Verification**: all three new smoke tests passed 2/2 clean isolated runs each.

### Console Config screen and a data-specific Metrics assertion: both confirmed correct

Closing out the last item on the original open-scope list. No new bug: both screens genuinely
reflect real backend state.

**Config screen**: `GreeterSmokeTestIT#provisionTenantAndSecret` now also writes a real, plain
(non-encrypted) config entry (`greeting.locale` = `en-US`) via `PUT /config/{tenantId}/{key}`,
distinct from the already-masked `SECRET_KEY` the Secrets screen covers — exercises `ApiServer
#handleListConfig`'s own plain-vs-encrypted filtering path, which nothing previously drove through
the console. A new Playwright test asserts both the key and its real value render.

**Metrics screen**: asserts the "Instances observed" `StatTile`'s own rendered *value* equals the
real instance count (2: one `greeter-provider-deployment` instance, one `greeter-consumer-deployment`
instance) — a concrete, data-derived number, not just that the chart shell rendered without
crashing, the same "data-specific, not just page-loads" bar every other screen in this suite is
already held to.

**Diagnosed, not just retried, an apparent failure**: the very first isolated run of the updated
integration test showed the Config assertion failing. Rather than assume a real bug, a temporary
direct backend check (`GET /config/{tenantId}` polled straight from the Java test, bypassing the
browser) confirmed the correct data was present on every run, including the failing one — isolating
the flake to the browser/rendering side. Across 5 isolated runs while diagnosing this, the
Playwright suite failed exactly once on the Config assertion and, on a *different* run, once on the
pre-existing Nodes/healthy assertion instead — different screens failing on different runs is
itself evidence against a real, deterministic bug in either, consistent with this suite's own
already-documented general Playwright-under-load flakiness (see FLAKY_TESTS.md).

**Verification**: 3 further clean full runs of the integration test (Playwright suite included)
after the diagnostic confirmed the backend was correct; the diagnostic code itself was then removed.

### Real bug found and fixed: module-tier crash-loop backoff never actually gave up

Set out to add a real-cluster smoke test for `WorkerRuntime`'s module-tier restart budget (P0-3:
a module that keeps failing should eventually escalate to `FAILED` rather than restart forever) —
building the fixture surfaced a genuine bug in the mechanism itself, not just a gap in coverage.

**The bug**: a module whose `LivenessProbe` never recovers restarted *forever* instead of ever
exhausting its budget and escalating. Root cause, confirmed with a fast in-process repro before
touching production code (139 restart cycles in 20 seconds, budget never exhausted): each
successful `stop()`/`resolve()`/`start()` cycle inside `WorkerRuntime#restartModule` replaced its
own `RestartTracker` with a brand-new one — `stop()` drives the module through `UNINSTALLED`, which
fires `onUninstalled` and removes the tracker; the following `start()` fires `onActive`, which finds
nothing there and creates a fresh one. `attemptsInWindow` could therefore never accumulate past 1,
independent of `tracker.recordSuccess()` being called (on the now-orphaned old instance) immediately
after `start()` returned — with no stability confirmation at all, unlike `WorkerProcessSupervisor`'s
own worker-tier equivalent, which deliberately waits `DEFAULT_STABLE_UPTIME_THRESHOLD` (10s) of
genuine uptime before resetting its backoff.

**The fix**: `restartModule`'s attempt now re-associates the *original* tracker back into
`restartTrackers` after a successful restart (so accumulated attempt count survives the
onUninstalled/onActive churn), and only resets it via a new `scheduleModuleStabilityConfirmation` —
mirroring the worker-tier's own stability-confirmation pattern — which checks `tracker
.attemptsInWindow()` is unchanged after `stableUptimeThreshold` before calling `recordSuccess()`.
`stableUptimeThreshold` is now a constructor parameter (`WorkerRuntime.DEFAULT_STABLE_UPTIME_THRESHOLD`
= 10s in production, matching the worker tier exactly), with the existing 8-arg/10-arg constructors
preserved as overloads delegating to the new canonical one — no call site outside `gimle-worker`
needed to change.

**Regression tests** (`WorkerRuntimeTest`, fast/in-process): one proving a never-recovering module
now genuinely exhausts its budget and lands in `ModuleState.FAILED` after exactly 5 restart attempts
(not 6 — the 6th `restartModule()` call finds the budget already spent and never attempts a 6th
cycle); one proving a module that recovers *before* its stability threshold elapses gets a genuinely
fresh budget for its next failure spell, not a continuation of the first — asserted by the *total*
restart-cycle count across both spells exceeding what a single un-reset budget of 5 could produce.

**Also found and fixed a second, unrelated bug in the smoke-test fixture itself while chasing this**:
the first real-cluster run of the new scenario failed identically before *and* after the
`WorkerRuntime` fix, which briefly looked like the fix hadn't taken — cluster logs showed the real
cause instead: `submitDeployment`'s convenience overload hardcodes `version: 1.0.0` in the manifest
it submits, but the new fixture's own descriptor declares `1.0.0-unhealthy` (matching the existing
`-faulty`/`-slow` naming convention `buildFaultyProviderJar`/`buildSlowProviderJar` already use) —
a real moduleId mismatch, NACKed forever with "module not registered", so the module never even
started. Switched to `submitDeploymentWithReplicas`'s version-aware overload, matching how every
other non-default-version fixture in this suite is already submitted.

**Verification**: `WorkerRuntimeTest`/`WorkerProcessSupervisorTest`/`ControlPlaneAgentWorkerIntegrationTest`
(76 tests total across `gimle-worker`+`gimle-agent`) all green after the fix; the new smoke-test
scenario (`SelfHealingIT#a_module_that_never_passes_its_own_liveness_check_exhausts_its_restart_budget_and_fails`)
passed against a real cluster; a full `-Psmoke` run (15 tests, 1 pre-existing skip) passed clean
end to end afterward.

### Scope explicitly not covered this session

Given real time constraints, the following real-cluster scenarios named in the original QA mission
were **not** attempted this session — listed here as open scope for a follow-up, not silently
dropped:

- Rolling update / version-aware traffic cutover, multi-signal autoscaling, single-replica
  rolling-update downtime, quota-at-admission, Raft leader failover under load, and the console's
  Config/Metrics screens *were all* attempted this session — see above for each.
- RBAC/authz edge cases (cross-tenant denial, node-scoped self-service) at the smoke-test tier
  remain untested there (covered at the unit/integration tier, e.g. `ApiServerAuthzTest`) — the
  smoke suite runs the whole cluster in plaintext mode with auth bypassed, so exercising this
  properly needs a second, TLS+auth-enabled cluster variant, a bigger lift than any single scenario
  above and not attempted this session. This is now the only item remaining on the original QA
  mission's real-cluster scope list.

## Verification

Every fix in Phase 1 and every Phase 3 addition was verified with repeated isolated runs (3-20x
depending on the entry) plus checkstyle/spotbugs/fmt, documented per-entry in the commit history on
`qa-hardening`. A final full-reactor `mvn verify` (2-entry exclusion list) passed clean at
`9dadef8`..`fd22baf`; `gimle-smoke-tests -Psmoke` passed 5/5 including the worker-respawn test at
`a93b43f`, and 6/6 including the quota-enforcement test at `b364e3e` (after one full-suite run with
an unrelated shared-cause failure, see above). The `logback.xml` fix and the `RaftNode` phantom-
leader fix were each verified independently: the former via direct XML parsing of every tracked
`*.xml` file in the repo, the latter via a full, clean `mvn -pl gimle-mimir -am verify` (216+ tests,
zero regressions) plus 5 real-cluster smoke runs of the reproduction test that motivated it.
