# Gimle Maven Plugin

`gimle-maven-plugin` is a `maven-plugin`-packaged reactor module providing `spring-boot:run`-style
developer-experience goals for this repo, invoked straight from the reactor root with no `-pl`
needed — `mvn gimle:controlplane`, `mvn gimle:agent`, `mvn gimle:deploy -Dgimle.deploy.file=...`.
Each process-launching goal self-filters to its one target module by checking
`project.getArtifactId()` and no-ops for every other reactor project — a direct goal invocation with
no bound lifecycle phase iterates the whole reactor by default, and self-filtering is the standard
idiom for "this goal only makes sense for one specific module." None of these goals are bound to any
lifecycle phase, so nothing here ever runs as a side effect of `mvn verify`/`mvn install`.

Every process-launching goal spawns a genuinely separate OS process — never the target's `main()`
via reflection in this same JVM, since `AgentMain`/`ControlPlaneMain`/`GimleCli` all call
`System.exit()` on error paths, which would tear down the Maven process too if run in-process.

## Shared mechanics

- `AbstractGimleMojo` — base for goals that act inside exactly one named reactor module (e.g.
  `controlplane` only means something inside `gimle-controlplane`). `execute()` no-ops for every
  project except the one `targetArtifactId()` names.
- `AbstractGimleRootMojo` — base for goals that orchestrate at the reactor level instead. Guards on
  `MavenProject.isExecutionRoot()` (true for exactly one project in any reactor invocation) rather
  than one fixed artifactId.

## Goals

### Cluster process launchers (`AbstractGimleMojo`)

Each defaults its store/fafnir/muninn/andvari endpoints to the sibling goals' own default ports, so
running several of these together needs zero extra flags for single-node local dev.

| Goal | Launches | Key parameters (defaults) |
|---|---|---|
| `gimle:store` | `StoreMain` (`gimle-mimir`) — the Raft-replicated state store as its own process | `gimle.store.stateDir`, `gimle.store.raftPort` (9080), `gimle.store.clientPort` (9091), `gimle.store.peers`, `gimle.store.csrEndpoint`, `gimle.store.transportProtocol` |
| `gimle:controlplane` | `ControlPlaneMain` (`gimle-controlplane`) | `gimle.controlplane.port` (8080), `gimle.controlplane.secretKeyPath`, `gimle.controlplane.storeEndpoints` (`127.0.0.1:9091`), `gimle.controlplane.fafnirEndpoint` (`127.0.0.1:9092`), `gimle.controlplane.transportProtocol`, `gimle.controlplane.audit.readResourceKinds` |
| `gimle:agent` | `AgentMain` (`gimle-agent`) plus its own `WorkerMain` child, whose classpath is resolved separately against the already-installed `com.gimle:gimle-worker` artifact (the agent never imports `com.gimle.worker.*` itself) | `gimle.agent.nodeId` (`node-1`), `gimle.agent.controlPlaneUrl` (`http://127.0.0.1:8080`), `gimle.agent.gossipAddress` (`127.0.0.1:9090`), `gimle.agent.fafnirEndpoint` (`127.0.0.1:9092`), `gimle.agent.andvariEndpoint`, `gimle.agent.transportProtocol` |
| `gimle:fafnir` | `FafnirMain` (`gimle-fafnir`) — the secrets vault as its own process | `gimle.fafnir.port` (9092), `gimle.fafnir.secretKeyPath`, `gimle.fafnir.storeEndpoints` (`127.0.0.1:9091`), `gimle.fafnir.csrEndpoint`, `gimle.fafnir.transportProtocol` |
| `gimle:muninn` | `MuninnMain` (`gimle-muninn`) — the logs/metrics/traces sink as its own process | `gimle.muninn.port` (9093), `gimle.muninn.dataRoot`, `gimle.muninn.storeEndpoints` (`127.0.0.1:9091`), `gimle.muninn.csrEndpoint`, `gimle.muninn.transportProtocol` |
| `gimle:andvari` | `AndvariMain` (`gimle-andvari`) — the module artifact registry as its own process | `gimle.andvari.port` (9094), `gimle.andvari.dataRoot`, `gimle.andvari.storeEndpoints` (`127.0.0.1:9091`), `gimle.andvari.csrEndpoint`, `gimle.andvari.peerEndpoints`, `gimle.andvari.transportProtocol` |
| `gimle:tls-init` | `com.gimle.pki.PkiBootstrapMain` (in-module, no cross-reactor classpath resolution needed) — generates the cluster CA, the control plane's own leaf certificate, and the first operator's leaf certificate | `gimle.tlsInit.outputDir` (`./gimle-tls`), `gimle.tlsInit.caCommonName` (`gimle-cluster-ca`), `gimle.tlsInit.hostname` (`localhost`), `gimle.tlsInit.passwordFile` (unset — the one-time bootstrap password is printed only to a real terminal, so a non-interactive run must name a file for it or the goal fails) |
| `gimle:deploy` | `GimleCli` (`gimle-cli`'s own resolved runtime classpath) — applies a deployment manifest to a running control plane | `gimle.deploy.file` (required), `gimle.deploy.server` (`127.0.0.1:8080`) |

### Goals that run against an arbitrary caller module, not one fixed artifactId

These deliberately don't extend `AbstractGimleMojo`: they're meant to run inside whatever project
invokes them (an out-of-tree module, or `gimle-examples`), so there's no reactor artifactId to
self-filter to — bind them in the calling project's own `pom.xml`, or invoke with `-pl`.

| Goal | Behavior | Key parameters (defaults) |
|---|---|---|
| `gimle:init` | Runs `hilmir init` against this project's own built jar via a real `HilmirMain` subprocess, writing `gimle-module.yaml`/`deployment.yaml` into the project directory | `gimle.init.jar`, `gimle.init.outDir` (`${project.basedir}`), `gimle.init.hilmirVersion` |
| `gimle:doctor` | Runs `hilmir doctor` against this project's own built jar via a real `HilmirMain` subprocess | `gimle.doctor.vessel` (`false`), `gimle.doctor.server`, `gimle.doctor.tenant`, `gimle.doctor.hilmirVersion` |
| `gimle:publish` | Pushes the module jar this project just built to a running artifact registry via a real `GimleCli artifact push` subprocess — the registry coordinate is derived from the jar's own bundled `gimle-module.yaml`, not from a parameter here | `gimle.publish.server` (`127.0.0.1:8080`), `gimle.publish.cliVersion` |

### Root-level orchestration goals (`AbstractGimleRootMojo`)

Run once per reactor invocation, at the execution root, regardless of `-T`/module count.

| Goal | Behavior |
|---|---|
| `gimle:docs` | Runs the documentation site's full build pipeline in one command: `mvn javadoc:aggregate` across the platform modules, copy the result into `gimle-docs`'s own `static/javadoc/`, then build the Docusaurus site via two `bun` commands. Self-filters to the root aggregator project (artifactId `gimle`) rather than one leaf module. |
| `gimle:flaky-tests` | Runs every `@Tag("flaky")` test, one listed module at a time (`gimle.flakyTests.modules`, default `gimle-mimir`), each as its own genuinely separate `mvn -pl <module> test -Dgroups=flaky` child process rather than nested in this build's own reactor — removes cross-module Surefire-JVM contention by construction. `gimle.flakyTests.repeat` reruns each listed module that many times in a row. |
| `gimle:saga` | Ensures a Saga test-report server is up on `gimle.saga.port` (default 9096) and prints its console URL. A healthy already-listening server is reused as-is; otherwise spawns a detached `SagaMain` process (log at `~/.gimle/saga/saga.log`, pid recorded for `gimle:saga-stop`). |
| `gimle:saga-stop` | Best-effort shutdown of the local Saga server: `POST /api/shutdown` first, falling back to signalling the recorded pid. Never fails the build. |
| `gimle:saga-import` | Standalone sweep of every `target/surefire-reports/*.xml` under the execution root, posted to an already-running Saga server's `/api/import`. For pulling an ordinary `mvn verify` run's results into Saga after the fact. |
| `gimle:verify` (goal name `verify`, invoked as `gimle:verify`) | Runs a full build under Saga run tracking: ensures a Saga server is up, mints a run id from the wall clock and the working tree's short git sha, announces the run, then spawns `mvn <gimle.saga.mavenArgs>` (default `verify`) as a genuinely separate child Maven process with `-Dgimle.saga.endpoint`/`-Dgimle.saga.runId` threaded through. Sweeps and imports surefire reports after the child exits, closes the run, prints the console deep link, and only then propagates a non-zero child exit. |

## Supporting classes

- `GimleProcesses` — shared subprocess-spawning and cross-module runtime-classpath resolution
  (resolving an already-`mvn install`ed artifact like `gimle-worker`/`gimle-cli`/`gimle-hilmir` by
  coordinate via Maven's own Aether resolver, independent of reactor build order).
- `GitInfo` — reads the working tree's short git sha for `gimle:verify`'s run id.
- `SagaClient`/`SagaEvents`/`SagaServer` — the HTTP client and NDJSON wire-event helpers the saga
  goals share for talking to a `SagaMain` server.
- `SurefireReports` — parses `target/surefire-reports/*.xml` for the saga import/verify goals.

## Testing

Mojo-adjacent logic that doesn't require a live Maven process (`DoctorMojo`'s argument building,
`FlakyTestsMojo`'s module list parsing, `GimleProcesses`, `SagaClient`, `SagaEvents`, `SagaServer`,
`SagaVerifyMojo`, `SurefireReports`) has unit tests under `src/test/java`; `FakeProcess` stands in
for a real spawned process in these tests.
