<p align="center">
  <img src="gimle-docs/static/img/logo.png" alt="Gimlé" width="120" />
</p>

<h1 align="center">Gimlé</h1>
<p align="center">Karaf/OSGi-style module lifecycle meets Kubernetes-style orchestration, all on the JVM.</p>

A fully-Java application platform: dynamic module deploy/undeploy, tiered isolation
(classloader → worker JVM → namespace), Raft-replicated control plane, and a service fabric —
no containers, no external orchestrator, no non-Java runtime.

- Architecture and design: [`gimle-PROJECT-v2.md`](gimle-PROJECT-v2.md)
- Developer docs: [`gimle-docs/`](gimle-docs/) (`mvn -P docs -pl gimle-docs install` to build
  locally — hosting isn't decided yet, see `docusaurus.config.ts`)
- Web console: [`gimle-console/`](gimle-console/)

## Build

    mvn verify

Requires JDK 25 and Bun on `PATH`.

### Faster local builds

`.mvn/maven.config` already sets `-T 1C` (one reactor thread per core) for every invocation, and
the Maven Build Cache Extension (`.mvn/extensions.xml`) skips recompiling/retesting a module
whose inputs haven't changed since the last build, keyed off actual source content, not
timestamps (`.mvn/maven-build-cache-config.xml`; opted out for `gimle-smoke-tests` and the
Bun-built `gimle-console`/`gimle-fafnir-console`/`gimle-docs`, whose real inputs or cacheable
behavior a source-hash can't see — see the `maven.build.cache.enabled` override in each of those
modules' own `pom.xml`). Both apply automatically; nothing extra to opt into.

On top of that, for iterating on one module:

- `mvn -pl gimle-worker -am test` — build and test only `gimle-worker` and the modules it depends
  on, skipping the rest of the reactor entirely.
- [`mvnd`](https://github.com/apache/maven-mvnd) (the Maven Daemon) as a drop-in `mvn` replacement
  — keeps a warm JVM resident across invocations, which matters most for a tight edit/build loop on
  one machine; not applicable in CI, where every run starts a fresh runner.

To see where time actually went in a specific build, run with `-Dprofile` (e.g.
`mvn -Dprofile verify`): the Maven Profiler extension (`.mvn/extensions.xml`) writes an HTML
report under `.profiler/` breaking down execution time per module, phase, and plugin goal. It's
a no-op on every other invocation — only active when `-Dprofile` is passed.
