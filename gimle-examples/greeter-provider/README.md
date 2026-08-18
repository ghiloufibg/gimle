# greeter-provider

A real, deployable Gimlé module that publishes a fabric service — unlike `hello-module` (deliberately
minimal, no hooks or probes at all), this bundles genuine `ModuleLifecycleHooks` and
`LivenessProbe`/`ReadinessProbe` implementations inside its own jar, the real deploy shape rather than
the shortcut every dynamically-loaded test fixture elsewhere in this codebase takes (leaving its hooks
class outside the fixture jar, on the test's own classpath). Companion to `greeter-consumer`, which
looks this service up and calls it over the wire.

## Manifest

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

Tier 2 — a dedicated worker JVM, not a shared one. Paired with `greeter-consumer`'s own `TIER_2`
manifest, this guarantees the two modules never land in the same worker, so the consumer's service
lookup always exercises a genuine cross-worker fabric call rather than the same-worker `Class`-identity
shortcut.

## What it does

- **`GreeterProviderHooks.onStart`** registers a `Greeter` implementation
  (`name -> "Hello, " + name + "! (from provider)"`) on the fabric via `ctx.registerService(...)`, sets
  a shared `ready` flag, and reads back a config value (`ctx.config("some-secret-key")`), logging what
  it got — exercising the real config/secrets delivery path (agent fetches the tenant's secret from
  Fafnir and hands it down as config) end to end, not just at unit-test level.
- **`GreeterReadinessProbe`** reports ready only once that `ready` flag is set — the instance isn't
  ready for traffic until the service is actually registered.
- **`GreeterLivenessProbe`** always reports alive; this module has no failure mode of its own.
- **`Greeter`** (`com.gimle.examples.greeter.Greeter`) is a plain two-method-free interface
  (`String greet(String name)`). Each of `greeter-provider`/`greeter-consumer`/`greeter-load-generator`
  bundles its own literal, independently compiled copy rather than sharing a compile-time API jar —
  the fabric's service catalog resolves lookups by interface name and dispatches through a proxy built
  from the caller's own `Class` object, so structurally identical copies interoperate across the wire.
  That's a deliberate demonstration of the platform's actual value proposition.

`module-info.java` declares `com.gimle.module`/`org.slf4j` as `requires static` (the worker JVM's
platform layer is boot-only today, so a plain `requires` would fail resolution at deploy time —
`ModuleLayerFactory` separately grants this module's layer readability to the platform's classes at
runtime) and exports both `com.gimle.examples.greeter.provider` and `com.gimle.examples.greeter` — the
latter because `FabricServer` reflectively invokes `Greeter` methods on the registered instance from
outside this module.

## Deploying it

`deployment.yaml` is a ready-made `Deployment` manifest pointing at
`gimle-examples/greeter-provider/target/greeter-provider-0.1.0-alpha.1.jar`. Deploy alongside
`greeter-consumer` (and the provider's own secret written through `/secrets/*` first, if you want the
config round trip to have a value to fetch) to see the real cross-worker fabric call happen —
exercised end to end by `gimle-smoke-tests`' `GreeterClusterTopologyIT`.
