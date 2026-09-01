---
sidebar_position: 8
---

import ZoomableDiagram from '@site/src/components/ZoomableDiagram';

# Multi-tenancy and quotas

A `Tenant` is an identity plus a `ResourceQuota` (`maxMemoryBytes`, `maxCpuMillicores`,
`maxInstances`) — deliberately named `Tenant` rather than "namespace," since this codebase already
uses "namespace" for two unrelated concepts (JPMS `ModuleLayer` namespacing, Linux namespace
isolation); reusing the word here would invite exactly the ambiguity this project's naming
conventions elsewhere try to avoid.

One tenant identity is reserved rather than operator-assigned: `gimle-system`, the `kube-system`
equivalent where the platform's own self-hosted extensions run — `gimle-gateway` (see [Service
fabric § the gateway module](./service-fabric.md#the-gateway-module)) is the first such extension.
It is auto-seeded at control-plane startup with a generous default quota, and only a
`group:gimle:operators` credential — not an ordinary or even broad `cluster-admin`-style RBAC grant
— can create/rename/delete it or submit a workload naming it as `tenantId`. See [Authentication and
authorization § The reserved gimle-system
tenant](./authn-authz.md#the-reserved-gimle-system-tenant) for the guard itself.

A deployment (or DaemonSet/StatefulSet/Job/CronJob) manifest that omits `tenantId` resolves to a
second reserved identity, `default` — the `default` namespace equivalent. Kubernetes never has a
pod with no namespace: omit it, and the object silently lands in `default`, a real namespace with
its own ConfigMaps/Secrets. Gimlé's manifest parsers do the same at parse time (`ManifestFields
#parseTenantId`) rather than leaving "untenanted" a valid-but-broken state with nothing addressable
to configure it — `/config/default/...`, `/secrets/default/...`, etc. all work for a
`default`-tenant deployment exactly as they would for any operator-named tenant. Like
`gimle-system`, `default` is auto-seeded at control-plane startup, but it carries none of
`gimle-system`'s write/delete guard — an operator may freely adjust its quota through the ordinary
`/tenants/*` API. Unlike `gimle-system`, `default` is also **not** subject to quota/LimitRange/
policy enforcement (`Tenant#isEnforceable` treats it the same as no tenant at all) — matching real
Kubernetes, where the `default` namespace carries no `ResourceQuota` object unless an admin
explicitly creates one, so nothing is enforced against it by default either. Scheduler node taints
and the config-addressability path both treat `default` as a real, ordinary tenant, since those are
exactly the capabilities this defaulting exists to unlock.

The quota constraint for a tenant that *is* enforced: the sum of `resourceRequest × replicas` across
every deployment sharing that `tenantId` must not exceed the tenant's quota. Admission specifically
checks against `resourceRequest × (replicas + maxSurge)` — `DeploymentSpec#maxCommittedInstances()`
— rather than `replicas` alone: a rollout with a nonzero `disruption.maxSurge` (see [Manifest schema
§ Deployment manifest: disruption](../reference/manifest-schema.md#deployment-manifest-disruption))
can transiently run more than `replicas` instances, and admission has to reject a submission that
couldn't stay within quota even briefly, not just at steady state. `QuotaReconciler`'s own
continuous check (below) still sums plain `replicas` — a transient surge overshoot it would
otherwise flag is expected to self-heal within one reconcile tick as the rollout completes, not
something worth a standing violation for.

## Names are scoped per tenant, like a Kubernetes namespace

A Deployment/Job/CronJob/DaemonSet/StatefulSet/Service name is unique only within its own
`tenantId`, not cluster-wide — the state store's own key is the compound pair `(tenantId, name)`,
the same relationship a Kubernetes object's name has to its namespace. Two tenants (including
`default`) are free to each run their own `orders-service` Deployment without either colliding
with, overwriting, or being able to read the other's — the untenanted (`tenantId` omitted, distinct
from `default`) namespace is one more such bucket, not a fallback that only sometimes applies.

The one place this has a visible cost is addressing a single resource by bare name: a `PUT` (`apply`
in the CLI) always resolves unambiguously, since the manifest's own `tenantId:` field is part of the
write's target key, but a `GET`/`DELETE` by name alone is now ambiguous whenever more than one
tenant happens to share it. Both the API and `gimle-cli` resolve this the same way: a caller-declared
`?tenant=<id>` query parameter (`--tenant <id>` on the CLI) names which tenant's copy to address,
omitted meaning the untenanted namespace — see the [CLI reference](../reference/cli-reference.md)
for the full per-verb flag list. `NetworkPolicySpec` is the one exception: its own `tenantId` was
never optional to begin with (it restricts exactly one tenant's own traffic), so `--tenant`/
`?tenant=` is required, not defaulted, on its `get`/`delete` routes specifically.

One deliberate asymmetry to know about: **deleting a tenant does not cascade its resources**.
`gimle delete tenant <id>` removes the tenant object (and its quota) itself; the tenant's
workloads, [custom resources](./custom-kinds.md), config, and secrets remain stored under the
now-dead tenant id — still listable and deletable by an operator, just no longer admitting new
instances against a quota. Clean up a tenant's resources before (or after) deleting the tenant;
nothing is silently destroyed, and nothing becomes unreachable.

## Plaintext transport is explicitly single-tenant

Plaintext (the default transport, see [Transport security](./transport-security.md)) gives every
caller the identical unauthenticated identity — there is no peer identity for RBAC to check, so
there is no way, not even after the fact, to distinguish a legitimate co-tenant from an uninvited
caller reaching into someone else's tenant. Rather than quietly allowing shared multi-tenant use
under those conditions, `POST`/`PUT /tenants/{id}` refuses to create a second real tenant while
running in plaintext: neither the reserved `gimle-system` tenant nor `default` (both above) counts
toward the limit — both are seeded automatically regardless of transport — but the first
operator-created tenant claims the one slot plaintext allows, and every subsequent *new* tenant id
is rejected with `403` until the cluster moves to mTLS. An update to an already-existing tenant
(adjusting its own quota, for
example) is always permitted regardless of transport — the guard only ever blocks the creation of a
genuinely new tenant identity. Real multi-tenancy requires mTLS, where a real peer identity exists
for RBAC to actually check.

## Enforcement: checked at admission, and continuously

Checked twice, the same "reject early, reject again where it actually matters" shape the scheduler
and manifest validation already use elsewhere:

- **At admission** — the control-plane API.
- **Continuously** — `QuotaReconciler`, [level-triggered like every other reconciler](./control-plane.md):
  every tick, it recomputes *every* tenant's total usage from scratch against the state store's
  current deployments, rather than tracking deltas since the last tick. A quota edit, a deployment
  edit, or a fresh empty store all converge through the same code path.

**`QuotaReconciler` deliberately does not evict instances to force compliance.** Evicting a running
instance is a more consequential, unrequested action than anything else a reconciler in this
codebase does unprompted. Instead, it marks the offending deployment's status as quota-violating
(`StateStore.putQuotaViolation`, read by the API server's deployment status surface) and logs a
warning — a human operator resolves an over-quota tenant explicitly, the reconciler only surfaces
the problem.

## Policy rules

Admission checks two independent things: `TenantQuotaPlugin`'s aggregate resource math (above), and
`PolicyConfigPlugin`'s organization-specific rules — the roadmap's literal "policy as data, not code"
answer. A policy rule is a plain, unencrypted tenant-scoped config entry (below), not a new schema:
`gimle set config <tenantId> policy.maxReplicasPerDeployment <n>` caps how many replicas *any single*
deployment for that tenant may declare — a per-deployment sizing ceiling, independent of (and checked
alongside) `TenantQuotaPlugin`'s own tenant-wide resource-billing math. Absent the config entry (the
common case — no policy configured), no ceiling applies; a tenant opts in per rule, per key.

`policy.maxReplicasPerDeployment` is the one rule this plugin enforces today — adding another means
adding another key/check to `PolicyConfigPlugin` itself, not a schema change, so the mechanism is
"policy as data" for the rules that exist, while the *set* of possible rules is still code. A
malformed value, or an entry mistakenly written `--encrypted` (a policy rule is never a secret),
both reject the submission outright rather than silently skip enforcement.

## LimitRange

`ResourceQuota` bounds the *aggregate* sum of a tenant's deployments; nothing bounds what a
*single* deployment may declare on its own — one deployment can consume most of a tenant's quota by
itself as long as the sum still fits. `LimitRangeSpec` closes that gap, the platform's own
equivalent of Kubernetes' `LimitRange`: an optional, per-tenant min/max bound (`minRequest`/
`maxRequest`/`minLimit`/`maxLimit`, each an independently optional memory/cpu pair) on a single
Deployment's own `resources.request`/`resources.limit`. There is deliberately no `default` bound —
`resources.request`/`resources.limit` are always-required on a module's own manifest, so there's no
omitted-value case for a default to inject. Its constructor also rejects a `minRequest` above
`maxLimit`: since `ModuleDescriptor` already forces `resourceRequest <= resourceLimit` on every
manifest, that combination is one no manifest could ever satisfy, and would otherwise silently lock
a tenant out of deploying anything. Scope is Deployment-only today, matching `TenantQuotaPlugin`'s
own accepted Job/DaemonSet/StatefulSet/CronJob gap (above) — extending LimitRange further while
quota itself doesn't would be an inconsistent asymmetry.

Checked the same twice-over way quota is, both against the one shared `LimitRangeSpec.violation`
method so admission and reconciliation can never drift on what counts as a violation:

- **At admission** — `LimitRangePlugin`, run first in the deployment admission chain (before
  `TenantQuotaPlugin`) since it's the cheaper single-artifact comparison, with no cross-deployment
  summation to compute. Absent `tenantId`, or an absent LimitRange for the tenant, are both a no-op
  allow — a LimitRange is opt-in per tenant, not a default every deployment must satisfy.
- **Continuously** — `LimitRangeReconciler`, level-triggered like `QuotaReconciler`, but single-pass:
  a workload's own bound violation needs no cross-deployment accumulation to evaluate, only its own
  resource declaration against the tenant's current range. Deliberately does not evict instances to
  force compliance either — it marks the offending deployment's status as limit-range-violating,
  together with the reason (`StateStore.putLimitRangeViolation`, read by the API server's deployment
  status surface as `limitRangeViolating`/`limitRangeViolationReason`, a separate flag from
  `quotaViolating` since the two are independently-true-or-false failure modes) and logs a warning,
  the same "surface it, don't act on it" posture `QuotaReconciler` establishes.

Managed as its own top-level resource, `PUT`/`GET`/`DELETE /limitranges/{tenantId}` (keyed by
`tenantId` directly, like `Tenant` itself rather than `NetworkPolicySpec`'s separate-`name` shape,
since a LimitRange is naturally one-per-tenant) plus `GET /limitranges` for the full list, RBAC-gated
on its own `ResourceKind.LIMIT_RANGE` — a role can be granted "manage this tenant" without also
getting "constrain what any single deployment within it may request." See [CLI
reference](../reference/cli-reference.md) for the `gimle limitrange` verbs.

## Tenant-scoped config

Plain config entries (`gimle set config <tenantId> <key> <value>`, see
[CLI reference](../reference/cli-reference.md)) are scoped to a tenant and served directly by
`gimle-controlplane`'s own `/config/*` endpoints — no crypto boundary, since a plain entry never
needed one. `encrypted == true` is no longer a variant of this same entry: an entry needing crypto
is a **secret**, a distinct resource kind (`ResourceKind.SECRET` vs. `ResourceKind.CONFIG`), served
by a distinct process, covered next.

Delivery to a running instance is no longer one-shot. Initial delivery still happens synchronously
during an instance's install sequence, but each agent additionally runs a **config relay**
(`ConfigRelay`, interval `-Dgimle.agent.configRelayIntervalMillis`, default 30s, `0` disables):
the identical fetch logic re-runs on the interval — same tenant scoping, same
`configMapRefs`/`secretMapRefs` narrowing — and any value that changed since the relay last sent it
to that instance is re-pushed over the control channel, so a config edit or a rotated secret
reaches a running instance's very next `ModuleContext.config(key)` read instead of waiting for its
restart.

Deletions propagate too. Alongside the changed values, each successful tick sends one
`ControlMessage.ConfigKeysRetained` naming the **full set of keys that still exist** for the
instances behind that control channel; the worker applies it by dropping everything not named, so
deleting a ConfigMap or Secret key genuinely revokes it from a running instance rather than leaving
the stale value readable until a restart. It is a whole-set assertion re-sent every tick rather
than a one-shot "key X was removed" event, and is therefore level-triggered in the same sense every
reconciler here is: a worker that reconnected mid-deletion, or missed ticks entirely, converges on
the correct set from the very next assertion, with no replay. The set is computed per control
channel rather than per instance, because a Tier 1 worker hosts several density-packed instances
behind one channel and one worker-wide config map — asserting one instance's keys alone would
retract its neighbours'. A tick in which any instance on a channel failed to fetch sends no
assertion at all for that channel, so a transient control-plane blip never reads as "everything was
deleted".

A module that needs to *react* to a change rather than re-read on its own schedule registers
`ModuleContext.onConfigChange(listener)`: each delivery, rotation, and retraction arrives as a
`ConfigChange` carrying the key and either its new plaintext or an empty value for a retraction.
Listeners are held per instance context and dropped when that instance is uninstalled, so a
disposed module's callback can never keep its classloader alive; a listener that throws is logged
and skipped without disturbing the rest of the delivery.

## Secrets

Secret material is owned entirely by **Fafnir** (`gimle-fafnir`), a dedicated process extracted out
of the control plane — see [Node topology](./node-topology.md#fafnir) for where it sits in the
cluster. `gimle-controlplane` performs no cryptography itself: it proxies `/secrets/*` calls to
Fafnir over mTLS, forwarding the calling principal's identity as an internal claim, but Fafnir
still authorizes every request independently against RBAC data it reads itself — a compromised or
buggy control-plane replica that forwarded an unauthorized request is still caught there (genuine
defense-in-depth, not a decision Fafnir merely re-derives from the proxy's own conclusion).

### Why a dedicated service

The extraction wasn't a response to a crypto weakness — `SecretCipher` already did real
AES-256-GCM with key rotation before Fafnir existed. It closed five architectural/operational gaps
instead:

1. **No dedicated service.** `SecretCipher`/`KeyFileManager`/`KeyRing` were `gimle-controlplane`
   implementation details; nothing named "secrets" existed as its own thing anywhere in the system.
2. **Multi-replica control planes couldn't share secrets.** Each replica loaded its own local key
   file with nothing provisioning it identically across replicas — a live bug, not a hypothetical:
   a multi-replica smoke test spawned each control-plane replica with a *distinct* key file, so one
   replica's ciphertext couldn't be opened by a sibling.
3. **Full-fidelity plaintext exposure on every list call.** `GET /config/{tenantId}` decrypted and
   returned every secret in a tenant in one response — no `list` (metadata-only) vs. `get`
   (full-value) distinction the way Kubernetes RBAC or `vault kv list`/`get` split it.
4. **No versioning, no audit trail, no single-key read, no CLI surface.** Overwriting a key
   destroyed the old value permanently, and `gimle-cli`'s `config` command had no dedicated
   `secret` verb and no way to trigger key rotation at all.
5. **Module-facing consumption was push-only and undesigned.** Modules received secrets via
   `ModuleContext.config(...)`, populated by the agent pushing a decrypted value at deploy time —
   workable, but incidental: nobody had designed it as "the Gimlé secret-consumption pattern," it
   was just how generic config delivery happened to also carry secrets.

The rest of this section describes how Fafnir closes each of these — write, read, rotation, and
the node agent's own direct fetch path, in that order (source: `diagrams/secrets-lifecycle.d2`):

<ZoomableDiagram
  src="/diagrams/secrets-lifecycle.svg"
  alt="A secret write goes Operator to Control Plane to Fafnir, which re-checks RBAC itself before encrypting a new key@N version with the active KeyRing key; a read reverses the path and decrypts by the version's embedded keyId; rotate-key mints a new active key and re-encrypts every reachable entry, keeping old keys so not-yet-reached entries still decrypt; a node agent fetches secret values directly from Fafnir over its own mTLS identity, never through the Control Plane"
  width={760}
/>

- **`SecretCipher`** (`gimle-fafnir`) — AES-256-GCM via the JDK's own `Cipher`/`SecretKeySpec`, no
  external crypto library (the same "prefer what the JDK already provides" posture as AppCDS/JFR/
  `ModuleLayer` elsewhere in this codebase). Output is `version(1) || keyId(1) || iv(12) ||
  ciphertext-with-tag`: `keyId` lets decryption pick the right key out of the key ring after a
  rotation, without a caller having to track which key encrypted which blob separately.
- **`KeyFileManager`**/**`KeyRing`** (`gimle-fafnir`) — loads Fafnir's own AES-256 master key ring
  from a key file, generating one on first run if absent. A platform-generated local key file, not
  an external KMS dependency — consistent with the same MVP-first/YAGNI posture the rest of this
  codebase applies. File permissions are restricted to owner-read-only wherever the filesystem
  supports POSIX permissions (every real deployment target); on a filesystem that doesn't (Windows,
  local development only), the key is still written but the restriction is skipped with a logged
  warning rather than a hard failure. `POST /secrets/rotate-key` generates a new active key and
  re-encrypts every existing secret under it; old keys are kept, never deleted, so any entry the
  rotation walk hasn't reached yet still decrypts correctly under its original key.
- **Versioning** — every write claims a new, immutable version rather than overwriting the last one
  (`gimle secret set`/`GET .../versions`); `gimle secret get` defaults to the latest version,
  `--version N` reads a specific historical one. `gimle secret delete` soft-deletes by default
  (every version stays recoverable via `gimle secret undelete`, which clears the flag in place
  rather than minting a new version) — `--destroy` hard-deletes irreversibly. This versioning lives
  entirely inside Fafnir as a synthetic key-naming convention (`key@N` for each immutable version,
  `key@meta` for the mutable current-version pointer) layered over the same underlying config-entry
  store `/config/*` uses — no separate store schema for it.
- **Version provenance** — each version records who wrote it, when, and what type it was declared
  as, on that same `key@meta` pointer entry (nothing there is secret material, which is why it can
  sit on the unencrypted pointer). `gimle secret versions` prints all three, and `gimle secret get`
  repeats them for the version it returned, so "who wrote version 3 of this key, and when" is
  answered from the version listing itself rather than by correlating a bare version number against
  the cluster-wide audit trail by timestamp. The audit trail carries the version too: a secret
  write's `AuditEvent` records the version number it produced, which was previously missing
  entirely.
- **Types** — a write may declare what shape its value has: `opaque` (the default — stored
  unexamined, exactly as before), `pem-certificate`, or `pem-private-key`. A declared type is
  validated structurally at write time, so a truncated or wrongly-encoded PEM is refused by the
  `gimle secret set` call that caused it instead of being encrypted, replicated, and only failing
  later at module launch, far from its cause. The type set is deliberately tiny — opaque plus the
  shapes the platform itself mounts as files — rather than a taxonomy of value kinds nothing here
  interprets. The declared type is stored per version and travels with the value on read and on
  export.
- **Bulk export/import** — `gimle secret export <tenantId> --out <file>` fetches every live secret
  the tenant owns in one authorized, audited call (`GET /secrets/{tenantId}?names=a,b,c`), and
  `gimle secret import <tenantId> --in <file>` writes them back one key at a time through the
  ordinary single-key write path, so each import is separately authorized, separately audited, and
  lands as a new version at the destination. This exists for migrating a tenant to a
  freshly-bootstrapped cluster, whose master key cannot open the source cluster's ciphertext.

  The export file therefore holds **plaintext secret material** (base64-encoded, not encrypted) —
  unavoidable given its purpose, so the command constrains it instead: it writes only to a file,
  never stdout, so values never land in terminal scrollback or a shell pipeline; it creates that
  file with owner-only permissions wherever the filesystem supports POSIX ones; and it refuses an
  existing path rather than silently replacing one. Deleting the file after import remains the
  operator's job — treat it exactly like the master key file. The bulk route runs Fafnir's own
  independent `Authorizer.authorize(...)` check like every other read, and deliberately does **not**
  extend the node self-service path: a `gimle:nodes` certificate that may read its assigned tenant's
  secrets one key at a time is refused the whole-tenant read outright, since that is an operator
  migration tool, not something a node ever needs.
- **Payload ceilings** — a secret's plaintext is capped at 512 KiB and a single stored config/secret
  row at 1 MiB (`ConfigEntry.MAX_VALUE_BYTES`, the same limit Kubernetes places on a
  Secret/ConfigMap). The plaintext cap sits at half the storage cap so a value accepted at write
  time always fits once encrypted. Request bodies are capped at 4 MiB in both `ApiServer` and
  `FafnirServer`, enforced on the bytes as they stream rather than by trusting `Content-Length`, and
  an oversized write is refused with `413` rather than read into memory first. Without these, a
  multi-megabyte blob was silently accepted, encrypted, and replicated through Raft consensus
  exactly like a small entry — held in every store replica's memory and written into every snapshot.
