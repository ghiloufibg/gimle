# Gimle Hilmir

Hilmir is Gimlé's deployment/bootstrap tool: it reads a declarative topology document describing a
multi-machine Gimlé cluster, checks it for real operational problems, and turns it into the exact
per-machine process commands the platform's own process kinds (`StoreMain`, `ControlPlaneMain`,
`FafnirMain`, `MuninnMain`, `AndvariMain`, `AgentMain`) expect.

## Topology YAML

A minimal single-machine, plaintext topology:

```yaml
name: dev
machines:
  - {name: m1, host: 127.0.0.1}
store:
  replicas:
    - {machine: m1}
controlPlane:
  replicas:
    - {machine: m1}
fafnir:
  keyFile: /etc/gimle/fafnir-secret.key
  replicas:
    - {machine: m1}
agents:
  - {machine: m1, nodeId: node-a}
```

A larger, multi-machine, mTLS topology:

```yaml
name: prod
transport: mtls
tls:
  materialDir: /etc/gimle/tls

machines:
  - {name: m1, host: gimle-1.example.com}
  - {name: m2, host: gimle-2.example.com, ssh: {user: deploy, port: 2222}}

runtime:                         # entire section optional
  javaExecutable: java
  classpath: /opt/gimle/lib/*
  dataRoot: /var/lib/gimle
  ssh: {user: ubuntu, identityFile: /home/op/.ssh/id_ed25519, installDir: /opt/gimle}
                                  # topology-wide default for `--remote`; a machine's own `ssh:`
                                  # (like m2's above) overrides it field by field

store:
  replicas:
    - {machine: m1, raftPort: 9080, clientPort: 9091}
    - {machine: m2}              # ports default when omitted

controlPlane:
  replicas:
    - {machine: m1, port: 8080}

fafnir:
  keyFile: /etc/gimle/fafnir-secret.key
  replicas:
    - {machine: m1, port: 9092}

muninn:
  replicas: []                   # optional role -- empty list or the whole section omitted both
                                  # mean "disabled"
andvari:
  replicas: []

agents:
  - {machine: m2, nodeId: node-a, gossipPort: 9090, labels: [ssd]}

jvm:                              # optional, per-role extra JVM flags
  store: ["-Xmx256m"]
```

Every mapping in a topology document -- the root and every section within it -- rejects unknown
keys outright, so a typo'd field fails at parse time rather than silently being ignored.

Default ports, applied when a replica entry omits its own: store raft `9080` / client `9091`,
control plane `8080`, fafnir `9092`, muninn `9093`, andvari `9094`, agent gossip `9090`.

## Validator rule catalog

`hilmir validate` runs every rule below against a parsed topology and prints each finding's code,
severity, and message. Only an `ERROR`-severity finding fails the command's exit code.

| Code | Severity | Meaning |
|---|---|---|
| `NO_MACHINES` | ERROR | `machines` is empty. |
| `NO_STORE` | ERROR | Zero store replicas declared. |
| `NO_CONTROL_PLANE` | ERROR | Zero control-plane replicas declared. |
| `NO_FAFNIR` | ERROR | Zero fafnir replicas declared (`ControlPlaneMain` always requires `--fafnir-endpoint`). |
| `UNKNOWN_MACHINE` | ERROR | A replica or agent references a machine name not declared in `machines`. |
| `DUPLICATE_MACHINE` | ERROR | Two machines share a name. |
| `DUPLICATE_NODE_ID` | ERROR | Two agents share a `nodeId`. |
| `PORT_CONFLICT` | ERROR | Two processes on the same machine claim the same port. |
| `REPLICAS_COLOCATED` | ERROR (multi-machine) / WARNING (single-machine) | A role (store/controlPlane/fafnir/muninn/andvari) with two or more replicas places two or more of them on the same machine. |
| `AGENTS_COLOCATED` | ERROR (multi-machine) / WARNING (single-machine) | Two agents are placed on the same machine. |
| `MTLS_NO_MATERIAL_DIR` | ERROR | `transport: mtls` with no `tls.materialDir` configured. |
| `MTLS_IP_LITERAL_HOST` | ERROR | `transport: mtls` and a machine's `host` is an IP literal (the platform's PKI mints DNS-only subject alternative names, so hostname verification against an IP literal fails). |
| `SINGLE_STORE` | WARNING | Exactly one store replica: no quorum or failover. |
| `STORE_EVEN_REPLICAS` | WARNING | An even store replica count gains no quorum benefit over one fewer replica. |
| `SINGLE_CONTROL_PLANE` | WARNING | Exactly one control-plane replica. |
| `NO_AGENTS` | WARNING | Zero agents declared: the cluster can never place a workload. |
| `FAFNIR_KEY_DISTRIBUTION` | WARNING | Fafnir replicas span more than one machine: the shared key file must be manually distributed to the same path on each. |
| `MTLS_SINGLE_HOSTNAME_PKI` | WARNING | `transport: mtls` across more than one machine, but `PkiBootstrapMain` mints server leaves for a single hostname only. |

## CLI verbs

- `hilmir validate -f <topology.yaml>` -- parses and validates a topology, printing every finding
  (errors first). Exits non-zero only if an `ERROR`-severity finding exists.
- `hilmir plan -f <topology.yaml> [--machine <name>]` -- validates (aborting with the same findings
  output on any error), then prints the fully resolved per-machine process commands. `--machine`
  filters to one machine; omitted prints every machine.
- `hilmir up -f <topology.yaml> --machine <name>` -- validates, then spawns every process the named
  machine hosts, in the plan's own boot order (store, then Muninn, then Andvari, then Fafnir, then
  control plane, then agent). Before spawning a command, waits for every command anywhere in the
  cluster that must be up first and lives on a different machine (a same-machine prerequisite is
  already running by the time its own turn comes) -- run `hilmir up` once per machine, in any order
  that respects that dependency, and each invocation blocks until its own machine's own processes
  are reachable. Writes a run ledger (`hilmir-run.json`) under the resolved runtime's data root
  (`runtime.dataRoot`, `gimle-data` by default) so a later `down`/`status` on the same machine can
  find these processes again. For an mtls topology, an agent's own bootstrap token is minted
  automatically via a one-shot `gimle cert token create` call against the already-running control
  plane.
- `hilmir up -f <topology.yaml> --remote [--machine <name>] [--ssh-user <user>] [--ssh-key <path>]
  [--ssh-port <port>] [--install-dir <path>]` -- the same `up` above, dispatched over SSH instead
  of running locally: with `--machine`, just that one machine; omitted, every machine the topology
  declares, concurrently. Shells out to the operator's own `ssh`/`scp` (no SSH library dependency),
  no host-key verification, no provisioning (`<installDir>/bin/hilmir` must already exist on the
  target -- default `/opt/gimle`), no credential handling of its own. See `gimle-docs`' own Remote
  (SSH) fleet bootstrap reference section for the full v1 scope and the
  `machines[].ssh:`/`runtime.ssh:` precedence.
- `hilmir down --machine <name> [--data-root <path>]` -- reads the run ledger at `--data-root`
  (`gimle-data` by default) and stops every process it recorded, in reverse of the order `up`
  started them, then removes the ledger. A pid no longer running is reported and skipped, not
  treated as an error.
- `hilmir down -f <topology.yaml> --remote [--machine <name>] [--data-root <path>] [--ssh-user
  <user>] [--ssh-key <path>] [--ssh-port <port>] [--install-dir <path>]` -- the same `down`,
  dispatched over SSH; unlike local `down`, `--remote` needs `-f` to resolve each target's host and
  SSH settings.
- `hilmir status --machine <name> [--data-root <path>]` -- reads the run ledger at `--data-root` and
  reports each recorded process's pid liveness and (best-effort) whether its own readiness address
  is currently reachable.
- `hilmir status -f <topology.yaml> --remote [--machine <name>] [--data-root <path>] [--ssh-user
  <user>] [--ssh-key <path>] [--ssh-port <port>] [--install-dir <path>]` -- the same `status`,
  dispatched over SSH; same `-f`-required-under-`--remote` asymmetry as `down` above.
- `hilmir pki init -f <topology.yaml>` -- generates a brand-new mtls topology's cluster CA and
  per-role leaf certificates under `tls.materialDir` by spawning the platform's own
  `PkiBootstrapMain` once. Only applies to a topology with `transport: mtls` and a configured
  `tls.materialDir`. Since the platform's PKI mints DNS-only, single-hostname server SANs today (see
  `MTLS_SINGLE_HOSTNAME_PKI` below), a multi-machine topology gets material for one machine's
  hostname only -- printed as a note -- and every other machine's server processes need
  manually-issued material.

`down`/`status` deliberately take `--data-root` rather than `-f` for local dispatch: the run ledger
lives under a resolved runtime's own data root, and neither verb needs the topology document again
to find it. `--remote` is the one exception -- see above.
