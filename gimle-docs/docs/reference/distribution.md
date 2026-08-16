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
com.gimle.hilmir.HilmirMain "$@"` (or `com.gimle.cli.GimleCli` for `bin/gimle`) — respecting
`JAVA_HOME` when set, falling back to `java` on `PATH` otherwise.

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
- **No jlink JRE bundling.** The root `runtime-image` profile only has
  `gimle.runtimeImage.jdkModules`/`launcherClass` wired for three modules
  (`gimle-agent`/`gimle-worker`/`gimle-controlplane`); deriving the same properties for the other
  seven process/client modules needs empirical per-module trial and error not yet done.
  `gimle-dist`'s archives ship jars run by the caller's own `java`, not a bundled custom runtime.
- **One combined SBOM, not three scoped ones.** `cyclonedx-maven-plugin` has no per-artifact
  include/exclude filter — only whole-project-dependency-graph or reactor-aggregate modes — so
  genuinely scoping a separate SBOM to each archive's own narrower jar set would need three separate
  Maven modules. `gimle-dist` generates one CycloneDX SBOM covering its own full resolved dependency
  set (a superset of any single archive's own jars) and copies it to each archive's own
  `-cyclonedx.json` name.
