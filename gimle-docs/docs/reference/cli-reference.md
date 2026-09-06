---
sidebar_position: 2
---

# CLI reference

`gimle-cli` is a `kubectl`-shaped client — familiar muscle memory, with no claim of Kubernetes API
compatibility. Mirrors `GimleCli`'s own usage text directly.

## Global flags

Any order, anywhere on the command line:

- `--server host:port` — control-plane address. One of three sources, consulted in a fixed order
  that never varies: `--server`, then the `GIMLE_SERVER` environment variable, then the current
  context in the config file (see [Contexts](#contexts-talking-to-more-than-one-cluster) below).
  Each is read only when every earlier one is absent, so an explicit flag always wins and a command
  that carries one never even opens the config file.
- `-o`/`--output table|json|manifest` — output format, default `table`. `manifest` is honored only
  by `get <deployment|job|cronjob|daemonset|statefulset> <name>`: it re-projects the status
  response back into a manifest that `apply -f` accepts unchanged, closing the round-trip gap where
  the status response's own `spec.moduleId` (nested, server-computed) could never be fed back as
  the manifest's own top-level `module:` key. Every other command falls through to a table under
  `-o manifest`, the same as an unrecognized `-o` value not being caught earlier would.

`-o json` is honored by **every** verb, not only the read-shaped ones. A read verb emits the
resource (or the array of them) as the API returned it; a write verb emits a one-line result object
in place of its human sentence — `{"result":"cordoned","kind":"node","id":"node-a"}` for
`gimle cordon node-a`, `{"result":"destroyed","kind":"volume","id":"orders/0","nodeId":"node-a","tenantId":null}`
for `gimle volume destroy orders 0 --node node-a`, and the same `result`/`kind`/`id` shape for
every other `set`/`delete`/`apply`. `gimle logs` emits the structured log lines themselves rather
than a re-serialization of its own one-line rendering: one JSON array per request (an empty array
when nothing matched, so a zero-match query is still valid JSON to pipe onward), and one JSON
object per line as it arrives under `--follow`, since a stream that never ends has no closing
bracket to print. [`get ... --watch`](#watching-a-resource-converge) follows the identical rule for
the identical reason: one array for a single read, NDJSON while watching.

Advisory output — deprecation warnings, the stale-credential notice, the "some nodes were
unreachable" note on `gimle volume list` — always goes to **stderr**, whatever `-o` says, so stdout
carries nothing but the result a script is parsing.

## Exit codes

A failed invocation exits with a code naming *why* it failed, so a script can branch without
parsing the stderr message:

| Code | Meaning | Typical cause |
| ---- | ------- | ------------- |
| `0`  | success | — |
| `1`  | generic / unclassified | a usage or argument mistake (unknown verb, missing flag), a local I/O failure |
| `2`  | invalid input | the control plane rejected the request as invalid (HTTP `400`), or a manifest failed client-side validation |
| `3`  | not found | the addressed resource does not exist (HTTP `404`) |
| `4`  | forbidden | the caller is unauthenticated or lacks the permission (HTTP `401`/`403`) |
| `5`  | conflict | the request conflicts with the resource's current state (HTTP `409`) |
| `6`  | unreachable or retryable | the server could not be reached, or answered "not leader, leader unknown" (HTTP `307` with no `Location`) |

`401` and `403` share code `4` deliberately: both mean the caller may not do this, and there is no
remedy that depends on telling them apart. Client-side usage errors stay on `1` — the CLI reports
them in prose and does not attempt to categorize them further.

A rejection *predicted* by [`apply --dry-run`](#previewing-a-submission-apply---dry-run) exits with
the same code the real submission would have — see that section for why, and for the one case
(unplaceable replicas) that deliberately stays at `0`.

```bash
gimle get deployment never-created --server 127.0.0.1:8080
echo $?   # 3
```

## Verbs

```text
gimle get deployments [name] [--tenant <id>]
gimle get jobs [name] [--tenant <id>]
gimle get cronjobs [name] [--tenant <id>]
gimle get daemonsets [name] [--tenant <id>]
gimle get statefulsets [name] [--tenant <id>]
gimle get <deployments|jobs|cronjobs|daemonsets|statefulsets|nodes|node-assignments> [name]
                     [--watch|-w] [--watch-interval=SECS] [--watch-ticks=N]
gimle apply -f <manifest.yaml>|- [--dry-run]
                                   (kind: Deployment, Job, CronJob, DaemonSet, StatefulSet,
                                    ArtifactSet, KindDefinition, Service, NetworkPolicy, Tenant,
                                    LimitRange, Role, RoleBinding, Account, or any defined custom
                                    kind, read from the manifest itself; -f - reads the manifest
                                    from stdin instead of a file)
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
gimle label node <nodeId> <label>[ <label>-]...
gimle cordon <nodeId>
gimle uncordon <nodeId>
gimle taint <nodeId> <tenantId>
gimle untaint <nodeId> <tenantId>
gimle volume list
gimle volume destroy <statefulSet> <instanceIndex> --node <nodeId> [--tenant <id>]
gimle events <deploymentName> <instanceIndex> [--tenant <id>] [--limit N]
gimle metrics
gimle metrics-history <CONTROLPLANE|FAFNIR|STORE|AGENT|WORKER> <processId>
                       [--since <cursor>] [--limit N]
gimle traces-history <CONTROLPLANE|FAFNIR|STORE|AGENT|WORKER> <processId>
                      [--since <cursor>] [--limit N]
gimle context list
gimle context show [name]
gimle context use <name>
gimle context set <name> --server host:port
gimle context delete <name>
gimle get services [name] [--tenant <id>]
gimle set service <name> (--deployment <name> [--deployment ...] | --external-name <host>)
                          --port N [--target-port N] [--tenant <id>] [--session-affinity]
gimle delete service <name> [--tenant <id>]
gimle service endpoints <name> [--tenant <id>]
gimle get networkpolicies [<name> --tenant <id>]
gimle set networkpolicy <name> --tenant <id> [--deployment ...] [--service-interface ...]
                                [--allowed-caller-tenant <id> ... | --deny-all-callers]
                                [--allowed-callee-tenant <id> ... | --deny-all-callees]
gimle set networkpolicy <name> --tenant <id> [--add-allowed-caller-tenant <id> ...]
                                [--remove-allowed-caller-tenant <id> ...]
                                [--add-allowed-callee-tenant <id> ...]
                                [--remove-allowed-callee-tenant <id> ...]
gimle delete networkpolicy <name> --tenant <id>
gimle get alertrules [name] [--tenant <id>]
gimle set alertrule <name> --deployment <name> --metric <METRIC> --comparator <GREATER_THAN|LESS_THAN>
                            --threshold N --webhook <url> [--tenant <id>] [--disabled]
gimle delete alertrule <name> [--tenant <id>]
gimle get tenants [id]
gimle set tenant <id> --max-memory-bytes N --max-cpu-millicores N --max-instances N
                       [--isolation-posture OPEN|DENY_BY_DEFAULT]
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
gimle secret set <tenantId> <key> (--value <v> | --from-file <path>) [--type <t>]
gimle secret delete <tenantId> <key> [--destroy]
gimle secret undelete <tenantId> <key> [--version N]
gimle secret versions <tenantId> <key>
gimle secret export <tenantId> --out <file>
gimle secret import <tenantId> --in <file>
gimle secret rotate-key
gimle secret rewrap
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
gimle artifact push <jar> [--tenant <id>] --vessel --name <moduleId> --version <version>
gimle artifact list [moduleId]
gimle artifact get <moduleId> <version> [--to <path>]
gimle artifact delete <moduleId> <version>
gimle backup create [--to <path>]
gimle backup restore <path>
gimle audit list [--principal <name>] [--resource <kind>] [--tenant <id>]
                  [--since <epochMillis>] [--limit N] [--cursor <token>] [--all]
gimle logs <target> [--category=CAT] [--follow|-f] [--since=<cursor>]
                    [--level=LEVEL] [--contains=TEXT] [--tenant <id>|--tenant=<id>]
gimle get roles [name]
gimle set role <name> --permission <resource>:<verb>[:<tenant>[:<qualifier>]] [--permission ...]
                       (resource, verb, and tenant each accept "*" for every value — quote it,
                        most shells expand a bare *: "*:read", "deployment:*", "*:*:acme")
                       (qualifier narrows a custom_resource grant to one kind, e.g.
                        custom_resource:write:team-a:custom.Greeting/status; leave the tenant
                        segment empty for a cluster-wide qualified grant: custom_resource:read::custom.Greeting)
gimle delete role <name>
gimle get rolebindings [id]
gimle set rolebinding <id> --subject user:<name>|group:<name> --role <name>
gimle delete rolebinding <id>
gimle get accounts [username]
gimle set account <username> --password <value> [--groups <g1,g2,...>]
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
gimle top
```

`top` is not built into `gimle-cli` — it is contributed by `gimle-hugin` and discovered through
`ServiceLoader`, so it appears in `gimle --help` only when that jar is on the classpath (every
distribution archive puts it there). See the [terminal cluster view](./terminal-view.md) for what it
shows and what it deliberately cannot do.

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
either way.

`versions` prints each version's author, write timestamp, and declared type — not just the version
numbers — so "who wrote version 3, and when" is answerable directly; `get` repeats the same three
fields for whichever version it returned.

`set` takes an optional `--type`: `opaque` (the default — stored unexamined, exactly as before),
`pem-certificate`, or `pem-private-key`. A declared type is validated structurally at write time, so
a truncated or wrongly-encoded PEM is refused here rather than accepted and only failing later at
module launch. `--from-file <path>` is the companion for those types: a PEM is multi-line material
that shell-quoting into `--value` tends to mangle into exactly the malformed value `--type` then
rejects. Exactly one of `--value` or `--from-file` is required. A secret's plaintext is capped at
512 KiB (see [Multi-tenancy](../architecture/multi-tenancy.md#secrets)); an oversized value is
refused rather than stored.

`export`/`import` are the bulk pair, for moving a tenant's whole secret set to a
freshly-bootstrapped cluster whose master key cannot open the old cluster's ciphertext. `export`
fetches every live secret in one authorized, audited call and writes it to `--out`; `import` writes
each key back through the ordinary single-key write path, so every key is separately authorized,
separately audited, and lands as a new version at the destination (source version *numbers* are not
recreated — they mean nothing in another cluster's ledger, but each key's declared type does travel
with it and is re-validated on arrival). The export file holds **plaintext secret material**,
unavoidably: that is the whole point of carrying values to a cluster with a different master key. It
is written only to a file, never stdout; created with owner-only permissions where the filesystem
supports POSIX ones; and an existing path is refused rather than silently overwritten. Deleting it
once imported is the operator's job — treat it like the master key file itself.

`rotate-key` generates a new master encryption
key and re-encrypts every existing secret under it, cluster-wide. `retire-key <keyId>` is the
sharper operation: it destroys that key id's material, so a value still encrypted under it would
become permanently unreadable. Rather than doing that silently, retirement is **refused** while any
stored value still depends on the key, and the error names how many.

`rewrap` clears that: it re-encrypts every stored secret version that is not already under the
active key, without minting a new key the way a second `rotate-key` would. It is idempotent, so an
interrupted run is finished by running it again. The normal sequence is `rotate-key`, then
`retire-key` — rotation sweeps as it goes, so there is usually nothing left behind; `rewrap` is what
clears the residue rotation's own sweep can miss (a value written concurrently, after the sweep had
already passed that entry). Retiring the currently active key, or key id 0, is rejected outright.

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

`metrics` prints the control plane's per-deployment rollup of the same live request/error-rate
readings `get deployments` surfaces per instance: one row per deployment with its average request
rate, average error rate, and how many instances actually contributed a reading (a deployment whose
instances have never reported shows `0`, which is why that count is worth reading alongside the
averages). The same rollup backs the console's Metrics screen.

The response keys each row by deployment name alone and **carries no tenant id**, while the
authorization filter behind it is per-tenant — so a caller who may read two tenants that each run a
deployment of the same name gets two rows nothing in the payload distinguishes. Every row is kept
exactly as the server sent it, and both sides of such a collision carry `ambiguous: true`; under
the default table format a `note:` line names the deployments affected. Nothing is merged (that
would invent an average across tenants the server never computed) and nothing is dropped — there is
no client-side join available to do better, since the response never names a tenant.

`metrics-history` and `traces-history` read a *process's* own shipped observability history back out
of [Muninn](../architecture/node-topology.md#muninn) through the control plane's own proxy — the
terminal equivalent of the console's Metrics and Traces screens, so an investigation can be scripted
instead of clicked. There is no discovery API for which process ids exist: every non-agent id is the
`host:port` that process chose for itself at startup, an agent's is its node id, and a worker's is
the composite `{nodeId}:{workerId}` (a worker has no listening address of its own). An unrecognized
process kind is rejected locally, listing the five that exist. `--since <cursor>` (a line timestamp)
reads forward from that point and is the one filter the proxy forwards; `--limit N` is therefore
applied client-side, keeping the most recent N of an oldest-first response, the same treatment
`events` gives its own `--limit`. Under the table format the last line's own timestamp is printed as
the cursor to resume from. A cluster with no Muninn configured answers `not found: no muninn
endpoint configured` — history is the only place a process's metrics or traces ever live, so there
is no live-process fallback the way `logs` has one.

`service`/`networkpolicy` manage the [Service abstraction](../architecture/service-fabric.md#the-service-abstraction-a-stable-name-in-front-of-a-deployment)
— a stable name in front of a Deployment's live, ephemeral endpoints, the ClusterIP analogue named
in the platform's own network-model design. `set service` POSTs to the `/services` collection
rather than PUTting a per-name path, since a Service names itself in its own request body;
`--deployment` may repeat (the set of workload names a Service fronts). `--target-port` is
genuinely optional and is *not* defaulted to `--port`: given, it is authoritative and only an
instance actually reporting that port contributes an endpoint; omitted, the Service resolves to
whatever single port each backing instance reports (an instance reporting several then contributes
none, since nothing names which one is meant). Declaring a `--target-port` no backing instance
currently reports is admitted, not rejected — instance ports are reported at runtime and change —
and the control plane answers with an `X-Gimle-Warning` header the CLI prints as a `warning:` line
on stderr. The same advisory-not-refusal treatment covers a Service whose `--deployment` set
overlaps another Service's in the same tenant: a shared front door is a legitimate pattern, so the
create succeeds and names the overlapping Service in a warning rather than failing.
`--external-name <host>` declares the ExternalName shape instead — the Service resolves to that
external hostname at the target port (or `--port` when none is declared), with no in-cluster
backing (and therefore no `--deployment`);
`--session-affinity` asks the Bifrost proxy layer to pin each caller address to one backend by
consistent hash rather than round-robining. `service endpoints`
resolves the Service's current live backing-instance set on every call, never a cached value, so it
never lags a reconcile interval behind. `networkpolicy` manages the accompanying NetworkPolicy
analogue — a deny-by-default restriction on which other tenants may call into one tenant's own
Services (ingress) and which tenants its own workloads may call out to (egress); `--tenant` is
required (a NetworkPolicy always restricts exactly one tenant's own traffic) — including on `get`
and `delete` when a name is given, since unlike every other by-name resource here a NetworkPolicy
has no untenanted namespace to fall back to. `--deployment` scopes
it to specific workloads instead of the whole tenant when given, and `--service-interface` scopes
it to named exported fabric interfaces. A direction is restricted only when expressed, and at least
one must be: `--allowed-caller-tenant` (repeatable) allows the named caller tenants in, while
`--deny-all-callers` is the allow-nobody form of the same direction; `--allowed-callee-tenant` /
`--deny-all-callees` restrict the egress direction the same way.

`ingress` reads and removes an [Ingress](../architecture/service-fabric.md#ingress-routes-as-a-resource)
— the declared gateway route table. `get ingresses` with no name lists every Ingress (narrowed
client-side by `--tenant` when given); with a name it reports that one, and `--tenant` is how a
tenant's own Ingress is addressed, since a bare name reaches only the untenanted namespace. There is
deliberately no `set ingress`: a route carries up to six fields whose meaning depends on its kind,
which reads worse on a command line than in the `kind: Ingress` manifest `apply -f` accepts. Every
apply is compare-and-set guarded — a manifest carrying the `version` a `get ingress` printed is
refused with a `409` naming both versions if the stored Ingress has moved on since, and a manifest
carrying no `version` is guarded against whatever is stored when the CLI reads it — so an edit
built on a revision another operator has already replaced fails loudly instead of discarding their
change.

`alertrule` manages an [AlertRule](../architecture/observability.md#alerting) — a declared threshold
on one Deployment's own observed signal (`--metric`, one of `REQUEST_RATE_PER_SECOND`,
`ERROR_RATE_PER_SECOND`, `QUEUE_DEPTH`, `CPU_MILLICORES_USED`, `MEMORY_BYTES_USED`) that posts a
webhook notification once when crossed and again once resolved. `set alertrule` POSTs to the
`/alertrules` collection the same way `set service` does, since a rule names itself in its own
request body; `--disabled` creates the rule silenced (never evaluated) rather than enabled by
default.

`limitrange` manages a tenant's [LimitRange](../architecture/multi-tenancy.md#limitrange) — a
per-workload min/max bound on a single Deployment's own `resources.request`/`resources.limit`,
distinct from `tenant`'s own aggregate quota. Each of the four bound pairs
(`--min-request-*`/`--max-request-*`/`--min-limit-*`/`--max-limit-*`) is independently optional and
all-or-nothing — setting one flag of a pair without the other is rejected as a 400, not silently
guessed. Unlike `service`/`networkpolicy`, `limitrange` is PUT by `tenantId` directly (like `tenant`
itself) rather than POSTed to a collection, since a LimitRange is naturally one-per-tenant.

`logs` accepts two content filters alongside its timestamp cursor, and both are applied by
whichever log reader answers — the owning node's agent, the control plane's own platform log, or
Muninn's shipped history for a node that is already gone — so a filtered read never ships a whole
high-volume stream just to have most of it discarded at the client. `--level` is a **threshold**,
not an equality test: `--level=WARN` keeps `WARN` and `ERROR`, and a line carrying no level at all
(a raw, unstructured SYSTEM capture) is never kept by one, since it cannot be placed on the scale.
`--contains` is a plain, **case-insensitive substring** — never a regular expression, so pasting a
message fragment containing `(`, `[` or `.` matches literally — tested against a line's
human-readable fields only (`message`, `logger`, `stackTrace`, `raw`), not machine identifiers like
`nodeId` or `thread`. Both work together, both work under `--follow`, and a query matching nothing
prints what was filtered on rather than exiting silently (under `-o json` that becomes an empty
array — still valid JSON to pipe onward). An unrecognized level fails locally, before any request
is sent. The console's Logs screen exposes the identical two filters, backed by
the identical `level`/`contains` query parameters.

`audit list` reads the cross-resource audit trail (see
[Authentication and authorization](../architecture/authn-authz.md#audit-logging)) — every
`WRITE`/`DELETE` authorization decision, allowed and denied, across both the control plane and
Fafnir. Every filter is optional and independently combinable; omitting all of them lists the most
recent events cluster-wide.

Without `--limit` the whole matching trail comes back in one response, so a one-shot query never
has to page. With one, `--limit` is a page size and the command reports how many events matched in
total plus the cursor for the next page (`note: more events match; continue with --cursor …`);
`--cursor <token>` resumes from it and `--all` follows it to exhaustion, printing every matching
event as a single table. A cursor is only valid alongside the exact filters it was issued under —
the control plane rejects a mismatched one rather than silently answering a different question.

The trail is a fixed-size ring, so the cursor names an event rather than an offset: neither
decisions recorded while you page nor events evicted from the oldest end can shift the next page.
If the event a cursor anchored on is itself evicted before you use it, the command says so
explicitly (`note: the page this cursor pointed at has already been discarded …`) instead of
returning a plausible-looking wrong page — eviction is strictly oldest-first, so everything older
than that anchor is gone too. That is distinct from the trail-wide retention note (`note: the audit
trail has exceeded its retention cap …`), which describes the cluster's whole record regardless of
what this query asked for. Under `-o json` the command prints only the events array, with no notes,
so the output stays a single parseable document — use `--all` there to get the complete set.

The `role`/`rolebinding`/`account` verbs manage RBAC — see
[Authentication and authorization](../architecture/authn-authz.md). `--permission` may repeat (a
role is a set of permissions); the optional third segment of `resource:verb:tenant` scopes a grant
to one tenant instead of cluster-wide. Each of those three segments also accepts `*`, the wildcard
for every value in that position — stored as a wildcard, so a `"*:read"` grant covers a resource
kind the platform gains later without the role being re-edited. An unknown resource or verb is
rejected by the CLI itself, before any request is sent; the qualifier segment takes no `*` (see the
authorization page for why). `set account` doubles as create-or-reset-password, matching
`set tenant`/`set config`'s existing create-or-update convention — the password is sent once over
the same authenticated mTLS connection every other write already uses and is hashed server-side,
never stored or echoed back in plaintext. `--groups` (comma-separated) is what lets a `group:`
`RoleBinding` subject match this account; omitting it on a reset preserves whatever groups the
account already had, so resetting a password never silently strips group membership as a side
effect.

`can-i` is the `kubectl auth can-i` analogue: `gimle can-i write deployments --tenant acme` asks
the control plane's self-subject access review (`GET /authz/can-i`) whether the calling identity
would be authorized for that action, without performing it, and prints `yes`/`no` (the full review
as JSON under `-o json`). Verb and resource are matched case-insensitively, `-` is accepted for
`_` (`network-policy` works), and a plural `s` is tolerated so the nouns the other verbs use spell
valid questions here too.

## Previewing a submission: `apply --dry-run`

`can-i` answers only the authorization question. `apply --dry-run` answers the rest of it: the
control plane runs everything a real submission runs -- authorization, manifest kind/name
validation, artifact resolution, the admission chain (tenant quota, LimitRange, ConfigMap and
SecretMap references, policy config) -- plus a placement forecast, then writes nothing and returns
the verdict.

Because it is the *same* admission chain and the *same* scheduler the real path uses, not a
validation-only copy of them, the reason a dry-run gives is verbatim the reason the real request
would have given. A preview that could disagree with the request it predicts would be worse than
no preview at all.

```bash
gimle apply -f deployment.yaml --dry-run --server 127.0.0.1:8080
```

```text
dry run: Deployment/orders (tenant acme)
PASSED	rbac	the calling identity may WRITE DEPLOYMENT in tenant acme
PASSED	manifest	kind and name match the addressed route
PASSED	artifact	resolved, sha256 3f7c…
FAILED	admission	workload orders would push tenant acme past its resource quota: …
SKIPPED	placement	not evaluated: the submission would be rejected at the 'admission' stage
verdict: would be rejected (the real request would answer 409)
```

`-o json` emits the same verdict as a structured object -- `admitted`, `wouldRespondStatus`, the
per-stage `checks` array, and, when placement was evaluated, a `placement` object naming the node
each replica would land on and the scheduler's own message for any that would not.

**Exit code.** A dry-run that predicts a rejection exits with the code the real `apply` would have
exited with -- `5` for an admission rejection, `2` for a manifest or artifact problem, `4` for the
reserved-tenant veto -- so `gimle apply --dry-run` drops into a pipeline as a gate whose exit
status means exactly what the unguarded `gimle apply` after it would mean.

**Placement is advisory.** No submission is ever *rejected* for being unschedulable: an unplaceable
replica simply waits for room, which is what `unplacedCount` -- and, once a reconciler tick has
actually refused an index, `unplacedReason` -- reports on the deployment afterwards.
So a forecast of unplaceable replicas prints as a `warning:` line on stderr and leaves the exit
code at `0` -- the manifest really would be accepted.

```bash
gimle apply -f deployment.yaml --dry-run --server 127.0.0.1:8080
# warning: instance 0 would remain unplaced: deployment orders instance 0 cannot be placed: it
# requests memory=64Mi cpu=100m, and none of the 2 candidate node(s) with TIER_1 support has room
# -- memory is short by 48Mi (the most any candidate has free is 16Mi, on node-b); …
```

`--dry-run` is supported for the five placeable workload kinds -- `Deployment`, `Job`, `CronJob`,
`DaemonSet`, `StatefulSet` -- which are exactly the kinds the admission chain and the scheduler
reason about. On any other kind it is **refused**, not silently ignored: a flag that quietly does
nothing is how an operator ends up believing a manifest was previewed when it never was.

## Watching a resource converge

`--watch` (`-w`) is the `kubectl get … --watch` analogue: instead of one point-in-time snapshot, the
command keeps reading and reports what changed, so a rollout, a scale-up or a cordon draining a node
can be observed as it lands rather than by re-running the same command in a shell loop.

It is offered on the reads whose value is in watching them converge:

```text
gimle get deployments|jobs|cronjobs|daemonsets|statefulsets [name] --watch
gimle get nodes --watch
gimle get node-assignments <nodeId> --watch
```

Every other `get` resource — `tenants`, `limitranges`, `roles`, `rolebindings`, `accounts`,
`config`, `services`, `networkpolicies`, `alertrules`, and every custom kind — deliberately has no
watch form: those are operator-authored declarative state with no controller converging them, so
they change when somebody applies a change and not otherwise. Asking for `--watch` on one of them is
rejected outright rather than silently accepted as a poll that would never print a second line.

**It is a client-side poll, not a subscription.** The control plane exposes no watch/streaming API
outside `/logs`, so the CLI simply re-issues the same read the one-shot form would, on an interval —
which is exactly why the interval is a documented, overridable knob rather than a hidden constant:

| Flag | Default | Meaning |
| ---- | ------- | ------- |
| `--watch`, `-w` | off | watch instead of reading once |
| `--watch-interval=SECS` | `2` | seconds between polls; fractional values allowed, `0` or negative rejected (a busy loop against the control plane is not a faster watch) |
| `--watch-ticks=N` | unbounded | print `N` snapshots and exit normally, instead of watching until interrupted |

**What each tick prints.** Not the whole table again — a terminal refilling with an identical table
every couple of seconds is unreadable, and the interesting thing is the two rows that moved, not the
eighty that did not. The first tick prints the full snapshot; every later tick prints only the rows
that changed, one line each, under the header printed **once**. Every line carries a leading `EVENT`
column so the three cases stay distinguishable:

```text
EVENT     name              module                          artifactPath  tenantId  replicas  health
ADDED     greeter-provider  com.gimle.example.greeter@1.0.0  -             acme      2/2       HEALTHY
ADDED     greeter-consumer  com.gimle.example.consumer@1.0.0 -             -         1/1       HEALTHY
MODIFIED  greeter-provider  com.gimle.example.greeter@1.0.0  -             acme      2/4       UNPLACED(2)
MODIFIED  greeter-provider  com.gimle.example.greeter@1.0.0  -             acme      4/4       HEALTHY
DELETED   greeter-consumer  com.gimle.example.consumer@1.0.0 -             -         1/1       HEALTHY
```

`DELETED` is why the event column exists at all: a poll-derived diff has nowhere else to say that a
row vanished, which a bare re-print of the surviving rows could not express.

**`-o json` emits NDJSON.** A stream that never ends has no closing bracket, so `--watch` prints one
JSON object per line — the same convention `gimle logs --follow` already uses against its own
one-array-per-request non-follow form. Each line is an envelope, because the event kind is derived
by the CLI's own diff and is not a field of the resource:

```json
{"event":"MODIFIED","object":{"spec":{"name":"greeter-provider", "...":"..."}}}
```

The `object` is exactly the shape the non-watch `-o json` read returns, so `jq` filters written
against `gimle get deployments -o json` work unchanged against `.object`.

**Termination.** Ctrl-C ends a watch cleanly, with no stack trace and no partially written line;
`--watch-ticks=N` is the bounded form for a script that wants N snapshots and a normal exit instead.

**When the server goes away.** A failed *first* poll fails the command outright, exactly as the
one-shot form would — there is nothing to watch yet. A failure *later* is reported on stderr and
retried with an exponential backoff (capped at 30s), so a control plane bouncing mid-rollout does
not end the watch; after five consecutive failed polls the watch gives up with the underlying
failure's own [exit code](#exit-codes). It never spins silently, and never hangs against a server
that is not coming back.

## Contexts: talking to more than one cluster

`gimle context` names the control planes this CLI talks to, so moving between dev/staging/prod is
`gimle context use staging` rather than a re-typed `--server` on every command. `set` creates or
updates a named endpoint (the first one set also becomes the current one, since a config holding
exactly one endpoint and no selection would otherwise still need a separate `use`); `use` switches
the selection; `list`/`show` report what is configured and which is current; `delete` removes one,
clearing the selection if it was the current one. Every one of these is purely local — nothing
contacts a control plane, so they are the only verbs that work with no server resolvable at all,
which is exactly the state you are in before the first `set`. `set` takes the endpoint through the
same global `--server` flag every other verb uses — it is the one subcommand that reads it as a
value to *store* rather than an address to dial.

The file is `~/.gimle/config` (the same `~/.gimle` directory the rest of the tooling's local state
lives under); `-Dgimle.cli.configFile=<path>` selects a different one. It is optional in the
strongest sense: a CLI that has never run `context set` never creates it, and every command still
works off `--server`/`GIMLE_SERVER` exactly as before. It is written through a temp file and an
atomic rename, with owner-only permissions (`0600`, and `0700` on the directory) where the
filesystem supports POSIX ones, and holds **endpoints only** — no credential ever lands here, since
a client certificate and key still come from `gimle.tls.certFile`/`keyFile`.

```yaml
currentContext: "prod"
contexts:
  - name: "prod"
    server: "cp.prod.internal:8080"
  - name: "dev"
    server: "127.0.0.1:8080"
```

A file that is unreadable or malformed never breaks unrelated commands: it is consulted only when
neither `--server` nor `GIMLE_SERVER` is set, and when it is consulted and cannot be used, the CLI
warns on stderr naming the file and the problem, then reports that no server is configured — rather
than failing with a parse error. The `context` verbs themselves do report it as a hard error, since
that is precisely the file you asked them to operate on.

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

## Applying non-workload manifests

`apply -f` also covers `Service`, `NetworkPolicy`, `Tenant`, `LimitRange`, `Role`, `RoleBinding`,
and `Account` — the same manifest-driven convention Deployment/Job/CronJob/DaemonSet/StatefulSet
already follow, alongside their own bespoke `gimle set <kind> ...` flag-based commands (both stay
available; a manifest is just an alternative to spelling every field as a flag). Unlike the
workload kinds, these seven have no `PUT /{kind}/{name}`-shaped route to send the YAML bytes to
directly, so the CLI parses the manifest client-side and builds the identical JSON body `set`
already builds from flags, then issues the same request `set` would:

```yaml
kind: Service
name: web
deploymentNames: [orders-service]
port: 8080
targetPort: 9090
```

```yaml
kind: NetworkPolicy
name: acme-policy
tenantId: acme
allowedCallerTenantIds: [partner]   # present (even empty) restricts; absent leaves it open
```

```yaml
kind: Role
name: deployment-reader
permissions:
  - resource: deployment
    verb: read
  - resource: config
    verb: write
    tenantScope: acme
  - resource: "*"                    # every resource kind, including ones added later
    verb: read
```

```yaml
kind: LimitRange
name: acme                           # the tenant the bounds apply to
minRequest: {memory: 24Mi, cpu: 15m} # each bound is a nested block, never a flat
maxLimit: {memory: 512Mi, cpu: 500m} # minRequestMemory-style field
```

`Tenant`, `LimitRange`, `RoleBinding`, and `Account` manifests use `name:` for the identifier the
same way every other kind does, even though their own `get`/`set`/`delete` verbs call it `id` (or
`username`) — see each kind's own manifest shape by round-tripping `gimle get <kind> <name>
-o json` and reshaping it, which yields exactly the nested shape a manifest wants. A `LimitRange`
whose bounds are spelled as flat, flag-mirroring fields (`minRequestMemory: 24Mi`) is **rejected**,
not quietly stored as a range that bounds nothing, and so is one declaring no bound at all —
removing a tenant's bounds is `gimle delete limitrange <tenantId>`, which says so unambiguously.

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

# Watch a rollout land instead of re-running the same command in a shell loop: the first tick
# prints the whole table, later ticks print only the rows that changed, under an EVENT column
gimle get deployments --watch --server 127.0.0.1:8080
gimle get deployment orders-service-deployment -w --watch-interval=5 --server 127.0.0.1:8080

# Bounded, and NDJSON for a script: ten snapshots, one {"event":...,"object":{...}} per change
gimle get nodes --watch --watch-ticks=10 -o json --server 127.0.0.1:8080 | jq -r '.object.nodeId'

# A bad rollout's history and a way back -- revisions lists newest-first, rollback with no
# --to-revision restores the one immediately before the current one; statefulset/daemonset accept
# the identical two verbs
gimle deployment revisions orders-service-deployment --server 127.0.0.1:8080
gimle deployment rollback orders-service-deployment --server 127.0.0.1:8080
gimle deployment rollback orders-service-deployment --to-revision 2 --server 127.0.0.1:8080

# Tail a target's logs live -- the CLI-side equivalent of the console's own Logs screen
gimle logs instance/greeter-consumer-deployment/0 --follow --server 127.0.0.1:8080

# Watch the whole cluster settle instead of re-running `get` by hand -- a live, read-only view of
# nodes and instances, with a per-instance drill-down and log tail behind Enter
gimle top --server 127.0.0.1:8080

# Narrow a high-volume log to what an incident is actually about: a level threshold
# (WARN keeps WARN and ERROR) and a plain case-insensitive substring, both applied
# server-side, both usable together and under --follow
gimle logs node/node-1 --level=WARN --server 127.0.0.1:8080
gimle logs instance/orders-service-deployment/0 --contains="timed out" --server 127.0.0.1:8080
gimle logs controlplane --level=ERROR --contains=quota --follow --server 127.0.0.1:8080

# Inspect which node an instance landed on, and what else is scheduled there
gimle get nodes --server 127.0.0.1:8080
gimle get node-assignments node-1 --server 127.0.0.1:8080

# Label a running node so manifests requiring that label can be placed on it. A trailing "-"
# removes a label instead of adding it, the same shorthand kubectl uses. This edits only the
# operator-applied half: labels the node reported for itself at startup (-Dgimle.node.labels)
# stay put, and survive the node re-registering.
gimle label node node-1 edge --server 127.0.0.1:8080
gimle label node node-1 gpu ssd --server 127.0.0.1:8080
gimle label node node-1 edge- --server 127.0.0.1:8080

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

# Which deployments are actually taking traffic, and which are erroring -- every row names its
# own tenant, so two tenants running a same-named deployment read as the two distinct rows they are
gimle metrics --server 127.0.0.1:8080
gimle -o json metrics --server 127.0.0.1:8080

# A process's own shipped metrics and traces, without opening the console. The processId is that
# process's own self-reported host:port -- a node agent's is its nodeId, a worker's is
# {nodeId}:{workerId}. --since resumes from the cursor the previous read printed.
gimle metrics-history CONTROLPLANE 127.0.0.1:8080 --limit 50 --server 127.0.0.1:8080
gimle metrics-history WORKER node-1:worker-2 --since 2026-08-30T10:00:00Z --server 127.0.0.1:8080
gimle -o json traces-history AGENT node-1 --server 127.0.0.1:8080

# Name each cluster once instead of retyping --server; the selection lives in ~/.gimle/config
gimle context set dev --server 127.0.0.1:8080
gimle context set prod --server cp.prod.internal:8080
gimle context use prod
gimle context list
gimle get deployments                      # dials cp.prod.internal:8080
gimle get deployments --server 127.0.0.1:8080   # ...unless a flag says otherwise

# A stable name in front of a Deployment's live endpoints, and who else may call it
gimle set service orders-web --deployment orders-service-deployment --port 8080 --server 127.0.0.1:8080
gimle service endpoints orders-web --server 127.0.0.1:8080
gimle set networkpolicy orders-policy --tenant orders-platform --allowed-caller-tenant billing --server 127.0.0.1:8080

# Add or drop one caller without restating the whole allow list. The whole-list form above
# replaces every field it omits, so reconstructing a policy client-side to change one entry can
# silently un-scope it -- and two operators doing that concurrently lose one edit. These flags
# read, amend and write under a version guard instead, so a concurrent edit is refused rather
# than overwritten.
gimle set networkpolicy orders-policy --tenant orders-platform --add-allowed-caller-tenant analytics --server 127.0.0.1:8080
gimle set networkpolicy orders-policy --tenant orders-platform --remove-allowed-caller-tenant billing --server 127.0.0.1:8080

# Who deleted the acme tenant's secrets in the last hour -- allowed and denied attempts alike
gimle audit list --tenant acme --resource SECRET --since 1712000000000 --server 127.0.0.1:8080

# Schedule a recurring job, list cronjobs, then fire one immediately without waiting for its
# schedule -- generated Jobs show up on `gimle get jobs`, named nightly-cleanup-<epochSeconds>
gimle apply -f cronjob.yaml --server 127.0.0.1:8080
gimle get cronjobs --server 127.0.0.1:8080
gimle cronjob trigger nightly-cleanup --server 127.0.0.1:8080

# Pause a schedule without losing its firing history: export the CronJob as a manifest, set
# suspend: true, and apply it back. `get` shows the current value in its own suspend column, and
# `-o manifest` round-trips it, so re-applying with suspend removed resumes the schedule.
# A suspended CronJob still answers `gimle cronjob trigger` -- that is a manual run, not a schedule.
gimle get cronjobs nightly-cleanup -o manifest --server 127.0.0.1:8080 > cronjob.yaml

# Run one instance on every eligible node (topology-derived, no --replicas flag to set)
gimle apply -f daemonset.yaml --server 127.0.0.1:8080
gimle get daemonsets node-exporter --server 127.0.0.1:8080

# Ordered rollout, sticky per-index placement -- get shows each index's own nodeId
gimle apply -f statefulset.yaml --server 127.0.0.1:8080
gimle get statefulsets orders-statefulset --server 127.0.0.1:8080

# Push one jar, tagged with its owning tenant
gimle artifact push target/orders-service-1.0.0.jar --tenant orders-platform --server 127.0.0.1:8080

# A vessel jar carries no gimle-module.yaml, so there is no descriptor to read a coordinate from --
# name it explicitly instead
gimle artifact push target/legacy-report-runner.jar --vessel --name report-runner --version 2.1.0 --server 127.0.0.1:8080

# Push a whole multi-jar app in one command instead of one `artifact push` per jar
gimle apply -f artifactset.yaml --server 127.0.0.1:8080

# Per-tenant resource caps
gimle set tenant acme --max-memory-bytes 536870912 --max-cpu-millicores 2000 --max-instances 10

# A tenant's baseline stance on inbound cross-tenant fabric calls before any NetworkPolicy names
# it. OPEN (the default) means an empty policy set permits; DENY_BY_DEFAULT means an empty policy
# set denies, so a freshly provisioned tenant is closed from the moment it exists rather than
# staying open until someone remembers to write its first policy.
gimle set tenant acme --max-memory-bytes 536870912 --max-cpu-millicores 2000 --max-instances 10 --isolation-posture DENY_BY_DEFAULT

# Bound what any single deployment in acme may request/limit
gimle set limitrange acme --min-request-memory 64Mi --min-request-cpu 50m --max-limit-memory 512Mi --max-limit-cpu 500m
```

`GIMLE_SERVER=127.0.0.1:8080` in your shell's environment removes the need to repeat `--server` on
every call above — see [Getting started](../tutorials/getting-started.md) for the one-time
`~/.m2/settings.xml` setup that also makes `mvn gimle:deploy` (a thin wrapper around `apply`)
available.
