# Gimle Agent

The node agent: one per machine, `AgentMain` is the process that owns every worker JVM's lifecycle
on that machine, assigns it a resource limit, supervises and restarts it on crash, and reports the
machine's capacity and instance state back to the control plane. It never runs hosted-module code
itself — every module instance lives inside a `gimle-worker` (or, for a vessel, a plain OS process)
this agent spawns and supervises, never in-process here.

## Process role

`AgentMain` registers with the control plane once, then loops forever: polls
`GET /nodes/{nodeId}/assignments` and reconciles the locally-supervised worker/vessel set against
it (spawning a worker JVM or vessel process per newly-assigned instance, tearing one down per
instance no longer assigned — each replica gets its own worker JVM, matching the scheduler's
anti-affinity assumption), then reports a heartbeat. Independent of that loop, it also runs a SWIM
membership member (`GossipMember`, over UDP) carrying a `ServiceCatalog` on its gossip piggyback
channel, folding service-registration deltas from its own supervised workers into the catalog and
relaying every new delta — local or learned from gossip about a remote node — back down to every
supervised worker as a catalog update, so each worker's `FabricServiceRegistry` stays eventually
consistent without a central catalog service.

## What runs inside the agent process

| Component | Role |
|---|---|
| `WorkerProcessSupervisor` | Spawns and supervises one worker JVM via the `Process` API; restart on unexpected exit uses the same `RestartTracker`-driven destroy-and-respawn backoff `gimle-worker`'s module-level restart uses one tier down, with its own numeric parameters. |
| `VesselProcessSupervisor` / `VesselProber` | The equivalent for a vessel — a plain runnable jar the agent spawns directly as its own OS process rather than loading into a worker JVM's `ModuleLayer`. Captures stdout/stderr unconditionally as the instance's APPLICATION log (a vessel's output is whatever arbitrary program it runs, never Gimlé's own structured Logback JSON), and gets the same Tier-2-equivalent crash-domain guarantee. |
| `CapacityTracker` | Tracks this machine's total resource capacity (read via `com.sun.management.OperatingSystemMXBean`, a standard JDK API, not an OS-specific mechanism) against what's currently assigned to spawned workers, as a plain in-process query. |
| `ControlChannelServer` / `WorkerConnection` | Agent-side half of the newline-delimited control channel each supervised worker connects into. |
| `AgentGossipServer` | Read-only HTTP surface (`/gossip/members`) exposing this node's live SWIM membership view. |
| `AgentLogServer` | Read-only HTTP surface backing `/logs/*` proxying — the control plane forwards a log request to whichever node hosts the target instance, the same architecture `kubectl logs` uses (API server → kubelet → node's local log file). Reads node-level platform logs and per-worker instance logs from this agent's own `logRoot`, where each spawned worker's `-Dgimle.log.root` is pointed. |
| `NetworkPolicyRelay` (`com.gimle.agent.networkpolicy`) | Polls the control plane's `GET /networkpolicies` and relays the full current rule set — tenant-wide and per-deployment-scoped — to every supervised worker over the control channel (`ControlMessage.NetworkPoliciesUpdated`), so `FabricServer` on each worker can independently re-check a caller's tenant against policy before dispatch. |
| `BifrostProxy` (`com.gimle.agent.bifrost`) | An optional embedded per-node service proxy, off by default (`-Dgimle.agent.bifrostEnabled=true`). Polls a `ServiceSource` on a fixed interval and keeps one loopback-bound listener per currently-known Service, round-robin-forwarding to live endpoints; also polls the same `NetworkPolicySource` and fails a Service's listener closed whenever a policy currently applies to its tenant/deployment, since — unlike `FabricServer` — it relays opaque bytes for whatever protocol the caller speaks and has no caller identity to check a policy against. |

## Isolation-tier boundary enforced here

`AgentMain` is where an unsupported Tier 3 assignment is rejected: assigning a module that declares
Tier 3 isolation throws `GimleIsolationException.tierUnsupported(...)` rather than silently
downgrading it to Tier 1 or Tier 2. This is "not built yet," not "your platform doesn't support
it" — there is no FFM `unshare`/`setns` code path anywhere in this module to fall back to.

## Resource assignment and secrets

Resource limits assigned to a spawned worker come from the portable `ResourceLimiter` in
`gimle-os` (`PortableJvmFlagsResourceLimiter` — `-Xmx`/`ActiveProcessorCount` JVM flags, identical
across platforms); this agent has no kernel-level cgroup enforcement. When
`-Dgimle.agent.fafnirEndpoint` is configured, this agent fetches secret values straight from
`gimle-fafnir` over its own mTLS node identity — rather than through the control plane — so a
node's instances never wait on the control plane to broker every secret read; an agent with no
tenant ever using secrets never needs it configured, and instances still start without it, simply
without any secret access.

## Observability

`AgentMetrics` (from `gimle-observability`, the agent's first genuine main-scope dependency on that
module) tracks this agent's own request shape. When `-Dgimle.agent.muninnEndpoint` is configured,
the agent ships its own platform log plus every supervised worker's logs, metrics, and traces to
`gimle-muninn` via `MuninnShipper` — workers relay their own metrics/trace snapshots up over the
existing control channel for the agent to forward, since a worker has no outbound network identity
of its own.

## How other modules use this one

`gimle-controlplane` depends on `gimle-agent` in test scope, to drive a real agent process against
a real control plane in integration tests. `gimle-smoke-tests` and `gimle-holmgang` spawn real
`AgentMain` subprocesses as part of their cluster fixtures. `gimle-hilmir` resolves `AgentMain`'s
process command as one of the process kinds a topology document can boot.
