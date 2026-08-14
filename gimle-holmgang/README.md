# gimle-holmgang

Functional validation of a real Gimlé cluster: boots genuine multi-process clusters from
declarative topologies and runs Gherkin scenarios against them. Named for the *hólmganga*, the
formalized duel by which a claim was proven — which is what this module does to the platform's
claims.

Not part of the default `mvn verify` and never run in CI. Invocation is always manual.

## Running

```sh
mvn install                                   # once: builds the platform + example-module jars
mvn -pl gimle-holmgang verify -Pvalidation    # the full validation suite
```

Filter scenarios with standard Cucumber tag expressions:

```sh
mvn -pl gimle-holmgang verify -Pvalidation -Dcucumber.filter.tags="@rolling-update"
mvn -pl gimle-holmgang verify -Pvalidation -Dcucumber.filter.tags="@mtls or @partition"
mvn -pl gimle-holmgang verify -Pvalidation -Dcucumber.filter.tags="not @destructive"
```

Requires JDK 25 on `PATH`/`JAVA_HOME`. Every cluster process, Gatling load generator, PKI
bootstrap, and CLI invocation is spawned against the test JVM's own classpath; ports are leased
dynamically, so suites and manually started clusters can coexist on one machine. (One exception:
the deployed `greeter-load-generator` example module listens on its own fixed port 19077, so only
one load scenario can run on a machine at a time.)

## Layout

| Piece | Where |
|---|---|
| Topology documents | `src/test/resources/topologies/*.yaml` |
| Gherkin scenarios | `src/test/resources/features/*.feature` |
| Topology model + YAML parser + Java DSL | `com.gimle.holmgang.topology` |
| Cluster bootstrap runtime (`GimleCluster`, `ClusterApi`) | `com.gimle.holmgang.cluster` |
| Heimdall condition harness (views, log follow, probes, invariants) | `com.gimle.holmgang.heimdall` |
| Loki network-fault injection (proxied topologies) | `com.gimle.holmgang.loki` |
| Fenrir randomized chaos scheduler (`FenrirPlan`, `Fenrir`, `ChaosLedger`) | `com.gimle.holmgang.fenrir` |
| Surtr scale/churn workload runner (`SurtrWorkload`, `SurtrRunner`, `SurtrReport`) | `com.gimle.holmgang.surtr` |
| Workload documents | `src/test/resources/workloads/*.yaml` |
| Gatling load (`LoadGenerator`, simulation) | `com.gimle.holmgang.load` |
| Recorded write workloads | `com.gimle.holmgang.workload` |
| Step definitions, hooks, cluster pool | `com.gimle.holmgang.steps` |
| `@Holmgang` JUnit extension (plain-Java scenarios) | `com.gimle.holmgang.junit` |

## Topologies

A topology declares composition, never ports: replica counts per process kind, node count and
labels, `transport: plaintext | mtls`, optional `faults.proxied` (Loki interposition), per-role
JVM flags, and seed accounts/tenants. `mtls` topologies generate their own CA and per-role
certificates at boot and put agents through the real CSR bootstrap flow; `faults.proxied`
topologies hand each control-plane replica its own interposed store endpoints so a partition can
target one replica precisely. The two are mutually exclusive by design.

Clusters are pooled per topology across non-destructive scenarios (each hands the cluster back as
found — the after-scenario hook enforces it). Scenarios tagged `@destructive` get a fresh cluster
to themselves.

## Chaos soaks (Fenrir)

Where a scenario injects one hand-aimed fault and asserts it once, **Fenrir** runs a randomized
soak: over a window, it repeatedly strikes a healthy cluster from a weighted palette — worker kills
(the platform supervisor must respawn them), store/leader/control-plane bounces (kill, dwell,
harness `restart()`, then a rejoin gate), and link cuts on proxied topologies — and, by default,
gates every next strike on full recovery from the last, so a failure names exactly one fault on a
provably healthy cluster. One fault is in flight at a time; a quorum guard and a control-plane
floor skip (never silently drop) any strike that would breach them.

The strike sequence is seeded and printed into the `ChaosLedger`, so a failing run replays with
`-Dgimle.holmgang.chaosSeed=<seed>`. A soak is expressed in Gherkin — `When Fenrir is unleashed for
N seconds striking every M seconds` — and always runs on a `@destructive` scenario, since repeated
kills leave no clean state for a pooled cluster. See `features/chaos-soak.feature`.

## Scale & performance burns (Surtr)

Where the scenario suite validates behaviour and Fenrir injects faults, **Surtr** answers the scale
question: what the control plane does when asked for many of everything. A workload YAML declares a
topology, jobs (`create` at a controlled QPS/burst, `churn` that redeploys or recreates a fraction
per cycle, `delete`), the measurements to collect, and the gates that fail the run. One built
reference module is deployed under N templated names — the pause-image trick — so object count
scales without building N artifacts; `-Dgimle.surtr.scale=N` multiplies a create job's iterations.

Startup latency is measured from the platform's **own lifecycle event log** (per-transition
timestamps the platform recorded), not from harness polling, which would drown millisecond Tier-1
deploys in sampling noise; API latency is client-observed. Every run writes a timestamped
`target/holmgang/surtr/<workload>-<timestamp>/` directory — `summary.json` with an environment
fingerprint plus per-measurement NDJSON — so two runs diff with `jq`. `maxFailedSubmissions` and
`maxNeverActive` gate by default; latency gates are opt-in tripwires.

```sh
mvn -pl gimle-holmgang verify -Pvalidation -Dgimle.surtr.workload=module-density
mvn -pl gimle-holmgang verify -Pvalidation -Dgimle.surtr.workload=module-density -Dgimle.surtr.scale=10
```

Without `-Dgimle.surtr.workload`, `SurtrIT` skips via a JUnit assumption, so ordinary validation
runs are untouched. Bundled workloads live in `workloads/`; the property also accepts a filesystem
path to a custom one.

## Failure forensics

A failed condition throws the investigation: the last observed cluster view, per-instance states
and versions, process alive/dead status, recent transition events, and the platform's own
lifecycle events for the deployment in question. Cluster work directories live under
`target/holmgang/` and are kept according to
`-Dgimle.holmgang.keepWorkDirs=onFailure|always|never` (default `onFailure`). The Cucumber HTML
report lands in `target/holmgang-reports/`.

## Adding a scenario

1. Pick (or add) a topology under `topologies/` — composition only, no ports.
2. Write the feature under `features/`, tagged `@holmgang`, a capability tag, and `@destructive`
   if it kills processes, injects faults, or mutates membership.
3. Reuse the step vocabulary in `com.gimle.holmgang.steps`; a new step should be one or two calls
   into `ClusterApi`/Heimdall/Loki, so the Gherkin stays truthful about what runs.
4. No polling in steps: wait through Heimdall conditions (`when()...await`), invariants
   (`holdInvariant`), or probes — never a sleep-and-check loop.
