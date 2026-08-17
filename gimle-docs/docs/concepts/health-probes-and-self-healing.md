---
sidebar_position: 3
---

import ZoomableDiagram from '@site/src/components/ZoomableDiagram';

# Health probes and self-healing

[Failure detection and gossip](./failure-detection-and-gossip.md) covered how one *machine* learns
another machine is gone. This page is one layer down: how a single module instance's own health is
checked, and what a distributed system actually does once it decides something is unhealthy —
which turns out to be a harder question than "restart it."

## Liveness vs. readiness: two different questions

It's tempting to treat "is this instance healthy?" as one yes/no signal, but Gimlé — like
Kubernetes before it — deliberately splits it into two, because the *consequence* of a `false`
answer is completely different for each:

```java
// gimle-module/src/main/java/com/gimle/module/probe/LivenessProbe.java
public interface LivenessProbe {
  boolean isAlive();
}

// gimle-module/src/main/java/com/gimle/module/probe/ReadinessProbe.java
public interface ReadinessProbe {
  boolean isReady();
}
```

**Liveness** answers "is this instance broken and should be restarted?" — a `false` here triggers
the whole escalation ladder below. **Readiness** answers "should traffic be routed to this instance
right now?" — a `false` here only flips the instance's tracked readiness state (pulling it out of
service-fabric load balancing) and never, by itself, restarts anything. The distinction matters
because a perfectly healthy instance can be legitimately *not ready* — still loading a large cache
on startup, say — and restarting it in that state would be actively counterproductive: it would
just make the cache-loading start over.

## `ProbeLoop`: bounded, on a schedule, never blocking

Both probes are called on a fixed interval — production defaults, set in `WorkerMain`:

```java
Duration probeInterval = Duration.ofSeconds(1);
Duration probeTimeout = Duration.ofSeconds(2);
int livenessFailureThreshold = 3;
```

The check itself runs with a real timeout, on the module's own bounded scheduler, never on
`ProbeLoop`'s shared ticker thread — a hung probe (one stuck in an infinite loop, say) can never
block the loop that's supposed to be checking every *other* module too:

```java
// gimle-worker/src/main/java/com/gimle/worker/ProbeLoop.java
private void runOneTick(
    String key, BoundedModuleScheduler moduleScheduler, Callable<Boolean> check,
    Duration timeout, Consumer<Boolean> onResult) {
  Future<Boolean> future = moduleScheduler.submit(check);
  boolean result;
  try {
    result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return;
  } catch (ExecutionException | TimeoutException e) {
    result = false; // a probe that throws or times out counts as unhealthy, not "unknown"
  } finally {
    future.cancel(true);
  }
  onResult.accept(result);
}
```

A single failed check doesn't restart anything either — that would make a module flap on one
GC-pause-induced blip. `WorkerRuntime` counts *consecutive* liveness failures and only acts once
`livenessFailureThreshold` (3) is reached in a row:

```java
// gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java
private void onLivenessResult(ModuleId id, boolean alive) {
  if (alive) {
    consecutiveLivenessFailures.computeIfAbsent(id, key -> new AtomicInteger()).set(0);
    return;
  }
  int failures = consecutiveLivenessFailures
      .computeIfAbsent(id, key -> new AtomicInteger())
      .incrementAndGet();
  if (failures < livenessFailureThreshold) {
    return;
  }
  consecutiveLivenessFailures.get(id).set(0);
  restartModule(id);
}
```

## The escalation ladder, and the backoff shared across every rung

Restarting isn't the end of the story either — a module that keeps crashing needs a bigger hammer,
eventually. Three tiers, matching the three failure domains from [Node
topology](../architecture/node-topology.md#three-failure-domains-three-recovery-costs):

<ZoomableDiagram
  src="/diagrams/self-healing-escalation.svg"
  alt="A failing module is disposed and re-instantiated first; if it keeps failing the worker JVM is destroyed and respawned; if that keeps failing the module is rescheduled onto another machine"
  width={640}
/>

Every tier uses the *same* backoff algorithm, deliberately — one class, `RestartTracker`, shared
between module-level restart (`gimle-worker`) and worker-level restart (`gimle-agent`), differing
only in their numeric parameters:

```java
// gimle-core/src/main/java/com/gimle/core/restart/RestartTracker.java
public boolean recordFailureAndCheckShouldRetry(Instant now) {
  if (windowStart == null || Duration.between(windowStart, now).compareTo(window) > 0) {
    windowStart = now;
    attemptsInWindow = 0;
  }
  attemptsInWindow++;
  if (attemptsInWindow > maxAttemptsPerWindow) {
    return false; // budget exhausted -- escalate
  }
  long delayMillis = (long) Math.min(
      cap.toMillis(),
      initialDelay.toMillis() * Math.pow(multiplier, attemptsInWindow - 1));
  nextAllowedAttempt = now.plusMillis(delayMillis);
  return true;
}
```

`delay = min(cap, initialDelay × multiplier^(attempt−1))` — classic exponential backoff, capped, in
a rolling time window rather than a lifetime counter (so a module that's been stable for an hour
gets a clean budget again, not permanently penalized for one bad hour last week):

| Tier | Initial delay | Multiplier | Cap | Max attempts | Window |
|---|---|---|---|---|---|
| Module restart (`gimle-worker`) | 100ms | 2.0 | 5s | 5 | 60s |
| Worker respawn (`gimle-agent`) | 1s | 2.0 | 30s | 5 | 10min |
| Machine reschedule (`HealthReconciler`) | 2s | 2.0 | 1min | 5 | 15min |

Each tier's budget is deliberately looser than the one below it — rescheduling onto a different
machine is a heavier, more disruptive operation than destroying a worker JVM, which is heavier than
disposing a `ModuleLayer`, so each rung gets more patience before escalating further.

## Where each escalation actually fires

Budget exhaustion at the module tier doesn't retry forever — it marks the module `FAILED` and stops,
handing off to the tier above:

```java
// gimle-worker/src/main/java/com/gimle/worker/WorkerRuntime.java
if (!tracker.recordFailureAndCheckShouldRetry(now)) {
  log.error("module {} exhausted its restart budget; giving up on this worker", id);
  // Escalate to FAILED rather than leaving the module ACTIVE-but-permanently-broken: this is
  // what makes the worker's alive flag flip and the machine-tier reschedule fire, completing
  // the module -> worker -> machine escalation chain instead of dead-ending here.
  controller.forceFailed(id, "restart budget exhausted");
  onModuleRestartBudgetExhausted.accept(id);
  return;
}
```

Worker-tier exhaustion works the same way one level up — `WorkerProcessSupervisor` gives up
respawning and simply stops reporting the instance in this node's heartbeat. Nothing on the agent
needs to know a machine-level reschedule is coming; it just stops claiming to own the instance, and
the control plane's own reconcilers (previous page's [level-triggered
reconciliation](./level-triggered-reconciliation.md)) notice the assignment is now unaccounted-for
on their very next tick and re-place it elsewhere — the same "no memory required between ticks"
property that lets the whole system recover cleanly from any restart, applied here to complete the
escalation ladder without agent and control plane needing to coordinate the handoff explicitly.

## What breaks without the threshold and the backoff

Skip the consecutive-failure threshold, and a single slow GC pause on an otherwise-healthy instance
restarts it — pure self-inflicted downtime. Skip the backoff cap, and a genuinely broken module
(bad config, missing dependency) gets restarted in a tight loop, burning CPU and log volume without
ever giving whoever's watching a chance to intervene before it escalates. Skip the *escalation*
itself — retry forever at the same tier — and a module whose actual problem lives one level up (the
whole worker JVM's classpath is corrupt, say) never gets the fix that would actually work: moving it
to a different worker, or a different machine entirely.
