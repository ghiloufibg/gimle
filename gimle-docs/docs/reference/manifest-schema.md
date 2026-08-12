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
| `health.liveness` / `health.readiness` | no | Fully-qualified class names implementing `LivenessProbe`/`ReadinessProbe`. Omit either (or both) and the worker defaults to no health check for that probe kind — again, `hello-module`'s deliberately minimal shape. |
| `health.initialDelaySeconds` | no | How long after the module reaches `ACTIVE` before its first probe tick fires, independent of the probe's own tick interval. Omit it and the first tick fires one interval after `ACTIVE`, same as every interval after it — useful for a module whose post-start warmup (lazy init, cache fill, JIT) would otherwise fail an eager first probe and get torn down within seconds. |

## What's optional vs. required, concretely

`gimle-examples/hello-module`'s own manifest omits `requires`, `exports`, `lifecycle`, and `health`
entirely — proof those four are genuinely optional, not just under-documented. `name`, `version`,
`resources`, and `isolation` are the only fields every real manifest in this repo actually sets.

See [Writing a module manifest](../tutorials/writing-a-module-manifest.md) for a guided walkthrough
building one of these up from scratch.

## Deployment manifest: `autoscale`

This one field lives on a different file — the deployment manifest (`deployment.yaml`, e.g.
`gimle apply -f deployment.yaml`), not `gimle-module.yaml` above — but is documented here since it's
the one part of that file's schema with enough shape to be worth a reference table. Grounded
directly in `DeploymentManifestParser.parseAutoscale`; omit the whole `autoscale:` block for a
deployment with a fixed `replicas` count (the common case) and none of this applies.

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
