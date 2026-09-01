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
  - {name: m2, host: gimle-2.example.com, sshHostKeyFingerprint: "SHA256:abc...",
     ssh: {user: deploy, port: 2222, archive: /local/gimle-platform-m2.tar.gz}}

runtime:                         # entire section optional
  javaExecutable: java
  classpath: /opt/gimle/lib/*
  dataRoot: /var/lib/gimle
  ssh: {user: ubuntu, identityFile: /home/op/.ssh/id_ed25519, installDir: /opt/gimle,
        archive: /local/gimle-platform.tar.gz}
                                  # topology-wide default for `--remote`; a machine's own `ssh:`
                                  # (like m2's above) overrides it field by field. `archive` is the
                                  # local platform archive `--remote up` ships and unpacks when
                                  # `<installDir>/bin/hilmir` isn't already there.
                                  # `sshHostKeyFingerprint` (machine-only, no runtime-wide tier)
                                  # pins that one host's SSH key; left unset, `--remote` trusts
                                  # whatever key it scans on first use instead.

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
  declares, concurrently. Shells out to the operator's own `ssh`/`scp`/`ssh-keyscan`/`ssh-keygen`
  (no SSH library dependency); verifies every target's SSH host key against a per-topology
  `known_hosts` file (pinned to a declared `sshHostKeyFingerprint` when present, trust-on-first-use
  otherwise); self-provisions `<installDir>/bin/hilmir` from the resolved `archive` when it isn't
  already there; and distributes exactly the TLS/Fafnir-key material each machine needs, already
  generated locally by `hilmir pki init` -- no credential handling of its own beyond that. See
  `gimle-docs`' own Remote (SSH) fleet bootstrap reference section for the full detail and the
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
- `hilmir upgrade-cluster -f <topology.yaml> --machine <name> --new-classpath <cp> [...]` -- a
  per-machine platform binary rollout; see `gimle-docs`' own Cluster upgrade reference section for
  its own flags. `hilmir upgrade-cluster -f <topology.yaml> --remote [--machine <name>]
  --new-classpath <cp> [...] [--ssh-user <user>] [--ssh-key <path>] [--ssh-port <port>]
  [--install-dir <path>]` dispatches that exact same command over SSH the same way `up`/`down`/
  `status --remote` do.
- `hilmir pki init -f <topology.yaml>` -- generates a brand-new cluster's shared secret material,
  once, locally: for an mtls topology (`transport: mtls` with `tls.materialDir` configured), the
  cluster CA and one leaf certificate per (role, machine hostname) by spawning the platform's own
  `PkiBootstrapMain` -- whose one-time bootstrap console password is written to
  `bootstrap-password.txt` inside `tls.materialDir` (owner-only) rather than printed, since this
  command captures that subprocess's output into a log file; and, whenever `fafnir.keyFile` is
  configured and no file already exists there, a fresh Fafnir key -- for a plaintext topology this is the *only* thing `pki init` does, and only
  when Fafnir spans more than one machine (a single-machine Fafnir still just generates its own key
  on first start, same as always). `--remote up` distributes exactly what each machine needs from
  this material before starting any process there.
- `hilmir doctor <jar> [<dep-jar>...] [--vessel] [--server host:port] [--tenant <id>] [-o json]` --
  static pre-flight checks against a built jar, needing neither a topology document nor a running
  control plane. Runs the full static finding catalog (structural jar inspection, a lenient
  `gimle-module.yaml` read, and a bytecode hazard scan for things like `System.exit`, shutdown-hook
  registration, non-daemon threads, native-library loading, server-socket opening, and an
  unshutdown static `ExecutorService`) against `<jar>`; any further positional jars are extra
  artifacts on the same hypothetical module path, consulted only by the split-package/bundled-
  logging-binding checks. Evaluates module-hosting intent by default (the richer, more constrained
  path); `--vessel` switches to the smaller vessel-hosting check set instead, mirroring how a real
  deploy manifest's own `vessel:` block presence/absence -- not jar-sniffing -- is the platform's
  actual switch. `--server host:port` adds cluster-aware checks on top: `REGISTRY_COORDINATE_NOT_FOUND`
  if the jar's own `(name, version)` isn't present in the artifact registry behind that control
  plane, and (with `--tenant <id>`) `TENANT_NOT_FOUND` if that tenant doesn't exist there either.
  Exits non-zero on any `ERROR`-severity finding, matching `hilmir validate`'s own exit-code
  convention; `-o json` prints findings as a JSON array instead of the default text listing.

`down`/`status` deliberately take `--data-root` rather than `-f` for local dispatch: the run ledger
lives under a resolved runtime's own data root, and neither verb needs the topology document again
to find it. `--remote` is the one exception -- see above.
