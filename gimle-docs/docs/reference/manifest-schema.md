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
| `health.initialDelaySeconds` | no | How long after the module reaches `ACTIVE` before its first probe tick fires, independent of the probe's own tick interval. Omit it and the first tick fires one interval after `ACTIVE`, same as every interval after it — useful for a module whose post-start warmup (lazy init, cache fill, JIT) would otherwise fail an eager first probe and get torn down within seconds. |

## What's optional vs. required, concretely

`gimle-examples/hello-module`'s own manifest omits `requires`, `exports`, `lifecycle`, and `health`
entirely — proof those four are genuinely optional, not just under-documented. `name`, `version`,
`resources`, and `isolation` are the only fields every real manifest in this repo actually sets.

See [Writing a module manifest](../tutorials/writing-a-module-manifest.md) for a guided walkthrough
building one of these up from scratch.

## Workload manifests: `kind:`

`gimle-module.yaml` above describes a module *artifact*; a separate, second file — the workload
manifest (`deployment.yaml`/`job.yaml`, submitted via `gimle apply -f <file>` or the console's own
create form) — describes how the control plane should *run* it. Every workload manifest carries a
required top-level `kind:` field naming which one it is: `Deployment` (long-running, replicated) or
`Job` (run-to-completion, retried up to a limit — see below). There is no default: a manifest
missing `kind:` is rejected outright by `ManifestParser`, not silently assumed to be a `Deployment`.
`gimle apply -f` reads this field client-side to route to the right resource automatically — there's
no separate `gimle job apply`/`gimle deployment apply` verb to remember.

## Deployment manifest: `autoscale`

The deployment manifest (`deployment.yaml`, e.g. `gimle apply -f deployment.yaml`, `kind:
Deployment`) is a different file from `gimle-module.yaml` above — see `gimle-examples/*/deployment.yaml`
for real, minimal examples (`name`, `module: {name, version}`, `artifactPath`, `replicas`, plus the
required `kind: Deployment`). Its one field with enough shape to be worth a reference table here is
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

Each configured signal (CPU always, the other three only when present) proposes its own
observed/target ratio from the same averaged, ready-instance observations. `mode:` picks how those
ratios combine into one scaling decision — see [Control plane §
Reconcilers](../architecture/control-plane.md#reconcilers) for the full mechanics of both:

- `worst-signal` (the default — omitting `mode:` entirely gets this, matching every manifest
  written before `weighted` existed) — each signal independently proposes an ideal replica count,
  and the highest one wins.
- `weighted` — every configured signal's ratio is weighted and averaged into one blended ratio
  first, then converted to a replica count exactly once.

Flat keys (`cpuWeight`, not a nested per-signal block) were chosen deliberately to keep this
schema's diff against the pre-weighting shape minimal and stay consistent with the flat style the
other five fields already use, at the cost of a slightly less namespaced key set — a nested
`cpu: {target: 50, weight: 1.0}`-style block was considered and rejected for that reason.

## Job manifest

`kind: Job` (priority-3 roadmap item 10) is a genuinely different workload shape from a Deployment:
one logical unit of work, run to completion exactly once and retried up to `backoffLimit` times on
failure, not a fixed-size pool of long-running replicas. There is deliberately no
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
tenantId: acme                 # optional -- omit for an untenanted job
placement:                     # optional, same shape as a deployment manifest's own placement:
  antiAffinity: false
  requiredLabels: [gpu]
```

| Field | Required | Meaning |
|---|---|---|
| `kind` | yes | Must be `Job`. |
| `name` | yes | The job's identifier — also what `gimle get jobs <name>`/the console's Jobs screen key on. |
| `module.name` / `module.version` | yes | The module to run. |
| `artifactPath` | yes | Path to the module's jar, same convention as a deployment manifest's own field. |
| `backoffLimit` | no | Maximum number of attempts before the job is marked permanently `FAILED`. Defaults to `6` (Kubernetes Job's own default) when omitted. |
| `activeDeadlineSeconds` | no | Wall-clock ceiling across *every* attempt combined, not per-attempt — once exceeded the job is marked `FAILED` regardless of remaining `backoffLimit` headroom. Omit for no deadline. |
| `tenantId` | no | Same meaning as a deployment manifest's own field — omit for an untenanted job. |
| `placement.antiAffinity` / `placement.requiredLabels` | no | Same `PlacementConstraints` shape a deployment manifest's own `placement:` block uses. |

A job's `phase` (`RUNNING`/`SUCCEEDED`/`FAILED`) and its current attempt's own placement/health are
read-only, computed state — never part of the manifest you submit, the same way a deployment's own
`instances[]` never is. `gimle get jobs <name>` (or the console's Jobs screen) is how you read them
back.

**What this does not provide, plainly stated**: no `parallelism`/`completions` multi-pod fan-out, no
CronJob-style scheduling yet (a Job runs once, submitted once) — both real, larger pieces of scope
this item deliberately deferred, not oversights.
