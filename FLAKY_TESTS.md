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

## Also investigated: `mvn verify` speed (no change kept)

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
