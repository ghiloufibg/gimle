---
sidebar_position: 6
---

# `gimle-ragnarok` reference

`ragnarok` runs Fenrir (chaos) and Surtr (stress/load) against a real, already-running Gimlé
cluster — config-file-driven, no test code to write or compile. Fenrir and Surtr are the identical
library code `gimle-holmgang`'s own test harness uses internally, behind a `ClusterTarget` seam —
`ragnarok` provides two real-cluster implementations of that seam, wired to a small CLI instead of a
Cucumber suite:

- **`EndpointClusterTarget`** (the default, no `inventory:` block in the target document) reaches a
  cluster purely over the network: HTTP for the control plane, a direct `StoreClient` RPC for the
  store's own read-only status. No boot-time interposition, no process control. Only network faults
  (`LINK_CUT`, `STORE_PARTITION`) can ever fire through it — every other Fenrir fault kind
  (worker/store/leader/control-plane/Fafnir/Muninn/Andvari bounce, all of which need process
  control this target doesn't have) always records `SKIPPED`, never throws.
- **`SshInventoryClusterTarget`** (opt in via the target document's `inventory:` block) additionally
  controls the machines/processes a cluster runs on over SSH — real `kill -9`/respawn against each
  machine's own store/control-plane/Fafnir/Muninn/Andvari process and, given a matching `agents:`
  entry, a real worker's OS pid resolved from its node agent's own platform log. Every bounce/kill
  fault kind actually fires and recovers through this target, not just the two network-only ones.

```text
ragnarok preflight --target <target.yaml>
ragnarok chaos --target <target.yaml> --plan <plan.yaml> [--seed N]
    [--confirm-destructive] [--report <dir>]
ragnarok stress --target <target.yaml> [--workload <name-or-path>]
    [--module-jar <path>] [--report <dir>]
ragnarok replay --from-report <chaos-report.json> --target <target.yaml>
    [--confirm-destructive] [--report <dir>]
ragnarok report (--chaos-report <path> | --surtr-report <dir>)
```

## The target document

Every verb but `report` needs a `--target`: which cluster to reach, and how.

```yaml
controlPlaneBaseUrls: [https://cp-0.prod:8443, https://cp-1.prod:8443]
storeClientEndpoints: [store-0.prod:7100, store-1.prod:7100, store-2.prod:7100]
muninnBaseUrls: []      # optional, default []
andvariBaseUrls: []     # optional, default []
transport: mtls         # optional, default plaintext
tls:                    # required when transport: mtls
  certFile: /etc/ragnarok/operator.crt
  keyFile: /etc/ragnarok/operator.key
  caFile: /etc/ragnarok/ca.crt
workDir: /var/lib/ragnarok/work   # optional, default a temp directory

inventory:                        # optional -- adds real SSH process control (see below)
  sudo: false                      # optional, default false -- see "Firewall faults" below
  machines:
    - name: node-1
      host: 10.0.1.10
      ssh: {user: gimle, identityFile: /home/op/.ssh/id_ed25519}
  store:
    - machine: node-1
      id: store-0
      pidFile: /opt/gimle/data/store-0.pid
      logFile: /opt/gimle/data/store-0.log
      command: [java, -cp, /opt/gimle/lib/*, com.gimle.mimir.StoreMain, "7100", "7101"]
      raftPort: 7100                # optional -- only needed for STORE_PARTITION, see below
  controlPlane: []   # same shape, for control-plane replicas
  fafnir: []          # same shape, for Fafnir replicas
  muninn: []           # same shape, for Muninn replicas
  andvari: []          # same shape, for Andvari replicas
  agents:
    - machine: node-1
      nodeId: node-abc
      logRoot: /opt/gimle/data/agent-node-abc-logs   # hilmir's own -Dgimle.log.root convention
```

`storeClientEndpoints` is optional — an empty list just means the store-health checks (`preflight`'s
leader/member report, and any future gate that reads them) degrade to "unknown" instead of failing
outright. `transport`/`tls` follow the identical `plaintext`/`mtls` + `certFile`/`keyFile`/`caFile`
shape `gimle-hilmir`'s own topology documents use, deliberately, so an operator who already knows one
recognizes the other immediately.

### The `inventory:` block

Adding an `inventory:` block switches the target from `EndpointClusterTarget` to
`SshInventoryClusterTarget` — no separate `kind:` field, the block's presence is the whole
discriminator. `machines` declares every host `ragnarok` may SSH into (reusing `gimle-hilmir`'s own
`Machine`/`ssh:` shape); each of `store`/`controlPlane`/`fafnir`/`muninn`/`andvari` is a list of
managed roles, one per replica, in the same index order `--target`'s own `storeClientEndpoints`/etc.
already imply — `pidFile`/`logFile`/`command` describe exactly how to launch and track that one
process over SSH, deliberately independent of any `hilmir up` run: a bounce is `kill -9 $(cat
pidFile)` followed by re-running `command` in the background and recording the new pid, so a role's
`command` needs its own explicit `-cp` (an inventory document is not a `hilmir` topology and doesn't
inherit one). `agents` maps a Gimlé node id to the machine hosting it and that node agent's own
`-Dgimle.log.root` directory, letting `WORKER_KILL` resolve a worker's real OS pid from the agent's
own `"spawned worker ... as pid ..."` platform-log line — no worker-kill victim can be resolved for
a node with no matching `agents` entry.

### Firewall faults (`LINK_CUT`/`STORE_PARTITION`) over SSH

An `inventory:`-backed target also fires `LINK_CUT`/`STORE_PARTITION` for real, via `iptables` rules
pushed over the same SSH transport — real `kill -9 $(cat pidFile)`/reinstall-owned processes need no
elevated privilege, but a firewall rule does, so this is opt-in: `sudo: true` prefixes every
`iptables` invocation with `sudo -n` (non-interactive — a target without real passwordless sudo
configured for `iptables` fails fast rather than hanging on a password prompt); leave it `false` when
the inventory's SSH user is already root. `LINK_CUT` needs no extra configuration (it uses the target
document's own `storeClientEndpoints`); `STORE_PARTITION` needs every declared `store:` role's own
`raftPort` — a plan naming `STORE_PARTITION` against an inventory where any store role omits it is
refused up front, before the soak starts, naming exactly which store roles are missing it.
`STORE_BOUNCE`/`LEADER_BOUNCE`'s own quorum-floor guard (above) applies here too — a single-replica
store can never clear it, so `STORE_PARTITION` needs at least three store replicas to ever actually
fire, independent of whether `raftPort` is configured.

### The `adminApi:` block (`WORKER_KILL` without SSH)

A third, mutually exclusive target kind — `AdminApiClusterTarget` — reaches every node's own Admin
Fault API directly over HTTP/mTLS instead of SSH, for an operator who wants to run `WORKER_KILL`
without handing out shell access to any machine at all:

```yaml
adminApi:
  nodes:
    - nodeId: node-abc
      endpoint: https://10.0.1.10:9500
    - nodeId: node-def
      endpoint: https://10.0.1.11:9500
```

`inventory:` wins if both blocks are somehow present in the same document — the three target kinds
(`EndpointClusterTarget`, `SshInventoryClusterTarget`, `AdminApiClusterTarget`) stay mutually
exclusive in v1. Every other `ClusterTarget` method (`api()`, `storeLeaderId()`, `preflight`'s own
reachability checks, ...) behaves exactly like the plain `EndpointClusterTarget` case — `adminApi:`
only changes what `workerFor()` can do, so only `WORKER_KILL` is ever anything but `SKIPPED`
through this target; `LINK_CUT`/`STORE_PARTITION` stay unavailable exactly like the default
`EndpointClusterTarget` case (no boot-time network interposition here either), and every other
bounce kind (store/control-plane/Fafnir/Muninn/Andvari) still needs `inventory:`'s own SSH process
control.

The node side is a node agent's own Admin Fault API, opt-in and off by default — an agent that
never sets `-Dgimle.agent.storeEndpoints` (one or more comma-separated `gimle-mimir` store
`host:port` entries, the agent's own independent RBAC re-check target, matching Fafnir/Andvari's
defense-in-depth pattern rather than trusting a forwarded claim) opens no admin port at all.
`-Dgimle.agent.adminApiPort` pins the listener to a known port (default: ephemeral, logged at
startup) — set it explicitly when the target document's `adminApi:` block needs a fixed address to
declare, as above. Authorization is peer-certificate-only (no forwarded-header tier, no session
cookie — `ragnarok` dials the agent directly, so there's no proxy hop to trust a forwarded claim
from) against `ResourceKind.FAULT`, a cluster-admin-only permission by default (not granted to any
tenant role template — an operator delegating it creates an explicit `Role`/`RoleBinding`). A kill
force-kills the worker's OS process directly, leaving the platform's own supervisor
(`WorkerProcessSupervisor`'s crash-detection-and-respawn) to bring it back — the identical contract
`SshWorkerHandle.kill()` already has over SSH, and the only fault kind this surface covers at all:
every other bounce kind needs a restart driven by *this tool*, not the platform's own supervisor,
so it would need an admin listener on five other unrelated process kinds to cover — a materially
larger, separately-scoped lift, not built here.

## `preflight`

A plain readiness report: the store's own leader/member visibility (if `storeClientEndpoints` is
set), and every configured control-plane/Muninn/Andvari endpoint's own reachability. Prints one line
per check and exits non-zero if anything configured is unreachable — nothing is mutated.

## `chaos` and the `--confirm-destructive` gate

A chaos plan document drives Fenrir. Every field maps directly to a `FenrirPlan`/`Pool` builder
call:

```yaml
seed: 42                        # optional; --seed on the command line overrides this
soakSeconds: 300
strikeEverySeconds: 15          # or strikeEveryMinSeconds/strikeEveryMaxSeconds for a range
eligibleDeployments: [burn-greeter-0, burn-greeter-1]   # required if a worker-kill pool is present
convergeBetweenFaults: true     # optional, default true
gateTimeoutSeconds: 60          # optional, default 60
pools:
  - kind: WORKER_KILL
    weight: 2
  - kind: STORE_BOUNCE
    dwellSeconds: 5
  - kind: LINK_CUT
    healAfterSeconds: 10
```

`kind` is one of the nine `FaultKind` values. A plan whose pools name anything beyond a pure network
fault (`LINK_CUT`/`STORE_PARTITION`) — that is, anything that kills or bounces a real process — is
refused unless `--confirm-destructive` is passed; the refusal names exactly which pools triggered it.
This checks the plan's own declared pools, not what the target can currently fire: an
`EndpointClusterTarget` (no `inventory:` block) has no process control at all, so every one of those
kinds always records `SKIPPED` against it regardless of the gate; against an
`SshInventoryClusterTarget` (`inventory:` present) they actually fire and are expected to recover.
`STORE_BOUNCE`/`LEADER_BOUNCE` additionally require enough live store replicas to clear Fenrir's own
quorum floor (`live > total/2 + 1`) — a single-replica store can never clear it and always skips both,
independent of process-control capability.

`chaos` prints the resulting chaos ledger (seed, executed/recovered/skipped counts, one line per
strike) and exits non-zero unless every fault that fired recovered. `--report <dir>` writes a
`chaos-report.json` there — the fully-resolved plan (seed, soak, gap, pools) embedded alongside the
ledger, which is what `replay` reads back.

## `stress`

A workload document drives Surtr — the identical YAML shape `gimle-holmgang`'s own `SurtrIT` already
parses. `--workload` defaults to a workload named `pause-density`, bundled inside `ragnarok`'s own
jar, deploying a minimal, deliberately inert reference module (`gimle-ragnarok-pause`) also bundled
inside the jar — so a bare `ragnarok stress --target ...` needs nothing beyond the binary itself, no
`gimle-examples` build, no operator-supplied jar. `--module-jar <path>` overrides the default with a
real module jar of the operator's own (its module name is read from the jar's own bundled
`gimle-module.yaml`). `stress` prints Surtr's own pass/fail gate summary and exits non-zero if any
gate failed; `--report <dir>` writes the same `summary.json` + NDJSON shape `gimle-holmgang`'s own
Surtr runs already produce.

## `replay`

Re-runs a previous `chaos --report` run's exact plan and seed for a deterministic repro, reading the
fully-resolved plan a report embeds inline rather than re-reading the original plan file (which may
have moved or changed since). Subject to the identical `--confirm-destructive` gate `chaos`
enforces — replay never bypasses it.

## `report`

An offline pretty-printer for a `chaos-report.json` or a Surtr run's `summary.json`, already written
to disk by a previous `chaos`/`stress --report` run. No re-run, no `--target` needed — useful for a
CI artifact or sharing a result without cluster access.

## Distribution

`gimle-dist` packages a standalone `gimle-ragnarok-<version>.tar.gz` archive — `bin/ragnarok`(`.cmd`)
plus exactly `gimle-ragnarok`'s own runtime dependency closure — the same shape the standalone
`gimle-hilmir-<version>.tar.gz` archive already takes; see [Distribution archives](./distribution.md).
