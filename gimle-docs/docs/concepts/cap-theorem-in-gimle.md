---
sidebar_position: 4
---

# CAP theorem, seen across Gimlé's own components

The previous three pages each covered one mechanism in isolation. This page is the synthesis:
*why* Gimlé makes different tradeoffs in different parts of the same cluster, using the classic
lens for thinking about that tradeoff.

## The theorem, briefly

CAP theorem says a distributed system that replicates data can't simultaneously guarantee all
three of:

- **Consistency** — every read sees the most recent write (or an error), never stale data.
- **Availability** — every request gets *some* response, never a hang or a refusal.
- **Partition tolerance** — the system keeps working even when the network splits nodes into
  groups that can't reach each other.

In practice, partitions happen (cables get cut, switches misbehave, a cloud AZ has a bad day), so
**P isn't optional** — a real system has to tolerate it. The actual choice CAP forces on you is
between **C and A** *during* a partition: when part of the cluster can't reach the rest, do you
refuse to answer (protecting consistency) or answer anyway with whatever you have (protecting
availability, at the risk of a stale or divergent answer)?

Gimlé doesn't make one choice for the whole platform — different pieces of it sit at different
points on that line, on purpose, because they're protecting different things.

## `gimle-mimir`: chooses C over A

The state store is Gimlé's source of truth for deployments, nodes, tenants, and RBAC — the kind of
data where a stale or divergent answer is actively dangerous (imagine two control-plane replicas
disagreeing about whether a tenant is over quota, or which node a security-sensitive instance is
scheduled on). See [Consensus and replication](./consensus-and-replication.md): Raft's majority-write
rule means a partitioned minority simply **can't accept writes at all** — `checkQuorumTick` forces
a leader stuck on the minority side to step down rather than keep serving stale-but-confident
answers. That minority becomes briefly unavailable rather than silently wrong. This is a textbook
**CP** choice.

## `gimle-fabric`'s gossip membership: leans A over C

Cluster membership — who's alive, who's suspect, who's dead — is a different kind of data. A
slightly stale view (a node that's actually back up but still shows `SUSPECT` for another second)
is a minor inefficiency; refusing to answer "who's alive?" at all would be far worse, since routing
decisions depend on it constantly. See [Failure detection and gossip](./failure-detection-and-gossip.md):
SWIM never blocks waiting for perfect agreement — every node answers from its own local, eventually-
consistent view, and disagreements resolve themselves over the next few gossip rounds via
piggybacking and anti-entropy sync. This is a textbook **AP** choice: available and
partition-tolerant, consistent only eventually.

## `gimle-fabric`'s circuit breaking: never blocks, degrades instead

[Load balancing and resilience](./load-balancing-and-resilience.md) extends the same availability-first
posture one layer further: rather than blocking a caller until a definitive health verdict is
reached about a replica, the circuit breaker makes an immediate local decision from recent history
and moves on — refuse fast (`OPEN`), or try cautiously (`HALF_OPEN`), never hang waiting to find
out for certain. The panic-mode floor (re-admit everyone if breaker trips would exclude a majority)
is the same instinct in a different spot: a *guess* that's probably right beats certainty that
arrives too late or not at all.

## Why the split, not one answer for everything

The reason this isn't a contradiction is that **C and A aren't properties of a cluster, they're
properties of a specific piece of data** — and Gimlé's process split (see [Node
topology](../architecture/node-topology.md)) makes that boundary a real architectural line, not
just a design note. `gimle-mimir` is its own process, physically separate from the gossip and
fabric machinery in `gimle-agent`/`gimle-worker`, specifically so a CP guarantee for cluster state
doesn't force an AP tradeoff onto request routing, and vice versa. If membership gossip had to go
through the same majority-write path as a deployment record, a network blip would make routing
*and* scheduling both hang together, for data (who's alive right now) where a fast, slightly-stale
answer is what you actually want.

## One more axis worth knowing: PACELC

CAP only describes behavior *during* a partition. **PACELC** extends the idea to the common case
where there *isn't* one: even with the network fully healthy, a system still has to choose between
**L**atency and **C**onsistency for every write — do you wait for full replication acknowledgment
(more latency, definitely consistent) or return early (less latency, briefly less certain)? Raft's
majority-commit rule answers that one too: `propose()` blocks the caller until a majority has
acknowledged, spending real latency to buy real consistency, on every single write — not just
during partitions. That's a second, independent reason `gimle-mimir` is a dedicated process: the
latency cost of "wait for majority" is one `gimle-fabric`'s same-worker/same-machine-first routing
was explicitly built to avoid paying on the vastly more frequent service-to-service call path.
