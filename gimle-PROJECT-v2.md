# Gimlé

A **fully-Java application platform** combining Karaf/OSGi's dynamic module lifecycle with Kubernetes' declarative orchestration — self-healing, scaling, load balancing, service discovery, and observability — implemented end to end on the JVM, with no containers, no external orchestrator, and no non-Java runtime dependencies.

## Goal

Build the platform Karaf and Kubernetes would be if they were one system written entirely in Java: modules as the deployment unit, JVMs as the isolation boundary, machines as the substrate, and a declarative control plane reconciling all three.

The wager is that the JVM and its ecosystem now provide every primitive an orchestrator needs — process supervision, resource limits, fast startup, health instrumentation, dynamic module graphs, high-performance networking — and that assembling them in Java yields something both **denser than containers** (module deploy = classloader work, milliseconds) and **simpler than Kubernetes** (one runtime, one language, one debugger, no YAML-to-container-to-cgroup translation layers).

## Non-goals

- Not an OSGi implementation. The OSGi specification is not a target and Felix/Equinox are not dependencies — dynamic JPMS layers replace OSGi's classloader machinery.
- Not Kubernetes-API-compatible. No CRDs, no kubectl interop, no OCI images.
- Not a general workload runtime. Gimlé orchestrates JVM code. Running arbitrary untrusted native workloads is what containers are for, and this project does not compete there.
- Not built on Spring Boot, Quarkus, Netty, or an existing service mesh. The module system, supervisor, control plane, and service fabric are the learning targets.

## Host language

**Java 25** (LTS). Every major feature below is load-bearing, not incidental:

| Feature | Role in Gimlé |
|---|---|
| `ModuleLayer` / dynamic JPMS | Module system foundation — dynamic module graphs with real encapsulation, disposable on undeploy |
| `Process` API (`onExit`, `destroyForcibly`, `pid`) | Worker JVM supervision — spawn, monitor, kill, restart, entirely from Java |
| FFM API (`Linker`, `MemorySegment`) | Direct libc/syscall access for namespace and affinity control without JNI or native code — for the deferred kernel-level resource limiter and Tier 3 namespaces (Phase 5, not built yet) |
| Virtual threads, unpinned (JEP 491) | Thousands of in-flight requests per worker; per-module bounded schedulers |
| Scoped values (JEP 506) | Module identity, tenant, and trace context propagation without `ThreadLocal` leaks |
| Structured concurrency | Reconciliation loops and health-probe fan-out with automatic cancellation and deadlines |
| JFR event streaming | Per-module allocation and CPU accounting from the JVM's own instrumentation |
| AppCDS / CDS archives | Sub-second worker JVM startup — what makes JVM-level restart cheap enough to be routine |
| `jlink` / `jpackage` | Minimal runtime images per node role (direct reuse of the slim-jre work) |
| Sealed interfaces + records | Desired state, observed state, reconciliation events, protocol frames |

## Isolation strategy — the core architecture

Kubernetes gets isolation from cgroups and namespaces. Gimlé reaches equivalent guarantees in Java by **tiering isolation across three levels**, choosing the cheapest level that satisfies a module's declared requirements:

```
Machine (Node Agent, JVM)
 └── Worker JVM  ── memory/CPU boundary, own -Xmx, own resource limiter
      └── Module ── ModuleLayer + classloader, soft accounting
           └── Instance ── bounded virtual-thread scheduler
```

**Tier 1 — Module inside a shared worker JVM.** Deploy cost: milliseconds, megabytes. Isolation: classloader-level encapsulation, soft resource accounting via JFR, bounded thread scheduler. Appropriate for trusted co-tenant modules. This is the Karaf half and the density win.

**Tier 2 — Module in a dedicated worker JVM.** Deploy cost: sub-second with AppCDS. Isolation: hard memory ceiling (`-Xmx`), hard CPU ceiling (`ActiveProcessorCount`), independent crash domain. This is the Kubernetes-equivalent guarantee, and it is available *per module* as a manifest setting rather than as an all-or-nothing platform decision. **Platform independence first:** this ceiling is enforced today purely through JVM flags (`ResourceLimiter` / `PortableJvmFlagsResourceLimiter` in `gimle-os`), identically on every OS — no cgroup, no kernel-level enforcement. A second, kernel-enforced `ResourceLimiter` implementation is a deliberately deferred later addition behind the same interface, not a parallel path built alongside the portable one from day one.

**Tier 3 — Worker JVM in a namespace.** Additional filesystem and network isolation via Linux namespaces, entered through FFM downcalls to `unshare`/`setns`. For hostile-neighbour scenarios. Not implemented yet, on any platform — requests are rejected outright rather than silently downgraded.

The manifest declares what a module needs; the scheduler places it accordingly. A module asking for a hard memory limit gets its own worker; one that doesn't shares. **This is the design's central claim: container-grade isolation and classloader-grade density in one system, selected per workload** — the tiering model holds today even though the strongest enforcement mechanisms arrive incrementally behind it.

## Resource control: platform-independent first, kernel-level later

Building genuinely platform-specific enforcement (cgroups, namespaces) before the portable foundation was solid would get the emphasis backwards for a project whose whole premise is a JVM-native platform. So the near-term reality is: Tier 1 and Tier 2 are enforced purely through portable JVM mechanisms (`-Xmx`, `ActiveProcessorCount`) behind the `ResourceLimiter` interface — identical on Linux, macOS, and Windows, no OS-specific code anywhere in that path today.

The enabling fact that makes the later, kernel-level work cheap once it's picked up: **Linux resource control on cgroup v2 is a filesystem interface.** Creating a cgroup is `Files.createDirectory`; setting a memory ceiling is `Files.writeString` to `memory.max`; CPU weight is `cpu.weight`; reading usage is `Files.readString` of `memory.current`. No syscalls, no native code, no privileged runtime — plain `java.nio.file` against `/sys/fs/cgroup`. What genuinely requires syscalls — `unshare` for namespace creation, `setns` for entry, `sched_setaffinity` for CPU pinning — is reachable through the **FFM API** as ordinary downcalls to libc, no JNI required. So the resource-management layer that Kubernetes delegates to containerd and runc will be, on Linux, a few hundred lines of Java — but it arrives as a second `ResourceLimiter` implementation once the portable foundation has proven itself, not as an early spike gating everything else.

Tier 3 (namespaces) is rejected outright today, on every platform uniformly — "not implemented yet," not "your platform doesn't support it." Platform detection for choosing between resource-limiter implementations is itself deferred until there's a second implementation to choose between.

## Node topology

Three Java processes, no other runtime on the machine:

- **Node Agent** — one JVM per machine. Owns the machine: spawns and supervises worker JVMs via the `Process` API, assigns their resource limits (portable JVM flags today, see "Resource control" above), reports machine capacity and observed state to the control plane, executes placement directives. Never runs user code, so it cannot be crashed by one.
- **Worker JVM** — hosts module instances in `ModuleLayer`s. Started with limits derived from its assigned modules' requests. Reports health and per-module metrics to its agent over a local channel. Disposable by design.
- **Control Plane** — one or more JVMs, Raft-replicated. API server, state store, scheduler, reconcilers.

Node failure, worker failure, and module failure are three distinct events with three distinct recovery costs — milliseconds, sub-second, seconds — and the reconcilers treat them accordingly.

## Module system

- A **module artifact** is a JAR plus a `gimle-module.yaml` descriptor: name, version, required modules with version ranges, exported services, isolation tier, resource requests and limits, health probe class, lifecycle hooks.
- Each deployed instance gets its own `ModuleLayer` and classloader, parented on a shared platform layer holding the JDK and Gimlé's service API. Common libraries hoisted into shared layers are the density lever — that decision must be explicit and measurable.
- **Lifecycle**: `INSTALLED → RESOLVED → STARTING → ACTIVE → STOPPING → UNINSTALLED`, deliberately mirroring OSGi's, because it is the correct state machine and there is no reason to invent a worse one.
- **Hot redeploy**: install the new version alongside the old, drain traffic, dispose the old layer. Version skew during drain is expected and supported.
- **Classloader leak detection is a first-class feature.** OSGi's most notorious failure mode is a disposed bundle whose classloader is retained by a stray reference, leaking metaspace on every redeploy. After undeploy Gimlé holds a `PhantomReference` to the layer's loader and reports a leak if it survives a configurable window, naming the retaining path via a heap walk. Redeploy-in-a-loop with flat metaspace is a mandatory acceptance test.

Note the escape hatch this buys: a module that leaks despite everything can be moved to Tier 2, where undeploy means killing a JVM — a guarantee no OSGi container has ever been able to offer.

## Control plane

- **API server** — accepts manifests describing desired state. Persisted to the state store.
- **State store** — embedded, backed by a Java Raft implementation for control-plane HA. Single-node until Phase 5.
- **Scheduler** — places module instances given resource requests, isolation tier, anti-affinity (replicas of one module must not share a worker JVM, or a single crash takes out every replica), and current machine load. Tier selection makes this a bin-packing problem across two dimensions rather than one.
- **Reconcilers** — one control loop per resource kind, comparing desired to observed and emitting actions. **Level-triggered, not edge-triggered**: a reconciler must converge from any starting state, including after missing every event. This is the single most important correctness property in the control plane and the hardest to test.
- **Membership and failure detection** — a SWIM-style gossip protocol between node agents, in Java over UDP. Gossip carries membership and coarse health; the control plane is not on the critical path for detecting a dead node.

## Service fabric

- Modules publish and consume services through a registry keyed by interface + version.
- **Same-worker calls are direct in-JVM invocations** — no serialization, no network, no proxy. A call between co-located modules costs a virtual method dispatch. Nothing in Kubernetes can do this.
- **Cross-worker, same-machine calls** use a Unix domain socket (`java.net.UnixDomainSocketAddress`) with a compact binary codec — no loopback TCP overhead.
- **Cross-machine calls** use the same codec over TCP with virtual-thread-per-connection handling.
- The load balancer **prefers locality**: healthy same-worker instance first, then same-machine, then remote by least-outstanding-requests.
- Circuit breaking and outlier ejection at the registry level, so unhealthy instances stop receiving traffic before a probe declares them dead.

## Health, scaling, self-healing

- **Probes are interfaces, not HTTP endpoints.** A module implements `LivenessProbe` / `ReadinessProbe`; the worker calls them directly. No socket, no serialization, no sidecar — probing hundreds of modules stays cheap.
- **Tiered self-healing**, matching the isolation tiers:
  - Module-level: dispose layer, re-instantiate. Milliseconds.
  - Worker-level: `destroyForcibly`, respawn with AppCDS. Sub-second.
  - Machine-level: reschedule modules elsewhere. Seconds.
  Escalation is automatic — repeated module restarts promote to worker restart, repeated worker restarts to rescheduling, then `CrashLoopBackOff` with Kubernetes-equivalent backoff semantics.
- **Horizontal scaling** driven by per-module metrics (request rate, latency, queue depth, allocation rate). Scaling up may add instances to the same worker — the density advantage, and exactly why anti-affinity must be configurable.
- **Graceful drain**: stop routing, await in-flight completion with a deadline, dispose.

## Observability

- **Per-module metrics** via Micrometer: request rate/latency/errors, allocation rate, CPU time, thread counts, classloader and metaspace footprint.
- **Distributed tracing** via OpenTelemetry, propagated through scoped values — including across in-JVM calls, so a co-located hop is a visible span rather than a gap in the trace.
- **JFR-backed resource accounting** — per-module allocation and CPU attribution from JFR event streams. This is what makes Tier 1 soft limits enforceable at all.
- **Structured event log** of every lifecycle transition and reconciliation decision, queryable via the API server. The `kubectl describe` equivalent and the primary debugging surface.
- **Whole-cluster debugging**: because every tier is a JVM, a JFR recording or heap dump can be pulled from any component through the same API. Debugging the platform and debugging the workload use identical tools — the clearest practical advantage of the all-Java approach.

## Phases

### Phase 0 — Validate the foundation (week 1)
Before anything else, prove the enabling assumptions with a throwaway spike:
- Measure AppCDS-assisted JVM startup to establish the real Tier 2 restart cost.

Platform independence first (see "Resource control" above): validating cgroup v2 and an FFM `unshare` downcall is *not* a Phase 0 gate. That work is deliberately deferred until the portable resource-limiting foundation (Phase 2) has proven itself — spiking platform-specific mechanisms early, before anything consumes them, is exactly the kind of speculative work this design avoids.

### Phase 1 — Module system (weeks 2–5)
- Descriptor format, artifact layout, resolver with version-range satisfaction.
- Dynamic `ModuleLayer` construction, per-module classloaders, shared platform layer.
- Full lifecycle state machine with hooks; hot install/start/stop/update/uninstall.
- Classloader leak detection with phantom-reference tracking; redeploy-loop test showing flat metaspace.

### Phase 2 — Worker runtime and supervision (weeks 6–9)
- Worker JVM: module hosting, per-module bounded virtual-thread schedulers, probe loop, in-JVM service registry.
- Node agent: worker spawning via `Process` API, portable resource limiting (`ResourceLimiter`/JVM flags — no cgroups yet, see "Resource control" above), supervision and restart, capacity reporting.
- Tier 1 and Tier 2 placement working end to end on one machine, identically on every OS.
- Per-module metrics via Micrometer and JFR streaming.

### Phase 3 — Control plane (weeks 10–14)
- Manifest model, API server, embedded state store.
- Scheduler with resource requests, isolation tier, and anti-affinity.
- Reconcilers for deployment, replica count, and health — level-triggered, with convergence tests from arbitrary state.
- Tiered self-healing with escalation and backoff.

### Phase 4 — Distribution and fabric (weeks 15–19)
- Multi-machine clustering; SWIM gossip membership and failure detection.
- Service fabric across all three call paths (in-JVM, Unix socket, TCP) with locality-preferring load balancing.
- Rolling updates with drain and version skew.
- Horizontal autoscaling; distributed tracing across every hop type.

### Phase 5 — Availability and hardening (weeks 20+)
- Raft-replicated control plane.
- Kernel-level Tier 2 enforcement: a `CgroupResourceLimiter` (cgroup v2) behind the existing `ResourceLimiter` interface, plus the platform detection needed to select it.
- Tier 3 namespace isolation via FFM.
- Multi-tenancy: namespaces, per-tenant quotas, module permissions.
- Secrets and configuration distribution.

### Phase 6 — Stretch goals
- CRaC checkpoint/restore for near-instant worker startup, collapsing the Tier 1/Tier 2 cost gap.
- `jlink`-minimized runtime images per node role.
- A `kubectl`-shaped CLI for muscle memory, without API compatibility.
- Web console for cluster state and module topology.
- Cross-platform tier degradation for macOS and Windows development environments.

## Tooling & conventions

- **Build tool**: Maven.
- **Code formatting**: Google Java Format, enforced (e.g. `fmt-maven-plugin`), run in CI and as a pre-commit hook.
- **Git hooks**:
  - `commit-msg` hook rejecting any commit message that mentions an AI assistant (Claude, Copilot, ChatGPT, etc.).
  - Commit messages follow Conventional Commits style: prefix (`feat`, `fix`, `chore`, `refactor`, `docs`, `test`, ...), short subject, max 3 lines total.
  - `pre-commit` hook running `mvn verify` (build + tests) and blocking the commit on failure.
- **Code style**: clean, self-documenting code (clear names, small methods) preferred over Javadoc/comments. Add a comment only where logic is genuinely complex or non-obvious (e.g. layer parent selection, leak-detection reference handling, FFM struct layouts, reconciler convergence edge cases). Applies to test code as well as production code.
- **Naming convention**: standard Java `camelCase` everywhere — production code, JUnit lifecycle hooks, and private/helper methods in test classes — except methods directly annotated `@Test`, which are `snake_case` so a test's name reads as a sentence describing the behavior it verifies. Enforced via two Checkstyle `MethodName` instances split by an XPath suppression keyed on `@Test` presence (`checkstyle.xml`/`checkstyle-suppressions.xml`), not Google Java Format.
- **Test coverage**: tests must cover both normal/happy paths and exception/error paths (e.g. unresolvable dependency, version conflict, probe timeout, worker OOM, network partition, no feasible placement, corrupt manifest, cgroup write failure), not just the success case. Reconcilers additionally require convergence tests from arbitrary starting states; the module system requires a repeated-redeploy leak test; the supervisor requires kill-and-recover tests at every tier.
- **Error types**: Gimlé-specific failures use dedicated unchecked exception types in `gimle-core` (e.g. `GimleResolutionException`, `GimleLifecycleException`, `GimleSchedulingException`, `GimleManifestException`, `GimleClusterException`, `GimleIsolationException`), all extending `RuntimeException` — no checked exceptions anywhere in the project. Control-plane errors map to structured API responses rather than propagating stack traces to clients.
- **Immutability**: immutable data structures (records, `List.of`/unmodifiable collections) preferred over mutable ones wherever feasible. Desired state, observed state, and reconciliation events are strictly immutable snapshots — a reconciler reads a snapshot and returns actions, never mutating in place. This is what makes reconciliation testable.
- **References**: use `final` for variables, fields, and parameters wherever possible.
- **Dependencies**: no Lombok. Use plain Java (records, standard getters/constructors) instead of annotation-generated boilerplate. **No JNI and no native code anywhere** — OS interaction goes through `java.nio.file` or FFM downcalls.
- **Logging**: SLF4J API with Logback as the binding, configured once in `gimle-core` and inherited by all modules. Hosted modules see the platform logging API through the shared layer rather than bundling their own binding.
- **Project structure**: multi-module Maven project, one module per concern:
  - `gimle-core` — shared model/domain types, exceptions, logging config.
  - `gimle-module` — descriptor model, resolver, `ModuleLayer` construction, lifecycle state machine, leak detection.
  - `gimle-api` — platform service API exposed to hosted modules (probes, service registry, config, metrics).
  - `gimle-os` — resource limiting (`ResourceLimiter`); portable JVM-flags implementation today, kernel-level cgroup v2 + FFM syscall bindings a deferred later addition (see "Resource control" above).
  - `gimle-worker` — worker JVM runtime: module hosting, schedulers, probing, local registry.
  - `gimle-agent` — node agent: worker supervision, resource assignment, capacity reporting.
  - `gimle-controlplane` — API server, state store, Raft, scheduler, reconcilers.
  - `gimle-fabric` — service registry, three-path invocation, load balancing, circuit breaking, gossip membership.
  - `gimle-observability` — metrics, tracing, JFR accounting, event log.
  - `gimle-cli` — control-plane client, agent launcher, worker launcher.
- **Repo hygiene**: commit only essential source code and config files. No generated reports or ad-hoc markdown files, except `CLAUDE.md` and `README.md`. A `claudedocs/` folder is gitignored.

## Naming

Norse/Viking naming line, consistent with Drakkar, Þjappa, Skald, Bifrost, Galdr, and Muninn. *Gimlé* is the golden-roofed hall that stands through Ragnarök and shelters the survivors afterward — the most fitting available name for a platform whose defining promise is that the workloads it hosts outlive the failures beneath them.
