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
  sized from any manifest — it takes whatever `-Xmx` you launch it with. Size that heap for the
  number of instances this node will supervise, not just for one: a burst of bookkeeping (many
  pre-existing deployments reconciled at once after a restart, for example) that exhausts it makes
  the agent's own tick loop halt the JVM outright — deliberately, so the process actually exits and
  the supervisor restarts it — rather than leaving a partially-updated agent limping along.
- **One worker JVM per spawned worker.** A Tier-2 instance always gets its own; Tier-1 instances
  may share one (see below). A vessel is always its own process too.

See [Node topology](../architecture/node-topology.md) for the cluster-wide picture and
[Tiered isolation](../architecture/tiered-isolation.md) for what each tier guarantees.

## Where a worker's ceiling comes from

Resource enforcement today is the portable `PortableJvmFlagsResourceLimiter` (`gimle-os`) — JVM
flags only, no cgroups, identical on Linux/macOS/Windows. When the agent spawns a worker it derives
two flags from that worker's size:

| Flag | Derived from |
|---|---|
| `-Xmx<bytes>` | the worker's memory size |
| `-XX:ActiveProcessorCount=<n>` | `ceil(the worker's cpu size / 1000m)`, never below `1` |

Where that size comes from depends on the tier, because the two tiers differ in how many instances
share one heap:

| Tier | Worker sized from |
|---|---|
| **Tier 2** | the instance's own `resources.limit` — one instance per worker, so its declared limit *is* the worker's limit. |
| **Tier 1** | the node's shared-worker budget (see [Tier 1 density](#tier-1-density) below), never any single instance's limit — several instances share one heap, so none of them owns it. |

`resources.request` is a deliberately different figure throughout: it is what the agent's
`CapacityTracker` accounts against the machine's total memory and `availableProcessors() * 1000`
millicores, and what the scheduler places against. Request is the planning number; the table above
is the ceiling the JVM is actually launched under.

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
- the worker is holding fewer instances than the density cap,
- the summed `resources.limit.memory` of everything already on that worker, plus the candidate's
  own, still fits inside the heap that worker was actually spawned with (see the budget below).

An instance that fails only the last two conditions is not rejected — it gets a fresh worker of its
own instead. This holds however many Tier-1 instances arrive in the same reconcile tick — a
scale-up, or an agent's first reconcile after restarting and finding a pile of pre-existing
assignments — not only one at a time with a pause in between: packing is decided the moment a
worker exists, before it has necessarily finished connecting.

This is agent-local and invisible to the control plane. The scheduler reasons about node-level
capacity only; it has no concept of which worker a Tier-1 instance lands in once placed.

### The shared-worker budget

A Tier-1 worker's heap is not any one instance's to set — several instances run behind it, so
sizing it from whichever one happened to spawn it would make every other instance's own declared
limit both unenforced and unpredictable. Instead, the node declares a fixed budget every shared
worker is spawned at:

| Property | Default | Meaning |
|---|---|---|
| `gimle.agent.tier1WorkerHeap` | `1Gi` | The `-Xmx` a shared Tier-1 worker is spawned with. |
| `gimle.agent.tier1WorkerCpu` | `2000m` | The `-XX:ActiveProcessorCount` figure a shared Tier-1 worker is spawned with. |
| `gimle.agent.tier1WorkerOverheadReserve` | `128Mi` | Heap held back from every shared worker before any instance is admitted, for the module runtime, the fabric server, the metrics registry, the leak detector's recording stream, and a scheduler per hosted instance. |

A module declaring more heap than a whole budget is never strangled by it: such an instance gets a
worker sized to its own `resources.limit` plus the overhead reserve instead, which — correctly —
leaves no room for anything else to join it. Every other admission is a straight sum: an instance is
packed onto an existing worker only while the residents' declared memory limits plus its own still
fit inside that worker's actual heap, minus the reserve. Only memory is summed; CPU is time-shared
rather than exhaustible, so two modules each declaring a whole worker's worth of CPU is ordinary and
harmless, not a reason to refuse packing them together. As with the density cap, a malformed value
for any of these three properties **fails the agent at startup**.

This is a reservation, not a partition: one JVM still has one heap, so a module that allocates past
its own declared limit can still exhaust the worker its co-tenants are running in. That remains the
reason Tier 2 exists.

### The density cap

| Property | Default | Meaning |
|---|---|---|
| `gimle.agent.maxTier1Density` | `4` | The most Tier-1 instances this agent will pack into one shared worker JVM before preferring a fresh one. |

```bash
java -Dgimle.agent.maxTier1Density=8 \
     -Dgimle.agent.tier1WorkerHeap=2Gi \
     -cp ... com.gimle.agent.AgentMain node-1 http://...
```

`maxTier1Density=1` disables packing entirely — every Tier-1 instance gets its own worker JVM, which
is the same process topology Tier 2 gives, without Tier 2's own dedicated `-Xmx` per instance. A
value of `0`, a negative number, anything non-numeric, an unparseable quantity, or a reserve as
large as the heap **fails the agent at startup** rather than being ignored: an operator who set one
of these meant to change the packing behavior, and a setting that silently does nothing is worse
than a startup error that says exactly what is wrong.

The defaults are conservative starting points, not measured optima.

### How the budget is spent

A shared worker is spawned at `tier1WorkerHeap`, and instances are admitted into it while the sum of
their declared `resources.limit.memory` still fits in `tier1WorkerHeap - tier1WorkerOverheadReserve`.
The decisive fact is that **a shared worker's ceiling is fixed at spawn time and never
subdivided** — it comes from the budget above, not from any one instance packed into it, and is
never recomputed as more instances join. So the arithmetic to do is:

> the declared limits of the modules you want packed together must sum to less than
> `tier1WorkerHeap` minus the reserve, and their combined *live* footprint must comfortably fit the
> same space — up to `maxTier1Density` instances of that tenant's modules, plus the JVM's own
> overhead (the reserve above is a floor for that overhead, not a measured figure for your modules).

**Limits are summed, not requests.** Requests may be oversubscribed where each workload has its own
enforced ceiling; here there is no per-instance ceiling to fall back on, so admitting on requests
would make "every co-tenant reaches the bound its manifest promises" precisely the case that OOMs
the worker.

**CPU is not summed.** Heap runs out permanently and takes the JVM with it; CPU is time-shared and
merely gets slower under contention, so two modules each declaring a whole worker's worth of CPU
still pack together.

**A module declaring more heap than a whole budget still gets what it asked for.** Its worker is
spawned at its own limit plus the reserve rather than at `tier1WorkerHeap`, so a fixed budget never
becomes a fixed ceiling that strangles a large module — and that worker is then correctly full, with
no room for a co-tenant.

Three practical consequences:

1. **Raising the density cap alone does nothing once the summed-limit budget check is the binding
   constraint.** The count cap and the budget are two independent gates — an instance is packed
   only while it is under *both*. If your Tier-1 modules' declared limits already sum to the
   budget's usable heap (its size minus the overhead reserve) before the cap is reached, raising
   `maxTier1Density` further changes nothing; raise `gimle.agent.tier1WorkerHeap` (or shrink the
   modules' declared limits) instead.
2. **Density multiplies the blast radius of one worker.** A worker JVM that dies — an OOM, a native
   crash, a `destroyForcibly` during self-healing — takes every instance packed into it down at
   once, and they are all restarted together. A cap of 1 trades density for an independent crash
   domain per instance.
3. **A module that leaks its classloader hurts its neighbours.** Repeated redeploys of a leaking
   Tier-1 module grow the metaspace of the worker its co-tenants are also running in. The
   platform's own answer is to move that module to Tier 2, where undeploy simply kills the JVM —
   see [Module system](../architecture/module-system.md).

Start at the defaults, watch `gimle.module.metaspace.bytes` and each worker's reported heap usage
(see [Observability](../architecture/observability.md)), and move the worker heap and the cap one at
a time rather than tuning both at once.

:::caution[Admission is a reservation, not a partition]

A JVM has one heap. Summing declared limits keeps a shared worker from being *knowingly*
oversubscribed, and it makes a Tier-1 `resources.limit` a real admission input rather than a number
the runtime discards — but it does not bound any individual instance. A module that allocates past
its own declared limit still draws on the whole worker heap and can still OOM the JVM its co-tenants
are running in. A per-instance ceiling inside a shared JVM is not reachable with JVM flags at all,
which is the reason Tier 2 exists.

:::

### What density does *not* change

- **Capacity accounting.** Every instance is accounted against the node's capacity by its own
  `resources.request`, packed or not — density does not make an instance free to the scheduler.
- **Per-instance identity.** Logs, metrics, probes, and lifecycle events stay per instance; only
  the hosting process is shared. A packed instance's logs live under the worker's owning instance's
  own directory (the instance that actually spawned the worker), which `AgentLogServer` resolves
  for you.
- **Tier 2 and vessels.** Neither is ever packed. The cap applies to Tier 1 only.

## Node-level checklist

- Total worker heap across the workers a node will host — `tier1WorkerHeap` per shared worker plus
  each Tier-2 instance's own `resources.limit.memory` — plus per-process JVM overhead, plus the
  agent's own heap, must fit in the machine. The `CapacityTracker` only guards the *request*
  totals, not the heaps, and `-Xmx` is a ceiling rather than a reservation, so these sums can
  legitimately exceed physical memory; they are what the machine must survive if every worker fills
  its heap at once.
- `-XX:ActiveProcessorCount` is derived per worker and does not partition real cores: the sum
  across workers can exceed the machine's core count. Treat CPU limits as scheduling hints, not
  hard partitions, until kernel-level enforcement lands.
- Each worker writes its own log directory and, on a native crash, its own `hs_err_pid*.log` under
  `gimle.log.root` — size that filesystem for the worker count, not for one process.
