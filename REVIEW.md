# Gimlé Code Quality Review — Maintainability, Clarity, Readability

**Date:** 2026-08-13 (initial review) · **incrementally updated 2026-08-13** after rebasing onto `master`
**Method:** One independent review agent per Maven module (20 modules), each scoped strictly to that module's own source.

## Incremental update

This branch was rebased onto `master` after two new commits landed there (`42c481c` "feat: add maxUnavailable disruption budgets for rolling updates" and its merge commit `5d0e043`), touching `gimle-controlplane`, `gimle-mimir`, and `gimle-docs`. Those three modules were re-reviewed in full against the current code; the other 17 modules were untouched by the rebase and their findings below are unchanged from the initial pass. New findings from this round are marked **(new)** in the sections below.

## Scope and exclusions

This review looks at exactly one axis: **how easy this code is for a human maintainer — who did not write it — to read, understand, and safely change.** It intentionally excludes performance, optimization, security, and correctness/bug-hunting; a finding here is about clarity, not whether the code is fast, safe, or right.

Concretely, each agent looked for: unclear/inconsistent naming, oversized methods/classes mixing abstraction levels, stale or restating comments (and comments that explain real *why*), duplicated logic, tangled control flow, unexplained magic values, inconsistent conventions within a module, dead/commented-out code, overly dense/clever code, weak test names/assertions, and violations of this repo's own committed conventions (Google Java Format, `@Test` snake_case vs. camelCase elsewhere, no Lombok, immutable records, no checked exceptions, and — notably — the rule that no comment may cite `claudedocs/` or any other planning artifact).

**Excluded from this pass by explicit request:** `gimle-console` and `gimle-fafnir-console` (the two frontend SPAs).

**Included:** all 20 remaining Maven modules, including the tiny `gimle-examples/*` modules, `gimle-smoke-tests`, and `gimle-docs`.

## Summary

| Module | Files (approx) | Findings | One-line take |
|---|---:|---:|---|
| [gimle-core](#gimle-core) | 55 | 4 | Very clean; minor telescoping-constructor and duplication nits |
| [gimle-module](#gimle-module) | 40 | 3 | Very clean; one misleading test name, one duplicated test helper |
| [gimle-os](#gimle-os) | 6 | 2 | Small, well-documented; one stale pom description |
| [gimle-pki](#gimle-pki) | 11 | 4 | Well-documented; PEM-encoding duplication contradicts its own javadoc |
| [gimle-observability](#gimle-observability) | 23 | 7 | Thorough javadoc; one convention violation, some stale comments, 5-way metrics-class duplication |
| [gimle-worker](#gimle-worker) | 18 | 5 | Clean overall; `WorkerMain` is an oversized, magic-value-heavy entry point |
| [gimle-agent](#gimle-agent) | 21 | 5 | Well-commented; `AgentMain` (1629 lines) mixes many concerns, long parameter lists |
| [gimle-mimir](#gimle-mimir) | 30 | 12 | Well-documented; `StateStore` (now ~2304 lines) and manifest-parsing duplication both grew further with the new disruption-budget feature |
| [gimle-muninn](#gimle-muninn) | 10 | 5 | Disciplined; `MuninnServer` handler duplication, one stale/convention-violating comment |
| [gimle-fafnir](#gimle-fafnir) | 18 | 5 | Well-documented; triplicated TLS test fixtures, one long handler method |
| [gimle-controlplane](#gimle-controlplane) | 49 | 11 | Heavily commented; `ApiServer` (3599 lines) is a God class with repeated dispatch skeletons; disruption-budget change left two stale `rollingIndex` comments |
| [gimle-fabric](#gimle-fabric) | 28 | 3 | Unusually readable; telescoping constructors, one large-but-organized class |
| [gimle-cli](#gimle-cli) | 21 | 3 | Very clean; one design-doc citation, one 6-way duplicated parsing block |
| [gimle-maven-plugin](#gimle-maven-plugin) | 10 | 4 | Clean; `BootstrapMojo` is a 650-line outlier vs. its ~100-line siblings |
| [gimle-examples/hello-module](#gimle-exampleshello-module) | 2 | 0 | Clean |
| [gimle-examples/greeter-provider](#gimle-examplesgreeter-provider) | 8 | 0 | Clean |
| [gimle-examples/greeter-consumer](#gimle-examplesgreeter-consumer) | 5 | 0 | Clean |
| [gimle-examples/greeter-load-generator](#gimle-examplesgreeter-load-generator) | 5 | 0 | Clean |
| [gimle-smoke-tests](#gimle-smoke-tests) | — | 8 | Well-documented tests; `GreeterSmokeClusterSupport` (1961 lines) mixes six concerns, two `QA_FINDINGS.md` citations |
| [gimle-docs](#gimle-docs) | 27 | 8 | Clean TS/MDX; seven separate planning-artifact citations on the *published* docs site itself (five `claudedocs/`, two `design doc §N`) |

**Total: 89 findings across 16 modules; 4 modules came back clean.** (77 from the initial pass + 12 new from the incremental re-review of `gimle-controlplane`/`gimle-mimir`/`gimle-docs`.)

The single most common theme across the whole codebase is **duplication a human maintainer has to keep in sync by hand** — repeated dispatch/handler skeletons, repeated manifest/YAML (de)serialization helpers, repeated test fixtures — usually well-commented in isolation but requiring the same edit in 3–8 places when it changes. The second most common theme is a handful of **outsized "do everything" classes** (`ApiServer`, `StateStore`, `AgentMain`, `WorkerMain`, `GreeterSmokeClusterSupport`) that are internally well-organized but too large to hold in working memory at once. Third, several modules have one or two **comments that cite a planning artifact** (`claudedocs/...`, `QA_FINDINGS.md`, a phase/section number) or have **gone stale relative to the code**, which this repo's own conventions explicitly flag as a readability defect.

---

## gimle-core

**Overall:** gimle-core is unusually easy to maintain for a module of its size: naming is consistent, methods stay small and single-purpose, records validate their own invariants uniformly, and nearly every non-obvious decision is captured in a comment that actually earns its place rather than restating the code. No dead code, no stale/misleading comments, no `claudedocs/`-style references leaked into committed files.

1. **[duplication]** `gimle-core/src/main/java/com/gimle/core/protocol/InstanceObservation.java:49` — `InstanceObservation` accumulates three overlapping backward-compat constructors instead of consolidating call sites onto the full one (same telescoping pattern repeats in `ControlMessage.MetricsReport`/`Hello`/`InstallModule`/`ResolveModule`). *Suggestion:* update the (few, in-repo) call sites to pass explicit values and drop superseded overloads, or use a builder for still-growing records.
2. **[consistency]** `gimle-core/src/main/java/com/gimle/core/protocol/InstanceEvent.java:40` — `InstanceEvent` silently defaults a null `causeSummary` to `Optional.empty()` instead of throwing, breaking every sibling record's established fail-fast convention. *Suggestion:* throw the same `IllegalArgumentException` as every other Optional-field record, or comment why this one is lenient.
3. **[duplication]** `gimle-core/src/main/java/com/gimle/core/logging/JsonLogEncoder.java:45` — the APPLICATION-vs-PLATFORM category test is copy-pasted verbatim between `JsonLogEncoder` and `TextLogEncoder`. *Suggestion:* extract a shared `InstanceMdcKeys.isApplicationCategory(...)` helper.
4. **[test-readability]** `gimle-core/src/test/java/com/gimle/core/module/ModuleDescriptorTest.java:88` — `id_combines_name_and_version` only asserts `id()` doesn't throw, never that the result actually combines name and version. *Suggestion:* assert the actual returned `ModuleId`.

## gimle-module

**Overall:** Unusually easy to read: precise naming, comments that explain genuinely non-obvious mechanics (JFR retaining-path attribution, `ModuleLayer` parent selection, leak-detection reference handling), small single-purpose methods, and a lifecycle test suite that reads as a clear specification.

1. **[duplication]** `gimle-module/src/test/java/com/gimle/module/leak/RedeployLoopFlatMetaspaceTest.java:150` — `javaExecutable()`/`buildClasspath()`/`modulePathEntryOf()` duplicated almost verbatim in `RetainingPathAttributionTest`. *Suggestion:* extract a shared `SubprocessTestSupport` helper.
2. **[test-readability]** `gimle-module/src/test/java/com/gimle/module/lifecycle/ModuleControllerTest.java:171` — `uninstall_from_failed_state_succeeds()` never drives the module into `FAILED`; it uninstalls directly from `INSTALLED`, contradicting its own name. *Suggestion:* rename to `uninstall_from_installed_state_succeeds()`.
3. **[structure-and-control-flow]** `gimle-module/src/main/java/com/gimle/module/lifecycle/ModuleController.java:65` — four overlapping, all-delegating constructors implement a telescoping-constructor pattern for two optional collaborators, duplicating the same no-op default in two places. *Suggestion:* collapse to one canonical constructor plus a builder or defaults holder.

## gimle-os

**Overall:** Small (two interfaces, two records, two implementations, two test classes) with unusually thorough, accurate javadoc explaining why cgroup v2 is deferred, why `release` is idempotent, etc.

1. **[comment-quality]** `gimle-os/pom.xml:18` — module `<description>` claims this module does "cgroup v2 management on Linux," which no code here does; every javadoc in the module says the opposite (deliberately deferred). *Suggestion:* fix the description to match the code's actual, stated scope.
2. **[comment-quality]** `gimle-os/src/test/java/com/gimle/os/localdisk/LocalDiskVolumeManagerTest.java:20` — `@TempDir(cleanup = CleanupMode.NEVER)` departs from JUnit's default with no comment explaining why. *Suggestion:* add a one-line rationale or revert to default cleanup.

## gimle-pki

**Overall:** Small, well-scoped, unusually well-documented — javadoc consistently explains the *why* (signing-path unification, SAN/hostname-verification rationale, thundering-herd avoidance, defense-in-depth identity reasoning).

1. **[comment-quality]** `gimle-pki/src/main/java/com/gimle/pki/Pem.java:17` — `Pem`'s javadoc claims test-class PEM-encoding duplication was already eliminated, but two test classes still hand-roll their own PEM encoding. *Suggestion:* migrate the two test helpers onto `Pem`, or correct the javadoc.
2. **[duplication]** `gimle-pki/src/test/java/com/gimle/pki/CertificateAuthorityTest.java:296` — `toPem()` duplicates `Pem.encodeCertificate`'s wrap logic instead of calling it.
3. **[duplication]** `gimle-pki/src/test/java/com/gimle/pki/SslContextsIntegrationTest.java:135` — `writePem()` duplicates `Pem`'s encode-and-wrap logic for certificates and private keys.
4. **[magic-values]** `gimle-pki/src/main/java/com/gimle/pki/OwnCertificateRotator.java:121` — RSA key size `2048` is a bare literal here, while `CertificateAuthority` and `PkiBootstrapMain` use a named `KEY_SIZE_BITS` constant for the same purpose. *Suggestion:* add the same named constant here.

## gimle-observability

**Overall:** Small, consistently formatted, with unusually thorough javadoc explaining JFR field-name quirks, gauge-vs-boxed-Long pitfalls, and best-effort shipping posture.

1. **[convention-violation]** `gimle-observability/src/test/java/com/gimle/observability/GimleTracingInstallTest.java:22` — class javadoc cites a design-doc section ("design doc Part B/O-13"), violating this repo's own committed convention. *Suggestion:* remove the citation and state the reasoning inline.
2. **[comment-quality]** `gimle-observability/src/test/java/com/gimle/observability/MuninnShipperTest.java:244` — comment references `MuninnShipper#meterToJsonLine`, a method that now lives on `MeterSnapshotCodec`. *Suggestion:* fix the reference.
3. **[comment-quality]** `gimle-observability/src/test/java/com/gimle/observability/GimleTracingInstallTest.java:70` — comment cites `@Timeout(10)` but the annotated method is actually `@Timeout(20)`. *Suggestion:* fix the number, or stop hardcoding it so it can't drift again.
4. **[duplication]** `gimle-observability/src/main/java/com/gimle/observability/AgentMetrics.java:16` — `AgentMetrics`/`ApiServerMetrics`/`FafnirMetrics`/`StoreMetrics`/`WorkerMetrics` duplicate the same Timer+Counter+error-Counter wiring five times, each javadoc explicitly acknowledging the sibling it copies. *Suggestion:* extract a shared internal `TaggedRequestMetrics` helper with five thin adapters.
5. **[duplication]** `gimle-observability/src/test/java/com/gimle/observability/AgentMetricsTest.java:11` — the four `*MetricsTest` classes mirror the production duplication, near-line-for-line. *Suggestion:* a shared parameterized/base test fixture for the common contract.
6. **[duplication]** `gimle-observability/src/test/java/com/gimle/observability/GimleTracingInstallTest.java:45` — an identical 15-line anonymous `SpanExporter` test double is copy-pasted four times across three test files. *Suggestion:* extract a shared `CapturingSpanExporter` test helper.
7. **[duplication]** `gimle-observability/src/main/java/com/gimle/observability/MuninnShipper.java:191` — the "join lines with `Json.write` + newline" NDJSON-building loop is duplicated identically in three places. *Suggestion:* a tiny shared `Json.writeNdjson(...)` helper in `gimle-core`.

## gimle-worker

**Overall:** Small, well-organized, with unusually thorough, purposeful comments and consistently readable, sentence-style test names. The weaknesses concentrate in `WorkerMain`.

1. **[magic-values]** `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java:148` — `FabricServiceRegistry` is constructed with six unexplained trailing positional literals, including two same-typed `0.5` values with completely different meanings. *Suggestion:* extract named locals or constants at the call site.
2. **[magic-values]** `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java:217` — `WorkerRuntime` is constructed with unexplained bare literals for concurrency/health-check tuning, unlike the file's own well-commented constants elsewhere. *Suggestion:* name these too.
3. **[method-or-class-size]** `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java:76` — `main()` is ~200 lines mixing banner printing, logging setup, control-channel connection, tracing install, registry wiring, fabric registry construction, lifecycle sink, leak tracker, controller/runtime construction, fabric server binding, background threads, and the receive loop. *Suggestion:* split into named private helpers.
4. **[structure-and-control-flow]** `gimle-worker/src/main/java/com/gimle/worker/WorkerMain.java:169` — the lifecycle-event sink is a 20+ line multi-branch lambda declared inline inside `main()`. *Suggestion:* extract to a named method.
5. **[duplication]** `gimle-worker/src/test/java/com/gimle/worker/WorkerRuntimeTest.java:97` — `startFixture`/`startFixtureWithIdentity`/`startBudgetFixture` (plus `JobHooksExecutionTest.startFixture`) repeat nearly identical ~25-line setup blocks. *Suggestion:* factor shared setup into a test-support helper.

## gimle-agent

**Overall:** Unusually well-commented for AI-generated code — almost every non-obvious decision (Tier-1 density packing, volume release semantics, TLS bootstrap/rotation ordering, crash classification) has a javadoc explaining why. The weakness is concentration in `AgentMain`.

1. **[single-responsibility]** `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java:92` — `AgentMain.java` (1629 lines) is a single static-method class mixing CLI bootstrap, TLS issuance/rotation, control-plane registration/heartbeat, gossip catalog relay, Tier-1 density packing, and Muninn shipping wiring. *Suggestion:* split into cohesive collaborators, mirroring the decomposition already applied to `WorkerProcessSupervisor`/`CapacityTracker`/`ControlChannelServer`.
2. **[structure-and-control-flow]** `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java:727` — core reconciliation methods (`reconcileAssignments`, `startInstance`, `installIntoExistingWorker`, `driveInstanceUp`, `readLoop`) take 10–17 positional parameters, several same-typed and adjacent (e.g. two `URI` args, two `Map` args), risking a silent argument swap. *Suggestion:* bundle invariant per-tick context into a single `AgentContext`-style record.
3. **[duplication]** `gimle-agent/src/test/java/com/gimle/agent/AgentMainTest.java:76` — the identical 7-argument `buildWorkerCommand(...)` call plus setup is copy-pasted across six test methods. *Suggestion:* extract a `buildDefaultWorkerCommand()` helper.
4. **[consistency]** `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java:282` — `com.gimle.fabric.catalog.CatalogDelta` is referenced fully-qualified at four call sites instead of imported, unlike every other type in the file. *Suggestion:* add the import.
5. **[method-or-class-size]** `gimle-agent/src/main/java/com/gimle/agent/AgentMain.java:727` — `reconcileAssignments` spans ~120 lines blending assignment-fetching, replacement detection, reuse/spawn decision, instance start, and a removal sweep. *Suggestion:* extract a `reconcileOneAssignment(...)` helper.

## gimle-mimir

**Overall:** Unusually well-documented for AI-generated code — javadoc explains Raft safety mechanics, leak/pruning ordering, leader-only-vs-round-robin reads. Naming is consistent (`xLocked` suffix convention, `snake_case` test names). The main risks are size and duplication — and the new disruption-budget feature (`DisruptionBudget`, rolling-set bookkeeping in `StateStore`, a sixth duplicated manifest parser helper) made both pre-existing risks measurably worse rather than better, while the feature's own new code is otherwise integrated in a style consistent with the rest of the module.

1. **[duplication]** `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentManifestParser.java:191` — `requireString`, `requireMap`, `parseModuleId`, `parsePlacement`/`parseRequiredLabels`, and `booleanField` are byte-for-byte duplicated across all five manifest parser classes. *Suggestion:* extract a shared `ManifestFields` utility, mirroring the existing `AtomicFiles` precedent.
2. **[method-or-class-size]** `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java:67` — `StateStore` is a single 2228-line class combining CRUD, disk-path layout, and hand-rolled YAML (de)serialization for ~25 unrelated resource kinds. *Suggestion:* split by resource-kind family, or at minimum separate the YAML codec methods into their own class.
3. **[duplication]** `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java:1712` — the moduleId-to-YAML-map-and-back encoding is repeated verbatim at least six times; the "null-safe Optional-from-map-value" idiom is repeated close to a dozen times. *Suggestion:* add `moduleIdToYamlMap`/`moduleIdFromYamlMap`/`optionalString` helpers once.
4. **[consistency]** `gimle-mimir/src/main/java/com/gimle/mimir/rpc/StoreClient.java:457` — `parseAddress` uses a fully-qualified `new java.net.InetSocketAddress(...)` instead of an import, the only such reference in an otherwise fully-imported file.
5. **[naming]** `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentManifestParser.java:159` — `requiredIntField`/`optionalDoubleField`/`optionalIntField` read as general-purpose but hardcode an `"autoscale."` prefix in their error messages. *Suggestion:* rename to scope-specific names, or take the field path as a parameter.
6. **(new) [duplication]** `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentManifestParser.java:107` — the new disruption-budget optional-int-field parsing helper is copy-pasted between `DeploymentManifestParser` (`optionalDisruptionIntField`) and `DaemonSetManifestParser` (`optionalIntField`) under two different names for the identical logic — finding #1's duplication pattern extended to a sixth helper, with inconsistent naming added on top. *Suggestion:* fold both into one shared helper.
7. **(new) [duplication]** `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DaemonSetManifestParser.java:73` — `parseDisruptionBudget` is duplicated near-verbatim between `DaemonSetManifestParser` and `DeploymentManifestParser` (same map validation, same `maxUnavailable` default, same exception wrapping; only `maxSurge` handling differs). *Suggestion:* extract the common "parse an optional disruption block" shape, then let each caller layer its own `maxSurge` policy on top.
8. **(new) [method-or-class-size]** `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java` — `StateStore` has grown from the already-flagged 2228 lines to ~2304 lines with this change, adding a whole new add/remove/get/clearAll block rather than shrinking the file — finding #2 getting incrementally worse with each new workload feature. *Suggestion:* as previously noted, split by resource-kind family before the next feature adds yet another block.
9. **(new) [duplication]** `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java:425` — `addRollingDaemonSetNode`/`removeRollingDaemonSetNode`/`getRollingDaemonSetNodes`/`clearAllRollingDaemonSetNodes` structurally duplicate `addRollingIndex`/`removeRollingIndex`/`getRollingIndices`/`clearAllRollingIndices` almost line for line, differing only in `String` vs `Integer` keys and DaemonSet vs. Deployment naming. *Suggestion:* extract a small generic "rolling set" helper keyed by resource name that both bookkeeping pairs delegate to.
10. **(new) [test-readability]** `gimle-mimir/src/test/java/com/gimle/mimir/raft/RaftCodecTest.java:102` — the parameterized test method `roundTripsThroughStreams` uses camelCase, inconsistent with every other test in the same class (`round_trips_an_install_snapshot_carrying_arbitrary_bytes`, etc.) and the project's mandated snake_case-sentence test naming. *Suggestion:* rename to match, e.g. `round_trips_simple_rpc_variants_through_streams`.
11. **(new) [complexity-density]** `gimle-mimir/src/main/java/com/gimle/mimir/store/StateStore.java:976` — `snapshot()`'s single `StateSnapshot` constructor call now embeds two multi-line `Collectors.toUnmodifiableMap` stream pipelines inline among roughly 25 other positional constructor arguments, making it harder to visually match each argument to its record component. *Suggestion:* extract the two rolling-set snapshot pipelines into small private helper methods, mirroring how `auditEventsSnapshotOrder()` is already broken out just below.
12. **(new) [duplication]** `gimle-mimir/src/main/java/com/gimle/mimir/manifest/DeploymentSpec.java:82` — `DeploymentSpec` now carries four overlapping telescoping back-compat constructors (grew from three when the disruption field was added), each just delegating to the canonical constructor with `Optional.empty()` defaults. *Suggestion:* if these overloads exist only for legacy/test call sites, consolidate on named/builder-style construction so a new field doesn't require stacking another overload — the same anti-pattern already flagged in `gimle-core`'s `InstanceObservation`.

## gimle-muninn

**Overall:** Small, disciplined, with consistently thorough javadoc and sentence-like tests. The cost is structural duplication in `MuninnServer`.

1. **[duplication]** `gimle-muninn/src/main/java/com/gimle/muninn/MuninnServer.java:190` — the same method-check/try-catch-finally scaffolding is copy-pasted across all eight route handlers. *Suggestion:* a small `handle(exchange, expectedMethod, logLabel, RouteBody body)` template method.
2. **[duplication]** `gimle-muninn/src/main/java/com/gimle/muninn/MuninnServer.java:291` — the `processKind`/`processId` path-parsing-and-validation block is duplicated verbatim across four handlers (ingest+read × metrics+traces), plus a similar pairwise duplication for node/instance log handlers.
3. **[convention-violation]** `gimle-muninn/src/main/java/com/gimle/muninn/MuninnDayFileStore.java:21` — class javadoc cites internal backlog-ticket IDs ("B-9/B-11") *and* is now factually stale — it describes metrics/traces as not-yet-landed when this module already ships and tests them. *Suggestion:* rewrite to state current, true scope; drop the ticket reference.
4. **[consistency]** `gimle-muninn/src/main/java/com/gimle/muninn/RetentionSweeper.java:34` — constructor parameter spelled out as fully-qualified `java.time.Duration` instead of imported, unlike every other `java.time` usage in the module (a related test also fully-qualifies `ZoneOffset` unnecessarily).
5. **[duplication]** `gimle-muninn/src/test/java/com/gimle/muninn/MuninnServerLogsIngestTest.java:54` — identical private `post`/`get` HTTP helpers and identical setup/teardown are copy-pasted across three ingest test classes. *Suggestion:* a shared abstract test base.

## gimle-fafnir

**Overall:** Unusually well-documented — consistent naming, named constants, correct `@Test` snake_case convention, and javadoc that explains genuinely non-obvious design choices (optimistic secret versioning, key-ring rotation, legacy-ciphertext fallback).

1. **[duplication]** `gimle-fafnir/src/test/java/com/gimle/fafnir/FafnirSecretsAuthzTest.java:458` — TLS/certificate test-fixture helpers are copy-pasted nearly verbatim across three test classes, despite the module already having extracted `InProcessStore` for exactly this kind of risk. *Suggestion:* extract a shared `TlsTestFixtures` class.
2. **[single-responsibility]** `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirServer.java:481` — `authorizeSecrets` mixes principal resolution, rate-limit checking, the RBAC decision, dual audit logging, metrics, and HTTP response writing in one ~58-line method. *Suggestion:* split into named `recordAudit`/`decideAllowed` helpers.
3. **[duplication]** `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirMain.java:103` — the `metricsShipper` and `tracesShipper` construction blocks in `main` are near-identical copy-pasted logic. *Suggestion:* extract a `shipperFor(...)` helper.
4. **[structure-and-control-flow]** `gimle-fafnir/src/main/java/com/gimle/fafnir/FafnirServer.java:330` — `handleSecrets` routes on URI-part-count with no comment stating which URL shape each branch handles. *Suggestion:* add a comment per branch naming its URL pattern.
5. **[consistency]** `gimle-fafnir/src/test/java/com/gimle/fafnir/FafnirServerTlsTest.java:191` — this test class routes path construction through a private one-line `fileName(...)` wrapper that no sibling test class uses, with no explained purpose.

## gimle-controlplane

**Overall:** Heavily and thoughtfully commented; tests read as clear behavioral sentences. The main risk is concentrated in `ApiServer.java`, a single 3599-line God class. The disruption-budget change was carried through `DeploymentReconciler`/`DaemonSetReconciler`/`ApiServer`/the rolling-update test consistently and mostly re-worded surrounding comments to match — except two now-stale references to the old singular `rollingIndex` concept the refactor replaced with a per-index `Set`.

1. **[method-or-class-size]** `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java:126` — `ApiServer` handles HTTP routing, JSON (de)serialization, RBAC, PKI/CSR issuance, secrets/log/metrics proxying, and session management all in one file, far larger than a human can hold in working memory at once even with clear section-banner comments. *Suggestion:* split by concern into cooperating handler classes.
2. **[duplication]** `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java:502` — `handleDeployment`/`handleJob`/`handleCronJob`/`handleDaemonSet`/`handleStatefulSet` repeat the identical try/switch/catch dispatch skeleton five times. *Suggestion:* a shared generic dispatcher.
3. **[duplication]** `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java:589` — four near-identical `withArtifactSha256(...)` overloads each manually rebuild a record field-by-field. *Suggestion:* a generic "copy record with field X changed" utility, or a method on each spec record itself.
4. **[duplication]** `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java:1126` — `findObservation`, `findObservationForDaemonSetAssignment`, `findObservationForStatefulSetAssignment`, `findObservationForJobRun` are structurally identical apart from which name/index fields they match. *Suggestion:* one shared helper parameterized on name/index.
5. **[duplication]** `gimle-controlplane/src/main/java/com/gimle/controlplane/api/ApiServer.java:2800` — `handleTracesHistory` is a near-verbatim copy of `handleMetricsHistory`, and its own javadoc admits it. *Suggestion:* factor out a shared `handleHistoryProxy(...)`.
6. **[complexity-density]** `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/QuotaReconciler.java:64` — a single-element `Boolean[]` array is used purely as a mutable box to escape lambda-capture rules. *Suggestion:* replace the `ifPresent(lambda)` with a plain `if` block.
7. **[magic-values]** `gimle-controlplane/src/main/java/com/gimle/controlplane/tenant/TenantUsage.java:41` — `excludingDeploymentName` uses the string literal `""` as an undocumented-by-example sentinel, inconsistent with the module's own `Optional`-based convention for absence, and untested. *Suggestion:* change to `Optional<String>`.
8. **[method-or-class-size]** `gimle-controlplane/src/main/java/com/gimle/controlplane/autoscale/AutoscaleReconciler.java:66` — `reconcileDeployment` is a ~115-line method mixing several abstraction levels (artifact reading, per-signal averaging, ideal-replica computation, combination-mode selection, clamping, single-step adjustment). *Suggestion:* split into named steps.
9. **(new) [comment-quality]** `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/DeploymentReconciler.java:281` — javadoc on `isReady()` still says `handleRollingUpdate` "clears rollingIndex," a name retired by the disruption-budget refactor (the single `Optional<Integer> rollingIndex` became a per-index `Set` via `getRollingIndices`/`addRollingIndex`/`removeRollingIndex`); every *other* comment in this file was updated to the new plural phrasing, just not this one. *Suggestion:* reword to "clear this index's in-flight marker," matching the rest of the file.
10. **(new) [comment-quality]** `gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/StatefulSetReconciler.java:57` — class javadoc still contrasts StatefulSet's rolling-update marker with `DeploymentReconciler`'s "rollingIndex," a name that no longer exists there — collateral staleness in a file the disruption-budget commit didn't itself touch. *Suggestion:* update the cross-reference to "DeploymentReconciler's own in-flight index set."
11. **(new) [magic-values]** `gimle-controlplane/src/test/java/com/gimle/controlplane/reconcile/DeploymentReconcilerRollingUpdateTest.java:395` — the new drain loop's bailout bound (10 ticks) is an unexplained literal, unlike the rest of this test file which otherwise explains every non-obvious choice. *Suggestion:* add a one-line comment on why 10 ticks is guaranteed enough (or is just a safety margin) for 5 replicas at `maxUnavailable=2`.

*(Also noted: `ApiServer.java:152` has several 5–10-line paragraph-style comments stacked on adjacent private fields, making the declaration block harder to scan than it needs to be — worth trimming to one sentence per field with deeper rationale moved to the class javadoc. The incremental round's reviewer independently flagged the same `ApiServer.java` God-class problem as finding #1 above, noting the disruption-budget change itself had to touch two separate near-duplicate `withArtifactSha256`-rebuilding call sites — Deployment and DaemonSet — because of it, i.e. finding #3 above is exactly the kind of miss this file's size invites.)*

## gimle-fabric

**Overall:** Unusually easy to read for its complexity — consistent, precise naming (`Ping`/`PingReq`/`Ack`/`IndirectAck`, `TAG_*` constants), javadoc explaining genuinely non-obvious protocol/concurrency decisions (DTLS initiator tie-breaking, Lifeguard-style local-health multiplier, panic-mode breaker ejection).

1. **[structure-and-control-flow]** `gimle-fabric/src/main/java/com/gimle/fabric/registry/FabricServiceRegistry.java:88` — four telescoping constructors (7–13 positional parameters) force call sites into unlabeled positional `int`/`double`/`Duration` arguments. *Suggestion:* collapse breaker-tuning parameters into a `CircuitBreakerPolicy` config record or builder.
2. **[duplication]** `gimle-fabric/src/main/java/com/gimle/fabric/transport/FabricServer.java:429` — `decodeTraceState` and `decodeBaggage` contain the identical key=value comma-split parsing loop. *Suggestion:* extract a shared `parsePairs(...)` helper.
3. **[method-or-class-size]** `gimle-fabric/src/main/java/com/gimle/fabric/cluster/GossipMember.java:68` — nearly 1,000 lines combining the SWIM state machine, the UDP/DTLS receive loop, DTLS session lifecycle, and membership merge/refutation logic, despite good internal section comments. *Suggestion:* extract DTLS session bookkeeping into a `SecureGossipTransport` collaborator.

## gimle-cli

**Overall:** Unusually clean and consistent for its size — every resource command follows the identical get/set/delete shape, javadoc explains genuine "why," and there's no dead code.

1. **[convention-violation]** `gimle-cli/src/main/java/com/gimle/cli/AuditCommand.java:9` — class javadoc cites an external design-doc filename (`OBSERVABILITY_AUDIT_DESIGN.md`), which CLAUDE.md explicitly forbids. *Suggestion:* replace with an inline explanation.
2. **[duplication]** `gimle-cli/src/main/java/com/gimle/cli/DeploymentsCommand.java:72` — the identical ~20-line `requireFileFlag`/`extractName` manifest-parsing block is duplicated verbatim across six classes (`GimleCli` plus five resource commands). *Suggestion:* extract a shared `ManifestFiles`/`YamlManifests` helper.
3. **[consistency]** `gimle-cli/src/main/java/com/gimle/cli/RolesCommand.java:76` — `Locale.ROOT` is referenced fully-qualified inline instead of imported, unlike every other class in the module.

## gimle-maven-plugin

**Overall:** Small, consistently structured, unusually well-commented for generated code — every Mojo follows the same shape, and port-default cross-references between Mojos are explained inline. The outlier is `BootstrapMojo`.

1. **[convention-violation]** `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/FafnirMojo.java:12` — class javadoc parenthetically cites "(design doc)" as the source of a claim, which CLAUDE.md explicitly forbids. *Suggestion:* state the actual why inline.
2. **[single-responsibility]** `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/BootstrapMojo.java:64` — at ~650 lines, roughly 6–13× the size of every sibling Mojo (all under ~100 lines), mixing TLS bootstrap, five-process orchestration, CLI-output scraping, readiness polling, and shutdown. *Suggestion:* split into a `ClusterProcessLauncher` and a `ClusterCliClient` collaborator.
3. **[duplication]** `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/BootstrapMojo.java:267` — `spawnStore`/`spawnFafnir`/`spawnMuninn`/`spawnControlPlane` repeat the same command-building skeleton, differing only in specific args. *Suggestion:* extract a shared helper parameterized on cert name, main class, args, log file.
4. **[duplication]** `gimle-maven-plugin/src/main/java/com/gimle/mavenplugin/BootstrapMojo.java:443` — the same four-parameter clump (`cliClasspath`, `tls`, `tlsDir`, `controlPlaneHost`) is threaded individually through six private methods. *Suggestion:* a `ClusterEndpoint` record.

*(Also noted: `BootstrapMojo.java:470` scrapes CLI output via an embedded regex and a raw JSON substring match with no comment tying either literal back to the CLI code that produces it — worth naming as constants with a cross-reference comment.)*

## gimle-examples/hello-module

**Overall:** Deliberately minimal (one class, module-info, descriptor YAML, deployment YAML, no tests). Clean and easy to read; follows repo conventions throughout. **No findings.**

## gimle-examples/greeter-provider

**Overall:** Small (four classes), notably clean — self-explanatory naming, single clear responsibility per class, comments used sparingly but explaining genuinely non-obvious decisions. **No findings.**

## gimle-examples/greeter-consumer

**Overall:** Five-file sample module that reads cleanly, with real effort invested in explaining non-obvious decisions (background virtual-thread fabric call, MDC capture/restore across the thread hop). **No findings.**

## gimle-examples/greeter-load-generator

**Overall:** Five-file module reading cleanly, consistent with its `greeter-provider`/`greeter-consumer` siblings, with every non-obvious decision explained inline. **No findings.**

## gimle-smoke-tests

**Overall:** Unusually well-documented for a smoke-test suite — every test method's javadoc explains not just what is asserted but why. The main liability concentrates in one file, `GreeterSmokeClusterSupport.java` (1961 lines).

1. **[duplication]** `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterSmokeClusterSupport.java:309` — five `buildXProviderJar` factory methods (~80–110 lines each) are nearly identical boilerplate, differing only in the `greet` lambda body and liveness/readiness values (~450 lines total). *Suggestion:* a single `buildGreeterProviderVariant(...)` helper taking only what varies.
2. **[single-responsibility]** `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterSmokeClusterSupport.java:57` — a single 1961-line class combining subprocess spawning, synthetic module-jar fabrication, HTTP deployment/tenant/secret submission, HTTP-polling assertion helpers, Playwright launching, and port/process utilities. *Suggestion:* split into narrower, composed fixtures.
3. **[convention-violation]** `gimle-smoke-tests/src/test/java/com/gimle/smoketests/RaftResilienceIT.java:226` — an `@Disabled` reason and surrounding comments cite `QA_FINDINGS.md`, a planning artifact CLAUDE.md explicitly forbids citing even when checked in. *Suggestion:* inline the actual finding and rationale.
4. **[convention-violation]** `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GossipFailureDetectionIT.java:115` — same `QA_FINDINGS.md` citation pattern. *Suggestion:* inline the fix/rationale directly.
5. **[consistency]** `gimle-smoke-tests/src/test/java/com/gimle/smoketests/AutoscaleIT.java:42` — about half the `*IT` classes import `TimeUnit` and use the short form in `@Timeout`, the other half fully-qualify it inline every time. *Suggestion:* standardize on the imported short form.
6. **[duplication]** `gimle-smoke-tests/src/test/java/com/gimle/smoketests/RollingUpdateIT.java:89` — an identical ~18-line background-sampler block (`AtomicInteger`/`AtomicBoolean`/virtual-thread loop) is duplicated between the file's two test methods. *Suggestion:* extract a `startMinActiveSampler(...)` helper.
7. **[convention-violation]** `gimle-smoke-tests/src/test/java/com/gimle/smoketests/QuotaIT.java:66` — `assertTrue(!isQuotaViolating(...), ...)` used instead of the already-statically-imported `assertFalse(...)`, inconsistent with sibling test classes.
8. **[duplication]** `gimle-smoke-tests/src/test/java/com/gimle/smoketests/GreeterSmokeClusterSupport.java:1727` — nine separate polling helpers each independently build an `HttpRequest`, call `httpClient.send`, and catch-and-swallow. *Suggestion:* factor out a shared `tryGet(url)` primitive.

## gimle-docs

**Overall:** No Java source (a Docusaurus/Bun static site wrapped as a `pom`-packaging Maven module); the small amount of TS/TSX/CSS it has is clean and well-named. The recurring defect is systemic: this is the module that most violates the repo's own "never cite a planning artifact" rule — and does so on the *published, user-facing* docs site itself, not just in internal comments. The three files touched by the disruption-budget commit (`control-plane.md`, `roadmap.md`, `manifest-schema.md`) are consistent in tone with the rest of the site, but re-review confirmed all six previously-flagged issues are still unaddressed, and surfaced two more instances of the same pattern (a `design doc §N` citation, distinct from the `claudedocs/` path citations) in files this commit touched.

1. **[convention-violation]** `gimle-docs/docs/architecture/transport-security.md:9` — the published page tells readers "Full design: `claudedocs/tls-transport-security-design.md`," a path that doesn't exist for anyone without that gitignored file. *Suggestion:* inline the relevant rationale or drop the pointer.
2. **[convention-violation]** `gimle-docs/docusaurus.config.ts:17` (and again at line 55) — build-config comments cite `claudedocs/docs-site-design.md §1` to justify a placeholder `url: 'https://example.com'`. *Suggestion:* state the actual reason inline.
3. **[convention-violation]** `gimle-docs/README.md:5` — README directs readers to `claudedocs/docs-site-design.md` for "the design rationale." *Suggestion:* summarize the rationale directly in the README.
4. **[convention-violation]** `gimle-docs/src/css/custom.css:22` — a theme-color comment cites `claudedocs/docs-site-design.md §1.6`. *Suggestion:* state inline why the re-theme is partial.
5. **[convention-violation]** `gimle-docs/docs/contributing/conventions.md:74` — the published "repo hygiene" page names `claudedocs/` as holding design notes/QA findings, immediately after stating the very rule the module's other pages break. Arguably compliant on its own, but worth fixing alongside the other four for internal consistency.
6. **[dead-or-commented-out-code]** `gimle-docs/sidebars.ts:20` — a leftover, never-used `create-docusaurus` scaffold example (`tutorialSidebar`, commented out) plus generic top-of-file JSDoc about generic Docusaurus concepts rather than this project's actual config. *Suggestion:* delete both.
7. **(new) [convention-violation]** `gimle-docs/docs/reference/manifest-schema.md:52` — the volume field description cites "priority-3 design doc §5a," an external planning artifact by section number, in a file this commit modified. *Suggestion:* drop the parenthetical; the sentence already stands on its own without it.
8. **(new) [convention-violation]** `gimle-docs/docs/contributing/roadmap.md:62` — roadmap prose cites "design doc §6" as the source for the worker-to-agent metrics/traces relay mechanism, in a file this commit modified. *Suggestion:* remove the "§6" pointer; the surrounding sentence already explains the relay mechanism (`ControlMessage.MetricsSnapshot`/`TracesSnapshot`) without it.

---

*Generated by 20 independent per-module review agents; each agent read only its own module's source and was instructed to ignore performance, security, and correctness concerns. `gimle-controlplane`, `gimle-mimir`, and `gimle-docs` were independently re-reviewed in full after the branch was rebased onto `master`'s disruption-budget commits.*
