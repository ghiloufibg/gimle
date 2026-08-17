---
sidebar_position: 1
---

import ZoomableDiagram from '@site/src/components/ZoomableDiagram';
import DocVideo from '@site/src/components/DocVideo';

# Consensus and replication

If you're new to distributed systems: this page explains *why* a cluster needs consensus at all,
then shows exactly how `gimle-mimir`'s `RaftNode` implements it — real class names, real
constants, no hand-waving.

<DocVideo
  src="/video/raft-consensus-explainer.webm"
  poster="/video/raft-consensus-explainer-poster.png"
  captions="/video/raft-consensus-explainer.vtt"
  caption="Consensus in gimle-mimir: Raft, explained (1m50s)"
/>

<details>
<summary>Video transcript</summary>

How does a cluster of machines agree on one shared truth, even when a node crashes or the network
splits them apart? This is Raft, the consensus algorithm behind gimle-mimir, Gimlé's replicated
state store.

Every node starts out as a follower. Each one runs its own randomized election timer, between 150
and 300 milliseconds. If node A doesn't hear from a leader before its timer fires, it assumes there
isn't one, becomes a candidate, and asks its peers to vote for it.

Node B and Node C don't just vote blindly. Each one checks that Node A's log is at least as
up to date as its own. Only then does it grant its vote. This is what stops a node with stale data
from ever taking over as leader.

Two votes out of three is a majority, so Node A becomes the leader. From now on, it sends a
heartbeat to every follower every 50 milliseconds, asserting that it's still in charge. A follower
stuck in a network partition, unable to reach a majority, can never elect itself leader alone.

Now a client proposes a write — say, registering a new deployment. Only the leader accepts writes.
It appends the entry to its own log first.

Next, the leader replicates that log entry out to every follower, over the same `AppendEntries`
message its heartbeats already use.

Once a majority of nodes — not all of them, just a majority — have acknowledged the entry, it's
considered committed. It's applied to the state machine, and only then is the client finally
unblocked. A minority can never commit anything alone, and that single rule is what makes split
brain impossible.

That's leader election and log replication in Raft, and exactly how gimle-mimir implements both.
The rest of this page goes further: snapshotting, live membership changes, and a real split-brain
bug this project's own test suite caught and fixed.

</details>

## The problem consensus solves

A single machine holding "the truth" (which deployments exist, which nodes are registered, who's
allowed to do what) is a single point of failure — lose that machine, lose the cluster. So you
replicate the data across several machines. But now you have a harder problem: if two of those
machines briefly can't talk to each other (a network blip, not even a crash), and both keep
accepting writes independently, they can each end up with a different idea of "the truth." That's
called **split brain**, and it's the central failure mode consensus algorithms exist to prevent.

**Raft** (the algorithm `gimle-mimir` implements) solves this by making sure only one node is ever
allowed to accept writes at a time — the **leader** — and only once a **majority** of the cluster
has durably recorded a change does it count as real. A minority can never make progress alone,
which is precisely what stops split brain: if the network splits a 3-node cluster into a lone node
and a pair, only the pair (a majority) can still commit anything.

## Leader election

Every node starts as a `FOLLOWER`. Each follower runs its own **randomized election timer** — in
`RaftNode`, `ELECTION_TIMEOUT_MIN_MS = 150` to `ELECTION_TIMEOUT_MAX_MS = 300`, a fresh random pick
in that range every time the timer resets. If a follower doesn't hear from a leader before its own
timer fires, it assumes there isn't one, becomes a `CANDIDATE`, votes for itself, and asks every
peer to vote for it (`RequestVote`).

*Why randomized, not a fixed interval?* If every node used the same timeout, a leader's death would
make every follower become a candidate in the same instant, splitting the vote and forcing a
re-election — over and over. Randomizing means whichever node's timer fires first almost always
gets to campaign and win before anyone else even notices.

A peer grants its vote only if the candidate's log is **at least as up-to-date** as its own — the
exact check in `onRequestVote`:

```java
boolean candidateUpToDate =
    request.lastLogTerm() > raftLog.lastTerm()
        || (request.lastLogTerm() == raftLog.lastTerm()
            && request.lastLogIndex() >= raftLog.lastIndex());
```

This is the safety net that stops a node with stale, incomplete data from ever becoming leader and
overwriting everyone else's more-current log. A candidate becomes `LEADER` once it collects votes
from a **majority** — `(votingPeers.size() + 1) / 2 + 1`, computed identically in two places in
`RaftNode`. In a 3-node cluster that's 2 votes (including its own); a lone node in a network
partition can never reach that number.

<ZoomableDiagram
  src="/diagrams/raft-consensus.svg"
  alt="Node A's election timer fires, it becomes a candidate and requests votes; B and C grant their votes and A becomes leader; A sends heartbeats; a client proposes a write, A appends it to its own log, replicates it to B and C, and commits it once a majority has acknowledged"
  width={760}
/>

## Replication and commit

Once elected, the leader is the *only* node that accepts writes. `propose(mutation)` appends the
entry to the leader's own log first, then replicates it to every follower via `AppendEntries`, and
blocks the caller until the entry is **committed** — durably agreed by a majority. Committing isn't
"I sent it to everyone," it's a specific, computed condition in `advanceCommitIndexLocked`: collect
every peer's `matchIndex` (how far it's confirmed caught up), sort them, and take the **median** —
that's the highest index a majority has actually acknowledged. An entry only counts as committed if
it also belongs to the *leader's current term* (Raft's "Figure 8" rule) — a subtlety that exists
specifically to stop a rare case where an entry replicated by a majority under an old,
since-deposed leader could otherwise get silently overwritten by a new one.

Between real writes, the leader still sends `AppendEntries` every 50ms (`HEARTBEAT_INTERVAL`) —
empty ones double as heartbeats, so there's no separate "I'm alive" message type. A follower whose
log has drifted (say, it missed some entries while restarting) gets repaired automatically: on a
mismatch, the leader backs its `nextIndex` for that follower down one entry at a time until they
agree again, then replays forward from there.

## What breaks without check-quorum

A leader doesn't just wait to be told it lost its majority — it checks proactively.
`checkQuorumTick` runs every heartbeat interval and tracks whether the leader has heard back from a
majority of peers within the last `CHECK_QUORUM_WINDOW` (300ms). If it hasn't — because it's on the
minority side of a network partition, say — it steps itself down to `FOLLOWER` immediately, rather
than continuing to believe it's in charge.

This is worth sitting with, because Gimlé's own test suite caught a real bug here that's a
genuinely instructive distributed-systems lesson: a node bootstrapped alone (a fresh single-node
cluster, term 1) can become leader without ever winning a contested election. If that node later
joins a *second* real cluster whose term also happens to be 1 — an easy coincidence, since both
started fresh — the original code only demoted a `CANDIDATE` on receiving a same-term
`AppendEntries`, not an existing `LEADER`. Two nodes could each keep broadcasting heartbeats,
genuinely convinced they were both in charge. The fix: demote on receiving a valid `AppendEntries`
from *any* non-follower role, not just candidate. That one-line difference is the gap between "a
network hiccup that self-heals in milliseconds" and "a sustained split brain."

## Snapshotting and membership change

A log that only ever grows is a problem — replaying millions of entries to catch up a new node
would be absurd. Past `SNAPSHOT_THRESHOLD = 10,000` entries, `RaftNode` compacts: it asks the state
store for a snapshot of its current contents, persists that snapshot, and discards every log entry
it now supersedes. A follower too far behind to catch up via ordinary replication gets sent the
snapshot instead, chunked into 512KB pieces over `InstallSnapshot` RPCs.

Adding or removing a cluster member (`gimle-mimir`'s etcd-style `AddServer`/`RemoveServer`) is
deliberately **one change at a time**, never overlapping — a second membership change is rejected
outright while one is still in flight. A newly added node starts as a non-voting **learner**: it
receives replication but can't vote or campaign until it's caught up to within
`LEARNER_CATCH_UP_THRESHOLD = 10` entries of the leader. Without that staging step, a
brand-new, empty-log node could otherwise force an election it has no way to win yet still
disrupt — a real leader would see its `RequestVote` and have to at least consider stepping down
before ever checking whether the candidate's log was any good.

## See it running

`gimle-smoke-tests`' `RaftResilienceIT` kills and restarts real store replicas mid-cluster and
asserts the cluster keeps converging. `gimle-holmgang`'s `chaos-soak.feature` (via Fenrir) goes
further, randomly bouncing leaders and partitioning links against a live cluster over an extended
run, gated on full recovery after every strike. Neither is a simulation of Raft — both drive the
real `RaftNode` over real sockets on real subprocesses.
