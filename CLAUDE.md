# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Phases 1–3 are implemented and committed: `gimle-core`, `gimle-module`, `gimle-os`, `gimle-worker`, `gimle-agent`, `gimle-controlplane`, and `gimle-observability` all exist as working Maven modules with tests. `gimle-api`, `gimle-fabric`, and `gimle-cli` don't exist yet — later-phase work. Build/test with `mvn verify` from the repo root (requires JDK 25 on `PATH`/`JAVA_HOME`).

Read `gimle-PROJECT-v2.md` in full before doing any non-trivial work — it is the authoritative spec. This CLAUDE.md summarizes it for quick orientation only. `claudedocs/phase{1,2,3}-*-design.md` (gitignored, local to this checkout) hold the as-built design detail — including revisions made mid-implementation — for each phase already done; check them before assuming the top-level spec alone reflects the latest decisions on a given area.

## What Gimlé is

A fully-Java application platform that combines Karaf/OSGi-style dynamic module lifecycle with Kubernetes-style declarative orchestration (self-healing, scaling, load balancing, service discovery, observability) — implemented entirely on the JVM with no containers, no external orchestrator, and no non-Java runtime dependencies.

Non-goals worth remembering when reviewing design choices: not OSGi-compliant (no Felix/Equinox, JPMS `ModuleLayer` instead), not Kubernetes-API-compatible (no CRDs/kubectl/OCI images), not a general untrusted-workload runtime, and not built on Spring Boot/Quarkus/Netty/an existing service mesh — the module system, supervisor, control plane, and service fabric are the point of the project, not glue over existing frameworks.

## Host language & load-bearing JDK features

Java 25 (LTS). These are architectural dependencies, not incidental choices — don't suggest alternatives that bypass them:

- `ModuleLayer` / dynamic JPMS — module system foundation, replaces OSGi classloading
- `Process` API (`onExit`, `destroyForcibly`, `pid`) — worker JVM supervision
- FFM API (`Linker`, `MemorySegment`) — direct libc/syscall access (namespaces, CPU affinity), for the deferred kernel-level resource limiter and Tier 3 namespaces (not built yet, see "Core architecture" below); **no JNI, no native code anywhere**, now or once it lands
- Virtual threads (unpinned, JEP 491), Scoped values (JEP 506), Structured concurrency
- JFR event streaming — per-module resource accounting
- AppCDS / CDS archives — sub-second worker JVM startup
- `jlink` / `jpackage` — minimal per-node-role runtime images
- Sealed interfaces + records for state/event/protocol types

## Core architecture: tiered isolation

The central design claim is container-grade isolation and classloader-grade density in one system, selected per workload via the module manifest:

```
Machine (Node Agent, JVM)
 └── Worker JVM  ── memory/CPU boundary, own -Xmx, own resource limiter
      └── Module ── ModuleLayer + classloader, soft accounting
           └── Instance ── bounded virtual-thread scheduler
```

- **Tier 1** — module in a shared worker JVM. Millisecond deploys, classloader-level isolation, soft JFR-based accounting. Density win.
- **Tier 2** — module in a dedicated worker JVM. Sub-second deploy (AppCDS), hard `-Xmx`/CPU ceiling, independent crash domain. Kubernetes-equivalent guarantee, available per module.
- **Tier 3** — worker JVM in a Linux namespace (via FFM `unshare`/`setns`). For hostile-neighbour scenarios.

**Platform independence first, platform-specific enforcement later** (deliberate design revision — see `claudedocs/phase2-worker-runtime-design.md` §1 and §2.4). Tier 1/2 limits are enforced today entirely through the portable `ResourceLimiter` interface (`gimle-os`) and its only current implementation, `PortableJvmFlagsResourceLimiter` — `-Xmx`/`ActiveProcessorCount`, identical on Linux/macOS/Windows, zero OS-specific code. Real kernel-level enforcement (cgroup v2 on Linux via plain `java.nio.file` I/O against `/sys/fs/cgroup` — no containerd/runc equivalent needed) is a deliberately deferred second `ResourceLimiter` implementation, not a parallel path built alongside the portable one. Tier 3 (FFM downcalls to `unshare`/`setns`) is unimplemented on every platform today and rejected outright (`GimleIsolationException`) rather than silently downgraded — "not built yet," not "your platform doesn't support it." Don't add cgroup/FFM code, or platform-detection branching, to satisfy a capability nothing yet consumes — that's exactly the speculative work this revision avoids.

## Node topology

Three Java process roles, nothing else runs on the machine:

- **Node Agent** — one per machine, owns worker `Process` lifecycle and resource-limit assignment (portable JVM flags today, see "Core architecture" above), reports capacity/state, never runs user code.
- **Worker JVM** — hosts module instances in `ModuleLayer`s, reports health/metrics to its agent, disposable by design.
- **Control Plane** — Raft-replicated, one or more JVMs: API server, state store, scheduler, reconcilers.

Node/worker/module failure are distinct events with distinct recovery costs (seconds/sub-second/milliseconds) and are reconciled accordingly.

## Module system

- A module artifact = JAR + `gimle-module.yaml` (name, version, required-module version ranges, exported services, isolation tier, resource requests/limits, health probe class, lifecycle hooks).
- Each instance gets its own `ModuleLayer`/classloader parented on a shared platform layer (JDK + Gimlé service API). Hoisting common libraries into shared layers is the density lever and must be an explicit, measured decision.
- Lifecycle: `INSTALLED → RESOLVED → STARTING → ACTIVE → STOPPING → UNINSTALLED` (deliberately OSGi-like).
- Hot redeploy: install new version alongside old, drain, dispose old layer.
- **Classloader leak detection is first-class**: after undeploy, a `PhantomReference` to the disposed layer's loader is held; if it survives a configurable window, a leak is reported with the retaining path via heap walk. Redeploy-in-a-loop with flat metaspace is a mandatory acceptance test. A module that leaks anyway can be moved to Tier 2, where undeploy just kills a JVM.

## Control plane

- **API server** accepts manifests (desired state), persisted to the state store.
- **State store** — embedded, Raft-backed for HA (single-node until Phase 5).
- **Scheduler** places instances by resource requests, isolation tier, anti-affinity (replicas of one module must not share a worker JVM), and machine load — a two-dimensional bin-packing problem (resources × tier).
- **Reconcilers** — one control loop per resource kind, **level-triggered, not edge-triggered**: must converge from any starting state, including after missing every event. This is the hardest-to-test and most important correctness property in the codebase — reconciler changes need convergence tests from arbitrary starting states, not just the happy-path transition.
- **Membership/failure detection** — SWIM-style gossip over UDP between node agents, off the control plane's critical path.

## Service fabric

- Modules publish/consume services via a registry keyed by interface + version.
- Same-worker calls are direct in-JVM invocations (no serialization/network/proxy). Cross-worker-same-machine uses a Unix domain socket with a compact binary codec. Cross-machine uses the same codec over TCP with virtual-thread-per-connection.
- Load balancing prefers locality: same-worker → same-machine → remote (least-outstanding-requests), with circuit breaking/outlier ejection at the registry level.

## Health, scaling, self-healing

- Probes are Java interfaces (`LivenessProbe`/`ReadinessProbe`) called directly by the worker — no HTTP, no sidecar.
- Tiered self-healing matches isolation tiers (module dispose+reinstantiate → worker `destroyForcibly`+respawn → machine-level reschedule), with automatic escalation and `CrashLoopBackOff`-style backoff.
- Horizontal scaling driven by per-module metrics (request rate, latency, queue depth, allocation rate); scale-up may pack onto the same worker, which is why anti-affinity must stay configurable.

## Observability

Micrometer for per-module metrics, OpenTelemetry tracing propagated via scoped values (including across in-JVM hops), JFR-backed per-module allocation/CPU accounting (what makes Tier 1 soft limits enforceable), and a structured, queryable event log of every lifecycle/reconciliation decision.

## Project structure (multi-module Maven)

- `gimle-core` — shared model/domain types, exceptions, logging config
- `gimle-module` — descriptor model, resolver, `ModuleLayer` construction, lifecycle state machine, leak detection
- `gimle-api` — *(not created yet)* platform service API exposed to hosted modules (probes, service registry, config, metrics) — probe/service-registry types currently live in `gimle-module` instead
- `gimle-os` — resource limiting (`ResourceLimiter`); portable JVM-flags implementation only today, kernel-level cgroup v2 deferred (see "Core architecture" above)
- `gimle-worker` — worker JVM runtime: module hosting, schedulers, probing, local registry
- `gimle-agent` — node agent: worker supervision, resource assignment, capacity reporting
- `gimle-controlplane` — API server, state store, scheduler, reconcilers (Raft still single-node)
- `gimle-fabric` — *(not created yet)* service registry, three-path invocation, load balancing, circuit breaking, gossip membership
- `gimle-observability` — metrics, tracing, JFR accounting, event log
- `gimle-cli` — *(not created yet)* control-plane client, agent launcher, worker launcher

## Conventions (binding, not optional)

- **Build**: Maven.
- **Formatting**: Google Java Format, enforced via `fmt-maven-plugin` in CI and pre-commit.
- **Method naming**: standard Java `camelCase` everywhere — production code, JUnit lifecycle hooks (`@BeforeEach`/`@AfterEach`/`@BeforeAll`/`@AfterAll`), and private/helper methods in test classes. The one exception: methods directly annotated `@Test` are `snake_case`, so a test's name reads as a sentence describing the behavior it verifies. Enforced by two Checkstyle `MethodName` instances (one scoped to `@Test` methods, one to everything else) via XPath-based suppressions in `checkstyle-suppressions.xml`, not inline `@SuppressWarnings`.
- **No checked exceptions anywhere.** Gimlé failures use dedicated unchecked types in `gimle-core` (`GimleResolutionException`, `GimleLifecycleException`, `GimleSchedulingException`, `GimleManifestException`, `GimleClusterException`, `GimleIsolationException`), all extending `RuntimeException`. Control-plane errors map to structured API responses, not propagated stack traces.
- **Immutability**: records / `List.of`/unmodifiable collections preferred everywhere feasible. Desired state, observed state, and reconciliation events are strictly immutable snapshots — a reconciler reads a snapshot and returns actions, never mutates in place.
- **`final`** on variables, fields, and parameters wherever possible.
- **No Lombok.** Plain Java (records, standard getters/constructors).
- **`@SuppressWarnings` is a last resort, not a shortcut.** Before reaching for it, fix the actual type issue: extract the unchecked cast into a single well-named, documented helper (e.g. `Json.asObject`/`Json.asObjectList` in `gimle-core`) so at most one place in the codebase carries the suppression instead of every call site; or restructure to avoid the cast entirely. Scattering `@SuppressWarnings` through test methods to silence casts from a shared untyped/erased API (JSON trees, reflective lookups) is exactly the case a shared helper should absorb instead. The narrow case where it's genuinely unavoidable — a typesafe heterogeneous container keyed by a `Class<T>` witness token (see `SimpleServiceRegistry.lookup`) — is the reference example for "no other option"; if you can't point to something structurally equivalent, don't add the annotation.
- **No JNI, no native code.** OS interaction only via `java.nio.file` or FFM downcalls.
- **Logging**: SLF4J API + Logback binding, configured once in `gimle-core`, inherited by all modules; hosted modules see the platform logging API through the shared layer rather than bundling their own binding.
- **Comments**: clear names/small methods over Javadoc; add a comment only where logic is genuinely non-obvious (e.g. layer parent selection, leak-detection reference handling, FFM struct layouts, reconciler convergence edge cases). Applies to test code too.
- **Test coverage**: cover both happy paths and failure paths (unresolvable dependency, version conflict, probe timeout, worker OOM, network partition, no feasible placement, corrupt manifest, cgroup write failure). Reconcilers additionally require convergence tests from arbitrary starting states; the module system requires a repeated-redeploy leak test; the supervisor requires kill-and-recover tests at every tier.
- **Git hooks**: `commit-msg` rejects any commit message mentioning an AI assistant (Claude, Copilot, ChatGPT, etc.) — do not attribute commits to AI tooling. Commit messages follow Conventional Commits (`feat`, `fix`, `chore`, `refactor`, `docs`, `test`, ...), short subject, max 3 lines total. `pre-commit` runs `mvn verify` and blocks on failure.
- **Repo hygiene**: commit only essential source and config. No generated reports or ad-hoc markdown files except `CLAUDE.md`/`README.md`. `claudedocs/` is gitignored.

## Naming

Norse/Viking naming line (consistent with Drakkar, Þjappa, Skald, Bifrost, Galdr, Muninn) — keep new component/tool names in that register.
