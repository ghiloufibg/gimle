---
sidebar_position: 3
---

# Writing a module manifest

Every module artifact bundles a `gimle-module.yaml` under `META-INF/gimle/`. This walks through
building one up from scratch, field by field — see [Manifest schema](../reference/manifest-schema.md)
for the full reference once you know your way around.

## Start minimal

The smallest real manifest in this repo, `hello-module`'s:

```yaml
name: com.gimle.examples.hello
version: 1.0.0
isolation:
  tier: TIER_1
resources:
  request:
    memory: 16Mi
    cpu: 10m
  limit:
    memory: 32Mi
    cpu: 50m
```

`name`, `version`, `isolation`, and `resources` are the only fields every manifest needs.
`isolation.tier: TIER_1` puts it in a shared worker JVM — appropriate for a trusted module with no
hard resource ceiling requirement. See [Tiered isolation](../architecture/tiered-isolation.md) for
when `TIER_2` is the right call instead.

## Add lifecycle hooks and health probes

A module that actually does something on startup, and can be health-checked, needs two more
sections — from the real `greeter-provider` manifest:

```yaml
lifecycle:
  hooks: com.gimle.examples.greeter.provider.GreeterProviderHooks
health:
  liveness: com.gimle.examples.greeter.provider.GreeterLivenessProbe
  readiness: com.gimle.examples.greeter.provider.GreeterReadinessProbe
```

Each is a fully-qualified class name, implementing `ModuleLifecycleHooks` and
`LivenessProbe`/`ReadinessProbe` respectively, bundled inside the module's own jar — the worker
calls them directly, no HTTP, no sidecar. `GreeterProviderHooks.onStart` is where this module
registers its exported service with the fabric (see the next section).

A module whose post-start warmup takes a moment (lazy init, a cache fill, JIT) can add
`health.initialDelaySeconds` alongside `liveness`/`readiness` to delay the *first* probe tick
without slowing down every tick after it — otherwise an eager first tick can fail and get the
module torn down within seconds of reaching `ACTIVE`.

The rest of the probe timing is declarable per module too, and defaults to the worker's own values
(1s interval, 2s timeout, 3 consecutive liveness failures before a restart) when omitted:

```yaml
health:
  liveness: com.gimle.examples.greeter.provider.GreeterLivenessProbe
  readiness: com.gimle.examples.greeter.provider.GreeterReadinessProbe
  intervalSeconds: 10
  timeoutSeconds: 30
  failureThreshold: 6
```

`timeoutSeconds` is the one to reach for when a readiness check honestly needs more than a couple of
seconds — a cold cache fill, a slow downstream dependency. Without it such a check fails every tick
indistinguishably from a genuinely broken probe. `intervalSeconds` and `timeoutSeconds` must be
positive and `failureThreshold` at least 1; anything else is rejected when the manifest is parsed,
not silently normalized.

## Export a service

For another module to call this one over the fabric, declare what it exports:

```yaml
exports:
  - service: com.gimle.examples.greeter.Greeter
    version: 1.0.0
```

The interface (`com.gimle.examples.greeter.Greeter`) has to actually exist on this module's
classpath — the lifecycle hook registers an instance of it under this name+version at `onStart`,
and a consuming module looks it up the same way. See
[Service fabric](../architecture/service-fabric.md) for how that lookup resolves to same-worker,
same-machine, or cross-machine, depending on where the consumer ends up scheduled.

## Depend on another module

If your module needs a specific version range of another module present, declare it under
`requires` (neither `greeter-provider` nor `greeter-consumer` actually uses this — they discover
each other purely through the service registry at runtime, not a build-time `requires`):

```yaml
requires:
  - module: com.gimle.examples.greeter.provider
    version: "[1.0.0,2.0.0)"
```

`version` uses Maven/OSGi interval notation, not npm-style ranges: `[1.0.0,2.0.0)` means
inclusive-lower/exclusive-upper, `[1.5.0,)` is unbounded above, and a bare `1.2.3` means an exact
version match (`VersionRange` in `gimle-core`).

The resolver checks this before the module's `ModuleLayer` is even constructed — see
[Module system](../architecture/module-system.md).

## Try it

Package your module (`mvn package` inside its own module directory) with the manifest under
`src/main/resources/META-INF/gimle/gimle-module.yaml`, write a `deployment.yaml` pointing
`artifactPath` at the built jar (see either example module's own `deployment.yaml`) -- or push
the jar with `gimle artifact push` and omit `artifactPath` entirely, letting nodes pull it from
the Andvari artifact registry by `module: {name, version}` -- and deploy it
per [Deploy your first module](./deploy-your-first-module.md).
