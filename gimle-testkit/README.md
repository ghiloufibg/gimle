# Gimle Testkit

`gimle-testkit` is a plain `jar` reactor module holding reusable real-cluster test infrastructure
shared across this repo's test suites: an event-driven cluster-condition watcher (Heimdall), a
spin-polling wait primitive (`Await`), and kernel-assigned loopback port reservation (`PortLease`).
It depends only on `gimle-core` and has no production role of its own — nothing outside a `test`
scope ever depends on it. Heimdall was originally built inside `gimle-holmgang` and extracted here
once `gimle-smoke-tests` needed the same machinery, so both modules' real-subprocess-cluster test
suites share one implementation instead of two copies drifting apart.

Consumers today (all `test`-scoped): `gimle-agent`, `gimle-worker`, `gimle-mimir`,
`gimle-controlplane`, `gimle-observability`, `gimle-fabric`, `gimle-holmgang`, and
`gimle-smoke-tests`.

## `Await`

Spin-polls a `BooleanSupplier` condition instead of a fixed sleep, so a real-cluster test fails fast
the moment the condition becomes true rather than always waiting out a fixed delay:

```java
Await.until(() -> node.isRegistered(), Duration.ofSeconds(10));
Await.until(() -> deployment.isActive(), Duration.ofSeconds(30), "deployment reaches ACTIVE");
```

Default poll interval is 10ms; an optional description enriches the timeout failure message.

## `PortLease`

Reserves a cluster's whole port budget up front by binding kernel-assigned loopback ports and
holding the listening sockets open, so no real-cluster fixture ever hardcodes a port and two
fixtures can coexist on one machine:

```java
try (PortLease ports = PortLease.reserve(5)) {
  int raftPort = ports.port(0);
  // ... release just before spawning the process that will bind it, then launch
}
```

A leased port is released (its socket closed) immediately before the process meant to bind it is
spawned. The close-to-bind window is a real but tiny race; kernels hand out fresh ephemeral ports
for new binds rather than immediately reusing one just closed, so collisions are vanishingly rare
rather than merely unlikely-by-convention.

## `com.gimle.testkit.heimdall` — the cluster watcher

`Heimdall` is the watcher every condition and invariant hangs off. Three event sources feed it:

- every registered process's own exit (`Process.onExit` — an unexpected death fails all pending
  conditions immediately with the real cause, instead of letting them time out),
- the platform's own log follow streams (used by `LogConditions`),
- one shared poller — exactly one per cluster, not one per condition — that snapshots
  `GET /deployments` + `GET /nodes` into a `ClusterView` every 250ms, rotating across
  control-plane replicas so every replica's view of shared state is continuously exercised.

Conditions are predicates over `ClusterView` snapshots: N concurrent conditions cost the same one
poller, and each completes on the first view that satisfies it. A condition that times out or fails
outright throws a `HeimdallConditionError` carrying a forensic report (`ForensicReport`) of the last
observed cluster state, which processes were still alive, recent events, and where the process logs
are — built for a human debugging a failed CI run, not just a stack trace.

Typical usage:

```java
Heimdall cluster = new Heimdall(controlPlaneBaseUrls, processes, workDir);

cluster.when().deployment("greeter-provider").isActive();
cluster.when().node("smoke-node-1").isRegistered();
cluster.when(1).deployment("greeter-provider").isActive(); // pinned to replica 1 specifically

try (InvariantGuard guard =
    cluster.hold(Invariants.deployment("greeter-provider").staysActive(1))) {
  // ... drive a rollout; guard.close() throws the violation's own forensic report
  // the instant the invariant is first broken, not after the scenario finishes
}
```

- `HeimdallScope` — the fluent entry point conditions are built from. `cluster.when()` accepts a
  satisfying view from any control-plane replica; `cluster.when(N)` pins to replica `N` only — the
  deterministic way to assert that state written through one replica is observable through another.
- `DeploymentConditions` / `NodeConditions` / `LogConditions` — conditions scoped to one
  deployment/node/instance log respectively.
- `Invariants` / `InvariantGuard` — held continuously over every `ClusterView` observed between
  creation and `close()`, for "this must hold throughout a scenario" assertions (e.g. availability
  never drops below N replicas during a rolling update). The first violating view is captured with
  its own forensic report at the moment of violation, not reconstructed after the fact.
- `HeimdallProcess` — the minimal shape Heimdall needs of a spawned cluster process (`role()`,
  `id()`, `isAlive()`, `exitWasExpected()`, an exit callback) — deliberately not the richer
  spawn/restart/kill/log-access interface a real cluster fixture builds for itself, so this module
  never depends on one.
- `ClusterView` — one immutable snapshot of control-plane-visible state.

## `TestkitException`

The harness's own unchecked failure type (a lease that couldn't be reserved, a condition that failed
outright, a watcher interrupted mid-wait) — deliberately not one of `gimle-core`'s platform exception
kinds, since those name failures of the platform itself, while this names a failure of the test
harness around it.
