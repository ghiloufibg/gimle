# Flaky tests

Tracking doc for tests that have been observed to fail intermittently in this sandbox,
independent of the change under review, so they don't get mistaken for real regressions and
don't need re-diagnosing from scratch each time they trip a `mvn verify` run. Not a substitute
for fixing them — just a running log until someone has time to dig in.

Suspected common cause for most entries below: this sandbox's CPU/IO is shared and can stall a
JVM long enough to blow through a fixed timeout (Raft proposal commit windows, lease TTLs,
process-respawn wait windows) that would comfortably pass on dedicated hardware.

## Already excluded from the standard verify command

These are established enough (multiple sessions) that they're carried as a standing
`-Dtest='!...'` exclusion list in the production-hardening batch's verify runs, rather than
re-diagnosed every time:

- `LogRotationTest` (whole class)
- `CertificateAuthorityTest#generated_leaf_certificate_is_readable_by_openssl`
- `RaftClusterTest#a_redirected_write_to_a_follower_returns_the_correct_leader_address`
- `SystemLogCaptureTest#system_log_capture_survives_a_respawn`
- `StoreClientClusterTest#a_client_can_read_and_write_through_any_endpoint_once_a_leader_is_elected`
- `StoreClientClusterTest#a_client_keeps_writing_successfully_across_a_forced_leader_failover`
- `GreeterSmokeTestIT` (opt-in `-Psmoke` only, not part of default `verify` — excluded for
  runtime/scope reasons, not flakiness, but listed here for completeness since it's in the same
  exclusion flag)

## Newly observed, not yet added to the standing exclusion list

### `RaftClusterTlsTest#leader_election_and_write_replication_work_over_mtls`

- Observed: 2026-08-09, during a `gimle-core,gimle-pki,gimle-mimir,...` verify run on
  `production-hardening` (P2-18 work).
- Failure: `com.gimle.core.exception.GimleRaftException: node node-0's proposal did not commit
  within PT5S`, thrown from `RaftNode.giveUpAndTruncateLocked` via `RaftNode.awaitAppliedThrowing`.
- Passed on a later full-suite run with no code change in the mTLS/Raft path — consistent with a
  timing stall, not a logic bug introduced by that session's changes.

### `StoreClientClusterTest#leases_are_acquired_renewed_and_released_through_the_client`

- Observed: 2026-08-09, same run as above.
- Failure: `AssertionFailedError: expected: <true> but was: <false>` at
  `StoreClientClusterTest.java:237` (a lease-acquired assertion).
- Also passed on a later re-run with no relevant code change.

### `RaftClusterTest#removing_a_server_shrinks_the_quorum_requirement_so_writes_still_succeed_after_losing_a_node`

- Observed: 2026-08-09, during a `gimle-mimir` module test run on `production-hardening`
  (P2-12 SpotBugs work; unrelated to the change under review — `StateStore`/`AtomicFiles` null
  handling, not membership-change logic).
- Failure: `com.gimle.core.exception.GimleRaftException: node node-3's proposal did not commit
  within PT5S`, thrown from `RaftNode.giveUpAndTruncateLocked` via `RaftNode.awaitAppliedThrowing`,
  same shape as the other already-listed proposal-timeout entries.
- Passed cleanly on an isolated re-run (`-Dtest=RaftClusterTest#...`) with no code change —
  consistent with a timing stall under shared sandbox load, not a logic bug.

### `RaftClusterTest#a_far_behind_follower_catches_up_via_install_snapshot_not_full_log_replay`

- Observed: 2026-08-09, during a full-reactor `mvn verify` on `production-hardening` (P2-12
  SpotBugs final verification pass), running under parallel-forked reactor load.
- Failure: same proposal-timeout shape as the other Raft entries above (`GimleRaftException: node
  node-0's proposal did not commit within PT5S`).
- Passed cleanly on an isolated re-run with no code change.
- Observed again: 2026-08-09, during the P3 (InstallSnapshot chunking, etc.) final full-reactor
  `mvn verify`, this time as `assertTrue` failing on `snapshotLastIncludedIndex() >= lastIndex`
  immediately after the preceding `awaitTrue`'s tenant-visibility condition already succeeded.
  Consistent with a genuine, pre-existing (not introduced by the P3-1 chunking change --
  `RaftClusterTest.java` itself wasn't touched by that commit) memory-visibility race in the test
  itself: `store.getTenant(...)` (a `ConcurrentHashMap` read) and `raftLog.snapshotLastIncludedIndex()`
  (a plain field read) are two separately-synchronized reads from a different thread than the
  writer, with no ordering guarantee between them absent additional synchronization -- more likely
  to manifest under this sandbox's heavier full-reactor CPU contention. Passed cleanly on three
  consecutive isolated re-runs with no code change.

### `StoreClientClusterTest#heartbeat_reads_are_leader_routed_and_never_answer_empty_from_a_stale_follower`

- Observed: 2026-08-09, same full-reactor `mvn verify` run as above.
- Failure: `AssertionFailedError: call 0 returned empty ==> expected: <true> but was: <false>` at
  `StoreClientClusterTest.java:263` — the P2-14 leader-routed-heartbeat-read test, presumably
  hitting a leader-election/heartbeat-propagation race under the same shared-sandbox load as the
  Raft entries above, not a logic regression.
- Passed cleanly on an isolated re-run with no code change.

### `GreeterSmokeTestIT` runs under plain `mvn verify` despite being documented as `-Psmoke`-only

- Observed: 2026-08-09, same full-reactor `mvn verify` run as above — `gimle-smoke-tests`'
  `GreeterSmokeTestIT` executed via Surefire's `default-test` execution (not Failsafe, and without
  passing `-Psmoke`), then failed on its embedded Playwright suite (exit code 1, browser/cluster
  environment specifics not investigated). Not a flaky-timing issue like the entries above — a
  build-wiring gap: an unqualified `-Dtest='!A,!B,...'` (exclusions only, no positive pattern)
  apparently broadens Surefire's default class-discovery beyond the usual `*Test`/`*Tests`/
  `*TestCase` set enough to pick up `GreeterSmokeTestIT` too, even though the module's own
  `maven-failsafe-plugin` binding (the intended runner for it) is gated behind the `smoke` profile
  and never activated here. Worked around for this session by adding an explicit
  `!GreeterSmokeTestIT` exclusion to the verify command; worth a real fix (e.g. an explicit
  Surefire `<excludes>` entry in `gimle-smoke-tests/pom.xml` for its own `IT` suffix) in a future
  session rather than leaning on the ad hoc `-Dtest` flag forever.

### `SessionTokensTest#a_tampered_token_is_rejected`

- Observed: 2026-08-09, during a `gimle-core,gimle-mimir,gimle-controlplane` verify run on
  `secrets-vault-implementation` (F-2, `LoginThrottle` relocation — unrelated to this test's own
  code, `SessionTokens` wasn't touched by that change).
- Failure: `AssertionFailedError: expected: <Optional.empty> but was: <Optional[alice]>` at
  `SessionTokensTest.java:60`.
- Not a timing race like the entries above — the test flips the token's last base64url character
  (`'A' <-> 'B'`) to corrupt the trailing HMAC tag byte, but a base64 group's last character only
  encodes 2 significant bits; occasionally the specific flip lands on bits that don't change the
  decoded byte's meaningful value, so verification spuriously still succeeds. A pre-existing,
  low-probability property of the test's own tampering strategy, not a `SessionTokens` bug.
- Passed cleanly on an isolated re-run with no code change.

### `SecretStoreTest#concurrent_writers_to_the_same_key_never_lose_an_update_and_every_slot_has_one_winner`

- Observed: 2026-08-09, during F-7 (Fafnir rate limiting + observability) on
  `secrets-vault-implementation`, running the full `gimle-fafnir` module test suite together (not
  in isolation) alongside several other real-`InProcessStore`/real-HTTP-server test classes.
- Failure: `GimleRaftException: no reachable store leader could serve ListConfigEntriesFor after
  retrying every endpoint`, thrown from one of the six concurrent writer threads' own
  `SecretStore.put` → `readMeta` → `StoreClient.listConfigEntriesFor` call.
- Same shape as this doc's other Raft-timeout entries: this test already deliberately drives six
  threads at a single-node `InProcessStore` simultaneously (see `SecretStore.java`'s own
  `MAX_WRITE_ATTEMPTS` javadoc for why that concurrency is intentional, not a bug), and under this
  sandbox's shared CPU/IO plus several *other* test classes' real sockets/Raft nodes running at the
  same time, that self-imposed load occasionally pushes a proposal/read past its retry budget --
  not a correctness regression in `SecretStore`'s write-verify-retry logic itself, which the same
  test's own version-uniqueness assertions verify on every other run.
- Passed cleanly on three consecutive full-module re-runs (`mvn -pl gimle-fafnir test`, no `-Dtest`
  filter) with no code change, and on three consecutive isolated (`-Dtest=SecretStoreTest`) re-runs
  before that.

### `GreeterSmokeTestIT`'s Playwright leg fails on a sandbox chromium-version mismatch

- Observed: 2026-08-09, during F-12 (Fafnir topology + secret round trip) on
  `secrets-vault-implementation`, running
  `mvn -pl gimle-smoke-tests verify -Psmoke -Dtest=GreeterSmokeTestIT#greeter_modules_deploy_...`.
- Failure: `runPlaywrightSuite` fails with exit code 1 on both Surefire retries; the underlying
  Playwright error is `browserType.launch: Executable doesn't exist at
  /opt/pw-browsers/chromium_headless_shell-1234/...` -- this sandbox's pre-installed browser is
  `chromium_headless_shell-1194` (see `/opt/pw-browsers/`), a version mismatch against whatever
  `@playwright/test` version `gimle-console/package.json` currently pins, not something either
  `GreeterSmokeTestIT` or `gimle-console`'s own `playwright.config.ts` controls.
- Not a regression from F-12's own change: every await *before* `runPlaywrightSuite` in the same
  run -- both deployments reaching `ACTIVE`, the consumer's real fabric-call log line, and the new
  secret round trip (`providerLogShowsTheSecret`, the real write-via-API -> fetch-via-agent-from-a-
  real-multi-replica-Fafnir-cluster -> observed-in-the-module's-own-log path) -- all passed on both
  attempts; only the Playwright leg, launched as a separate `bun run test:e2e` subprocess against a
  pre-installed browser this sandbox doesn't have at the exact path Playwright expects, failed.
  Confirmed via the agent/fafnir-0/fafnir-1/controlplane-0/controlplane-1 log files from the same
  run: two Fafnir replicas came up sharing one `fafnir-secret.key`, both worker subprocesses
  spawned, and the run only reached `runPlaywrightSuite` (a later step) because everything before
  it already returned true.
- Not re-run further in isolation: the failure is deterministic (a missing file at a fixed path),
  not timing-dependent, so a re-run would reproduce identically without a version-matched browser
  or an `executablePath` override in `gimle-console`'s own Playwright config -- a fix belongs to
  that mismatch, not to this test.

### `FabricServiceRegistryTest#a_failing_endpoints_breaker_opens_and_is_excluded`

- Observed: 2026-08-09, during Final verification (full repo-root `mvn verify` with the standard
  exclusion list) on `secrets-vault-implementation` -- `gimle-fabric` was untouched by any F-1
  through F-12 change, so this is unrelated to the diff being verified.
- Failure: `java.io.UncheckedIOException: fabric call to node-a/worker-dead failed`, caused by
  `java.net.ConnectException: Connection refused` from `FabricClient.callOverChannel`.
- Not a real regression: the test deliberately calls a socket address ("worker-dead") nothing is
  listening on to prove the circuit breaker opens and excludes it -- a `ConnectException` on that
  first attempt is exactly what's supposed to happen, but this run's failure is Surefire's own
  `[ERROR] ... <<< ERROR!` on attempt 1 rather than the assertion the test is actually checking,
  suggesting a timing sensitivity in when the breaker's own state transition is observed under this
  sandbox's shared CPU/IO, not a logic bug.
- Self-healed within the same run: Surefire's built-in rerun-failing-tests mechanism retried it and
  it passed clean (`Run 2: PASS`), so the module still reported `Failures: 0, Errors: 0` overall
  (`Flakes: 1`) and the build did not fail. Not re-run further in isolation since it never actually
  blocked anything; noted here per this doc's own standing convention rather than left silent.

## Process

When a test fails that looks unrelated to the diff being verified: re-run it in isolation
(`-Dtest=ClassName#methodName`) once or twice before concluding it's flaky. If it's inconsistent
and the failure mode looks timing-related (proposal/lease/respawn timeouts), add an entry above
with the date, the exact failure, and whether a clean re-run confirmed it. Only promote an entry
to the standing exclusion list once it's shown up across more than one unrelated session.
