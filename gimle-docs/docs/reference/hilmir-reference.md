---
sidebar_position: 5
---

# `gimle-hilmir` reference

`gimle-hilmir` is five tools in one binary. The `validate`/`plan`/`up`/`down`/`status`/`pki init`
verbs are a declarative-topology cluster bootstrapper — they read a topology YAML document and turn
it into real, running Gimlé processes on the local machine, or the exact per-machine process
commands the topology implies. `store add`/`store remove` are an operator-facing surface over the
store's own live Raft membership change, talking directly to an already-running cluster over the
same binary `StoreClient` protocol `gimle-mimir` itself uses — see
[Store membership verbs](#store-membership-verbs) below. The `deploy`/`upgrade`/`rollback`/
`undeploy`/`releases`/`release-status` verbs are a Helm-equivalent release lifecycle layered on top
of an already-running cluster — they talk to the control plane's own HTTP API, the same way
`gimle-cli` does, and never touch a topology document at all. `doctor`/`init` are a fourth,
independent concern: deployability diagnostics and manifest scaffolding for a single built jar,
needing neither a topology document nor a running control plane (`doctor --server` only adds
cluster-aware checks on top of its own static ones) — see [`doctor`/`init`](#doctorinit) below.
`enable`/`disable` are a fifth concern layered directly on top of the release verbs: turning a named
platform extension (today, only `gateway`) on or off against an already-running cluster, without an
operator ever hand-writing its bundle file — see
[Extensions (`hilmir enable`/`hilmir disable`)](#extensions-hilmir-enablehilmir-disable) below.

## Machine bootstrap verbs

```text
hilmir validate -f <topology.yaml>
hilmir plan -f <topology.yaml> [--machine <name>]
hilmir up -f <topology.yaml> --machine <name>
hilmir down --machine <name> [--data-root <path>]
hilmir status --machine <name> [--data-root <path>]
hilmir pki init -f <topology.yaml>
```

`validate` checks a topology document for structural and semantic problems (missing machines, port
conflicts, an even-numbered store replica count, TLS material referenced but never declared) without
starting anything. `plan` resolves a validated topology into the exact per-machine process commands
each of Gimlé's process kinds expects — useful for inspecting what `up` would run before it runs it.
`up` actually spawns every process a topology assigns to `--machine`, waiting on any cross-machine
prerequisite (a store replica another machine hosts, say) via a plain TCP-connect readiness check
before starting anything that depends on it; `down`/`status` act on the run ledger `up` wrote, so
neither needs the topology document again. `pki init` mints the cluster's certificate authority and
every process's leaf certificate up front, for a topology that declares `tls:`.

## Store membership verbs

```text
hilmir store add <peerId> <host> <raftPort> <clientPort>
    (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
    [--pki-dir <dir>]
hilmir store remove <peerId>
    (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
    [--pki-dir <dir>]
```

`gimle-mimir`'s Raft membership has always been dynamically reconfigurable at runtime
(`StoreClient#addServer`/`#removeServer`), but until now the only callers were test fixtures. `store
add`/`store remove` are the operator-facing surface over exactly that: each builds a short-lived
`StoreClient` against a resolved bootstrap endpoint list, makes the one call, and closes it
immediately after — never a cached or reused client, and no control-plane HTTP hop involved at any
point (there is no `gimle-controlplane`/`gimle-cli` surface for this; it is a direct binary
`StoreClient` connection to the store cluster, the same way `gimle-smoke-tests`' `RaftResilienceIT`
and `gimle-holmgang`'s `GimleCluster` already talk to it).

`peerId` follows the same `host:raftPort` convention used everywhere else in the codebase
(`PeerAddress.raftId()`) and is always given explicitly — it is never derived from `<host>`/
`<raftPort>` automatically, since a real caller (a test fixture, an operator) already has it in hand
independently.

Exactly one of two ways to reach the cluster is required:

- `--topology <file>` — parses the topology document the same way `validate`/`plan`/`up` do, derives
  the bootstrap endpoint list from `topology.store().replicas()` resolved against
  `topology.machines()`, and auto-detects mTLS from the topology's own `transport`/`tls` fields.
- `--server <host:clientPort>[,<host:clientPort>...]` — a direct, topology-free endpoint list (any
  member of a running cluster answers; `StoreClient` follows leader redirects on its own). Plaintext
  by default; `--pki-dir <dir>` turns on mTLS, presenting `<dir>/operator.crt`/`<dir>/operator.key`
  against `<dir>/ca.crt` — the same three-file operator-identity convention `hilmir up`'s own
  bootstrap-token minting already uses for talking to a running cluster.

Giving neither or both flags is a clean usage error, not a silently-picked default.

Both `addServer`/`removeServer` throw the same `GimleRaftException` for genuine unreachability and
for a transient "another membership change is still in flight" rejection alike (a just-added
learner's automatic promotion racing the call, most commonly) — the two aren't distinguishable from
the exception alone. Both verbs therefore retry the call up to 10 times, 200ms apart, printing one
progress line if they're still retrying after a few attempts, and only surface the final attempt's
error once every retry is exhausted — so a transient rejection resolves on its own within a second or
two, while a genuinely unreachable cluster or a request that can never succeed (removing a peer that
was never a member) still fails within a few seconds, not a hang.

On success, each verb prints one confirmation line naming the peer and the operation performed.

## Release verbs

```text
hilmir deploy -f <bundle.yaml> [--values <file>] [--set k=v]... [--wait] [--dry-run] [-o json]
hilmir upgrade -f <bundle.yaml> [--values <file>] [--set k=v]... [--wait] [--dry-run] [-o json]
hilmir rollback --release <name> [--to-revision r] [--wait] [--dry-run] [-o json]
hilmir undeploy --release <name> [--keep-tenants] [-o json]
hilmir releases [-o json]
hilmir release-status <name> [-o json]
```

Every release verb targets an already-running control plane over plain HTTP (`--server host:port`,
or the `GIMLE_SERVER` environment variable — the same precedence `gimle-cli` itself uses), through a
small HTTP client `gimle-hilmir` builds and owns itself rather than depending on `gimle-cli` for one
class. A **bundle** is a `kind: Bundle` manifest declaring the tenants, plain config entries,
secrets, and workload manifests a release is made of; a **release** is the record of one bundle
having been applied, tracked entirely in the control plane's own plain config store under a fixed
bookkeeping tenant, `gimle-hilmir` — no separate storage, no local state file.

### The bundle format

```yaml
kind: Bundle
name: greeter-suite
version: 1.0.0
values: # defaults; overridden by --values <file>, then by --set k=v (repeatable, highest precedence)
  apiToken: ""
tenants:
  - id: acme
    quota: {maxMemoryBytes: 268435456, maxCpuMillicores: 1000, maxInstances: 10}
config:
  - {tenant: acme, key: greeting.prefix, value: "Hello"}
secrets:
  - {tenant: acme, key: api.token, value: "${values.apiToken}"}
workloads:
  - file: provider-deployment.yaml # sibling file, resolved relative to the bundle file
  - manifest: | # inline manifest (raw YAML)
      kind: Deployment
      name: greeter-consumer
```

`${values.key}` substitution is deliberately minimal — no conditionals, no loops, no expression
language — and applies to `config[].value`, `secrets[].value`, and the full text of every workload
manifest (both `file:`-referenced and inline). Precedence mirrors Helm's own: the bundle's own
`values:` block first, a `--values <file>` (a flat YAML mapping of key to scalar) next, then any
number of repeatable `--set key=value` flags, which win over both. A reference with no default and
no override supplied is a named, immediate error at render time — never a literal `${values.x}`
string sent to the control plane.

### `deploy` / `upgrade`

`deploy` renders the bundle, refuses if a release under this bundle's own `name` already exists
(clearly naming `upgrade` as the alternative), then applies its full state in order — every declared
tenant, then every plain config entry, then every secret (value base64-encoded, matching `gimle
secret set`'s own wire convention), then every workload manifest (its `kind:` field picks the right
control-plane URL prefix, mirroring `gimle apply`'s own dispatch) — and records this as revision 1.
`upgrade` requires an existing release, applies the new bundle's full state the same way, then
**prunes**: any workload the *previous* revision applied that the new bundle no longer declares gets
deleted, matching Helm's own upgrade semantics.

`--dry-run` renders (and, for `upgrade`, computes the prune list against the release's existing
ledger state) and prints the plan without applying anything — for `deploy`, that means no
control-plane call at all, since there's no existing release to check against; for `upgrade`, only
the read calls needed to compute an accurate plan run, never a write. `--wait` polls every applied
workload to its own kind-appropriate readiness signal before returning:

| Kind | "Ready" means |
|---|---|
| Deployment / DaemonSet / StatefulSet | Every instance has a live observation, and every observation's `lifecycleState` is `ACTIVE`. An empty instance list is not yet ready. |
| Job | The job's `phase` has reached a terminal state (`SUCCEEDED` or `FAILED`) — waiting means waiting for completion, not asserting success, the same posture `kubectl wait` itself takes. |
| CronJob | A single successful `GET` — there's no per-instance active state for a schedule, so existence is the whole signal. |

### `rollback`

```text
hilmir rollback --release greeter-suite --to-revision 2
```

Reads a past revision's full snapshot (the rendered manifests actually applied at that revision, not
the original bundle file, which may no longer exist) and re-applies it as a **new** revision, pruning
anything the *current* revision has that the target one doesn't — never rewrites history in place,
the same way `helm rollback` itself always creates a new revision rather than time-traveling. With no
`--to-revision`, rolls back to the revision immediately before the release's current one, the same
"undo my last change" default `helm rollback` uses when given no explicit revision.

### `undeploy`

Deletes every workload the release's current revision lists (in reverse of the order they were
applied), deletes the tenants it created unless `--keep-tenants` is given, then deletes the release's
own ledger rows entirely. This is a v1 simplification versus Helm's own `--keep-history`: once
undeployed, a release's revision history is gone, not merely hidden.

### `releases` / `release-status`

`releases` lists every release's name, current revision, and bundle version. `release-status <name>`
shows that plus each of the current revision's resources' live status, fetched fresh from the
control plane.

### The release ledger

A release's state is two kinds of row in the control plane's plain config store, under the fixed
tenant `gimle-hilmir` (created idempotently the first time any release verb runs against a cluster
that doesn't have it yet):

- `hilmir.release.<name>.meta` — a small pointer: `{bundleName, bundleVersion, currentRevision,
  tenants}`.
- `hilmir.release.<name>.rev.<n>` — that revision's full snapshot: every rendered manifest actually
  applied, `{revision, resources: [{kind, name}], appliedAtEpochMilli, tenants, config, secrets,
  workloads, rollbackOfRevision}`.

The naming deliberately uses `.` rather than `@` as the revision delimiter, even though it's the same
idea Fafnir's own `key@N`/`key@meta` versioned-secret convention uses: the control plane's `GET
/config/{tenantId}` list endpoint already strips any key whose text after its last `@` character
reads `meta` or is all digits, on the assumption that shape always means a Fafnir-managed secret row
— a ledger key in that exact shape would be invisible to `releases`/`release-status`, which have no
per-key `GET` to fall back on, only list-and-filter.

## Extensions (`hilmir enable`/`hilmir disable`)

```text
hilmir enable gateway --server <host:port> [--modules-dir <dir>] [--values <file>]
    [--set k=v]... [--wait] [--dry-run] [-o json]
hilmir disable gateway --server <host:port> [-o json]
```

A platform **extension** is an optional, platform-owned module shipped inside every
`gimle-platform-<version>` archive's own `modules/` directory (a sibling of `bin/` and `lib/`) rather
than something an operator builds or packages themselves. `enable`/`disable` turn one on or off
against an already-running cluster without an operator ever hand-writing a bundle file for it — under
the hood, both are thin wrappers over the same release-verb machinery `deploy`/`upgrade`/`undeploy`
already provide (see [Release verbs](#release-verbs) above): no separate apply logic, no separate
ledger. Today the only extension is `gateway`; the verb shape (`hilmir enable <extension>`/`hilmir
disable <extension>`) leaves room for more without changing this one's own flags.

### `enable gateway`

1. Resolves a **modules directory** and finds the single `gimle-gateway-*.jar` inside it (zero or
   more than one match is a clear error).
2. Reads that jar's own `gimle-module.yaml` to derive its push coordinate — `(name, version)`.
3. HEAD-checks the artifact registry for that exact coordinate (through the control plane's existing
   `/artifacts/*` proxy, the same check `doctor --server` uses); pushes the jar only if the registry
   doesn't already have it under an identical sha256, so re-running `enable` after nothing has
   changed does no redundant upload.
4. Deploys (first time) or upgrades (an existing `gimle-gateway` release found) a **synthesized**
   bundle reproducing `gimle-gateway`'s real manifest shape: a `DaemonSet` named `gimle-gateway`,
   `tenantId: gimle-system`, `placement.requiredLabels: [edge]`, `module: {name, version}` with no
   `artifactPath` set (admission resolves it through the registry coordinate just pushed — the
   production path every node actually takes, not a local dev-tree file path), plus the two
   `gimle-system/gateway.port`/`gateway.routes` config keys `GatewayHooks` reads at startup,
   defaulted to `8090`/`""` (no routes) and overridable through the same `--values <file>`/`--set
   k=v` mechanism every other release verb uses.

Enabling again after the jar under `--modules-dir` has been rebuilt at a new version re-derives the
coordinate from that new jar, pushes it, and takes the **upgrade** path against the existing
`gimle-gateway` release — the same "point it at the new artifact and re-run" workflow `deploy`/
`upgrade` already give every other release.

### Modules-directory resolution

`--modules-dir <dir>` is the explicit override; absent that, the default is `<GIMLE_HOME>/modules`,
where `GIMLE_HOME` is an environment variable the platform archive's own `bin/hilmir` wrapper script
exports automatically (the install root, a sibling of `bin/`, `lib/`, and `modules/`). There is no
third, silent fallback: `hilmir` launched directly (a hand-built classpath, or from the standalone
`gimle-hilmir-<version>.tar.gz` archive, which has no `modules/` directory of its own at all) fails
with a clear error naming both the environment variable and the flag, rather than guessing from
`java.class.path`.

### `disable gateway`

Undeploys the release named `gimle-gateway` — `undeploy --release gimle-gateway` under the hood, with
nothing else to do: the synthesized bundle `enable` applies declares no `tenants:` entries of its own
(`gimle-system` is a reserved tenant the control plane seeds automatically at startup), so there is
never a tenant for `undeploy`'s own `--keep-tenants` behavior to act on for this particular release,
and `disable` doesn't expose that flag.

### Operator credentials are required

`gimle-system` is a reserved tenant: the control plane only accepts a write against it from an mTLS
caller in the `gimle:operators` group, and the artifact registry's own push authorization checks the
same group on the forwarded identity. Both `enable` and `disable` therefore need the same operator PKI
material (`-Dgimle.tls.certFile/keyFile/caFile` pointing at a `gimle:operators`-group leaf) an
operator already needs for `hilmir deploy`/`upgrade` against any `gimle-system`-scoped bundle. A
401/403 from either the registry push or the release apply is reported with that requirement spelled
out directly, rather than the control plane's own opaque status-code message.

## `doctor`/`init`

```text
hilmir doctor <jar> [<dep-jar>...] [--vessel] [--server host:port] [--tenant <id>] [-o json]
hilmir init <jar> [--out-dir <dir>]
```

Both share one analyzer (`com.gimle.hilmir.analyze`): structural jar inspection (mirroring
`ModuleArtifactReader`'s own `JarFile`/`JarEntry` shape), a lenient `gimle-module.yaml` reader, and a
`java.lang.classfile`-based bytecode scanner for a fixed set of hazard signals (`System.exit`,
shutdown-hook registration, non-daemon `Thread` construction, native-library loading, server-socket
opening, a static `ExecutorService` field with no visible shutdown call anywhere in its class). This
is a linear instruction-stream scan for known failure classes, not a control-flow/dataflow analysis
— reflection-driven or indirectly-invoked hazards are false negatives by design, the same "tripwire,
not certification" posture the design already takes elsewhere.

### Module-hosting vs. vessel-hosting

The platform itself never sniffs a jar's structure to decide module-hosting vs. vessel-hosting — a
deploy manifest's own `vessel:` block presence/absence is the one and only switch (see
[Vessel workloads](./manifest-schema.md#vessel-workloads-vessel)). `doctor` mirrors that posture: it
evaluates a jar under the module-hosting interpretation by default (the richer, more constrained
path — real isolation-tier/resource/probe/hook validation), and only under `--vessel` does it switch
to the smaller vessel-hosting check set. One finding, `NOT_LAYER_HOSTABLE`, is reported at a
different severity depending on which mode was asked for: an `ERROR` under the default module intent
(a launcher archive genuinely cannot be module-hosted), an `INFO` note under `--vessel` (confirming
the shape is exactly what vessel-hosting expects). `init` makes the same jar-shape call for itself,
since it has no manifest to read a `vessel:` block from yet — see below.

### `doctor`'s static finding catalog

| Code | Severity | Fires when |
|---|---|---|
| `NOT_LAYER_HOSTABLE` | `ERROR` (module intent) / `INFO` (`--vessel`) | The jar is a launcher archive (nested `BOOT-INF/`/Quarkus-fast-jar-style classpath layout) — a real Spring Boot/Quarkus launcher jar, not a flat classes-on-a-module-path shape. |
| `UNSUPPORTED_PACKAGING` | `ERROR` | Not a plain runnable jar at all: a `.war`/`.ear`, a directory/fast-jar distribution, or a non-ZIP-shaped binary (most commonly a native-image executable). |
| `NOT_A_MODULE` | `WARNING` | No `module-info.class`, module-hosting was intended, and the jar isn't a launcher archive either (that's `NOT_LAYER_HOSTABLE`'s case) — an ordinary flat, non-modularized jar. |
| `DESCRIPTOR_UNREADABLE` | `ERROR` | Module-hosting intended, the jar has `module-info.class`, but its `gimle-module.yaml` is missing or missing a required field (`name`, `version`, `isolation.tier`, `resources.request`/`.limit`) — grounded directly in `ModuleArtifactReader`'s own hard failure for exactly this case, one code added beyond the originally scoped catalog. |
| `TIER3_REQUESTED` | `ERROR` | `gimle-module.yaml` declares `isolation.tier: TIER_3` — unimplemented on every platform today, rejected outright at scheduling time. |
| `RESOURCES_INCOHERENT` | `ERROR` | `resources.limit` < `resources.request`, or either is an unparseable quantity. |
| `PROBE_INVALID` | `ERROR` | A class named in `health.liveness`/`health.readiness`/`lifecycle.hooks`/`lifecycle.jobHooks` is missing from the jar, doesn't directly implement the expected interface (by name — see below), or has no no-arg constructor. |
| `VERSION_DRIFT` | `WARNING` | `gimle-module.yaml`'s own `version` doesn't match the jar manifest's `Implementation-Version` attribute. |
| `NATIVE_CODE` | `ERROR` | Bundled `.so`/`.dll`/`.dylib` entries, or a class calling `System.load`/`System.loadLibrary`. |
| `CALLS_SYSTEM_EXIT` | `ERROR` (module intent) / `WARNING` (`--vessel`) | A class calls `System.exit`. |
| `LEAK_RISK` | `WARNING` | A class registers a JVM shutdown hook, constructs a `Thread` without `setDaemon(true)`, or declares a static `ExecutorService` field with no `shutdown`/`shutdownNow`/`close` call anywhere in that same class. |
| `BINDS_OWN_PORT` | `INFO` | A class opens a `ServerSocket`/`ServerSocketChannel`/`com.sun.net.httpserver.HttpServer` — informational only, the platform has no ingress story for a plain module today. |
| `SPLIT_PACKAGE` | `ERROR` | Two of the jars given on the command line (the primary plus any `<dep-jar>` arguments) declare the same package. |
| `BUNDLED_LOGGING_BINDING` | `WARNING` | A `logback-classic`/`log4j-core`/`slf4j-simple` class prefix or bundled dependency jar name is found among the artifact's own entries (its own nested `lib/` layout, or an extra `<dep-jar>` argument). |

`PROBE_INVALID`'s "implements the expected interface" check is a name-only comparison against the
class's own declared (`implements`) interfaces, not a walk up its superclass chain, and its
no-arg-constructor check doesn't verify accessibility — `doctor` cannot depend on `gimle-module` for
the real `LivenessProbe`/`ReadinessProbe`/`ModuleLifecycleHooks`/`JobHooks` interfaces, so it
compares binary names only.

### Cluster-aware checks (`--server`)

`--server host:port` (the same control-plane address the release verbs use) adds two more checks on
top of the static catalog, both additive, never replacing anything above:

| Code | Severity | Fires when |
|---|---|---|
| `REGISTRY_COORDINATE_NOT_FOUND` | `ERROR` | The jar's own `(name, version)` isn't present in the artifact registry behind `--server` (a plain `HEAD /artifacts/{name}/{version}` through the control plane's existing `/artifacts/*` proxy — no separate Andvari address needed). |
| `REGISTRY_UNREACHABLE` | `WARNING` | The registry coordinate couldn't be confirmed (no registry configured on that control plane, or a transport failure). |
| `TENANT_NOT_FOUND` | `ERROR` | `--tenant <id>` was given and that tenant doesn't exist on the control plane behind `--server`. |

This is deliberately not exhaustive — quota headroom, scheduler feasibility, and similar deeper
cluster checks are a clearly scoped-out follow-up, not something this pass tried to force in.

### `init`

Inspects a built jar with the same analyzer `doctor` uses and writes `deployment.yaml`, plus
`gimle-module.yaml` when the jar is module-hosting-shaped (no `module-info.class`, or a
launcher-archive layout, routes to the vessel form instead — the same jar-shape judgment call
`doctor --vessel` makes explicit via a flag, made automatically here since there's no manifest yet to
read a `vessel:` block from). Detected facts (a probe/hooks class actually found implementing the
right interface with a no-arg constructor, the module name from `module-info.class` when present)
are filled in directly; everything else (version, resource sizing, isolation tier) gets a
conservative default annotated `# TODO: measure and adjust`. Never overwrites a file that already
exists at either target path — refuses outright, listing every colliding path, rather than silently
clobbering a hand-edited file.
