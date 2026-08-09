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
