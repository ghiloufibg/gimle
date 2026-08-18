# Gimle Andvari

Andvari is the module artifact registry: its own process (`AndvariMain`), an immutable,
content-addressed store of module jars — a lightweight first-party Maven-central/Nexus equivalent
that closes the gap where a deployment's `artifactPath` had to pre-exist on every node's own
filesystem. Node agents pull from it on a cache miss the way a kubelet pulls a missing container
image; operators (or CI) push through the control plane's proxy or straight to this port.
Deliberately a dumb store of bytes plus a SHA-256 checksum — it never parses `gimle-module.yaml` or
validates JPMS-ness; that validation already lives in the worker's own `ModuleArtifactReader`, and
duplicating it here would be parallel-path drift.

## Storage (`ArtifactStore`)

One version is one whole jar under `{dataRoot}/artifacts/{moduleId}/{version}/` beside a small
`meta.json`. A coordinate is never overwritten: a re-push with different bytes is refused with
`409 CONFLICT`, an identical re-push is an idempotent no-op. That immutability is load-bearing for
every downstream cache — a node that trusts a cached coordinate by presence alone is only sound
because the bytes behind it can never change here. Pushes stream through a `DigestInputStream` into
a temp file and commit with an atomic rename: never a whole jar buffered in memory, never a torn
file visible at the final path after a crash mid-upload. Downloads are streamed straight from disk
to the response socket and re-digested on the way through, so on-disk bit rot is actually caught,
not just trusted from `meta.json`'s recorded value — a mismatch quarantines the coordinate
(`ArtifactStore#quarantine`) so the same corrupted bytes are never served again without an operator
re-pushing it. `IntegrityScrubber` can additionally walk the whole store proactively
(`-Dgimle.andvari.scrub.enabled=true`), and `ArtifactRetentionSweeper` can retire old/excess
versions on a schedule (`-Dgimle.andvari.retention.enabled=true`); both are off by default.

Multi-replica deployments get real, if simple, high availability without a consensus protocol:
`AndvariPeerSync` polls every other configured replica's catalog (`--peer-endpoints`) and pulls in
whatever coordinate is missing locally, through the identical streamed-and-digested path a client's
own `PUT` already goes through — since `put` is already idempotent-on-identical/hard-conflict-on-
differing, any replica can accept a push independently and every other replica eventually converges
on having it too.

## HTTP surface (`AndvariServer`)

Plain `com.sun.net.httpserver.HttpServer`, the same minimal stack `ApiServer`/`FafnirServer`/
`MuninnServer` already use.

```
GET          /artifacts                        catalog of module ids
GET          /artifacts/{moduleId}              stored versions with checksums/provenance
HEAD/GET     /artifacts/{moduleId}/{version}    digest header / raw jar bytes
PUT          /artifacts/{moduleId}/{version}    upload (raw jar body); differing re-push is 409
DELETE       /artifacts/{moduleId}/{version}    operator-driven removal
```

A second, Maven-2-shaped view lives under `/repository/**` (`GAV` path translation via
`MavenCoordinates`) over the *identical* store — so a stock `mvn deploy`/`mvn install` can target
Andvari directly, with no separate repository implementation to keep in sync: `.jar` requests
resolve to the same `(moduleId, version)` coordinate the operational API uses, `.jar.sha256` is
always server-computed from `meta.json` (never trusted from a client upload), `.pom` and checksum
sidecars are accepted and stored opaquely, and `maven-metadata.xml` is generated fresh from the
store's own version list on every read.

## Authorization (defense-in-depth)

Andvari follows the same posture `gimle-fafnir` established: plaintext mode is open, matching
every other Gimlé surface's plaintext default — `AndvariMain` logs a loud startup warning naming
the exact consequence (an unauthenticated push places an executable jar where node agents will
download and run it). Under mTLS, every `/artifacts/*` and `/repository/*` route funnels through
one method, `authorizeArtifacts`: a forwarded principal header (set only by `ApiServer`'s proxy)
wins over the connection's own peer certificate, but this process always re-runs its own
independent `Authorizer.authorize(...)` against RBAC data it reads itself from `gimle-mimir` —
never trusting "arrived already-forwarded" as proof by itself. A `gimle:nodes` certificate identity
(a node agent) may only ever `READ`, and only a full coordinate its node currently holds a live
assignment for (deployment instance, job run, daemonset, or statefulset — walked via
`nodeHasAssignmentFor`); it can never push, delete, or enumerate the catalog. A `Permission` may
additionally be scoped to a single `moduleId` — Andvari's stand-in for `FafnirServer`'s tenant
scope, since it has no separate tenant dimension of its own — so e.g. a CI service account can be
granted push rights to one module without the whole store. Every push/delete decision is
dual-audited: an SLF4J line plus a durable `AppendAuditEvent`; reads are not, since pulls are the
high-volume path and disclose only what a deployment manifest already references.

Andvari also carries its own operator console session story, lifted verbatim from `FafnirServer`:
`/auth/login`, `/auth/logout`, `/auth/session` against store-held `Account` records, a
`gimle_andvari_session` cookie (`HttpOnly`, `SameSite=Strict`, `Secure` under TLS) signed with its
own key under the data root, and `resolvePrincipal`'s three-tier fallback (forwarded header → peer
certificate → session cookie).

## Entry point (`AndvariMain`)

```
AndvariMain <port> --store-endpoints host1:clientPort1,... --data-root <path>
            [--host <hostname>] [--csr-endpoint <host:port>] [--peer-endpoints host1:port1,...]
```

Same argument shape as `MuninnMain`: `--store-endpoints` reaches `gimle-mimir` for RBAC/assignment
data, `--data-root` is where pushed jars live, `--csr-endpoint` is the certificate-rotation
ticker's target, and `--peer-endpoints` opts into `AndvariPeerSync` for a multi-replica topology
(omitted entirely for a single-replica deployment — "one process, one disk," no sync needed).
`AndvariMain` also resolves and serves the bundled `gimle-andvari-console` SPA off the classpath at
startup (`BundledSpa.resolve(..., "andvari-console/index.html")`), and can ship its own metrics and
traces to Muninn via `-Dgimle.andvari.muninnEndpoint`.

## Package layout

- `AndvariMain` — entry point, argument parsing, wiring, shutdown hook.
- `AndvariServer` — the HTTP surface above (operational + Maven repository + console session),
  including TLS material hot-reload on rotation.
- `ArtifactStore` — the immutable, content-addressed jar store.
- `MavenCoordinates` — GAV-path ⇄ `(moduleId, version)` translation for `/repository/**`.
- `AndvariPeerSync` — cross-replica catalog replication.
- `IntegrityScrubber` — optional proactive full-store digest walk.
- `ArtifactRetentionSweeper` — optional scheduled version retirement.
