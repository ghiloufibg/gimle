---
sidebar_position: 8
---

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

A deployment optionally carries a `tenantId`. The quota constraint: the sum of
`resourceRequest × replicas` across every deployment sharing that `tenantId` must not exceed the
tenant's quota. Admission specifically checks against `resourceRequest × (replicas + maxSurge)` —
`DeploymentSpec#maxCommittedInstances()` — rather than `replicas` alone: a rollout with a nonzero
`disruption.maxSurge` (see [Manifest schema § Deployment manifest:
disruption](../reference/manifest-schema.md#deployment-manifest-disruption)) can transiently run
more than `replicas` instances, and admission has to reject a submission that couldn't stay within
quota even briefly, not just at steady state. `QuotaReconciler`'s own continuous check (below) still
sums plain `replicas` — a transient surge overshoot it would otherwise flag is expected to self-heal
within one reconcile tick as the rollout completes, not something worth a standing violation for.

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

## Tenant-scoped config

Plain config entries (`gimle set config <tenantId> <key> <value>`, see
[CLI reference](../reference/cli-reference.md)) are scoped to a tenant and served directly by
`gimle-controlplane`'s own `/config/*` endpoints — no crypto boundary, since a plain entry never
needed one. `encrypted == true` is no longer a variant of this same entry: an entry needing crypto
is a **secret**, a distinct resource kind (`ResourceKind.SECRET` vs. `ResourceKind.CONFIG`), served
by a distinct process, covered next.

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

The rest of this section describes how Fafnir closes each of these.

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
  (every version stays recoverable) — `--destroy` hard-deletes irreversibly. This versioning lives
  entirely inside Fafnir as a synthetic key-naming convention (`key@N` for each immutable version,
  `key@meta` for the mutable current-version pointer) layered over the same underlying config-entry
  store `/config/*` uses — no separate store schema for it.
