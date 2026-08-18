# Gimle Module

The module system itself: parses `gimle-module.yaml`, resolves a module's dependencies against
what's already installed, builds its `ModuleLayer`/classloader, drives it through its lifecycle
state machine, and detects classloader leaks after it's undeployed. `gimle-worker` hosts this
machinery inside a worker JVM process; `gimle-module` itself has no process entry point and no
knowledge of workers, agents, or the control plane — it only depends on `gimle-core`. Because
`gimle-api` doesn't exist as its own module, the platform-facing contracts a hosted module actually
implements — `LivenessProbe`, `ReadinessProbe`, `ModuleLifecycleHooks`, `ServiceRegistry` — live
here too, in `com.gimle.module.probe`/`com.gimle.module.lifecycle`, rather than in a separate API
module.

## Lifecycle

```
INSTALLED → RESOLVED → STARTING → ACTIVE → STOPPING → UNINSTALLED
                                      ↓
                                   FAILED
                                      ↓ (Job kind only)
                                  COMPLETED
```

`ModuleController` (`com.gimle.module.lifecycle`) drives every transition and invokes lifecycle
hooks at the right points: `onInstall` fires once the `ModuleLayer` is built (end of `resolve`),
`onStart`/`onStop` bracket `ACTIVE`, `onUninstall` fires just before disposal. Gating hooks
(`onInstall`, `onStart`) abort their transition and propagate synchronously on failure; teardown
hooks (`onStop`, `onUninstall`) are best-effort — a misbehaving one is recorded in a
`TransitionFailed` event but never blocks resource disposal. `FAILED` and `COMPLETED` are pragmatic
additions beyond the five OSGi-shaped states: `FAILED` is the terminal for a resolve or `onStart`
failure (no in-worker retry — only `gimle-controlplane`'s `HealthReconciler` can act on it, by
rescheduling to a different node), and `COMPLETED` is the run-to-completion counterpart for a
Job-kind module whose `JobHooks.run(...)` finished successfully.

## Key packages

- **`descriptor`** — `ModuleDescriptorParser` reads `META-INF/gimle/gimle-module.yaml` via SnakeYAML's
  `SafeConstructor` (plain maps/lists/scalars only — a module artifact's descriptor is untrusted
  input, so arbitrary-type-instantiation tags are deliberately unavailable) and validates it into a
  `ModuleDescriptor` (`gimle-core`).
- **`artifact`** — `ModuleArtifactReader` validates a module jar against its own descriptor;
  `ArtifactPullCache` is the local pull-through cache in front of the Andvari artifact registry — a
  `(moduleId, version)` coordinate resolves to a cached jar path, downloaded once, digest-verified
  against the registry's advertised SHA-256, and trusted by presence alone thereafter (sound because
  the registry's own store is immutable).
- **`resolve`** — `ModuleResolver` wires each `Requirement` to the highest installed version
  satisfying it among candidates already `RESOLVED`/`ACTIVE`; `ModuleRegistry` is the installed-artifact
  lookup it resolves against. Dependents are never re-wired when a newer dependency installs — only
  an explicit re-resolve rewires them — which is what makes version skew during hot redeploy a normal
  state, not a special case.
- **`layer`** — `ModuleLayerFactory` builds one `ModuleLayer`/classloader per `(name, version)`,
  parented on the platform layer and every wired dependency's own layer, via
  `Configuration.resolve`/`ModuleLayer.defineModulesWithOneLoader`. It also grants the new module
  readability to the platform's own unnamed module (`Module#addReads` via `ModuleLayer.Controller`) —
  necessary because `gimle-worker` and every `gimle-*` jar run unnamed (launched via `-cp`, not
  `--module-path`, since `gimle-api` doesn't exist to be a real platform module on the boot layer), a
  deliberate, documented stopgap rather than a parallel path to migrate away from later.
- **`lifecycle`** — `ModuleController`, `ModuleContext`/`SimpleModuleContext` (what a hook/probe
  receives — service registry access, config lookup, logging), `ModuleLifecycleHooks`,
  `ServiceRegistry`/`SimpleServiceRegistry` (the same-worker service registry backing
  `ModuleContext#registerService`/`lookupService`, shared across every module hosted in one worker;
  same-worker lookup returns a direct reference for zero-overhead virtual dispatch, no proxy).
- **`leak`** — `LeakTracker`: after a module is disposed, holds a `PhantomReference` to its
  classloader and reports a leak (with a retaining path resolved by `OldObjectSampleCorrelator` via
  JFR old-object sampling) if it survives a configurable window. A background virtual thread drains
  the reference queue continuously; a periodic sweep (`window / 2`) escalates entries still tracked
  past their window, triggering one `System.gc()` first to give a merely-pending collection one more
  chance.
- **`probe`** — `LivenessProbe`/`ReadinessProbe`: plain interfaces called directly by the worker's
  probe loop, no HTTP, no sidecar.

## Design notes

- **One `ModuleLayer` per instance.** Isolation is classloader-grade: a module reads exactly the
  platform's and its resolved dependencies' exported packages, nothing else. Split-package conflicts
  surface as JPMS's own `ResolutionException` rather than an independent pre-check, to stay aligned
  with the JDK's canonical algorithm.
- **Leak detection is first-class, not a diagnostic bolt-on.** `PhantomReference`-based tracking with
  a retaining-path report is the mechanism that makes hot redeploy safe to trust: a module that leaks
  anyway can be moved to Tier 2, where undeploy just kills a JVM.
- **A hosted module's own lifecycle-hooks/probe class must be `exports`ed or `opens`ed** in its
  `module-info.java` — `ModuleController` instantiates it reflectively from outside the module, and
  JPMS strong encapsulation blocks that against a concealed package regardless of the constructor's
  own visibility.

## Consumers

`gimle-worker` is the primary consumer — it hosts `ModuleController`/`LeakTracker` inside a worker
JVM process and supplies the real, richer `ServiceRegistry` implementation workers actually use.
`gimle-agent` and `gimle-controlplane` depend on it for `ModuleDescriptor`/`ArtifactPullCache`-level
concerns (resolving artifact coordinates, validating manifests at admission time). `gimle-fabric`,
`gimle-gateway`, and `gimle-cli` depend on it for the shared probe/service-registry contract types.
A test-jar (`TestModuleBuilder`, an in-process `javac`-based fixture for building a throwaway module
jar) is published for reuse by `gimle-worker`'s and `gimle-smoke-tests`' own tests.
