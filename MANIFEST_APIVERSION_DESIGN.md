# Manifest `apiVersion` & the registry-only `v1` workload manifests — design proposal

## The problem this closes

A workload manifest's `artifactPath` is a bare filesystem path string that travels, verbatim,
from the manifest file through `gimle apply -f`, the control plane's admission check, the stored
spec, and an `InstanceAssignment`, until some *other* process — the control plane validating it,
a node agent installing it — finally calls `Path.of(artifactPath)` and reads it. A **relative**
path in that string is therefore resolved against whichever process happens to be reading it, and
that process's working directory is an accident of how the cluster was launched:

- Every checked-in example (`gimle-examples/*/deployment.yaml`, all of `orders-platform`)
  declares a repo-root-relative path like
  `gimle-examples/greeter-provider/target/greeter-provider-0.1.0-alpha.2.jar`. That resolves only
  because `mvn gimle:controlplane` and `scripts/run-local-cluster.sh` happen to launch
  `ControlPlaneMain` from the repo root.
- The real distribution path (`hilmir up`, exactly as an operator runs it) launches the control
  plane from wherever `hilmir up` was invoked — a deploy directory, not a source checkout — and
  every one of those manifests then fails to place. The QA log records this as a standing
  friction item: *"relative `artifactPath` silently breaks the moment a cluster isn't launched
  from the repo root"*, deliberately left open because "a real fix is invasive."

The same QA entry already names the steady-state answer: the Andvari coordinate-only flow
(`artifactPath` omitted, `module: {name, version}` resolved from the artifact registry) sidesteps
the whole problem class — there is no path to resolve, on any machine, ever. What's missing is a
way to *move* manifests to that flow without breaking every existing example, script, and test
that still names a local path.

That migration mechanism is the real subject of this design: Kubernetes-style **`apiVersion`
on manifest kinds**. A manifest that doesn't declare one gets the **alpha** version of its kind —
bit-for-bit today's schema and semantics, so every current example keeps working untouched. A
manifest that declares one gets exactly that version — and the first new version introduced this
way, `v1` of the workload kinds, removes `artifactPath` outright and makes the artifact registry
the only resolution path.

### A note on scope vs. the request as worded

The request names "a new version of the **ArtifactSet** kind" as the first user of `apiVersion`.
The field actually being deprecated — `artifactPath` — is a field of the **five workload kinds**
(`Deployment`, `Job`, `CronJob` via `jobTemplate`, `DaemonSet`, `StatefulSet`), not of
`ArtifactSet`: an `ArtifactSet` entry's `artifact:` path is a local build output being pushed
*into* the registry, is inherently local by construction, and is already resolved against the
manifest file's own directory (`ArtifactSetManifestParser.resolvePath`), so it never had the
cwd-relative failure mode. This design therefore versions the **whole `gimle apply -f` manifest
family at once** — all six kinds get the `apiVersion` mechanism; the artifactPath deprecation
lands where the field lives, in the workload kinds' new `v1`; and `ArtifactSet` is promoted to a
`v1` of its own (schema unchanged) as the mandated on-ramp into the registry that workload `v1`
now requires. This interpretation has been reviewed and confirmed; implementers should treat the
per-kind version catalog below as settled.

## Goals

- `apiVersion:` is an optional top-level field on every kind `gimle apply -f` accepts.
- **Absent `apiVersion` ⇒ the kind's alpha version** (`v1alpha1`), which is defined as *exactly
  today's behavior*. Every existing manifest in the repo — examples, smoke-test fixtures,
  Holmgang topologies, Surtr templates — parses and behaves identically without touching a file.
- **Present `apiVersion` ⇒ exactly that version**, or a loud, structured rejection naming the
  kind's supported versions. Never a silent fallback to some other version.
- Workload-kind `v1` **rejects `artifactPath`** with an error that states the migration (push via
  `gimle artifact push` / `kind: ArtifactSet`, deploy by coordinate), making the registry the
  enforced path.
- Using `artifactPath` under `v1alpha1` still works but is **visibly deprecated**: a warning
  surfaced back to the person running `gimle apply`, not only a control-plane log line — the QA
  finding's core complaint was "zero surfaced diagnostic."
- Versioning is a **parse-time concern only**. One internal spec model (`WorkloadSpec` and
  friends) stays latest-shaped; nothing about stored state, codecs, assignments, the agent↔worker
  protocol, or any API payload changes shape.

## Non-goals

- **No API groups.** Kubernetes writes `apps/v1`; Gimlé writes bare `v1`. Gimlé is deliberately
  not Kubernetes-API-compatible, has a single first-party API surface, and has no extension/CRD
  mechanism a group would namespace. A group segment today is pure ceremony.
- **No version conversion webhooks / round-tripping.** There is exactly one internal model; a
  version is a parse ruleset, not a stored representation. Nothing ever converts `v1` ↔
  `v1alpha1` objects because neither exists past the parser.
- **No change to alpha's relative-path semantics.** An alpha `artifactPath` stays
  reading-process-cwd-relative, exactly as today (documented, not fixed) — the remedy for the
  relative-path problem is migrating to `v1`, not new resolution rules bolted onto a deprecated
  field. See "Alternatives considered."
- **No versioning (yet) of resources outside the `apply` family.** `Service`/`NetworkPolicy`
  YAML files in the examples are documentation of `POST /services` bodies, not `gimle apply`
  kinds; `gimle-module.yaml` (the in-jar descriptor) and `gimle-entrypoint.yaml` are separate
  formats with their own evolution story. The `ApiVersion` type introduced here is where they
  would plug in if they ever need a second version — nothing more is built for them now.
- **No removal of `v1alpha1`, in this change or on any planned schedule.** Unversioned manifests
  keep alpha behavior indefinitely and `v1` is explicit opt-in only (see "Lifecycle").
- **No auto-push tooling for `v1`.** An author opting into `v1` is expected to push the jar to
  the registry first (`gimle artifact push`, `kind: ArtifactSet`, or `mvn gimle:artifactset-push`)
  before applying the manifest; dev loops and tests that don't want that round trip simply stay
  on unversioned (alpha) manifests.

## The `apiVersion` field

```yaml
apiVersion: v1          # optional; omitted ⇒ v1alpha1, the kind's alpha
kind: Deployment
name: greeter-provider
module:
  name: com.gimle.examples.greeter-provider
  version: 0.1.0-alpha.2
replicas: 2
```

Rules, in the order the parser applies them:

1. `kind` is read first, exactly as today (`ManifestParser` server-side, `GimleCli.handleApply`'s
   client-side peek). `apiVersion` never influences kind dispatch — a version selects a *ruleset
   within* a kind, it doesn't select the kind.
2. `apiVersion`, when present, must be a non-blank string; anything else is rejected the same way
   a blank `artifactPath` already is (`'apiVersion' must be a non-blank string when present --
   omit it entirely for the kind's alpha version`). Matching is exact and case-sensitive:
   `V1`/`v1 ` are unknown versions, not lenient matches.
3. Absent ⇒ `v1alpha1`, **permanently**. This is a stable contract, not a "latest" pointer:
   an unversioned manifest always keeps the current (alpha) behavior, and opting into `v1` — or
   any future version — always requires declaring it explicitly. An unversioned manifest can
   never silently change meaning under a manifest author.
4. Present but not in the kind's supported set ⇒
   `unsupported apiVersion 'v3' for kind Deployment -- supported: v1alpha1 (default when
   omitted), v1`. Same failure shape for a version that exists for *some other* kind but not this
   one.

### The version catalog introduced by this change

| Kind | `v1alpha1` (default when omitted) | `v1` |
|---|---|---|
| `Deployment` | Today's schema, unchanged. `artifactPath` accepted, **deprecated** (warning). | Identical schema **minus `artifactPath`**: the key's presence is rejected. `module: {name, version}` always resolves via the artifact registry. |
| `Job` | Same as above. | Same as above. |
| `CronJob` | Same, for `jobTemplate.artifactPath`. | Same, for `jobTemplate.artifactPath`. |
| `DaemonSet` | Same as Deployment. | Same as Deployment. |
| `StatefulSet` | Same as Deployment. | Same as Deployment. |
| `ArtifactSet` | Today's schema, unchanged. No deprecations — its `artifact:` paths are local push inputs, already manifest-file-relative. | **Schema identical to alpha** — a straight promotion marking the shape stable. Exists so the whole family shares one versioning story and so docs/generators can uniformly say "declare `apiVersion: v1`". |

`v1` of a workload kind is deliberately *only* the `artifactPath` removal — no opportunistic
renames or restructuring ride along. A version bump that changes one thing is auditable; one that
changes five is a migration project.

The `v1` rejection error is the migration doc in miniature:

```
'artifactPath' is not accepted in apiVersion v1 -- push the jar to the artifact
registry (gimle artifact push, or kind: ArtifactSet for a set) and let
module: {name, version} resolve it from there; only v1alpha1 manifests
(deprecated) may name a local path
```

The `v1alpha1` deprecation warning, surfaced to the CLI user (see plumbing below):

```
warning: 'artifactPath' is deprecated and resolved against the reading process's
own working directory, not this manifest file -- omit it and push the artifact
to the registry instead (rejected outright in apiVersion v1)
```

### Why `v1` and not `v1beta1`

The registry flow is not experimental: it is implemented end to end, smoke-tested against a real
cluster (`AndvariRegistryIT`), and already the documented recommendation for anything beyond a
single-machine dev loop. Under this project's no-backward-compat convention there is no audience
for an intermediate beta rung whose only purpose is to soften a break for external users that
don't exist. The ladder this repo actually needs is two rungs: "the alpha shape we're migrating
off" and "the shape we mean." (`v1beta1` remains available as a token if a future kind genuinely
wants a probation rung.)

## Where the changes land

Versioning is parse-time-only, so the touched surface is small and almost entirely inside the two
existing parse entry points.

**`gimle-core` — `com.gimle.core.manifest.ApiVersion` (new).** A small enum-with-parser, the
`ArtifactReference`-style "one place that defines the convention":

- `V1ALPHA1("v1alpha1")`, `V1("v1")`; `token()`; `parse(String)`.
- `ApiVersion.of(Map<?, ?> root, Set<ApiVersion> supported, String kind)` — reads the optional
  top-level key, applies rules 2–4 above, throws `GimleManifestException` (which `gimle-core`
  already owns) on violation. Both dispatchers (`gimle-mimir`'s and `gimle-module`'s, below) call
  this one helper so the error texts and defaulting can never drift apart.

**`gimle-mimir` — `manifest` package.**

- `ManifestParser.parse` resolves the `ApiVersion` right after `kind`, and each
  `*ManifestParser.parseRoot(Map)` grows an `ApiVersion` parameter. The per-kind parsers stay
  independently usable for their kind-agnostic unit tests, exactly as their javadoc promises
  today — tests pass the version explicitly.
- `ManifestFields.optionalArtifactPath(Map, ApiVersion, WarningSink)` becomes the one shared
  place both new behaviors live (it is already "the one shared reading of `artifactPath` across
  every workload kind"): under `V1` a *present* key throws the rejection above (note: presence of
  the key, not just a non-blank value — `artifactPath: ""` is as rejected in `v1` as a real
  path); under `V1ALPHA1` a local-path value emits the deprecation warning and otherwise behaves
  exactly as today. `CronJobManifestParser` threads the same call through `jobTemplate`.
- `ManifestParser.parse` returns a `ParsedManifest(WorkloadSpec spec, List<String> warnings)`
  record instead of a bare `WorkloadSpec`, so deprecation warnings travel with the parse result
  instead of dying in a log file. (`WarningSink` above is just the accumulating list behind that
  record — a parser-internal detail, not a public callback type.)
- `WorkloadSpec` and every stored record are **untouched**. The version is not persisted: a spec
  parsed from a `v1` manifest is indistinguishable from one parsed from a coordinate-only alpha
  manifest, which is exactly the hub-and-spoke property that keeps codecs, Raft log entries,
  rollback history, and the console API stable. (If a future version ever changes *semantics*
  rather than just accepted input, persisting the version becomes that design's problem — not
  speculatively this one's.)

**`gimle-controlplane` — `ApiServer`.** The manifest-accepting handlers
(`PUT /deployments/*` and siblings) fold `ParsedManifest.warnings` into the existing JSON success
body as a `"warnings": [...]` array, present only when non-empty — the same idea as Kubernetes'
`Warning` response headers, riding the body since Gimlé's CLI already parses it. Each warning is
also SLF4J-`warn`ed server-side. No admission change: the coordinate-vs-local-path branch
(`ArtifactResolver`, the Andvari `HEAD` pre-check) already handles both states; `v1` merely
guarantees admission only ever sees the coordinate state.

**`gimle-cli`.**

- `handleApply`'s dispatch is unchanged (kind-routed, version-blind, per rule 1).
- Every apply/PUT path that prints a success row also prints each entry of a `"warnings"` array
  in the response to **stderr** as `warning: ...` — stdout's `--output json` contract stays
  clean. This is the piece that turns the control-plane-log-only diagnostic the QA finding
  complained about into something the operator actually sees at the moment they can act on it.
- `ArtifactSetCommand` needs no version logic of its own — it flows through the parser below.

**`gimle-module` — `ArtifactSetManifestParser`.** Calls the same `ApiVersion.of(...)` helper with
supported set `{V1ALPHA1, V1}`; both versions currently select the identical parse path. This is
the whole "new version of the ArtifactSet kind": the schema is promoted, the mechanism is
exercised client-side as well as server-side, and the parser's existing "ignorant of `kind:`"
contract is preserved (it reads `apiVersion`, still never `kind`).

**`gimle-maven-plugin` — `ArtifactSetMojo`.** The generated `artifactset.yaml` pins an explicit
`apiVersion: v1`. Generators always pin: a generated file that leans on the default would
silently change meaning if the default ever moves, and the generator is regenerated output — the
one place pinning costs nothing.

**`gimle-console`.** Optional, thin: `HttpDeploymentsRepository` (and siblings that submit
manifests) surface a response's `warnings` as a toast. Nothing else — the console never composes
manifests itself.

**`gimle-docs`.** At implementation time (same change, per convention):
`reference/manifest-schema.md` gains an "apiVersion" section up top plus an `apiVersion` row in
each kind's field table and the `v1`-vs-`v1alpha1` `artifactPath` difference in the existing
`artifactPath` rows; `reference/cli-reference.md`'s apply section notes the stderr warnings;
`reference/maven-plugin-goals.md` notes the pinned version in generated manifests.

**Requirements traceability.** At implementation time: two new entries starting at the next free
ID (`GIMLE-609`: optional `apiVersion` with alpha defaulting and unknown-version rejection;
`GIMLE-610`: workload `v1` rejects `artifactPath` / registry-only resolution, plus the
deprecation-warning surfacing) added to `requirements-matrix.json` and `rtm.json`
(`coverage: "Covered"` only once a real Holmgang `.feature` scenario exercises them — the
`registry-deploy.feature` topology is the natural host: apply a `v1` manifest carrying
`artifactPath`, assert the structured rejection; apply the coordinate-only `v1` twin, assert
`ACTIVE`), then `python3 scripts/generate_requirements_docs.py`.

## What deliberately does not change

- **Every current example, script, fixture, and topology works untouched.** None declares
  `apiVersion`, so all get `v1alpha1` — today's semantics to the byte. The only observable
  difference is the new deprecation warning on stderr wherever `artifactPath` is used, which is
  the point.
- Stored state, codecs, `InstanceAssignment`, the agent↔worker protocol, Andvari, the pull
  cache, the scheduler, all reconcilers.
- `ArtifactReference` and its blank-means-registry convention — `v1` simply guarantees the blank
  state at the parse boundary instead of permitting both.
- The CLI's PUT-the-verbatim-bytes property: manifests are still submitted unmodified;
  `apiVersion` is parsed, never rewritten.

## Alternatives considered

- **Fix relative resolution instead of deprecating the field** — resolve `artifactPath` against
  the manifest file's directory client-side (absolutize before PUT), or against a configured
  artifact root server-side. Rejected: the first breaks the verbatim-bytes property, diverges
  from any non-CLI submission path (console, raw curl, Surtr), and bakes the manifest author's
  filesystem layout into the submitted spec; the second adds a config knob whose value must still
  be correct on every node — the same distributed-filesystem coupling Andvari exists to remove.
  Both invest in the field being deprecated.
- **Hard-remove `artifactPath` now, no versioning** — the project's no-backward-compat
  convention would normally demand exactly this. Rejected here only because the request
  explicitly requires current examples to keep working during the migration; `apiVersion` is the
  mechanism that makes the eventual clean break loud instead of silent.
- **A separate kind name (`DeploymentV2`) per revision** — rejected; kind proliferation, and
  Kubernetes precedent is versioned same-kind for good reason (one kind, one identity, one
  dispatch).
- **Group-qualified versions (`gimle.io/v1`)** — rejected, see Non-goals.
- **Defaulting absent `apiVersion` to the newest version** — rejected; it makes every future
  version bump retroactively rewrite the meaning of every unversioned manifest. Alpha-as-default
  is what lets the examples keep working with zero edits.
- **Persisting the submitted apiVersion in the stored spec** — rejected as speculative while
  versions differ only in accepted input, not semantics; nothing would read it back.

## Lifecycle (settled decisions)

This design's slice is the mechanism plus `v1`. The lifecycle beyond it is deliberately modest:

- **`v1alpha1` stays, indefinitely, as what an unversioned manifest means.** There is no planned
  removal and no planned flip of the default: `v1` is and remains explicit opt-in. If alpha is
  ever retired, that is its own future design — `apiVersion` simply guarantees such a break
  would announce itself with a structured error instead of a silent misparse.
- **Dev loops and tests keep using unversioned (alpha) manifests.** Nothing in-repo is forced
  through a registry push to iterate or test; the smoke-test and local-dev fixture patterns are
  unaffected. An author who opts into `v1` takes on the push-first step (`gimle artifact push`,
  `kind: ArtifactSet`, `mvn gimle:artifactset-push`) as part of that choice — no auto-push
  tooling is added on their behalf.
- **Alpha's relative-`artifactPath` behavior is deprecated, not fixed.** A real fix was judged
  invasive (see "Alternatives considered"), and that judgment is precisely the motivation for
  deprecating the field: the supported remedy is the warning plus migration to `v1`, never new
  resolution rules on the alpha field.
- **Migrating the in-repo examples to `v1`** (each gaining an `artifactset.yaml` push step and a
  coordinate-only, `apiVersion: v1` workload manifest) remains an optional later slice — a
  documentation improvement, not a prerequisite for anything above.

## Testing plan

- **`ApiVersion` unit tests** (`gimle-core`): defaulting, exact-match parse, blank/non-string
  rejection, unsupported-version message listing the kind's supported set.
- **Per-kind parser tests** (`gimle-mimir`): for each of the five workload kinds — unversioned ≡
  explicit `v1alpha1` (identical spec, field for field); `v1alpha1` + `artifactPath` parses *and*
  yields the deprecation warning; `v1` coordinate-only parses to `REGISTRY_COORDINATE`; `v1` +
  `artifactPath` (and `artifactPath: ""`) rejected with the migration-pointing message; CronJob's
  `jobTemplate` variant of each. `ManifestParserTest` covers unknown-version and
  malformed-`apiVersion` dispatch.
- **`ArtifactSetManifestParserTest`**: unversioned ≡ `v1alpha1` ≡ `v1`; unknown version rejected.
- **`ApiServerTest`**: a deprecated-field apply returns `warnings` in the success body; a clean
  apply omits the key; a `v1`+`artifactPath` apply maps to the structured error response, not a
  stack trace.
- **CLI tests**: warnings land on stderr, never in `--output json` stdout; `ArtifactSetMojoTest`
  asserts the generated manifest pins `apiVersion: v1`.
- **Holmgang** (`-Pvalidation`): the `registry-deploy.feature` additions described under
  requirements traceability, which is also what lets the two new RTM entries claim `Covered`.
