---
sidebar_position: 6
---

# `gimle-ragnarok` reference

`ragnarok` runs Fenrir (chaos) and Surtr (stress/load) against a real, already-running Gimlé
cluster — no boot-time cluster interposition, no process control, config-file-driven, and no test
code to write or compile. It reaches its target purely over the network: HTTP for the control
plane, a direct `StoreClient` RPC for the store's own read-only status. Because of that, only
network faults (`LINK_CUT`, `STORE_PARTITION`) can ever actually fire through it — every other
Fenrir fault kind (worker/store/leader/control-plane/Fafnir/Muninn/Andvari bounce, all of which
need process control this tool doesn't have) always records `SKIPPED`, never throws. Fenrir and
Surtr are the identical library code `gimle-holmgang`'s own test harness uses internally, behind a
`ClusterTarget` seam — `ragnarok` is simply a second, real-cluster-only implementation of that seam
(`EndpointClusterTarget`), wired to a small CLI instead of a Cucumber suite.

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
```

`storeClientEndpoints` is optional — an empty list just means the store-health checks (`preflight`'s
leader/member report, and any future gate that reads them) degrade to "unknown" instead of failing
outright. `transport`/`tls` follow the identical `plaintext`/`mtls` + `certFile`/`keyFile`/`caFile`
shape `gimle-hilmir`'s own topology documents use, deliberately, so an operator who already knows one
recognizes the other immediately.

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
This checks the plan's own declared pools, not what the target can currently fire: today
`EndpointClusterTarget` has no process control at all, so none of those kinds can actually execute —
the gate exists for whatever `ClusterTarget` a future process-control-capable adapter provides too,
not just this one.

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
