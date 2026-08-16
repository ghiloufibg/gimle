---
sidebar_position: 6
---

# Distribution archives

`gimle-dist` packages the platform's own already-built jars into three audience-specific tarballs —
no Java sources of its own, only `maven-assembly-plugin` descriptors and two POSIX shell wrapper
scripts. It's an opt-in reactor member the same way `gimle-docs` is: `mvn install` builds every
module including `gimle-dist`, since `gimle-dist` is a plain (always-in) module, not a
profile-gated one.

## Building

```bash
mvn -pl gimle-dist -am install
```

`-am` builds every module `gimle-dist` depends on first (every process-kind module, plus
`gimle-hilmir`, `gimle-cli`, and `gimle-gateway`); `gimle-dist` itself then assembles three tarballs
under `gimle-dist/target/`, each with a `.sha256` checksum file and a CycloneDX SBOM
(`*-cyclonedx.json`) beside it.

Add `-P dist-with-jre` to also bundle a per-component jlink JRE into each archive (see [Bundling a
JRE into the archives](#bundling-a-jre-into-the-archives) below) — the default build above is
completely unaffected by this profile's existence.

## The three archives

| Archive | Audience | Contents |
|---|---|---|
| `gimle-platform-<version>.tar.gz` | Cluster machines | `bin/hilmir`, `bin/gimle`, a flat `lib/` holding every process-kind jar (`StoreMain`, `ControlPlaneMain`, `AgentMain`, `WorkerMain`, `FafnirMain`, `MuninnMain`, `AndvariMain`, `PkiBootstrapMain`) plus `gimle-hilmir`/`gimle-cli` themselves and their full deduplicated runtime dependency closure, and a `modules/` directory holding `gimle-gateway`'s own jar (the hosted-module payload for a future `hilmir enable gateway` verb — not on any process's own classpath). |
| `gimle-cli-<version>.tar.gz` | A workstation that only needs the `gimle` client | `bin/gimle` plus exactly `gimle-cli`'s own runtime dependency closure. Nothing else. |
| `gimle-hilmir-<version>.tar.gz` | A workstation that only needs to run `hilmir`'s release verbs against an already-running cluster | `bin/hilmir` plus exactly `gimle-hilmir`'s own runtime dependency closure. Nothing else. |

Unpacking any archive creates its own top-level directory (`gimle-platform-<version>/`,
`gimle-cli-<version>/`, `gimle-hilmir-<version>/`); the intended install location on a cluster
machine is `/opt/gimle/<version>`, matching `gimle-hilmir`'s own topology YAML convention of
pointing `runtime.classpath` at an already-unpacked `lib/` (see [`gimle-hilmir`
reference](./hilmir-reference.md)).

## The wrapper scripts

`bin/hilmir` and `bin/gimle` are the same two files reused verbatim across all three archives: each
resolves its own directory (so `../lib` is found regardless of the caller's working directory),
builds a classpath from every jar under that `lib/`, and `exec`s `java -cp "$CLASSPATH"
com.gimle.hilmir.HilmirMain "$@"` (or `com.gimle.cli.GimleCli` for `bin/gimle`). The `java` each
script launches itself with follows this precedence: an explicit `JAVA_HOME` environment variable
always wins (a deliberate operator override); otherwise, if the archive was built with
`-P dist-with-jre`, each script prefers its own bundled JRE (`jre/hilmir/bin/java` for `bin/hilmir`,
`jre/cli/bin/java` for `bin/gimle`) when that file actually exists next to it; otherwise both fall
back to plain `java` on `PATH`, exactly as they did before this archive ever bundled a JRE of its
own. A plain default-build archive (no `-P dist-with-jre`) simply has no `jre/` directory at all, so
every unpacked archive built that way always falls through to the `JAVA_HOME`/`PATH` behavior.

### Why `bin/hilmir up` needs no extra flag inside the platform archive

`hilmir up` spawns every other process kind (`StoreMain`, `ControlPlaneMain`, and so on) with a
classpath that `LaunchPlanner` resolves once for the whole cluster plan
(`ResolvedRuntime.classpath`) and reuses for every spawned command. When a topology document
doesn't set `runtime.classpath` itself, that default falls back to
`System.getProperty("java.class.path")` — the classpath the running `hilmir` JVM itself was
launched with. Inside the platform archive, `bin/hilmir`'s own `-cp` argument is already built from
every jar in that same archive's `lib/` — the identical flat directory every other role's jar lives
in — so this default is already correct with no `--platform-lib` flag or extra environment variable
needed; confirmed by running `hilmir plan` and `hilmir up` from an unpacked platform archive and
inspecting the generated commands' own `-cp` arguments.

This only holds inside the platform archive. The standalone `gimle-hilmir` archive's own `lib/`
holds just `gimle-hilmir`'s narrow six-jar closure — enough for `validate`/`plan`/`pki init` and
every release verb (`deploy`/`upgrade`/`rollback`/`undeploy`/`releases`/`release-status`, which only
ever talk HTTP to an already-running control plane), but running `hilmir up` from that archive
against a real multi-role topology fails immediately: the spawned `StoreMain` process inherits that
same narrow classpath and can't find its own main class. Confirmed directly: `hilmir up` from an
unpacked standalone `gimle-hilmir` archive produces `Error: Could not find or load main class
com.gimle.mimir.StoreMain`. Either run `up` from inside a platform archive, or set
`runtime.classpath` explicitly in the topology document to point at a platform archive's `lib/`
elsewhere on that machine.

## What v1 deliberately leaves out

- **No git-tag-driven versioning.** Every module in this repo builds as `0.1.0-SNAPSHOT` today —
  there's no tag or release process to key a version off of. `gimle-dist` names every archive after
  whatever `${project.version}` the reactor currently resolves to, SNAPSHOT included. Real
  release-tag-driven versioning is a genuine follow-up, not built here.
- **No cryptographic signing.** Each archive gets a `sha256sum`-compatible checksum file, not a
  signature — who signs a real release and where that key lives is an operational/security decision
  left for later, not a placeholder step invented here.
- **One combined SBOM, not three scoped ones.** `cyclonedx-maven-plugin` has no per-artifact
  include/exclude filter — only whole-project-dependency-graph or reactor-aggregate modes — so
  genuinely scoping a separate SBOM to each archive's own narrower jar set would need three separate
  Maven modules. `gimle-dist` generates one CycloneDX SBOM covering its own full resolved dependency
  set (a superset of any single archive's own jars) and copies it to each archive's own
  `-cyclonedx.json` name. This SBOM never lists a bundled JRE either way (see below) — a jlink-built
  runtime is never a Maven dependency, so it was never going to appear in a dependency-graph-derived
  SBOM regardless of whether `-P dist-with-jre` was used, a known and accepted limitation rather than
  an oversight.

## Bundling a JRE into the archives

`-P dist-with-jre` is an opt-in, additive build option: `mvn -pl gimle-dist -am install
-P dist-with-jre` builds the same three archives the default build produces, each additionally
carrying a per-component jlink-trimmed JRE under a new top-level `jre/` directory. It never changes
what the default (no-profile) build produces — no `jre/` directory exists in an archive built
without this flag.

### Which components, and why not all of them

Only **eight** of the ten process/client modules are safe to bundle a trimmed JRE for, and this
profile bundles exactly those eight — never more:

| Bundled (`jre/<name>/`) | Never bundled |
|---|---|
| `controlplane`, `mimir`, `fafnir`, `muninn`, `andvari`, `pki`, `hilmir`, `cli` | `agent`, `worker` |

`gimle-agent` and `gimle-worker` are excluded on purpose, not by oversight: the node agent spawns
arbitrary vessel workloads (plain runnable jars, e.g. a Spring Boot app) as child processes, and the
worker hosts arbitrary Gimlé modules inside its own JVM via `ModuleLayer` — in both cases the actual
JDK module needs of the code that ends up running are only known once that workload is deployed,
long after this archive was built. jlink's `--add-modules` list, by contrast, is derived once, ahead
of time, from a fixed set of platform code that never changes at runtime — true for the other eight
process kinds (each one only ever runs the platform's own code), but never true for the agent or the
worker. Bundling a trimmed JRE for either would silently break any vessel or module that happens to
need a JDK module outside whatever set was baked in at build time, with no way to detect the mismatch
ahead of time. Nothing in this feature — not `gimle-dist`'s own build, not any `gimle-hilmir`
topology field, not either wrapper script — ever produces or consumes a `jre/agent/` or
`jre/worker/` directory.

### The `jre/<component>/` layout

Each bundled JRE is a real, standalone, runnable `jlink` output (its own `bin/java`, `lib/`,
`release` file, and so on), named after the module it was built for rather than the topology-level
role vocabulary `gimle-hilmir` uses elsewhere (so `jre/mimir/`, not `jre/store/`; `jre/controlplane/`,
not `jre/control-plane/`) — the same naming already used for `lib/`'s own jars, so a `jre/<name>/`
directory is identifiable purely by inspecting the unpacked archive. The `--add-modules` list each
one is built from is a verbatim duplicate of that component's own `gimle.runtimeImage.jdkModules`
property (`gimle-dist/pom.xml`'s own `dist-with-jre` profile documents which module's `pom.xml` each
one was copied from), and every invocation uses the identical `--strip-debug --no-header-files
--no-man-pages` flags the root `runtime-image` profile already established.

Archive contents:

| Archive | `jre/` contents when built with `-P dist-with-jre` |
|---|---|
| `gimle-platform-<version>.tar.gz` | All eight: `jre/controlplane/`, `jre/mimir/`, `jre/fafnir/`, `jre/muninn/`, `jre/andvari/`, `jre/pki/`, `jre/hilmir/`, `jre/cli/`. |
| `gimle-cli-<version>.tar.gz` | `jre/cli/` only. |
| `gimle-hilmir-<version>.tar.gz` | `jre/hilmir/` only. |

### How it gets used

Both wrapper scripts auto-prefer their own bundled JRE when present (see [The wrapper
scripts](#the-wrapper-scripts) above), and `gimle-hilmir`'s own `runtime.useBundledJre` topology
field controls whether `hilmir up`/`hilmir pki init` launch the *spawned* cluster processes against
their own bundled JREs too — see [`gimle-hilmir`
reference](./hilmir-reference.md#topology-runtime-block) for that field's own precondition and
failure behavior. The two mechanisms are independent: a `JAVA_HOME`
override, an unpacked archive's own bundled `jre/hilmir/`, and `runtime.useBundledJre` in a topology
document each answer a different "which `java`?" question — which `java` runs `hilmir` itself, versus
which `java` `hilmir` spawns every other process kind with.

### Storage tradeoff

A single trimmed per-component JRE (`java.base` plus a handful of modules, `--strip-debug`) is
meaningfully smaller than a full JDK install, but the platform archive bundles eight of them side by
side — its total size is measurably larger than the default build's. This is why bundling stays
opt-in rather than becoming the default: an operator who doesn't need a self-contained JRE bundled
into the archive (a machine that already has a suitable `java` installed, or one where minimizing
archive size matters more than removing the external `java` dependency) keeps the smaller default
archive by simply not passing `-P dist-with-jre`.
