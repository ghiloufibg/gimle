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

### Real bug found and fixed: classloader leak detection was never actually wired into the real worker

Set out to add a real-cluster smoke test for classloader leak detection (CLAUDE.md's own framing:
"first-class", a `PhantomReference` to a disposed module's loader, reported if it survives a
configurable window) — research before writing the test surfaced that the whole mechanism was dead
in production, not just untested at the real-cluster tier.

**The bug**: `LeakTracker` (`gimle-module`) — the `PhantomReference`/`ReferenceQueue` detector,
its periodic sweep, and the JFR-based `OldObjectSampleCorrelator` retaining-path walk — existed
only inside `gimle-module`'s own unit tests. `WorkerMain` constructed its real `ModuleController`
using the constructor overload whose `onDisposed` callback defaults to a no-op, so `LeakTracker
#track` was never called on a real module disposal. A genuine leak in a real deployed module went
completely undetected and unreported. Every worker JVM the agent spawns already carries the right
JFR launch flag (`path-to-gc-roots=true`) for retaining-path attribution — only the tracker's own
wiring was missing.

**The fix**: `WorkerMain` now constructs a `LeakTracker` (30s detection window) and wires `LeakTracker
#track` as `ModuleController`'s own `onDisposed` callback; a detected leak is logged via slf4j
(module id, survival time, retaining path if attributed), landing in that worker's real
`worker-platform.log` the same way every other platform log line already does.

**New real-cluster smoke test** (`ClassloaderLeakIT`): a module whose `onStart` deliberately leaves
a platform thread running that `onStop` never interrupts — the classic real-world leak bug pattern
— is deployed, then redeployed once to force disposal of the leaking version. A second, unrelated
`TIER_1` anchor module (the real, deliberately-inert `hello-module`) shares the same worker
throughout, needed because `AgentMain` only kills a worker process outright when the instance being
torn down is the *only* one hosted there (Tier 1 density's own survival guarantee) — without that
anchor, the leaking instance's own worker (and the `LeakTracker` living inside it) would be killed
together with it, before the detection window ever had a chance to fire. The test polls the shared
worker's real `PLATFORM` log for `LeakTracker`'s own report line. Two mechanical fixture bugs hit
and fixed along the way: a missing `TestModuleBuilder#dependsOn` (module-path resolution failure at
compile time) and a missing `exports` in the fixture's own `module-info` (the platform couldn't
reflectively instantiate the hooks class across the module boundary without it).

**Verification**: `gimle-worker`/`gimle-agent`'s full test suites green after the wiring change; the
new smoke test passed twice against a real cluster (73.5s, then 39.5s inside a full `-Psmoke` run);
a full `-Psmoke` run (16 tests) passed with only the already-documented `QuotaIT` full-suite flake
(confirmed clean in isolation, unrelated); a full-reactor `mvn verify` passed with only the
already-documented `RaftClusterTlsTest` full-reactor-contention flake (confirmed clean in isolation,
unrelated, and untouched by this change).

### Real bug found and fixed: a persistently-failing remote endpoint's circuit breaker converges back toward its pre-breaker failure rate

Set out to add a real-cluster smoke test for the service fabric's circuit breaker (CLAUDE.md's own
framing: "circuit breaking/outlier ejection at the registry level") — two real bugs surfaced in the
production `CircuitBreaker` itself while building the fixture, plus two fixture-only mistakes along
the way, all confirmed via repeated real-cluster runs rather than assumed from a single failure.

**The bug**: `CircuitBreaker`'s `HALF_OPEN` cooldown was a fixed duration (production default: 5s),
re-applied identically on every re-open. A caller whose own request cadence happens to land on the
same order as that cooldown — not a contrived case; it's exactly what the shipped
`gimle-examples/greeter-consumer`'s own 5s call interval does against the shipped 5s default — sees
the endpoint re-admitted into `HALF_OPEN` on almost every subsequent call, so the observed failure
rate converges back toward the pre-breaker ~50% steady state instead of being suppressed. Confirmed
directly: a first real-cluster run showed a perfectly alternating success/failure pattern sustained
for the test's full 400s budget, never settling into a run of consecutive successes.

**The fix**: `CircuitBreaker` now doubles its effective cooldown on each consecutive re-open (capped
at 16x the base), resetting back to the base on a successful close — the same
`base_ejection_time * ejections_count` shape Envoy's own outlier detection uses, for the same
reason. New `CircuitBreakerTest` cases cover the doubling and the reset-on-close.

**A second, more consequential bug found while chasing why the breaker still never appeared to
open even after the above fix**: the fixture module built to simulate a persistently-broken replica
had its `greet()` implementation `Thread.sleep(60_000)` before throwing, deliberately outlasting
`FabricClient#DEFAULT_TIMEOUT` (5s) so the caller's own read timeout — not an application throw —
is what should trip the breaker (see the third bullet below). That 60-second block, held on every
call inside `BoundedModuleScheduler`'s own concurrency-bounded pool, let several calls pile up
concurrently and destabilized the instance badly enough to repeatedly trigger a real dispose +
redeploy cycle — confirmed directly via the module's own
`Stopping`/`Uninstalled`/`Resolved`/`Starting`/`Active` churn in its `worker-platform.log`, roughly
every 90-110s, entirely unrelated to either `LivenessProbe` or `ReadinessProbe` (both trivially
`true` in the fixture). Each redeploy assigns the module a new `workerId`, and `ServiceEndpoint`
(the `CircuitBreaker` map key) embeds it — so every redeploy silently reset the breaker's own
accumulated failure history back to a fresh, `CLOSED` instance, which is what actually explained the
sustained alternation, not the cooldown-doubling gap the first fix alone addressed. Fixed by
shortening the fixture's sleep to 8s (comfortably past the 5s timeout, without the pileup) — the
fixture never needed a full minute, only to outlast the client's own timeout.

**Two more, smaller issues, both confirmed and fixed along the way**:
- `FabricServiceRegistry#invokeRemote`'s own P2-6 app-error-vs-transport-error split (an already
  existing, deliberate distinction — see that method's own javadoc) scores a method that merely
  *throws* as `breaker.recordSuccess()`, "proof the endpoint was reachable and answered, not a
  transport failure" — so the fixture's first version (throwing immediately, no sleep) could never
  open the breaker no matter how many application-level failures occurred. This is why the fixture
  needs the sleep-then-throw shape in the first place, not a plain throw.
- The test's own log-parsing logic (`ServiceFabricIT#breakerHasExcludedTheBrokenReplica`) treated
  `fetchInstanceLog`'s response body as newline-separated text (`log.lines()`), but `AgentLogServer
  #respondPage` actually returns one compact JSON object (`{"lines":[{...},{...}]}`) with no
  embedded newlines — so `log.lines()` always produced exactly one "line" containing every message
  concatenated together, which made the assertion's own success-counting logic structurally always
  zero regardless of what the breaker actually did underneath. Fixed by parsing the JSON properly
  via `gimle-core`'s existing `Json.parse`/`Json.asObjectList` rather than treating the body as raw
  text.

**A separate real gap found and fixed in the shared smoke-test fixture itself, not specific to this
scenario**: `GreeterSmokeClusterSupport#spawnAgent` never scoped the agent's `-Dgimle.log.root` to
the test's own `@TempDir`, unlike every other process this fixture spawns (store, fafnir, muninn,
control plane) — it silently defaulted to `gimle-logs` relative to the forked test JVM's own CWD
(`gimle-smoke-tests/`'s own module root), the same physical directory across every separate `mvn
verify` invocation in the same checkout. Real log files (keyed only by deployment name/instance
index, with no per-run boundary) accumulated on disk indefinitely across runs — caught directly via
a genuinely corrupted assertion reading 24 stale log lines from an entirely different, earlier test
run mixed into what should have been a fresh instance log. Fixed by passing
`-Dgimle.log.root=<tempDir>/gimle-logs` to the agent, matching the tempDir-scoping convention every
other spawned process already follows; the stale accumulated directories from this session's own
runs were deleted as part of the fix.

**Verification**: `gimle-fabric`'s full test suite (96 tests, including the two new
`CircuitBreakerTest` cases) green after the production fix; the new smoke test
(`ServiceFabricIT#a_circuit_breaker_excludes_a_consistently_failing_replica_after_real_failures`)
passed against a real cluster in both an isolated run (166.1s) and inside a full `-Psmoke` run
(164.6s); a full `-Psmoke` run (17 tests) passed with one unrelated failure —
`QuotaIT` failed with `409 unknown tenantId` in both the full-suite run and a subsequent isolated
re-run, a pre-existing issue in tenant-quota admission timing this session's diff never touches (no
change here reads or writes tenant/quota state); a full-reactor `mvn verify` passed clean (5:19) with
only the two already-standing exclusions
(`RaftClusterTest#a_far_behind_follower_catches_up_via_install_snapshot_not_full_log_replay`,
`WorkerProcessSupervisorTest#backoff_delay_escalates_across_repeated_crashes_then_gives_up`).
`QuotaIT`'s own tenant-admission timing issue is left as open follow-up scope, not fixed here.

### Node cordoning (P1-6): confirmed correct against a real cluster, no bug found

Asked directly whether plaintext-mode real-cluster coverage was complete, a survey turned up four
genuine gaps — not deferred choices, but subsystems nothing at the smoke-test tier had ever
exercised. This is the first: node cordoning, previously proven only via `SchedulerTest`'s pure-unit
fakes and `ApiServerTest`'s in-JVM HTTP round trip, never against a real multi-process cluster.

New `NodeCordoningIT` (single-node topology, no fixture changes needed): deploys `greeter-provider`
and lets it reach `ACTIVE`, cordons the node, then polls for 15s across several real
`DeploymentReconciler` ticks proving the already-running instance is never evicted; submits a
second, distinct deployment while cordoned and polls for 20s proving it never reaches `ACTIVE` (the
sole tier-eligible candidate being cordoned means `Scheduler#place` throws `GimleSchedulingException
#nodeCordoned` on every attempt); uncordons and confirms the pending deployment now places. Also
asserts `GET /nodes`'s own `"cordoned"` field flips at the right points. Two new
`GreeterSmokeClusterSupport` helpers (`cordonNode`/`uncordonNode`/`nodeCordonedIs`) mirror
`putTenantQuota`'s existing shape for a POST-then-poll helper against a non-deployment endpoint.

**Result: the mechanism works exactly as `Scheduler`'s own class javadoc describes, with no
production bug found.** Verified with three isolated real-cluster runs (67.55s, 66.36s, 65.93s —
stable timing, no flakiness), `gimle-controlplane`'s own module suite, a full `-Psmoke` run (18
tests; one unrelated `RollingUpdateIT` failure traced to sandbox-timing contention from many
back-to-back real-cluster runs this session, confirmed clean via an isolated re-run immediately
after), and a full-reactor `mvn verify` (5:05) with only the two already-standing exclusions.

### Tier 1 density packing (P1-1): confirmed correct against a real cluster, no bug found

Second of the four plaintext-mode gaps identified this session: the `MAX_TIER1_DENSITY` cap
(`AgentMain#findReusableTier1Worker`) was previously proven only by `AgentMainTest`'s in-process
fakes, never against real worker JVMs. `ClassloaderLeakIT` already incidentally packs two different
real `TIER_1` modules onto one worker as a side effect of needing a stable anchor, confirming the
reuse mechanism works end to end for two modules — this proves the cap itself.

New `Tier1DensityIT`: deploys four distinct, untenanted `TIER_1` fixture modules (`buildInertTier1ModuleJar`,
a new `GreeterSmokeClusterSupport` helper — module name doubles as package name, parameterized so
five calls produce five genuinely different module identities, since `findReusableTier1Worker`'s
`noModuleConflict` check unconditionally refuses to pack two replicas of the *same* module),
confirms all four collapse onto a single real worker process via a new `findWorkerDescendants`
helper (the existing `findWorkerDescendant`, pluralized to return every match instead of the
first), then deploys a fifth distinct module and confirms it genuinely spawns a second worker
process rather than packing onto the first.

**Result: the density cap works exactly as `AgentMainTest`'s own in-process assertion describes,
with no production bug found.** Verified with three isolated real-cluster runs (61.57s, 69.32s,
71.34s), `gimle-agent`'s own module suite, a full `-Psmoke` run (19 tests; `Tier1DensityIT` itself
passed clean, but two unrelated tests — `QuotaIT` and, this time, `NodeCordoningIT` — failed only in
the full-suite run and both confirmed clean via an immediate isolated re-run, the same
sandbox-timing-contention pattern already documented above for `RollingUpdateIT`), and a
full-reactor `mvn verify` (5:39) with only the two already-standing exclusions.

### Real bug found and fixed: a deployment could permanently deadlock and never roll forward again

Third of the four plaintext-mode gaps identified this session: `ClassloaderLeakIT` proves exactly
one redeploy cycle survives cleanly, and CLAUDE.md's own "mandatory acceptance test"
(redeploy-in-a-loop, flat metaspace) exists only at the `gimle-module` unit tier
(`RedeployLoopFlatMetaspaceTest`/`RedeployLoopDriver`) — repeated redeploys had never run against a
real multi-process cluster. Building the new smoke test surfaced a genuine, serious bug in
`DeploymentReconciler`'s rolling-update state machine, not just a coverage gap.

**The bug**: `handleRollingUpdate`'s condition for clearing an in-flight `rollingIndex` required the
replacement instance's own module version to equal the deployment spec's *live* `moduleId()` — but
`spec` is read fresh on every reconcile tick, so it can already have raced ahead to a newer
submission by the time this check runs (an operator or pipeline submitting a second version bump
before the first migration's readiness was even observed). Once that equality failed to hold for a
given index, it could never hold again for that index, since nothing else advances `spec`. With
`rollingIndex.isPresent()` gating an early return that skips all further mismatch detection, this
permanently deadlocked the deployment — not just the in-flight migration, but every rollout
submitted afterward, forever. Root-caused via `RedeployStabilityIT`'s first real-cluster failure
(timed out waiting for cycle 3 of 8) by reading the `@TempDir(cleanup=NEVER)`-persisted store state
directly off disk: `rolling/<name>.yaml` showed the index still set, `assignments/<name>/0.yaml`
showed the instance already sitting on the version *before* the one actually submitted, and the
node's own heartbeat confirmed that instance had been genuinely `ACTIVE` and ready the whole time —
proving the clear condition, not the redeploy mechanism itself, was stuck.

**The fix**: removed the `current.get().moduleId().equals(spec.moduleId())` clause from the clear
condition, leaving only `current.isPresent() && isReady(current.get())`. `isReady` already
independently confirms the live heartbeat's own observation matches the assignment's *own recorded*
`moduleId` (not the spec's) — the only check "did this specific migration step land" actually
needs. Once cleared, the very next tick's mismatch scan below picks up whatever the spec's current
version is on its own, so rollouts now chain instead of deadlocking on the first one a newer
submission races ahead of.

**Regression test** (`DeploymentReconcilerRollingUpdateTest`, fast/in-process): a fifth test
reproducing the exact race — index 0 migrates v1→v2, becomes ready, but a v3 submission lands
before the next tick clears the rollout — proving the clear still fires for the completed v1→v2
step even though `spec` has already moved on, and that the subsequent v2→v3 migration then starts
and completes cleanly on its own tick, chaining rather than deadlocking. All 4 pre-existing tests in
the file continued to pass unchanged.

**Process note, not a second bug**: the first two real-cluster re-verification attempts after
applying the fix *also* failed, at different, later cycles each time (6 and then 3 of 8) — this
briefly looked like a second, subtler bug (deep investigation went as far as temporary diagnostic
logging inside `handleRollingUpdate` and a defensive `ScheduledExecutorService` hardening change in
`ControlPlaneMain` before the real cause was found). The actual cause was simpler: those
re-verification runs invoked `gimle-smoke-tests` as a separate `-pl` build, which resolves
`gimle-controlplane` from the local Maven repository, not the reactor's freshly compiled classes —
and the fixed jar had only ever been `verify`'d, never `install`'d, so every "re-verification" was
silently re-testing the pre-fix code. Once actually installed, the fix passed cleanly on every
subsequent run. The diagnostic logging and the unrelated `ScheduledExecutorService` hardening were
both reverted before commit, keeping this change scoped to the one confirmed defect.

**Verification**: `DeploymentReconcilerRollingUpdateTest` (5/5) and `gimle-controlplane`'s full
module suite (153/153) both green; `RedeployStabilityIT` passed 9 times in a row against a real
cluster once the fix was properly installed (isolated runs and as part of the full suite alike,
~101-119s each, all 8 redeploy cycles completing every time); a full `-Psmoke` run (12 IT classes,
1 pre-existing skip) passed clean; a full-reactor `mvn verify` (5:42) with only the two
already-standing exclusions passed clean.

### Real bug found and fixed: a node that learned of a peer's death via gossip never said so

Fourth and last of the plaintext-mode real-cluster gaps identified this session: every existing
smoke test runs a single-node topology (`GreeterSmokeClusterSupport#spawnAgent` was called exactly
once, hardcoded `smoke-node-1` with no other seeds), so `GossipMember`'s real membership/failure-
detection machinery had never run across real, separate agent processes before — only proven by
`GossipMemberTest`'s in-process fakes.

**Fixture work**: `spawnAgent` gained `nodeId`/`seedsSpec` parameters (replacing the hardcoded
literals) and its `-Dgimle.log.root=` is now scoped per node id — load-bearing, not cosmetic: a
second or third agent would otherwise interleave its own `agent-platform.log` with the first's.
`SmokeCluster.agentProcess` (singular) became `agentProcesses()` (a `List<Process>` holding just
the one agent `startCluster` itself spawns), with every existing call site
(`ObservabilityIT`/`SelfHealingIT`/`Tier1DensityIT`/`RedeployStabilityIT`) updated to
`.agentProcesses().get(0)`. New `GossipFailureDetectionIT` spawns two *additional* real agents on
top of the normal one-node cluster — three total, deliberately: with only two, `GossipMember`'s
indirect ping-req relay path is never exercised — both seeded off node 1 alone, relying on SWIM's
own full-state anti-entropy sync (P2-8) to converge the complete table, then hard-kills the third
and polls both survivors' own log files for `"member smoke-node-3 is now DEAD"`.

**A real test-construction race, fixed first, not a production bug**: the first attempts failed
with node 2 logging `"its single configured seed ... is unreachable; treating this as a legitimate
empty-cluster start"` — `startCluster` returns the instant node 1's process is *forked*, with no
wait for anything agent-specific (only the control plane's own HTTP port), so spawning node 2/3
immediately after it raced node 1's own gossip listener bind. Fixed by waiting for node 1's own
control-plane registration first (which `AgentMain#main` only sends *after*
`GossipMember#start`/`#join` both return) before any other node tries to seed off it.

**The real bug**: `GossipMember#mergeOne` — the path that adopts a peer's status *learned
secondhand*, via an incoming message's piggyback or an anti-entropy sync, as opposed to detecting
it directly via this node's own probe timeout — updated `members`/`suspectedSince`/`deadSince`
correctly but logged nothing at all, ever. Only `markSuspect`/`markDead` (the *direct-detection*
path) had a log statement. Found via `GossipFailureDetectionIT`'s own repeated real-cluster
failures: node 1 (which happened to directly probe and detect node 3's death) reliably logged
`"member smoke-node-3 is now DEAD"`, but node 2 — which correctly knew about the death internally,
confirmed by temporary diagnostic logging showing a real `mergeOne` adoption of the `DEAD` status —
never produced that line, so the test's own log-based assertion (matching an operator's own
realistic way of checking a cluster's gossip state) timed out waiting for it. Not a convergence
failure and not a flake: the membership table was correct the whole time; only the log trail was
silently incomplete for every node except whichever one happened to detect a failure first.

**The fix**: `mergeOne` now logs `"{self}: member {id} is now {status}"` — the exact same wording
`markSuspect`/`markDead` already use, deliberately, so a single substring search catches a status
change regardless of whether this node detected it directly or learned it from a peer — on a
genuine status transition into SUSPECT or DEAD (guarded by comparing against the previous status,
matching `markSuspect`/`markDead`'s own existing "log actual transitions only" convention; ALIVE
transitions stay silent, same as before).

**Verification**: `gimle-fabric`'s own module suite (88/88, including `GossipMemberTest`'s 11 tests
and `GossipMemberDtlsTest`'s 4 with zero regressions) and `gimle-agent`'s (40/40) both green;
`GossipFailureDetectionIT` passed 3 times in a row against a real cluster once both fixes (the test
race and the production logging gap) were in place, consistently landing in the 4-11s range from
kill to both survivors' own `"is now DEAD"` log line (well inside the 60s-per-node budget, 60s
chosen the same way `ClassloaderLeakIT`'s 90s window was — generous real-sandbox headroom over a
measured, not just theoretical, convergence time); `ObservabilityIT`/`SelfHealingIT` individually
re-verified given they're the two process-killing tests touched by the `agentProcess()` ->
`agentProcesses()` rename; a full `-Psmoke` run (13 IT classes, 1 pre-existing skip) passed clean;
a full-reactor `mvn verify` (5:38) with only the two already-standing exclusions passed clean.

This closes out all four scenarios identified at the start of this session's plaintext-mode
real-cluster QA pass (node cordoning, Tier 1 density packing, repeated-redeploy stability,
gossip/SWIM failure detection): two genuine production bugs found and fixed (the rolling-update
deadlock, this gossip logging gap), two mechanisms (node cordoning, Tier 1 density packing)
confirmed correct with no bug found.

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
  above and not attempted this session.
- Node cordoning, Tier 1 density packing, repeated-redeploy stability, and gossip/SWIM failure
  detection — the four gaps identified partway through this session, once real-cluster coverage was
  audited directly — are now all covered too; see the four entries above this one.
- Two items newly identified, not attempted, while covering repeated-redeploy stability: the full
  remote-metaspace acceptance test CLAUDE.md itself calls "mandatory" (redeploy-in-a-loop, flat
  metaspace) exists only at the `gimle-module` unit tier today (`RedeployLoopFlatMetaspaceTest`)
  — `RedeployStabilityIT` is an explicitly lighter real-cluster substitute, not that test moved up a
  tier, since nothing in this codebase reads a separately-launched worker process's own metaspace
  remotely. And the dead-code JFR per-module attribution (`ThreadNameJfrAttributor`) and metaspace
  gauge (`WorkerMetrics#recordMetaspaceBytes`) remain unwired into `WorkerMain` — new production
  instrumentation, not a test-only change, so out of scope for a QA pass on its own.
- RBAC/authz edge cases above are now the only item remaining on the *original* QA mission's
  real-cluster scope list.

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

## 2026-08-14 — worker crash-respawn fix, workload-kind + worker-observability real-cluster coverage, and a fresh gap sweep

### Real bug found and fixed: a respawned worker never re-established its control channel

While closing the DaemonSet/StatefulSet/Job/CronJob real-cluster coverage gap left open by the
prior session (see "Scope explicitly not covered this session" above),
`StatefulSetPersistenceIT#a_statefulset_instance_keeps_its_sticky_node_and_its_volume_data_across_a_worker_restart`
surfaced a genuine production bug distinct from the worker-tier respawn `GreeterSmokeTestIT`
already covers: after `WorkerProcessSupervisor` respawns a crashed worker JVM, `AgentMain` never
re-ran `InstallModule`/`ResolveModule`/`StartModule` against it -- the fresh process sat idle while
`SupervisedInstance.lifecycleState` stayed permanently stale at whatever it was before the crash.
`SelfHealingIT` never caught this because it only asserts on that same stale field.

**The fix**: `WorkerProcessSupervisor` gained an `onRespawned` callback, fired once a respawn's
`spawn()` call returns successfully (outside its own monitor lock, since the caller's handshake
does a blocking `accept()`). `AgentMain` wires this to reset every `SupervisedInstance` the crashed
worker hosted (all of them under Tier 1 density, sharing one `ControlChannelServer`) back to its
pre-connection state and re-accept the connection, redriving the install sequence exactly as a
worker's first start does. `instance.volumeHandle` is deliberately left untouched -- re-allocating
it resolves to the same on-disk directory anyway (`LocalDiskVolumeManager#allocate` is idempotent).

**Verification**: `StatefulSetPersistenceIT` passes; `SelfHealingIT` re-verified with no
regression, now passing for the right reason; `gimle-agent`'s full unit suite green, via a
backward-compatible constructor overload so no existing test call site needed a signature change.

### Real-cluster coverage added: DaemonSet, StatefulSet, Job/CronJob, and worker-tier metrics/traces

Four new `gimle-smoke-tests` `*IT` classes close the "unit/reconciler-tested but never run against
a real multi-process cluster" gap for every workload kind added since the last QA pass, plus the
worker-to-agent-to-Muninn observability relay:

- `JobLifecycleIT` -- a real worker JVM runs `JobHooks#run` to completion and `JobReconciler`
  observes the real `lifecycleState: COMPLETED` heartbeat; a triggered CronJob generates a real,
  independently-listed Job the same way.
- `DaemonSetLifecycleIT` -- per-node placement against a real agent.
- `StatefulSetPersistenceIT` -- sticky placement and volume data survive a real worker restart
  (see the bug above, found by this test).
- `WorkerObservabilityIT` -- a real deployed module's own request counter and the real span its
  fabric call produces both travel worker JVM -> agent -> Muninn and read back through
  `/metrics-history/WORKER/*` and `/traces-history/WORKER/*`, closing the gap that
  `AgentMuninnShippingTest` only proved this relay against a stub Muninn and a hand-driven raw
  worker socket, never a real worker JVM.

`GimleCliTest` also gained coverage for the `job`/`cronjob`/`daemonset`/`statefulset` CLI commands,
previously entirely untested (0 -> 9 new tests).

### Updated remaining scope

The one item the prior session left open -- RBAC/authz edge cases (cross-tenant denial, node-scoped
self-service) at the smoke-test tier, needing a TLS+auth-enabled cluster variant -- is still open;
`gimle-holmgang`'s `mtls.feature` (added since, see below) only tests an anonymous client being
rejected at authentication, not an authenticated tenant's cross-tenant access, and its topology
model has no role/role-binding seed to build one on.

A fresh gap sweep across the ~90 commits that landed between the prior session and this one --
admission chain/`PolicyConfigPlugin`, `maxUnavailable`/`maxSurge` disruption budgets and surge-in-
place retargeting, weighted multi-metric autoscaling, opt-in audit-on-READ, and the entire new
`gimle-holmgang` topology-driven Gherkin/Heimdall functional-validation module (`-Pvalidation`) --
found the following still open, ranked by value:

1. **Pure-surge rollout (`maxUnavailable: 0` + nonzero `maxSurge`) is unproven.** The fix that
   unblocked this shape (`d0c83fd`) landed with three manifest-*parsing* unit tests only; no
   reconciler test, unit or real-cluster, has ever actually run a rollout with `maxUnavailable: 0`.
2. **Machine-tier reschedule after node death has no real-cluster proof.** The fix for a dead node
   staying a placement candidate (`3ca6907`) landed with a unit test only. This is the one of the
   platform's three self-healing tiers (module dispose+reinstantiate, worker respawn, machine-level
   reschedule) still unproven against a real cluster -- `GossipFailureDetectionIT` stops at DEAD
   convergence, never asserting the dead node's instances actually land on a survivor.
3. **Agent secret-delivery independence (`b52735e`) has no test at all**, at any tier -- its trigger
   (a denied `/config` read must not block Fafnir secret delivery) only occurs on an authz-enabled
   cluster, which nothing real-cluster runs today.
4. **`PolicyConfigPlugin` admission rejection is untested outside its own unit test.** It's live on
   the real `PUT /deployments` path; no real submission has ever actually been rejected -- or
   allowed -- by a configured policy. `gimle-holmgang`'s `ClusterApi#putConfig` and its existing
   `quota-and-admission.feature` shape already provide the harness to close this cheaply.
5. **READ-decision audit opt-in and the console's Access Control screen** share the same missing
   prerequisite as the RBAC/authz gap above (an auth-enabled real cluster): both are validated only
   in-process (`ApiServerAuthzTest`) or against a mock fixture, never against a real backend.

Not gaps, for contrast: weighted multi-metric autoscaling (`AutoscaleIT`), the TCP delayed-ACK fix
(`StoreRpcLatencyTest`), and surge retargeting plus the rolling-update disruption-budget floor
(`SurgePromotionIT`, `RollingUpdateIT`) all already have real regression coverage.
`gimle-holmgang` itself is additive, not redundant with `gimle-smoke-tests` -- it covers
partition-tolerance and live membership-change scenarios nothing else touches.

## 2026-08-24 — end-user application deployment QA (real distribution artifact, real apps)

A different kind of QA pass from every prior session on this file: not authoring new automated
tests, but acting as a genuine end user of the platform's own `gimle-dist` distribution tarball —
`mvn -pl gimle-dist -am install` once, then everything else through `bin/hilmir`/`bin/gimle` off
the unpacked archive, never `mvn gimle:*` dev-loop goals — and trying to get real, unmodified
third-party applications running on it, the way someone evaluating the platform actually would.
Three apps, in increasing order of "not written for Gimlé": `hello-module`/`greeter-provider`/
`greeter-consumer` (sanity baseline, already proven elsewhere), `gimle-examples/orders-platform`
(a real Spring-DI multi-service app already checked into this repo as a hand-built manual-QA
fixture, but — per its own README before this session — never actually built or run against real
JDK 25 or a real cluster), and the actual upstream Spring PetClinic
(`spring-projects/spring-petclinic`, cloned and built completely unmodified). No source changes
were made to any of the three apps themselves; two real, confirmed platform bugs were found and one
was fixed, described below.

### Bug 1 (HIGH, FIXED): Vessel hosting is completely non-functional over the real HTTP wire

Vessel hosting (`vessel:` block on a Deployment manifest — run an arbitrary runnable jar as its own
OS process, no `ModuleLayer`, no `gimle-module.yaml`) is the platform's own documented, tooling-
recommended answer for "I have an existing non-modular jar" — `hilmir doctor <jar>` even says so
explicitly ("run 'hilmir doctor --vessel' to evaluate it as a vessel instead" when module-hosting
is rejected). It is fully implemented and unit-tested at the type level
(`VesselSpec`/`VesselEnvValue`/`VesselProbeSpec`, `DeploymentManifestParserTest`'s own
`parses_a_full_vessel_block`), but nothing had ever exercised it through a real control-plane ->
agent HTTP round trip before this session — and it was completely broken there.

**Repro**: built the real upstream Spring PetClinic (`git clone --depth 1
https://github.com/spring-projects/spring-petclinic`, `./mvnw package`, zero code changes), ran
`hilmir doctor` against the resulting fat jar:

- Module-hosting mode (default): correctly rejected — `[ERROR] NOT_LAYER_HOSTABLE: artifact looks
  like a launcher archive ... run 'hilmir doctor --vessel' to evaluate it as a vessel instead`,
  plus `[ERROR] CALLS_SYSTEM_EXIT` for Spring Boot's own `JarModeRunner`.
- `--vessel` mode: correctly downgrades both to `WARNING`, plus an `[INFO] NOT_LAYER_HOSTABLE`
  explaining vessel-hosting is exactly what a launcher archive expects. Genuinely good, actionable
  UX — this diagnosis is exactly right and exactly what an end user needs to hear.

Followed that advice: deployed it with a real `vessel:` block (`SERVER_PORT: {port: dynamic}`, tcp
liveness/readiness probes, real resource request/limit) against a real `hilmir`-launched cluster.
Result: an unconditional crash loop, one failure every ~5 seconds indefinitely (9 in a row observed
over 45s, zero backoff — see Bug 3 below), every single one identical:

```
failed to start instance petclinic-vessel#0: module artifact is not a real JPMS module
(no module-info.class); automatic modules are rejected: .../spring-petclinic-4.0.0-SNAPSHOT.jar
```

That is the *module*-hosting error path (`ModuleArtifactReader.read`, `AgentMain.java:1186`) firing
on a manifest that unambiguously declared `vessel:` — and `GET /deployments/petclinic-vessel`
confirmed the vessel block really did round-trip correctly through the control plane and
`gimle-mimir` (present, fully populated, in the stored `DeploymentSpec`). The break is exactly one
hop later: `AgentMain#reconcileAssignments` does branch correctly on `assigned.vessel().isPresent()`
(`AgentMain.java:1130`) — but the JSON the agent actually receives from `GET
/nodes/{id}/assignments` never carries a `vessel` key in the first place.
`ApiServer#assignedInstanceToJson` (`gimle-controlplane/.../api/ApiServer.java:3150`) builds that
JSON and serializes `deploymentName`/`instanceIndex`/`moduleId`/`artifactPath`/`tenantId`/
`renamedFromInstanceIndex`/`configMapRefs`/`secretMapRefs` — but never `instance.vessel()`, despite
a sibling encoder (`vesselToJson`, used by every read-side `GET /deployments` etc. response) already
existing in the same file. `AgentMain#fetchAssignments` (`gimle-agent/.../AgentMain.java:858`)
compounds it: it hardcodes `Optional.empty()` for the vessel field of every `AssignedInstance` it
builds from that response, regardless of what the JSON actually contains. So every vessel-flagged
assignment reaches the agent looking exactly like a module assignment, forever — this is not a
timing or ordering bug, it cannot ever succeed. **Vessel hosting has no working end-to-end path
through the real distribution today**, despite being fully implemented, exposed by the CLI/API, and
explicitly recommended by `hilmir doctor`'s own output.

**Fixed** in both places: `assignedInstanceToJson` now emits `instance.vessel()` via the existing
`vesselToJson` encoder; `fetchAssignments` gained the inverse decode (`parseVessel` and its
`VesselEnvValue`/`VesselProbeSpec` helpers, mirroring `vesselToJson`'s shape exactly). Rebuilt
`gimle-dist`, tore down and relaunched a fresh cluster, redeployed the identical manifest: real
vessel process spawned (`VesselProcessSupervisor` — "spawned vessel petclinic-vessel#0 as pid
..."), real dynamic port allocated, and the real, completely unmodified upstream PetClinic served
its actual UI (`<title>PetClinic :: a Spring Framework demonstration</title>`) and its real
DB-backed owners list (`George Franklin` et al.) over HTTP. Re-verified no regression against the
`orders-platform` app and the `hello-module`/greeter pair on the same rebuilt binaries — all six
deployments `HEALTHY` together. No manifest, docs, or wire-format change; this is a pure bug fix
inside two already-existing JSON encode/decode functions.

### Bug 2 (HIGH, confirmed, not yet fixed): `gimle get deployments`'s health column never looks at whether anything is actually running

While Bug 1 was crash-looping every 5 seconds with zero successful starts, `gimle get deployments`
reported it as `1/1 HEALTHY` the entire time — not once, across the whole repro window. Root cause
is generic, not vessel-specific: `DeploymentsCommand.healthOf` (`gimle-cli/.../DeploymentsCommand.
java:146`) computes the `health` column purely from `unplacedCount`/`quotaViolating`/
`limitRangeViolating` — it never reads any instance's own `lifecycleState`, `alive`, or `ready`
observation at all. A *placed* instance (the scheduler assigned it a node, so `unplacedCount == 0`)
that has never once successfully started reads identically to a genuinely healthy one. The same gap
would just as easily hide a crash-looping *module* (a throwing `onStart` hook, say) — this isn't
specific to vessels or to Bug 1's own trigger, it is a property of the health column itself. Given
this repo's own explicit framing of a durable, accurate operator-facing status as core to the
platform's pitch (see `PRODUCTION_HARDENING_BACKLOG.md`'s own Kubernetes/Nomad events comparisons),
this is worth prioritizing above most items already tracked there. Not fixed this session — flagged
here with an exact repro and root cause for a following one; the natural fix folds each instance's
own worst observed `lifecycleState`/`alive` into `healthOf`, the same way `unplacedCount` already
does for placement.

### Bug 3 (MEDIUM, confirmed, not yet fixed): `gimle logs` can't find a StatefulSet/DaemonSet/Job instance's placement at all

`gimle logs instance/inventory-service-statefulset/0` against a real, `ACTIVE`, `ready: true`
StatefulSet instance (confirmed via `gimle get statefulsets`, and its real log file present on disk
with real reconciliation lines) fails outright: `error: not found: no placement found for
inventory-service-statefulset#0`. Root cause: `ApiServer#handleInstanceLogsProxy`
(`ApiServer.java:4691`) resolves the owning node exclusively via `storeClient.listAssignmentsFor
(deploymentName)` (`ApiServer.java:4720`) — a lookup that is populated only by
`DeploymentReconciler`'s own bookkeeping (confirmed: every other caller of `listAssignmentsFor` in
the codebase is Deployment/autoscaler/service-endpoint machinery). StatefulSet, DaemonSet, and Job
placements live in entirely separate assignment lists (`listStatefulSetAssignments`/
`listDaemonSetAssignments`/`listJobRuns`, as already used by `handleStatefulSet`/the DaemonSet and
Job status handlers elsewhere in the same file) that this one proxy path never consults. Net effect:
`gimle logs`/the console's own Logs screen (same backend) cannot tail an instance's application log
for 3 of the platform's 5 workload kinds — only Deployment-owned instances work. Not fixed this
session; the fix is the same shape as the `/endpoints/{name}` handler already a few hundred lines
away in the same file (try each kind's own assignment list in turn until one resolves a node).

### Friction: relative `artifactPath` silently breaks the moment a cluster isn't launched from the repo root, with zero surfaced diagnostic

Every example manifest in this repo (`hello-module/deployment.yaml`, `greeter-*/deployment.yaml`,
all of `orders-platform`) declares a repo-root-relative `artifactPath`
(`gimle-examples/hello-module/target/hello-module-0.1.0-alpha.2.jar`). That only resolves correctly
because every documented dev-loop path (`mvn gimle:controlplane`, `scripts/run-local-cluster.sh`)
happens to launch `ControlPlaneMain` with the repo root as its cwd. The real distribution-artifact
path this session used — `hilmir up`, exactly as an operator or evaluator would actually run it —
does not: the control plane's cwd is wherever `hilmir up` itself was invoked from (a deploy/ops
directory, not a source checkout), so every one of these example manifests fails to place, silently,
the first time. `gimle apply` reports `deployment/hello-deployment applied` regardless — nothing
about that response hints at trouble. The deployment then sits at `UNPLACED(1)` forever, and
`gimle events hello-deployment 0` returns `No resources found` — the reconciler's own
`DeploymentReconciler` WARN (`deployment hello-deployment references an unreadable artifact ...:
module artifact not found: ...`) fires every 2s but is never recorded as a durable instance/
deployment event, only a line in the control plane's own structured platform log file
(`gimle-data/controlplane-0-logs/controlplane-platform.log`), which nothing in the CLI surfaces.
Switching to an absolute path fixed it immediately. Two independent, cheap improvements worth
making here: (1) record this specific reconciler WARN as a durable event so `gimle events` actually
shows it — this repo already treats a durable, queryable event log as a first-class design goal;
(2) either resolve `artifactPath` against something well-defined regardless of the control plane's
own cwd, or have every example manifest's own doc comment call out that the path is
control-plane-process-cwd-relative, since that is genuinely surprising and this session is exactly
the "real cluster, real distribution artifact" scenario where it bites hardest. (Andvari's own
coordinate-only `artifactPath: ""` + artifact-registry push flow sidesteps this entirely and is
almost certainly the right steady-state answer — worth calling out more prominently in the example
manifests and the getting-started docs as the recommended path outside a single-machine dev loop.)

### Friction: a stale `JAVA_HOME` produces a bare, unhelpful `UnsupportedClassVersionError`

`bin/hilmir`/`bin/gimle`'s own documented Java-selection precedence (an explicit `JAVA_HOME` always
wins) is reasonable, but on a machine with more than one JDK installed and an already-set
`JAVA_HOME` pointing at an older one (this sandbox's own default was JDK 21) — a completely
ordinary real-world situation — the failure is `Error: LinkageError ... class file version 69.0,
this version of the Java Runtime only recognizes class file versions up to 65.0`. Nothing in that
message says "Gimlé requires JDK 25+", names the actual JDK found, or hints at checking `JAVA_HOME`
specifically (as opposed to `PATH`, which was in fact already correct). A one-line class-file-version
sanity check in each wrapper script before dispatching to `java` — or even just echoing the resolved
`java_bin`'s own `-version` output on this specific failure — would turn a genuinely confusing first
five minutes into an immediately obvious fix. **Fixed**: `bin/gimle`/`bin/hilmir` (and their `.cmd`
counterparts) now check, before dispatching, both that an explicit `JAVA_HOME`'s `bin/java` actually
exists and is executable, and that it reports major version 25+ — printing a one-line explanation
naming `JAVA_HOME`'s value and the resolved `java`'s own reported version, and exiting immediately,
instead of launching into a bare classfile-version error. The version check is scoped to the explicit
`JAVA_HOME` branch only (the bundled-JRE and `PATH` fallbacks are either guaranteed correct by the
archive's own build or already covered by this friction item's own narrower repro), and parses past
any `JAVA_TOOL_OPTIONS`/`_JAVA_OPTIONS` "Picked up ..." diagnostic noise the JVM may print ahead of
its actual version line — a real edge case caught while testing this fix (this very session's own
proxy environment sets `JAVA_TOOL_OPTIONS`, exposing it immediately rather than latently).

### Friction: a process's stdout launch log and its own structured platform log live at two different paths

`hilmir up` redirects each spawned process's raw stdout/stderr to `gimle-data/<role>-<id>.log`
(a launch banner plus anything not routed through SLF4J), while that same process's own
`-Dgimle.log.root` points at a sibling `gimle-data/<role>-<id>-logs/<role>-platform.log` (JSON-lines,
everything real). Diagnosing anything beyond "is the process alive" means knowing to check both, in
two different formats, for every one of the four+ processes a cluster comprises — not fatal, but a
real speed bump the first time. **Documented**: `hilmir-reference.md`'s `dataRoot` section now
spells out both paths explicitly, right next to the existing `dataRoot`/`-logs` table row — a
docs-only fix; consolidating the two into one location/format would be a real behavioral change
outside this pass's scope.

### Positive: `orders-platform` — a real Spring-DI multi-service app — now verified end to end for the first time

`gimle-examples/orders-platform` (`orders-service`, `inventory-service`, `orders-report-job`,
`web-ui`; see the module's own README) was, before this session, explicitly documented as never
having been built against real JDK 25 or run against a real cluster at all (its README's own "What
was, and wasn't, verified" section said so outright). This session did both, for real: `mvn -f
gimle-examples/orders-platform/pom.xml package` built cleanly on JDK 25 with zero changes, and all
four modules — one of each of Deployment, StatefulSet, and Job kinds tried directly (DaemonSet/
CronJob reuse the same jars under alternate manifests, not separately re-tried) — reached `ACTIVE`/
`HEALTHY` against a real `hilmir`-launched cluster. Verified working end to end: real Spring
`AnnotationConfigApplicationContext` DI and bean wiring; a real cross-worker fabric call
(`inventory-service` -> `orders-service`'s `OrderCatalog`, self-healing past a transient
"not registered yet" race exactly like `greeter-consumer` already proves); a real tenant-scoped
Fafnir secret gating `web-ui`'s `POST /api/orders` (`401` with no/wrong `X-Admin-Token`, `200` with
the real one); a real `Service` + `GET /services/{name}/endpoints` resolution; and a real `Job` run
producing a correct consolidated report reflecting orders placed through the live web UI. See the
doc-sync update to `gimle-examples/orders-platform/README.md`'s own verification section made
alongside this entry.

### Scope not covered this session

The web console (`/console`) was confirmed reachable and serving its built SPA (`200`, real
`index.html`) — the same read APIs exercised via the CLI above back it — but no interactive,
browser-driven pass was performed. Quarkus was not built or deployed (network/time budget); given
`hilmir doctor`'s hazard catalog is generic bytecode/structural analysis (launcher-archive layout,
`System.exit`, bundled logging bindings), a Quarkus fast-jar (`quarkus-run.jar` plus its sibling
`lib/`/`app/`/`quarkus/` directories) would almost certainly hit the identical `NOT_LAYER_HOSTABLE`
module-mode rejection and the identical vessel-mode recommendation Spring Boot's own launcher
archive did — noted as a reasoned inference, not a result, since it wasn't actually run.

## 2026-08-24 — end-user QA round 2: five parallel real-cluster sessions via Workflow, deduplicated

A different shape from every prior session on this file: the round above ran solo; this one ran
five independent end-user QA sessions concurrently via the `Workflow` tool, each against its own
isolated `hilmir`-launched cluster (distinct ports, distinct working directory, same already-built
`gimle-dist` binaries with the vessel-hosting fix baked in), then a sixth agent deduplicated all six
sessions' write-ups (the round above plus these five) into one findings set. No source changes were
made by the five round agents themselves — by design, to avoid five agents concurrently editing the
same files — but the three real bugs below were confirmed by hand afterward and are recorded here
un-fixed, the same way round 1 recorded its own un-fixed bugs. Full narrative detail and repro
transcripts for all 29 deduplicated findings (plus four annotated console screenshots) went to a
published report artifact for this session; what follows is the durable written record.

### Bug 4 (HIGH, FIXED): the rolling-update reconciler ignores `artifactPath`/`artifactSha256` — a same-version artifact swap is silently never rolled out

`DeploymentReconciler`'s rolling-migration logic decides which running instances need replacing
using only `!assignment.moduleId().equals(spec.moduleId())` — it never compares `artifactPath` or
`artifactSha256`. Admission's own `deploymentContentChanged` (which decides whether to mint a new
`ControllerRevision`) does treat a changed `artifactPath`/`artifactSha256` as a real content change.
So re-applying a `Deployment` with the same `moduleId`+version but a different `artifactPath` — a
realistic dev-iteration workflow, patching a jar without bumping semver — is admitted, a new
revision is minted, and `gimle get deployments`/`deployment revisions` both show the new path as
current, but the running instances keep executing the old jar bytes indefinitely. Nothing in the
CLI reveals the mismatch short of manually diffing `artifactPath` between `get deployments` and
`get node-assignments`. **Fixed**: `mismatchedAssignments` now also compares `assignment.artifactPath()` against
`spec.artifactPath()` (not `artifactSha256` — `InstanceAssignment` doesn't carry it, and a same-path
swap stays `validateArtifact`'s own separate concern), matching admission's own definition of
"changed." Two new regression tests cover it (`DeploymentReconcilerRollingUpdateTest`,
`DeploymentReconcilerTest`).

### Bug 5 (HIGH, FIXED): a stuck rolling-migration index permanently exhausts `maxUnavailable` and wedges the deployment forever, immune even to rollback

When a rolling migration's replacement instance genuinely fails to install (reproduced with a real
module-version mismatch between the manifest's declared version and the artifact's own bundled
`gimle-module.yaml` — never validated at admission, only discovered at worker install time via
NACK) and `HealthReconciler` exhausts its restart budget and gives up, the persisted rolling-migration
bookkeeping for that index (`store/rolling/<deployment>/<index>.yaml`) is never cleared — no
`RemoveRollingIndex` is proposed for a "gave up" outcome, only for the normal success/shrink paths.
`handleRollingUpdate`'s very first check is `if (inFlight.size() >= maxUnavailable) return;`, so the
one stuck index permanently consumes the whole budget (default `maxUnavailable: 1`) and every future
reconcile tick for that deployment exits immediately — including `deployment rollback --to-revision
N` to a known-good earlier revision, which is recorded as a new revision but never actually acted
on; the stuck instance sits untouched. Directly compounded by Bug 2 (see round 1 above): the
deployment reports fully `HEALTHY` throughout, and the give-up event itself is never durably
recorded (`gimle events` shows the earlier RESOLVED/STARTING/ACTIVE transitions, never the give-up).
**Fixed**: `handleRollingUpdate`'s (and `handleSurge`'s, the mirror-image surge budget) in-flight
loop now also clears an index whose `ReconcilerInstanceState` is `permanentlyFailed`, freeing the
budget in the same tick a pending rollback/rollforward can pick it up — the same continuous-top-up
behavior every other budget-freeing path in that class already has. `HealthReconciler`'s own
give-up branch now also posts a durable `TRANSITION_FAILED` event, closing the other half of this
finding (previously invisible in `gimle events`). A new regression test drives both reconcilers
together (`a_permanently_failed_in_flight_index_frees_the_rolling_budget_instead_of_wedging_forever`).
The deeper ask — surface a stuck/given-up instance in `gimle get deployments`'s own health column —
is the separate, still partially-open item below (health column now flags an explicit `alive: false`
instance, not yet a `permanentlyFailed` one specifically).

### Bug 6 (HIGH, confirmed, not fixed — documented): a coordinate-only (Andvari-pulled) vessel deployment of a real Quarkus fast-jar crash-loops forever — Andvari's one-file-per-coordinate model has no way to carry a multi-file artifact

A vessel deployment's `artifactPath`, left blank, resolves through Andvari exactly the way a
module deployment's does: `ArtifactPullCache` downloads and caches exactly one file per coordinate.
Correct and sufficient for a module-hosted jar or a genuinely self-contained fat jar (confirmed
working for Spring Boot in round 1). But a real, freshly-built Quarkus 3.15.1 app's default `mvn
package` output is a fast-jar: `quarkus-run.jar` is a tiny (693-byte) bootstrap stub whose own
manifest `Class-Path` references sibling `../lib/main/*.jar`, `../lib/boot/*.jar`, `../app/*.jar`,
and `../quarkus/*.jar` entries that must sit next to it on disk. Andvari's registry has no notion of
a multi-file artifact, so none of those siblings are ever pushed or pulled — the instance is placed
and repeatedly respawned (`Could not find or load main class
io.quarkus.bootstrap.runner.QuarkusEntryPoint`) but can never actually start. The identical app
deploys and runs correctly via a local `artifactPath` pointing straight at the original build output
with siblings intact — this is specifically a coordinate-only/registry-pull gap, not a Quarkus/vessel
incompatibility in general. Compounding discovery: `gimle artifact push` itself refuses to push a
non-modular jar at all (`module artifact is not a real JPMS module (no module-info.class);
automatic modules are rejected`) — true for essentially every real-world Spring Boot/Quarkus/plain
launcher jar — so the only way to even get the jar into the registry for this repro was a raw HTTP
`PUT` against the control plane's `/artifacts/*` proxy, bypassing the CLI entirely; tracked as its
own MEDIUM finding below. Fix: either document plainly that coordinate-only vessel deployment only
supports single-jar-shaped artifacts, or extend Andvari's artifact model to support a multi-entry
artifact (e.g. push/pull the whole `quarkus-app/` directory as one tar/zip unit, unpacked atomically
on resolve). **Documented** (the first option) in `manifest-schema.md`'s own vessel section — the
actual multi-entry-artifact fix is real feature work, deliberately out of scope for this pass.

### Round 1 findings re-confirmed, with new detail

- **Bug 2** (`gimle get deployments`'s health column ignores actual instance state) was
  independently hit by two of the five new sessions through unrelated mechanisms — the Quarkus
  coordinate-pull crash loop above, and (more consequentially) Bug 5's stuck rolling migration,
  which stays `HEALTHY` forever with no CLI/API surface, not even `gimle events`, ever revealing the
  wedge. Confirms this is a systemic blind spot, not tied to vessels or to round 1's own trigger.
  **Fixed**: `unhealthyInstanceCount` now also flags `UNHEALTHY(n)` for an instance whose own
  observation reports `lifecycleState: "FAILED"`, mirroring the exact definition
  `HealthReconciler#isHealthy` already uses server-side (`FAILED` is treated as an unconditional
  failure there too, not a transient starting state, so reusing that definition client-side carries
  no new false-positive risk). Bug 5's own stuck-instance scenario specifically (an agent-reported
  `FAILED` lifecycleState with `alive: true`) is now caught by this column, closing the gap left
  after the first pass. A new regression test covers it
  (`DeploymentsCommandTest#the_health_column_reports_unhealthy_for_a_failed_but_still_alive_instance`).
- **Bug 3** (`gimle logs` can't resolve a StatefulSet/DaemonSet/Job instance's placement) was
  independently reproduced for a real CronJob-triggered `Job` run this time, confirming the gap
  spans all three non-Deployment workload kinds it was already suspected to. **Fixed**:
  `handleInstanceLogsProxy` now tries each workload kind's own assignment list in turn (the same
  shape `/endpoints/{name}` already used), covering Deployment, StatefulSet, DaemonSet, and Job.
  Three new regression tests cover it (`ApiServerLogsFallbackTest`).
- The relative-`artifactPath` friction item was not independently re-hit (every new session used
  absolute paths, per its own briefing) — it stands as previously reported, not newly confirmed, and
  remains open (a real fix is invasive; see below). The stale-`JAVA_HOME` error and the split
  stdout/platform log locations are both **Fixed**, see the "Friction" entries above/below for detail.
- `orders-platform` continued to verify cleanly under two more sessions, now including its CronJob
  path (manually triggered, real fabric-call numbers in the report) and its secret-gated web UI
  redeployed under a fresh tenant.

### New MEDIUM findings

- **Plaintext-mode RBAC is fully creatable but silently inert.** `gimle set role`/`set account`/`set
  rolebinding` all succeed and are durably stored in plaintext transport (the default), but have no
  authorization effect: `ApiServer.requireAuthorized` opens with `if (!(exchange instanceof
  HttpsExchange)) return true;`, short-circuiting before any principal or role is ever consulted.
  This matches documented design (`authn-authz.md`: plaintext is "fully open, no identity, no
  enforcement"), so it is not a bug — but nothing in the CLI, a log line, or help text tells an
  operator who just configured a restrictive role in a plaintext cluster that it is decorative.
  **Fixed**: `set role`/`set account`/`set rolebinding` now print a one-line
  `note: RBAC has no effect in plaintext mode` after a successful write, in plaintext mode only.
- **The audit log is completely empty in plaintext mode.** `gimle audit list`/`GET /audit` returned
  zero entries after a full session of real mutations (tenant create/delete, secret set/delete/
  `--destroy`, config/configmap set, RBAC object creation, two real deployments). Root cause: both
  `ApiServer.requireAuthorized` and `FafnirServer.authorizeSecrets` gate their `AppendAuditEvent`
  proposal behind the identical plaintext short-circuit that also disables authorization, so the
  audit-recording code is never reached for any resource kind in plaintext mode — contradicting a
  natural reading of the docs' description of Fafnir's own secret audit as firing "unconditionally
  for every verb" (true only with respect to verb, silently false with respect to transport mode;
  the plaintext carve-out is documented only in a separate RBAC section, not cross-referenced from
  the audit-logging section). **Fixed**: both `requireAuthorized` and `authorizeSecrets` now audit a
  plaintext WRITE/DELETE (and opted-in READ) under a synthetic `anonymous` principal, the same one
  the console's own session endpoint already reports for this mode. A new regression test covers it
  (`ApiServerTest#a_plaintext_write_is_still_recorded_in_the_durable_audit_trail`).
- **`gimle artifact push` has no way to push a plain vessel/launcher jar** — see Bug 6 above.
  **Fixed**: `artifact push --vessel --name <moduleId> --version <version>` pushes a jar with no
  `gimle-module.yaml` under an explicitly given coordinate, skipping the module-descriptor read
  entirely.

### New LOW/INFO findings

- `gimle <resource> --help` fails with "no control-plane server configured" instead of printing
  usage, since `--server`/`GIMLE_SERVER` is required before help-flag handling runs — an operator
  can't get in-CLI help for a command group without already having a cluster address in hand.
  **Fixed**: `-h`/`--help` anywhere on the command line now prints usage before the server check.
- `gimle set limitrange <tenant> --max-limit-memory 32Mi` (one side of a memory/cpu pair only) fails
  with the misleadingly generic `invalid request: cpu must not be blank` rather than explaining the
  pairing requirement or naming the missing flag. **Fixed**: `putBoundIfPresent` now validates the
  pairing client-side, naming both the missing flag and the one given, before ever sending the
  request.
- A default `Deployment` manifest (no `placement:` block) does **not** spread replicas across nodes
  even with a healthy second node available — `PlacementConstraints.NONE`'s `antiAffinityAcrossNodes`
  defaults to `false`, and only `placement: {antiAffinity: true}` turns on cross-node spreading. This
  is real, working, opt-in behavior, but reads as contradicting a literal reading of this file's own
  architecture summary ("replicas of one module must not share a worker JVM") — worth tightening
  that phrasing or reconsidering the default. **Documented**: `CLAUDE.md`'s own architecture summary
  now states cross-node anti-affinity is opt-in; the default itself is left unchanged (a behavior
  change here has real density/placement tradeoffs beyond this pass's scope).
- The console's Metrics/Traces screens each fire one `console.error` for the expected 404 when
  Muninn isn't configured, even though the page already shows a clear inline message — harmless, but
  will flag on automated console-error monitoring for a common, supported configuration. **Fixed**:
  the first 404 either screen's history fetch sees is now remembered for the session (a shared
  `historyAvailability` helper both HTTP repositories go through), short-circuiting every later
  fetch on either screen with no change to what renders.
- Hard-killing the node agent leaves its child worker JVMs running as orphans, invisible to
  `hilmir`'s own run ledger (`hilmir down` correctly detects the agent is gone but has no way to find
  workers it didn't spawn directly) — they had to be found and killed manually. Not fixed this
  pass — a worker self-terminating on prolonged loss of its parent agent's control-channel
  connection is a real design change, deliberately deferred rather than rushed.

### New positives

Two-agent-per-machine topologies work directly via `hilmir`'s declarative `agents:` list (no manual
`AgentMain` launch needed); worker-tier self-healing (`kill -9` a `WorkerMain`, agent respawns a
genuinely new process) verified correctly end-to-end on the real distribution binaries; node
cordon/uncordon correctly gates new placement without touching already-placed instances; the web
console renders real, accurate live data matching the CLI on every screen with live per-instance log
tailing and a working "New deployment" form, submitting real deployments the same way `gimle apply`
does; the full secrets lifecycle (versioning, soft-delete, `--destroy`, re-set-after-destroy)
behaves exactly as documented; Muninn's logs/metrics fallback was proven for the first time against
the real distribution binaries (not just `gimle-smoke-tests`) surviving a hard node-agent kill; and
quota/limitrange enforcement is correctly dual-mode — a fresh over-quota/over-limit submission is
rejected outright at admission (contrary to a literal reading of this file's own "flag, never evict"
phrasing, which correctly describes only the narrower case of a quota retroactively tightened below
an already-running deployment's consumption).

### Follow-up fix pass: everything fixable landed the same session

Unlike round 1, the five parallel sessions above deliberately made no source changes themselves (to
avoid five agents concurrently editing the same files). All nine fixable findings above were fixed
in a single follow-up pass immediately after, split into four parallel, file-disjoint batches
(`gimle-controlplane`'s reconciler package, `gimle-controlplane`+`gimle-fafnir`'s API-server package,
`gimle-cli`, and `gimle-console`) plus a docs-only batch — see each finding's own **Fixed**/
**Documented** note above for what changed and where. Every fix has its own regression test; every
touched module compiles clean, passes its own relevant test suite (targeted, not a full `mvn
verify`), and passes `checkstyle:check`/`fmt-maven-plugin:check`.

A second, smaller follow-up pass closed three more of the previously-deliberately-open items: the
health column's `lifecycleState`-based gap (reusing `HealthReconciler#isHealthy`'s own
already-proven definition removed the false-positive risk that held this one back the first time),
the stale/too-old-`JAVA_HOME` friction item (`bin/gimle`/`bin/hilmir` and their `.cmd` counterparts
now fail fast with a real explanation instead of a bare classfile-version error), and the split
stdout/platform-log-location friction item (documented in `hilmir-reference.md`, not consolidated —
a real behavioral fix there is a separate, larger change). Left open, deliberately: Bug 6's real fix
(Andvari multi-file artifact support — a genuine new feature, not a bug fix), the orphaned-worker-JVM
cleanup (a real self-healing design change), and the relative-`artifactPath`-resolves-against-the-
control-plane's-own-cwd friction item (a real fix is invasive; see its own entry above for why this
pass left it as-is).
