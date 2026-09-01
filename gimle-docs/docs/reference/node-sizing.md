---
sidebar_position: 8
---

# Node sizing and worker density

How much of a machine one Gimlé node actually consumes, and how to tune the one knob that decides
how tightly Tier-1 workloads pack onto it. Everything below is derived from what the code does —
there are no benchmark figures here, because none have been measured.

## What runs on a node

A node runs exactly two kinds of JVM, and nothing else:

- **One node agent** (`AgentMain`) per machine, for the machine's whole lifetime. It supervises
  workers, reports capacity, relays config/policy, and never runs user code. Its own heap is not
  sized from any manifest — it takes whatever `-Xmx` you launch it with.
- **One worker JVM per spawned worker.** A Tier-2 instance always gets its own; Tier-1 instances
  may share one (see below). A vessel is always its own process too.

See [Node topology](../architecture/node-topology.md) for the cluster-wide picture and
[Tiered isolation](../architecture/tiered-isolation.md) for what each tier guarantees.

## Where a worker's ceiling comes from

Resource enforcement today is the portable `PortableJvmFlagsResourceLimiter` (`gimle-os`) — JVM
flags only, no cgroups, identical on Linux/macOS/Windows. When the agent spawns a worker it derives
two flags from the module's declared **`resources.limit`**:

| Flag | Derived from |
|---|---|
| `-Xmx<bytes>` | `resources.limit.memory` |
| `-XX:ActiveProcessorCount=<n>` | `ceil(resources.limit.cpu / 1000m)`, never below `1` |

`resources.request` is a deliberately different figure: it is what the agent's `CapacityTracker`
accounts against the machine's total memory and `availableProcessors() * 1000` millicores, and what
the scheduler places against. Request is the planning number; limit is the ceiling the JVM is
actually launched under.

:::caution[The limiter is JVM-level, not kernel-level]

`-Xmx` bounds the Java heap. It does not bound metaspace, thread stacks, direct/mapped buffers, code
cache, or a native allocation made by a library the module pulled in. Budget real headroom per
worker process above its `-Xmx` — the JVM's own non-heap footprint is not a rounding error, and
nothing in the platform will stop a runaway native allocation today.

:::

## Tier 1 density

The node agent packs several Tier-1 instances into one shared worker JVM when it is safe to. All of
these must hold:

- the instances are on the same node (implicit — an agent only ever reuses its own workers),
- the same tenant, or both untenanted,
- no two instances of the same module in one worker (that would corrupt `WorkerRuntime`'s
  per-`ModuleId` keying),
- the worker is holding fewer instances than the density cap.

This is agent-local and invisible to the control plane. The scheduler reasons about node-level
capacity only; it has no concept of which worker a Tier-1 instance lands in once placed.

### The knob

| Property | Default | Meaning |
|---|---|---|
| `gimle.agent.maxTier1Density` | `4` | The most Tier-1 instances this agent will pack into one shared worker JVM before preferring a fresh one. |

```bash
java -Dgimle.agent.maxTier1Density=8 -cp ... com.gimle.agent.AgentMain node-1 http://...
```

`1` disables packing entirely — every Tier-1 instance gets its own worker JVM, which is the same
process topology Tier 2 gives, without Tier 2's own dedicated `-Xmx` per instance. A value of `0`,
a negative number, or anything non-numeric **fails the agent at startup** rather than being ignored:
an operator who set this meant to change the packing behavior, and a setting that silently does
nothing is worse than a startup error that says exactly what is wrong.

The default of `4` is a conservative starting point, not a measured optimum.

### Choosing a value

The decisive fact is that **a shared worker's ceiling is fixed at spawn time and never
subdivided**. The first Tier-1 instance to land in a worker sizes it from its own
`resources.limit`; every instance packed in afterwards runs inside that same `-Xmx`, and the
worker's flags are not recomputed. So the arithmetic to do is:

> one worker's `-Xmx` must comfortably hold the *combined* live footprint of up to
> `maxTier1Density` instances of that tenant's modules, plus the JVM's own overhead.

Three practical consequences:

1. **Raising density without raising `resources.limit` shrinks each instance's real share of
   heap.** If your Tier-1 modules declare a limit sized for one instance, raising the cap to 8 is a
   way to run out of heap, not a way to gain density. Raise the modules' declared limits, or leave
   the cap alone.
2. **Density multiplies the blast radius of one worker.** A worker JVM that dies — an OOM, a native
   crash, a `destroyForcibly` during self-healing — takes every instance packed into it down at
   once, and they are all restarted together. A cap of 1 trades density for an independent crash
   domain per instance.
3. **A module that leaks its classloader hurts its neighbours.** Repeated redeploys of a leaking
   Tier-1 module grow the metaspace of the worker its co-tenants are also running in. The
   platform's own answer is to move that module to Tier 2, where undeploy simply kills the JVM —
   see [Module system](../architecture/module-system.md).

Start at the default, watch `gimle.module.metaspace.bytes` and each worker's reported heap usage
(see [Observability](../architecture/observability.md)), and move the cap in one direction with the
module limits adjusted to match, rather than tuning both at once.

### What density does *not* change

- **Capacity accounting.** Every instance is accounted against the node's capacity by its own
  `resources.request`, packed or not — density does not make an instance free to the scheduler.
- **Per-instance identity.** Logs, metrics, probes, and lifecycle events stay per instance; only
  the hosting process is shared. A packed instance's logs live under the connection-owning
  instance's worker directory, which `AgentLogServer` resolves for you.
- **Tier 2 and vessels.** Neither is ever packed. The cap applies to Tier 1 only.

## Node-level checklist

- Total worker `resources.limit.memory` across the workers a node will host, plus per-process JVM
  overhead, plus the agent's own heap, must fit in the machine — the `CapacityTracker` only guards
  the *request* totals, not the limits.
- `-XX:ActiveProcessorCount` is derived per worker and does not partition real cores: the sum
  across workers can exceed the machine's core count. Treat CPU limits as scheduling hints, not
  hard partitions, until kernel-level enforcement lands.
- Each worker writes its own log directory and, on a native crash, its own `hs_err_pid*.log` under
  `gimle.log.root` — size that filesystem for the worker count, not for one process.
