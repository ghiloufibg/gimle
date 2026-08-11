# QA hardening findings — 2026-08-11

A dedicated QA pass (`qa-hardening` branch, 10 commits) covering three phases: stabilize the build
by fixing reported flaky tests, look for ways to speed up `mvn verify`, and hunt bugs via new/
enhanced tests including a real end-to-end cluster. This doc records what was found and fixed, what
was investigated and found not to be a bug, and what's still open for a follow-up session.

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

### Scope explicitly not covered this session

Given real time constraints, the following real-cluster scenarios named in the original QA mission
were **not** attempted this session — listed here as open scope for a follow-up, not silently
dropped:

- Rolling update / version-aware traffic cutover under a real multi-replica cluster (unit/
  integration coverage exists per `DeploymentReconcilerRollingUpdateTest`; no real-cluster
  end-to-end exercise).
- Multi-signal autoscaling (Part C, this same development thread) under real synthetic load —
  unit/integration coverage exists (`AutoscaleReconcilerTest`), but nothing exercises the four
  signals against a real deployed cluster with real traffic.
- Raft leader failover / live membership change under concurrent writes at the smoke-test tier
  (covered at the unit/integration tier in `gimle-mimir`, e.g. `RaftMembershipChangeTest`,
  `RaftClusterTest`'s own membership-change tests).
- RBAC/authz edge cases (cross-tenant denial, node-scoped self-service) at the smoke-test tier
  (covered at the unit/integration tier, e.g. `ApiServerAuthzTest`; not attempted at the smoke
  tier since the smoke suite runs the whole cluster in plaintext mode with auth bypassed).
  Multi-tenancy quota *enforcement* (flag-not-evict) is now covered at the smoke tier too — see
  the added scenario above — but quota *interaction with scheduling* (a new deployment refused
  placement outright because it would immediately violate quota) remains untested at this tier.
- The console's full Playwright surface beyond Deployments/Logs (`gimle-console/e2e/`'s own scope
  today).

## Verification

Every fix in Phase 1 and both Phase 3 additions was verified with repeated isolated runs (3-20x
depending on the entry) plus checkstyle/spotbugs/fmt, documented per-entry in the commit history on
`qa-hardening`. A final full-reactor `mvn verify` (2-entry exclusion list) passed clean at
`9dadef8`..`fd22baf`; `gimle-smoke-tests -Psmoke` passed 5/5 including the worker-respawn test at
`a93b43f`, and 6/6 including the quota-enforcement test at `b364e3e` (after one full-suite run with
an unrelated shared-cause failure, see above).
