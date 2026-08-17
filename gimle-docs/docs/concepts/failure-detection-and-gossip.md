---
sidebar_position: 2
---

import ZoomableDiagram from '@site/src/components/ZoomableDiagram';
import DocVideo from '@site/src/components/DocVideo';

# Failure detection and gossip

Consensus (previous page) answers "how does a cluster agree on the truth." This page answers a
different question: "how does a node even know which other nodes are still alive?" It sounds
simple until you try to do it at scale.

<DocVideo
  src="/video/swim-gossip-explainer.webm"
  poster="/video/swim-gossip-explainer-poster.png"
  captions="/video/swim-gossip-explainer.vtt"
  caption="Failure detection: SWIM gossip in gimle-fabric, explained (1m53s)"
/>

<details>
<summary>Video transcript</summary>

How does every machine in a growing cluster know which other machines are still alive, without the
amount of network chatter exploding as the cluster grows? This is SWIM, the gossip protocol behind
failure detection in Gimlé's service fabric.

Every node runs on a steady one-second cycle. Each tick, it picks one other member and pings it
directly. If an acknowledgement comes back in time, that member is confirmed alive, and nothing
else happens.

But this time, Node B doesn't answer within its ping timeout. That alone doesn't mean Node B is
down — it could just as easily be a bad network path between only these two nodes.

So instead of giving up, Node A asks a handful of other members — here Node C and Node D — to
relay-probe Node B on its behalf. Each of them sends its own direct ping to B and reports back what
it finds.

Only when the direct probe and every indirect relay all fail does Node A mark Node B as
suspect — not dead yet, just suspect.

A suspected node gets a real chance to prove it's still there. If Node B were actually fine, just
briefly overloaded, it could refute the suspicion at any point during this window. Only once the
suspicion timeout — about three seconds — elapses with no refutation does Node A finally mark it
dead.

That new status doesn't need a broadcast. It piggybacks on whatever ordinary gossip traffic Node A
was already sending, spreading through the cluster the way a rumor spreads through casual
conversation. And every 30 seconds, a full anti-entropy sync acts as a backstop, in case any node
missed the news.

That's direct pings, indirect probes, suspicion, and gossip dissemination — the core of SWIM. The
rest of this page covers the local health multiplier that adapts these timeouts under load, and why
this same mechanism is what actually triggers Gimlé's self-healing, not just a status dashboard.

</details>

## The problem: you can't just ask everyone

The naive approach — one node health-checks every other node directly, all the time — is an
`O(n²)` amount of network traffic as the cluster grows, and it has a worse flaw: a health check can
fail because the *target* is down, or because the *network path between just those two nodes* is
briefly bad, or because the checker itself is overloaded and running late. A single ping/no-ping
result can't tell those apart, and a cluster acting on a false "it's dead!" (evicting or
rescheduling something that was actually fine) is often worse than acting a little slowly on a
real failure.

`gimle-fabric`'s `GossipMember` implements **SWIM** (Scalable Weakly-consistent Infection-style
Membership), a protocol designed around exactly those two constraints: bounded traffic regardless
of cluster size, and a multi-step process before declaring anyone dead, so a single bad signal
never gets the final say alone.

## Direct ping, then ask around

Every node runs on a fixed cadence — `protocolPeriod = 1s` by default. Each tick, it picks one
other member (round-robining through a shuffled list, not pure random sampling, which guarantees
everyone gets probed within a bounded number of cycles) and pings it directly. If it gets an ack
within `pingTimeout` (500ms, `GossipConfig`'s default), that member is confirmed `ALIVE` and
nothing else happens.

If the ack doesn't arrive in time, the *prober* doesn't jump straight to declaring the target dead
— a single dropped packet between exactly these two nodes doesn't mean the target is actually
unreachable from everyone. Instead it asks a handful of **other** members to relay-probe the target
on its behalf — `indirectFanout = 3` by default, capped by how many members actually exist. Each of
those relays sends its own direct ping to the target and, if *it* gets an ack, reports back
`"reachable, just not directly from you."` Only if the direct probe **and every indirect relay**
fail before an overall deadline does the prober mark the target `SUSPECT` rather than `ALIVE`.

<ZoomableDiagram
  src="/diagrams/swim-gossip.svg"
  alt="Node A pings Node B directly; when it gets no ack, A asks C and D to relay-probe B on its behalf; when that also fails, A marks B SUSPECT, then DEAD once the suspicion timeout elapses unrefuted, and disseminates the DEAD status to the rest of the cluster via piggybacked gossip"
  width={760}
/>

## Suspicion before death — and a chance to refute

`SUSPECT` isn't final either. A suspected member stays in that state for `suspicionTimeout` (3
seconds by default — "about three protocol periods") before being marked `DEAD`. Critically, a
member can **refute** a suspicion of itself at any point during that window: every gossip message
carries an *incarnation number*, and if a node ever learns that others believe it's suspect or
dead, it bumps its own incarnation and re-announces itself `ALIVE` — which, because a higher
incarnation always wins when nodes merge conflicting gossip state, cancels the suspicion cluster-wide.
This is what turns "briefly slow to respond" into a non-event instead of a false eviction, while
still letting an actually-dead node get declared dead within a bounded, known amount of time.

There's also a self-tuning wrinkle: each node tracks a *local health multiplier* (a simplified
version of the Lifeguard extension to SWIM) that scales up its own `pingTimeout` and
`suspicionTimeout` whenever its own probes have recently been timing out or it's been suspected by
others — a node on an overloaded machine or a congested link gives itself more slack rather than
flooding the cluster with false suspicions, then decays that multiplier back down once its probes
start succeeding again.

## Spreading the news

Once a status changes, it needs to reach the rest of the cluster — and SWIM does this without any
central broadcaster. Every ordinary gossip message (a ping, an ack, anything) **piggybacks** a
small batch of recently-changed member states (`piggybackCount = 6` entries by default) onto
whatever it was already sending. Over enough rounds, a change gets to everyone "for free," riding
along on traffic the cluster was sending anyway — this is the "infection-style" half of SWIM's
name, spreading the way a rumor spreads through casual conversation rather than an announcement.

Piggybacking alone can occasionally miss a node that's been quiet for a while, so there's a
backstop: every `antiEntropyInterval` (30 seconds by default), a node syncs its *entire* membership
table with one random peer, guaranteeing eventual convergence even if every piggyback opportunity
was somehow missed. A `DEAD` member is eventually forgotten entirely (`deadMemberReapAfter`, 60
seconds) rather than gossiped forever.

## Why this feeds self-healing, not just monitoring

This isn't a passive dashboard — SWIM's output *is* the input to Gimlé's tiered self-healing (see
[Node topology § three failure domains](../architecture/node-topology.md#three-failure-domains-three-recovery-costs)).
A node agent marked `DEAD` by gossip is what triggers rescheduling its instances elsewhere. That's
exactly why the suspicion window and refutation mechanism matter as much as they do: an eviction is
expensive and disruptive to reverse, so the protocol is deliberately biased toward "give it a
chance to prove it's still there" before taking that action — the same reasoning
`QuotaReconciler` never auto-evicts (see [Multi-tenancy](../architecture/multi-tenancy.md)), applied
one layer lower in the stack.
