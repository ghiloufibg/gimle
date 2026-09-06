---
sidebar_position: 11
---

# Custom kinds (Galdr)

Galdr is Gimlé's CRD analogue: a way to teach a running cluster a **new resource kind** — schema,
validation, RBAC, CLI/console visibility, and an operator reconciling it — without writing a line
of platform code or restarting anything. Kubernetes closed the same gap with
CustomResourceDefinitions; Gimlé closes it with the same three-part shape (a definition, instances,
an operator) built entirely from mechanisms the platform already had.

## The three artifacts

**A `KindDefinition` manifest** teaches the cluster the kind: its name, scope, declared
CLI/console nicknames, a small first-party schema for instance specs, and optional `printColumns`
for table rendering. Applied once by a platform operator via `gimle apply -f`, stored durably in
[gimle-mimir](./node-topology.md#store) like any other resource. See the
[manifest reference](../reference/manifest-schema.md#kinddefinition-manifest) for every field.

**Instances** are small YAML documents (`kind: custom.Greeting`, a `name`, a `tenantId` when the
kind is Tenant-scoped, and user data under `spec:`). Admission validates the spec against the
definition's schema, applies declared defaults, and persists the *defaulted* tree — a stored spec
is always complete, and an operator never re-derives defaulting logic.

**An operator** is an ordinary hosted module — deployed by manifest, tiered, supervised, probed,
redeployed like anything else; the platform never even knows it is an operator. It polls the full
current set of its kind's instances on an interval, does whatever the kind means in the real
world, and reports a `status` document back per instance.

## Kind names: the mandatory prefix

Every custom kind name carries a dotted prefix (`custom.Greeting`, `acme.AlertRule`). A definition
applied without one is normalized to `custom.` and stored that way, with a warning echoed back
through the API and CLI. This makes collision with a future built-in kind structurally impossible —
the one benefit of Kubernetes' API groups, bought without adopting groups: the prefix is a naming
rule inside Gimlé's one flat kind namespace, not a URL or version segment.

## The schema language

Deliberately small and first-party — a hand-rolled validator, not an OpenAPI subset. It catches
shape mistakes at admission with a good error; everything it cannot express is the operator's job
to check and report via `status`. Types: `string`, `int`, `double`, `bool`, `enum`, `list`,
`object` (recursive, depth-capped). A field is `required` or has a `default`, never both. Unknown
keys in an instance's spec are **rejected, not ignored** — a typo'd field name fails loudly at
apply time rather than being silently pruned. There is deliberately no `pattern` attribute: a
user-supplied regex evaluated at admission would be a ReDoS surface on the control plane (the JDK
engine backtracks); format checks belong in the operator.

## Generations, canonical bytes, and CAS

Each instance carries a store-assigned `generation`, bumped only when its **spec** changes. The
validated-and-defaulted spec is serialized to canonical JSON bytes (schema declaration order), so
re-applying an identical manifest is a byte-equality no-op — no generation bump, nothing for an
operator to re-do. Spec writes are compare-and-set-guarded on the generation the writer last read;
a lost race is a structured 409 the CLI retries a bounded number of times. Status writes are
last-write-wins and **never** bump the generation — an operator echoing `observedGeneration` in
its status is what lets readers see at a glance whether it has caught up with the latest spec.

The store side is generic: two record shapes (`KindDefinitionSpec`, `CustomResource`) and five
mutations travel gimle-mimir's Raft log for *every* kind ever defined — five codec tags total,
zero per user kind. The store never interprets the spec payload; "mimir is a dumb store" holds.

## RBAC: one resource kind, per-kind qualifiers

Custom resources authorize under a single `CUSTOM_RESOURCE` resource kind plus an optional
**qualifier** on each `Permission`:

| Qualifier | Grants |
|---|---|
| *(absent)* | Every kind's specs — never status |
| `custom.Greeting` | One kind's specs — never status |
| `custom.Greeting/status` | Only that kind's status sub-document — never specs |

Spec-write never covers status and the reverse, so a tenant admin can author Greetings while only
the operator's own workload principal may report what happened — and that operator cannot alter
desired state. Definitions themselves authorize under a separate `KIND_DEFINITION` resource kind
(writes are effectively platform-operator surface; reads are open to any authenticated principal,
since definitions are schemas, not data). Audit rows record the qualified
`CustomResource:{kind}` string, so `gimle audit --resource CustomResource:custom.Greeting` works
day one.

## The operator path: no new machinery

An operator inside a worker JVM has no network identity of its own — only its supervising
[node agent](./node-topology.md) does. Its reads and status writes travel the existing
agent-mediated control-plane relay:

1. The module calls the operator SDK; the worker forwards the request over its agent control
   channel.
2. The agent mints (and caches) a **workload-identity token** for the instance's own
   deployment+tenant — the same `/workload-tokens` mechanism every tenanted instance's relayed
   reads already use — and attaches it as a bearer credential. An untenanted instance is refused
   locally: there is no anonymous status-reporting path.
3. For a status report the worker sends the kind, tenant, resource name, and status JSON as
   **typed fields**; the agent validates each segment and assembles
   `PUT /resources/{kind}/{name}/status` itself — a module can never smuggle an arbitrary path or
   ride the agent's own node identity.
4. The control plane authorizes the call as the `svc:{tenant}:{deployment}` principal by ordinary
   RBAC — the `{kind}/status` qualifier above is what decides.

The operator SDK (`com.gimle.module.galdr`) is a for-loop, not a framework: `GaldrOperatorLoop`
polls the full current set every tick and hands it to a reconciler — full recompute per tick,
never a delta, so [convergence from any starting state](../concepts/level-triggered-reconciliation.md)
is inherited, not engineered. A failed poll backs off exponentially and recovers on the next
success; a reconciler exception poisons only its own tick — but it abandons the rest of that
tick's list, so an operator that must keep serving healthy resources while one is poisoned wraps
its per-resource work in its own try/catch, the way the example operator does. There is
deliberately **no watch API**:
nothing in Gimlé watches, and introducing an edge-triggered path for the least-trusted consumers
first would invert the platform's hardest-won correctness property.

`gimle-examples/greeting-operator` is the reference operator: it watches `custom.Greeting`, says
each greeting's `spec.message` its `spec.repeat` number of times, and reports
`{timesSaid, observedGeneration}` back — the complete loop in ~80 lines of module code.

## Definition updates

Re-applying a `KindDefinition` with a changed schema re-validates **every stored instance**
against the new schema before anything commits: a breaking change is refused with a 409 naming the
violating instances, and a compatible change backfills newly-declared defaults into stored specs in
the same atomic batch — the "stored specs are always complete" invariant survives schema
evolution. A definition with live instances cannot be deleted (refused at both the API and,
defense-in-depth, the store level); tenant deletion does not cascade custom resources, matching
how deployments already behave.

## CLI and console

The CLI resolves any unrecognized noun against the kind catalog — exact prefixed name, then each
definition's declared `plural`, then `shortNames` — so `gimle get greetings` and `gimle get gr`
work as soon as the definition declares them. `gimle kinds` lists what the cluster currently
knows. Tables render `NAME · TENANT · GENERATION` plus the definition's `printColumns`, resolved
by dotted path into spec/status; `-o json` emits both verbatim. See the
[CLI reference](../reference/cli-reference.md#custom-kinds).

The [web console](./web-console.md)'s **Custom Resources** screen is the same read path: a kind
picker fed by `/kinddefinitions`, an instance table honoring `printColumns`, and a detail pane
showing spec and status side by side with the generation/observedGeneration pair made visible.
Deliberately read-only — authoring stays in the CLI, where apply semantics live. The screen
re-reads on the console's own auto-refresh interval, since an operator's own status writes move
under it, and it never reports a catalog it could not read as a cluster with no custom kinds.

## What deliberately does not change

- The scheduler, every existing reconciler, the fabric, gossip, and the agent↔worker protocol
  (two additive control messages aside).
- The five workload kinds and `ManifestParser` — custom instances never pass through it.
- The no-watch, level-triggered posture; the no-user-code-in-the-control-plane invariant (an
  earlier alternative — schemas as Java interfaces loaded by the control plane — was rejected
  exactly because it violates this); the "mimir is a dumb store" boundary.
- Kubernetes non-compatibility: no API groups, no OpenAPI, no CRD YAML interop.
