---
sidebar_position: 8
---

# Multi-tenancy and quotas

A `Tenant` is an identity plus a `ResourceQuota` (`maxMemoryBytes`, `maxCpuMillicores`,
`maxInstances`) — deliberately named `Tenant` rather than "namespace," since this codebase already
uses "namespace" for two unrelated concepts (JPMS `ModuleLayer` namespacing, Linux namespace
isolation); reusing the word here would invite exactly the ambiguity this project's naming
conventions elsewhere try to avoid.

A deployment optionally carries a `tenantId`. The quota constraint: the sum of
`resourceRequest × replicas` across every deployment sharing that `tenantId` must not exceed the
tenant's quota.

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

## Tenant-scoped config, and encryption

Config entries (`gimle set config <tenantId> <key> <value> [--encrypted]`, see
[CLI reference](../reference/cli-reference.md)) are scoped to a tenant. An encrypted entry is
protected with real cryptography, not a placeholder:

- **`SecretCipher`** — AES-256-GCM via the JDK's own `Cipher`/`SecretKeySpec`, no external crypto
  library (the same "prefer what the JDK already provides" posture as AppCDS/JFR/`ModuleLayer`
  elsewhere in this codebase). Output is `iv || ciphertext-with-tag`, self-contained so a single
  `byte[]` round-trips through decryption without the caller tracking the IV separately.
- **`KeyFileManager`** — loads the control plane's AES-256 master key from a key file, generating
  one on first run if absent. A platform-generated local key file, not an external KMS dependency —
  consistent with the same MVP-first/YAGNI posture the rest of this codebase applies. File
  permissions are restricted to owner-read-only wherever the filesystem supports POSIX permissions
  (every real deployment target); on a filesystem that doesn't (Windows, local development only),
  the key is still written but the restriction is skipped with a logged warning rather than a hard
  failure.
