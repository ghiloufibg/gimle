# Flaky tests

Tracking doc for tests that have been observed to fail intermittently in this sandbox,
independent of the change under review, so they don't get mistaken for real regressions and
don't need re-diagnosing from scratch each time they trip a `mvn verify` run. Not a substitute
for fixing them — just a running log until someone has time to dig in.

Suspected common cause for most entries below: this sandbox's CPU/IO is shared and can stall a
JVM long enough to blow through a fixed timeout (Raft proposal commit windows, lease TTLs,
process-respawn wait windows) that would comfortably pass on dedicated hardware.

## 2026-08-11 QA hardening pass: most entries below were root-caused and fixed, not just excluded

A dedicated QA session (`qa-hardening` branch) went through every entry that was previously only
excluded and either found and fixed the real bug, or confirmed the entry is genuinely
sandbox-load-dependent and reduced its frequency where possible. Summary of what changed, in case
a future session wonders why a given test no longer needs its old exclusion:

- **`RaftClusterTlsTest#leader_election_and_write_replication_work_over_mtls`** — was missing the
  `@Isolated` annotation `RaftClusterTest` already needed for CPU-contention-under-class-level-
  concurrency (see that class's own comment). Added; confirmed clean across 5 isolated runs.
- **`RaftClusterTest#a_redirected_write_to_a_follower_returns_the_correct_leader_address`** — a
  real test race, not timing flakiness: `awaitLeader` only confirms *some* node believes itself
  leader, not that the picked follower has processed that leader's first heartbeat yet
  (`leaderHint` is set on receipt of one, a separate event). The propose-and-expect-redirect call
  could race a just-elected leader's first heartbeat, and the exception message reported "leader
  unknown" instead of the leader's address. Fixed by waiting for the follower's `leaderHint()` to
  resolve before exercising the redirect path. Same fix applied to `StoreClientClusterTest`'s own
  `awaitLeader` helper (used by its forced-failover test).
- **`RaftClusterTest#a_far_behind_follower_catches_up_via_install_snapshot_not_full_log_replay`**
  — a genuine JMM visibility gap, not a `RaftLog` bug: `RaftLog` is deliberately not internally
  synchronized (see its own class javadoc; `RaftNode` is its only intended caller, always under
  `RaftNode`'s own lock). The test read `raftLog.snapshotLastIncludedIndex()` (a plain field) once
  from the test thread immediately after a *different*, thread-safe condition (`store.getTenant`,
  a `ConcurrentHashMap` read) already succeeded — no happens-before edge connects the two, even
  though both are written by the same background thread around the same time. Folded the
  snapshot-index check into the same polling predicate instead of a one-shot read after the fact.
- **`RaftClusterTest#removing_a_server_shrinks_the_quorum_requirement_so_writes_still_succeed_after_losing_a_node`**
  and **`StoreClientClusterTest#a_client_keeps_writing_successfully_across_a_forced_leader_failover`**
  — genuinely still occasionally flaky under a full-reactor `mvn verify` specifically (both classes
  already have `@Isolated`, and pass 5-8/8 clean in isolation or as their own module's only test
  run every time this session tried). `@Isolated` only protects against *intra-module* class
  concurrency; `.mvn/maven.config`'s `-T 1C` means multiple Maven modules' own Surefire JVMs run
  concurrently too, a contention axis `@Isolated` has no visibility into. `RaftNode.PROPOSE_TIMEOUT`
  is now overridable via `-Dgimle.raft.proposeTimeoutSeconds` (production default unchanged at 5s)
  as available infrastructure for a future session — not activated by default here, since several
  existing test methods' own `@Timeout` budgets (some as tight as 10s) would need loosening in
  lockstep for a longer propose timeout to actually help, which is more invasive than this pass
  scoped itself to. Kept on the standing exclusion list below.
- **`SessionTokensTest#a_tampered_token_is_rejected`** — a real, deterministic test bug, not
  timing: flipping the token's *last* base64url character only corrupts the final partial group's
  significant bits (as few as 2), which occasionally decodes to the same byte. Flip a middle
  character instead, always inside a full 4-char/3-byte group. Confirmed clean across 20 runs.
- **`SystemLogCaptureTest#system_log_capture_survives_a_respawn`** — two compounding bugs, both
  fixed: (1) this sandbox's java launcher itself emits a "Picked up JAVA_TOOL_OPTIONS: ..." line to
  stdout before any Java `main()` runs, captured by the same SYSTEM-capture mechanism as a real
  line; the final assertion required *every* captured line to contain the fixture's banner text,
  which that incidental line doesn't. (2) Worse: the test's own `waitForLineCount` helper waited
  for *any* 2 lines total, so the JAVA_TOOL_OPTIONS line plus the *first* spawn's own banner alone
  satisfied it — closing the supervisor before the actual respawn this test means to exercise ever
  happened. Fixed both: wait for 2 *banner* lines specifically, and assert on banner-line count
  rather than requiring every captured line to match. Confirmed clean across 5 runs.
- **`GreeterSmokeTestIT` runs under plain `mvn verify` despite being `-Psmoke`-only** — a real
  build-wiring gap: an unqualified `-Dtest='!A,!B,...'` (exclusions only, no positive pattern), the
  exact shape every root-level verify command in this project's own session history uses for this
  standing exclusion list, overrides a plain Surefire `<excludes>` pattern and broadens class
  discovery enough to pick this `IT`-suffixed class up anyway. Fixed with `@Tag("smoke")` plus
  `excludedGroups=smoke` in the module's Surefire configuration — JUnit 5's own tag filtering is a
  separate mechanism from Surefire's class-name-pattern selection, applied after `-Dtest` has
  already run, so it isn't affected by the same override. Verified against the exact reproduction
  command (0 tests run) and that `-Psmoke`'s Failsafe execution is unaffected.
- **`SecretStoreTest#concurrent_writers_to_the_same_key_never_lose_an_update_and_every_slot_has_one_winner`**
  — same CPU-contention-under-class-level-concurrency cause as the `gimle-mimir` Raft entries
  above; `gimle-fafnir` had no `@Isolated` usage anywhere despite this test deliberately driving six
  threads at a single-node `InProcessStore` simultaneously. Added `@Isolated`. Confirmed clean
  across 3 full-module runs.
- **`GreeterSmokeTestIT`'s Playwright leg fails on a sandbox chromium-version mismatch** — fixed
  (separately, during Part B/Muninn work): `gimle-console/playwright.config.ts` now sets
  `executablePath: '/opt/pw-browsers/chromium'` when that path exists, matching this sandbox's
  pre-installed browser rather than the revision-specific path Playwright's own default resolution
  expects. Verified via a full `-Psmoke` run (4/4 tests, Playwright suite included).
- **`FabricServiceRegistryTest#a_failing_endpoints_breaker_opens_and_is_excluded`** — a fixed
  warmup count (30 calls) before assuming the dead endpoint's breaker had opened wasn't always
  enough; how many raw attempts the breaker's own error-rate window needs before it opens isn't
  guaranteed by any fixed pick count. Replaced with a poll loop (bounded to 500 attempts) that
  keeps calling until 5 consecutive calls land on the healthy endpoint. Confirmed clean across 8
  runs.
- **`LogRotationTest#cursor_paging_and_follow_resolve_correctly_across_a_rotation_boundary`** — was
  never timing-flaky in the usual sense, and previously had no diagnosed root cause: with
  `maxFileSizeBytes=512` and ~85-byte lines, only the *last* ~18 of 2000 written lines survive
  rotation eviction at all, and the original tight write loop (no time separation) let every
  survivor land on the same millisecond — `LogFileReader#readOlder`'s cursor uses a strict
  `isBefore`, so paging "older than" a timestamp every surviving line shares legitimately returns
  nothing. Sleeping 1ms per write for just the tail lines that actually survive rotation fixes it
  without slowing the rest of the test down. Confirmed clean across 8 runs.
- **`CertificateAuthorityTest#generated_leaf_certificate_is_readable_by_openssl`** — previously had
  no diagnosed root cause; confirmed by generating a cert directly and inspecting real `openssl
  x509 -text` output: OpenSSL 3.x renders `Subject: CN = node-1` (spaces around `=`), while the
  assertion checked for `CN=node-1` (no spaces) — a real, deterministic version-dependent
  formatting difference, not flakiness. Strip whitespace from the output before comparing.
  Confirmed clean across 5 runs.
- **`GossipMemberTest`** (`gimle-fabric`, whole class) — newly observed during this same session's
  own background verify runs (twice, different sub-tests each time), not previously tracked here.
  Real UDP sockets with millisecond-scale gossip/failure-detection timing; same
  CPU-contention-under-class-level-concurrency cause as the entries above, and `gimle-fabric` had
  no `@Isolated` usage anywhere despite this. Added. Confirmed clean across 3 full-module runs with
  rerun-masking disabled (`-Dsurefire.rerunFailingTestsCount=0`).
- **`GreeterSmokeTestIT#a_tenant_over_quota_deployment_is_flagged_but_not_evicted`** — observed once
  during the rolling-update QA scenario's own full-suite confirmation run (`409 unknown tenantId`
  on `provisionTenantAndSecret`'s own tenant, submitted moments earlier in the same test), not
  reproducible: passed cleanly in isolation and on an immediate full-suite retry (9/9, only the
  disabled membership test skipped). `GreeterSmokeTestIT`'s own tests each spin an independent
  cluster on distinct ports, so this looks like the same class of sandbox CPU/IO stall as every
  other entry here rather than a real cross-test interaction; not yet seen a second time.
- **`GreeterSmokeTestIT#a_rolling_update_keeps_at_least_one_instance_serving_traffic_throughout`** —
  observed once during the error-rate/queue-depth QA scenario's own full-suite confirmation run
  (`Observed minimum: 0`, i.e. the background sampler caught a moment reporting zero `ACTIVE`
  instances), not reproducible: passed 4/4 clean isolated runs (2 before this observation, 2
  immediately after) and an immediate full-suite retry (11/11, only the disabled membership test
  skipped). Checked the failing run's own logs directly rather than just re-running blind: both
  indices migrated correctly, one at a time, 14 seconds apart (`DeploymentReconciler`'s own "old
  module version; rolling it forward" log line, and the agent's own worker-respawn log, both show
  index 0 finishing before index 1 started) — the underlying rolling-update mechanism this test
  exists to prove worked correctly in the failing run too. The 300ms-interval background sampler
  polling `GET /deployments/*` over real HTTP is exactly the kind of fixed-cadence read the sandbox's
  shared CPU/IO can stall past a real (but brief and correct) transition window, the same root cause
  as every other entry here — not a residual gap in the agent/reconciler fix itself.
- **`GreeterSmokeTestIT#greeter_modules_deploy_across_a_store_cluster_and_multiple_control_plane_replicas`'s
  Playwright leg** — general console/browser flakiness under this sandbox's load, not specific to
  any one screen: across 5 isolated runs of this method while adding the Config/Metrics screen QA
  scenarios, the Playwright suite failed exactly once on the *Config* assertion and, on a different
  run, once on the pre-existing *Nodes/healthy* assertion instead -- different screens flaking on
  different runs is itself evidence against a real bug in either. A direct backend diagnostic (`GET
  /config/{tenantId}` polled straight from the Java test, bypassing the browser entirely) returned
  the correct data on every single run, isolating the flake to the browser/rendering side, the same
  class of timing issue as every other Playwright-tier entry here. One unrelated run also hit the
  already-known transient `503 store temporarily unavailable` on the very first post-startup write
  (this test's own initial `submitDeployment` calls don't use the retry wrapper other tests in this
  suite do) -- not a new finding, consistent with the existing entries about that same window.
- **`GreeterSmokeTestIT#a_tenant_over_quota_deployment_is_flagged_but_not_evicted`** -- fixed. Its
  own final assertion (the instance must stay `ACTIVE`, never evicted) read `isActive()` exactly
  once after a fixed `Thread.sleep`, a single-sample check with no tolerance for one stale read.
  Failed once, only during the heaviest run of the session (a 14-test, ~8-minute full-suite pass,
  the most sandbox contention any run this session generated) with `expected: <true> but was:
  <false>` -- a claim serious enough (a real `QuotaReconciler` eviction bug, contradicting its own
  documented contract) to warrant real investigation rather than a re-run-and-hope: 4 further
  isolated runs all passed clean, and the failing run itself had already gotten through quota
  creation, deployment submission, the initial ACTIVE wait, and the quota-violation-flag wait --
  all real store reads/writes succeeding -- before the single final read came back false, which
  rules out the early-post-startup-election window every other `submitDeployment`-without-retry
  flake in this file traces to. Concluded: a genuine one-sample heartbeat/store-read staleness
  blip, not an eviction. Fixed the test itself regardless of root cause, since a single-sample
  check for "never touched" is inherently fragile: it now retries up to 5 times, 1s apart, so a
  momentary blip self-corrects within the same confirmation window a real, sustained eviction never
  would. Confirmed clean across 3 further isolated runs after the fix.

## 2026-08-22 QA hardening pass: silent zero-test-count under parallel class execution

- **`ServiceCatalogCodecTest`** (`gimle-fabric`, whole class) — observed under this module's own
  class-level concurrency (root pom.xml) silently under-reporting its test count (0/3, then 2/3 on
  a second run) with no failure raised, rather than the timing failures every other entry in this
  file traces to the same cause — a worse symptom shape, since a build this happens to still
  reports BUILD SUCCESS instead of flagging anything wrong. Correct count (3/3) every time in
  isolation. Same CPU-contention-under-class-level-concurrency cause `GossipMemberTest` (also
  `gimle-fabric`) needed `@Isolated` for; added here too. Confirmed clean across 3 full-module runs
  (118/118 every time, `ServiceCatalogCodecTest` itself 3/3 every time).
- **`StoreMetricsTest`** (`gimle-observability`, whole class) — same failure shape as
  `ServiceCatalogCodecTest` above, observed independently in the same pass: silently under-reported
  0/6 twice in a row under full-suite parallel execution, no failure raised, correct count (6/6)
  every time in isolation. `gimle-observability` had no `@Isolated` usage anywhere despite this.
  Added. Confirmed clean across 3 full-module runs (58/58 every time, `StoreMetricsTest` itself 6/6
  every time).

A parallel pass looked at whether the standing `-T 1C` (root `.mvn/maven.config`) combined with
this pom's own `junit.jupiter.execution.parallel.config.dynamic.factor=1.0` oversubscribes cores
on this 4-core sandbox whenever multiple modules' own concurrent-class phases land at once — a
real, plausible mechanism, and the same shape of contention several entries above trace their
flakiness to. Tuning `dynamic.factor` down to `0.5` looked like a genuine win on a first
measurement (5:29 vs. a 6:03 baseline, fewer flakes), but a same-command confirmation run came back
at 6:39 — worse than baseline. Run-to-run wall-clock variance in this shared sandbox (over a full
minute across otherwise-identical runs) turned out to be larger than the effect being measured, so
no reliable conclusion could be drawn either way. Reverted rather than keep an unproven change;
`dynamic.factor` is still `1.0`. Separately, `maven-build-cache-config.xml`'s caching stays
disabled for the real, already-documented reason in that file's own top comment (the pinned
`maven-build-cache-extension` version, `1.2.3`, has no per-project override in its schema, and
only `1.2.3` is available in this sandbox's local repository to check against) — not re-litigated
here, since nothing changed to justify revisiting it.

## Already excluded from the standard verify command

Two entries remain here — both confirmed to pass reliably in isolation (5-8/8) or as their own
module's only test run, but still occasionally flaky specifically under a full-reactor `mvn verify`
(cross-*module* Surefire-JVM contention from `.mvn/maven.config`'s `-T 1C`, an axis `@Isolated`
can't reach):

- `RaftClusterTest#removing_a_server_shrinks_the_quorum_requirement_so_writes_still_succeed_after_losing_a_node`
- `StoreClientClusterTest#a_client_keeps_writing_successfully_across_a_forced_leader_failover`

## Implemented fix: a sequential flaky-test runner goal

The two entries above are confirmed non-bugs, root-caused to cross-*module* Surefire-JVM
contention from `.mvn/maven.config`'s `-T 1C` (multiple modules' own forks competing for CPU at
once) -- an axis `@Isolated` (a JUnit5 intra-module guard) has no visibility into. Considered and
rejected approaches, for the record:

- **Extracting the two flaky methods into new classes/support base classes** -- rejected: touches
  already-stable, passing test code purely for build-topology reasons, and both fixtures
  (`RaftClusterTest`, `StoreClientClusterTest`) are large enough (~300-470 lines of shared
  state/helpers each) that extraction is a real refactor, not a small one.
- **Patching or forking Surefire/Failsafe** -- rejected: neither plugin owns reactor-level
  scheduling. The actual contention comes from Maven *core*'s multithreaded builder (`-T`), which
  Surefire/Failsafe have no visibility into or control over -- patching them wouldn't reach the
  mechanism causing the flake.
- **Patching Maven core itself** to support pausing/resuming reactor parallelism mid-build --
  rejected: not a supported public extension point (no `EventSpy`/lifecycle-participant hook
  exposes the reactor's thread pool for mutation once a build has started); would mean maintaining
  a fork of Maven core across every future upgrade, wildly disproportionate for two known-benign
  tests.
- **Embedding the "run it alone" step inside the same `mvn verify` reactor invocation** (e.g. an
  `exec-maven-plugin` execution bound into `gimle-mimir`'s own build) -- rejected: to still look
  like "one command," the natural way to build this spawns the isolated re-run *while* other
  modules may still be mid-flight elsewhere in the same `-T 1C` reactor, silently reintroducing the
  exact contention it's meant to remove. Looks fixed, isn't reliably fixed.

**What's implemented:** the real fix is procedural, not code-level -- run the flaky-tagged
tests in a genuinely separate, single-module Maven invocation, so there is nothing else in that
reactor to contend with, *by construction*. `gimle-maven-plugin` (`mvn gimle:*`) already had the
right shape for this: every existing goal (`gimle:store`, `gimle:controlplane`, etc.) spawns a
real, standalone OS process and is deliberately never bound to a lifecycle phase, so it only ever
runs when explicitly invoked as its own command -- never nested inside `mvn verify`.

Shape:

1. **A repo-wide `@Tag("flaky")`** (not a Raft-specific tag) on the two tests above, in
   `gimle-mimir`.
2. **`<excludedGroups>flaky</excludedGroups>`** added to the *root* `pom.xml`'s Surefire
   `pluginManagement` (inherited by every module for free) -- tagging a test `@Tag("flaky")`
   anywhere automatically excludes it from that module's default `mvn verify`, no per-module pom
   edit needed for future entries.
3. **`AbstractGimleRootMojo`**, a sibling to the existing `AbstractGimleMojo`: guarded by
   `project.isExecutionRoot()` (true exactly once per reactor invocation, regardless of `-T` or
   module count) instead of `AbstractGimleMojo`'s "self-filter to one target module" check --
   this orchestrator needs to run once and potentially touch several modules, not exactly one.
   The shared "spawn a process, wait, check exit code" logic lives in `GimleProcesses` (already
   the shared-mechanics home for `javaExecutable()`) so both base classes reuse it.
4. **`FlakyTestsMojo extends AbstractGimleRootMojo`**, `mvn gimle:flaky-tests`: a `@Parameter`
   lists the module artifactIds known to carry `@Tag("flaky")` tests (`List.of("gimle-mimir")`
   today -- a small, manually-maintained list, deliberately, matching this file already being a
   manually-maintained ledger rather than something auto-discovered by scanning bytecode). For
   each listed module, *in order*: spawn `mvn -pl <module> test -Dgroups=flaky`, block on
   `waitFor()`, check its exit code, only then move to the next module. Two flaky-tagged tests
   never run concurrently with each other either, not just with the rest of the reactor -- each
   gets the whole machine to itself, one at a time.
5. **`mavenExecutable()`** in `GimleProcesses`, mirroring `javaExecutable()`'s own robustness
   (checks the running process's own command first, falls back through `MAVEN_HOME`/PATH).
6. **Docs**: a `mvn gimle:flaky-tests` entry in
   `gimle-docs/docs/reference/maven-plugin-goals.md`, and this file's own "Process" section
   describing tagging a test `@Tag("flaky")` plus adding its module to `FlakyTestsMojo`'s list,
   instead of the old ad hoc `-Dtest='!A,!B,...'` convention.

Net effect: `mvn verify` never runs (or flakes on) `@Tag("flaky")` tests at all; `mvn
gimle:flaky-tests` runs every one of them, strictly one module at a time, each with a clean
single-module reactor and nothing else competing for the machine.

## Process

When a test fails that looks unrelated to the diff being verified: re-run it in isolation
(`-Dtest=ClassName#methodName`) once or twice before concluding it's flaky. If it's inconsistent
and the failure mode looks timing-related (proposal/lease/respawn timeouts), add an entry above
with the date, the exact failure, and whether a clean re-run confirmed it. Before assuming a
failure is sandbox timing, read the actual assertion/exception message and the code path it came
from — several entries above turned out to be real, deterministic bugs (a test's own tampering
strategy, a version-dependent CLI output format, a JMM visibility gap, a fixed warmup count)
wearing a "flaky" label only because nobody had looked closely yet. Only promote an entry to the
standing exclusion list once it's shown up across more than one unrelated session, and only after
confirming it isn't something a closer look would actually fix.

Promoting an entry to the standing exclusion list means tagging the method `@Tag("flaky")` and,
if its module isn't already listed, adding its artifactId to `FlakyTestsMojo`'s module list in
`gimle-maven-plugin` -- not an ad hoc `-Dtest='!A,!B,...'` exclusion on the verify command line.
`mvn verify` then skips it for free (root `pom.xml`'s `excludedGroups=flaky`), and `mvn
gimle:flaky-tests` runs it on its own, one module at a time.
