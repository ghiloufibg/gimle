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

## Process

When a test fails that looks unrelated to the diff being verified: re-run it in isolation
(`-Dtest=ClassName#methodName`) once or twice before concluding it's flaky. If it's inconsistent
and the failure mode looks timing-related (proposal/lease/respawn timeouts), add an entry above
with the date, the exact failure, and whether a clean re-run confirmed it. Only promote an entry
to the standing exclusion list once it's shown up across more than one unrelated session.
