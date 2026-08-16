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
| Saga run-report writer (`SagaCollector`, `SagaCucumberPlugin`, `SagaJUnitListener`, `SagaWriter`) | `com.gimle.holmgang.saga` |
| Saga report console template (embedded into each run's `holmgang-report.html`) | `src/test/resources/saga/saga-console.html` |
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

## Run report (Saga)

Every `-Pvalidation` run writes one **Saga** report — a versioned `holmgang-report.json` under
`target/holmgang/saga/<run-id>/` — that gathers the whole run's story in one file: scenario results
(both the Gherkin scenarios, via a Cucumber event-listener plugin, and the plain-JUnit `*IT` classes,
via a JUnit Platform listener that folds each into the same shape — so the record is complete
regardless of a test's type), the topologies booted, and, when present, the Fenrir chaos ledgers
and Surtr measurements. It's assembled in a process-wide collector across the failsafe fork and
flushed once at JVM shutdown, so it captures every part regardless of run order. The run is `FAILED`
if any scenario or Surtr gate failed, else `PASSED`. (`SurtrIT` is deliberately not repeated as a
plain scenario — it appears in its own richer `surtr` section — and the Cucumber engine's tests are
counted once, by the plugin.)

Next to the JSON, the writer also emits **`holmgang-report.html`** — the bundled Saga report
console (`src/test/resources/saga/saga-console.html`) with that run's data embedded — so, like
surefire's HTML reports, it opens directly in a browser on the run it describes: scenarios split
into Gherkin and JUnit groups, chaos ledgers with a strike timeline, Surtr measurements and gates.
Use **Compare to baseline…** in its sidebar to load an earlier run's `holmgang-report.json` and
diff the two (new failures, fixed scenarios, latency deltas). The JSON stays the data contract;
the HTML is a rendering of it. The writer adds nothing to a plain `mvn verify`; it activates only
when a validation run actually produces results.

Pass `-Dgimle.saga.endpoint=http://127.0.0.1:9096` to also ship the run's Gherkin scenarios, Fenrir
chaos ledgers, Surtr measurements, and booted topologies to a running **`gimle-saga`** report
server (`mvn gimle:saga` starts one, or reuses an already-running one, on that default port), the
same endpoint property `gimle-core`'s own `SagaTestListener` reads for shipping per-test results:

```sh
mvn -pl gimle-holmgang verify -Pvalidation -Dgimle.saga.endpoint=http://127.0.0.1:9096
```

Shipping is best-effort and additive to the local JSON/HTML report, never a replacement for it: a
Saga server that's down or unreachable never fails the run. By default the shipped attachments land
under the collector's own run ID (the same one naming its `target/holmgang/saga/<run-id>/`
directory); pass `-Dgimle.saga.runId=<id>` matching a run already open in Saga (for example one
`SagaTestListener` is streaming the same suite's own test results into) to fold the attachments
into that run instead.

## Failure forensics

A failed condition throws the investigation: the last observed cluster view, per-instance states
and versions, process alive/dead status, recent transition events, and the platform's own
lifecycle events for the deployment in question. Cluster work directories live under
`target/holmgang/` and are kept according to
`-Dgimle.holmgang.keepWorkDirs=onFailure|always|never` (default `onFailure`). The Cucumber HTML
report lands in `target/holmgang-reports/`.

## Multi-machine container validation (Utgard)

Where every scenario above validates a real cluster on one machine (loopback addressing, dynamic
port leasing), **Utgard** (`com.gimle.holmgang.utgard`) validates the same platform across real,
independently addressable machines: one Docker container per declared machine, on one shared
Docker network, each aliased to its own machine name, driving the real `hilmir` CLI inside each
container exactly as an operator would on a real fleet. It closes the specific gap
`GimleCluster`'s single-machine topologies cannot reach: genuine cross-host readiness waiting
(`hilmir up`'s remote-prerequisite block actually blocking, not racing), whole-machine loss, network
partition, and mTLS addressed by real DNS hostnames instead of `localhost`.

Plain JUnit `*IT` classes, not Gherkin -- container orchestration in step definitions would be
noise, the same reasoning `SurtrIT` already follows. Four scenarios:

- `UtgardDistributedBootIT` -- three machines; `hilmir up` is issued in a deliberately
  out-of-dependency-order sequence to prove the remote-prerequisite wait genuinely blocks and later
  proceeds, then a real deployment reaches `ACTIVE` through the control plane's own HTTP API.
- `UtgardMachineLossIT` -- hard-kills a whole container and asserts the platform reschedules the
  instance it hosted onto a surviving machine, then demonstrates rejoin via a fresh `hilmir up
  --machine` against the restarted container.
- `UtgardPartitionIT` -- disconnects a machine from the shared Docker network (its own process stays
  alive throughout, unlike a kill) and asserts the cluster both reschedules around it and converges
  back to one instance once the network is reconnected.
- `UtgardMtlsIT` -- an mTLS topology addressed by the containers' own real network aliases, proving
  the certificate-bootstrap flow works over a genuine DNS-named network rather than the
  `localhost`-only mTLS every other topology in this module is limited to.

Requires Docker with normal container-registry egress. Every `*IT` class's own `@BeforeAll` starts
its container fleet inside a try/catch and converts any failure -- no reachable daemon, or a blocked
image pull -- into a JUnit assumption failure, so the suite skips cleanly rather than hanging or
failing hard on a machine without Docker or without registry access, the same style `SurtrIT` uses
for its own unmet precondition.

```sh
mvn -pl gimle-holmgang verify -Pvalidation -Dit.test=Utgard*
```

The pure YAML/exec-result helpers behind Utgard (`UtgardTopologies`, `UtgardExec`, `UtgardPoll`)
have their own fast unit tests that need no Docker at all, so they run under the module's default
`mvn verify` alongside every other unit test here.

## Docker Compose manual validation

`compose/` holds two hand-run Docker Compose files for manually eyeballing the two ways a real
`gimle-platform` archive can be deployed, distinct from Utgard above: Utgard is an automated JUnit
suite asserting specific platform behaviors from Java, while these compose files are for a person to
`docker compose up` and poke at with their own eyes and their own `curl`/`hilmir` commands. Both
files drive the exact same real `hilmir up`/`hilmir status` verbs an operator would run by hand
(`compose/entrypoint.sh`) against a real topology document (`compose/topology-*.yaml`, in the actual
`gimle-hilmir` `TopologyParser` schema) -- neither file hand-crafts `StoreMain`/`FafnirMain`/etc.
command lines itself.

- `docker-compose.full-jre.yml` -- the baseline: every service runs `eclipse-temurin:25-jre`, and
  `topology-full-jre.yaml` sets `runtime.useBundledJre: false`. Matches a `gimle-platform` archive
  built the default way (no `jre/<component>/` directory in it).
- `docker-compose.bundled-jre.yml` -- store/muninn/andvari/fafnir/controlplane run on a bare
  `debian:bookworm-slim` base with no JRE of their own at all, launching entirely off the
  `jre/<component>/bin/java` that `gimle-dist`'s `dist-with-jre` profile bundled into the mounted
  archive (`topology-bundled-jre.yaml` sets `runtime.useBundledJre: true`). `agent` keeps a real
  `eclipse-temurin:25-jre` image, matching `LaunchPlanner.planAgents`' structural exclusion from
  `useBundledJre` -- agent spawns arbitrary vessel workloads and the worker JVMs it supervises host
  arbitrary Gimlé modules, neither of which jlink's own derivation ever saw.

Prerequisites -- build the archive, nothing else:

```sh
# full-jre scenario
mvn -pl gimle-dist -am install
docker compose -f compose/docker-compose.full-jre.yml up

# bundled-jre scenario -- note the extra profile
mvn -pl gimle-dist -am install -P dist-with-jre
docker compose -f compose/docker-compose.bundled-jre.yml up
```

Each file's own `unpack` service extracts whichever `gimle-platform-<version>.tar.gz` it finds under
`gimle-dist/target/` into a shared volume before any other service starts (`condition:
service_completed_successfully`), so there's no tarball to unpack or path to export by hand --
`gimle-dist/target/` only ever holds one built archive at a time, so build the variant the file you're
about to run actually needs first. Building out of a different checkout (or a pre-built archive
elsewhere) is still possible: `GIMLE_DIST_TARGET` overrides the mounted directory, e.g.
`GIMLE_DIST_TARGET=/path/to/gimle-dist/target docker compose -f compose/docker-compose.full-jre.yml up`.

The control plane's HTTP API is published at `localhost:8080` in both files. Each service polls its
own `hilmir status` every 10s and exits non-zero the moment any process it hosts reports
`alive=false`, so `docker compose ps`/a non-zero exit is itself a signal something died -- watch
`docker compose logs -f` for which one and why. Tear down with `docker compose down -v` (the `-v`
matters: each service keeps its own `dataRoot` in a named volume, so a stale Fafnir key or Raft log
survives a plain `down` otherwise).

Not run automatically anywhere (no CI, not part of Utgard's own Testcontainers fleet) -- these are
for a person to run by hand. Utgard itself deliberately stays on its own programmatic
`UtgardMachines` container provisioning rather than these files: every Utgard scenario needs
per-test dynamic control a static compose file can't give it (killing/restarting one specific
container mid-scenario, disconnecting/reconnecting a machine from the network, mounting the *test
JVM's own* reactor build classpath rather than a pre-built archive) -- reusing these compose files
would mean re-deriving that same dynamic lifecycle control on top of Compose instead of Testcontainers'
own Java API for it, for no reduction in real complexity.

## Adding a scenario

1. Pick (or add) a topology under `topologies/` — composition only, no ports.
2. Write the feature under `features/`, tagged `@holmgang`, a capability tag, and `@destructive`
   if it kills processes, injects faults, or mutates membership.
3. Reuse the step vocabulary in `com.gimle.holmgang.steps`; a new step should be one or two calls
   into `ClusterApi`/Heimdall/Loki, so the Gherkin stays truthful about what runs.
4. No polling in steps: wait through Heimdall conditions (`when()...await`), invariants
   (`holdInvariant`), or probes — never a sleep-and-check loop.
