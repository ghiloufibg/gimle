# hello-module

A minimal, real, deployable Gimlé module artifact — a genuine JPMS module (`module-info.class`, not
an automatic module) plus `META-INF/gimle/gimle-module.yaml` — not a piece of platform
infrastructure. It exists to give a deployment's `artifactPath` something real to point at: the
`gimle-module` test suite's own `TestModuleBuilder` compiles fixture modules in-memory and was never
meant to produce a checked-in, shippable jar, so this one fills that gap for manual verification, QA
passes, and the local-dev runbook.

It is **deliberately inert**: no lifecycle hooks, no health probes, no fabric service. Both are
optional per the manifest schema (`ModuleDescriptorParser` defaults to `HealthProbes.NONE` and no
hooks when the sections are absent), and nothing in the platform calls into this module's own code at
all — the jar just needs to install, resolve, and run.

## Manifest

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

Tier 1 (shared worker JVM, classloader-level isolation). `resources.request` and `resources.limit`
are deliberately given distinct values so the pair actually exercises both fields rather than one
number doing double duty.

## Source

`com.gimle.examples.hello.Hello` is a single final class with one static `greeting()` method. It
exists purely so the jar contains a real class file alongside `module-info.class` — nothing in Gimlé
ever calls it; with no `health`/`lifecycle` sections in the manifest, the platform never resolves a
class from this module at all. `module-info.java` declares no `requires` beyond the implicit
`java.base` and no `exports`.

## Deploying it

`deployment.yaml` at the module root is a ready-made `Deployment` manifest (`kind: Deployment`,
`artifactPath: gimle-examples/hello-module/target/hello-module-0.1.0-alpha.2.jar`, one replica) —
build the jar (`mvn -pl hello-module package`) and submit the manifest through the control plane's
API or `gimle-cli` to see a real install/resolve/start cycle end to end.

Not exercised by any module's own automated test suite — it's a fixture for manual/QA use, not a
subject of `gimle-smoke-tests` or `gimle-holmgang`.
