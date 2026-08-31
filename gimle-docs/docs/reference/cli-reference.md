---
sidebar_position: 2
---

# CLI reference

`gimle-cli` is a `kubectl`-shaped client — familiar muscle memory, with no claim of Kubernetes API
compatibility. Mirrors `GimleCli`'s own usage text directly.

## Global flags

Any order, anywhere on the command line:

- `--server host:port` — control-plane address (or set the `GIMLE_SERVER` environment variable
  instead, so you don't have to pass it on every invocation).
- `-o`/`--output table|json` — output format, default `table`.

## Verbs

```text
gimle get deployments [name] [--tenant <id>]
gimle get jobs [name] [--tenant <id>]
gimle get cronjobs [name] [--tenant <id>]
gimle get daemonsets [name] [--tenant <id>]
gimle get statefulsets [name] [--tenant <id>]
gimle apply -f <manifest.yaml>   (kind: Deployment, Job, CronJob, DaemonSet, StatefulSet, ArtifactSet,
                                  KindDefinition, or any defined custom kind, read from the file itself)
gimle kinds
gimle get <custom-kind|plural|shortName> [name] [--tenant <id>]
gimle delete <custom-kind|plural|shortName> <name> [--tenant <id>]
gimle delete kinddefinition <kind>
gimle delete deployment <name> [--tenant <id>]
gimle delete job <name> [--tenant <id>]
gimle delete cronjob <name> [--tenant <id>]
gimle delete daemonset <name> [--tenant <id>]
gimle delete statefulset <name> [--tenant <id>]
gimle deployment revisions <name> [--tenant <id>]
gimle deployment rollback <name> [--to-revision N] [--tenant <id>]
gimle statefulset revisions <name> [--tenant <id>]
gimle statefulset rollback <name> [--to-revision N] [--tenant <id>]
gimle daemonset revisions <name> [--tenant <id>]
gimle daemonset rollback <name> [--to-revision N] [--tenant <id>]
gimle cronjob trigger <name> [--tenant <id>]
gimle get nodes
gimle get node-assignments <nodeId>
gimle cordon <nodeId>
gimle uncordon <nodeId>
gimle taint <nodeId> <tenantId>
gimle untaint <nodeId> <tenantId>
gimle volume list
gimle volume destroy <statefulSet> <instanceIndex> --node <nodeId>
gimle events <deploymentName> <instanceIndex> [--tenant <id>] [--limit N]
gimle get services [name] [--tenant <id>]
gimle set service <name> (--deployment <name> [--deployment ...] | --external-name <host>)
                          --port N [--target-port N] [--tenant <id>] [--session-affinity]
gimle delete service <name> [--tenant <id>]
gimle service endpoints <name> [--tenant <id>]
gimle get networkpolicies [name] [--tenant <id>]
gimle set networkpolicy <name> --tenant <id> [--deployment ...] [--service-interface ...]
                                [--allowed-caller-tenant <id> ... | --deny-all-callers]
                                [--allowed-callee-tenant <id> ... | --deny-all-callees]
gimle delete networkpolicy <name> --tenant <id>
gimle get tenants [id]
gimle set tenant <id> --max-memory-bytes N --max-cpu-millicores N --max-instances N
gimle delete tenant <id>
gimle get limitranges [tenantId]
gimle set limitrange <tenantId> [--min-request-memory M --min-request-cpu M]
                                 [--max-request-memory M --max-request-cpu M]
                                 [--min-limit-memory M --min-limit-cpu M]
                                 [--max-limit-memory M --max-limit-cpu M]
gimle delete limitrange <tenantId>
gimle get config <tenantId>
gimle set config <tenantId> <key> <value> [--encrypted]
gimle delete config <tenantId> <key>
gimle config versions <tenantId> <key>
gimle config rollback <tenantId> <key> <version>
gimle secret list <tenantId>
gimle secret get <tenantId> <key> [--version N]
gimle secret set <tenantId> <key> --value <v>
gimle secret delete <tenantId> <key> [--destroy]
gimle secret undelete <tenantId> <key> [--version N]
gimle secret versions <tenantId> <key>
gimle secret rotate-key
gimle secret retire-key <keyId>
gimle configmap list <tenantId>
gimle configmap get <tenantId> <name>
gimle configmap set <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
gimle configmap delete <tenantId> <name>
gimle configmap versions <tenantId> <name>
gimle configmap rollback <tenantId> <name> <version>
gimle secretmap list <tenantId>
gimle secretmap get <tenantId> <name>
gimle secretmap set <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
gimle secretmap replace <tenantId> <name> [--from-literal key=value ...] [--from-file path|key=path ...]
gimle secretmap delete <tenantId> <name> [--destroy]
gimle secretmap versions <tenantId> <name>
gimle secretmap rollback <tenantId> <name> <groupVersion>
gimle secretmap seal <tenantId> <name> --from-sealed key=path [...]
gimle seal public-key [--out <path>]
gimle seal value <plaintext> --public-key <path> --tenant <id> --name <name> --key <key> [--out <path>]
gimle seal rotate-key
gimle seal retire-key <keyId>
gimle artifact push <jar> [--tenant <id>]
gimle artifact list [moduleId]
gimle artifact get <moduleId> <version> [--to <path>]
gimle artifact delete <moduleId> <version>
gimle backup create [--to <path>]
gimle backup restore <path>
gimle audit list [--principal <name>] [--resource <kind>] [--tenant <id>]
                  [--since <epochMillis>] [--limit N]
gimle logs <target> [--category=CAT] [--follow|-f] [--since=<cursor>]
gimle get roles [name]
gimle set role <name> --permission <resource>:<verb>[:<tenant>[:<qualifier>]] [--permission ...]
                       (qualifier narrows a custom_resource grant to one kind, e.g.
                        custom_resource:write:team-a:custom.Greeting/status; leave the tenant
                        segment empty for a cluster-wide qualified grant: custom_resource:read::custom.Greeting)
gimle delete role <name>
gimle get rolebindings [id]
gimle set rolebinding <id> --subject user:<name>|group:<name> --role <name>
gimle delete rolebinding <id>
gimle get accounts [username]
gimle set account <username> --password <value>
gimle delete account <username>
gimle can-i <verb> <resource> [--tenant <id>] [--target <id>]
gimle cert token create [--ttl <duration>]
gimle cert request --purpose operator|node|tenant [--tenant <id>] --out-cert <path> --out-key <path> [--common-name <name>]
gimle cert status <request-id> --out-cert <path>
gimle cert approve <request-id>
gimle cert renew [--force]
gimle cert revoke <serialHex>
gimle cert unrevoke <serialHex>
gimle cert revocations
```

The `cert` verbs are the operator-facing half of the node-bootstrap-CSR and certificate-rotation
flows — see [Transport security](../architecture/transport-security.md) §4/§4a/§4b for the full
picture. `token create` and `approve` need this invocation's own configured mTLS identity (`--server`
plus `gimle.tls.certFile`/`keyFile`/`caFile`); `request`/`status` deliberately don't, since they run
before that identity exists. `renew` only acts if the credential is actually due for renewal, unless
`--force`. `request`'s `--common-name` sets the CSR Subject's CN, defaulting to the local
`user.name` system property when omitted. `--purpose tenant` (with `--tenant <id>`) is the one
`request` shape that *does* submit over this invocation's own mTLS identity: it mints a
tenant-membership client certificate (`O=gimle:tenant:<id>`, stamped server-side) for a caller
already authorized to approve certificate requests under that tenant's scope — the credential
`gimle-bifrost`'s TLS identity-verifying mode checks a NetworkPolicy's allow list against.

`config`'s own `versions`/`rollback` reach a separate plaintext version ledger (`ConfigVersionStore`)
each `set config`/`delete config` also stamps into, and are a narrower top-level `config` verb of
their own rather than folded into `get`/`set`/`delete` — that three-verb dispatch has no shape for
them either, but unlike `secret` below there was no reason to move `config`'s existing `get`/`set`/
`delete` off it too. `config versions <tenantId> <key>` lists every version ever stamped, oldest
first, each carrying its own value (or a `deleted: true` tombstone); `config rollback <tenantId>
<key> <version>` re-applies an earlier version's content (or its deletion) as a brand-new version,
never rewriting history — the same "restore = re-apply as a new revision" semantics `secretmap
rollback` below documents. Only plaintext config is covered: an encrypted (`--encrypted`) entry's
`set`/`delete` bypass this ledger entirely, since versioning an encrypted value would need Fafnir's
own key-rotation-aware history, not this one.

Unlike `config`'s own `get`/`set`/`delete`, `secret` is a distinct top-level verb rather than a
`get`/`set`/`delete` noun — it needs more actions (`versions`, `rotate-key`) than three-verb
dispatch has a shape for. Every
call is proxied by the control plane to Fafnir, the dedicated secrets service (see
[Multi-tenancy](../architecture/multi-tenancy.md#secrets) and
[Node topology](../architecture/node-topology.md#fafnir)) — never talked to directly. Each `set`
claims a new, immutable version rather than overwriting the last one; `get` defaults to the latest
version, `--version N` reads a specific one; `delete` soft-deletes by default (every version stays
recoverable via `undelete`), `--destroy` hard-deletes irreversibly and has no way back. `undelete`
clears the soft-delete flag in place rather than minting a new version: with no `--version` it
restores whatever version was current at the moment of `delete`, with one it restores that specific
earlier version's data as current instead, leaving every other version's own stored data untouched
either way. `rotate-key` generates a new master encryption
key and re-encrypts every existing secret under it, cluster-wide. `retire-key <keyId>` is
destructive in a different, sharper way than `delete`: it stops Fafnir from trusting that key id at
all, so any value still encrypted under it — one `rotate-key` alone never re-encrypts — becomes
permanently unrecoverable through this surface from that moment on. Retiring the currently active
key is rejected outright; rotate first, confirm nothing still depends on the old key, then retire
it.

Like `secret`, `configmap` is a distinct top-level verb rather than a `get`/`set`/`delete` noun —
`list` here returns names scoped to one tenant-owned ConfigMap object, not the flat per-key rows
`config` returns, and `set` needs a read-then-write sequence rather than a single call. A ConfigMap
is a named, multi-key bundle a Deployment attaches by reference (`configMapRefs` in its manifest)
instead of receiving its tenant's entire flat config set — see
[Manifest schema](./manifest-schema.md#deployment-manifest-configmaprefs). `set` always writes a
partial merge (`PATCH`): it reads the ConfigMap's current version first (treating "doesn't exist
yet" as version 0) and supplies that version back to the server itself, so a caller never types a
version number by hand; a concurrent writer racing that same read is reported back as a plain
conflict, never silently retried. `--from-literal key=value` may repeat to set several keys in one
call; `--from-file path` reads a whole file's content as one key named by the file's own base name,
or `--from-file key=path` names the key explicitly. `configmap versions <tenantId> <name>` and
`configmap rollback <tenantId> <name> <version>` are ConfigMap's own equivalent of `config`'s
version ledger above — same oldest-first listing, same "restore = new revision" rollback semantics
— stamped alongside every `set`/`delete` on the ConfigMap as a whole object rather than per key.

`secretmap` is the identical grouping for Fafnir-managed secrets, attached by reference
(`secretMapRefs` in the manifest — see
[Manifest schema](./manifest-schema.md#deployment-manifest-secretmaprefs)) instead of receiving a
tenant's entire secret set. Unlike `configmap`, there is no single object-level version to read
first: each key keeps its own independent `key@N` version ledger, the same one a flat `secret`
entry has, so `set` is a single call with no read-before-write — each key in the batch reports its
own outcome (a new version, or a per-key failure) rather than the whole call succeeding or failing
as one unit. `--from-literal`/`--from-file` behave exactly like `configmap set`'s own; `delete`
soft-deletes every key under the name by default, `--destroy` hard-deletes them all irreversibly,
the same distinction `secret delete` already makes per key.

Unlike `configmap`, `set` here always writes a **partial merge**: only the key(s) given are
touched, every other existing member key survives untouched — the same `kubectl apply` vs.
`kubectl replace` split, made explicit as two distinct verbs instead of one call whose behavior
depends on a flag. `replace <tenantId> <name> [--from-literal key=value ...] [--from-file
path|key=path ...]` is the full-replace counterpart: every key not named in the call is removed,
so the resulting key set is exactly what was given — including the empty set (no
`--from-literal`/`--from-file` at all), which clears the SecretMap entirely. Each touched key,
written or removed, still reports its own outcome the same way `set` does.

Every write to a SecretMap — `set`, `replace`, `delete`, `delete <key>` — also stamps a **group
version**:
a snapshot of every member key's own version and deleted state at that moment, layered on top of
the per-key ledger above. `versions` lists a SecretMap's full group-version history, oldest first;
`rollback <groupVersion>` restores every key that group version recorded — a live key's content as
a brand-new version (never rewriting the old one), a deleted key back to deleted — and records the
rollback itself as a new, later group version rather than rewriting history. A key added after the
target group version and never part of it is left untouched, not deleted.

`secretmap seal <tenantId> <name> --from-sealed key=path [...]` is `set`'s offline-sealed
counterpart: instead of a plaintext `--from-literal`/`--from-file` value, each `path` names a
sealed-envelope JSON file produced by `seal value` below. The plaintext is never visible to, or
readable back by, whoever produced the envelope — only Fafnir's own private sealing key can unwrap
it, which `seal` does at commit time before applying the recovered plaintext through the same write
path `set` uses (so it gets the identical per-key group versioning). A blob sealed for the wrong
tenant, name, or key is rejected as a per-key failure, the same shape `set` already reports.

`seal` is the standalone verb for Fafnir's asymmetric sealing-key lifecycle and the client-side
sealing operation itself, distinct from `secret`/`secretmap` since `public-key`/`value` are
global, tenant-agnostic operations. `seal public-key [--out <path>]` fetches Fafnir's current
public sealing key — unauthenticated, since the key is meant to be public, but only ever served
over TLS. `seal value <plaintext> --public-key <path> --tenant <id> --name <name> --key <key>`
is the one command in this CLI that never calls the control plane: it reads a previously-saved
`public-key` response and seals entirely client-side, so a CI pipeline or a value committed to a
config repo ahead of deploy needs no live authenticated session to produce a value only Fafnir can
recover. `seal rotate-key`/`seal retire-key <keyId>` mirror `secret rotate-key`/`retire-key`
exactly, but retiring a sealing key is strictly less destructive than retiring a `secret` key: it
only blocks unwrapping sealed blobs not yet committed — a SecretMap value already applied through
`secretmap seal` was re-encrypted under Fafnir's current symmetric key at commit time and never
stored in sealed form, so it is unaffected by a later sealing-key retirement.

`artifact` is a distinct top-level verb for the same reason `secret` is: `push` has no shape in
three-verb dispatch. Every call is proxied by the control plane to Andvari, the artifact registry
(see [Node topology](../architecture/node-topology.md#andvari)). `push` derives the registry
coordinate from the jar's own bundled `gimle-module.yaml` rather than taking name/version flags,
so the coordinate a jar is stored under and the identity it declares can never drift apart; a
re-push of different bytes under an existing coordinate is refused (a stored version is
immutable -- push the changed jar as a new version).

`backup create [--to <path>]` takes a full-cluster-state snapshot (`GET /backup`, leader-routed so
it's never a not-yet-caught-up follower's stale view) and streams it straight to a local file —
opaque bytes, never parsed by the CLI. `backup restore <path>` streams that file back (`PUT
/restore`) and proposes it through the ordinary replicated Raft log as a new
`StateMutation.RestoreSnapshot`, the same way any other write here is proposed, so every replica
ends up consistent rather than only whichever node answered the request. Cluster-admin-only by
default (`ResourceKind.BACKUP`, absent from every tenant role template, the same posture `fault`
and custom-kind definitions already take) — a restore overwrites every tenant's entire durable
state in one call.

`events` returns an instance's full lifecycle timeline, newest-first; `--limit N` caps how many of
those entries print, applied client-side (the underlying `GET /events` call has no server-side
`limit` parameter of its own, unlike `audit list` below) — useful against a crash-looping instance
whose timeline would otherwise be hundreds of lines.

`service`/`networkpolicy` manage the [Service abstraction](../architecture/service-fabric.md#the-service-abstraction-a-stable-name-in-front-of-a-deployment)
— a stable name in front of a Deployment's live, ephemeral endpoints, the ClusterIP analogue named
in the platform's own network-model design. `set service` POSTs to the `/services` collection
rather than PUTting a per-name path, since a Service names itself in its own request body;
`--deployment` may repeat (the set of workload names a Service fronts) and `--target-port` defaults
to `--port` when omitted (the Service listens and forwards on the same port).
`--external-name <host>` declares the ExternalName shape instead — the Service resolves to that
external hostname at the target port, with no in-cluster backing (and therefore no `--deployment`);
`--session-affinity` asks the Bifrost proxy layer to pin each caller address to one backend by
consistent hash rather than round-robining. `service endpoints`
resolves the Service's current live backing-instance set on every call, never a cached value, so it
never lags a reconcile interval behind. `networkpolicy` manages the accompanying NetworkPolicy
analogue — a deny-by-default restriction on which other tenants may call into one tenant's own
Services (ingress) and which tenants its own workloads may call out to (egress); `--tenant` is
required (a NetworkPolicy always restricts exactly one tenant's own traffic), `--deployment` scopes
it to specific workloads instead of the whole tenant when given, and `--service-interface` scopes
it to named exported fabric interfaces. A direction is restricted only when expressed, and at least
one must be: `--allowed-caller-tenant` (repeatable) allows the named caller tenants in, while
`--deny-all-callers` is the allow-nobody form of the same direction; `--allowed-callee-tenant` /
`--deny-all-callees` restrict the egress direction the same way.

`limitrange` manages a tenant's [LimitRange](../architecture/multi-tenancy.md#limitrange) — a
per-workload min/max bound on a single Deployment's own `resources.request`/`resources.limit`,
distinct from `tenant`'s own aggregate quota. Each of the four bound pairs
(`--min-request-*`/`--max-request-*`/`--min-limit-*`/`--max-limit-*`) is independently optional and
all-or-nothing — setting one flag of a pair without the other is rejected as a 400, not silently
guessed. Unlike `service`/`networkpolicy`, `limitrange` is PUT by `tenantId` directly (like `tenant`
itself) rather than POSTed to a collection, since a LimitRange is naturally one-per-tenant.

`audit list` reads the cross-resource audit trail (see
[Authentication and authorization](../architecture/authn-authz.md#audit-logging)) — every
`WRITE`/`DELETE` authorization decision, allowed and denied, across both the control plane and
Fafnir. Every filter is optional and independently combinable; omitting all of them lists the most
recent events cluster-wide.

The `role`/`rolebinding`/`account` verbs manage RBAC — see
[Authentication and authorization](../architecture/authn-authz.md). `--permission` may repeat (a
role is a set of permissions); the optional third segment of `resource:verb:tenant` scopes a grant
to one tenant instead of cluster-wide. `set account` doubles as create-or-reset-password, matching
`set tenant`/`set config`'s existing create-or-update convention — the password is sent once over
the same authenticated mTLS connection every other write already uses and is hashed server-side,
never stored or echoed back in plaintext.

`can-i` is the `kubectl auth can-i` analogue: `gimle can-i write deployments --tenant acme` asks
the control plane's self-subject access review (`GET /authz/can-i`) whether the calling identity
would be authorized for that action, without performing it, and prints `yes`/`no` (the full review
as JSON under `-o json`). Verb and resource are matched case-insensitively, `-` is accepted for
`_` (`network-policy` works), and a plural `s` is tolerated so the nouns the other verbs use spell
valid questions here too.

## Custom kinds

`gimle kinds` lists every [KindDefinition](../reference/manifest-schema.md#kinddefinition-manifest)
the cluster currently knows — name, scope, declared names, instance count, description. For `get`
and `delete`, any noun the built-in dispatch doesn't recognize is resolved against that catalog:
first as an exact prefixed kind name (`custom.Greeting`), then against each definition's declared
`plural` (`greetings`), then its `shortNames` (`gr`) — so a kind's own declared nicknames work the
moment its definition is applied, with no CLI release in between. Tables render
`NAME · TENANT · GENERATION` plus the definition's `printColumns`, each resolved by dotted path
into the instance's spec/status (an unresolved path is an empty cell); `-o json` emits spec and
status verbatim.

`apply -f` routes on the manifest's `kind:` the same way: `KindDefinition` teaches the cluster a
new kind, and any dotted kind name is sent up verbatim as an instance of that kind, validated
server-side against its stored schema. A concurrent-modification 409 (the server's
compare-and-set on the instance's generation losing a race) is retried a bounded number of times
before the conflict is surfaced; a schema-violation 409 — including a definition re-apply refused
with its violator list — is surfaced immediately, since resending the same bytes can't fix it.
See the [custom kinds architecture page](../architecture/custom-kinds.md) for the whole
mechanism, including how operator modules report the `status` these tables render.

## Examples

`apply -f` honors the manifest's own optional `apiVersion:` field (omitted means `v1alpha1`; see
the [manifest schema](./manifest-schema.md#manifest-versioning-apiversion)), and surfaces any
deprecation warnings the control plane attaches to the response — one `warning:` line each,
printed on **stderr** so `-o json` output on stdout stays clean for scripts. Today that means a
`v1alpha1` manifest naming a local `artifactPath` warns (the path resolves against the reading
process's own working directory, not the manifest file), and an `apiVersion: v1` manifest rejects
the field outright in favor of the artifact registry.

```bash
# Deploy (or update) a module from its manifest
gimle apply -f gimle-examples/greeter-provider/deployment.yaml --server 127.0.0.1:8080

# List every deployment, or look up one by name
gimle get deployments --server 127.0.0.1:8080
gimle get deployments greeter-provider-deployment --server 127.0.0.1:8080

# A bad rollout's history and a way back -- revisions lists newest-first, rollback with no
# --to-revision restores the one immediately before the current one; statefulset/daemonset accept
# the identical two verbs
gimle deployment revisions orders-service-deployment --server 127.0.0.1:8080
gimle deployment rollback orders-service-deployment --server 127.0.0.1:8080
gimle deployment rollback orders-service-deployment --to-revision 2 --server 127.0.0.1:8080

# Tail a target's logs live -- the CLI-side equivalent of the console's own Logs screen
gimle logs instance/greeter-consumer-deployment/0 --follow --server 127.0.0.1:8080

# Inspect which node an instance landed on, and what else is scheduled there
gimle get nodes --server 127.0.0.1:8080
gimle get node-assignments node-1 --server 127.0.0.1:8080

# Exclude a node from future placement without evicting what's already running there
gimle cordon node-1 --server 127.0.0.1:8080
gimle uncordon node-1 --server 127.0.0.1:8080

# Reserve a node for one tenant -- every other tenant's replica is excluded from it
gimle taint node-1 tenant-a --server 127.0.0.1:8080
gimle untaint node-1 tenant-a --server 127.0.0.1:8080

# An instance's own lifecycle timeline (installed, resolved, started, active, ...) -- --limit caps
# a crash-looping instance's otherwise-hundreds-of-lines timeline to the most recent entries.
# --tenant is required for a tenanted deployment: the timeline is keyed by the exact
# (tenantId, deploymentName, instanceIndex) triple, never a bare-name search across tenants.
gimle events orders-service-deployment 0 --server 127.0.0.1:8080
gimle events orders-service-deployment 0 --tenant acme --server 127.0.0.1:8080
gimle events orders-service-deployment 0 --limit 20 --server 127.0.0.1:8080

# A stable name in front of a Deployment's live endpoints, and who else may call it
gimle set service orders-web --deployment orders-service-deployment --port 8080 --server 127.0.0.1:8080
gimle service endpoints orders-web --server 127.0.0.1:8080
gimle set networkpolicy orders-policy --tenant orders-platform --allowed-caller-tenant billing --server 127.0.0.1:8080

# Who deleted the acme tenant's secrets in the last hour -- allowed and denied attempts alike
gimle audit list --tenant acme --resource SECRET --since 1712000000000 --server 127.0.0.1:8080

# Schedule a recurring job, list cronjobs, then fire one immediately without waiting for its
# schedule -- generated Jobs show up on `gimle get jobs`, named nightly-cleanup-<epochSeconds>
gimle apply -f cronjob.yaml --server 127.0.0.1:8080
gimle get cronjobs --server 127.0.0.1:8080
gimle cronjob trigger nightly-cleanup --server 127.0.0.1:8080

# Run one instance on every eligible node (topology-derived, no --replicas flag to set)
gimle apply -f daemonset.yaml --server 127.0.0.1:8080
gimle get daemonsets node-exporter --server 127.0.0.1:8080

# Ordered rollout, sticky per-index placement -- get shows each index's own nodeId
gimle apply -f statefulset.yaml --server 127.0.0.1:8080
gimle get statefulsets orders-statefulset --server 127.0.0.1:8080

# Push one jar, tagged with its owning tenant
gimle artifact push target/orders-service-1.0.0.jar --tenant orders-platform --server 127.0.0.1:8080

# Push a whole multi-jar app in one command instead of one `artifact push` per jar
gimle apply -f artifactset.yaml --server 127.0.0.1:8080

# Per-tenant resource caps
gimle set tenant acme --max-memory-bytes 536870912 --max-cpu-millicores 2000 --max-instances 10

# Bound what any single deployment in acme may request/limit
gimle set limitrange acme --min-request-memory 64Mi --min-request-cpu 50m --max-limit-memory 512Mi --max-limit-cpu 500m
```

`GIMLE_SERVER=127.0.0.1:8080` in your shell's environment removes the need to repeat `--server` on
every call above — see [Getting started](../tutorials/getting-started.md) for the one-time
`~/.m2/settings.xml` setup that also makes `mvn gimle:deploy` (a thin wrapper around `apply`)
available.
