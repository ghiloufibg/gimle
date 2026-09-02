---
sidebar_position: 1
---

# Tiered isolation

The central design claim of Gimlé is container-grade isolation and classloader-grade density in
one system, selected per workload via the module manifest:

```mermaid
graph TD
    Machine["Machine (Node Agent, JVM)"]
    Machine --> Worker["Worker JVM<br/>memory/CPU boundary, own -Xmx, own resource limiter"]
    Worker --> Module["Module<br/>ModuleLayer + classloader, soft accounting"]
    Module --> Instance["Instance<br/>bounded virtual-thread scheduler"]
```

| Tier | Placement | Guarantee | Deploy cost |
|---|---|---|---|
| **Tier 1** | Module in a shared worker JVM | Classloader-level isolation, soft JFR-based accounting | Millisecond deploys — the density win |
| **Tier 2** | Module in a dedicated worker JVM | Hard `-Xmx`/CPU ceiling, independent crash domain | Sub-second deploy (AOT cache) — Kubernetes-equivalent guarantee, available per module |
| **Tier 3** | Worker JVM in a Linux namespace (FFM `unshare`/`setns`) | Kernel-level isolation, for hostile-neighbour scenarios | Not yet implemented |

## What's actually enforced today

**Platform independence first, platform-specific enforcement later** — a deliberate design
choice, not a gap. Tier 1/2 limits are enforced entirely through the portable `ResourceLimiter`
interface (`gimle-os`) and its only current implementation, `PortableJvmFlagsResourceLimiter`:
`-Xmx` / `ActiveProcessorCount`, identical on Linux/macOS/Windows, zero OS-specific code.

:::note[Two things are explicitly deferred, not oversights]

- **Kernel-level resource enforcement** (cgroup v2 on Linux, via plain `java.nio.file` I/O against
  `/sys/fs/cgroup` — no containerd/runc equivalent needed) is a second `ResourceLimiter`
  implementation that hasn't been built yet.
- **Tier 3 isolation** (FFM downcalls to `unshare`/`setns`) is unimplemented on every platform
  today. Requesting it fails outright with `GimleIsolationException` rather than silently
  downgrading to a weaker tier — "not built yet," not "your platform doesn't support it."

:::

## Why this matters when reading the code

Don't expect to find cgroup or FFM-namespace code anywhere in `gimle-os` — it isn't there yet, on
purpose. The portable JVM-flags path is the whole story for resource enforcement today. See
[Module lifecycle](../reference/module-lifecycle.md) for how a module moves through a worker once
placed, and [Node topology](./node-topology.md) for how Node Agent, Worker JVM, and Control Plane
relate above this diagram.

## Tier 1 density: agent-local, not scheduler-visible

The node agent packs multiple Tier-1 instances into one shared worker JVM when it's safe to do so:
same node (implicit -- an agent only ever reuses its own already-running workers), same tenant (or
both untenanted), never two instances of the same module (which would corrupt `WorkerRuntime`'s
per-`ModuleId` keying), under a density cap (`-Dgimle.agent.maxTier1Density`, default `4`;
`1` disables packing entirely, and a zero, negative, or non-numeric value fails the agent at
startup rather than being ignored), and within the node's shared-worker heap budget
(`-Dgimle.agent.tier1WorkerHeap`, default `1Gi`, less `-Dgimle.agent.tier1WorkerOverheadReserve`,
default `128Mi`): an instance joins a shared worker only while the declared
`resources.limit.memory` of everything already in it, plus its own, still fits. An instance that
doesn't fit gets a fresh worker rather than being refused. See [Node sizing and worker
density](../reference/node-sizing.md) for how to choose these values and what they cost. This is
deliberately agent-local and invisible to the control plane: the scheduler still reasons about
node-level capacity only, with no concept of which worker a Tier-1 instance lands in once it's
placed on a node.

A shared worker is sized by that budget rather than by whichever instance happened to spawn it, so
a Tier-1 `resources.limit` is a real admission input with a predictable worker behind it. It is
still not a per-instance ceiling: a JVM has one heap, so a module allocating past its own declared
limit draws on the whole worker and can still OOM its co-tenants. Genuine per-instance memory
subdivision inside a shared JVM is not reachable with JVM flags at all, which is what Tier 2 is
for.
