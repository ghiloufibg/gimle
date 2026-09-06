---
sidebar_position: 4
---

# `gimle-maven-plugin` goal reference

`spring-boot:run`-style developer-experience goals — invoked straight from the reactor root, no
`-pl <module>` needed. Each goal spawns a genuine separate OS process (never runs a target's
`main()` via reflection in-process) and self-filters to its one target module, no-op'ing
everywhere else in the reactor. One-time setup: `com.gimle` needs a `<pluginGroups>` entry in
`~/.m2/settings.xml` (see [Getting started](../tutorials/getting-started.md)) for the short
`gimle:*` prefix form to resolve at all.

## `mvn gimle:store`

Launches a real `StoreMain` process — the Raft-replicated state store as its own process (the
etcd equivalent), separate from `gimle:controlplane`'s own API server (see
[Control plane](../architecture/control-plane.md)). Run this *before* `mvn gimle:controlplane`;
the latter's own `gimle.controlplane.storeEndpoints` default already points at this goal's default
client port.

| Property | Default | Meaning |
|---|---|---|
| `gimle.store.stateDir` | `${project.build.directory}/gimle-mimir-state` | Where `StateStore`/`RaftLog` persist to disk. |
| `gimle.store.raftPort` | `9080` | Raft peer-to-peer transport port. |
| `gimle.store.clientPort` | `9091` | Client-facing `StoreRpc` port — what `gimle-controlplane` connects to. |
| `gimle.store.peers` | *(unset, single-node)* | `host:raftPort:clientPort,...` for every other store replica, for a multi-node store cluster. |
| `gimle.store.csrEndpoint` | *(unset)* | `host:port` of a reachable `ApiServer` to submit this store node's own certificate-rotation CSRs to — only meaningful in TLS mode. |
| `gimle.store.transportProtocol` | *(unset, plaintext)* | Local-dev convenience for `gimle.transport.protocol` — see [Transport security](../architecture/transport-security.md). |

```bash
mvn gimle:store -Dgimle.store.clientPort=9091
```

## `mvn gimle:fafnir`

Launches a real `FafnirMain` process — the secrets vault as its own process (see [Node
topology](../architecture/node-topology.md#fafnir)), talking to a `gimle-mimir` store cluster over
the network exactly the way `gimle:controlplane`'s process does. Run this *before* any process that
proxies to it or reads secrets from it directly (the control plane, and any agent with
`gimle.agent.fafnirEndpoint` set). Its own default `storeEndpoints` already points at `mvn
gimle:store`'s default client port.

| Property | Default | Meaning |
|---|---|---|
| `gimle.fafnir.port` | `9092` | Secrets API port — distinct from the store's Raft (`9080`)/client (`9091`) ports, the agent's gossip default (`9090`), and the control plane's own default (`8080`). |
| `gimle.fafnir.secretKeyPath` | `${project.build.directory}/gimle-fafnir-state/secret.key` | Where this replica's own AES-256 master key ring persists to disk. |
| `gimle.fafnir.storeEndpoints` | `127.0.0.1:9091` | `host:clientPort,...` of every store endpoint to connect to — matches `mvn gimle:store`'s own default client port. |
| `gimle.fafnir.csrEndpoint` | *(unset)* | `host:port` of a reachable `ApiServer` to submit this replica's own certificate-rotation CSRs to — only meaningful in TLS mode. |
| `gimle.fafnir.transportProtocol` | *(unset, plaintext)* | Local-dev convenience for `gimle.transport.protocol` — see [Transport security](../architecture/transport-security.md). |

```bash
mvn gimle:fafnir -Dgimle.fafnir.port=9092
```

## `mvn gimle:muninn`

Launches a real `MuninnMain` process — the logs/metrics/traces sink as its own process (see [Node
topology](../architecture/node-topology.md#muninn)), talking to a `gimle-mimir` store cluster over
the network for its own read-only `Authorizer` check, the same way `gimle:fafnir`'s process does.
Run this *before* any process that wants to ship to it — its own default `storeEndpoints` already
points at `mvn gimle:store`'s default client port.

| Property | Default | Meaning |
|---|---|---|
| `gimle.muninn.port` | `9093` | Ingest/read HTTP port. |
| `gimle.muninn.dataRoot` | `${project.build.directory}/gimle-muninn-data` | Where shipped logs/metrics/traces persist to disk, day-bucketed. |
| `gimle.muninn.storeEndpoints` | `127.0.0.1:9091` | `host:clientPort,...` of every store endpoint to connect to — matches `mvn gimle:store`'s own default client port. |
| `gimle.muninn.csrEndpoint` | *(unset)* | `host:port` of a reachable `ApiServer` to submit this replica's own certificate-rotation CSRs to — only meaningful in TLS mode. |
| `gimle.muninn.transportProtocol` | *(unset, plaintext)* | Local-dev convenience for `gimle.transport.protocol` — see [Transport security](../architecture/transport-security.md). |

```bash
mvn gimle:muninn -Dgimle.muninn.port=9093
```

## `mvn gimle:andvari`

Launches a real `AndvariMain` process — the module artifact registry as its own process (see [Node
topology](../architecture/node-topology.md#andvari)), talking to a `gimle-mimir` store cluster over
the network for its own read-only `Authorizer` check, the same posture `gimle:muninn` and
`gimle:fafnir` already take. Run this *before* `mvn gimle:agent` if any deployment will resolve an
artifact by coordinate instead of a local `artifactPath`.

| Property | Default | Meaning |
|---|---|---|
| `gimle.andvari.port` | `9094` | Operational (`/artifacts/*`) and Maven-2 (`/repository/**`) HTTP port. |
| `gimle.andvari.dataRoot` | `${project.build.directory}/gimle-andvari-data` | Where pushed jars persist to disk, content-addressed by `(moduleId, version)`. |
| `gimle.andvari.storeEndpoints` | `127.0.0.1:9091` | `host:clientPort,...` of every store endpoint to connect to — matches `mvn gimle:store`'s own default client port. |
| `gimle.andvari.csrEndpoint` | *(unset)* | `host:port` of a reachable `ApiServer` to submit this replica's own certificate-rotation CSRs to — only meaningful in TLS mode. |
| `gimle.andvari.peerEndpoints` | *(unset)* | `host:port,...` of every *other* Andvari replica to peer-sync against — see [Node topology](../architecture/node-topology.md#andvari). Unset means a single, unreplicated registry. |
| `gimle.andvari.transportProtocol` | *(unset, plaintext)* | Local-dev convenience for `gimle.transport.protocol` — see [Transport security](../architecture/transport-security.md). |

```bash
mvn gimle:andvari -Dgimle.andvari.port=9094
```

## `mvn gimle:controlplane`

Launches a real `ControlPlaneMain` process, talking to a `gimle-mimir` store cluster over the
network (see `mvn gimle:store` above) rather than embedding one.

| Property | Default | Meaning |
|---|---|---|
| `gimle.controlplane.port` | `8080` | API server port. |
| `gimle.controlplane.secretKeyPath` | `${project.build.directory}/gimle-state/secret.key` | Where this replica's own AES-256 secrets master key persists to disk. |
| `gimle.controlplane.storeEndpoints` | `127.0.0.1:9091` | `host:clientPort,...` of every store endpoint to connect to — matches `mvn gimle:store`'s own default client port. |
| `gimle.controlplane.andvariEndpoint` | `127.0.0.1:9094` | `host:port` of the artifact registry replica to resolve registry-coordinate deployments against — matches `mvn gimle:andvari`'s own default port. Optional at the process level (a cluster with no reachable Andvari keeps working on local-`artifactPath` manifests unchanged), but defaulted here so `mvn gimle:publish` and a coordinate-only `mvn gimle:deploy` work against a plain `mvn gimle:controlplane` with no extra flags. |
| `gimle.controlplane.transportProtocol` | *(unset, plaintext)* | Local-dev convenience for `gimle.transport.protocol` — see [Transport security](../architecture/transport-security.md). |
| `gimle.controlplane.audit.readResourceKinds` | *(unset, no READ auditing)* | Comma-separated `ResourceKind` names to opt into READ-decision audit-trail coverage — see [Authentication and authorization § Audit logging](../architecture/authn-authz.md#audit-logging). |

```bash
mvn gimle:controlplane -Dgimle.controlplane.port=8081
```

## `mvn gimle:agent`

Launches a real `AgentMain` process, plus a worker command-tail whose classpath is resolved
separately (the worker is a genuinely different OS process — `AgentMain`'s own code never imports
`com.gimle.worker.*`, by design, since the [Node Agent never runs hosted-module code
itself](../architecture/node-topology.md)). Requires `mvn install` to have already produced the
`gimle-worker` artifact this goal resolves against.

| Property | Default | Meaning |
|---|---|---|
| `gimle.agent.nodeId` | `node-1` | This node's identifier. |
| `gimle.agent.controlPlaneUrl` | `http://127.0.0.1:8080` | Control plane to register with. |
| `gimle.agent.gossipAddress` | `127.0.0.1:9090` | This node's own gossip listen address — see [Service fabric](../architecture/service-fabric.md). |
| `gimle.agent.transportProtocol` | *(unset, plaintext)* | Local-dev convenience for `gimle.transport.protocol` — see [Transport security](../architecture/transport-security.md). |
| `gimle.agent.andvariEndpoint` | *(unset)* | `host:port,...` of one or more artifact registry replicas to pull module jars from on a coordinate-only deployment's cache miss, rotating and failing over between them — see [Node topology](../architecture/node-topology.md#andvari). An agent whose tenants only ever use a local `artifactPath` never needs this configured. |

```bash
# A second agent alongside the first, on the same machine
mvn gimle:agent -Dgimle.agent.nodeId=node-2 -Dgimle.agent.gossipAddress=127.0.0.1:9091
```

## `mvn gimle:bootstrap`

Collapses the multi-terminal local-dev walkthrough (`tls-init` if TLS, `store`, `muninn`,
`andvari`, `fafnir`, `controlplane`, `agent`, then a bootstrap-token `apply` per manifest if TLS)
into one foreground command: brings up a single-node cluster with every process kind, deploys each
`gimle-examples` module once the cluster is ready, prints a summary, then blocks until interrupted
and tears the whole cluster back down. Unlike every other goal here, it isn't self-filtered to one
reactor module — it needs nine modules' runtime classpaths and supervises six long-running
processes together, so it runs only from the root aggregator project.

In TLS mode the one-time bootstrap console password this goal's own `tls-init` step mints is
written to `bootstrap-password.txt` beside the rest of the TLS material, never printed — this goal
inherits Maven's own stdout, so printing it would put the cluster's first administrator credential
into the build log of any non-interactive run. The closing summary names the file; read it, then
delete it.

`gimle.bootstrap.baseDir` (Raft state, secret key files, Muninn's day files, TLS material) survives
between runs by design — stopping and restarting this goal resumes the same cluster rather than
starting from scratch. `-Dgimle.bootstrap.clean=true` wipes it first for a guaranteed-fresh
cluster instead.

Before spawning anything, this goal probes every port it's about to bind and fails loudly if one
is already listening, rather than silently attaching to whatever unrelated cluster is already
there (a shared Midgard container, a manually-started cluster). Each of the six ports it binds is
independently overridable for exactly that situation — point this goal at ports nothing else on
the machine already owns.

| Property | Default | Meaning |
|---|---|---|
| `gimle.bootstrap.protocol` | `plaintext` | `plaintext` or `tls`. |
| `gimle.bootstrap.baseDir` | `${project.basedir}/gimle-bootstrap` | Where all spawned processes' state, logs, and (in TLS mode) certificates persist to disk. |
| `gimle.bootstrap.storeRaftPort` | `9080` | Store's own Raft peer port. |
| `gimle.bootstrap.storeClientPort` | `9091` | Store's own client port — matches `mvn gimle:store`'s own default. |
| `gimle.bootstrap.fafnirPort` | `9092` | Fafnir's own port — matches `mvn gimle:fafnir`'s own default. |
| `gimle.bootstrap.muninnPort` | `9093` | Muninn's own port — matches `mvn gimle:muninn`'s own default. |
| `gimle.bootstrap.andvariPort` | `9094` | Andvari's own port — matches `mvn gimle:andvari`'s own default. |
| `gimle.bootstrap.controlPlanePort` | `8080` | Control plane's own API port — matches `mvn gimle:controlplane`'s own default. |
| `gimle.bootstrap.deployExamples` | `true` | Deploy every `gimle-examples` module once the cluster is ready. |
| `gimle.bootstrap.clean` | `false` | Wipe `gimle.bootstrap.baseDir` before spawning anything. |
| `gimle.bootstrap.readyTimeoutSeconds` | `120` | How long to wait for each process/condition (port open, node registered, deployment `ACTIVE`) before giving up. |

```bash
mvn gimle:bootstrap
mvn gimle:bootstrap -Dgimle.bootstrap.protocol=tls
mvn gimle:bootstrap -Dgimle.bootstrap.clean -Dgimle.bootstrap.deployExamples=false
# Point at ports that don't collide with something already running on this machine
mvn gimle:bootstrap -Dgimle.bootstrap.controlPlanePort=18080 -Dgimle.bootstrap.storeRaftPort=19080 \
    -Dgimle.bootstrap.storeClientPort=19091 -Dgimle.bootstrap.fafnirPort=19092 \
    -Dgimle.bootstrap.muninnPort=19093 -Dgimle.bootstrap.andvariPort=19094
```

## `mvn gimle:deploy`

A thin wrapper around a real `GimleCli apply` invocation — see
[Deploy your first module](../tutorials/deploy-your-first-module.md).

| Property | Default | Meaning |
|---|---|---|
| `gimle.deploy.file` | *(required)* | Path to the deployment manifest. |
| `gimle.deploy.server` | `127.0.0.1:8080` | Control plane address. |

```bash
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/greeter-provider/deployment.yaml
```

## `mvn gimle:publish`

A thin wrapper around a real `GimleCli artifact push` invocation, run from inside a module's own
project directory once its jar is built — the `gimle:deploy` shape applied to pushing rather than
deploying. Unlike `gimle:deploy`, this goal isn't self-filtered to one reactor module: it resolves
`gimle-cli`'s own runtime classpath by coordinate, so it works from any module project that depends
on `gimle-maven-plugin`. The coordinate itself is read from the jar's own bundled
`gimle-module.yaml`, not from a Maven property.

| Property | Default | Meaning |
|---|---|---|
| `gimle.publish.file` | `${project.build.directory}/${project.build.finalName}.jar` | The module jar to push. |
| `gimle.publish.server` | `127.0.0.1:8080` | Control plane address (pushes proxy through `/artifacts/*`, the same as `gimle artifact push` run directly). |
| `gimle.publish.cliVersion` | `${plugin.version}` | Version of `gimle-cli` to resolve and spawn — defaults to this plugin's own version, since the two ship from one build; override to pin a CLI build other than the plugin's own. |

```bash
mvn gimle:publish -pl gimle-examples/greeter-provider
```

## `mvn gimle:artifactset-push`

The multi-module answer to `gimle:publish`: bound once at a reactor **aggregator** root (not
self-filtered to one module — extends the same `AbstractGimleRootMojo` base `gimle:flaky-tests` and
`gimle:saga` do, so it runs exactly once per reactor invocation regardless of how many modules it's
bound in), it walks every module already in the current reactor, generates a `kind: ArtifactSet`
manifest (see the [manifest schema](./manifest-schema.md#artifactset-manifest)) grouping their built
jars by tenant, and shells out to a real `GimleCli apply` the same way `gimle:publish` shells out to
`artifact push`. The generated manifest pins `apiVersion: v1` explicitly (see
[manifest versioning](./manifest-schema.md#manifest-versioning-apiversion)) — generated output
never leans on the unversioned default, so regeneration can't change meaning if that default ever
moved.

Tenant assignment defaults to one reactor-wide `gimle.artifactset.tenantId` value; a submodule that
belongs to a different tenant overrides it with its own `gimle.artifactset.tenantId` property in its
own `pom.xml`:

```xml
<!-- billing-service/pom.xml -->
<properties>
  <gimle.artifactset.tenantId>billing</gimle.artifactset.tenantId>
</properties>
```

| Property | Default | Meaning |
|---|---|---|
| `gimle.artifactset.tenantId` | *(unset)* | Reactor-wide default tenant; a submodule's own property (above) wins when present. Unset and no override means untenanted. |
| `gimle.artifactset.server` | `127.0.0.1:8080` | Control plane address, same as `gimle:publish`. |
| `gimle.artifactset.cliVersion` | `${plugin.version}` | Version of `gimle-cli` to resolve and spawn, same convention as `gimle:publish`. |

A `pom`-packaged project (the aggregator root itself, typically) builds no jar and is skipped
rather than failing the set on its own nonexistent artifact — unless it declares an explicit
`gimle.artifactset.kind` (below), which opts it back in. The goal also runs correctly under a
`-pl <submodule>` invocation from the aggregator directory: when the reactor contains no
execution-root project, it executes once in the reactor's first project instead of silently doing
nothing.

A submodule whose build output isn't a plain module jar declares that in its own `pom.xml` with the
same per-module-property shape (each per-module only — there is no reactor-wide default for these):

| Per-module property | Meaning |
|---|---|
| `gimle.artifactset.kind` | `module` (the default when absent), `vessel` (a plain runnable jar), or `bundle` (a multi-file application directory — see the [manifest schema's bundle entry](./manifest-schema.md#artifactset-manifest)). |
| `gimle.artifactset.artifact` | Overrides the entry's artifact path, relative to the submodule's own base directory. Effectively required for `bundle` — a Quarkus fast-jar build outputs `target/quarkus-app`, which the goal cannot guess. |
| `gimle.artifactset.command` | `bundle` only, required: the entrypoint argv, comma-separated (`java,-jar,quarkus-run.jar`). |
| `gimle.artifactset.workdir` | `bundle` only, optional launch directory inside the unpacked bundle. |
| `gimle.artifactset.name` / `.version` | Override the `vessel`/`bundle` coordinate; defaults are `{groupId}.{artifactId}` and the project's own version. |

```xml
<!-- orders-report-ui/pom.xml — a Quarkus fast-jar submodule -->
<properties>
  <gimle.artifactset.kind>bundle</gimle.artifactset.kind>
  <gimle.artifactset.artifact>target/quarkus-app</gimle.artifactset.artifact>
  <gimle.artifactset.command>java,-jar,quarkus-run.jar</gimle.artifactset.command>
</properties>
```

```bash
mvn gimle:artifactset-push -Dgimle.artifactset.tenantId=orders-platform -pl gimle-examples/orders-platform -am
```

## `mvn gimle:doctor`

A thin wrapper around a real `hilmir doctor` invocation (see the [`gimle-hilmir`
reference](./hilmir-reference.md#doctorinit)), run from inside a module's own project directory once
its jar is built — the same "resolve `gimle-hilmir` by coordinate, run from any project" shape
`gimle:publish` already established for `gimle-cli`. Like `gimle:publish`, this isn't self-filtered
to one reactor module of this repo's own: it works from any module project that depends on
`gimle-maven-plugin`, whether or not it's this repo's own reactor.

| Property | Default | Meaning |
|---|---|---|
| `gimle.doctor.jar` | `${project.build.directory}/${project.build.finalName}.jar` | The jar to diagnose. |
| `gimle.doctor.vessel` | `false` | Evaluate the jar under vessel-hosting intent instead of the default module-hosting one. |
| `gimle.doctor.server` | *(unset)* | Control plane address for the cluster-aware checks (registry-coordinate existence, tenant existence). Unset skips them, running only the static catalog. |
| `gimle.doctor.tenant` | *(unset)* | Tenant id to check exists on the control plane behind `gimle.doctor.server`. |
| `gimle.doctor.hilmirVersion` | `${plugin.version}` | Version of `gimle-hilmir` to resolve and spawn — defaults to this plugin's own version, since the two ship from one build. |

```bash
mvn gimle:doctor -pl gimle-examples/greeter-provider
mvn gimle:doctor -Dgimle.doctor.server=127.0.0.1:8080 -pl gimle-examples/greeter-provider
```

## `mvn gimle:init`

A thin wrapper around a real `hilmir init` invocation (same reference section as `gimle:doctor`
above), writing `gimle-module.yaml`/`deployment.yaml` into the project directory. Same "runs
wherever it's invoked, not self-filtered" shape as `gimle:doctor` and `gimle:publish`.

| Property | Default | Meaning |
|---|---|---|
| `gimle.init.jar` | `${project.build.directory}/${project.build.finalName}.jar` | The jar to inspect. |
| `gimle.init.outDir` | `${project.basedir}` | Where to write the generated file(s) — this project's own directory, beside its `pom.xml`, never inside `target/` where the next `mvn clean` would delete them. Never overwrites a file that already exists there. |
| `gimle.init.hilmirVersion` | `${plugin.version}` | Same meaning as `gimle:doctor`'s own property. |

```bash
mvn gimle:init -pl gimle-examples/greeter-provider
```

## `mvn gimle:tls-init`

Generates the cluster CA, the control plane's own leaf certificate, and the first human operator's
leaf certificate via a real `com.gimle.pki.PkiBootstrapMain` subprocess — everything a brand-new
cluster needs to start in `gimle.transport.protocol=tls` mode. See
[Transport security](../architecture/transport-security.md). Unlike `gimle:agent`, this needs no
cross-module classpath resolution: `PkiBootstrapMain` lives in `gimle-pki` itself, the module this
goal targets.

| Property | Default | Meaning |
|---|---|---|
| `gimle.tlsInit.outputDir` | `./gimle-tls` | Where the generated `.crt`/`.key` files are written. |
| `gimle.tlsInit.caCommonName` | `gimle-cluster-ca` | The cluster CA's own Subject CN. |
| `gimle.tlsInit.hostname` | `localhost` | SAN on the control plane's leaf certificate — clients must reach the control plane by this hostname, not a bare IP literal (no `iPAddress` SAN support yet). |
| `gimle.tlsInit.passwordFile` | *(unset)* | Where to write the one-time bootstrap admin password. Unset, the password is printed — but only when the build's own output is a terminal. A non-interactive run (CI, redirected output) must set this, or the goal fails rather than writing the plaintext password into a build log. See [Bootstrap (day 0)](../architecture/authn-authz.md#bootstrap-day-0). |

```bash
mvn gimle:tls-init -Dgimle.tlsInit.outputDir=./gimle-tls -Dgimle.tlsInit.hostname=localhost
```

## `mvn gimle:saga`

Ensures a real `SagaMain` process is up — the test-run report server, serving its own bundled
console at `/console` (see [Node topology](../architecture/node-topology.md)). Idempotent: if a
Saga instance already answers its health check on the configured port, this goal reuses it and
just prints the console URL rather than spawning a second one — the intended everyday use, since a
long-lived instance is what lets the flake scoreboard accumulate history across runs. `mvn
gimle:saga-stop` tears it down again.

| Property | Default | Meaning |
|---|---|---|
| `gimle.saga.port` | `9096` | Ingest/read HTTP port, and where the console is served. |
| `gimle.saga.dataRoot` | `~/.gimle/saga` | Where run event logs and the derived flake ledger persist to disk — outside the build, so `mvn clean` never touches it. |
| `gimle.saga.host` | *(unset, loopback)* | Bind address override — Saga carries no authentication, so binding beyond loopback is a deliberate, logged choice. |
| `gimle.saga.serverVersion` | `${plugin.version}` | Version of `gimle-saga` to resolve and spawn — defaults to this plugin's own version, since the two ship from one build. |

```bash
mvn gimle:saga
mvn gimle:saga-stop
```

### `mvn gimle:saga-stop`

Best-effort shutdown of the local Saga server: asks it to stop over its own `POST /api/shutdown`
first, falling back to signalling the pid `gimle:saga` recorded at spawn time if that's
unreachable. Never fails the build — "nothing was running" is a fine outcome for a stop command.
Takes the same `gimle.saga.port` property as `gimle:saga` above, no others.

## `mvn gimle:verify`

The one-command report loop: ensures a Saga server is up (reusing one already running), mints a
run id from the current git branch/sha, launches the given Maven command as a genuine separate
child process — never nested in the current reactor, the same reasoning `gimle:agent`'s worker
process keeps separate — with `gimle.saga.endpoint`/`gimle.saga.runId` threaded through so every
test JVM's own `SagaTestListener` streams live, sweeps `**/target/surefire-reports/*.xml` as a
safety net for anything a dead test JVM never got to flush, and prints the run's console URL. The
child always runs with `-Dmaven.build.cache.skipCache=true`: a build-cache restore skips Surefire
entirely, which would silently report zero tests for an otherwise-real run.

| Property | Default | Meaning |
|---|---|---|
| `gimle.saga.port` | `9096` | Same as `gimle:saga` above. |
| `gimle.saga.mavenArgs` | `verify` | The command line to hand to the child Maven process, e.g. `-Psmoke` or `-pl gimle-mimir -am test`. |
| `gimle.saga.serverVersion` | `${plugin.version}` | Same as `gimle:saga` above. |

```bash
mvn gimle:verify
mvn gimle:verify -Dgimle.saga.mavenArgs="-Psmoke"
mvn gimle:verify -Dgimle.saga.mavenArgs="-pl gimle-mimir -am test"
```

## `mvn gimle:saga-import`

Folds `**/target/surefire-reports/*.xml` into a running Saga server after the fact — the standalone
version of `gimle:verify`'s own safety-net sweep, for CI runs or any build that didn't set
`gimle.saga.endpoint` up front. Importing into a run id that's already live only appends the test
results the live stream never delivered, so running this after a normal `gimle:verify` pass is a
safe no-op rather than a duplicate.

| Property | Default | Meaning |
|---|---|---|
| `gimle.saga.port` | `9096` | Same as `gimle:saga` above. |
| `gimle.saga.runId` | *(unset)* | Run id to fold the reports into. Unset derives one deterministically from the reports' own content, so re-running the import twice against unchanged reports is idempotent. |

```bash
mvn gimle:saga-import -Dgimle.saga.runId=2026-08-16T10-30-05_abc1234
```

## `mvn gimle:flaky-tests`

Runs every `@Tag("flaky")` test (see the repo's own `FLAKY_TESTS.md`), one listed module at a
time, each as its own genuinely separate `mvn -pl <module> test -Dgroups=flaky` child process --
never nested inside this build's own reactor. `@Tag("flaky")` tests are excluded from every
module's default `mvn verify` (root `pom.xml`'s Surefire `excludedGroups=flaky`); this goal is how
they still get run, with nothing else in the reactor to contend with.

Each listed module can optionally be repeated several times in a row, still one clean standalone
reactor invocation per repeat -- a nightly run accumulates real pass/fail evidence for a
known-flaky test across many runs instead of a single snapshot. The goal fails on the first repeat
of the first module that fails, logging which module and which repeat failed.

| Property | Default | Meaning |
|---|---|---|
| `gimle.flakyTests.modules` | `gimle-mimir` | Comma-separated artifactIds known to carry `@Tag("flaky")` tests -- a small, manually-maintained list, run strictly in order, one module at a time. |
| `gimle.flakyTests.repeat` | `1` | How many times, in a row, to run each listed module's flaky-tagged tests. Must be at least 1. |

```bash
mvn gimle:flaky-tests
mvn gimle:flaky-tests -Dgimle.flakyTests.modules=gimle-mimir,gimle-fabric
mvn gimle:flaky-tests -Dgimle.flakyTests.repeat=20
```

## `mvn gimle:docs`

Builds this documentation site end to end: runs `mvn javadoc:aggregate` at the repo root, copies
the output into `gimle-docs/static/javadoc/`, then builds the Docusaurus site — see `gimle-docs`'s
own `README.md` and `pom.xml` description for why those two steps aren't chained by the reactor
build alone.
