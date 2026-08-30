# Galdr — custom Kinds for Gimlé, design proposal

## The gap this closes

Gimlé's declarative vocabulary is closed. The platform understands exactly the kinds its own
source code names — `Deployment`, `Job`, `CronJob`, `DaemonSet`, `StatefulSet`, plus the
JSON-posted `Service` and `NetworkPolicy` — and teaching it one more is a cross-cutting platform
change. Today a new kind touches, at minimum:

| Touch point | Where | What has to change |
|---|---|---|
| Manifest dispatch | `ManifestParser` (gimle-mimir) | New case in the five-kind `switch`, plus a new `*ManifestParser` |
| Replicated state | `StateMutation` | New `record` variants in a sealed interface that already holds 62 |
| Wire format | `RaftCodec` / `DomainCodec` | New hand-assigned byte tags (`MUT_* = 0…61` today) and field codecs |
| Snapshotting | `StateSnapshot` / `StateStore` | A new component in a 38-component record, plus `snapshot()/restore()/clear()` |
| Store RPC | `StoreReader` / `StoreClient` / `StoreRpc` | New read methods mirrored across the interface, client, and RPC handler |
| API server | `ApiServer` (gimle-controlplane) | A new context pair and handler wiring |
| RBAC | `ResourceKind` (gimle-core) | A new enum value, picked up by `BuiltinRoles` |
| CLI | `GimleCli.handleApply` | A new case in the client-side kind `switch`, plus a command class |
| Docs / RTM | `manifest-schema.md`, `rtm.json`… | Reference page section, requirement entries, regenerated views |

That cost is right for *platform* kinds — each one is load-bearing infrastructure. But it means
neither Gimlé's own developers nor its users can model a domain concept declaratively without
patching the platform. There is no way to say "my cluster has a notion of a `Greeting`" — let
alone "…of a `FeatureFlag`, a `TenantOnboarding`, a `BackupSchedule`" — and get storage, an API,
RBAC, CLI/console visibility, and a reconciliation loop for it. Kubernetes closed the same gap
with CustomResourceDefinitions, and CRDs-plus-operators became the dominant way that ecosystem
extends itself.

### Reconciling with the spec's "No CRDs" non-goal

`gimle-PROJECT-v2.md` lists "Not Kubernetes-API-compatible. No CRDs, no kubectl interop, no OCI
images" as a non-goal. That bullet forbids *compatibility* — `apiextensions.k8s.io`, OpenAPI v3
schemas, kubectl interop — not the capability. This is the same move `ANDVARI_DESIGN.md` made
against "no OCI images": Andvari is a first-party artifact registry, not an OCI registry. Galdr is
a first-party extension mechanism, not a CRD implementation. Nothing here parses or serves a
Kubernetes API shape.

## Goals

- A new kind is **declared, not compiled**: one `kind: KindDefinition` manifest applied through
  the ordinary `gimle apply -f` path teaches every control-plane replica the new kind — no
  rebuild, no restart, no platform patch.
- Custom resources get the full first-class treatment their platform siblings get: **Raft-replicated
  storage** (survives restarts, visible from every control-plane replica), **schema-validated
  admission** with structured 400s, **tenant scoping**, **RBAC**, **audit rows**, and **CLI +
  console visibility**.
- Reconciliation logic runs where user code already runs — in **hosted operator modules** deployed
  like any workload — never in the control plane. The control plane's role stays what it is
  everywhere else: store desired state, validate it, serve it.
- Operators follow the platform's universal posture: **level-triggered polling**, full-set reads,
  convergence from any starting state — the same shape as `NetworkPolicyRelay`, `BifrostProxy`,
  and Skald.
- The store stays dumb: `gimle-mimir` gains **generic** mutations for definitions and instances —
  a fixed, small cost paid once — and never learns another byte tag per user-defined kind.
- Operators authenticate as first-class RBAC subjects using the platform's **existing**
  workload-identity mechanism (`/workload-tokens`) — no new identity machinery is introduced.
- The whole mechanism composes with what exists: `apiVersion` defaulting, the two-check
  re-tenanting guard, the audit trail's string-typed `resourceKind`, the agent relay's
  whitelisted-path design, and the shipped workload-identity tokens as operator credentials.

## Non-goals

- **No Kubernetes API compatibility.** No `apiextensions` shapes, no OpenAPI/JSON-Schema
  dialects, no API groups in the Kubernetes sense (see "Kind naming and prefixes" below for what
  Galdr does instead).
- **No user code in the control plane, ever.** No validation webhooks, no admission plugins
  loaded from user jars, no conversion hooks. The only validation a KindDefinition can express is
  the declarative schema below — if a kind needs Turing-complete validation, that logic belongs in
  its operator, reported through `status`.
- **No watch/subscribe API.** Nothing in Gimlé watches; operators poll. A future long-poll or
  `?sinceRevision=` optimization would be its own design, and level-triggered operators written
  against this one would be unaffected by it.
- **No multi-version schemas or conversion.** A KindDefinition declares one schema. Its instances
  default to `v1alpha1` under the existing `ApiVersion` rules; a second schema version per kind is
  deliberately deferred until a real kind needs one.
- **No finalizers or garbage-collection graph.** Deleting a custom resource removes the record;
  whatever the operator materialized from it is the operator's job to notice and clean up on its
  next level-triggered pass (it must already handle "resource absent" to converge from arbitrary
  state).
- **No operator-initiated writes to platform kinds in the first slice — by whitelist policy, not
  missing machinery.** Workload identity already exists; an operator's `svc:` principal *could* be
  RBAC-bound to write Deployments today. What gates it is the agent relay's path whitelist, which
  v1 widens only to custom-resource reads and status writes. Admitting platform-kind writes
  through the relay (the full composite-kind pattern) is one deliberate later decision, not a new
  subsystem — see "Deferred: operators that write platform resources" below.

## Naming: Galdr

A *galdr* is Old Norse for a chanted spell — words that, spoken in the right form, make the world
act. That is precisely what this mechanism is: user-authored words (a YAML manifest) that extend
what the platform itself responds to. The name sits in the project's own register line ("Drakkar,
Þjappa, Skald, Bifrost, **Galdr**, Muninn, Fafnir") and is claimed by no module today.

Galdr is **not a new process kind**. It follows the `gimle-bifrost` precedent — a named capability
embedded in existing processes as a package: `com.gimle.controlplane.galdr` (definition registry,
validator, admission), `com.gimle.mimir` gains only generic records, and the operator-side SDK
lands in `com.gimle.module.galdr`. Resource kind names stay plain English, matching
`Deployment`/`Service`/`ArtifactSet`: the defining kind is `KindDefinition`, and user kinds are
whatever the definition names — with the mandatory prefix described next.

## "Saying hello", end to end

The walkthrough the whole design serves. Three artifacts: a definition, an instance, an operator.

**1 · `greeting-kind.yaml` — applied once by a platform operator**

```yaml
kind: KindDefinition
name: Greeting          # no prefix supplied -- normalized and stored as custom.Greeting
scope: Tenant            # Tenant | Cluster -- Namespaced/Cluster analogue
description: "A greeting this cluster should keep saying"
names:                    # CLI/console nicknames, declared like Kubernetes' spec.names
  plural: greetings
  shortNames: [gr]
schema:
  fields:
    - name: message
      type: string
      required: true
    - name: repeat
      type: int
      default: 1
      min: 1
      max: 100
    - name: tone
      type: enum
      values: [friendly, formal]
      default: friendly
printColumns:              # optional; dotted paths into spec/status for CLI + console tables
  - name: MESSAGE
    path: spec.message
  - name: SAID
    path: status.timesSaid
```

**2 · `hello.yaml` — applied by anyone the RBAC below allows**

```yaml
kind: custom.Greeting     # the stored, prefixed name -- instances always use it
name: hello-world
tenantId: team-a           # required for a Tenant-scoped kind, rejected for Cluster
spec:
  message: "hello"
  repeat: 3
```

**3 · the operator — an ordinary hosted module (full sketch below)**

```
gimle apply -f greeting-kind.yaml
warning: kind name 'Greeting' has no prefix -- stored as 'custom.Greeting'
gimle apply -f hello.yaml             # validated against the schema, stored, audited
gimle set rolebinding greeting-operator-rb \
  --subject user:svc:team-a:greeting-operator --role team-a-greeting-operator
                                       # grants the operator's workload principal its RBAC
gimle get greetings --tenant team-a    # 'greetings'/'gr' resolve via the definition's declared names
NAME          TENANT   GENERATION   MESSAGE   SAID
hello-world   team-a   1            hello     3
```

One resource's life, every hop an existing mechanism:

1. **gimle-cli** — `gimle apply -f hello.yaml` peeks `kind:`, finds no built-in match, and PUTs
   the verbatim bytes to `/resources/custom.Greeting/hello-world` — the same one-peek-then-PUT
   shape `handleApply` uses today.
2. **ApiServer · admission** — looks up the `custom.Greeting` KindDefinition from the store,
   validates the manifest against its schema, applies defaults, runs the existing two-check
   re-tenanting RBAC guard, and appends an audit row.
3. **gimle-mimir** — one generic `StateMutation.PutCustomResource` travels the Raft log —
   canonical-JSON bytes, generation-CAS-guarded like `PutDeployment`. The store never interprets
   the payload.
4. **greeting-operator · hosted module** — on its poll tick, reads the current full set of
   Greetings through the agent-mediated relay. The agent attaches the operator's own minted
   workload token, so the read is authorized as `svc:team-a:greeting-operator` by ordinary RBAC —
   then the module says hello for every resource in the set.
5. **status, back up** — the operator reports `{"timesSaid": 3, "observedGeneration": 1}`; the
   agent forwards it to `PUT /resources/custom.Greeting/hello-world/status` under the same
   workload token, checked against the separate status grant. CLI and console show spec and status
   side by side.

## The schema language

Deliberately small and first-party — a hand-rolled validator in the Skald tradition, not an
OpenAPI subset. It exists to catch shape mistakes at admission with a good error, not to be a type
system. Everything it cannot express is the operator's job to check and report via `status`.

| Type | Attributes | Validation |
|---|---|---|
| `string` | `required`, `default`, `maxLength` | non-null string; length when declared. No `pattern`: a user-supplied regex evaluated at admission is a ReDoS surface on the control plane (the JDK engine backtracks, unlike Kubernetes' linear-time RE2) — format checks belong in the operator |
| `int` / `double` | `required`, `default`, `min`, `max` | numeric YAML scalar; bounds inclusive |
| `bool` | `required`, `default` | true/false only |
| `enum` | `values` (required, non-empty), `required`, `default` | exact, case-sensitive membership |
| `list` | `items` (any type here, incl. `object`), `minItems`, `maxItems` | each element validated against `items` |
| `object` | `fields` (nested field list) | recursive; depth capped (8) at definition admission |

Cross-cutting rules, all enforced when the *KindDefinition itself* is admitted, so a bad schema
can never be stored:

- Unknown keys in an instance's `spec` are **rejected, not ignored** — a typo'd field name fails
  loudly at apply time. (Kubernetes' silent-pruning default is a documented source of pain; Gimlé
  has no compat pressure forcing it.)
- A field is `required` or has a `default`, never both; defaults are applied at admission and
  **persisted**, so the stored spec is always complete and an operator never re-derives defaulting
  logic (see "Definition updates" below for how this stays true across schema changes).
- Instance manifests put user data under a `spec:` block, unlike the flat workload manifests. The
  root level is reserved (`kind`, `apiVersion`, `name`, `tenantId`) so no user schema can ever
  collide with a future reserved field.
- **Size caps at admission**: an instance's canonical `spec` and each reported `status` are capped
  at 256 KiB — these bytes travel the Raft log and live in every replica's snapshot, so an
  uncapped payload is a replicated-storage DoS, not just a big row.

### Kind naming and prefixes

**Every custom kind name carries a dot-separated prefix** — `acme.Greeting`, `billing.Invoice` —
and a definition submitted without one is normalized to the default prefix `custom.` (`Greeting`
→ `custom.Greeting`), announced back to the submitter via the existing `X-Gimle-Warning` response
header. Built-in kinds never contain a dot, so a future platform release can never shadow a custom
kind — the collision class is structurally impossible, without adopting Kubernetes-style API
groups anywhere else. The part after the last dot is `UpperCamelCase`; prefixed names live in one
flat cluster-wide namespace, first definition wins, and a differing re-definition of a live kind
follows the update rules below.

**The definition declares its own CLI names**, Kubernetes' `spec.names` in miniature: an optional
`names: {plural, shortNames}` block. `gimle get greetings` and `gimle get gr` resolve through it;
the full prefixed kind name always works. Plural and short names share one flat namespace with
each other, checked for collision at definition admission.

## Storage in gimle-mimir

The store learns two generic shapes and stays otherwise ignorant. This is the design's central
economy: *the marginal storage cost of a user-defined kind is zero*.

New domain records (gimle-core, wire-transferable like `NetworkPolicyRule`):

```java
// The definition: schema stored as its parsed model, not re-parsed YAML
record KindDefinitionSpec(
    String kindName,          // always prefixed: "custom.Greeting" -- normalized at admission
    KindScope scope, String description,
    KindNames names,          // declared plural/shortNames for CLI + console resolution
    SchemaModel schema, List<PrintColumn> printColumns, long generation)

// An instance: the store never looks inside specJson/statusJson
record CustomResource(
    String kindName, String name, Optional<String> tenantId,
    byte[] specJson,        // canonical JSON, defaults already applied
    byte[] statusJson,      // empty until an operator reports; opaque
    long generation)        // bumped on each spec change, not status
```

New `StateMutation` variants — five tags total, forever:

```java
record PutKindDefinition(KindDefinitionSpec spec, long expectedGeneration)
record RemoveKindDefinition(String kindName)          // refused while instances exist
record PutCustomResource(CustomResource resource, long expectedGeneration)
record RemoveCustomResource(String kindName, Optional<String> tenantId, String name)
record PutCustomResourceStatus(String kindName, Optional<String> tenantId,
                               String name, byte[] statusJson)   // never bumps generation
```

| | |
|---|---|
| **Snapshot** | Two new `StateSnapshot` components — `kindDefinitions` and `customResources` — mirrored in `StateStore.snapshot()/restore()/clear()`. Fixed cost, paid once; user kinds never widen the record again. |
| **Keys** | `StateStore` keeps two maps keyed `kindName` and `kindName + "#" + tenantOrBlank + "#" + name` — the established composite-key pattern (`configKey` is already `tenantId + "#" + key`). |
| **Reads** | `StoreReader` gains `listKindDefinitions()`, `getKindDefinition(kindName)`, `listCustomResources(kindName)`, `listCustomResourcesFor(kindName, tenantId)`, `getCustomResource(kindName, tenantId, name)` — mirrored in `StoreClient`/`StoreRpc` as usual. |
| **Concurrency** | `PutCustomResource` is CAS-guarded on `expectedGeneration`, the exact `PutDeployment` pattern. A lost race surfaces as a **409 Conflict to the client** — the Kubernetes `resourceVersion` posture — and `gimle apply` retries the read-validate-PUT loop itself (bounded, then reports the conflict). The server never retries silently. Re-applying an *identical* canonical spec is a no-op: no mutation proposed, no generation bump — the Andvari identical-re-push rule — so declarative re-applies never cause phantom `generation`/`observedGeneration` churn. Status stays last-write-wins; operators embed `observedGeneration` in their status JSON, and a stale status self-corrects on the next level-triggered pass. |
| **Payload form** | Canonical JSON bytes, produced by the control plane *after* validation and defaulting. Mimir stays a dumb store — the same posture `SecretStore`'s javadoc states: policy lives in the owning process, never in the store. |

Codec cost: five one-byte tags in `RaftCodec`'s table and straightforward `DomainCodec` encoders
(strings, optionals, byte arrays, and a recursive `SchemaModel` encoder). No per-user-kind wire
format exists anywhere.

## API surface & admission

| Route | Methods | Authorization |
|---|---|---|
| `/kinddefinitions` | GET (list) | any authenticated principal — definitions are schemas, not data; authors and the console picker need them (Kubernetes' `system:discovery` posture) |
| `/kinddefinitions/{kind}` | PUT · GET · DELETE | GET: any authenticated principal; PUT/DELETE: `KIND_DEFINITION` · WRITE/DELETE, cluster-scoped |
| `/resources/{Kind}` | GET (list; `?tenant=` filter) | `CUSTOM_RESOURCE` · READ, per-item tenant filter |
| `/resources/{Kind}/{name}` | PUT · GET · DELETE | `CUSTOM_RESOURCE` · WRITE/READ/DELETE, tenant-scoped — WRITE here never covers `/status` |
| `/resources/{Kind}/{name}/status` | PUT | `CUSTOM_RESOURCE` · WRITE under the separate `{kind}/status` qualifier |

Both handlers ride the existing machinery rather than growing parallel plumbing:

- **`dispatchResourceRequest` is mirrored, not reused.** The existing dispatcher is hardwired to
  `ManifestParser` and its `PutResourceAction` is typed to `WorkloadSpec`, so custom resources get
  a generalized twin, `dispatchCustomResourceRequest`, that copies its flow exactly —
  parse-before-authorize, the two-check re-tenanting guard, audit recording, the same error
  mapping — over a generic JSON body. Its sub-route hook (the trick `/deployments/{name}/rollback`
  already uses) is where `/status` lands. The PUT path parses YAML generically (SnakeYAML
  `SafeConstructor`, untrusted-input posture as everywhere), resolves the KindDefinition,
  validates, defaults, canonicalizes.
- **Unknown kind is a 400 with the catalog in the message**, mirroring the `apiVersion` design's
  error style: `unknown kind 'custom.Greetng' -- no KindDefinition with that name; defined kinds:
  custom.Greeting, acme.FeatureFlag`.
- **`apiVersion` composes.** `KindDefinition` itself supports `{v1alpha1}` under the existing
  `ApiVersion.of(...)` helper; instances of a custom kind likewise default to `v1alpha1`. A
  declared version outside the supported set fails with the established message shape. Multi-version
  schemas stay a non-goal.
- **KindDefinition updates re-validate the world before they land.** On a re-PUT, the control
  plane validates *every stored instance* against the new schema: any failure refuses the update
  with 409 and the violator list (no compatibility calculus over nested schemas — the instances
  themselves are the check). If all pass, new defaulted fields are **backfilled** into the stored
  instances in one `Batch` mutation, preserving the invariant that a stored spec is always complete
  — deliberately unlike Kubernetes' read-time defaulting, so what's stored is always exactly what's
  served. `DELETE /kinddefinitions/{kind}` is refused (409) while any instance exists: delete the
  instances first, explicitly.
- **Tenant deletion cascades.** Removing a tenant removes its custom resources along with the rest
  of the tenant's state, following exactly whatever `RemoveTenant` does with that tenant's
  deployments today — verified against the actual behavior at implementation time, with custom
  resources added to the same path rather than growing their own rule.

## RBAC & audit

`ResourceKind` is a Java enum that RBAC, audit, and `BuiltinRoles` iterate — it cannot enumerate
user-defined kinds, and shouldn't try. Two new *platform* values cover the mechanism; a qualifier
covers the kinds:

- `ResourceKind.KIND_DEFINITION` — deliberately its own kind, the `DAEMONSET` reasoning verbatim:
  "may teach the whole cluster a new resource vocabulary" is a consequential grant operators must
  be able to withhold independently. Effectively platform-admin territory.
- `ResourceKind.CUSTOM_RESOURCE` — instances, tenant-scoped like `DEPLOYMENT`. Both values are
  picked up by `BuiltinRoles.CLUSTER_ADMIN` automatically via `values()`.
- **`Permission` gains one optional field, `qualifier`** — meaningful only alongside
  `CUSTOM_RESOURCE`, in two forms. A kind name (`custom.Greeting`) covers that kind's spec CRUD; a
  kind name plus `/status` (`custom.Greeting/status`) covers *only* its status writes — the
  Kubernetes `/status` subresource split, spelled as a qualifier instead of a second resource.
  **Spec-WRITE never implies status-WRITE and vice versa**: a human editor can't stomp what the
  operator reported, and an operator granted only `READ` + `…/status` can't alter desired state.
  Absent qualifier means all custom kinds' specs (so existing roles and the per-tenant
  view/edit/admin templates behave sensibly with zero migration). `Permission#covers(resource,
  verb, tenant)` grows the qualifier check; one new optional field in the role codec.
- **Operators are RBAC subjects like anyone else.** The platform's existing workload-identity
  tokens (see below) resolve to principals named `svc:{tenant}:{deployment}` in group
  `gimle:workloads`, deny-by-default with no implicit grants. A typical operator role is exactly
  two permissions: `CUSTOM_RESOURCE · READ · qualifier custom.Greeting` and `CUSTOM_RESOURCE ·
  WRITE · qualifier custom.Greeting/status`, bound with `--subject
  user:svc:team-a:greeting-operator`. Per-deployment least privilege falls out of machinery that
  already ships — no new access-control concept is introduced anywhere in this design.

Audit needs no schema change at all: `AuditEvent.resourceKind` is already a `String`, so
custom-resource rows record `CustomResource:custom.Greeting` (the handlers pass the qualified
string explicitly; the enum-name path used everywhere else stays untouched) and the existing `GET
/audit?resource=` filter works unmodified. The defense-in-depth pattern is likewise untouched —
any future process re-checking these resources re-runs `Authorizer.authorize(...)` against
store-read RBAC data, exactly as Fafnir, Muninn, and Andvari already do.

## Operator modules

The controller half of the CRD pattern. In Kubernetes it's an operator pod; in Gimlé it is an
**ordinary hosted module** — deployed by manifest, tiered, probed, supervised, self-healed like
anything else. The control plane never runs it, and the platform never even knows a module *is*
an operator: it's just a module that reads custom resources and reports status.

### Identity: the ServiceAccount analogue already ships

Gimlé already has per-workload identity, end to end — `ApiServer.handleWorkloadTokenMint`'s own
javadoc calls it "the ServiceAccount analogue's issuance path." A node's agent mints a token per
`deploymentName#nodeId` under its own mTLS node identity, only for workloads the store currently
assigns to that node; only the token's SHA-256 is replicated (`WorkloadTokenRecord`), the TTL is
an hour with agent-side caching and re-mint, and revocation is one record removal. The agent
*already attaches* the minted token as `Authorization: Bearer` on every `relayControlPlaneRead`
call a module makes, and the control plane resolves it to the principal `svc:{tenant}:{deployment}`
in group `gimle:workloads` — deny-by-default, bindable in RBAC like any user. Galdr invents no
identity machinery: operators authenticate with what exists.

### Reading resources: the relay whitelist grows, authorization is plain RBAC

A worker JVM has no outbound network identity — only its agent does — and
`ModuleContext.relayControlPlaneRead(path)` is the deliberately-generic, agent-mediated,
whitelist-checked hole for exactly this, today admitting only `GET /endpoints/{name}`. Galdr
widens the whitelist by two reads and adds one status write:

- `GET /resources/{Kind}` and `GET /resources/{Kind}/{name}` — relayed with the caller's workload
  token; the control plane authorizes the `svc:` principal with the ordinary
  `Authorizer.authorize(...)` walk against its role bindings. No node-grant special case, no
  agent-side tenant pinning: RBAC is the scoping, and the existing per-item tenant filter on list
  responses does the rest. A module whose principal is bound to nothing reads nothing.
- `PUT /resources/{Kind}/{name}/status` via a narrow
  `ModuleContext.reportResourceStatus(kindName, name, statusJson)` — status only, never spec,
  forwarded by the agent with the same workload token and checked against the separate
  `{kind}/status` grant. Modules get only these typed methods; the raw relay path surface does not
  widen beyond the whitelist, so there are no query parameters to strip or spoof.

Two consequences worth stating plainly. An operator's reach is exactly its role binding — one
tenant, several, or a cluster-wide grant — decided by whoever binds it, not hardcoded by the
mechanism. And since token minting refuses untenanted workloads (a workload identity exists to
carry tenant-scoped RBAC), **an operator deployment must itself be tenanted**, even when the kind
it reconciles is `Cluster`-scoped — its grant simply covers the untenanted resources.

### The SDK is a for-loop, not a framework

`com.gimle.module.galdr` ships a thin harness with `ServiceReconciler`'s exact temperament — full
recompute per tick, per-item try/catch, absence is a valid state:

```java
public final class GreetingOperator implements ModuleLifecycleHooks {
  private GaldrOperatorLoop loop;

  public void onStart(ModuleContext ctx) {
    loop = GaldrOperatorLoop.start(ctx, "custom.Greeting", Duration.ofSeconds(5), resources -> {
      for (GaldrResource r : resources) {              // the full current set, every tick
        int repeat = r.spec().getInt("repeat");
        for (int i = 0; i < repeat; i++) log.info("{}", r.spec().getString("message"));
        r.reportStatus(Json.obj("timesSaid", repeat,
                                "observedGeneration", r.generation()));
      }
    });
  }
  public void onStop(ModuleContext ctx) { loop.close(); }
}
```

The loop owns nothing clever: poll, hand over the set, catch per-resource failures, never crash
the tick. Backoff on relay errors, a virtual thread, and that's the framework. Convergence from any
starting state is inherited, not engineered.

### Deferred: operators that write platform resources

The full composite pattern (a `Greeting` whose operator creates a `Deployment` per instance) needs
no new machinery at all: an operator's `svc:` principal could be RBAC-bound to `DEPLOYMENT ·
WRITE` today. What v1 deliberately withholds is the *relay whitelist* — it admits custom-resource
reads and status writes, nothing else — because widening a hosted module's reach into platform-kind
writes deserves its own considered decision (blast radius, audit expectations, whether some kinds
stay off-limits), not a side effect of this design. When taken, it is roughly one whitelist rule
plus that policy discussion.

## CLI

- **`gimle apply -f`** — `handleApply`'s `switch` gains a default branch: an unrecognized kind
  routes to a generic `CustomResourceCommand` that PUTs the verbatim bytes to
  `/resources/{Kind}/{name}` (kind and name peeked exactly as today; the server owns validation).
  `kind: KindDefinition` routes like any built-in, to `/kinddefinitions/{name}`. The current hard
  `unknown manifest kind` error moves server-side, where the definition catalog lives. A lost CAS
  race (409) triggers a bounded client-side retry of the read-validate-apply loop, surfacing the
  conflict only if it's exhausted.
- **`gimle kinds`** — lists KindDefinitions: prefixed name, declared plural/short names, scope,
  instance count, description.
- **`gimle get/delete <kind> [name]`** — the existing noun dispatch falls through to the generic
  path for custom kinds, resolving the noun against the definitions catalog in a fixed order:
  exact prefixed kind name, then declared `plural`, then declared `shortNames` (Kubernetes'
  discovery-based resolution, minus discovery — one `GET /kinddefinitions` feeds it, cached per
  invocation). Tables render `NAME · TENANT · GENERATION` plus the definition's `printColumns`,
  resolved by dotted path (`status.timesSaid`) — a ten-line resolver, not a JSONPath engine. `-o
  json` emits spec and status verbatim.
- **`gimle describe`-equivalents stay free**: audit rows (`gimle audit --resource
  CustomResource:custom.Greeting`) work day one via the string-typed filter.

## Console

One new screen, *Custom Resources*, in `gimle-console`: a kind picker fed by `GET
/kinddefinitions`, an instance table honoring `printColumns`, and a detail pane showing spec and
status YAML side by side with the generation/observedGeneration pair made visible — the
at-a-glance "has the operator caught up" signal. Repository-interface + Zustand store +
Mock/Http pair, the pattern all four consoles already follow. Deliberately read-only in the first
slice; authoring YAML stays in the CLI where apply semantics live.

## What deliberately does not change

- The scheduler, every existing reconciler, the fabric, gossip, and the agent↔worker protocol (two
  additive control messages aside: the relay's new paths and `reportResourceStatus`).
- The five workload kinds, their parsers, and `ManifestParser` itself — custom instances never
  pass through it; its five-case switch stays exactly five cases.
- `RaftCodec`'s tag discipline: five new tags now, zero per user kind, ever.
- The no-watch, level-triggered posture; the no-user-code-in-the-control-plane invariant; the
  "mimir is a dumb store" boundary.
- The workload-token mechanism (`/workload-tokens`, `WorkloadTokenRecord`, the agent's
  bearer-token attachment on relayed calls) — reused byte for byte as the operator identity, zero
  changes.
- Kubernetes non-compatibility — no API groups (the dot prefix is a naming rule inside Gimlé's one
  flat kind namespace, not a group segment in URLs or versions), no OpenAPI, no kubectl interop,
  exactly as the spec demands.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Layer instances over `ConfigEntry`** with a synthetic key prefix (the `SecretStore`/`ConfigMapCodec` trick, a third time) | Proven for value-shaped overlays, wrong for a core extension surface: custom resources need cross-tenant listing by kind, generation CAS, a status sub-document, and RBAC-filtered collection reads — all awkward through a flat tenant-keyed KV. First-class generic records cost five codec tags. |
| **Schema as a Java interface** in a user jar, loaded by the control plane | Violates the platform's clearest invariant: user code runs in workers, never in the control plane. Also reintroduces exactly the artifact-distribution problem Andvari solved, but for the control plane. |
| **Embed an OpenAPI/JSON-Schema library** | A large third-party surface for a platform that hand-rolls DNS; drags in the K8s-compat gravity the non-goal exists to resist. The schema language above covers the realistic validation set; everything else is operator logic. |
| **A watch/long-poll API for operators** | Nothing in Gimlé watches; every consumer converges by polling full sets. Introducing an edge-triggered path for the least-trusted consumers first would invert the platform's hardest-won correctness property. |
| **Per-kind `ResourceKind` values** (dynamic enum, or string-typed RBAC everywhere) | The enum is load-bearing (BuiltinRoles, audit filters, defense-in-depth re-checks in four processes). The `CUSTOM_RESOURCE`-plus-qualifier design gets per-kind granularity with one optional field instead of a type-system rewrite. |
| **Operators as control-plane plugins** (jars on the CP classpath) | Same invariant violation, plus it forfeits everything modules already get free: tiering, supervision, self-healing, probes, redeploy, leak detection. |
| **Authorizing operators via node identity** (a Fafnir-style "node with an active assignment may read the tenant's resources" rule, agent-pinned tenants, an `operatedBy:` allowlist on the definition) | An earlier draft of this design — superseded on discovering the workload-token machinery already ships. Node-identity access is per-tenant-per-node, so any module in the tenant could read every kind and forge any status; `svc:`-principal RBAC gives per-deployment least privilege with mechanisms that already exist, and needed none of the three stopgaps. |
| **Unprefixed kind names** with only an admission-time collision check against today's built-ins | A later platform release adding a built-in with the same name would silently shadow the custom kind. The mandatory dot prefix (defaulted to `custom.`) makes that collision structurally impossible — the one benefit of API groups, bought without adopting groups. |
| **Accepting Kubernetes CRD YAML** for definitions | Explicitly forbidden by the spec's non-goal, and the shapes genuinely don't fit (no API groups, no namespaces, different scoping model). A migration-shaped feature with no migrating users. |

## Testing plan

- **gimle-mimir**: codec round-trips for all five mutations and both records; snapshot/restore
  including populated definition and instance maps; CAS conflict on `PutCustomResource`;
  `RemoveKindDefinition`-while-instances-exist refused at the store level too (defense in depth).
- **Schema validator**: table-driven happy/failure pairs per type; unknown-key rejection; default
  persistence; depth cap; required-xor-default; the 256 KiB spec/status caps; the full
  corrupt-manifest failure-path battery the conventions demand.
- **ApiServer**: admission against a live definition; prefix normalization (`Greeting` →
  `custom.Greeting` with the `X-Gimle-Warning`); unknown-kind 400 with catalog; definition re-PUT
  re-validating all instances (409 + violator list) and backfilling new defaults; CAS race → 409 to
  the client; identical-spec re-apply as a no-op (no generation bump); tenant-scope enforcement
  both ways (Tenant kind without `tenantId`, Cluster kind with one); status PUT never bumping
  generation; audit rows carrying `CustomResource:{kind}`.
- **Authorizer**: qualifier matching (absent = all kinds' specs; `{kind}` = one kind's spec;
  `{kind}/status` = only its status), spec-WRITE never covering status and the reverse, a `svc:`
  workload principal authorized purely by its bindings.
- **Agent/worker**: relay whitelist additions (path traversal rejected, workload token attached),
  `reportResourceStatus` round-trip over the control channel; a token-mint failure surfacing as a
  relay error, not a hang.
- **CLI**: noun resolution order (exact prefixed name → plural → shortName), the bounded apply
  retry on 409 with the conflict surfaced when it exhausts.
- **Operator SDK**: convergence-from-arbitrary-state — resources present before the operator
  starts, deleted mid-loop, redefined mid-loop; a tick surviving one poisoned resource.
- **Holmgang** (`-Pvalidation`): a `custom-kinds.feature` — define `Greeting` against a live
  cluster, apply an instance, deploy a real operator module, assert the status lands and survives
  a control-plane bounce. This scenario is what lets the RTM rows claim `Covered`.
- **Requirements traceability**: next free IDs are GIMLE-654+ — roughly one row each for the
  KindDefinition mechanism, schema-validated admission, RBAC qualifier, operator status loop —
  entered in `requirements-matrix.json`/`rtm.json` with the four views regenerated, per convention.

## Implementation phases, in order

Six phases, strictly ordered: each phase's exit demo is the next phase's precondition, and no
later phase forces rework of an earlier one. Phases 1–5 are the build; phase 6 is deliberately
not a build phase at all — it is a black-box, real-user validation pass over the finished
feature, run by testers who never open the sources, and the feature is not "done" until it has
survived that pass (see "Phase 6" below for its full design). Automated coverage (unit,
integration, Holmgang) lands inside phases 1–5 per the testing plan above; phase 6 exists
because automated suites verify what the design *says*, while a user-perspective pass verifies
what a person actually *experiences* — the two catch disjoint failure classes, as every prior
QA pass on this platform has demonstrated.

| Phase | Contents | Builds on | Exit criterion — the demo |
|---|---|---|---|
| **P1 · store + schema** | `KindDefinitionSpec`/`CustomResource` records, five mutations, codecs, snapshot components, StoreReader/Client/Rpc reads; the schema model + validator with its full table-driven failure battery | — | Codec round-trips, snapshot/restore, CAS conflict, and every validator happy/failure pair green in `gimle-mimir`'s own suite |
| **P2 · API + RBAC** | `/kinddefinitions` and `/resources/*` routes via `dispatchCustomResourceRequest`; prefix normalization + warning; defaulting/canonicalization; definition re-PUT revalidation + backfill; `KIND_DEFINITION`/`CUSTOM_RESOURCE` + the qualifier; audit rows | P1 | `curl` a definition and an instance through a real control plane; both survive a restart; a 409 violator list on a breaking re-PUT |
| **P3 · CLI** | `apply` fallthrough with the bounded 409 retry, `gimle kinds`, generic `get/delete` with plural/shortName resolution, printColumns rendering | P2 | The walkthrough transcript, minus the SAID column |
| **P4 · operator path** | Relay whitelist additions riding the existing workload-token bearer flow, `reportResourceStatus`, the `{kind}/status` qualifier enforcement, `GaldrOperatorLoop`, a real `gimle-examples/greeting-operator` | P3 | The walkthrough transcript, complete — hello said, status visible, operator bound by RBAC |
| **P5 · console + docs + RTM** | Custom Resources screen; `manifest-schema.md`/`cli-reference.md`/architecture pages; requirement rows (GIMLE-654+) + the Holmgang `custom-kinds.feature` | P4 | `mvn verify` green; `-Pvalidation` proves the whole loop end to end; RTM rows claim `Covered` honestly |
| **P6 · watchers pass** | The user-end validation pass designed below — five watcher personas plus a lead, ~40 scenarios, run against a real cluster built the way a user would build one | P5 | Every blocker/major finding fixed and re-verified against a rebuilt cluster; every minor triaged to an explicit fix/accept decision |
| *Deferred* | Widening the relay whitelist to platform-kind writes (composite kinds — a policy decision, the identity machinery already exists); multi-version schemas; an event timeline for custom resources (status is the only observable in v1) | — | Each its own design doc |

## Phase 6 · the Galdr watchers — a real-user validation pass

The final phase is a scoped rerun of the platform's established multi-watcher QA format —
independent tester personas, each handed a role and a scenario list, none permitted to read
Gimlé's Java sources to decide whether something is a bug. Every watcher judges the way a
customer would: from the documentation a user actually reads, from what the CLI and console
tell them, and from whether the system does what it just said it did. This pass covers **only
the Galdr feature surface** — everything phases 1–5 shipped — not the platform at large.

### Posture

- **Black-box, sources closed.** A watcher may read `manifest-schema.md`, `cli-reference.md`,
  the console, CLI help text, and error messages — never `*.java`. "The code says it's fine"
  is not an admissible verdict; "the error message told me what to fix" is.
- **Objective plus oracle, not a script.** Each scenario below states what the watcher tries
  and how they'd know it worked. The click-by-click path is the watcher's own — divergence
  between watchers on the same objective is signal, not noise.
- **One lead, who never tests.** The lead merges duplicate findings across watchers
  (fingerprint: scenario domain + normalized one-line symptom; merged findings keep the
  maximum severity and the union of reproduction steps), resolves false alarms by
  cross-referencing watchers' own activity logs, and writes the consolidated report. A
  disagreement between two watchers about severity is recorded, never silently resolved.

### Environments

| Env | Shape | What only it can test |
|---|---|---|
| **A — plaintext** | Single node via `mvn gimle:bootstrap`: store, control plane, one agent, the everyday cluster a developer first meets | All CRUD, CLI, console, operator-loop, and negative-path scenarios — the 90% of usage |
| **B — mTLS** | Same shape brought up with real PKI, so principals are real and RBAC actually bites | Every GOV scenario: tenant isolation, the qualifier boundaries, withheld grants, audit attribution |

### Watcher roster

| Watcher | Persona | Scenarios |
|---|---|---|
| **Kind Author** | The platform admin teaching the cluster a new word — writes KindDefinitions from the docs alone, evolves them, deletes them | KIND-1…8 |
| **Resource Author** | A tenant developer who applies instances of a kind someone else defined — never saw the schema's YAML, only its error messages | RES-1…8 |
| **Operator Dev** | Builds and deploys the reconciler module against the SDK docs and the `greeting-operator` example | OPR-1…7 |
| **Gatekeeper** | Whoever is accountable if tenant A reads tenant B's resources — runs entirely on Env B | GOV-1…6 |
| **Breaker** | Mildly adversarial, reads no manual twice — races, oversized payloads, malformed input, process bounces | CHAOS-1…8 |
| **Lead** | Never tests; dedups, adjudicates, writes the report | — |

Console coverage (CON-1…4) is carried by the Resource Author and Gatekeeper rather than a
dedicated watcher — the screen is read-only by design, and a persona that just mutated state
through the CLI is exactly who should check the screen reflects it.

### Scenario catalog

**Kind Author — the definition lifecycle**

- **KIND-1** Write a `Greeting`-like KindDefinition from `manifest-schema.md` alone (no
  copy-paste of the walkthrough), apply it. *Oracle:* the prefix-normalization warning names
  the stored name; `gimle kinds` lists it with scope, names, and a zero instance count.
- **KIND-2** Re-apply the identical definition. *Oracle:* success, no generation churn
  anywhere, no spurious warning difference from the first apply.
- **KIND-3** Submit a schema where one field is both `required` and has a `default`.
  *Oracle:* a 400 naming that field and the rule; nothing stored; `gimle kinds` unchanged.
- **KIND-4** Submit a schema nested 9 objects deep. *Oracle:* refused at definition admission
  with the depth cap stated, not stored and not half-stored.
- **KIND-5** With live instances, re-PUT the definition adding one new defaulted field.
  *Oracle:* update lands; every existing instance's `-o json` now shows the new field with
  its default (backfilled, not read-time-defaulted); the operator needed no change.
- **KIND-6** Re-PUT a schema an existing instance violates (tighten a `max` below a stored
  value). *Oracle:* 409 naming the violating instance(s); the old schema still validates new
  applies; nothing was partially updated.
- **KIND-7** `DELETE` the definition while instances exist. *Oracle:* 409 telling the user to
  delete instances first; after deleting them, the definition deletes cleanly; a subsequent
  instance apply gets the unknown-kind 400 with the remaining catalog.
- **KIND-8** Define a second kind whose declared `plural` collides with an existing kind's.
  *Oracle:* refused at admission naming the clash; the CLI noun still resolves to the
  original kind.

**Resource Author — living with someone else's kind**

- **RES-1** Apply the walkthrough's instance; read it back as `gimle get greetings`, `get
  gr`, and `get custom.Greeting`. *Oracle:* all three resolve to the same table, rendering
  NAME/TENANT/GENERATION plus the definition's printColumns.
- **RES-2** Typo a spec field name. *Oracle:* 400 naming the unknown key — rejected, not
  silently pruned; nothing stored.
- **RES-3** Submit an out-of-set enum value and an out-of-bounds int (separately). *Oracle:*
  each is a structured 400 naming the field and the violated constraint, understandable
  without reading the KindDefinition YAML.
- **RES-4** Apply a spec omitting every defaulted field. *Oracle:* `-o json` shows the stored
  spec complete with defaults persisted — what's stored is what's served.
- **RES-5** Apply a Tenant-scoped instance without `tenantId`, then a Cluster-scoped one with
  it. *Oracle:* both rejected, each error stating which scope rule was violated.
- **RES-6** Re-apply the identical spec, then change one field. *Oracle:* the identical
  re-apply bumps nothing; the real change bumps `generation` by exactly 1, and
  `status.observedGeneration` catches up within one operator poll interval.
- **RES-7** Delete an instance the operator is acting on. *Oracle:* whatever the operator
  materialized ceases on its next pass; `gimle audit --resource CustomResource:<kind>` shows
  the create, updates, and delete.
- **RES-8** Inspect an instance whose status the operator hasn't reported yet. *Oracle:* the
  table renders an empty status column (not an error); `-o json` shows spec verbatim and an
  empty status.

**Operator Dev — building the controller half**

- **OPR-1** Build an operator from the SDK docs and the `greeting-operator` example, deploy
  it, bind its `svc:` principal per the walkthrough. *Oracle:* status lands on every instance
  within one poll interval of the binding existing.
- **OPR-2** Deploy the operator before any instance of its kind exists. *Oracle:* it idles
  cleanly (no crash loop, no error spam); the first instance applied later is picked up on
  the next tick with no operator restart.
- **OPR-3** Deploy the operator with no role binding at all. *Oracle:* it reads nothing, and
  the relay's authorization failure is visible in the module's own log as a real error — not
  a hang, not an empty-set success.
- **OPR-4** With the operator's token granted only `READ` + `…/status`, attempt a spec write
  with that same identity from outside (curl with the bearer token). *Oracle:* 403 — the
  status grant never covers spec, live, not just on paper.
- **OPR-5** Kill the operator's worker JVM mid-loop. *Oracle:* self-healing respawns it;
  status reporting resumes; `observedGeneration` is correct afterward — no stuck stale
  status, no double-processing visible to the user.
- **OPR-6** Poison one instance (a spec value the operator's own logic chokes on). *Oracle:*
  every other instance still reconciles on every tick; the per-resource failure is visible in
  the operator's log; the tick never dies.
- **OPR-7** Bounce the control plane while the operator polls. *Oracle:* the loop backs off
  and rides through; status converges after recovery; no crash-loop and no manual
  intervention needed.

**Gatekeeper — the boundaries, on Env B**

- **GOV-1** As tenant B's principal, list and get tenant A's instances. *Oracle:* the list is
  filtered per-item, the direct get is denied — for a Tenant-scoped kind, cross-tenant reads
  simply don't happen.
- **GOV-2** As a principal with full tenant-admin grants but no `KIND_DEFINITION`, PUT a
  definition. *Oracle:* denied; the same principal can still `GET /kinddefinitions` (schemas
  are discoverable, teaching the cluster new words is not).
- **GOV-3** Bind a role qualified to one kind; act on a second kind with it. *Oracle:* the
  qualifier confines every verb to the named kind — the second kind is untouchable.
- **GOV-4** With spec-WRITE but no status qualifier, PUT a status; with only the status
  qualifier, PUT a spec. *Oracle:* both denied — the split holds in both directions.
- **GOV-5** Audit the whole session. *Oracle:* definition PUTs, instance PUT/DELETEs, and
  status PUTs all appear with `CustomResource:{kind}` (or `KIND_DEFINITION`) and the true
  mTLS principal — including the operator's `svc:` identity on status rows.
- **GOV-6** Delete a tenant owning custom resources. *Oracle:* its instances are gone with
  the rest of its state; no orphan rows readable under the dead tenant ID afterward.

**Breaker — races, garbage, and bounces**

- **CHAOS-1** Fire two different-spec applies at the same instance name concurrently.
  *Oracle:* the final stored spec is exactly one of the two, never a merge; the loser either
  retried transparently onto the winner's generation or reported a real conflict; generation
  arithmetic adds up.
- **CHAOS-2** Apply while bouncing the mimir leader. *Oracle:* the CLI either succeeds or
  reports a real error — never a false success; the store converges with no torn instance.
- **CHAOS-3** Apply a 300 KiB spec. *Oracle:* 400 at admission naming the cap; the control
  plane and store stay healthy.
- **CHAOS-4** Feed the apply path malformed YAML and an anchor-bomb. *Oracle:* structured
  400s, control plane unharmed — the untrusted-input posture holds for the generic parse
  path too.
- **CHAOS-5** Apply and `get` against a kind that doesn't exist. *Oracle:* both produce the
  unknown-kind message listing the defined kinds — the catalog-in-the-error contract, on
  every surface.
- **CHAOS-6** Restart the entire control plane. *Oracle:* definitions, instances, and
  statuses all survive; `gimle kinds` and every instance table are byte-identical before and
  after.
- **CHAOS-7** Delete a kind (instances first), then redefine it under the same name with a
  different schema. *Oracle:* the new schema alone governs new applies; nothing of the old
  kind's data resurfaces.
- **CHAOS-8** PUT an over-cap status, and a status for a nonexistent instance. *Oracle:*
  structured errors for both; no stored instance's generation moved.

**Console — carried by Resource Author and Gatekeeper**

- **CON-1** After the CLI work above, open Custom Resources. *Oracle:* the kind picker shows
  every definition; the instance table honors printColumns; spec and status render side by
  side with generation/observedGeneration both visible.
- **CON-2** Bump a spec and watch the screen. *Oracle:* observedGeneration visibly trails,
  then catches up — the "has the operator caught up" signal a human can actually read.
- **CON-3** View the screen with zero definitions, and with a definition that has zero
  instances. *Oracle:* honest empty states, not errors or spinners.
- **CON-4** On Env B as a read-only principal. *Oracle:* resources visible per RBAC; no
  mutation affordance exists anywhere on the screen — read-only by design, verified as such.

### Run plan

| Wave | Runs | Depends on |
|---|---|---|
| **0 — bring-up** | Both environments built the way a user would build them; the operator example built from the shipped sources; nothing else | Phases 1–5 complete, `-Pvalidation` green |
| **1 — baseline** | Kind Author, Resource Author, Operator Dev in parallel, each populating real state (definitions, instances, a live operator) | Wave 0 |
| **2 — pressure** | Gatekeeper and Breaker in parallel, deliberately colliding with wave 1's live state rather than a clean cluster | Wave 1 |

### Reporting and the exit gate

Findings use the platform's established QA shape — id, one-line title stating the wrong
behavior, severity, environment, numbered steps, expected vs. actual, evidence — under the
established taxonomy: **blocker** (data loss, stuck state with no operator path out, or a
security boundary that doesn't hold), **major** (a documented capability doesn't work, or
reports success while doing the wrong thing), **minor** (works, but the error message, output
shape, or edge-case behavior is wrong or inconsistent), **cosmetic** (presentation only).

The phase — and the feature — exits when every blocker and major is fixed and re-verified
against a rebuilt cluster, and every minor and cosmetic finding carries an explicit fix or
accept decision. Scenarios that passed clean are reported too: a ledger of defects alone
understates what held, and "what held up" is what the next design leans on.

### Non-goals of this pass

- Codec, Raft, and snapshot internals — invisible from outside the process; P1's own suite
  and Holmgang already own them.
- Load ceilings (how many kinds/instances before something falls over) — Surtr's job, a
  performance exercise, not functional QA.
- The deferred features (composite-kind writes, multi-version schemas, the event timeline) —
  not built, nothing for a black-box tester to observe.
- General platform regression hunting — anything reproducible with the feature absent is out
  of scope here and belongs to the platform-wide QA program instead.

Grounded against the codebase as of 2026-08-29: `StateMutation`'s 62 variants and `RaftCodec`'s
tag table, `dispatchResourceRequest`, `Authorizer.authorize(principal, resource, verb, tenant,
targetId)`, `SecretStore`/`ConfigMapCodec`'s overlay precedents, `NetworkPolicyRelay`/`ConfigRelay`'s
poll-and-relay template, `ModuleContext.relayControlPlaneRead`'s whitelist, and the shipped
workload-identity flow (`ApiServer.handleWorkloadTokenMint`/`verifyWorkloadToken`,
`WorkloadTokenRecord`, the agent's bearer-token attachment).
