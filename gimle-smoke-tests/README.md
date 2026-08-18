# Gimle Smoke Tests

`gimle-smoke-tests` is a test-only reactor module (`jar` packaging, no main sources) that runs the
platform against itself: a shared fixture spawns a genuine multi-process Gimlé cluster — real
`StoreMain`, `ControlPlaneMain`, `AgentMain` (+ its own `WorkerMain` child), `FafnirMain`, and
`MuninnMain` OS processes via `ProcessBuilder`, never mocked or constructed in-JVM — and every
concrete `*IT` class in the module drives real scenarios against it through the real HTTP API. This
is deliberately unlike most integration tests elsewhere in the repo (e.g.
`ControlPlaneAgentWorkerIntegrationTest` in `gimle-agent`), which construct `ApiServer` directly
in-JVM: several scenarios here specifically need things only a real, separately-launched process
tree provides — the real bundled web console (`BundledConsole`/`ApiServer.serveConsole`) for the
Playwright suite to hit with a real browser, real cross-process worker respawn, a real second agent
process for gossip/DaemonSet coverage, and so on.

Not part of the default `mvn verify` — this is slow (real JVMs, a real browser) and needs
`mvn install` to have already produced every jar it launches. Opt in with:

```sh
mvn -pl gimle-smoke-tests verify -Psmoke
```

## Why the tests are excluded by default, and how

Two independent, redundant defenses ensure this suite never runs under plain `mvn verify`: Surefire's
own `**/*IT.java` exclude pattern (its default convention, made explicit), and JUnit 5's own
`excludedGroups: smoke` tag filtering. The second exists because an unqualified
`-Dtest='!A,!B,...'` (exclusions only, no positive pattern) — the shape a root-level `verify` command
takes when it maintains a standing flaky-test exclusion list — has been observed to override
`excludes` and broaden Surefire's own class discovery enough to pick a class up anyway, even with the
`smoke` profile never activated. Tag-based filtering is a separate mechanism applied after
`-Dtest`'s own class-file selection, so it isn't affected the same way. Every concrete `*IT` class
carries `@Tag("smoke")` for exactly this reason. The `smoke` profile itself only adds the
Failsafe-plugin execution (`integration-test` + `verify` goals) that actually runs `*IT.java`
classes.

## `GreeterSmokeClusterSupport` — the shared fixture

Every `*IT` class in this package extends `GreeterSmokeClusterSupport`, which spawns:

- a real `gimle-mimir` store cluster (`STORE_COUNT = 3` `StoreMain` processes),
- multiple `ControlPlaneMain` replicas (`CONTROLPLANE_COUNT = 2`) sharing that same store cluster,
- one `AgentMain` (with its own `WorkerMain` child),
- `FAFNIR_COUNT = 2` `FafnirMain` replicas,
- one `MuninnMain`,

and adds only the `@Test` methods for its own topic. Splitting the original single ~2500-line
`GreeterSmokeTestIT` into these topic-grouped classes was a pure refactor — same real subprocess
cluster, same helpers, just organized for clarity as the number of real-cluster scenarios grew. Ports
are distinct from `gimle-console/LOCAL_DEV.md`'s manual walkthrough (8080/9080), so this suite can
run alongside a developer's own manually-started cluster without colliding. All cluster-condition
waiting goes through `gimle-testkit`'s Heimdall/`Await`/`PortLease`, the same real-cluster test
infrastructure `gimle-holmgang` is built on.

## The `*IT` classes

| Class | Covers |
|---|---|
| `GreeterClusterTopologyIT` | Base topology: deploys `greeter-provider`/`greeter-consumer` across the real multi-node store cluster and multiple control-plane replicas, asserts both reach `ACTIVE` observed through a *different* replica than they were submitted to (proving shared state via `gimle-mimir`, not per-replica state), the consumer's real fabric call to the provider shows up in its own log, and a real tenant-scoped secret round-trips through Fafnir — then runs `gimle-console`'s own Playwright suite against that live cluster. |
| `RaftResilienceIT` | Store-cluster resilience under real process failure: losing one store node mid-deployment, leader failover under real concurrent writes with no acknowledged write lost, and etcd-style live membership change (a 4th node joining and leaving). |
| `ObservabilityIT` | Round trips through Muninn: a deployed instance's log line survives its owning agent's death (served from Muninn's shipped history instead of a client-visible 502), and a real control-plane request metric is shipped to Muninn and readable back via `GET /metrics-history/*`. |
| `WorkerObservabilityIT` | The worker tier of the same relay: a real deployed module's own request counter and a real span from its own fabric call both reach Muninn under the `WORKER` process kind, having traveled worker JVM → agent → Muninn (workers have no outbound network identity of their own), readable back through `GET /metrics-history/*`/`GET /traces-history/*`. |
| `SelfHealingIT` | Tiered self-healing: the worker tier (`WorkerProcessSupervisor` respawning a killed worker process, deployment recovers to `ACTIVE`) and the module tier (a module that never passes liveness exhausts its dispose+reinstantiate restart budget and escalates to `FAILED` for good). |
| `QuotaIT` | Multi-tenancy quota enforcement: `QuotaReconciler`'s flag-but-never-evict contract when a tenant's quota is retroactively lowered, and `TenantQuotaPlugin`'s real 409 rejection at admission for a deployment that would push a tenant over quota. |
| `AutoscaleIT` | Multi-signal autoscaling under real generated load, one scenario per signal: request rate (CPU signal unreachable), error rate (a ~50%-failure-rate provider variant), and queue depth (real sustained concurrency via Gatling's closed injection model past `WorkerRuntime`'s per-module concurrency bound). Uses `load/GreeterAutoscaleSimulation` (Gatling) to drive real, controllable-rate HTTP traffic. |
| `RollingUpdateIT` | Rolling update / version-aware traffic cutover under real load, both replica-count regimes the default disruption budget implies: 2-replica (continuous availability throughout) and 1-replica (the documented downtime tradeoff) — against a real v1.1.0 build compiled on the fly by `TestModuleBuilder` rather than a second committed example module. |
| `SurgePromotionIT` | `maxSurge` pinned promotion: `DeploymentReconciler#handleSurge` retargeting an already-healthy surge worker onto its final index instead of starting fresh, and `AgentMain#renameInPlace` carrying that out without restarting the worker's real OS process. |
| `AndvariRegistryIT` | The registry-resolution path end to end against a real Andvari replica: a jar pushed through the control plane's `/artifacts/*` proxy, a deployment submitted with no `artifactPath`, a real agent resolving the coordinate into its own pull-through cache, and the instance reaching `ACTIVE` — observed through a different control-plane replica than the one everything was submitted to. |
| `ServiceNetworkIT` | The Service/Bifrost/Skald network path: a real `POST /services` fronting a deployed `greeter-provider`, a real `gimle-bifrost` node proxy, and a real `SkaldMain` UDP DNS responder answering a hand-crafted query for the Service's own name — plus the `ServiceExport` tenant re-check against a real cross-tenant fabric call. Documents two real structural gaps found only by running this scenario for real (see the class's own javadoc): `ServiceEndpointResolver` never resolves an endpoint for a plain hosted module that isn't a Vessel, and `HttpServiceSource` currently throws a `ClassCastException` on every real poll against `ApiServer`'s actual `/services` response shape, so `BifrostProxy` can never bind a listener today. |
| `ServiceFabricIT` | The service fabric's circuit breaker, proven against real cross-process failures (inferred indirectly from the consumer's own call pattern, since `FabricServiceRegistry` exports no breaker-state metric or log line). |
| `GatewayFabricRouteIT` | `gimle-gateway`'s v1 fabric-routes scope end to end: the real `greeter-provider` and real `gimle-gateway` modules (the latter as a `DaemonSet` on an edge-labeled node in the `gimle-system` tenant) in one cluster, hit over real HTTP, asserting the response reflects a real fabric call that reached the real provider instance. |
| `GossipFailureDetectionIT` | SWIM gossip/failure detection across three real, separate agent processes (not two — a third node is needed to exercise the indirect ping-req relay path), seeded off node 1 alone to prove full-state anti-entropy convergence. |
| `DaemonSetLifecycleIT` | `kind: DaemonSet` against real, separate node agent processes: per-node fan-out across two real agents, then a hard kill of the second node's agent to prove its stale assignment is actually cleaned up. |
| `NodeCordoningIT` | Node cordoning against a real cluster: a cordoned sole tier-eligible node causes `Scheduler#place` to throw `GimleSchedulingException#nodeCordoned` on every reconciler attempt while a placement is pending, without evicting anything already running there. |
| `JobLifecycleIT` | `kind: Job`/`kind: CronJob` through the full real-process chain: agent spawns a real worker JVM, the worker drives `JobHooks#run` on its own virtual thread, and `JobReconciler` reads the resulting `lifecycleState: COMPLETED` heartbeat to declare the job `SUCCEEDED`. |
| `StatefulSetPersistenceIT` | `kind: StatefulSet`'s core promise: a persistent volume's data survives a real worker JVM crash-and-respawn, and the same index lands back on the same node afterward. |
| `ClassloaderLeakIT` | Classloader leak detection end to end. Building this scenario surfaced and fixed a real gap: `WorkerMain` never actually wired `LeakTracker` into the real `ModuleController` it constructs, so a real leak in a real deployed module went completely undetected. |
| `RedeployStabilityIT` | Several real redeploy cycles of a well-behaved module on a real shared worker without `LeakTracker` ever reporting a false-positive leak — a lighter, real-cluster-shaped substitute for the module-tier "redeploy-in-a-loop, flat metaspace" acceptance test, which already exists at the `gimle-module` unit tier. |
| `Tier1DensityIT` | The `MAX_TIER1_DENSITY` cap against real worker JVMs: four distinct Tier 1 modules really do share one worker process, and a fifth genuinely gets a new one. |

Nearly every `*IT` class documents, in its own class-level javadoc, a real platform gap or bug that
building the scenario surfaced (and in several cases fixed) — read the class itself for the specific
finding.

## Notable test dependencies

- `gimle-testkit` (`test` scope) — Heimdall, `Await`, `PortLease`.
- `gimle-controlplane`, `gimle-agent`, `gimle-worker`, `gimle-fafnir`, `gimle-muninn`,
  `gimle-andvari`, `gimle-skald` (all `test` scope) — put each process kind's `Main` class on the
  Surefire/Failsafe fork's classpath so `GreeterSmokeClusterSupport` can launch it as a real
  subprocess. Several are needed here specifically because the depending-on module's own dependency
  on them is itself `test`-scoped and therefore non-transitive.
- `gimle-module` (`test-jar`) — `TestModuleBuilder`, used to compile a real "v2" of
  `greeter-provider` at test run time for `RollingUpdateIT` without committing a near-duplicate
  example module.
- `gatling-charts-highcharts` / `gatling-http-java` — real, controllable-rate HTTP load generation
  for `AutoscaleIT`'s queue-depth/request-rate scenarios, driven at `greeter-load-generator`
  (`gimle-examples/`).
