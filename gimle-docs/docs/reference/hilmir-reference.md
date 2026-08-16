---
sidebar_position: 5
---

# `gimle-hilmir` reference

`gimle-hilmir` is two tools in one binary. The `validate`/`plan`/`up`/`down`/`status`/`pki init`
verbs are a declarative-topology cluster bootstrapper — they read a topology YAML document and turn
it into real, running Gimlé processes on the local machine, or the exact per-machine process
commands the topology implies. The `deploy`/`upgrade`/`rollback`/`undeploy`/`releases`/
`release-status` verbs are a Helm-equivalent release lifecycle layered on top of an already-running
cluster — they talk to the control plane's own HTTP API, the same way `gimle-cli` does, and never
touch a topology document at all.

## Machine bootstrap verbs

```text
hilmir validate -f <topology.yaml>
hilmir plan -f <topology.yaml> [--machine <name>]
hilmir up -f <topology.yaml> --machine <name>
hilmir down --machine <name> [--data-root <path>]
hilmir status --machine <name> [--data-root <path>]
hilmir pki init -f <topology.yaml>
```

`validate` checks a topology document for structural and semantic problems (missing machines, port
conflicts, an even-numbered store replica count, TLS material referenced but never declared) without
starting anything. `plan` resolves a validated topology into the exact per-machine process commands
each of Gimlé's process kinds expects — useful for inspecting what `up` would run before it runs it.
`up` actually spawns every process a topology assigns to `--machine`, waiting on any cross-machine
prerequisite (a store replica another machine hosts, say) via a plain TCP-connect readiness check
before starting anything that depends on it; `down`/`status` act on the run ledger `up` wrote, so
neither needs the topology document again. `pki init` mints the cluster's certificate authority and
every process's leaf certificate up front, for a topology that declares `tls:`.

## Release verbs

```text
hilmir deploy -f <bundle.yaml> [--values <file>] [--set k=v]... [--wait] [--dry-run] [-o json]
hilmir upgrade -f <bundle.yaml> [--values <file>] [--set k=v]... [--wait] [--dry-run] [-o json]
hilmir rollback --release <name> [--to-revision r] [--wait] [--dry-run] [-o json]
hilmir undeploy --release <name> [--keep-tenants] [-o json]
hilmir releases [-o json]
hilmir release-status <name> [-o json]
```

Every release verb targets an already-running control plane over plain HTTP (`--server host:port`,
or the `GIMLE_SERVER` environment variable — the same precedence `gimle-cli` itself uses), through a
small HTTP client `gimle-hilmir` builds and owns itself rather than depending on `gimle-cli` for one
class. A **bundle** is a `kind: Bundle` manifest declaring the tenants, plain config entries,
secrets, and workload manifests a release is made of; a **release** is the record of one bundle
having been applied, tracked entirely in the control plane's own plain config store under a fixed
bookkeeping tenant, `gimle-hilmir` — no separate storage, no local state file.

### The bundle format

```yaml
kind: Bundle
name: greeter-suite
version: 1.0.0
values: # defaults; overridden by --values <file>, then by --set k=v (repeatable, highest precedence)
  apiToken: ""
tenants:
  - id: acme
    quota: {maxMemoryBytes: 268435456, maxCpuMillicores: 1000, maxInstances: 10}
config:
  - {tenant: acme, key: greeting.prefix, value: "Hello"}
secrets:
  - {tenant: acme, key: api.token, value: "${values.apiToken}"}
workloads:
  - file: provider-deployment.yaml # sibling file, resolved relative to the bundle file
  - manifest: | # inline manifest (raw YAML)
      kind: Deployment
      name: greeter-consumer
```

`${values.key}` substitution is deliberately minimal — no conditionals, no loops, no expression
language — and applies to `config[].value`, `secrets[].value`, and the full text of every workload
manifest (both `file:`-referenced and inline). Precedence mirrors Helm's own: the bundle's own
`values:` block first, a `--values <file>` (a flat YAML mapping of key to scalar) next, then any
number of repeatable `--set key=value` flags, which win over both. A reference with no default and
no override supplied is a named, immediate error at render time — never a literal `${values.x}`
string sent to the control plane.

### `deploy` / `upgrade`

`deploy` renders the bundle, refuses if a release under this bundle's own `name` already exists
(clearly naming `upgrade` as the alternative), then applies its full state in order — every declared
tenant, then every plain config entry, then every secret (value base64-encoded, matching `gimle
secret set`'s own wire convention), then every workload manifest (its `kind:` field picks the right
control-plane URL prefix, mirroring `gimle apply`'s own dispatch) — and records this as revision 1.
`upgrade` requires an existing release, applies the new bundle's full state the same way, then
**prunes**: any workload the *previous* revision applied that the new bundle no longer declares gets
deleted, matching Helm's own upgrade semantics.

`--dry-run` renders (and, for `upgrade`, computes the prune list against the release's existing
ledger state) and prints the plan without applying anything — for `deploy`, that means no
control-plane call at all, since there's no existing release to check against; for `upgrade`, only
the read calls needed to compute an accurate plan run, never a write. `--wait` polls every applied
workload to its own kind-appropriate readiness signal before returning:

| Kind | "Ready" means |
|---|---|
| Deployment / DaemonSet / StatefulSet | Every instance has a live observation, and every observation's `lifecycleState` is `ACTIVE`. An empty instance list is not yet ready. |
| Job | The job's `phase` has reached a terminal state (`SUCCEEDED` or `FAILED`) — waiting means waiting for completion, not asserting success, the same posture `kubectl wait` itself takes. |
| CronJob | A single successful `GET` — there's no per-instance active state for a schedule, so existence is the whole signal. |

### `rollback`

```text
hilmir rollback --release greeter-suite --to-revision 2
```

Reads a past revision's full snapshot (the rendered manifests actually applied at that revision, not
the original bundle file, which may no longer exist) and re-applies it as a **new** revision, pruning
anything the *current* revision has that the target one doesn't — never rewrites history in place,
the same way `helm rollback` itself always creates a new revision rather than time-traveling. With no
`--to-revision`, rolls back to the revision immediately before the release's current one, the same
"undo my last change" default `helm rollback` uses when given no explicit revision.

### `undeploy`

Deletes every workload the release's current revision lists (in reverse of the order they were
applied), deletes the tenants it created unless `--keep-tenants` is given, then deletes the release's
own ledger rows entirely. This is a v1 simplification versus Helm's own `--keep-history`: once
undeployed, a release's revision history is gone, not merely hidden.

### `releases` / `release-status`

`releases` lists every release's name, current revision, and bundle version. `release-status <name>`
shows that plus each of the current revision's resources' live status, fetched fresh from the
control plane.

### The release ledger

A release's state is two kinds of row in the control plane's plain config store, under the fixed
tenant `gimle-hilmir` (created idempotently the first time any release verb runs against a cluster
that doesn't have it yet):

- `hilmir.release.<name>.meta` — a small pointer: `{bundleName, bundleVersion, currentRevision,
  tenants}`.
- `hilmir.release.<name>.rev.<n>` — that revision's full snapshot: every rendered manifest actually
  applied, `{revision, resources: [{kind, name}], appliedAtEpochMilli, tenants, config, secrets,
  workloads, rollbackOfRevision}`.

The naming deliberately uses `.` rather than `@` as the revision delimiter, even though it's the same
idea Fafnir's own `key@N`/`key@meta` versioned-secret convention uses: the control plane's `GET
/config/{tenantId}` list endpoint already strips any key whose text after its last `@` character
reads `meta` or is all digits, on the assumption that shape always means a Fafnir-managed secret row
— a ledger key in that exact shape would be invisible to `releases`/`release-status`, which have no
per-key `GET` to fall back on, only list-and-filter.
