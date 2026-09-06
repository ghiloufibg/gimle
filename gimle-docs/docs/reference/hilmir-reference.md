---
sidebar_position: 5
---

# `gimle-hilmir` reference

`gimle-hilmir` is five tools in one binary. The `validate`/`plan`/`up`/`down`/`stop`/`status`/`pki
init` verbs are a declarative-topology cluster bootstrapper — they read a topology YAML document and turn
it into real, running Gimlé processes on the local machine (or, for `up`/`down`/`status`, on a
remote machine over SSH via the opt-in `--remote` flag — see [Remote (SSH) fleet
bootstrap](#remote-ssh-fleet-bootstrap) below), or the exact per-machine process commands the
topology implies. `upgrade-cluster` restarts a subset of those already-running processes
against a newly-unpacked platform binary, one machine and one role at a time, and has its own
`--remote` too — see
[Cluster upgrade (platform binary rollout)](#cluster-upgrade-platform-binary-rollout) below; note this
is a **different** verb from the release `upgrade` further down, despite the shared word. `store
add`/`store remove` are an operator-facing surface over the store's own live Raft membership change,
talking directly to an already-running cluster over the same binary `StoreClient` protocol
`gimle-mimir` itself uses — see [Store membership verbs](#store-membership-verbs) below. The
`deploy`/`upgrade`/`rollback`/`undeploy`/`releases`/`release-status` verbs are a Helm-equivalent
release lifecycle layered on top of an already-running cluster — they talk to the control plane's own
HTTP API, the same way `gimle-cli` does, and never touch a topology document at all. `sync` is a
further, GitOps-flavored verb built on that same release lifecycle — see
[Sync (GitOps-style reconciliation)](#sync-gitops-style-reconciliation) below. `doctor`/`init` are a
further, independent concern: deployability diagnostics and manifest scaffolding for a single built
jar, needing neither a topology document nor a running control plane (`doctor --server` only adds
cluster-aware checks on top of its own static ones) — see [`doctor`/`init`](#doctorinit) below.
`enable`/`disable` are a fifth concern layered directly on top of the release verbs: turning a named
platform extension (today, only `gateway`) on or off against an already-running cluster, without an
operator ever hand-writing its bundle file — see
[Extensions (`hilmir enable`/`hilmir disable`)](#extensions-hilmir-enablehilmir-disable) below.

## Machine bootstrap verbs

```text
hilmir validate -f <topology.yaml>
hilmir plan -f <topology.yaml> [--machine <name>]
hilmir up -f <topology.yaml> --machine <name>
hilmir up -f <topology.yaml> --remote [--machine <name>] [--ssh-user <user>]
    [--ssh-key <path>] [--ssh-port <port>] [--install-dir <path>]
hilmir down --machine <name> [--data-root <path>]
hilmir down -f <topology.yaml> --remote [--machine <name>] [--data-root <path>]
    [--ssh-user <user>] [--ssh-key <path>] [--ssh-port <port>] [--install-dir <path>]
hilmir stop --machine <name> (--role <ROLE> | --id <process-id>) [--data-root <path>]
hilmir stop -f <topology.yaml> --remote --machine <name>
    (--role <ROLE> | --id <process-id>) [--data-root <path>]
    [--ssh-user <user>] [--ssh-key <path>] [--ssh-port <port>] [--install-dir <path>]
hilmir status --machine <name> [--data-root <path>]
hilmir status -f <topology.yaml> --remote [--machine <name>] [--data-root <path>]
    [--ssh-user <user>] [--ssh-key <path>] [--ssh-port <port>] [--install-dir <path>]
hilmir pki init -f <topology.yaml>
```

`pki init` mints the cluster's shared secret material once, locally, before any machine is bootstrapped:
for an mtls topology, the cluster CA and one leaf certificate per (role, machine hostname) via
`PkiBootstrapMain` — whose one-time bootstrap console password is written to
`bootstrap-password.txt` inside `tls.materialDir` (owner-only; this command captures its
subprocess's output to a log file, so the password is never printed into it) and whose path the
command reports; and, whenever `fafnir.keyFile` is configured and no file already exists there, a
fresh Fafnir key — the only thing it does at all for a plaintext topology, and only when Fafnir spans
more than one machine (a single-machine Fafnir still just generates its own key at first start, same
as ever). `--remote up` distributes exactly the subset of this material each machine's own role
placement needs before that machine's own processes start — see [Remote (SSH) fleet
bootstrap](#remote-ssh-fleet-bootstrap) below.

`validate` checks a topology document for structural and semantic problems (missing machines, port
conflicts, an even-numbered store replica count, TLS material referenced but never declared) without
starting anything. Every way a document can be rejected — the file is missing, its YAML is
malformed, a field is the wrong shape, or a semantic rule fired — prints on stdout as the same
`[SEVERITY] CODE: message` line, so one output format covers the lot; a document that could not be
read at all reports `UNREADABLE_TOPOLOGY`, and one that could not be parsed into a topology reports
`MALFORMED_TOPOLOGY`. `plan`, `up`, and `upgrade-cluster` report a rejected document the same way
before doing anything. Only a mistake in the *invocation* — a missing flag, an unknown machine name
— goes to stderr as `error: …` instead. `plan` resolves a validated topology into the exact per-machine process commands
each of Gimlé's process kinds expects — useful for inspecting what `up` would run before it runs it.
`plan --machine <name>` narrows that preview to one machine and rejects a machine name the topology
never declares exactly as `up --machine <name>` does, naming the machines the plan does cover — a
preview that silently printed nothing for a typo'd name would be worse than no preview at all.
`up` actually spawns every process a topology assigns to `--machine`, waiting on any cross-machine
prerequisite (a store replica another machine hosts, say) via a plain TCP-connect readiness check
before starting anything that depends on it; `down`/`stop`/`status` act on the run ledger `up`
wrote, so none of them needs the topology document again for local dispatch.

`stop` is `down` narrowed to a single process — the one operation a machine hosting several roles
otherwise has no way to express short of an operator finding the pid themselves. It kills exactly
one of that machine's running processes and drops exactly that entry from the run ledger, leaving
every other process the machine hosts running and recorded:

```text
hilmir stop --machine m1 --role CONTROL_PLANE
hilmir stop --machine m1 --id store-1
```

Exactly one of `--role` or `--id` is required. `--role` takes any of `STORE`, `CONTROL_PLANE`,
`FAFNIR`, `MUNINN`, `ANDVARI`, `AGENT` (never `WORKER` — a worker is spawned and supervised by its
own node agent, which restarts it as soon as it dies), and is the ordinary form; where one machine
hosts two processes of the same role it refuses to guess, naming both ids so the next invocation can
pass `--id`. A `--role`/`--id` that names nothing currently recorded is likewise an error listing
what *is* recorded, rather than a silent success.

Because the stopped process's ledger entry is genuinely removed rather than left stale, a later
`hilmir up` against the same data root spawns that one process again and leaves its still-running
neighbours untouched — so `stop` then `up` is the way to restart one co-located replica in place.
(A platform-binary rollout, which restarts a role against a *new* classpath, is `upgrade-cluster`
instead.)

`--remote` re-invokes this exact same local `up`/`down`/`stop`/`status` over SSH instead of running
on the machine `hilmir` itself is invoked on — see [Remote (SSH) fleet
bootstrap](#remote-ssh-fleet-bootstrap) below. `--remote` is the one case where `down`/`stop`/`status` *do*
need `-f`: resolving a target machine's host and SSH settings needs the topology document, even
though local `down`/`stop`/`status` never do. `stop --remote` additionally always needs `--machine`
— stopping one named role across a whole fleet at once is not something this verb offers.

### Topology `runtime:` block

A topology document's optional `runtime:` section carries the shared knobs `validate`/`plan`/`up`/
`pki init` resolve once and reuse across every process the topology spawns. All four fields are
optional; a topology that omits `runtime:` entirely still parses.

| Field | Default when omitted | Purpose |
|---|---|---|
| `javaExecutable` | `java` (on `PATH`) | The `java` launcher every spawned process uses, unless `useBundledJre` overrides it for a given role (see below). |
| `classpath` | The running `hilmir` JVM's own `java.class.path` | The classpath every spawned process is launched against — see [Why `bin/hilmir up` needs no extra flag inside the platform archive](./distribution.md#why-binhilmir-up-needs-no-extra-flag-inside-the-platform-archive) for why this default is already correct inside an unpacked platform archive. |
| `dataRoot` | `gimle-data` (relative to the current working directory) | The root directory under which every spawned process gets its own scoped `<dataRoot>/<id>` data directory and `<dataRoot>/<id>-logs` log directory. |
| `useBundledJre` | `false` | When `true`, `up` and `pki init` launch the STORE, MUNINN, ANDVARI, FAFNIR, and CONTROL_PLANE processes against their own bundled jlink JRE instead of `javaExecutable` — see below. |

```yaml
runtime:
  javaExecutable: java
  classpath: /opt/gimle/0.1.0/lib/*
  dataRoot: /var/lib/gimle
  useBundledJre: true
```

A given process's own logging is split across two separate paths under `dataRoot`, not one: `<dataRoot>/<id>.log` is that process's raw launch stdout/stderr (a startup banner plus anything not routed through SLF4J), while `<dataRoot>/<id>-logs/<role>-platform.log` is its real structured JSON-lines platform log (everything logged through SLF4J — the file `-Dgimle.log.root` above actually points at). Diagnosing anything beyond "is the process alive" means checking both, for every process a topology spawns.

#### `useBundledJre`

Setting `useBundledJre: true` tells `hilmir up`/`hilmir pki init` to resolve
`${GIMLE_HOME}/jre/<component>/bin/java` for each of the five roles above instead of using
`javaExecutable` — the identical `jre/<component>/` layout (`mimir`, `fafnir`, `muninn`, `andvari`,
`controlplane`, `pki`) a `gimle-dist` platform archive built with its `-P dist-with-jre` profile
produces; see [Bundling a JRE into the
archives](./distribution.md#bundling-a-jre-into-the-archives) for that layout in full.
`GIMLE_HOME` is read from the process environment — the platform archive's own `bin/hilmir` wrapper
script already exports it (a sibling of that archive's own `bin/`/`lib/`/`jre/` directories), so an
operator running `hilmir up` from an unpacked platform archive needs no extra flag.

Two things fail this cleanly rather than falling through to a confusing spawn error, both checked as
early as `hilmir plan`/`hilmir up` (not deferred to a mysterious `ProcessBuilder` failure): a topology
setting `useBundledJre: true` while `GIMLE_HOME` isn't set in the environment `hilmir` itself was
launched with, and a topology needing a component whose own `jre/<component>/bin/java` doesn't
actually exist under `GIMLE_HOME` (an archive built without `-P dist-with-jre`, say). Both surface as
a clear, named error identifying exactly what's missing.

**`useBundledJre` never applies to the AGENT role, under any circumstance** — nor to the WORKER
command tail the agent itself spawns. Both dynamically load code (arbitrary vessel jars, hosted
Gimlé modules loaded into their own `ModuleLayer`) whose own JDK module needs were never part of any
jlink derivation; see [Which components, and why not all of
them](./distribution.md#which-components-and-why-not-all-of-them) for the full reasoning. A topology
with `useBundledJre: true` still launches every agent with plain `javaExecutable`, exactly as if the
setting were `false`.

This same resolution also applies to `hilmir upgrade-cluster` (below), since it plans against the
identical topology: if `useBundledJre: true`, every role `upgrade-cluster` restarts still resolves
its own bundled JRE from the *current* `GIMLE_HOME`, which takes precedence over that command's own
`--new-java-executable` flag for those roles. An upgrade that also needs a different `java` should
either point `GIMLE_HOME` at the newly-unpacked archive before running `upgrade-cluster`, or set
`runtime.useBundledJre: false` in the topology and rely on `--new-java-executable` instead.

### Topology `store:` block

Each entry under `store.replicas` places one store replica and names its ports. Only `machine` is
required.

| Field | Default when omitted | Purpose |
|---|---|---|
| `machine` | *(required)* | The `machines[]` entry this replica runs on. |
| `raftPort` | `9080` | Raft peer-to-peer transport port. |
| `clientPort` | `9091` | Client-facing `StoreRpc` port — what the control plane, Fafnir, Muninn, and Andvari connect to. |
| `healthPort` | *(unset — no health listener)* | Port for the store's read-only, unauthenticated `GET /health` endpoint, passed through to the store process as `--health-port`. |

```yaml
store:
  replicas:
    - {machine: m1, raftPort: 9080, clientPort: 9091, healthPort: 9095}
    - {machine: m2, raftPort: 9080, clientPort: 9091, healthPort: 9095}
```

Unlike every other role a topology places, a store replica serves no HTTP surface at all unless
`healthPort` names one — a load balancer or liveness probe otherwise has nothing but a raw
`clientPort` TCP connect to check. The endpoint is plaintext and unauthenticated even under an
`mtls` topology (it exposes only that replica's own Raft role, nothing sensitive), so put it on a
port your fleet's own network policy is willing to expose. `validate` counts it as one more port
claim on its machine, so a `healthPort` colliding with any other process on the same machine is
reported as `PORT_CONFLICT` exactly like any other collision.

## Remote (SSH) fleet bootstrap

`--remote` on `up`/`down`/`stop`/`status`/`upgrade-cluster` dispatches that exact same local verb
over SSH to every machine a topology declares (or just the one `--machine` names, when given —
always required for `stop`), rather than requiring an operator to already have a shell open on each
target machine — or `docker exec` into it, the way `gimle-holmgang`'s own Utgard test harness does.
Nothing about `up`/`down`/`stop`/`status`/`upgrade-cluster` themselves changes: `--remote`
re-invokes the identical `<installDir>/bin/hilmir up|down|stop|status|upgrade-cluster --machine
<name>` command on the target, over SSH, instead of running it locally — the run ledger, its readiness polling, and every other local behavior are untouched.
`up` additionally self-provisions a missing install and distributes exactly the TLS/Fafnir-key
material each machine needs, both before that command runs — see below.

```sh
hilmir up -f topology.yaml --remote
```

brings up every machine the topology declares, concurrently — one SSH session per machine. One
machine's failure never aborts the others; the exit code is non-zero if any machine failed.

**Host-key verification is real, not skipped.** `--remote` shells out to the operator's own
already-installed `ssh`/`scp`/`ssh-keyscan`/`ssh-keygen` (no SSH library dependency — the same
`ProcessBuilder` pattern `hilmir up`'s own local process spawning already uses) with
`StrictHostKeyChecking=yes` against a per-topology `known_hosts` file (`<dataRoot>/known_hosts` —
never the operator's own global one). Before any machine's dispatch thread starts, every target's SSH
host key is pinned into that file, sequentially, one machine at a time: a machine with a declared
`sshHostKeyFingerprint` (see below) has its scanned key checked against that exact fingerprint and
refuses to proceed on a mismatch — a rotated key, a stale pin, or a possible man-in-the-middle; a
machine with none pins whatever key `ssh-keyscan` returns instead (trust-on-first-use). A pinning
failure is reported and that one machine never dispatches — every other machine is unaffected, the
same partial-failure posture every other part of `--remote` already has.

**Provisioning is real.** `up --remote` checks `<installDir>/bin/hilmir` on each target before doing
anything else; if it's missing, the machine's resolved `archive` (see below) is shipped and unpacked
into a temp directory, then atomically moved into place — a crash mid-unpack never leaves a
half-installed directory visible at the real path. A machine that needs provisioning with no
`archive` configured anywhere fails clearly, naming the machine, rather than silently trying to run a
binary that was never there.

**Material distribution is real.** After provisioning (and before the topology file itself is
copied), `up --remote` ships exactly what each machine's own role placement needs, already generated
locally by `hilmir pki init`: the shared CA certificate; that machine's own per-role TLS leaves (by
hostname); the CA private key, only to a machine hosting a control plane (it signs agent CSRs); the
operator identity, only to a machine hosting an agent (it mints that agent's own bootstrap token
locally); and the Fafnir key file, only to a machine hosting a Fafnir replica. No cross-machine
ordering is needed for this — every file already has its final, consistent content before any
`--remote up` dispatch starts, since `pki init` ran first.

**No credential handling of its own.** Authentication is entirely the operator's own `ssh` identity —
an agent, a default key, or `~/.ssh/config` — the same way it would be for a plain manual `ssh`
session. `--remote` never reads, stores, or transmits a password or private key.

**SSH connection settings**, highest precedence first: the CLI flags (`--ssh-user`/`--ssh-key`/
`--ssh-port`/`--install-dir`) shown above; a per-machine override (`machines[].ssh:`); a
topology-wide default (`runtime.ssh:`); and, for anything left unset at every tier, the operator's
own `ssh` configuration decides (so a topology that declares no `ssh:` at all still works, exactly
as if the operator had typed `ssh <host>` themselves). `installDir` defaults to `/opt/gimle` when
nothing overrides it, matching every `docker-compose.*.yml` example under `gimle-holmgang/compose/`.
`archive` (the local platform archive to ship when provisioning) follows that same per-machine-then-
topology-wide precedence, but with no CLI-flag tier of its own and no built-in default — it's
topology data (which file to ship), not an operator convenience. `sshHostKeyFingerprint` has no
tiering at all: it names one specific host's expected key, so it lives only on `machines[]`.

```yaml
runtime:
  ssh: {user: ubuntu, identityFile: /home/op/.ssh/id_ed25519, installDir: /opt/gimle,
        archive: /local/gimle-platform.tar.gz}
machines:
  - {name: m1, host: gimle-1.example.com}
  - {name: m2, host: gimle-2.example.com, sshHostKeyFingerprint: "SHA256:abc...",
     ssh: {user: deploy, port: 2222}}
```

Here `m1` inherits the topology-wide default entirely (including `archive`); `m2` overrides just
`user`/`port`, still inheriting `identityFile`/`installDir`/`archive` from the topology-wide default
— precedence resolves field by field, not tier by tier. `m2` also pins its own SSH host key via
`sshHostKeyFingerprint`; `m1` has none declared, so `--remote` trusts whatever key it scans there on
first use.

`down`/`status`/`upgrade-cluster --remote` need `-f <topology.yaml>` too, unlike their local form:
resolving each target's host and SSH settings needs the topology document. Omitting `--machine` under
`--remote` fans out to every machine the topology declares, for every verb exactly as it does for
`up`.

## Cluster upgrade (platform binary rollout)

```text
hilmir upgrade-cluster -f <topology.yaml> --machine <name>
    --new-classpath <classpath-string>
    [--new-java-executable <path>] [--data-root <path>]
    [--role <STORE|CONTROL_PLANE|FAFNIR|MUNINN|ANDVARI>]...
hilmir upgrade-cluster -f <topology.yaml> --remote [--machine <name>]
    --new-classpath <classpath-string>
    [--new-java-executable <path>] [--data-root <path>] [--role <ROLE>]...
    [--ssh-user <user>] [--ssh-key <path>] [--ssh-port <port>] [--install-dir <path>]
```

`upgrade-cluster` rolls out a new platform binary to one machine's already-running processes: kill,
respawn against the new classpath, wait for readiness, repeat for the next role — never `hilmir
upgrade`, which is a completely different, bundle-workload-rollout concern (see
[Release verbs](#release-verbs) below).

**Scope: this command itself is strictly per-machine, with `--remote` layered on top exactly like
`up`/`down`/`status`'s own.** `UpgradeClusterCommand` itself only ever touches OS processes on the
one machine it runs on and has no cross-machine logic of its own; a multi-machine rollout is
`hilmir upgrade-cluster -f <topology.yaml> --remote --new-classpath <cp> [...]` re-invoking this
exact command once per machine over SSH, in the platform's own fixed boot order — see [Remote (SSH)
fleet bootstrap](#remote-ssh-fleet-bootstrap) above for the shared host-key-pinning/dispatch
mechanics (provisioning and material distribution are `up`-specific and don't apply here: a machine
`upgrade-cluster` targets is, by definition, already a running member of the cluster).

`--new-classpath` is required and deliberately independent of both the topology's own
`runtime.classpath` and whichever `hilmir` binary happens to be invoking the command: point it at a
newly-unpacked `gimle-platform-<new-version>/lib/*` classpath string, and every restarted process on
this machine launches against that new classpath, without editing the topology file or needing a
different `hilmir` binary at all. `--new-java-executable`/`--data-root` follow the topology's own
defaults (or `up`'s own defaults) when omitted — `--data-root` in particular must match the data root
`hilmir up` originally used for this machine, since that's where the run ledger this verb reads and
updates already lives.

With no `--role` given, every stateless platform role this machine hosts is restarted, one at a time,
in the platform's own fixed boot order: store, then muninn, then andvari, then fafnir, then control
plane. `--role` (repeatable) restricts the run to a chosen subset, still always sequenced in that same
boot order regardless of the order the flags were given in. **`AGENT` is never a valid `--role`
value** — an agent's own jar only matters at its next natural restart, and restarting one risks
interrupting in-flight worker instances on that machine, a materially different blast radius than
bouncing a stateless platform process; agents and workers are out of scope for this verb entirely.

A single role restart is: find that role's process in a freshly-computed plan (so it picks up the new
classpath), find its current run-ledger entry (failing clearly, pointing at `hilmir up`, if nothing
is recorded), kill it, spawn its replacement, wait for the replacement's own readiness, then update
just that one ledger entry — every other process this machine hosts, and its own ledger record, is
left completely untouched. A failure restarting any one role stops the whole run immediately; it
never silently continues to the next role and reports success.

**Store restarts are quorum-gated.** Killing a store replica can break a cluster-wide property —
Raft quorum — not just that one machine's own state, so a store role restart is gated twice, on two
different questions.

Before (and again after) the restart, `upgrade-cluster` polls every *other* store replica's
readiness and refuses to proceed unless a majority of the total store replica count is reachable.
This protects against an operator accidentally restarting two store machines "at once" via two
concurrent invocations, or restarting the last-standing majority member. A single-replica store has
no other replicas to protect and is always permitted to restart — refusing would make it permanently
un-upgradable, not safer.

Then, after the replacement is listening, `upgrade-cluster` additionally waits until the store
cluster actually serves a leader-routed read again, and fails the command if it does not within 90
seconds. Port reachability alone cannot answer this: a fresh store process opens its port the moment
it binds, well before it has rejoined Raft, caught its log up, or the cluster has elected a leader.
Without this second gate a rollout reports the step healthy and moves straight on to the next
machine, and taking another replica down during that window is exactly the quorum loss the first
gate exists to prevent. Failing loudly is deliberate: an operator told a step succeeded continues
the rollout, which is the action that turns a slow recovery into an outage.

Non-store roles have no equivalent cluster-wide property to protect; the ordinary per-process
readiness wait is sufficient there.

**Known assumption, partially proven:** a restarted store process rejoins its Raft cluster with the
same peer id and the same static `--peers` list the topology already declares, which is an ordinary
peer reconnecting rather than a membership change — `gimle-mimir`'s `StateStore`/`RaftLog` reload
their state from disk on construction, and this is covered by `StateStoreTest`'s own reload tests.
The leader-serving gate above now checks the *cluster's* recovery on every real store restart, so a
rollout can no longer proceed past a store that failed to rejoin. What is still not covered by any
single end-to-end test, in this codebase or in `gimle-smoke-tests`, is killing and restarting the
*same* store node and asserting it specifically rejoins as a follower and catches up — every
existing kill test permanently removes a node from its own cluster instead.

## Store membership verbs

```text
hilmir store add <peerId> <host> <raftPort> <clientPort>
    (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
    [--pki-dir <dir>]
hilmir store remove <peerId>
    (--topology <file> | --server <host:clientPort>[,<host:clientPort>...])
    [--pki-dir <dir>]
```

`gimle-mimir`'s Raft membership has always been dynamically reconfigurable at runtime
(`StoreClient#addServer`/`#removeServer`), but until now the only callers were test fixtures. `store
add`/`store remove` are the operator-facing surface over exactly that: each builds a short-lived
`StoreClient` against a resolved bootstrap endpoint list, makes the one call, and closes it
immediately after — never a cached or reused client, and no control-plane HTTP hop involved at any
point (there is no `gimle-controlplane`/`gimle-cli` surface for this; it is a direct binary
`StoreClient` connection to the store cluster, the same way `gimle-smoke-tests`' `RaftResilienceIT`
and `gimle-holmgang`'s `GimleCluster` already talk to it).

`peerId` follows the same `host:raftPort` convention used everywhere else in the codebase
(`PeerAddress.raftId()`) and is always given explicitly — it is never derived from `<host>`/
`<raftPort>` automatically, since a real caller (a test fixture, an operator) already has it in hand
independently.

Exactly one of two ways to reach the cluster is required:

- `--topology <file>` — parses the topology document the same way `validate`/`plan`/`up` do, derives
  the bootstrap endpoint list from `topology.store().replicas()` resolved against
  `topology.machines()`, and auto-detects mTLS from the topology's own `transport`/`tls` fields.
- `--server <host:clientPort>[,<host:clientPort>...]` — a direct, topology-free endpoint list (any
  member of a running cluster answers; `StoreClient` follows leader redirects on its own). Plaintext
  by default; `--pki-dir <dir>` turns on mTLS, presenting `<dir>/operator.crt`/`<dir>/operator.key`
  against `<dir>/ca.crt` — the same three-file operator-identity convention `hilmir up`'s own
  bootstrap-token minting already uses for talking to a running cluster.

Giving neither or both flags is a clean usage error, not a silently-picked default.

Both `addServer`/`removeServer` throw the same `GimleRaftException` for genuine unreachability and
for a transient "another membership change is still in flight" rejection alike (a just-added
learner's automatic promotion racing the call, most commonly) — the two aren't distinguishable from
the exception alone. Both verbs therefore retry the call up to 10 times, 200ms apart, printing one
progress line if they're still retrying after a few attempts, and only surface the final attempt's
error once every retry is exhausted — so a transient rejection resolves on its own within a second or
two, while a genuinely unreachable cluster or a request that can never succeed (removing a peer that
was never a member) still fails within a few seconds, not a hang.

On success, each verb prints one confirmation line naming the peer and the operation performed.

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

## Sync (GitOps-style reconciliation)

```text
hilmir sync (-f <bundle.yaml> | --dir <directory>) [--values <file>] [--set k=v]...
    [--wait] [--dry-run] [--prune] [-o json] [--server host:port] [--watch <seconds>]
```

`sync` reconciles one bundle file (`-f`) or every bundle file in a directory (`--dir`, non-recursive,
`*.yaml`/`*.yml`) against the release ledger `deploy`/`upgrade` already write, applying only what has
actually changed. It is built entirely on the same rendering and apply logic those two verbs use —
render the bundle, then compare it against the ledger's own recorded content — so a bundle that
`sync` finds absent from the ledger gets a fresh `deploy`-equivalent apply (revision 1), and a bundle
whose rendered content differs from its release's current revision gets an `upgrade`-equivalent apply
(next revision, with the same intra-revision resource prune `upgrade` already performs). A bundle
whose rendered content is *identical* to its release's current revision is reported as **already
converged** and left untouched entirely: no apply call, no revision bump. This diff-before-apply step
is `sync`'s own addition — `upgrade` itself still always applies and always bumps the revision, even
for unchanged content, since introducing that check there would change `upgrade`'s existing,
already-tested behavior.

In `--dir` mode, every file is reconciled in one invocation, and two files declaring the same bundle
`name` is a usage error naming both colliding files — nothing is applied until that check passes. Past
that point, `sync` reconciles each bundle independently: a bundle that fails to render or apply (a bad
`${values.*}` reference, a control-plane rejection) is recorded as failed and reconciliation continues
with the rest of the directory, rather than aborting every other, otherwise-healthy release over one
broken bundle — the exit code is nonzero if any bundle failed. A bundle file that fails to *parse* at
all (malformed YAML) is the one exception: since there is no bundle name yet to report a per-bundle
failure against, a parse failure aborts the whole invocation immediately, the same as a bad `-f` file
already does for `deploy`/`upgrade` today.

`sync` is deliberately **one-shot** — reconcile the bundles given right now, print a result, exit —
matching every other hilmir verb's own "act, print, exit" posture rather than becoming hilmir's first
foreground daemon/watch loop. `sync` has no notion of a git repository at all: it reconciles whatever
bundle files are already sitting on local disk, so watching a git remote for new commits is an
explicitly out-of-scope, separately-scriptable concern layered on top —
`while true; do git pull && hilmir sync --dir ./checkout; sleep 30; done`, not something built into
this verb. The optional `--watch <seconds>` flag is only a thin convenience wrapper around that exact
same loop (reconcile, sleep, repeat, foreground, until interrupted) for a caller who wants a single
long-running process instead of an external loop; it is not the primary design of the verb and, unlike
every other piece of `sync`, is not covered by an automated test (a genuine infinite loop isn't
something a unit test can assert against without hanging).

`--dry-run` makes **zero** control-plane calls — not even the ledger read `sync` would otherwise need
to tell a fresh deploy, an upgrade, and an already-converged bundle apart, mirroring `deploy
--dry-run`'s own "nothing to check against without a call" reasoning. A sync dry-run therefore cannot
report which of those three categories a bundle falls into; it prints the same "this is what would be
applied" plan `deploy --dry-run` already prints, for every bundle uniformly.

`--prune` additionally removes any release whose `.meta` row is on the ledger but whose bundle `name`
is not declared by any bundle in this invocation — true orphan-removal GitOps semantics (a bundle file
that has been deleted from the source directory), layered on top of, and distinct from, the
intra-revision resource prune an upgraded bundle already performs. It requires `--dir` (comparing "the
whole ledger" against a single `-f` file would make one lone sync capable of deleting every other
release the cluster knows about) and, like everything else `--dry-run` gates, has no effect under
`--dry-run`.

`--wait`, `--values`, `--set`, `-o json`, and `--server`/`GIMLE_SERVER` all behave exactly the way they
already do for `deploy`/`upgrade`: `--wait` polls only the bundles that actually changed (an
already-converged bundle has nothing to wait on); `--values`/`--set` apply the identical merged
override set to every bundle in a `--dir` run, not a per-bundle override.

## Extensions (`hilmir enable`/`hilmir disable`)

```text
hilmir enable gateway --server <host:port> [--modules-dir <dir>] [--values <file>]
    [--set k=v]... [--wait] [--dry-run] [-o json]
hilmir disable gateway --server <host:port> [-o json]
```

A platform **extension** is an optional, platform-owned module shipped inside every
`gimle-platform-<version>` archive's own `modules/` directory (a sibling of `bin/` and `lib/`) rather
than something an operator builds or packages themselves. `enable`/`disable` turn one on or off
against an already-running cluster without an operator ever hand-writing a bundle file for it — under
the hood, both are thin wrappers over the same release-verb machinery `deploy`/`upgrade`/`undeploy`
already provide (see [Release verbs](#release-verbs) above): no separate apply logic, no separate
ledger. Today the only extension is `gateway`; the verb shape (`hilmir enable <extension>`/`hilmir
disable <extension>`) leaves room for more without changing this one's own flags.

### `enable gateway`

1. Resolves a **modules directory** and finds the single `gimle-gateway-*.jar` inside it (zero or
   more than one match is a clear error).
2. Reads that jar's own `gimle-module.yaml` to derive its push coordinate — `(name, version)`.
3. HEAD-checks the artifact registry for that exact coordinate (through the control plane's existing
   `/artifacts/*` proxy, the same check `doctor --server` uses); pushes the jar only if the registry
   doesn't already have it under an identical sha256, so re-running `enable` after nothing has
   changed does no redundant upload.
4. Deploys (first time) or upgrades (an existing `gimle-gateway` release found) a **synthesized**
   bundle reproducing `gimle-gateway`'s real manifest shape: a `DaemonSet` named `gimle-gateway`,
   `tenantId: gimle-system`, `placement.requiredLabels: [edge]`, `module: {name, version}` with no
   `artifactPath` set (admission resolves it through the registry coordinate just pushed — the
   production path every node actually takes, not a local dev-tree file path), plus the two
   `gimle-system/gateway.port`/`gateway.controlPlaneEndpoint` config keys `GatewayHooks` reads at
   startup,
   defaulted to `8090`/`""` (no routes) and overridable through the same `--values <file>`/`--set
   k=v` mechanism every other release verb uses — the bundle's own `values:` block names its two
   keys `gateway.port`/`gateway.controlPlaneEndpoint` identically to the config keys they feed, so
   `--set gateway.controlPlaneEndpoint=<host:port>`/`--set gateway.port=<port>` are the flags that
   actually take effect. The route table itself is not a config key — declare it as `Ingress`
   resources.

Enabling again after the jar under `--modules-dir` has been rebuilt at a new version re-derives the
coordinate from that new jar, pushes it, and takes the **upgrade** path against the existing
`gimle-gateway` release — the same "point it at the new artifact and re-run" workflow `deploy`/
`upgrade` already give every other release.

### Modules-directory resolution

`--modules-dir <dir>` is the explicit override; absent that, the default is `<GIMLE_HOME>/modules`,
where `GIMLE_HOME` is an environment variable the platform archive's own `bin/hilmir` wrapper script
exports automatically (the install root, a sibling of `bin/`, `lib/`, and `modules/`). There is no
third, silent fallback: `hilmir` launched directly (a hand-built classpath, or from the standalone
`gimle-hilmir-<version>.tar.gz` archive, which has no `modules/` directory of its own at all) fails
with a clear error naming both the environment variable and the flag, rather than guessing from
`java.class.path`.

### `disable gateway`

Undeploys the release named `gimle-gateway` — `undeploy --release gimle-gateway` under the hood, with
nothing else to do: the synthesized bundle `enable` applies declares no `tenants:` entries of its own
(`gimle-system` is a reserved tenant the control plane seeds automatically at startup), so there is
never a tenant for `undeploy`'s own `--keep-tenants` behavior to act on for this particular release,
and `disable` doesn't expose that flag.

### Operator credentials are required

`gimle-system` is a reserved tenant: the control plane only accepts a write against it from an mTLS
caller in the `gimle:operators` group, and the artifact registry's own push authorization checks the
same group on the forwarded identity. Both `enable` and `disable` therefore need the same operator PKI
material (`-Dgimle.tls.certFile/keyFile/caFile` pointing at a `gimle:operators`-group leaf) an
operator already needs for `hilmir deploy`/`upgrade` against any `gimle-system`-scoped bundle. A
401/403 from either the registry push or the release apply is reported with that requirement spelled
out directly, rather than the control plane's own opaque status-code message.

## `doctor`/`init`

```text
hilmir doctor <jar> [<dep-jar>...] [--vessel] [--server host:port] [--tenant <id>] [-o json]
hilmir init <jar> [--out-dir <dir>]
```

Both share one analyzer (`com.gimle.hilmir.analyze`): structural jar inspection (mirroring
`ModuleArtifactReader`'s own `JarFile`/`JarEntry` shape), a lenient `gimle-module.yaml` reader, and a
`java.lang.classfile`-based bytecode scanner for a fixed set of hazard signals (`System.exit`,
shutdown-hook registration, non-daemon `Thread` construction, native-library loading, server-socket
opening, a static `ExecutorService` field with no visible shutdown call anywhere in its class). This
is a linear instruction-stream scan for known failure classes, not a control-flow/dataflow analysis
— reflection-driven or indirectly-invoked hazards are false negatives by design, the same "tripwire,
not certification" posture the design already takes elsewhere.

### Module-hosting vs. vessel-hosting

The platform itself never sniffs a jar's structure to decide module-hosting vs. vessel-hosting — a
deploy manifest's own `vessel:` block presence/absence is the one and only switch (see
[Vessel workloads](./manifest-schema.md#vessel-workloads-vessel)). `doctor` mirrors that posture: it
evaluates a jar under the module-hosting interpretation by default (the richer, more constrained
path — real isolation-tier/resource/probe/hook validation), and only under `--vessel` does it switch
to the smaller vessel-hosting check set. One finding, `NOT_LAYER_HOSTABLE`, is reported at a
different severity depending on which mode was asked for: an `ERROR` under the default module intent
(a launcher archive genuinely cannot be module-hosted), an `INFO` note under `--vessel` (confirming
the shape is exactly what vessel-hosting expects). `init` makes the same jar-shape call for itself,
since it has no manifest to read a `vessel:` block from yet — see below.

### `doctor`'s static finding catalog

| Code | Severity | Fires when |
|---|---|---|
| `NOT_LAYER_HOSTABLE` | `ERROR` (module intent) / `INFO` (`--vessel`) | The jar is a launcher archive (nested `BOOT-INF/`/Quarkus-fast-jar-style classpath layout) — a real Spring Boot/Quarkus launcher jar, not a flat classes-on-a-module-path shape. |
| `UNSUPPORTED_PACKAGING` | `ERROR` | Not a plain runnable jar at all: a `.war`/`.ear`, a directory/fast-jar distribution, or a non-ZIP-shaped binary (most commonly a native-image executable). |
| `NOT_A_MODULE` | `WARNING` | No `module-info.class`, module-hosting was intended, and the jar isn't a launcher archive either (that's `NOT_LAYER_HOSTABLE`'s case) — an ordinary flat, non-modularized jar. |
| `DESCRIPTOR_UNREADABLE` | `ERROR` | Module-hosting intended, the jar has `module-info.class`, but its `gimle-module.yaml` is missing or missing a required field (`name`, `version`, `isolation.tier`, `resources.request`/`.limit`) — grounded directly in `ModuleArtifactReader`'s own hard failure for exactly this case, one code added beyond the originally scoped catalog. |
| `TIER3_REQUESTED` | `ERROR` | `gimle-module.yaml` declares `isolation.tier: TIER_3` — unimplemented on every platform today, rejected outright at scheduling time. |
| `RESOURCES_INCOHERENT` | `ERROR` | `resources.limit` < `resources.request`, or either is an unparseable quantity. |
| `PROBE_INVALID` | `ERROR` | A class named in `health.liveness`/`health.readiness`/`lifecycle.hooks`/`lifecycle.jobHooks` is missing from the jar, doesn't directly implement the expected interface (by name — see below), or has no no-arg constructor. |
| `VERSION_DRIFT` | `WARNING` | `gimle-module.yaml`'s own `version` doesn't match the jar manifest's `Implementation-Version` attribute. |
| `NATIVE_CODE` | `ERROR` | Bundled `.so`/`.dll`/`.dylib` entries, or a class calling `System.load`/`System.loadLibrary`. |
| `CALLS_SYSTEM_EXIT` | `ERROR` (module intent) / `WARNING` (`--vessel`) | A class calls `System.exit`. |
| `LEAK_RISK` | `WARNING` | A class registers a JVM shutdown hook, constructs a `Thread` without `setDaemon(true)`, or declares a static `ExecutorService` field with no `shutdown`/`shutdownNow`/`close` call anywhere in that same class. |
| `BINDS_OWN_PORT` | `INFO` | A class opens a `ServerSocket`/`ServerSocketChannel`/`com.sun.net.httpserver.HttpServer` — informational only, the platform has no ingress story for a plain module today. |
| `MAKES_OUTBOUND_CALLS` | `INFO` | A class constructs a connecting `java.net.Socket`, opens a `SocketChannel`, or builds a `java.net.http.HttpClient` — informational only, the mirror of `BINDS_OWN_PORT` on the egress side: nothing on the platform restricts a module's outbound traffic today. |
| `SPLIT_PACKAGE` | `ERROR` | Two of the jars given on the command line (the primary plus any `<dep-jar>` arguments) declare the same package. |
| `BUNDLED_LOGGING_BINDING` | `WARNING` | A `logback-classic`/`log4j-core`/`slf4j-simple` class prefix or bundled dependency jar name is found among the artifact's own entries (its own nested `lib/` layout, or an extra `<dep-jar>` argument). |

`PROBE_INVALID`'s "implements the expected interface" check is a name-only comparison against the
class's own declared (`implements`) interfaces, not a walk up its superclass chain, and its
no-arg-constructor check doesn't verify accessibility — `doctor` cannot depend on `gimle-module` for
the real `LivenessProbe`/`ReadinessProbe`/`ModuleLifecycleHooks`/`JobHooks` interfaces, so it
compares binary names only.

### Cluster-aware checks (`--server`)

`--server host:port` (the same control-plane address the release verbs use) adds two more checks on
top of the static catalog, both additive, never replacing anything above:

| Code | Severity | Fires when |
|---|---|---|
| `REGISTRY_COORDINATE_NOT_FOUND` | `ERROR` | The jar's own `(name, version)` isn't present in the artifact registry behind `--server` (a plain `HEAD /artifacts/{name}/{version}` through the control plane's existing `/artifacts/*` proxy — no separate Andvari address needed). |
| `REGISTRY_UNREACHABLE` | `WARNING` | The registry coordinate couldn't be confirmed (no registry configured on that control plane, or a transport failure). |
| `TENANT_NOT_FOUND` | `ERROR` | `--tenant <id>` was given and that tenant doesn't exist on the control plane behind `--server`. |

This is deliberately not exhaustive — quota headroom, scheduler feasibility, and similar deeper
cluster checks are a clearly scoped-out follow-up, not something this pass tried to force in.

### `init`

Inspects a built jar with the same analyzer `doctor` uses and writes `deployment.yaml`, plus
`gimle-module.yaml` when the jar is module-hosting-shaped (no `module-info.class`, or a
launcher-archive layout, routes to the vessel form instead — the same jar-shape judgment call
`doctor --vessel` makes explicit via a flag, made automatically here since there's no manifest yet to
read a `vessel:` block from). Detected facts (a probe/hooks class actually found implementing the
right interface with a no-arg constructor, the module name from `module-info.class` when present)
are filled in directly; everything else (version, resource sizing, isolation tier) gets a
conservative default annotated `# TODO: measure and adjust`. Never overwrites a file that already
exists at either target path — refuses outright, listing every colliding path, rather than silently
clobbering a hand-edited file.

Both files land in `--out-dir`, or in the directory the command was run from when that flag is
absent — deliberately not beside the inspected jar, since that is a build output directory whose
next clean would delete files you are meant to edit and keep.
