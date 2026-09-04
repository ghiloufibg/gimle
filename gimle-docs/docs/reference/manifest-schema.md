---
sidebar_position: 3
---

# Manifest schema

Field-by-field reference for `gimle-module.yaml`, the descriptor bundled into every module artifact
under `META-INF/gimle/`. Grounded directly in `ModuleDescriptorParser` — every field below is
something the parser actually reads, not an aspirational schema.

## Full example

Taken from the real `greeter-provider` example (`gimle-examples/greeter-provider/src/main/resources/META-INF/gimle/gimle-module.yaml`):

```yaml
name: com.gimle.examples.greeter.provider
version: 1.0.0
isolation:
  tier: TIER_2
resources:
  request:
    memory: 32Mi
    cpu: 20m
  limit:
    memory: 64Mi
    cpu: 100m
exports:
  - service: com.gimle.examples.greeter.Greeter
    version: 1.0.0
lifecycle:
  hooks: com.gimle.examples.greeter.provider.GreeterProviderHooks
health:
  liveness: com.gimle.examples.greeter.provider.GreeterLivenessProbe
  readiness: com.gimle.examples.greeter.provider.GreeterReadinessProbe
```

## Fields

| Field | Required | Meaning |
|---|---|---|
| `name` | yes | The module's identifier (reverse-DNS style by convention, not enforced). |
| `version` | yes | The module's own version. |
| `resources.request.memory` / `.cpu` | yes | Requested memory (`Mi`/`Gi` suffix) and CPU (millicores, `m` suffix) — what the scheduler bin-packs against. |
| `resources.limit.memory` / `.cpu` | yes | Hard ceiling passed to the `ResourceLimiter` (see [Tiered isolation](../architecture/tiered-isolation.md)) — enforced today via portable JVM flags (`-Xmx`, `ActiveProcessorCount`). |
| `isolation.tier` | yes | `TIER_1` (shared worker) or `TIER_2` (dedicated worker). `TIER_3` is parsed but rejected at scheduling time — see [Tiered isolation](../architecture/tiered-isolation.md). |
| `requires` | no | List of `{module, version}` entries — other modules' interfaces this one depends on, with a version range the resolver checks before construction. Neither example module in this repo uses it (both are self-contained), but the parser supports it. |
| `exports` | no | List of `{service, version, allowedTenants}` — services this module publishes to the registry. `allowedTenants` optionally restricts which tenants may look the service up. |
| `lifecycle.hooks` | no | Fully-qualified class name implementing `ModuleLifecycleHooks` (`onInstall`/`onStart`/`onStop`/`onUninstall`). Omit for a module with no lifecycle behavior — `hello-module` does exactly this. |
| `lifecycle.jobHooks` | no | Fully-qualified class name implementing `JobHooks` (a single `run(ModuleContext): CompletionStatus` method) — sibling field to `lifecycle.hooks`, declared instead of it for a module deployed as a [`kind: Job`](#job-manifest), never alongside it. The worker runs it to completion on its own virtual thread once the module reaches `ACTIVE`, then reports `SUCCEEDED`/`FAILED` back to the control plane. A `kind: Job` module has no liveness/readiness semantics worth enforcing (it's never "ready to serve"), so `health:` is simply omitted alongside this field. |
| `health.liveness` / `health.readiness` | no | Fully-qualified class names implementing `LivenessProbe`/`ReadinessProbe`. Omit either (or both) and the worker defaults to no health check for that probe kind — again, `hello-module`'s deliberately minimal shape. |
| `health.initialDelaySeconds` | no | How long after the module reaches `ACTIVE` before its first probe tick fires, independent of the probe's own tick interval. Omit it and the first tick fires one interval after `ACTIVE`, same as every interval after it — useful for a module whose post-start warmup (lazy init, cache fill, JIT) would otherwise fail an eager first probe and get torn down within seconds. `0` is accepted and means "probe immediately"; a negative value is a manifest error. |
| `health.intervalSeconds` | no | How often this module's probes tick once ACTIVE. Defaults to the worker's own `1` second, shared by every module it hosts. Must be positive — `0` would tick without pause, so it is rejected rather than normalized. |
| `health.timeoutSeconds` | no | How long a single probe check may run before the worker counts it as failed. Defaults to the worker's own `2` seconds. Raise it for a module whose readiness check legitimately takes longer (a cold cache fill, a slow downstream dependency), so a healthy-but-slow check isn't reported identically to a broken one. Must be positive. |
| `health.failureThreshold` | no | How many *consecutive* liveness failures the worker tolerates before restarting this module. Defaults to the worker's own `3`. Must be at least `1` (restart on the first failure). |
| `volume.sizeBytes` | no | Declares this module needs a persistent local-disk volume — a property of the artifact itself, like `resources:`/`isolation:` above, not of the workload manifest. Only meaningful for a module deployed as a [`kind: StatefulSet`](#statefulset-manifest); see that section for the full contract, including what this deliberately does *not* provide. The module reads its allocated directory back at runtime via `ModuleContext.dataDirectory()`. The singular `volume:` is shorthand for one volume named `data` under `volumes:` below — the same parsed shape, not a second schema. |
| `volume.reclaimPolicy` | no | What a genuinely *permanent* removal (a real scale-down, or the whole spec deleted — never an ordinary reschedule or rolling-update replacement) does with the data already on disk: `Retain` (the default) leaves the directory in place for an operator to inspect or destroy explicitly, `Delete` opts into immediate recursive removal for genuinely disposable data (a cache, a scratch spool). |
| `volumes.<name>.sizeBytes` / `.reclaimPolicy` | no | The multi-volume form: a mapping of volume name to that volume's own request, each allocated its own directory (`<dataRoot>/volumes/<set>/<index>/<name>`) and read back via `ModuleContext.dataDirectory(name)`. The no-arg `dataDirectory()` answers only when exactly one volume is declared — with several, a hook must name which one it means. Declare `volume:` or `volumes:`, never both. |

## What's optional vs. required, concretely

`gimle-examples/hello-module`'s own manifest omits `requires`, `exports`, `lifecycle`, and `health`
entirely — proof those four are genuinely optional, not just under-documented. `name`, `version`,
`resources`, and `isolation` are the only fields every real manifest in this repo actually sets.

See [Writing a module manifest](../tutorials/writing-a-module-manifest.md) for a guided walkthrough
building one of these up from scratch.

## Workload manifests: `kind:`

`gimle-module.yaml` above describes a module *artifact*; a separate, second file — the workload
manifest (`deployment.yaml`/`job.yaml`/`cronjob.yaml`/`daemonset.yaml`/`statefulset.yaml`, submitted
via `gimle apply -f <file>` or the console's own create form) — describes how the control plane
should *run* it. Every workload manifest carries a required top-level `kind:` field naming which one
it is: `Deployment` (long-running, replicated), `Job` (run-to-completion, retried up to a limit),
`CronJob` (a scheduled generator of Jobs), `DaemonSet` (one instance per eligible node), or
`StatefulSet` (stable per-index identity, optionally with persistent storage — see below for each).
There is no default: a manifest missing `kind:` is rejected outright by `ManifestParser`, not
silently assumed to be a `Deployment`. `gimle apply -f` reads this field client-side to route to the
right resource automatically — there's no separate `gimle job apply`/`gimle deployment apply`/`gimle
cronjob apply`/`gimle daemonset apply`/`gimle statefulset apply` verb to remember.

One more `kind:` value, `ArtifactSet`, is also routed through `gimle apply -f`'s same client-side
`kind:` dispatch, but it is not a sixth workload kind — it never reaches `ManifestParser` or
scheduling at all, only Andvari's artifact registry. See [ArtifactSet manifest](#artifactset-manifest)
below.

Two further shapes ride the same dispatch without being workload kinds either: `kind:
KindDefinition` (teaching the cluster a new custom kind — see
[KindDefinition manifest](#kinddefinition-manifest) below) and any *dotted* kind name
(`kind: custom.Greeting`) — the fallthrough for an instance of a cluster-defined custom kind, sent
up verbatim for the server to validate against that kind's own stored schema. See
[Custom resource manifests](#custom-resource-manifests) below and the
[custom kinds architecture page](../architecture/custom-kinds.md).

## Manifest versioning: `apiVersion`

Every `gimle apply -f` kind — the five workload kinds plus `ArtifactSet` — also accepts an
**optional** top-level `apiVersion:` field, the Kubernetes-style mechanism for evolving a kind's
schema without breaking existing manifests:

```yaml
apiVersion: v1          # optional; omitted ⇒ v1alpha1, the kind's alpha
kind: Deployment
```

- **Omitted** `apiVersion` means `v1alpha1` — **permanently**. This is a stable contract, not a
  "latest" pointer: an unversioned manifest always keeps the alpha behavior, and opting into `v1`
  (or any future version) always requires declaring it explicitly, so an unversioned manifest can
  never silently change meaning under its author.
- **Declared**, it must exactly match a version the kind supports (matching is case-sensitive);
  anything else — an unknown version, a blank value, a non-string — is rejected outright with an
  error naming the supported set, never silently defaulted.
- `kind:` is always read first; `apiVersion` selects a parse ruleset *within* a kind, never the
  kind itself.

The versions defined today:

| Version | Meaning |
|---|---|
| `v1alpha1` (default when omitted) | Exactly the historical schema of every kind. `artifactPath` is accepted on the five workload kinds but **deprecated**: using it produces a warning (returned to `gimle apply` and printed on stderr) because the path is resolved against the *reading process's own working directory* — the control plane's, or a node agent's — not the manifest file's, which silently breaks the moment a cluster isn't launched from where the manifest author assumed. |
| `v1` | Identical to `v1alpha1` **minus `artifactPath`**: the key's very presence (even `artifactPath: ""`, and `jobTemplate.artifactPath` for CronJob) is rejected with an error pointing at the migration. `module: {name, version}` always resolves from the Andvari artifact registry — push the jar first (`gimle artifact push`, or `kind: ArtifactSet` for a set). For `ArtifactSet` itself, `v1` is a straight promotion with an unchanged schema: its `artifact:` entries are local build outputs being pushed *into* the registry, resolved against the manifest file's own directory, so there is nothing to deprecate. |

Generated manifests always pin their version explicitly — `mvn gimle:artifactset-push` writes
`apiVersion: v1` into its generated `artifactset.yaml` — so regenerated output can never change
meaning if a default ever moved.

## Vessel workloads: `vessel`

`vessel:` is an additive, optional block on every one of the five workload kinds above (`Deployment`,
`Job`, `CronJob`'s `jobTemplate`, `DaemonSet`, `StatefulSet`) — never a sixth workload kind of its
own. Its presence, not a separate flag, is what switches a spec from module hosting to **vessel
hosting**: instead of loading `module: {name, version}`/`artifactPath` as a Java module into a shared
or dedicated worker JVM, the node agent runs it directly as its own OS process — `java <jvmFlags> -jar
<the artifact> <args>`, the jar's own unmodified launcher. No `gimle-module.yaml` is required inside
that jar; a vessel is any runnable jar. `module`/`artifactPath` are read exactly as they are for a
module-hosted spec — a vessel's jar coordinate is identified and resolved (local path, or blank to
pull from Andvari) the same way, nothing new needed there.

A single-jar vessel coordinate resolves to that one jar, launched as `java -jar <the artifact>` —
correct for a genuinely self-contained launcher (a Spring Boot fat jar, for example). A multi-file
launcher layout like Quarkus's default fast-jar output (`quarkus-run.jar` plus sibling
`lib/`/`app/`/`quarkus/` directories its own manifest `Class-Path` depends on) is published as a
**bundle** instead: a `kind: bundle` entry in an [`ArtifactSet` manifest](#artifactset-manifest)
zips the whole build-output directory together with a `gimle-entrypoint.yaml` launch descriptor at
the archive root, and Andvari stores it as a single `BUNDLE`-kind artifact. A node agent resolving
that coordinate unpacks the zip into its pull-through cache and launches the entrypoint's own
`command` in the unpacked directory (its `workdir`), so sibling files resolve exactly as they do in
the original build output. A bundle coordinate is vessel-only — a workload manifest naming one
without a `vessel:` block is rejected at submission — and `vessel.jvmFlags` is rejected against it
too, since the entrypoint's own command decides how (and whether) a JVM is launched; the same
`resources:`-derived JVM flags every vessel gets are spliced in automatically when the entrypoint's
command starts with `java`.

A vessel is always dedicated-process hosting, the same isolation guarantee `isolation.tier: TIER_2`
gives a module — there is no `isolation:` field on a vessel block, since there is no weaker option to
choose between and no `gimle-module.yaml` to read one from anyway.

```yaml
kind: Deployment
name: billing-api
module:
  name: com.acme.billing-api
  version: 2.3.1
artifactPath: /var/gimle/artifacts/billing-api-2.3.1.jar
replicas: 3
vessel:
  args: ["--spring.profiles.active=prod"]
  jvmFlags: ["-XX:+UseZGC"]
  env:
    DB_PASSWORD: {secret: db.password}    # resolved from Fafnir at spawn time, tenant-scoped
    HTTP_PORT: {port: dynamic}            # the agent allocates a free port and exports it
    FIXED_PORT: {port: 9000}              # or use exactly this port, no allocation
    DATA_DIR: {volume: {sizeBytes: 1073741824}}  # a persistent volume; its host path is exported
    LOG_LEVEL: INFO                        # a plain literal works too
  files:
    - {path: conf/application.yaml, config: billing.app-config}
    - {path: conf/db.pass, secret: db.password}   # secret-backed, written owner-only
  probes:
    liveness:  {http: /actuator/health/liveness, port: HTTP_PORT, initialDelaySeconds: 20}
    readiness: {tcp: true, port: FIXED_PORT}
  resources:
    request: {memory: 512Mi, cpu: 250m}
    limit:   {memory: 1Gi,   cpu: 1000m}
```

| Field | Required | Meaning |
|---|---|---|
| `args` | no | Extra program arguments appended after the jar invocation. Defaults to none. |
| `jvmFlags` | no | Extra JVM flags, on top of the same `ResourceLimiter`-derived `-Xmx`/`ActiveProcessorCount`-equivalent flags every dedicated-process worker already gets. Defaults to none. |
| `env.<NAME>` | no | One of four shapes: a plain string literal; `{secret: "<tenant-scoped-key>"}`, resolved via the same Fafnir-fetch-by-the-agent's-own-mTLS-identity path module secret delivery already uses; `{port: dynamic}` / `{port: <fixed-integer>}`, which the agent allocates (or simply uses, for a fixed value) and exports as this variable's value; or `{volume: {sizeBytes: N[, reclaimPolicy: Retain\|Delete]}}`, a persistent local-disk volume allocated by the agent (keyed by the instance's placement identity plus this variable's name, so it survives restarts and rolling updates exactly like a module volume) whose resolved host path is exported as this variable — the vessel analogue of a module's own `volumes:`, riding the env map the way ports already do since an opaque process can only learn a path through its environment. |
| `files` | no | A list of `{path, config}` or `{path, secret}` entries — renders that key's tenant-scoped value, verbatim (no templating), to `path` before the process starts. `path` is relative to the instance's own per-instance data root, or absolute. A single-jar vessel launches with that per-instance root as its working directory, so the process resolves a relative `path` exactly as declared; a bundle vessel keeps its own entrypoint `workdir` as the working directory (and the unpacked bundle is a shared cache nothing may write into), so it finds the rendered files via the `GIMLE_INSTANCE_ROOT` environment variable, exported to every vessel process with the per-instance root's absolute path. A `secret:`-backed entry (the Kubernetes secret-volume-mount analogue) is fetched from Fafnir over the agent's own mTLS node identity and written with owner-only file permissions, via the portable `File.setReadable`/`setWritable` calls. |
| `probes.liveness` / `probes.readiness` | no | Each one of: absent (process-alive only, the always-available floor rung); `{tcp: true}`; or `{http: "<path>", initialDelaySeconds: N}`. A `tcp`/`http` rung requires at least one `env` entry declaring `{port: ...}` — rejected at parse time otherwise. `port: "<NAME>"` names which declared `{port: ...}` env entry that rung dials, by its env-var name — required once more than one is declared (rejected at parse time as ambiguous otherwise), optional when exactly one exists. `initialDelaySeconds` (optional, default `0`) delays that rung's first check, the same role `health.initialDelaySeconds` plays for a module's own probes. |
| `resources.request` / `.limit` | yes | The one genuine schema difference from module hosting: read directly off this manifest, not off a `ModuleDescriptor` pulled from the jar — there is no descriptor to read them from. Same `{memory, cpu}` shape as a `gimle-module.yaml`'s own `resources:` block. |

Deliberately a black-box process, not a Java module: no service fabric (nothing to publish/consume
by interface — a vessel has no `ModuleLayer`), no Tier 1 density (every vessel instance is always its
own process). Everything else — scheduling, tenant quotas, rolling updates, self-healing, per-instance
logs, the Andvari-coordinate artifact flow — operates on the same `InstanceAssignment`/reconciler
machinery a module-hosted spec does; none of that layer needs to know a spec is vessel-hosted.

A vessel instance's declared ports (`{port: ...}` entries) travel back through the node agent's
heartbeat and are queryable via `GET /endpoints/{deployment}` — see [Control plane § API server and
store client](../architecture/control-plane.md#api-server-and-store-client) for the response shape.

## Deployment manifest: `autoscale`

The deployment manifest (`deployment.yaml`, e.g. `gimle apply -f deployment.yaml`, `kind:
Deployment`) is a different file from `gimle-module.yaml` above — see `gimle-examples/*/deployment.yaml`
for real, minimal examples (`name`, `module: {name, version}`, `artifactPath`, `replicas`, plus the
required `kind: Deployment`). `artifactPath` is optional in every workload kind — and **deprecated**
(see [Manifest versioning](#manifest-versioning-apiversion): a `v1` manifest rejects it outright,
and using it under the default `v1alpha1` produces a warning): present, it names a local jar read
directly by whichever process needs it, resolved against that process's own working directory;
omitted entirely, `module: {name, version}` alone identifies the artifact and node agents pull it
from the Andvari artifact registry on a cache miss (an explicitly blank value is rejected rather
than treated as the registry form under `v1alpha1`; under `v1` presence alone rejects).
Once resolved (Deployment, Job, StatefulSet, DaemonSet, and rollbacks of each), the control plane
compares the artifact's own bundled `gimle-module.yaml` identity against this manifest's declared
`module: {name, version}` and rejects submission outright on a mismatch — a manifest bumped without
rebuilding the jar (or a jar pushed to Andvari under the wrong coordinate) fails at `PUT` time with a
400, not only later when a worker's install attempt nacks it. An artifact that can't be resolved yet
is unaffected by this check. `tenantId` (optional on every workload kind, this one included) resolves
to the reserved `default` tenant when omitted, exactly like an unset namespace on a Kubernetes pod —
see [Multi-tenancy and quotas](../architecture/multi-tenancy.md) for the full contract. Its one field
with enough shape to be worth a reference table here is
`autoscale`, grounded directly in `DeploymentManifestParser.parseAutoscale`; omit the whole
`autoscale:` block for a deployment with a fixed `replicas` count (the common case) and none of this
applies.

```yaml
autoscale:
  minReplicas: 1
  maxReplicas: 5
  targetCpuUtilizationPercent: 50
  targetRequestRatePerSecond: 20.0
  targetErrorRatePercent: 5.0
  targetQueueDepth: 10
  mode: weighted            # optional -- "worst-signal" (default) or "weighted"
  cpuWeight: 1.0             # optional, only meaningful when mode: weighted
  requestRateWeight: 3.0
  errorRateWeight: 2.0
  queueDepthWeight: 1.5
  scaleUpCooldownSeconds: 0     # optional -- defaults to 0 (react to a spike immediately)
  scaleDownCooldownSeconds: 900 # optional -- defaults to 300 (5 minutes)
```

| Field | Required | Meaning |
|---|---|---|
| `minReplicas` / `maxReplicas` | yes | The effective replica count `AutoscaleReconciler` computes is always clamped to this range. |
| `targetCpuUtilizationPercent` | yes | CPU signal, always evaluated: average observed CPU (`cpuMillicoresUsed` ÷ the module's own `resources.request.cpu`) against this target. |
| `targetRequestRatePerSecond` | no | Per-instance requests/sec target. Omit and this signal is never evaluated — an existing CPU-only policy scales exactly as before. |
| `targetErrorRatePercent` | no | Target error rate as a percentage of that instance's own request volume (errors/sec ÷ requests/sec × 100), not a raw errors/sec count. |
| `targetQueueDepth` | no | Per-instance queue depth target. |
| `mode` | no | `worst-signal` (default, omit to get this) or `weighted` — see below. |
| `cpuWeight` / `requestRateWeight` / `errorRateWeight` / `queueDepthWeight` | no | Only consulted when `mode: weighted`; each defaults to `1.0` when its own signal is configured but its weight is not. Must be positive if present, same as the target fields. |
| `scaleUpCooldownSeconds` | no | Stabilization window before another scale-*up* may happen. Defaults to `0` — a genuine load spike is answered on the next tick. `0` disables the window; a negative value is rejected. |
| `scaleDownCooldownSeconds` | no | Stabilization window before another scale-*down* may happen. Defaults to `300` (5 minutes), so shedding capacity waits for the load to stay down. Same `0`/negative rules as above. |

Each configured signal (CPU always, the other three only when present) proposes its own
observed/target ratio from the same averaged, ready-instance observations. `mode:` picks how those
ratios combine into one scaling decision — see [Control plane §
Reconcilers](../architecture/control-plane.md#reconcilers) for the full mechanics of both:

- `worst-signal` (the default — omitting `mode:` entirely gets this, matching every manifest
  written before `weighted` existed) — each signal independently proposes an ideal replica count,
  and the highest one wins.
- `weighted` — every configured signal's ratio is weighted and averaged into one blended ratio
  first, then converted to a replica count exactly once.

Both cooldowns are measured against the deployment's own last recorded scale event, which the
control plane persists in the state store alongside the effective replica count — so a control-plane
restart or a failover onto another replica does not reopen a window that has not elapsed. A
deployment that has never been scaled has no window to wait out, and clamping an out-of-range stored
replica count back into `[minReplicas, maxReplicas]` (typically right after an operator edits those
bounds) is a correction rather than a scaling decision and is never suppressed. The `GET
/deployments/{name}` response carries the stamp as `lastScaleTime` once one exists.

Flat keys (`cpuWeight`, not a nested per-signal block) were chosen deliberately to keep this
schema's diff against the pre-weighting shape minimal and stay consistent with the flat style the
other five fields already use, at the cost of a slightly less namespaced key set — a nested
`cpu: {target: 50, weight: 1.0}`-style block was considered and rejected for that reason.

## Deployment manifest: `disruption`

`disruption` caps how many indices a rolling update may replace concurrently, grounded directly in
`DeploymentManifestParser`'s own parsing of it and `DeploymentReconciler`'s use of the resulting
budget. Omit the whole `disruption:` block and a rollout migrates exactly one index at a time — the
same behavior every deployment had before this field existed.

```yaml
disruption:
  maxUnavailable: 2   # optional -- defaults to 1 if the block is present but this key is omitted
  maxSurge: 1          # optional -- defaults to 0 (no surge, the original behavior)
```

| Field | Required | Meaning |
|---|---|---|
| `maxUnavailable` | no | How many indices may be mid-migration (old instance already removed, replacement not yet ready) at once. Must be at least `0` if present; defaults to `1`. |
| `maxSurge` | no | How many *extra* instances (beyond `replicas`) a rollout may provision ahead of removing the originals they're replacing. Must be at least `0` if present; defaults to `0`. |

A freed `maxUnavailable` slot is topped up with a new migration the moment budget allows — including
in the very same reconcile tick a prior migration clears, not the next one — so `maxUnavailable: N`
keeps up to `N` migrations continuously in flight rather than draining a whole batch of `N` before
starting the next. `maxSurge` is an independent budget, not summed with `maxUnavailable`: both apply
simultaneously, each as its own pass over the same mismatched-index list, so `maxUnavailable: 1,
maxSurge: 1` migrates one index the old way and provisions a second one ahead of removal, at once.
`maxUnavailable` and `maxSurge` may not both be `0` (that combination would mean "never replace
anything," a stuck rollout) — but either one alone at `0` is fine: `maxUnavailable: 0, maxSurge: N`
is a pure-surge rollout, never removing an index before its replacement lands, matching literal
Kubernetes `RollingUpdateDeployment` semantics. See [Control plane §
Reconcilers](../architecture/control-plane.md#reconcilers) for the full mechanics of both budgets,
including how a surge instance is placed at a synthetic index `>= replicas` and promoted once
healthy.

Admission is surge-aware too: a tenant's quota is checked against `replicas + maxSurge` (the peak a
rollout could transiently reach), not `replicas` alone — a deployment that fits its tenant's quota at
steady state but would exceed it while surging is rejected at submission time, before any surge
instance is ever placed.

## Deployment manifest: `configMapRefs`

`configMapRefs` names the ConfigMaps (see `gimle configmap` in the
[CLI reference](./cli-reference.md)) this deployment's instances should receive in place of their
tenant's entire flat config set — a deployment states exactly which config it depends on, instead of
every instance getting pushed every config/secret value the tenant owns. Omit the field entirely
(the default, and every deployment submitted before it existed) and nothing changes: instances still
receive the whole tenant's flat config.

```yaml
configMapRefs:
  - app-config
  - feature-flags
```

Each key is delivered flattened into the same `ctx.config(key)` lookup a flat config entry already
uses — a module never has to know whether a given key came from a ConfigMap or the tenant's flat
config. Because of that, admission rejects the submission outright, rather than picking a silent
winner, if:

- a named ConfigMap doesn't exist for this deployment's tenant,
- two referenced ConfigMaps declare the same key, or
- a referenced key collides with one of the tenant's own flat config keys.

Write ConfigMap content with `gimle configmap set <tenantId> <name> --from-literal key=value` before
referencing it — referencing one that doesn't exist yet fails the deployment at submission, not
silently at instance start. `configMapRefs` is scoped to Deployment only today; Job/DaemonSet/
StatefulSet manifests don't accept it yet.

## Deployment manifest: `secretMapRefs`

`secretMapRefs` is the identical narrowing for Fafnir-managed secrets: it names the SecretMaps
(see `gimle secretmap` in the [CLI reference](./cli-reference.md)) this deployment's instances
should receive in place of their tenant's entire secret set. Omit the field entirely (the default)
and nothing changes: instances still receive every secret the tenant owns.

```yaml
secretMapRefs:
  - db-creds
```

Unlike a ConfigMap, a SecretMap has no single object-level version — each key it groups keeps its
own independent version ledger, the same versioned `key@N` history a flat `gimle secret` entry
already has. Grouping is purely a naming convention (`secretmap:{name}:{key}` as the underlying
Fafnir key) with its own reserved key prefix: a flat `gimle secret set`/`delete` against a
SecretMap-owned key is rejected outright.

Each key is delivered flattened into the same `ctx.config(key)` lookup a flat secret already uses
— a module never has to know whether a given key came from a SecretMap or the tenant's flat secret
set. Admission rejects the submission outright, rather than picking a silent winner, if:

- a named SecretMap doesn't exist for this deployment's tenant,
- two referenced SecretMaps declare the same key, or
- a referenced key collides with a `configMapRefs` key or one of the tenant's own flat config or
  secret keys.

Write SecretMap content with `gimle secretmap set <tenantId> <name> --from-literal key=value`
before referencing it — referencing one that doesn't exist yet fails the deployment at submission,
not silently at instance start. `secretMapRefs` is scoped to Deployment only today, the same limit
`configMapRefs` has.

## Job manifest

`kind: Job` is a genuinely different workload shape from a Deployment: one logical unit of work,
run to completion exactly once and retried up to `backoffLimit` times on failure, not a fixed-size
pool of long-running replicas. There is deliberately no
`replicas`/`autoscale` here — a Job is never scaled, only retried — and no `parallelism`/
`completions` either (Kubernetes Job's own multi-pod fan-out): a Job manifest here always describes
exactly one attempt at a time. The module it names must declare `lifecycle.jobHooks` (see the
`gimle-module.yaml` fields table above), not `lifecycle.hooks` — a Job-kind module with no
`JobHooks`-implementing class never reaches a terminal phase on its own.

```yaml
kind: Job
name: nightly-cleanup
module:
  name: com.gimle.examples.cleanup
  version: 1.0.0
artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
backoffLimit: 3               # optional -- defaults to 6, matching Kubernetes Job's own default
activeDeadlineSeconds: 600     # optional -- omit for no wall-clock ceiling on total attempts
tenantId: acme                 # optional -- omit to resolve to the "default" tenant
placement:                     # optional, same shape as a deployment manifest's own placement:
  antiAffinity: false
  requiredLabels: [gpu]
```

| Field | Required | Meaning |
|---|---|---|
| `kind` | yes | Must be `Job`. |
| `apiVersion` | no | Optional manifest version -- omitted means `v1alpha1`; `v1` rejects `artifactPath` outright. See [Manifest versioning](#manifest-versioning-apiversion). |
| `name` | yes | The job's identifier — also what `gimle get jobs <name>`/the console's Jobs screen key on. |
| `module.name` / `module.version` | yes | The module to run. |
| `artifactPath` | no | Path to the module's jar, same convention as a deployment manifest's own field -- omit it entirely to resolve `module: {name, version}` from the Andvari artifact registry instead. Deprecated: rejected outright under `apiVersion: v1`, and a warning under the default `v1alpha1` -- see [Manifest versioning](#manifest-versioning-apiversion). |
| `backoffLimit` | no | Maximum number of attempts before the job is marked permanently `FAILED`. Defaults to `6` (Kubernetes Job's own default) when omitted. |
| `activeDeadlineSeconds` | no | Wall-clock ceiling across *every* attempt combined, not per-attempt — once exceeded the job is marked `FAILED` regardless of remaining `backoffLimit` headroom. Omit for no deadline. |
| `tenantId` | no | Same meaning as a deployment manifest's own field — omit it and this job resolves to the reserved `default` tenant, not a distinct untenanted state; see [Multi-tenancy and quotas](../architecture/multi-tenancy.md). |
| `placement.antiAffinity` / `placement.requiredLabels` / `placement.priority` | no | Same `PlacementConstraints` shape a deployment manifest's own `placement:` block uses. `priority` is the PriorityClass analogue (integer, default `0`, higher wins) and is only consulted when the cluster is out of room — see [Priority and preemption](../architecture/control-plane.md#priority-and-preemption). |

A job's `phase` (`RUNNING`/`SUCCEEDED`/`FAILED`) and its current attempt's own placement/health are
read-only, computed state — never part of the manifest you submit, the same way a deployment's own
`instances[]` never is. `gimle get jobs <name>` (or the console's Jobs screen) is how you read them
back.

**What this does not provide, plainly stated**: no `parallelism`/`completions` multi-pod fan-out
(Kubernetes Job's own multi-pod fan-out) — a real, larger piece of scope this item deliberately
deferred, not an oversight. Scheduled, repeating firing of a Job is `kind: CronJob`, covered next.

## CronJob manifest

`kind: CronJob` is a thin, scheduled generator over `kind: Job` — never a second execution engine.
Each due firing materializes an ordinary `Job` named
`{cronJobName}-{epochSeconds}`; placement, retries, and completion from there on are entirely the
same `JobSpec`/`JobReconciler` mechanics described above, unchanged. `schedule` is a standard
5-field cron expression (`minute hour day-of-month month day-of-week`), evaluated in UTC — there is
no per-tenant or per-cluster timezone configuration anywhere in Gimlé. Supports `*`, a single
number, comma lists, ranges (`a-b`), and steps (`*/n`, `a-b/n`); named months/days (`JAN`, `MON`)
are not supported. Day-of-month and day-of-week combine with cron's own well-understood (if
surprising) historical quirk: if *both* fields are restricted (neither is a bare `*`), a moment
matches if it satisfies *either* field, not both.

```yaml
kind: CronJob
name: nightly-cleanup
schedule: "0 2 * * *"          # every day at 02:00 UTC
jobTemplate:
  module:
    name: com.gimle.examples.cleanup
    version: 1.0.0
  artifactPath: /var/gimle/artifacts/cleanup-1.0.0.jar
  backoffLimit: 3               # optional -- defaults to 6, same as a Job manifest's own field
  activeDeadlineSeconds: 600     # optional -- applies to each generated Job independently
  placement:                     # optional, same shape as a Job manifest's own placement:
    antiAffinity: false
    requiredLabels: [gpu]
startingDeadlineSeconds: 300    # optional -- omit for no missed-schedule cutoff
concurrencyPolicy: Forbid       # optional -- Allow (default), Forbid, or Replace
tenantId: acme                   # optional -- applied to every Job this CronJob generates;
                                  # omit to resolve to the "default" tenant
successfulJobsHistoryLimit: 3   # optional -- defaults to 3, matching Kubernetes CronJob
failedJobsHistoryLimit: 1       # optional -- defaults to 1, matching Kubernetes CronJob
suspend: false                   # optional -- defaults to false; true pauses the schedule
```

| Field | Required | Meaning |
|---|---|---|
| `kind` | yes | Must be `CronJob`. |
| `apiVersion` | no | Optional manifest version -- omitted means `v1alpha1`; `v1` rejects `artifactPath` outright. See [Manifest versioning](#manifest-versioning-apiversion). |
| `name` | yes | The cronjob's identifier — also the prefix every generated Job's name carries (`{name}-{epochSeconds}`). |
| `schedule` | yes | A standard 5-field cron expression, validated eagerly at submission — a malformed expression is rejected outright, not discovered on the first reconcile tick. |
| `jobTemplate.module.name` / `.version` | yes | The module each generated Job runs. |
| `jobTemplate.artifactPath` | no | Path to the module's jar, same convention as a Job manifest's own field -- omit it entirely to resolve the module coordinate from the Andvari artifact registry instead. Deprecated: rejected outright under `apiVersion: v1`, and a warning under the default `v1alpha1` -- see [Manifest versioning](#manifest-versioning-apiversion). |
| `jobTemplate.backoffLimit` | no | Per-generated-Job retry ceiling. Defaults to `6` when omitted, matching a directly-submitted Job. |
| `jobTemplate.activeDeadlineSeconds` | no | Per-generated-Job wall-clock ceiling across that Job's own attempts. Omit for no deadline. |
| `jobTemplate.placement.antiAffinity` / `.requiredLabels` | no | Same `PlacementConstraints` shape a Job/Deployment manifest's own `placement:` block uses. |
| `startingDeadlineSeconds` | no | How late a firing may still be honored (after its own due instant) before it's logged as missed instead — matches Kubernetes CronJob's own missed-schedule handling. Omit for no cutoff. |
| `concurrencyPolicy` | no | `Allow` (default), `Forbid` (skip this firing while the previous generated Job is still non-terminal), or `Replace` (remove the still-running Job first). Case-insensitive. |
| `tenantId` | no | Applied to every Job this CronJob generates — omit it and each generated Job resolves to the reserved `default` tenant, not a distinct untenanted state; see [Multi-tenancy and quotas](../architecture/multi-tenancy.md). |
| `successfulJobsHistoryLimit` | no | How many `SUCCEEDED` generated Jobs to keep, oldest-first pruned on every reconcile tick. Defaults to `3`, matching Kubernetes CronJob's own default. `0` keeps none. Independent of `concurrencyPolicy`, which only ever governs non-terminal jobs. |
| `failedJobsHistoryLimit` | no | Same as `successfulJobsHistoryLimit`, for `FAILED` generated Jobs. Defaults to `1`, matching Kubernetes CronJob's own default. |
| `suspend` | no | `true` pauses the schedule: no firing is materialized while it is set. Defaults to `false`. Matches Kubernetes CronJob's own field name and default. |

A cronjob's `lastScheduleTime` is read-only, computed state — never part of the manifest you submit.
`gimle get cronjobs <name>` (or the console's CronJobs screen) is how you read it back, alongside
every Job it has generated (visible on the Jobs screen, by the shared name prefix).

**Pausing a schedule**: `suspend: true` stops a CronJob firing without deleting it — the CronJob
stays listed, keeps every Job it has already generated (history pruning still runs), and keeps its
own `lastScheduleTime` advancing past each instant that comes due while it is paused. That last part
is what makes unsuspending resume at the *next* due instant rather than back-firing every schedule
missed during the pause. Apply the same manifest with `suspend: false` (or the key removed) to
resume. Without this field the only way to stop a misbehaving or temporarily unwanted schedule is to
delete and recreate the CronJob, which loses that firing history.

**Manual firing, independent of the schedule**: `gimle cronjob trigger <name>` fires immediately,
still subject to `concurrencyPolicy`, without touching `lastScheduleTime` or otherwise affecting the
next scheduled firing — the same relationship `kubectl create job --from=cronjob/x` has to its own
CronJob controller. It fires a suspended CronJob too: `suspend` pauses the schedule, and an operator
asking for one run right now is not that schedule.

**What this does not provide, plainly stated**: no per-cluster/per-tenant timezone configuration
(UTC only), no `parallelism`/`completions` on the generated Job (inherited from `kind: Job`'s own
scope).

## DaemonSet manifest

`kind: DaemonSet` places exactly one instance on every node currently eligible for it, not an
operator-chosen count — there is deliberately no `replicas`/`autoscale`
field here, and none is coming: a DaemonSet's size is topology-derived (however many nodes match),
recomputed on every reconcile tick as nodes join, leave, or are cordoned. `Scheduler.eligibleNodes`
(the same five-step tier/cordon/anti-affinity/tenant/label filter chain `place` uses for a
Deployment or Job replica, minus its final bin-packing pick) decides eligibility; every survivor
gets an assignment, not just one.

`placement.antiAffinity` is rejected outright if present — `DaemonSetManifestParser` throws
`GimleManifestException` rather than silently ignoring it — since "at most one replica per node" is
already this workload's entire placement model; a manifest that sets it is almost certainly a
copy-pasted Deployment/Job manifest, not a deliberate choice. `placement.requiredLabels` is the
field that actually matters here: for a Deployment or Job it's a minor placement tiebreak, but for a
DaemonSet it's the *primary* way an operator scopes which nodes run the workload at all (e.g. a
GPU-only telemetry agent) — the console's DaemonSets screen surfaces it as a first-class column for
exactly this reason, not buried in a details panel the way a Deployment's own placement fields
currently are (not yet surfaced in the console at all).

A node an operator has tainted (`gimle taint <nodeId> <tenantId>`, see the
[CLI reference](./cli-reference.md)) is excluded from this DaemonSet's placement the same way it
excludes a Deployment or StatefulSet replica, by default — a DaemonSet gets no automatic exemption.
Set `tolerateAllTaints: true` to opt a genuinely cluster-wide DaemonSet (a log shipper, a node
exporter — something that must reach every node regardless of which tenant it's reserved for) out
of the taint filter entirely; every other DaemonSet stays scoped to untainted nodes (plus any node
tainted specifically for its own `tenantId`) unless it opts in too. This is a deliberate,
per-DaemonSet choice rather than an unconditional default — unlike Kubernetes, where a DaemonSet's
own baseline tolerations only ever cover a handful of built-in system taints, every Gimlé taint is
an operator-declared tenant reservation, so bypassing one is never silent.

```yaml
kind: DaemonSet
name: node-exporter
module:
  name: com.gimle.examples.node-exporter
  version: 1.0.0
artifactPath: /var/gimle/artifacts/node-exporter-1.0.0.jar
placement:                     # optional -- omit entirely to run on every eligible node
  requiredLabels: [gpu]
tenantId: acme                 # optional -- omit to resolve to the "default" tenant
tolerateAllTaints: false       # optional, defaults to false -- see below
disruption:                    # optional -- see the Deployment manifest's own disruption section
  maxUnavailable: 2
```

| Field | Required | Meaning |
|---|---|---|
| `kind` | yes | Must be `DaemonSet`. |
| `apiVersion` | no | Optional manifest version -- omitted means `v1alpha1`; `v1` rejects `artifactPath` outright. See [Manifest versioning](#manifest-versioning-apiversion). |
| `name` | yes | The daemonset's identifier — also what `gimle get daemonsets <name>`/the console's DaemonSets screen key on. |
| `module.name` / `module.version` | yes | The module to run. |
| `artifactPath` | no | Path to the module's jar, same convention as a deployment manifest's own field -- omit it entirely to resolve `module: {name, version}` from the Andvari artifact registry instead. Deprecated: rejected outright under `apiVersion: v1`, and a warning under the default `v1alpha1` -- see [Manifest versioning](#manifest-versioning-apiversion). |
| `placement.requiredLabels` | no | Same label-matching semantics as a Deployment/Job manifest's own field — a node missing even one required label is excluded. Omit for "every eligible node." |
| `placement.priority` | no | Same PriorityClass-analogue meaning as a Deployment's own field. A DaemonSet instance is never itself a preemption victim — it exists precisely because its node does. |
| `placement.antiAffinity` | rejected if present | Not a valid field on this manifest kind — `DaemonSetManifestParser` throws if the YAML sets it, rather than silently ignoring it. |
| `tenantId` | no | Same meaning as a deployment manifest's own field — omit it and this daemonset resolves to the reserved `default` tenant, not a distinct untenanted state; see [Multi-tenancy and quotas](../architecture/multi-tenancy.md). |
| `tolerateAllTaints` | no | Defaults to `false`. Set `true` to skip the node-taint filter entirely for this DaemonSet, reaching every eligible node regardless of tenant reservation — see above. |
| `disruption.maxUnavailable` | no | Same meaning as the [Deployment manifest's own field](#deployment-manifest-disruption) — how many nodes may be mid-rollout at once. Defaults to `1`. |
| `disruption.maxSurge` | rejected if nonzero | Permanently meaningless here, even though it's now implemented for Deployment — one instance per node is already the strongest guarantee a surge could offer. `DaemonSetManifestParser` rejects a nonzero value outright, the same posture it takes for `placement.antiAffinity`. |

A daemonset's `instances[]` (one entry per node currently running it, each carrying that node's own
health observation) is read-only, computed state — never part of the manifest you submit, the same
way a deployment's own `instances[]` never is. `gimle get daemonsets <name>` (or the console's
DaemonSets screen) is how you read it back — the CLI's default table output rolls it up into an
`instances` count and a `health` column the same way `gimle get deployments` does; pass `-o json`
for each instance's own `nodeId` and health observation individually.

**What this does not provide, plainly stated**: no kernel-level per-node resource enforcement beyond
whatever `ResourceLimiter` already provides for any other workload kind (see [Tiered
Isolation](../architecture/tiered-isolation.md)); no `maxSurge` (rejected outright, see above).

## StatefulSet manifest

`kind: StatefulSet`, the last workload-diversity item, is an index space `0..replicas-1` exactly
like a Deployment's, but with two properties neither Deployment nor DaemonSet
has: **ordered rollout** (`OrderedReady`, Kubernetes StatefulSet's own default, not an alternative
invented here — index `i+1` is never placed until index `i` reports ready, and scale-down removes the
highest index first, one at a time) and **sticky placement** — once an index is first placed on a
node, every later placement attempt for that same index (a rolling update, a reconciler restart, a
node that went dark and came back) is forced back onto that exact node or left unplaced, never moved
to a different one. `placement.antiAffinity` is a perfectly ordinary field here, unlike DaemonSet's
rejection of it — spreading replicas across distinct nodes is exactly as meaningful for a StatefulSet
as it is for a Deployment.

```yaml
kind: StatefulSet
name: orders-statefulset
module:
  name: com.gimle.examples.orders
  version: 1.0.0
artifactPath: /var/gimle/artifacts/orders-1.0.0.jar
replicas: 3
placement:                     # optional, same shape as a Deployment manifest's own placement:
  antiAffinity: true
  requiredLabels: [ssd]
tenantId: acme                 # optional -- omit to resolve to the "default" tenant
```

| Field | Required | Meaning |
|---|---|---|
| `kind` | yes | Must be `StatefulSet`. |
| `apiVersion` | no | Optional manifest version -- omitted means `v1alpha1`; `v1` rejects `artifactPath` outright. See [Manifest versioning](#manifest-versioning-apiversion). |
| `name` | yes | The statefulset's identifier — also what `gimle get statefulsets <name>`/the console's StatefulSets screen key on. |
| `module.name` / `module.version` | yes | The module to run. |
| `artifactPath` | no | Path to the module's jar, same convention as a deployment manifest's own field -- omit it entirely to resolve `module: {name, version}` from the Andvari artifact registry instead. Deprecated: rejected outright under `apiVersion: v1`, and a warning under the default `v1alpha1` -- see [Manifest versioning](#manifest-versioning-apiversion). |
| `replicas` | yes | The index space is `0..replicas-1`. Unlike Deployment, never autoscaler-managed. |
| `placement.antiAffinity` / `placement.requiredLabels` / `placement.priority` | no | Same `PlacementConstraints` shape a Deployment/Job manifest's own `placement:` block uses. |
| `tenantId` | no | Same meaning as a deployment manifest's own field — omit it and this statefulset resolves to the reserved `default` tenant, not a distinct untenanted state; see [Multi-tenancy and quotas](../architecture/multi-tenancy.md). |

Deliberately does **not** carry its own `volume:` field — persistent storage is declared once, on
the module's own `gimle-module.yaml` (see the [`volume.sizeBytes`/`.reclaimPolicy` fields
above](#fields)), the same place every other per-artifact property already lives. A StatefulSet
whose module declares no `volume:` still gets the ordering/identity guarantees above — "stateful" in
the identity sense, not the storage sense, is a legitimate, supported shape.

A statefulset's `instances[]` (one entry per currently-placed index, each explicitly carrying that
index's assigned `nodeId` — the sticky-placement contract made visible, not just implemented
silently) is read-only, computed state — never part of the manifest you submit. `gimle get
statefulsets <name>` (or the console's StatefulSets screen) is how you read it back — the CLI's
default table output rolls it up into a placed-vs-desired `replicas` count and a `health` column
the same way `gimle get deployments` does; pass `-o json` for each index's own sticky `nodeId`
individually.

Volumes have their own operator surface: `gimle volume list` (backed by `GET /volumes`, aggregated
across every node's agent) inventories each volume with its node, current on-disk usage, and
whether the store still attaches it — `attached: false` marks a retained orphan the default
`Retain` reclaim policy left behind. `gimle volume destroy <set> <index> --node <nodeId> [--tenant <id>]`
reclaims one explicitly; both the control plane (store attachment) and the owning agent (a live
supervised instance) independently refuse to destroy a volume that is still in use, and a
coordinate with nothing on disk is a `404` rather than a reported success. `--tenant` is part of
the volume's address, not a filter: omit it and the request names the untenanted volume at that
set and index, never a tenanted one — the two are separate directories on the node, and each is
only ever reachable by naming its own tenant. The console's
[Volumes screen](../architecture/web-console.md#screens) is the same surface with the same refusals,
carries the same tenant on a destroy, and reports which nodes went unanswered.
Each instance's heartbeat
also samples its volume's on-disk size on a coarse interval, surfaced as `volumeUsageBytes` in its
observation — a soft reading for operators, never an enforced ceiling.

**The load-bearing tradeoff to state plainly, not bury**: this is the single-node-local-disk version
of persistent storage. There is no replication, no backup, no CSI-style pluggable network storage — a
StatefulSet replica's data does not survive its node's permanent loss. If the node a sticky index is
bound to is gone for good, that index stays unplaced (never silently relocated, which would silently
orphan the volume) until an operator intervenes. Real durability (replicated volumes, backup/restore)
is a real, larger piece of scope that is deliberately not provided here, not something planned for
a later addition to `kind: StatefulSet` itself.

**What this does not provide, plainly stated**: no multi-volume support (one `volume:` per module
descriptor only); no CSI-style pluggable storage backends; no volume snapshotting or backup/restore;
no automatic relocation of a sticky index's data to a different node under any circumstance.

## ArtifactSet manifest

`kind: ArtifactSet` is not one of the five workload kinds above — it never reaches control-plane
admission or scheduling, and it carries no replicas, placement, or resource requests. It only talks
to Andvari, the artifact registry: it publishes several module jars in one `gimle apply -f` instead
of one `gimle artifact push <jar>` invocation per jar, and lets each jar be tagged with the tenant it
belongs to.

```yaml
kind: ArtifactSet
tenant:
  orders-platform:
    - orders-service/target/orders-service-1.0.0.jar
    - inventory-service/target/inventory-service-1.0.0.jar
    - orders-report-job/target/orders-report-job-1.0.0.jar
    - web-ui/target/web-ui-1.0.0.jar

    - artifact: billing-vessel/target/billing-vessel-1.0.0.jar
      kind: vessel
      name: com.acme.billing-vessel
      version: 1.0.0

    - artifact: orders-report-ui/target/quarkus-app
      kind: bundle
      name: com.acme.orders-report-ui
      version: 2.0.0
      command: [java, -jar, quarkus-run.jar]
      workdir: .
  billing:
    - billing-service/target/billing-service-1.0.0.jar
modules:
  - shared-lib/target/shared-lib-1.0.0.jar
```

| Field | Required | Meaning |
|---|---|---|
| `kind` | yes | Must be `ArtifactSet`. |
| `apiVersion` | no | Optional manifest version -- omitted means `v1alpha1`; `v1` rejects `artifactPath` outright. See [Manifest versioning](#manifest-versioning-apiversion). |
| `tenant` | no | A map of tenant ID → list of artifact entries. Every entry under one key is pushed tagged with that tenant. A set may name more than one tenant at once — a release train spanning several tenants, something no workload manifest's single `tenantId` field can express. |
| `modules` | no | A plain list of untenanted artifact entries — the exception, not the norm. At least one of `tenant`/`modules` must be non-empty. |

An entry is either a **bare string** — a path to an ordinary module jar, whose `moduleId`/`version`
are always read from the jar's own bundled `gimle-module.yaml` (exactly as a single `gimle artifact
push` already does, so the manifest can never assert a coordinate that disagrees with what's
actually inside the jar) — or a **mapping** declaring an explicit entry `kind:`, for the two shapes
that have no `gimle-module.yaml` to read a coordinate from:

| Mapping field | Required | Meaning |
|---|---|---|
| `artifact` | yes | Same path convention as the bare-string form. A jar file for `kind: vessel`; a build-output directory for `kind: bundle`. |
| `kind` | yes | `vessel` (a plain runnable jar, the `gimle artifact push --vessel` equivalent) or `bundle` (a whole multi-file application directory — see the [Vessel section](#vessel-workloads-vessel) above). Never defaulted. |
| `name` / `version` | yes | The explicit registry coordinate. |
| `command` | `bundle` only, required | The entrypoint argv — written into the produced archive as its `gimle-entrypoint.yaml`, always exec-form (a list, never a shell string). Rejected on a `vessel` entry. |
| `workdir` | `bundle` only, optional | Launch directory relative to the unpacked bundle root; defaults to the root itself. Rejected on a `vessel` entry. |

A `bundle` entry's directory is zipped by the CLI deterministically (sorted entries, fixed
timestamps), so re-applying an unchanged manifest reproduces the identical digest and lands as the
ordinary idempotent already-present outcome. Every path resolves relative to the manifest file's own
directory, the same convention a `docker-compose.yml`'s relative build contexts use. The same
artifact path listed under two different tenants, or under both a tenant and `modules`, is rejected
at parse time — ownership of one artifact is never ambiguous within one manifest — and so is one
coordinate claimed by two entries.

Publishing a set is not a database transaction: every coordinate is checked with a plain `HEAD`
first (a digest mismatch against what's already stored aborts the whole set before anything is
pushed), then each member is pushed in the manifest's own order through the same single-artifact
`PUT` path, which is already atomic and idempotent per coordinate. A failure partway through leaves
every already-pushed member valid — nothing to roll back — and re-applying the identical manifest
picks up from the failure point, since an already-pushed member simply comes back unchanged.

A jar pushed with no tenant can be claimed by a later push that supplies one (the coordinate is
otherwise still empty), but a tenant already recorded can never be swapped for a different one — that
re-push is refused the same way a differing digest is.

`gimle artifact push <jar> --tenant <id>` is the single-jar equivalent of naming one jar under one
`tenant:` key — an `ArtifactSet` manifest is a convenient way to say `--tenant` many times in one
file, not a divergent feature. A Maven reactor with several modules to publish together can bind
`gimle:artifactset-push` once at its aggregator root instead of hand-maintaining this file — see the
[Maven plugin goals reference](./maven-plugin-goals.md).

## KindDefinition manifest

Teaches the cluster a new custom kind — see the
[custom kinds architecture page](../architecture/custom-kinds.md) for the full mechanism. Applied
via `gimle apply -f`; stored durably; re-applying with a changed schema re-validates every stored
instance first (a breaking change is refused with a 409 naming the violators, a compatible one
backfills new defaults into stored specs).

```yaml
kind: KindDefinition
name: Greeting            # no dot prefix supplied -- normalized and stored as custom.Greeting
scope: Tenant             # Tenant | Cluster
description: "A greeting this cluster should keep saying"
names:                    # optional CLI/console nicknames
  plural: greetings
  shortNames: [gr]
schema:
  fields:
    - name: message
      type: string
      required: true
    - name: repeat
      type: int
      default: 1
      min: 1
      max: 100
    - name: tone
      type: enum
      values: [friendly, formal]
      default: friendly
printColumns:             # optional; dotted paths into spec/status for CLI + console tables
  - name: MESSAGE
    path: spec.message
  - name: SAID
    path: status.timesSaid
```

| Field | Required | Meaning |
|---|---|---|
| `kind` | yes | Always `KindDefinition`. |
| `name` | yes | The kind's name. Must carry a dotted prefix (`acme.AlertRule`); a bare name is normalized to `custom.<name>` with a warning — the prefix is what makes collision with a future built-in kind structurally impossible. |
| `scope` | yes | `Tenant` (instances require a `tenantId`) or `Cluster` (instances must not carry one). |
| `description` | no | Free text, shown by `gimle kinds` and the console's kind picker. |
| `names.plural` / `names.shortNames` | no | Extra nouns `gimle get`/`delete` resolve — `gimle get greetings`, `gimle get gr`. |
| `schema.fields` | yes | The instance spec schema — see below. |
| `printColumns` | no | Extra table columns for `gimle get` and the console, each a `name` plus a dotted `path` into the instance (`spec.message`, `status.timesSaid`); an unresolved path renders as an empty cell, never an error. |

Schema field types are `string` (`maxLength`), `int`/`double` (`min`/`max`, inclusive), `bool`,
`enum` (`values`, exact case-sensitive membership), `list` (`items` — any type including `object` —
plus `minItems`/`maxItems`), and `object` (nested `fields`, recursion depth capped at definition
admission). Every field takes `required` or `default` — never both. Unknown keys anywhere in the
manifest are rejected at parse time, and unknown keys in an *instance's* spec are rejected at apply
time — never silently pruned. There is deliberately no `pattern` attribute for strings: a
user-supplied regex evaluated at admission would be a ReDoS surface on the control plane; format
checks belong in the kind's operator.

## Custom resource manifests

An instance of any defined custom kind. The root level is reserved (`kind`, `apiVersion`, `name`,
`tenantId`, `spec`) so no user schema can ever collide with a future reserved field; all user data
lives under `spec:`, unlike the flat workload manifests.

```yaml
kind: custom.Greeting     # the stored, prefixed kind name -- instances always use it
name: hello-world
tenantId: team-a          # required for a Tenant-scoped kind, rejected for Cluster
spec:
  message: "hello"
  repeat: 3
```

Admission validates `spec` against the kind's schema, applies declared defaults, and persists the
**defaulted** tree — a stored spec is always complete. Re-applying an identical manifest is a
no-op (no generation bump); a changed spec bumps the store-assigned `generation`, which operators
echo back as `observedGeneration` in the status they report. The status sub-document is not
authored in this manifest at all: only an operator writes it, through its own separately-granted
RBAC surface.
