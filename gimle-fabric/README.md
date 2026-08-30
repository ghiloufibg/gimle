# Gimle Fabric

Fabric is Gimlé's service mesh, in-process: a service registry keyed by interface, three-path
invocation (same-worker, same-machine, cross-machine), locality-preferring load balancing, circuit
breaking, and the SWIM-style gossip membership protocol the whole cluster's node discovery and
service catalog dissemination rides on. It has no process entrypoint of its own — it's a library
module linked into `gimle-agent` (gossip) and `gimle-worker` (registry/transport), never a
standalone Gimlé process kind.

## Three-path invocation

`FabricServiceRegistry` (`registry`) is the worker-side `com.gimle.module.lifecycle.ServiceRegistry`
implementation wired in by `WorkerMain`. `lookup(Class<T>)` tries, in order:

1. **Same-worker** — delegates to a plain in-JVM `localRegistry`; a direct reference, no
   serialization, no network, no proxy at all.
2. **Same-machine** — a Unix domain socket, always plaintext (kernel-mediated, never leaves the
   machine).
3. **Remote (cross-machine)** — TCP, gated on `TransportProtocol.fromConfig()` between plaintext and
   mTLS, virtual-thread-per-connection on the accepting side.

Tiers 2 and 3 both dispatch through a dynamic `Proxy` whose `InvocationHandler` marshals the call
over the fabric wire protocol (`FabricCodec`/`FabricFrame`) — the layer needed for
least-outstanding-requests tracking to be measurable at all. A parallel, string-keyed path,
`invokeByName(interfaceName, majorVersion, methodName, paramTypeNames, args)`, exists for a caller
that only has a service's identity as runtime strings rather than a compile-time `Class<T>` — the
motivating case is `gimle-gateway`'s `FABRIC` route, which names its target service through runtime
config. It walks the identical three tiers with the identical selection logic, just keyed by name
and (unlike `lookup`) narrowed by the export's declared major version.

The proxy for a remote call is built against **the target interface's own classloader**, not a
fixed worker-wide loader — a service interface is typically private to one hosted module's own
`ModuleLayer`, so only that interface's own loader is guaranteed to be able to see it.

## Load balancing and locality

`FabricServiceRegistry` prefers same-machine endpoints over remote ones, but locality isn't a hard
cutoff (`localityAwareCandidates`): once every same-machine candidate is already busier (by
outstanding-request count, from `LeastOutstandingRequestsSelector`) than the least-loaded remote
candidate, the remote tier is admitted into selection too — otherwise a single lightly-loaded
same-machine replica would absorb all traffic forever while idle remote replicas sat unused. This
is a smaller, single-signal version of the overprovisioning-factor idea Envoy's own locality-aware
load balancing uses. An endpoint whose circuit breaker is currently open is scored at a sentinel
"infinitely loaded" value for this comparison — otherwise a fast-failing broken endpoint would look
artificially *least* loaded and win the tier comparison it should actually lose.

`CircuitBreaker` (`breaker`) is a per-endpoint sliding-window error-rate breaker: closed under
normal operation, opens once the error rate over the last `windowSize` calls crosses
`errorRateThreshold`, half-opens after a cooldown to admit exactly one trial call, and closes again
on that trial's success or re-opens on its failure. Each re-open **doubles** the cooldown actually
applied (capped at 16x the base), reset to the base on a successful close — matching Envoy's own
`base_ejection_time * ejections_count` outlier-detection shape, needed because a caller's own retry
cadence can otherwise land in lockstep with a fixed cooldown and keep re-admitting a still-broken
endpoint on almost every call. `FabricServiceRegistry.selectAllowedCandidate` also implements a
panic-mode ejection floor: once more than half (`DEFAULT_MAX_EJECTION_PERCENT`) of a lookup's own
candidates have an open breaker, ejection is bypassed entirely rather than routing the call nowhere
— a correlated failure that happens to be transient shouldn't blackhole every candidate.

## Authorization: independently re-checked at the listener, not trusted from the caller

`FabricServer` (`transport`) is the receiving side. Beyond ordinary dispatch, it independently
re-checks two things on every inbound call rather than trusting that only an already-filtered
caller could have reached it:

- **`checkTenantPermitted`** re-verifies the request's `callerTenantId` against the target's own
  declared `ServiceExport.allowedTenantIds`, closing the bypass where a caller could dial the raw
  catalog address directly and skip whatever filtering `FabricServiceRegistry`'s own caller-side
  `lookup` would have applied.
- **`checkNetworkPolicyPermitted`** independently re-checks the caller's wire-carried tenant
  identity against the current `NetworkPolicyRule` set relayed down from the control plane
  (`FabricServer.updateNetworkPolicies`, kept fresh by the agent's polling relay). A rule applies
  when its `tenantId` matches this worker's own tenant *and* `appliesToDeployment` accepts the
  target's deployment name — resolved per call via `deploymentNameOf`, since one worker JVM can host
  instances from several different deployments under Tier 1 density, so which deployment a call's
  target belongs to can't be fixed once at construction time the way "which tenant does this worker
  serve" can.

Both checks are a "forwarded claim, independently re-checked" posture, the same defense-in-depth
pattern Fafnir's and Andvari's own authorization re-checks use.

## Gossip membership and the service catalog

`GossipMember` (`cluster`) is one node agent's SWIM protocol participant: a protocol-period loop
(ping a random member; on timeout, ask `indirectFanout` random relays to probe on its behalf; no ack
from anyone flips the member to `SUSPECT`; unrefuted `SUSPECT` past its grace period becomes `DEAD`)
running entirely over `DatagramChannel`, independent of the control plane — gossip keeps functioning
with the control plane down or unreachable. A member refutes its own false suspicion automatically
by gossiping a higher incarnation number of itself the moment it observes one. Every message
piggybacks the sender's own state plus a bounded number of recently-changed other members' states
(`PiggybackExtension`), the same slot `ServiceCatalog` rides on.

`ServiceCatalog` is the cluster-wide service directory: keyed by `ServiceExport`, merged from gossip
piggyback deltas, last-writer-wins per `(node, workerId, moduleId)` using an incarnation-style
version counter — the identical conflict-resolution shape SWIM already uses for membership. One
instance runs per node agent, attached to that node's `GossipMember` so catalog data disseminates
over the exact same infection-style gossip as membership itself, never a second mechanism and never
routed through the control plane. It also rides `GossipMember`'s anti-entropy `SyncRequest`/
`SyncResponse` exchange with its own real full-state page (`ServiceCatalog#currentFullStatePayload`),
not the same bounded recent-deltas payload the ordinary piggyback path uses — the same "bounded
piggyback alone can't guarantee eventual convergence" backstop membership itself already relies on.

## Key packages

| Package | Role |
|---|---|
| `cluster` | `GossipMember`, `SwimMessage`/`SwimCodec`, `MemberState`/`MemberStatus`, `PiggybackExtension`, `DtlsPeerSession` — SWIM membership over UDP |
| `catalog` | `ServiceCatalog`, `ServiceEndpoint`, `CatalogDelta`, `ServiceCatalogCodec` — the gossip-propagated service directory |
| `registry` | `FabricServiceRegistry` — the worker-side `ServiceRegistry` tying lookup, locality, load balancing, and circuit breaking together |
| `transport` | `FabricServer`, `FabricClient`, `FabricCodec`/`FabricFrame`, `ObjectMarshalling`, `ReflectiveDispatch`, `ModuleWorkExecutor` — the wire protocol and both ends of a call |
| `balance` | `LeastOutstandingRequestsSelector` |
| `breaker` | `CircuitBreaker` |
| `trace` | `TraceContext` — the compact wire representation of a W3C trace/baggage context carried across a fabric hop |

## Distributed tracing across hops

Every `InvokeRequest` carries a `TraceContext`: trace/span IDs, sample flags, and hand-rolled
encodings of W3C `tracestate` and `baggage` (there's no built-in serializer for either, so
`FabricServiceRegistry`/`FabricServer` encode and decode them directly rather than pulling in a
header-parsing dependency for what's a few lines of format). This is `Context.wrap`
capture-and-restore made concrete across a real network hop, not just a virtual-thread boundary:
the receiving `FabricServer.dispatch` reconstructs a `SpanContext` from the wire fields via
`SpanContext.createFromRemoteParent` and makes it current for the duration of the dispatched call,
so a trace stays correctly parented across same-worker, same-machine, and cross-machine hops alike.

## How other modules consume it

- `gimle-worker` wires `FabricServiceRegistry` in as its `ServiceRegistry` and stands up
  `FabricServer` listeners (UDS same-machine, TCP cross-machine) per worker.
- `gimle-agent` runs `GossipMember`/`ServiceCatalog` per node and relays `NetworkPolicyRule` updates
  down to each supervised worker's `FabricServer` over the existing control channel.
- `gimle-gateway`'s `FABRIC` route type calls through `ModuleContext`'s name-driven invocation path,
  which bottoms out in `FabricServiceRegistry.invokeByName`.
- `gimle-examples/greeter-consumer` exercises the whole stack end to end against
  `greeter-provider` — both `TIER_2`, dedicated workers, guaranteeing the lookup takes the
  cross-worker path rather than the same-worker shortcut.
