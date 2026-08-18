# Gimle Fafnir

Fafnir is Gimlé's secrets vault: its own process (`FafnirMain`), holding the master key ring and
performing every secret encrypt/decrypt/rotate operation in the cluster. It is the only place
decrypted secret plaintext ever exists outside a worker's own memory. It was extracted out of
`gimle-controlplane` the same way `gimle-mimir` was split into its own process, so that a
compromise of the general-purpose API tier does not also compromise the crypto boundary:
`gimle-controlplane`'s `ApiServer` no longer performs any cryptography itself and proxies
`/secrets/*` and `/config/*` decryption to Fafnir over mTLS via its own `FafnirClient`.

## Process entrypoint

`FafnirMain` starts one Fafnir replica:

```
FafnirMain <port> <secretKeyPath> --store-endpoints host1:clientPort1,host2:clientPort2,...
           [--host <hostname>] [--csr-endpoint <host:port>]
```

- `secretKeyPath` points at the on-disk key ring file (`KeyFileManager.loadAllOrCreate`); every
  Fafnir replica is expected to be provisioned with **identical** key files by an out-of-band
  process this codebase doesn't control or verify. Since Fafnir replicas have no peer-discovery
  mechanism of their own, the key ring's fingerprint is logged loudly at startup (and exposed on
  `/status`) so an operator can diff it by hand across replicas and notice drifted provisioning.
- `--store-endpoints` is how Fafnir reaches the `gimle-mimir` cluster — it holds a `StoreClient`
  and treats the store the same way `gimle-controlplane` does, as a stateless HTTP-facing service
  in front of shared state.
- `--csr-endpoint` feeds `FafnirMain`'s own certificate-rotation ticker (`OwnCertificateRotator`),
  which checks every two seconds under mTLS and reloads `FafnirServer`'s TLS material in place on
  rotation.
- Plaintext is the default transport, and `FafnirMain` prints a loud startup warning when running
  that way: every `/internal/secrets/*` and `/secrets/rotate-key` call is unauthenticated in that
  mode. Set `-Dgimle.transport.protocol=tls` to require mTLS.
- Optional `-Dgimle.fafnir.muninnEndpoint` ships this replica's own request metrics and traces to
  one or more Muninn replicas via `MuninnShipper`; unset means "ship nowhere."
- Resolves a bundled `gimle-fafnir-console` SPA off the classpath (`BundledSpa`) and serves it at
  `/console` when present — the same pattern `gimle-controlplane` uses for `gimle-console`.

## Key types

| Type | Role |
|---|---|
| `FafnirCrypto` | The crypto boundary itself — the only object in the process holding decrypted plaintext or the key ring. Plain Java, not tied to HTTP, so it's testable without a socket. Wraps `SecretCipher` and owns key rotation (`rotate()`), including re-encrypting every existing `ConfigEntry` under the new active key. |
| `secret.SecretCipher` | AES-256-GCM via the JDK's own `Cipher`/`SecretKeySpec` — no external crypto dependency. Wire format is `version(1) \|\| keyId(1) \|\| iv(12) \|\| ciphertext-with-tag`; `decrypt` also falls back to a legacy pre-key-id `iv \|\| ciphertext` layout (discriminated by GCM tag-verification failure, not by inspecting bytes, since a legacy blob's leading byte is indistinguishable from a version marker by content alone). |
| `secret.KeyRing` / `secret.KeyFileManager` | The in-memory set of keys by id plus the on-disk file format and rotation logic (`KeyFileManager.rotate` appends a new active key without ever deleting an old one, so previously-encrypted entries keep decrypting). |
| `SecretStore` | Versioned secret storage layered over the store as a synthetic key-naming convention — see below. Plain Java, not tied to HTTP, matching `FafnirCrypto`'s own separation from `FafnirServer`. |
| `SecretMetadata` | The list-endpoint's public view of a secret: key, latest version, deleted flag — never a value. |
| `FafnirServer` | The HTTP surface — `com.sun.net.httpserver.HttpServer`, no framework dependency, the same minimal stack `ApiServer` uses. Owns authentication, RBAC re-checking, audit logging, and TLS material reload. |

### Versioned storage over the config store

`SecretStore` stores secrets as ordinary `ConfigEntry` rows in the same `gimle-mimir` store
`gimle-controlplane`'s `/config/*` traffic already uses — there is no separate store schema.
`key@meta` is a mutable, unencrypted pointer entry (`{latestVersion, deleted}`); `key@N` is an
immutable, encrypted value entry for version `N`. Writing a new version is lock-free for the
`key@N` claim (a collision there is harmless — whichever write lands last simply becomes what that
entry holds) but takes a narrowly-scoped store lease around the final verify-and-advance step that
moves `@meta` to the new version, since that step is a genuine shared-state race a lock-free
approach can't safely resolve (documented in detail in `SecretStore.put`'s own javadoc, including
an empirically-found TOCTOU bug in an earlier version of the check). Delete is soft by default
(every `@N` entry stays on disk, `@meta.deleted` flips true) or hard with `?destroy=true` (removes
`@meta` and every `@N`).

## HTTP surface

| Endpoint | Method | Purpose |
|---|---|---|
| `/internal/secrets/encrypt` | POST | Encrypt a base64 value under the active key. Used by `gimle-controlplane`'s `FafnirClient` to keep `/config/*` writes working now that crypto lives out-of-process. |
| `/internal/secrets/decrypt` | POST | Batch-decrypt base64 ciphertexts. |
| `/secrets/rotate-key` | POST | Generate a new active key and re-encrypt every existing entry under it. Moved here verbatim from the control plane's old `rotateSecretsKey`. |
| `/secrets/{tenantId}` | GET | List a tenant's secrets (metadata only). |
| `/secrets/{tenantId}/{key}/versions` | GET | List a key's stored version numbers. |
| `/secrets/{tenantId}/{key}` | GET / PUT / DELETE | Read (optionally `?version=N`), write, or delete (`?destroy=true` for hard delete) a single secret. |
| `/auth/login`, `/auth/logout`, `/auth/session` | POST / POST / GET | Fafnir's own console session story — a distinct cookie (`gimle_fafnir_session`) and signing key from `ApiServer`'s. |
| `/status` | GET | Uptime, active key id, key-ring fingerprint (never key material), transport protocol, known tenants — unauthenticated even under TLS, since nothing here is per-tenant or value-bearing. |
| `/console` | GET | The bundled operator SPA, when present. |

## Authorization: defense in depth, not trust-the-proxy

Every `/secrets/*` request passes through `FafnirServer.authorizeSecrets`, which never treats
"this request arrived already forwarded by `gimle-controlplane`" as proof of authorization by
itself. `resolvePrincipal` picks the calling identity in priority order:

1. `X-Gimle-Forwarded-Principal`/`X-Gimle-Forwarded-Groups` — set only by `ApiServer`'s own
   `/secrets/*` proxy, trusted only because it arrives over an mTLS-authenticated connection (the
   same "channel authenticated, not the claim itself" trust boundary Kubernetes' aggregation layer
   uses for `X-Remote-User`).
2. The connection's own peer certificate — a node agent or test caller reaching Fafnir directly.
3. Fafnir's own console session cookie — an operator signed in through `/auth/login`.

Whichever principal is resolved, Fafnir re-runs its own independent `Authorizer.authorize(...)`
(from `gimle-mimir.authz`) against RBAC data it reads itself from the store — a buggy or
compromised control-plane replica that forwards an unauthorized principal is still caught here. A
`gimle:nodes` principal takes a separate, narrower path (`Authorizer.isTenantAssignedToNode`): it
may only ever `READ`, never write or delete, and only for a tenant it currently has an active
instance assignment for — a node fetching secret values directly from Fafnir over its own mTLS
identity can't read another tenant's secrets or write/delete anything. Every authorization
decision is dual-audited: an SLF4J line on a dedicated `com.gimle.fafnir.audit` logger, plus a
durable `AuditEvent` proposed through the store. A per-principal `LoginThrottle` backs off after
repeated authorization failures (distinct from a second throttle instance guarding `/auth/login`
itself against password-guessing).

Like every other Gimlé process here, running in plaintext mode means no identity to check at all,
so every request passes — `-Dgimle.transport.protocol=tls` is the one switch that turns
authorization on, cluster-wide.

## How other modules consume it

- `gimle-controlplane`'s `FafnirClient` proxies `/secrets/*` and `/config/*` decryption over mTLS,
  forwarding the calling principal as an internal claim rather than performing crypto itself.
- `gimle-agent` node agents fetch secret values straight from Fafnir over their own mTLS node
  identity, never through the control plane, configured via `-Dgimle.agent.fafnirEndpoint`
  (optional — a node with no tenant using secrets never needs it configured).
- `gimle-cli`'s `secret` subcommand and the console's Secrets screen both talk to Fafnir's
  `/secrets/*` surface (through the control-plane proxy), not `/config/*` directly.
- `gimle-fafnir-console` is the bundled SPA served at `/console`; it has its own `pom.xml` and is a
  pure-resources dependency of this module (no Java sources), the same wiring
  `gimle-console`/`gimle-controlplane` establishes.
