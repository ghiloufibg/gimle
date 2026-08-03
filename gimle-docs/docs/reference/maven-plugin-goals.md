---
sidebar_position: 4
---

# `gimle-maven-plugin` goal reference

`spring-boot:run`-style developer-experience goals — invoked straight from the reactor root, no
`-pl <module>` needed. Each goal spawns a genuine separate OS process (never runs a target's
`main()` via reflection in-process) and self-filters to its one target module, no-op'ing
everywhere else in the reactor. One-time setup: `com.gimle` needs a `<pluginGroups>` entry in
`~/.m2/settings.xml` (see [Getting started](../tutorials/getting-started.md)) for the short
`gimle:*` prefix form to resolve at all.

## `mvn gimle:controlplane`

Launches a real `ControlPlaneMain` process.

| Property | Default | Meaning |
|---|---|---|
| `gimle.controlplane.port` | `8080` | API server port. |
| `gimle.controlplane.stateDir` | `${project.build.directory}/gimle-state` | Where `StateStore`/`RaftLog` persist to disk — see [Control plane](../architecture/control-plane.md). |
| `gimle.controlplane.raftPort` | `9080` | Raft transport port. |
| `gimle.controlplane.transportProtocol` | *(unset, plaintext)* | Local-dev convenience for `gimle.transport.protocol` — see [Transport security](../architecture/transport-security.md). |

```bash
mvn gimle:controlplane -Dgimle.controlplane.port=8081
```

## `mvn gimle:agent`

Launches a real `AgentMain` process, plus a worker command-tail whose classpath is resolved
separately (the worker is a genuinely different OS process — `AgentMain`'s own code never imports
`com.gimle.worker.*`, by design, since the [Node Agent never runs hosted-module code
itself](../architecture/node-topology.md)). Requires `mvn install` to have already produced the
`gimle-worker` artifact this goal resolves against.

| Property | Default | Meaning |
|---|---|---|
| `gimle.agent.nodeId` | `node-1` | This node's identifier. |
| `gimle.agent.controlPlaneUrl` | `http://127.0.0.1:8080` | Control plane to register with. |
| `gimle.agent.gossipAddress` | `127.0.0.1:9090` | This node's own gossip listen address — see [Service fabric](../architecture/service-fabric.md). |
| `gimle.agent.transportProtocol` | *(unset, plaintext)* | Local-dev convenience for `gimle.transport.protocol` — see [Transport security](../architecture/transport-security.md). |

```bash
# A second agent alongside the first, on the same machine
mvn gimle:agent -Dgimle.agent.nodeId=node-2 -Dgimle.agent.gossipAddress=127.0.0.1:9091
```

## `mvn gimle:deploy`

A thin wrapper around a real `GimleCli apply` invocation — see
[Deploy your first module](../tutorials/deploy-your-first-module.md).

| Property | Default | Meaning |
|---|---|---|
| `gimle.deploy.file` | *(required)* | Path to the deployment manifest. |
| `gimle.deploy.server` | `127.0.0.1:8080` | Control plane address. |

```bash
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/greeter-provider/deployment.yaml
```

## `mvn gimle:tls-init`

Generates the cluster CA, the control plane's own leaf certificate, and the first human operator's
leaf certificate via a real `com.gimle.pki.PkiBootstrapMain` subprocess — everything a brand-new
cluster needs to start in `gimle.transport.protocol=tls` mode. See
[Transport security](../architecture/transport-security.md). Unlike `gimle:agent`, this needs no
cross-module classpath resolution: `PkiBootstrapMain` lives in `gimle-pki` itself, the module this
goal targets.

| Property | Default | Meaning |
|---|---|---|
| `gimle.tlsInit.outputDir` | `./gimle-tls` | Where the generated `.crt`/`.key` files are written. |
| `gimle.tlsInit.caCommonName` | `gimle-cluster-ca` | The cluster CA's own Subject CN. |
| `gimle.tlsInit.hostname` | `localhost` | SAN on the control plane's leaf certificate — clients must reach the control plane by this hostname, not a bare IP literal (no `iPAddress` SAN support yet). |

```bash
mvn gimle:tls-init -Dgimle.tlsInit.outputDir=./gimle-tls -Dgimle.tlsInit.hostname=localhost
```

## `mvn gimle:docs`

Builds this documentation site end to end: runs `mvn javadoc:aggregate` at the repo root, copies
the output into `gimle-docs/static/javadoc/`, then builds the Docusaurus site — see `gimle-docs`'s
own `README.md` and `pom.xml` description for why those two steps aren't chained by the reactor
build alone.
