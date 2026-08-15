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
  - {name: m2, host: gimle-2.example.com}

runtime:                         # entire section optional
  javaExecutable: java
  classpath: /opt/gimle/lib/*
  dataRoot: /var/lib/gimle

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

Implemented:

- `hilmir validate -f <topology.yaml>` -- parses and validates a topology, printing every finding
  (errors first). Exits non-zero only if an `ERROR`-severity finding exists.
- `hilmir plan -f <topology.yaml> [--machine <name>]` -- validates (aborting with the same findings
  output on any error), then prints the fully resolved per-machine process commands. `--machine`
  filters to one machine; omitted prints every machine.

Not yet implemented (each prints "not yet implemented" and exits 2):

- `hilmir up -f <topology.yaml> --machine <name>`
- `hilmir down --machine <name>`
- `hilmir status --machine <name>`
- `hilmir pki init -f <topology.yaml>`
