---
sidebar_position: 7
---

# Distribution archives

`gimle-dist` packages the platform's own already-built jars into six audience-specific tarballs —
no Java sources of its own, only `maven-assembly-plugin` descriptors, four wrapper scripts (each
shipped as both a POSIX `sh` version and a Windows `.cmd` version), and the Midgard archive's
Docker/topology/seeding files. It's an opt-in reactor member the same way `gimle-docs` is: `mvn
install` builds every module including `gimle-dist`, since `gimle-dist` is a plain (always-in)
module, not a profile-gated one.

## Building

```bash
mvn -pl gimle-dist -am install
```

`-am` builds every module `gimle-dist` depends on first (every process-kind module, plus
`gimle-hilmir`, `gimle-ragnarok`, `gimle-ivaldi`, `gimle-cli`, `gimle-gateway`, and the example
modules the Midgard archive bundles); `gimle-dist` itself then assembles six tarballs under
`gimle-dist/target/`, each with a `.sha256` checksum file and a CycloneDX SBOM
(`*-cyclonedx.json`) beside it.

Add `-P dist-with-jre` to also bundle a per-component jlink JRE into each archive (see [Bundling a
JRE into the archives](#bundling-a-jre-into-the-archives) below) — the default build above is
completely unaffected by this profile's existence.

## The six archives

| Archive | Audience | Contents |
|---|---|---|
| `gimle-platform-<version>.tar.gz` | Cluster machines | `bin/hilmir`(`.cmd`), `bin/gimle`(`.cmd`), `bin/ivaldi`(`.cmd`), a flat `lib/` holding every process-kind jar (`StoreMain`, `ControlPlaneMain`, `AgentMain`, `WorkerMain`, `FafnirMain`, `MuninnMain`, `AndvariMain`, `SkaldMain`, `PkiBootstrapMain`) plus `gimle-hilmir`/`gimle-cli`/`gimle-ivaldi` themselves and their full deduplicated runtime dependency closure, and a `modules/` directory holding `gimle-gateway`'s own jar (the hosted-module payload for a future `hilmir enable gateway` verb — not on any process's own classpath). |
| `gimle-cli-<version>.tar.gz` | A workstation that only needs the `gimle` client | `bin/gimle`(`.cmd`) plus exactly `gimle-cli`'s own runtime dependency closure. Nothing else. |
| `gimle-hilmir-<version>.tar.gz` | A workstation that only needs to run `hilmir`'s release verbs against an already-running cluster | `bin/hilmir`(`.cmd`) plus exactly `gimle-hilmir`'s own runtime dependency closure. Nothing else. |
| `gimle-ragnarok-<version>.tar.gz` | An operator running chaos/stress tests against a real, already-running cluster | `bin/ragnarok`(`.cmd`) plus exactly `gimle-ragnarok`'s own runtime dependency closure — see [`gimle-ragnarok` reference](./ragnarok-reference.md). |
| `gimle-ivaldi-<version>.tar.gz` | An operator or developer running the cluster designer without a source checkout | `bin/ivaldi`(`.cmd`) plus exactly `gimle-ivaldi`'s own runtime dependency closure — confirmed via `mvn -pl gimle-ivaldi dependency:list -Dscope=runtime`, the same diligence [`mvn gimle:ivaldi`](./maven-plugin-goals.md#mvn-gimleivaldi) itself needs none of, since that goal resolves the equivalent closure through Aether instead. That closure is two layers deeper than validation alone needs: `gimle-hilmir`/`gimle-mimir` and, transitively through `gimle-mimir`, `gimle-pki`/`gimle-observability` for tier-2 validation; `gimle-ivaldi-console` for the bundled web console `IvaldiMain` serves at `/console`; and every process-kind module `RunController` hands `MachineLauncher` on a spawned child's classpath when it boots a local cluster (`gimle-controlplane`, `gimle-fafnir`, `gimle-muninn`, `gimle-andvari`, `gimle-agent`, `gimle-worker`, and each of those modules' own further runtime dependencies — `gimle-module`, `gimle-os`, `gimle-fabric`, and the three bundled process consoles `gimle-console`/`gimle-fafnir-console`/`gimle-andvari-console`). |
| `gimle-midgard-<version>.tar.gz` | Local development and manual QA | The [Midgard dev cluster](#the-midgard-dev-cluster-image): a self-contained Docker build context booting a complete single-machine cluster inside one container, pre-seeded with the example modules. |

Unpacking any archive creates its own top-level directory (`gimle-platform-<version>/`,
`gimle-cli-<version>/`, `gimle-hilmir-<version>/`, `gimle-ragnarok-<version>/`,
`gimle-ivaldi-<version>/`, `gimle-midgard-<version>/`); the intended install location on a cluster
machine is `/opt/gimle/<version>`, matching `gimle-hilmir`'s own topology YAML convention of
pointing `runtime.classpath` at an already-unpacked `lib/` (see [`gimle-hilmir`
reference](./hilmir-reference.md)). The Midgard archive is the exception: it unpacks anywhere
Docker runs, and nothing in it is executed on the host directly.

## The wrapper scripts

`bin/hilmir`/`bin/hilmir.cmd`, `bin/gimle`/`bin/gimle.cmd`, `bin/ragnarok`/`bin/ragnarok.cmd`, and
`bin/ivaldi`/`bin/ivaldi.cmd` are the same eight files reused verbatim across the archives (the
Midgard archive ships only the POSIX `hilmir`/`gimle` pair — everything in it runs inside a Linux
container, and it never runs `ragnarok` or `ivaldi` at all) — a POSIX `sh` script for Linux/macOS
and a Windows `.cmd` counterpart per tool, kept behaviorally identical. Each resolves its own
directory (so `../lib` / `..\lib` is found regardless of the caller's working directory), builds a
classpath from every jar under that `lib/`, and launches `java -cp "$CLASSPATH"
com.gimle.hilmir.HilmirMain "$@"` (`com.gimle.cli.GimleCli` for the `gimle`/`gimle.cmd` pair,
`com.gimle.ragnarok.RagnarokMain` for the `ragnarok`/`ragnarok.cmd` pair). `bin/ivaldi`/`bin/ivaldi.cmd`
differ in one respect: `IvaldiMain` reads no positional arguments at all, only `-D` system
properties (`gimle.ivaldi.port`/`dataRoot`/`host`), so those two scripts place `"$@"`/`%*` *before*
`com.gimle.ivaldi.IvaldiMain` on the `java` command line rather than after it — a `-D` flag is a
JVM option, not a program argument, and only ever takes effect there. The `java` each script
launches itself with follows this precedence: an explicit `JAVA_HOME` environment variable always
wins (a deliberate operator override); otherwise, if the archive was built with `-P dist-with-jre`,
each script prefers its own bundled JRE (`jre/hilmir/bin/java`(`.exe`) for the `hilmir` pair,
`jre/cli/bin/java`(`.exe`) for the `gimle` pair, `jre/ragnarok/bin/java`(`.exe`) for the `ragnarok`
pair, `jre/ivaldi/bin/java`(`.exe`) for the `ivaldi` pair) when that file actually exists next to
it; otherwise all four fall back to plain `java` on `PATH`, exactly as they did before this archive
ever bundled a JRE of its own. A plain default-build archive (no `-P dist-with-jre`) simply has no
`jre/` directory at all, so every unpacked archive built that way always falls through to the
`JAVA_HOME`/`PATH` behavior.

Two failure cases each script reports rather than swallowing:

- **A bundled JRE that can't run here.** A bundled runtime only runs on the platform it was built
  for (see [The bundled JRE is built for the machine that builds
  it](#the-bundled-jre-is-built-for-the-machine-that-builds-it) below), and an archive travels
  further than the machine that built it. If `jre/<component>/bin/java`(`.exe`) is missing or
  refuses to run, the script says so — naming the platform recorded in `jre/PLATFORM` and the
  machine it is actually on — and then continues down the same `PATH` fallback, instead of silently
  pretending no JRE was bundled.
- **No Java at all.** If nothing resolves — no `JAVA_HOME`, no usable bundled runtime, no `java` on
  `PATH` — the script says exactly that and exits 1, rather than letting the launch line fail with a
  bare `exec: java: not found`.

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
holds just `gimle-hilmir`'s narrow seven-jar closure — enough for `validate`/`plan`/`pki init`,
every release verb (`deploy`/`upgrade`/`rollback`/`undeploy`/`releases`/`release-status`), and the
store membership verbs (`store add`/`store remove`, which need `gimle-mimir`'s own `StoreClient` to
talk RPC to an already-running store cluster, even though this archive never spawns
`com.gimle.mimir.StoreMain` itself) — all of which only ever talk over the network to an
already-running cluster. Running `hilmir up` from that archive against a real multi-role topology
still doesn't work: the spawned `StoreMain` process inherits that same narrow classpath and can't
find its own main class. That failure is *not* immediate at the `hilmir up` level, though — the
spawned process's own log shows `Error: Could not find or load main class com.gimle.mimir.StoreMain`
right away, but `hilmir up` itself has no way to notice a spawned process died until its readiness
poll either observes the process exit or times out, so the operator sees `hilmir up` sit for as long
as that role's own readiness timeout before reporting the failure (`ReadinessPoller` fails fast the
moment it notices the process has exited — it does not need to wait out the whole timeout, but it
still cannot notice sooner than its own next poll tick after the process actually crashes). Either
run `up` from inside a platform archive, or set `runtime.classpath` explicitly in the topology
document to point at a platform archive's `lib/` elsewhere on that machine.

## The Midgard dev cluster image

`gimle-midgard-<version>.tar.gz` is Gimlé's minikube equivalent: unpack it anywhere Docker (with
Compose v2) runs and

```bash
docker compose up -d
```

builds an image and boots a complete, ready-to-use single-machine Gimlé cluster inside one
container — real development and manual QA against the actual platform with nothing on the host
but Docker. The archive is a self-contained Docker build context: the platform archive's own flat
`lib/` and `modules/` layout plus the two wrapper scripts, the example module jars under
`examples/`, a `Dockerfile` (based on a full JRE image, deliberately — the same
arbitrary-module-code reasoning that keeps the agent and worker out of `-P dist-with-jre` applies
to an image that hosts arbitrary deployed modules), a `docker-compose.yaml`, and a `midgard/`
directory holding the topology, entrypoint, seeding script, and example deployment manifests. No
JDK, Maven, or source checkout is needed on the host.

Inside the container, boot is the platform's own mechanism, not a parallel one: the entrypoint
runs `hilmir up` against the bundled single-machine topology (`midgard/topology.yaml` — one store
replica, one control plane, Fafnir, Muninn, Andvari, and one node agent named `midgard-node`, all
advertising `127.0.0.1`; every listener binds the wildcard address, which is why Docker's
published ports still reach them). Once the machine is up, the entrypoint pushes the bundled
example modules (`hello-module`, `greeter-provider`, `greeter-consumer`) to the Andvari registry
through the control plane's `/artifacts/*` proxy and applies an `apiVersion: v1`
registry-coordinate deployment for each, so the cluster starts with real running workloads.
Seeding happens once per data volume (marked on the volume itself), so a restart never re-applies
the bundled manifests over changes made to the example deployments since; set `MIDGARD_SEED:
"false"` in the compose file to boot empty instead, or re-run the bundled
`midgard/seed-examples.sh` inside the container to reset the examples deliberately. `docker stop`
tears the cluster down through `hilmir down` from the same entrypoint's signal trap.

Published ports: `8080` (control plane API and web console at `/console`), `9092` (Fafnir API and
console), `9093` (Muninn), `9094` (Andvari API and console). The cluster is plaintext and
unauthenticated, like every other local-dev Gimlé setup — never publish these ports beyond the
local machine. Cluster state (store data, secrets, pushed artifacts, per-process logs) lives in
the `midgard-data` named volume mounted at `/var/lib/gimle`, so state survives container
recreation; `docker compose down -v` resets to a fresh cluster. The compose file sets `init:
true`: `hilmir up` spawns each platform process detached, so they reparent to PID 1, and an init
process guarantees they are reaped when they exit (the entrypoint running as PID 1 happens to reap
them too, but that is incidental shell behavior, not a contract) — plain `docker run` should pass
`--init` for the same reason. See the archive's own `README.md` for the day-to-day commands
(pointing a host-side `gimle` CLI at `--server localhost:8080`, `docker exec` equivalents,
deploying your own module by `gimle artifact push` + a coordinate-only manifest).

## The SBOMs

Each archive ships a CycloneDX 1.5 SBOM beside it as `<archive>-<version>-cyclonedx.json`, scoped
to that archive's own jars rather than to one combined superset:

| Archive's SBOM | Generated by | Scope |
|---|---|---|
| `gimle-cli-<version>-cyclonedx.json` | `gimle-cli`'s own `cyclonedx-maven-plugin` execution | Exactly `gimle-cli`'s runtime dependency closure — which is exactly what that archive ships. |
| `gimle-hilmir-<version>-cyclonedx.json` | `gimle-hilmir`'s own execution | Same, for `gimle-hilmir`. |
| `gimle-ragnarok-<version>-cyclonedx.json` | `gimle-ragnarok`'s own execution | Same, for `gimle-ragnarok`. |
| `gimle-platform-<version>-cyclonedx.json` | `gimle-dist`'s own `sbom-platform` execution | `gimle-dist`'s runtime dependency graph excluding provided scope — the platform archive's own jar set. |
| `gimle-midgard-<version>-cyclonedx.json` | `gimle-dist`'s own `sbom-midgard` execution | The same graph *including* provided scope, which is where the example modules the Midgard image seeds live. |

`cyclonedx-maven-plugin` has no artifact-level include/exclude filter: `makeBom` always describes
the whole dependency graph of whichever Maven project it runs in. That is why the first three come
from the tool modules themselves — each already *is* the graph its archive ships — while only the
two graphs `gimle-dist` genuinely describes are generated here.

It also always writes the running Maven project as the SBOM's root `metadata.component`, with no
way to override it, so the platform and midgard files would arrive naming `gimle-dist` — a module
that ships nothing an operator can install. `gimle-dist`'s `postprocess-archives` step rewrites that
one root component (name, description, and the coordinate its `bom-ref`, `purl`, and dependency-graph
root ref all share) to name the archive the file actually sits beside. Nothing below the root is
touched: the resolved dependency components, which are what an SBOM is read for, come straight from
the generator. A build where that rewrite stops matching fails rather than shipping a mislabelled
SBOM.

## What v1 deliberately leaves out

- **No git-tag-driven versioning.** Every module in this repo builds as `0.1.0-alpha.2` today, a
  hand-bumped pre-release string — there's no tag or release process to key a version off of yet.
  `gimle-dist` names every archive after whatever `${project.version}` the reactor currently
  resolves to, pre-release qualifier included. Real release-tag-driven versioning is a genuine
  follow-up, not built here.
- **No cryptographic signing.** Each archive gets a `sha256sum`-compatible checksum file, not a
  signature — who signs a real release and where that key lives is an operational/security decision
  left for later, not a placeholder step invented here.
- **No SBOM ever lists a bundled JRE.** A jlink-built runtime is never a Maven dependency, so it
  was never going to appear in a dependency-graph-derived SBOM regardless of whether
  `-P dist-with-jre` was used — a known and accepted limitation rather than an oversight.

## Bundling a JRE into the archives

`-P dist-with-jre` is an opt-in, additive build option: `mvn -pl gimle-dist -am install
-P dist-with-jre` builds the same archives the default build produces, with the platform, CLI,
Hilmir, Ragnarök, and Ivaldi ones each additionally
carrying a per-component jlink-trimmed JRE under a new top-level `jre/` directory. It never changes
what the default (no-profile) build produces — no `jre/` directory exists in an archive built
without this flag.

### Which components, and why not all of them

Only **eleven** of the thirteen process/client modules are safe to bundle a trimmed JRE for, and
this profile bundles exactly those eleven — never more:

| Bundled (`jre/<name>/`) | Never bundled |
|---|---|
| `controlplane`, `mimir`, `fafnir`, `muninn`, `andvari`, `skald`, `pki`, `hilmir`, `cli`, `ragnarok`, `ivaldi` | `agent`, `worker` |

`gimle-agent` and `gimle-worker` are excluded on purpose, not by oversight: the node agent spawns
arbitrary vessel workloads (plain runnable jars, e.g. a Spring Boot app) as child processes, and the
worker hosts arbitrary Gimlé modules inside its own JVM via `ModuleLayer` — in both cases the actual
JDK module needs of the code that ends up running are only known once that workload is deployed,
long after this archive was built. jlink's `--add-modules` list, by contrast, is derived once, ahead
of time, from a fixed set of platform code that never changes at runtime — true for the other eleven
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
| `gimle-platform-<version>.tar.gz` | The ten cluster-machine process kinds plus Ivaldi: `jre/controlplane/`, `jre/mimir/`, `jre/fafnir/`, `jre/muninn/`, `jre/andvari/`, `jre/skald/`, `jre/pki/`, `jre/hilmir/`, `jre/cli/`, `jre/ivaldi/`. `jre/ragnarok/` is never bundled here — Ragnarök runs from an operator's own workstation against a real cluster, not from a cluster machine itself, so it has no reason to ship inside the platform archive; Ivaldi is bundled despite the same "runs from a workstation" shape, since an operator or developer already on a cluster machine sketching or validating a topology change is a real enough case to justify the extra jar there. |
| `gimle-cli-<version>.tar.gz` | `jre/cli/` only. |
| `gimle-hilmir-<version>.tar.gz` | `jre/hilmir/` only. |
| `gimle-ragnarok-<version>.tar.gz` | `jre/ragnarok/` only. |
| `gimle-ivaldi-<version>.tar.gz` | `jre/ivaldi/` only. |

Every one of those archives also carries a one-line `jre/PLATFORM` file naming the platform its
images were built for — see the next section.

### The bundled JRE is built for the machine that builds it

`jlink` produces a runtime image for the platform it runs on and no other — there is no
cross-platform build here, and none is possible from a single JDK install. So `-P dist-with-jre`
makes an otherwise platform-neutral archive (jars, shell scripts) platform-specific: a profile build
run on Windows produces `jre/*/bin/java.exe`, which a Linux cluster machine cannot execute, and a
profile build on macOS produces Mach-O binaries neither of the others can.

Two things make that impossible to ship by accident:

- **The build states what it is building for, and checks it.** `gimle.dist.jre.targetOsName` /
  `gimle.dist.jre.targetOsArch` default to `Linux` / `x86_64` — the platform the cluster-machine
  archive targets. After `jlink` runs, the build reads the platform back out of a produced image's
  own `release` file and fails if it isn't that, naming both platforms. Building for a different
  target is a matter of building *on* that platform (or in a container for it) and declaring it:

  ```bash
  mvn -pl gimle-dist -am install -P dist-with-jre \
      -Dgimle.dist.jre.targetOsName=Darwin -Dgimle.dist.jre.targetOsArch=aarch64
  ```

  Both values are spelled exactly as a JDK's own `release` file spells them (`OS_NAME`/`OS_ARCH`),
  so there is no separate naming scheme to learn: `Linux`/`Darwin`/`Windows`, and
  `x86_64`/`aarch64`/`amd64`.

- **The archive says which platform its runtime is for.** Every archive that bundles a JRE carries a
  one-line `jre/PLATFORM` file (`Linux-x86_64`) written by that same check. `cat jre/PLATFORM`
  answers "which machines is this archive's runtime for?" without running anything, and it is what
  the wrapper scripts name when the bundled runtime turns out not to run on the machine unpacking
  it.

The default build (no `-P dist-with-jre`) bundles no runtime at all, so its archives stay
platform-neutral and run anywhere a JDK 25+ is already installed. If you publish archives for
several platforms, run the profile build once per platform and keep the platform in the published
file name; nothing in the build does that renaming for you.

### How it gets used

Every wrapper script auto-prefers its own bundled JRE when present (see [The wrapper
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
meaningfully smaller than a full JDK install, but the platform archive bundles ten of them side by
side — its total size is measurably larger than the default build's. This is why bundling stays
opt-in rather than becoming the default: an operator who doesn't need a self-contained JRE bundled
into the archive (a machine that already has a suitable `java` installed, or one where minimizing
archive size matters more than removing the external `java` dependency) keeps the smaller default
archive by simply not passing `-P dist-with-jre`.
