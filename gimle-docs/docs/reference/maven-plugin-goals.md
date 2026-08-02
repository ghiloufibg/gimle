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

## `mvn gimle:docs`

Builds this documentation site end to end: runs `mvn javadoc:aggregate` at the repo root, copies
the output into `gimle-docs/static/javadoc/`, then builds the Docusaurus site — see `gimle-docs`'s
own `README.md` and `pom.xml` description for why those two steps aren't chained by the reactor
build alone.
