# Slow Test Modules

Findings from profiling the reactor's `test` phase with the Maven Profiler extension
(`.mvn/extensions.xml`, `mvn -Dprofile test`) to identify which modules dominate test-suite
wall-clock time, so they can be tackled first. Not auto-generated or re-run by CI; a point-in-time
analysis to guide prioritization.

## Methodology

Three full-reactor `mvn -Dprofile -DprofileFormat=JSON -Dmaven.build.cache.enabled=false -T 1 test`
runs (build cache disabled and reactor forced serial so each run measures real, unshared work
rather than cache hits or parallel-build contention). Per-module `surefire:test` goal time was
extracted from the profiler's JSON report (not the module's total time, which also includes
compile/resources). One run hit an unrelated pre-existing flaky test (see below) that caused
`mvn`'s `-fae` to skip that run's downstream dependents; the other two runs completed in full.

## Ranked by average test-phase time

| Module | Avg test time | Share of total |
|---|---:|---:|
| **gimle-mimir** | **~72s** | ~25% |
| **gimle-agent** | **~62s** | ~21% |
| gimle-controlplane | ~33s | ~11% |
| gimle-module | ~18s | 6% |
| gimle-fabric | ~14s | 5% |
| gimle-fafnir | ~12s | 4% |
| gimle-worker | ~11s | 4% |
| everything else (10 modules) | ≤7s each | ~19% combined |

`gimle-mimir` + `gimle-agent` + `gimle-controlplane` alone account for roughly 55-60% of the
entire serial test-suite wall-clock.

## Root cause, pinpointed to the actual test classes

**`gimle-agent` (~62s) is almost entirely one test**: `ControlPlaneAgentWorkerIntegrationTest`
takes ~58.8s by itself, nearly the whole module. It spins up a real `ControlPlaneMain` +
`AgentMain` + `WorkerMain` subprocess cluster and polls for convergence with two separate
`Duration.ofSeconds(90)` await loops plus `Thread.sleep(300/500)` inside the polling.
`FabricCrossProcessIntegrationTest` (~23s) and `WorkerProcessSupervisorTest` (~21s, 5 tests) are
the next real costs -- same pattern, real subprocesses.

**`gimle-mimir` (~72s) is more spread out but still concentrated**: `StateStoreTest` alone is
~47s (31 test methods) -- it has 9 real `Duration.ofSeconds(10)`/`TimeUnit.SECONDS` waits,
including lease-expiry assertions that appear to wait out real lease durations rather than using
a fake clock. `StoreClientClusterTest` (~11s) and `RaftMembershipChangeTest` (~5s) are real
multi-node Raft cluster spin-ups.

In both cases this is real end-to-end fidelity (real subprocesses, real Raft consensus, real
lease timing), not waste -- but concentrated in a small, identifiable number of places.

## What to tackle first, ranked by leverage

1. **`StateStoreTest`'s lease tests** -- check whether the `Duration.ofSeconds(10)` lease-expiry
   tests can use the same injectable/fake clock already available elsewhere in this codebase
   (`gimle-core`'s `TestClock`/`TestScheduler`) instead of sleeping out the real duration.
2. **`ControlPlaneAgentWorkerIntegrationTest`'s poll interval** -- the `Duration.ofSeconds(17)`/
   `Duration.ofSeconds(20)` reconciler interval baked into the test looks like the dominant cost;
   worth checking whether a shorter reconciler-tick override for this one test still exercises the
   real convergence logic.
3. **Move the heaviest real-subprocess tests to a slower, opt-in tier** -- the same pattern
   already applied to `gimle-smoke-tests` (`-Psmoke`, excluded from default `mvn verify`). Whether
   `ControlPlaneAgentWorkerIntegrationTest`/`FabricCrossProcessIntegrationTest` belong in the
   always-run unit suite versus a nightly/opt-in tier is a real trade-off to decide deliberately,
   not a pure speed fix.

## Aside: a real flaky test found along the way

`gimle-fafnir`'s `FafnirObservabilityTest.a_404_response_is_recorded_as_an_error` failed one
profiling run (asserted metric count `1.0`, got `0.0`) and failed again on Surefire's own retry --
reproducible, not environment noise. Unrelated to test *speed*; tracked here since it surfaced
during this investigation. See `FLAKY_TESTS.md` for this repo's existing flaky-test tracking.
