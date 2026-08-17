---
sidebar_position: 4
---

import ZoomableDiagram from '@site/src/components/ZoomableDiagram';

# Level-triggered reconciliation

This is arguably the single most important correctness idea in Gimlé's control plane — CLAUDE.md
calls it "the hardest-to-test and most important correctness property in the codebase." It's also
one of the least obvious ideas if you haven't met it before, so it's worth slowing down on.

## Edge-triggered vs. level-triggered, the electronics analogy Kubernetes borrowed

The terms come from digital circuits. An **edge-triggered** system reacts to a *change* — "the
signal just went from low to high, do something." A **level-triggered** system reacts to a
*current state* — "the signal is high right now, keep doing the thing it implies." The difference
sounds academic until you ask what happens when an edge-triggered system *misses* a transition —
the process handling it crashed, or the message got dropped. It has no idea anything happened at
all, and nothing about its own state says so. A level-triggered system doesn't have this problem by
construction: it never asked "what changed," only "what's true right now" — so it doesn't matter
whether it's been running continuously or just came back from the dead with zero memory of anything.

## What Gimlé's reconcilers actually do, every single tick

There's a small surprise in how this is implemented: **there's no shared `Reconciler` interface or
base class.** `ReplicaCountReconciler`, `DeploymentReconciler`, `HealthReconciler`, and six others
are all independent, unrelated `final` classes. What they share isn't inheritance — it's a
discipline, applied identically in each one, captured in `DeploymentReconciler`'s own class
javadoc:

> "Level-triggered: every tick re-derives the full set of assignments a from-scratch run would
> produce from the current snapshot, rather than reacting to what changed since last tick —
> deleting a deployment, scaling it, or a fresh empty store all converge through the exact same
> code path."

`ReplicaCountReconciler.reconcileOnce()` is the concrete shape of that discipline: it reads the
*entire* current set of assignments fresh from the store on every call, with no in-memory tracking
of what it saw last time:

```java
// gimle-controlplane/src/main/java/com/gimle/controlplane/reconcile/ReplicaCountReconciler.java
public void reconcileOnce() {
  Instant now = clock.instant();
  Set<String> currentKeys = new HashSet<>();
  for (InstanceAssignment assignment : store.listAssignments()) {
    currentKeys.add(key(assignment.deploymentName(), assignment.instanceIndex()));
    try {
      reconcileAssignment(assignment, now);
    } catch (RuntimeException e) {
      // One assignment's failure must never abort the rest of this tick's assignments -- the
      // next tick retries this one from the same full snapshot.
      log.warn("replica count reconcile of {} instance {} failed: {}",
          assignment.deploymentName(), assignment.instanceIndex(), e.getMessage(), e);
    }
  }
  // ...
}
```

<ZoomableDiagram
  src="/diagrams/level-triggered-reconciliation.svg"
  alt="A reconciler tick reads desired and observed state fresh every time; in the normal case the diff is zero and nothing happens; even if the reconciler process just restarted with no memory of anything, the same tick still notices a missing replica from the current snapshot alone and schedules a replacement"
  width={760}
/>

## Ticking: fixed schedule, leader-gated, isolated per reconciler

Reconcilers don't run in response to events at all — `ControlPlaneMain` ticks all nine of them on a
plain fixed-rate timer, every 2 seconds (`RECONCILE_INTERVAL`), gated behind a store-backed
reconciler-leader lease so only one control-plane replica is actively reconciling at a time. Each
reconciler's `reconcileOnce()` is wrapped individually, so one reconciler throwing never blocks the
other eight in the same tick:

```java
// gimle-controlplane/src/main/java/com/gimle/controlplane/ControlPlaneMain.java
private void runOne(String name, Runnable reconcile) {
  try {
    reconcile.run();
  } catch (RuntimeException e) {
    log.warn("{} reconcile tick failed: {}", name, e.getMessage(), e);
  }
}
```

## Proof, not just a claim: reconstructing a reconciler mid-flight

The strongest test of "does this really not need memory between ticks" is to *literally* throw the
memory away and check the outcome is unchanged. `ReplicaCountReconcilerTest` does exactly that —
starts a grace-period timer, closes the store, reopens it, builds a **brand-new**
`ReplicaCountReconciler` instance against it (simulating a reconciler-leader failover), and checks
the already-elapsing timer resumes correctly rather than restarting:

```java
@Test
void grace_period_state_survives_a_reconciler_reconstruction_against_the_same_store() {
  // Simulates a reconciler-leader failover: a fresh ReplicaCountReconciler instance, backed by
  // the same on-disk store, must resume the already-elapsing grace-period timer rather than
  // restarting it, which would delay a legitimate reschedule.
  original.reconcileOnce(); // starts the grace period
  StateStore reopened = new StateStore(dir);
  ReplicaCountReconciler resumed =
      new ReplicaCountReconciler(reopened, NODE_DARK_TIMEOUT, GRACE_PERIOD, clock);

  clock.advance(GRACE_PERIOD.plusSeconds(1)); // past the original deadline, not a fresh one
  resumed.reconcileOnce();

  assertFalse(hasAssignment(reopened, "orders-service", 0),
      "the resumed reconciler should have completed the grace period it didn't start itself");
}
```

This works because the one piece of state a naive implementation would be tempted to keep in a
local `HashMap` — how long an instance has already been missing — is instead persisted through the
store as `ReconcilerInstanceState`, not held in memory at all.

Every reconciler in the codebase carries at least one test in this same shape, checking convergence
from a deliberately messy, arbitrary starting snapshot — not just the happy path of "state changed,
did we react":

```java
@Test
void an_arbitrary_starting_snapshot_converges_the_same_as_a_fresh_reconcile() {
  // Mixed bag: index 0 already validly assigned, index 2 stale (>= the current replica count),
  // plus an assignment for a deployment that no longer exists at all -- a from-scratch run
  // starting from this exact snapshot has no history to consult, only what's here right now.
  store.putAssignment(new InstanceAssignment("orders-service", 0, "node-a"));
  store.putAssignment(new InstanceAssignment("orders-service", 2, "node-a"));
  store.putAssignment(new InstanceAssignment("ghost-deployment", 0, "node-a"));

  new DeploymentReconciler(store, scheduler).reconcileOnce();

  assertEquals(Set.of(0, 1), /* actual replica indices now */ );
  assertTrue(store.listAssignmentsFor("ghost-deployment").isEmpty());
}
```

## What breaks without this

Imagine the edge-triggered alternative: a reconciler that only acts on "deployment created" /
"instance crashed" events pushed to it. Now the control-plane replica handling that stream crashes
between receiving "instance crashed" and finishing the reschedule. On restart, that event is gone —
nothing pushes it again, because nothing knows it was ever half-handled. The missing instance simply
stays missing forever, silently, until a human notices. Kubernetes hit this exact class of bug
often enough in its own history that `client-go` informers gained a periodic *resync* specifically
to paper over missed watch events — which is really just level-triggering bolted on after the fact.
Gimlé's reconcilers don't need a resync mechanism because they never trusted the event stream as
their source of truth to begin with.
