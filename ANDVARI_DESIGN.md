# `gimle-andvari` — a lightweight artifact store, design proposal

## The gap this closes

Gimlé has no equivalent of an OCI image registry. A workload manifest's `artifactPath` is a bare
local filesystem path: `DeploymentManifestParser`/`JobManifestParser`/`CronJobManifestParser`/
`DaemonSetManifestParser`/`StatefulSetManifestParser` all just require it be a non-blank string --
no `Files.exists()` check at admission time, nothing. `Scheduler.place()`'s bin-packing filters
(memory/CPU/isolation-tier/cordon/anti-affinity/tenant-isolation/required-labels) have zero
artifact-locality awareness: a replica can be placed on any eligible node with total disregard for
whether that node's filesystem actually has the jar. The jar is read straight off the worker's
local disk at install time via `ModuleArtifactReader.read(Path.of(m.artifactPath()))` --
`WorkerMain`, not the control plane, and potentially on a completely different machine than
wherever the manifest was authored.

The failure mode when the file is missing is close to silent: `WorkerMain#handle`'s
`InstallModule` case catches the resulting `GimleManifestException` and replies with a
`ControlMessage.Nack`, but `AgentMain`'s `readLoop` only logs it (`log.warn(...)`) -- it never
updates `SupervisedInstance.lifecycleState`, which defaults to `"INSTALLED"` and stays there
forever. The instance looks like it's merely still starting, indefinitely, in both the console and
`gimle deployment status`.

In practice, running a real multi-node cluster today requires every jar to already exist at the
identical absolute path on every node a replica might land on, via a shared/NFS mount or manual
out-of-band copying -- entirely outside anything Gimlé itself does. This is distinct from the
project's documented non-goal of "no OCI images" (`gimle-docs/docs/intro.md`, `CLAUDE.md`'s own
non-goals list are explicit that's deliberate); this is the absence of *any* distribution
mechanism at all, not even a lightweight one, and it isn't called out anywhere in the docs or
roadmap.

## Goals

- A node that already has the right `(moduleId, version)` cached locally never makes a network
  call -- matches Kubernetes' own `imagePullPolicy: IfNotPresent` default.
- A node that doesn't have it fetches from a shared store on demand, verifies its checksum, and
  caches it for next time.
- `artifactPath` keeps working unchanged as an explicit local-file override -- local dev and
  `gimle-smoke-tests`' own on-the-fly-built-jar fixture pattern must not be forced through a real
  push/pull round trip just to run a test.
- Fits the same defense-in-depth auth pattern already proven twice (`gimle-fafnir`,
  `gimle-muninn`): the new process independently re-authorizes every request, never trusting
  "arrived already-forwarded" as proof by itself.

## Non-goals (v1)

- No signing/provenance verification beyond a SHA-256 checksum.
- No pull-through caching of an *external* Maven Central/Nexus/Artifactory.
- No partial replication or sharding -- every Andvari replica is a full mirror of every artifact.
- No automatic garbage collection of old versions (`DELETE` is manual/operator-driven only).
- No OCI-layer-style content deduplication across versions -- one version is one whole jar.

## Naming: `gimle-andvari`

Andvari is the dwarf from Norse myth who hoards a cursed treasure (the ring Andvaranaut) later
guarded by a certain dragon -- a deliberate in-universe callback to `gimle-fafnir` while staying
correctly scoped: Fafnir guards *secrets*, Andvari hoards *artifacts*. Fits the project's existing
naming register (`Drakkar, Þjappa, Skald, Bifrost, Galdr, Muninn, Fafnir`) without colliding with
anything already used.

## Process topology placement

A seventh Gimlé process kind (`AndvariMain`), alongside Node Agent / Worker / Control Plane /
Mimir / Fafnir / Muninn. Not Raft-replicated itself -- matches Fafnir's and Muninn's own posture;
only `gimle-mimir` is Raft-replicated. Node agents talk to it **directly** for pulls, the same way
they already fetch secret values straight from Fafnir over their own node mTLS identity, bypassing
the control plane. Pushes go **through the control plane** as a proxy, mirroring `/secrets/*`: a
human/CI operator's RBAC identity gets checked once centrally and forwarded as an internal claim,
with Andvari independently re-running its own `Authorizer.authorize(...)` regardless.

```
Node Agent ──(direct, mTLS)──► Andvari ◄──(proxied, via ApiServer)── gimle CLI / CI
                                   │
                                   └── read-only StoreClient → gimle-mimir (RBAC only)
```

## Storage & content model

Content-addressed by `(moduleId, version)`, immutable once written -- a jar is never overwritten,
only a new version pushed. Reuses the SHA-256 digest `ModuleArtifactReader` already computes today
rather than inventing a second checksum scheme.

```
<andvari-data-root>/artifacts/<moduleId>/<version>/
  artifact.jar
  meta.json     # {sha256, sizeBytes, pushedAt, pushedBy}
```

Andvari is deliberately a **dumb store**: it does not parse `gimle-module.yaml` or validate
JPMS-module-ness. That validation already lives in `ModuleArtifactReader` on the worker side;
duplicating it here would be exactly the kind of parallel-path drift this codebase's own
conventions warn against. Andvari just stores bytes plus a checksum.

Every replica is a full mirror in v1 -- N replicas exist purely for HA/load, each independently
authoritative over the complete artifact set. Good enough for "Nexus-lite"; real
distribution/replication (partial mirrors, gossip-propagated blobs) is future scope if the
artifact set ever gets large enough to matter.

## HTTP API

```
PUT    /artifacts/{moduleId}/{version}      upload (raw jar body)
GET    /artifacts/{moduleId}/{version}      download raw bytes
HEAD   /artifacts/{moduleId}/{version}      existence + digest, no body -- the manifest-check
                                             equivalent, used by the agent before a real GET
GET    /artifacts/{moduleId}                list known versions
DELETE /artifacts/{moduleId}/{version}      retention/cleanup, RBAC-gated
```

`PUT` rejects a re-push of an already-present `(moduleId, version)` with a checksum mismatch as a
409 (immutability is load-bearing: a cached jar on some node must never silently become stale
relative to what a later `GET` would return) -- an identical re-push (same bytes, same checksum)
is a no-op 200, not an error, so a retried/idempotent CI push doesn't need special-casing.

## Auth model

New `ResourceKind.ARTIFACT` in `gimle-core`, same granularity precedent `DAEMONSET`/`STATEFULSET`
already set: pushing or deleting a jar is a meaningfully more consequential grant than an ordinary
deployment submission (supply-chain-adjacent), worth withholding independently rather than folding
into an existing kind. `READ` (pull) is what node agents exercise via their node identity; `WRITE`
(push) and a separate delete-gate are what a human/CI operator's role needs.

## The fetch algorithm

Resolution happens in **`AgentMain`**, before it sends `ControlMessage.InstallModule` -- not in
`WorkerMain`, which keeps reading a concrete local path exactly as it does today. This means zero
worker-side changes and zero `ControlMessage`/protocol changes: the message agent sends to worker
still just carries a resolved local path.

```
resolveArtifact(moduleId, version, explicitPathOrEmpty):
  if explicitPathOrEmpty present:
    return explicitPathOrEmpty            # unchanged escape hatch

  cachePath = <gimle.data.root>/artifact-cache/{moduleId}/{version}/artifact.jar
  if cachePath exists:
    return cachePath                       # local hit -- no network call, no re-verify

  bytes = andvariClient.GET(/artifacts/{moduleId}/{version})
  expectedSha256 = andvariClient.HEAD(/artifacts/{moduleId}/{version}).sha256
  verify sha256(bytes) == expectedSha256   # fail the install with a clear error otherwise
  atomically write bytes to cachePath (temp file + rename -- no torn file on a crash mid-download)
  return cachePath
```

Trusting a cache hit purely by presence, with no re-hash, is deliberate and matches
`imagePullPolicy: IfNotPresent`: content-addressing already guarantees a given `(moduleId,
version)` never changes shape once cached, so re-hashing on every install would be pure waste. A
resolution failure (Andvari unreachable, checksum mismatch, 404) must surface as a genuine
`FAILED` lifecycle state visible in `/deployments/*` and the console -- not silently repeat the
existing "stuck at INSTALLED forever" failure mode this design is meant to fix, so this work
should also close that secondary observability gap as part of the same change, not defer it.

## Manifest schema change

`artifactPath` becomes **optional**. Present: kept exactly as today, a local-file override. Absent:
`module: {name, version}` alone is sufficient -- the agent resolves it via the algorithm above. No
new required field; every existing manifest keeps working unchanged.

## CLI / console / docs touchpoints

- `gimle artifact push <jar>` -- reads the jar's own bundled `gimle-module.yaml` to derive
  `moduleId`/`version`, `PUT`s through the control-plane proxy.
- `gimle artifact list [moduleId]`, `gimle artifact get <moduleId> <version>`.
- Console: a new "Artifacts" screen (versions/checksums/sizes/push timestamp), same shape as the
  existing Secrets screen.
- `gimle-docs/docs/architecture/node-topology.md` gains a seventh process-kind row.
- `gimle-docs/docs/reference/manifest-schema.md`'s `artifactPath` row updates to "optional -- omit
  to resolve `module: {name, version}` via Andvari."

## Testing strategy (once implementation starts)

- Unit tier: `AndvariServer`/`AndvariClient` request/response shapes, checksum verification,
  immutability/409-on-mismatch, RBAC re-check independent of an already-forwarded claim -- same
  shape as `FafnirServerTest`/`MuninnServerLogsIngestTest` etc.
- Real-cluster tier (`gimle-smoke-tests`, matching this session's own established pattern for new
  subsystems): a push through the real control-plane proxy, a deployment submitted with no
  `artifactPath` at all, a real agent resolving/fetching/caching it, reaching `ACTIVE` -- plus a
  second instance on a different node proving its own independent cache miss/fetch/hit path.

## Open follow-up (not blocking a v1)

- Garbage collection / retention policy for old versions.
- Partial replication if the artifact set outgrows "every replica mirrors everything."
- Signing/provenance beyond a bare checksum.
