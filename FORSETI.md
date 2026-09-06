# Forseti — the pre-release QA doctrine

Forseti is the procedure Gimlé runs before every release: a fleet of AI agents, each playing a
real user of the distribution, tests the platform black-box across purpose-built clusters running in
parallel, and one orchestrating agent — the Tech QA lead — merges what they found into a single
deduplicated findings report with reproduction steps. Named for the god who presides at Glitnir
and settles every dispute brought before him: the lead's whole job is arbitration and verdict.

This file is the doctrine — **what** to test, **who** tests it, **against what**, **how findings are
filed and merged** — and it is stable across releases. It contains no findings. Each run of it
produces a separate, dated findings artifact (see [Reporting](#7-reporting-and-deduplication)) that
is never committed; the only thing a run writes back here is one row in
[Release history](#10-release-history).

Two files are the structured source of truth behind this one, and the generated sections below
(marked `<!-- forseti:generated … -->`) are rendered from them by
`python3 scripts/generate_forseti_docs.py` — never hand-edit a generated block:

- `forseti.json` — the environments, personas, scenario catalog, and the classification of every
  requirement as user-observable, internal, or out of scope.
- `requirements-matrix.json` / `rtm.json` — the platform's requirements (`GIMLE-NNN`) and their
  existing unit-test and Holmgang citations, which every non-fleet row falls back to.

## 1. Posture

**Every scenario agent is a tester, not a code-reader.** It is handed a persona, an environment, and
a list of things a real person in that role would try to do with the software — the CLI, the web
consoles, the shipped example applications, the Maven plugin and `hilmir` tooling, and the docs a
new user actually reads (`gimle-console/LOCAL_DEV.md`, `gimle-dist/README.md`, the Midgard archive's
own `README.md`, the documentation site's tutorials). It is **never permitted to open Gimlé's own Java
sources to decide whether something is a bug**. It decides the way a customer would: from documented
behaviour, from what the CLI and console tell it, and from whether the system does what it just said
it would do.

**Every finding cites something actually observed** — a real command and its real output, a real
log line, a real HTTP response, a screenshot from a real browser session. No inferred, simulated,
or "would probably" results. A scenario an agent could not execute is reported as not executed, with
the reason, never as passed.

**One role never tests.** The Lead reads what the other agents produced, fingerprints and merges
duplicates, adjudicates disagreement between two agents about severity or whether something is a
bug at all, and writes the report. Disagreement is signal, not noise — it is recorded, not resolved
away.

**Scenarios are objective + oracle, not click scripts.** Each names what the tester is trying to
accomplish and what "correct" looks like; the agent chooses the exact commands and clicks. This is
what makes the same catalog re-runnable across releases whose CLI flags and console layouts drift.

**Agents collide on purpose.** Within one environment a wave of personas runs concurrently against
the *same* live cluster once it carries baseline state, so that two agents' actions can interfere in
ways neither would trigger alone. Across environments, clusters are fully independent.

## 2. Environments

Four environments, each built the way a real operator or developer would build it — never a test
fixture's shortcut. A scenario group is tagged with the environment(s) it needs and only that group's
agents touch that cluster.

<!-- forseti:generated environments -->
| Env | Shape | Built via | What only this environment can test |
|---|---|---|---|
| **Forge** (G) | A developer workstation: the source checkout, JDK 25, Maven, Bun, Docker where available. No standing cluster of its own — a scenario that needs one brings up a throwaway via `mvn gimle:bootstrap` and tears it down after. | `git clone` + `mvn install -DskipTests`, exactly as `gimle-console/LOCAL_DEV.md` describes. | Everything a module author touches before anything is deployed: manifest authoring, `hilmir doctor`/`init`, every `mvn gimle:*` goal, Saga, the documentation site's own tutorials followed literally. |
| **Midgard** (A) | One machine: one Mimir store, one control plane, Fafnir, Muninn, Andvari, one node agent. Plaintext, unauthenticated. Pre-seeded with the bundled greeter examples. | The `gimle-midgard-<version>.tar.gz` archive's own `docker compose up -d`. Where the run's sandbox has no Docker daemon, `mvn gimle:bootstrap` stands up the same real processes uncontainerized — same surface, and the coverage table notes which was used. | Everyday CRUD, every console screen, artifact push/pull, example-application deploys, logs/metrics/traces — the bulk of what an ordinary user ever does. |
| **Fleet** (B) | Five machines: 3 Mimir replicas, 2 control-plane replicas, Fafnir, Muninn, 2 Andvari replicas, 4 node agents (one labelled `edge`, one tainted). Plaintext. Optionally one extra machine with a real `sshd`. | Unpacked from the `gimle-platform` archive; `hilmir validate`/`plan`/`up` against a hand-written `topology.yaml`, one `hilmir up --machine` per machine — per the archive's own docs. Realised as one Docker container per machine on a shared network (the shape `gimle-holmgang/compose/docker-compose.naked-infra.yml` and Utgard already use), or as real hosts where available. | HA and failover, live store membership change, cross-node scheduling and anti-affinity, rolling updates, autoscaling, machine-level self-healing, cordon/taint, Bifrost/Skald across nodes, Andvari replication, upgrade-cluster, Ragnarok. |
| **Vault** (C) | Fleet's shape (it can be smaller — 3 machines is enough), but `transport: mtls` throughout: `hilmir pki init` mints the CA and every process leaf, agents join through the real token + CSR bootstrap. | Same as Fleet plus `hilmir pki init`, per the hilmir reference. Never an IP-literal host (the validator's own `MTLS_IP_LITERAL_HOST` rule) — real hostnames, which the container-per-machine realisation gives for free. | Certificate issuance, approval, revocation; RBAC under real authentication (not the plaintext anonymous-session carve-out); tenant isolation; the defense-in-depth re-checks in Fafnir, Muninn and Andvari; gateway TLS and SNI; the mTLS default-RBAC seams between node agents, the control plane and the registry. |
<!-- /forseti:generated -->

**Graceful degradation.** A run's sandbox may lack a Docker daemon (Midgard then comes up via
`mvn gimle:bootstrap`), or the ability to run five containers (Fleet shrinks to three machines and
loses the SSH machine), or any multi-machine capability at all (Fleet and Vault are declared "not
built this run"). None of that is silent: the findings artifact's coverage ledger names every
environment that was not built and every scenario that was therefore not executed, and the release
history row records the reduced scope. A scenario never moves to an environment that structurally
cannot host it.

**Ports.** Every environment uses ports distinct from `LOCAL_DEV.md`'s manual walkthrough and from
`gimle-smoke-tests`' fixture, so a run can coexist with a developer's own cluster on one machine.

## 3. Coverage model

Coverage is measured against the platform's own requirements, not an invented ledger. Every
`GIMLE-NNN` in `requirements-matrix.json` is placed in exactly one of three classes in
`forseti.json`:

- **User-observable** — a real person can exercise it through the CLI, a console, the API, the
  tooling, or a shipped example. This is the denominator the fleet is measured against.
  Anything not listed under the other two classes is user-observable by default, so a newly added
  requirement is loud until somebody places it.
- **Internal** — real platform behaviour invisible from outside a process (wire codecs, Raft and
  gossip mechanics, breaker scoring, TLS reload plumbing, storage-engine internals). Not skipped:
  each row carries the unit-test or Holmgang-scenario citation `rtm.json` already records for it,
  and the generator flags any that has neither.
- **Out of scope** — not built yet, a documented limitation, or a requirement that *is* a test asset
  (a suite, a fixture, a harness primitive). Listed with a reason in the exclusions table, never
  silently dropped.

**Target: at least 90% of user-observable requirements reached by a fleet scenario.** The rest carry
a unit-test or Holmgang citation in the residual table. Two numbers matter and the artifact reports
both: *designed reach* (what the catalog below covers, computed here) and *delivered reach* (what a
given run actually executed against environments that were actually built).

<!-- forseti:generated coverage-summary -->
| Bucket | Count | Meaning |
|---|---:|---|
| Requirements in `requirements-matrix.json` | 961 | The whole denominator before any classification. |
| Out of scope | 70 | Not built, a documented limitation, or a test asset itself — see the exclusions table. |
| Internal | 203 | Real platform behaviour a user cannot observe from outside a process; every row carries its unit-test or Holmgang citation (Holmgang 26, unit 177, uncited 0). |
| **User-observable** | **688** | The capability set the fleet is measured against. |
| Reached by a fleet scenario | 662 | **96.2%** of the user-observable set — meets the 90% target. |
| Observable, not fleet-reached | 26 | Each carries its unit/Holmgang citation in the residual table. |
<!-- /forseti:generated -->

**Keeping it in sync.** Adding a `GIMLE-NNN` to `requirements-matrix.json` means also placing it in
`forseti.json` — in a scenario's `requirements`, or in an internal/out-of-scope group with its
reason — then re-running `python3 scripts/generate_forseti_docs.py`, which fails loudly on an
unplaced observable requirement with no citation. Removing or fundamentally changing a requirement
means re-reading every scenario that names it: a scenario that passed against the old behaviour does
not carry over to the new one.

## 4. Agent roster

<!-- forseti:generated roster -->
| ID | Persona | Who they are | Environments | Scenarios | Requirements reached |
|---|---|---|---|---:|---:|
| **OPS** | Ops — Platform operator | The person who unpacks the archives and stands the cluster up, keeps it up, upgrades it, and restores it after a bad day. | A B C | 15 | 78 |
| **DEV** | Dev — Module author | A developer writing their first module against the docs, using the Maven plugin and hilmir tooling from a source checkout — never a running production cluster. | G A | 7 | 104 |
| **DEP** | App-1 — Deployments | A developer shipping a long-running service and living with it: redeploys, rollbacks, probes, tiers. | A | 13 | 67 |
| **BATCH** | App-2 — Batch, Cron, Node & Stateful workloads | Whoever owns the nightly jobs, the per-node agents, the ordered stateful sets, the plain-process vessels and their volumes. | A B | 9 | 62 |
| **SCHED** | Scheduling & Resilience | An SRE deciding whether the platform can be trusted unattended across several machines. | B | 10 | 48 |
| **NET** | Fabric & Networking | Whoever wires two teams' services together: Services, DNS, the node proxy, the gateway, network policy. | A B C | 10 | 77 |
| **GOV** | Governance — Tenancy, Quotas & RBAC | A platform admin onboarding a second team and needing to prove the first one can't see it. | A C | 12 | 56 |
| **SEC** | Security & Secrets | Whoever is accountable if a secret leaks or a certificate is trusted that shouldn't be. | C A | 12 | 84 |
| **ART** | Artifacts & Registry | A release engineer publishing module builds and expecting the registry to behave like Nexus. | A B | 8 | 31 |
| **OBS** | Observability & Console | An on-call engineer with nothing but the consoles and `gimle logs`, at 3 a.m. | A B | 10 | 97 |
| **JRN** | Journeys — Sample applications | Someone validating that a real application, not a primitive, works end to end — including a custom-kind operator. | A B | 7 | 27 |
| **CHAOS** | Exploratory, Chaos & Negative-path | Mildly adversarial, reads no manual twice, and runs the shipped chaos tooling against the cluster. | A B C | 10 | 52 |
| **LEAD** | Lead — Triage, deduplication & report | The Tech QA lead. Never runs a scenario. Ingests every raw finding, fingerprints and merges duplicates, adjudicates disagreement, writes the findings artifact. | — | — | — |
<!-- /forseti:generated -->

Each persona agent is briefed with exactly five things: its persona line from the table above; the
connection details of the environment(s) it owns (endpoints, credentials or certificate material,
the console URLs); its slice of the scenario catalog; the raw-finding template in §7, to be filled in
verbatim; and the posture rules in §1. It is not briefed on what other agents are doing — the
collisions are meant to be unplanned.

## 5. Scenario catalog

Each scenario is an objective plus an oracle. Findings cite scenario IDs for traceability; scenario
rows cite the `GIMLE-NNN` requirements they reach, which is where the coverage numbers come from.
Environment letters: **G** Forge, **A** Midgard, **B** Fleet, **C** Vault.

<!-- forseti:generated scenario-catalog -->
#### Ops — Platform operator

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **OPS-1** | G | Unpack every distribution archive (`gimle-platform`, `gimle-cli`, `gimle-hilmir`, `gimle-midgard`, `gimle-ragnarok`, `gimle-ivaldi`); verify each `.sha256` against its archive; open each archive's CycloneDX SBOM; run each `bin/` launcher with `-h`. | Checksums pass; each SBOM lists that archive's own dependency set, not a copy of another's; every launcher prints scoped help and exits 0 -- `hilmir -h` included, not treated as an unknown token -- except `ivaldi -h`, which IvaldiMain reads no positional arguments at all (only -D system properties), so `-h` there is the underlying `java` launcher's own generic usage text, not a scoped one; still exits 0. | GIMLE-560, GIMLE-561, GIMLE-562, GIMLE-564, GIMLE-611, GIMLE-635, GIMLE-642, GIMLE-849, GIMLE-909 |
| **OPS-2** | A | Boot Midgard cold from the archive (`docker compose up -d`) with no prior state; where no Docker daemon exists, `mvn gimle:bootstrap` instead, noted in the run. | Becomes healthy; the three seeded examples reach ACTIVE; all five documented URLs load (main, Fafnir and Andvari consoles served from the classpath, deep links resolve client-side); each process prints a legible startup banner; Andvari's and Muninn's plaintext warnings are loud and specific; the reserved `gimle-system` tenant exists; the Fafnir console needs no login in plaintext mode. | GIMLE-611, GIMLE-392, GIMLE-027, GIMLE-020, GIMLE-316, GIMLE-332, GIMLE-271, GIMLE-291, GIMLE-039, GIMLE-040, GIMLE-272, GIMLE-292, GIMLE-650 |
| **OPS-3** | A | `docker compose stop`/`start` Midgard after scaling one example and deleting another; then `down -v` and `up` again. | Edits survive the restart (the store's durable log replayed); after `down -v` the cluster is genuinely empty and re-seeds; `hilmir status` inside the container agrees with what is running. | GIMLE-148, GIMLE-152, GIMLE-393 |
| **OPS-4** | B | Stand Fleet up from a hand-written five-machine `topology.yaml` using only the platform archive's own docs: `hilmir validate` (fix every ERROR it names), `hilmir plan`, one `hilmir up --machine` per machine in an out-of-dependency order. Include one deliberately broken replica entry -- a second control-plane replica with no machine -- before fixing it. | `validate`'s findings are specific and actionable; `up` on a machine whose prerequisites live elsewhere blocks then proceeds rather than racing; `hilmir status` on each machine matches reality; the density knob and log-format flag set in `jvm:` are honoured; every process answers its operator health signal. The parser's rejection names the entry it read (controlPlane.replicas[1]), not a bare field name that repeats across every role. | GIMLE-021, GIMLE-390, GIMLE-391, GIMLE-392, GIMLE-393, GIMLE-706, GIMLE-746, GIMLE-887, GIMLE-888, GIMLE-890, GIMLE-891, GIMLE-916 |
| **OPS-5** | C | Stand Vault up: `hilmir pki init`, then the same `up` sequence; inspect a leaf certificate with `openssl x509`; connect to the control plane, Fafnir, Muninn and Andvari with and without a client certificate; grep every process log and the shell history for the bootstrap password. | Every inter-process link negotiates TLS (no silent plaintext fallback); a certless client is refused; leaf SANs are DNS names matching the topology; agents joined through the real token + CSR flow; a cross-worker fabric call succeeds over mTLS; the one-time bootstrap password appears in no log or persistent sink. | GIMLE-394, GIMLE-015, GIMLE-070, GIMLE-073, GIMLE-255, GIMLE-123, GIMLE-258, GIMLE-333, GIMLE-196, GIMLE-742, GIMLE-903, GIMLE-905 |
| **OPS-6** | B | `hilmir store add` a sixth machine's store replica to the running Fleet, then `hilmir store remove` one of the originals, writing deployments throughout. | The cluster stays quorate and writable throughout; membership change is one server at a time; nothing scheduled on the removed machine is left orphaned. | GIMLE-395, GIMLE-396, GIMLE-146 |
| **OPS-7** | B | `hilmir upgrade-cluster` the running Fleet to a second build of the platform archive, then `hilmir rollback`; repeat the upgrade `--remote` over SSH where the SSH machine exists. | Workloads stay available across the rolling binary upgrade (store restarts respect quorum); the rollback returns to the prior binary, not merely a prior manifest. | GIMLE-397, GIMLE-580, GIMLE-844 |
| **OPS-8** | G | Build the `dist-with-jre` platform archive and boot it in a container image with no JDK on `PATH`. | The bundled launchers find their own `jre/<component>/` and run; the agent still needs (and the docs say it needs) a real JRE. | GIMLE-563 |
| **OPS-9** | B | Against the ssh-remote machine (`gimle-holmgang/compose/docker-compose.ssh-remote.yml`'s shape): `hilmir up --remote`, `status --remote`, `down --remote` from outside the container. | Host-key pinning behaves as documented (TOFU or pinned fingerprint); the archive self-provisions; each verb's output matches what a local `status` inside the machine reports. | GIMLE-576, GIMLE-891 |
| **OPS-10** | A B | `gimle backup create`, then mutate the cluster (delete a deployment, add a tenant), then restore the backup. | Restored state matches the backup exactly, the later mutations are gone, and every running instance reconciles to the restored desired state without manual intervention. | GIMLE-701 |
| **OPS-11** | B | Poll each process kind's own health/status signal (control plane, Mimir, agent, Fafnir `/status`, Andvari `/status`, Muninn `/status`, an agent's gossip membership surface); then stop one process and poll again. | Each signal is reachable and truthful; the stopped process's signal fails fast; the gossip membership view drops the dead agent within the documented window. | GIMLE-706, GIMLE-293, GIMLE-318, GIMLE-339, GIMLE-126, GIMLE-849, GIMLE-890 |
| **OPS-12** | A | Release-bundle lifecycle: `hilmir deploy` a bundle with `${values.*}` templating and `--wait`; `releases`/`release-status`; `hilmir upgrade` with a resource removed from the bundle and a secret renamed (the old key dropped, a new one declared); `hilmir rollback`; `hilmir undeploy`; then `hilmir sync --prune --watch` a GitOps directory and edit files under it. | Templating precedence matches the docs; `--wait` returns only once workloads are ready; upgrade prunes exactly the dropped resource, and reports the dropped secret and config keys as prunedKeys -- reading the old secret key afterwards is a miss, not a stale value shadowing a config entry of the same name; rollback restores the prior revision; sync converges on each edit and prunes on delete. | GIMLE-398, GIMLE-399, GIMLE-400, GIMLE-401, GIMLE-402, GIMLE-403, GIMLE-404, GIMLE-405, GIMLE-406, GIMLE-407, GIMLE-408, GIMLE-851, GIMLE-914 |
| **OPS-13** | A | `hilmir enable gateway` then `hilmir disable gateway` on a running cluster. | Enabling deploys the gateway as a real workload that reaches ACTIVE; disabling removes it cleanly with no orphaned instance. | GIMLE-412, GIMLE-413 |
| **OPS-14** | A | Deploy a Tier-2 module, read the agent's log around the first and second worker spawn. | The agent records creating the JDK AOT cache on the first spawn and reusing it on the next; the second worker's time-to-ACTIVE is visibly shorter. | GIMLE-603 |
| **OPS-15** | A | Read platform (non-instance) logs: `gimle logs node/<id>` for the merged SYSTEM view, the per-process files under the data root, and restart one process with the JSON console format flag. | Node platform logs are retrievable and separate from instance logs; the merged SYSTEM view interleaves worker stdout correctly; the format flag switches the console encoding without a rebuild. | GIMLE-024, GIMLE-128, GIMLE-021, GIMLE-892 |

#### Dev — Module author

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **DEV-1** | G A | Write a brand-new module from the docs alone: `gimle-module.yaml` with `resources.request`/`limit`, Kubernetes-shaped quantities, probe classes with per-module interval/timeout/threshold, one deliberate outbound `HttpClient`; run `hilmir doctor` and `mvn gimle:doctor`, then `mvn gimle:init`, fix what they flag, deploy. | `doctor` explains every finding in plain language, flags the outbound call as INFO only, and passes once fixed; `init` scaffolds a manifest that applies unchanged; a descriptor with `request > limit` or a non-JPMS jar is rejected with a field-level reason. | GIMLE-059, GIMLE-060, GIMLE-409, GIMLE-410, GIMLE-411, GIMLE-422, GIMLE-423, GIMLE-573, GIMLE-003, GIMLE-005, GIMLE-725, GIMLE-008, GIMLE-883, GIMLE-885 |
| **DEV-2** | G | Bring up a local cluster with `mvn gimle:bootstrap`, then again piecewise (`gimle:store`, `gimle:controlplane`, `gimle:fafnir`, `gimle:muninn`, `gimle:andvari`, `gimle:agent`); `mvn gimle:deploy` and `mvn gimle:publish` the module from DEV-1; `mvn gimle:tls-init` and re-bring-up in TLS. | Each goal works with no `-pl` or classpath flags exactly as `LOCAL_DEV.md` says; `deploy` reaches ACTIVE; `publish` lands the coordinate in Andvari; the TLS bring-up authenticates the CLI with the minted material. | GIMLE-418, GIMLE-419, GIMLE-420, GIMLE-421, GIMLE-424, GIMLE-078, GIMLE-882, GIMLE-884 |
| **DEV-3** | A | Apply the same workload as `apiVersion: v1` (registry coordinate) and as the alpha default with `artifactPath`; apply a manifest whose `module:` disagrees with the jar's own descriptor; run `gimle apply --dry-run` on a good and a bad manifest. | v1 rejects `artifactPath`; alpha use is accepted with a surfaced deprecation warning; the identity mismatch is rejected at admission naming both identities; dry-run returns a structured verdict with every stage reported and writes nothing. | GIMLE-609, GIMLE-610, GIMLE-607, GIMLE-768, GIMLE-010 |
| **DEV-4** | G | `mvn gimle:saga` (twice — the second must reuse the running server); `mvn gimle:saga-import` of existing surefire reports; `mvn gimle:verify -pl gimle-core` under tracking; browse the Saga console; `mvn gimle:saga-stop`. | Runs appear live with a streaming test feed; imported runs show their totals; a test's detail and cross-run history resolve; two runs diff; the flake scoreboard ranks `@Tag("flaky")` tests as quarantined; a run's event stream can be tailed as NDJSON; stop is idempotent. | GIMLE-427, GIMLE-428, GIMLE-429, GIMLE-430, GIMLE-475, GIMLE-476, GIMLE-477, GIMLE-478, GIMLE-479, GIMLE-480, GIMLE-481, GIMLE-482, GIMLE-485, GIMLE-487, GIMLE-488, GIMLE-491, GIMLE-492, GIMLE-493, GIMLE-495, GIMLE-898 |
| **DEV-5** | G A | Build the documentation site (`mvn gimle:docs`) and follow its four tutorials command by command against Midgard, including the orders-platform NetworkPolicy example in both its API and CLI forms. | Every command works as written or the page is filed as a defect; the `--deny-all-callers` form does what the page says. | GIMLE-425, GIMLE-636 |
| **DEV-6** | G A | In a scratch module, exercise the `ModuleContext` downward API: instance identity, config key enumeration, named `dataDirectory(name)` volumes, and a config-change subscription; then change and delete a config key while it runs. | Identity matches what `gimle get instances` shows; enumeration lists exactly the delivered keys; each named volume is distinct and durable; the subscription fires on change and the deleted key is retracted from the running instance. | GIMLE-053, GIMLE-616, GIMLE-617, GIMLE-630, GIMLE-738, GIMLE-840 |
| **DEV-7** | G | `mvn gimle:ivaldi` (twice — the second must reuse the running server); create a Blueprint via `POST /api/blueprints`, list it, GET it back by id, and delete it; `POST /api/validate` a rendered `topology.yaml` with no agents declared and one missing Fafnir; `GET /console` and confirm the bundled Ivaldi web console is actually served, not just the API; `mvn gimle:ivaldi-stop`. POST /api/clusters (a local, no-auth connection); POST /api/runs against it with a topology naming no agents and a bundle with one tenant; confirm the run reaches `failed` or `running` rather than hanging, and that /api/clusters/{id}/topology reflects whatever the run actually applied. Then, with that run still tracked, POST a second /api/clusters and a second /api/runs against it; read GET /api/runs, GET /api/runs/for-cluster/{id} and GET /api/runs/for-blueprint/{id}; export the blueprint's file set and inspect its manifests and ivaldi.artifacts.yaml. With both runs live, open the console at /console: read the blueprint list's Run column, the designer's own status badge, and the Clusters screen; then delete a link on the canvas and check the inspector's Machine box on a role that a link places. Include a NetworkPolicy declaring an explicit empty allowedCallerTenantIds (deny-all-cross-tenant) in the deployed bundle; after the cluster is running, try deleting its cluster connection, and try starting a second, different blueprint against the same cluster. POST the exact same blueprint document (same id) to /api/blueprints twice. In the same console session: build a LimitRange node with only a memory value on its min bound and confirm the half-filled warning; select a Tenant node and confirm its own Links section lists what belongs to it; delete a role node with a link attached and undo with one Ctrl+Z; drag a node through several intermediate positions and undo the whole drag with one Ctrl+Z; duplicate the running blueprint and separately import its exported document twice. Deploy a second blueprint, sharing the exact same topology, onto the cluster the first one already booted; confirm both are tracked and reachable independently from the console's Runner page and the Clusters screen; try changing the topology of one of them while the other is still live; stop one of the two and confirm the other, and the cluster's shared infra, keep running; then stop the last one and confirm the cluster connection can finally be deleted. Validate a blueprint whose jar-sourced Deployment's real module resources are below its tenant's LimitRange minimum, using a real built module jar rather than the Inspector's own modeled value. In the console: click an edge routed under a Machine's own frame; drag a palette item onto a genuinely empty canvas; click the same palette item twice in a row without moving the view; delete a cluster connection with a live deployment tracked against it; clear a LimitRange bound field. Try deleting the blueprint one of the two live runs is tracked against, then stop that run and delete the blueprint again. Apply two Services on the running cluster that front the same deployment on the same port. Front two Services in the same tenant at the same deployment and confirm the overlap advisory; clear a Service's own Target Port field; toggle a DaemonSet's own Tolerate-all-taints checkbox and check its export; drag-link then unlink (and separately delete, both via the canvas and via the Inspector's own delete button) a Service from a Tenant; open the Problems drawer and press Escape; Tab to an unselected canvas node and check its computed focus outline; navigate between screens and check where keyboard focus lands; load the Designer, Blueprint list, and Clusters screens at 1280x800 and 390px widths. Start a run whose cluster connection is configured for a control-plane port the topology's own control plane never listens on. Address an mTLS cluster's control-plane URL by IP literal under a cluster saved with a display name distinct from its internal id. In the add-cluster dialog, fill in a client certificate path and read the Control-plane URL field's own hint and placeholder. Click a palette entry via its own automation id while a canvas node of the same kind is already present. Import a DaemonSet blueprint whose disruption budget carries a nonzero maxSurge. Edit a blueprint's name, then within the debounce window read the browser's own localStorage draft directly and confirm the server's copy is still unchanged; separately, seed a newer localStorage draft for a blueprint before opening it. Build a Blueprint whose topology declares two machines (a store, control plane and Fafnir on the first, Andvari alone on the second) and Run it; while it is up, read the console's own Runner page Machines section. | The second `gimle:ivaldi` invocation logs reusing the running server rather than spawning a second one; the created blueprint's id is minted from its name, its GET body round-trips exactly, and it is gone after delete; validate reports `NO_AGENTS` (warning) and `NO_FAFNIR` (error) naming `topology.yaml`, the same codes `hilmir validate` itself would report; `/console` returns 200 with the Ivaldi console's own title in the served HTML; stop is idempotent. The run's terminal status and GET /api/runs/{id}/log both name the same outcome; the cluster's own /topology reads back exactly the topology.yaml the run submitted once it reaches running, and stays null if the run never got that far. Starting the second run leaves the first reachable by its own cluster id; /api/runs lists both; for-blueprint returns only the run its own blueprint started, and an idle shape for a blueprint that has never run. No exported manifest carries artifactPath -- every workload is apiVersion v1 with a bare module coordinate -- and each jar-sourced workload is named once in ivaldi.artifacts.yaml, which is absent entirely when nothing is jar-sourced. The list and the Clusters screen each name the running blueprint and link to its runner; a second blueprint's designer shows no badge. A canvas link can be deleted (Delete key or the inspector's Links list) and the change survives a reload, and a Machine box fed by a link is read-only and states why rather than accepting input that changes nothing. The deny-all NetworkPolicy applies successfully and GET /networkpolicies on the real control plane shows the stored empty allow list. Deleting the cluster connection while the run is tracked is refused (409), and starting a different blueprint against the same already-owned cluster is refused (409) rather than silently taking it over. The second POST is refused (409) rather than silently creating a second, differently-id'd blueprint. The half-filled LimitRange bound is flagged by name before export. The Tenant's own Inspector panel lists its memberships with a control to cut one. Deleting a linked node removes the node and its edges together and a single undo restores all of them. A multi-position drag costs exactly one undo step and a click that never moves anything costs none. The duplicate and both imports each land under their own fresh id rather than colliding with the source document. The second blueprint's deployment starts and settles independently of the first, with its own status/log/endpoints in the console. A topology change while the other deployment is still live is refused rather than silently rebooting the shared infra. Stopping one deployment undeploys only its own release, leaves the other blueprint's deployment and the cluster's process tree running, and the cluster connection still refuses deletion; stopping the last one finally allows it. Validate reports LIMITRANGE_VIOLATION naming the module's real declared resource value and the tenant's real bound, not the Inspector's modeled value -- catching the mismatch before any run ever boots a cluster. The edge under the Machine's frame is selected, not the Machine. Dragging onto an empty canvas adds a node. Two successive click-to-adds land at visibly distinct positions. The delete-refusal toast is titled for a delete, not a generic load failure. A cleared LimitRange bound field shows no spurious invalid-value error, while a genuinely malformed value still does. Deleting the blueprint while its run is still tracked is refused (409) naming the run, the blueprint stays readable, and the delete succeeds once that run is stopped. The second Service's overlap with the first shows up in the run log as the control plane's own advisory naming both services, not silently dropped. The second Service's overlap with the first is announced (SERVICE_OVERLAP) naming both; a blank Target Port shows no port-range error and is omitted from the exported manifest; the DaemonSet's exported manifest carries tolerateAllTaints: true once checked; unlinking or deleting the Tenant by any path leaves the Service's own Tenant id field genuinely blank and editable, never stuck on the old value; Escape closes the open drawer; the unselected but Tab-focused node shows a real, non-none computed outline; focus lands on an announced landmark after a client-side navigation, not <body>; none of the three screens overflows its own viewport horizontally at either width, and every toolbar control stays reachable by scrolling its own header. The mismatched-port run fails immediately naming both the configured and the topology's own address, before any process boots. The IP-literal mTLS refusal names the cluster by its display name, never its internal id. Filling in a client certificate path switches the Control-plane URL field's placeholder and hint to the mTLS-aware ones naming the machine's own hostname. The palette entry resolves by its own testid independent of any canvas node sharing its label text. The imported DaemonSet's nonzero maxSurge surfaces as DAEMONSET_MAX_SURGE under Max unavailable. The localStorage draft reflects the rename before the server does. Opening a blueprint with a newer localStorage draft than the server's own copy offers Restore or Discard rather than picking one silently; Restore applies the draft and marks it dirty; Discard clears it and leaves the server's own copy showing, and it does not reappear on a further reload. The run boots real processes on both machines concurrently (not refused for having more than one), the backend's own log names both machines and the total process count, and the Runner page's Machines section lists each machine with the roles actually placed on it, not one undifferentiated group. | GIMLE-906, GIMLE-907, GIMLE-908, GIMLE-910, GIMLE-911, GIMLE-912, GIMLE-913, GIMLE-917, GIMLE-918, GIMLE-919, GIMLE-920, GIMLE-921, GIMLE-923, GIMLE-924, GIMLE-925, GIMLE-926, GIMLE-927, GIMLE-928, GIMLE-929, GIMLE-930, GIMLE-931, GIMLE-932, GIMLE-933, GIMLE-934, GIMLE-935, GIMLE-936, GIMLE-937, GIMLE-938, GIMLE-939, GIMLE-940, GIMLE-941, GIMLE-942, GIMLE-943, GIMLE-944, GIMLE-945, GIMLE-946, GIMLE-947, GIMLE-948, GIMLE-949, GIMLE-950, GIMLE-951, GIMLE-952, GIMLE-953, GIMLE-954, GIMLE-955, GIMLE-956, GIMLE-957, GIMLE-960, GIMLE-961 |

#### App-1 — Deployments

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **DEP-1** | A | Write a Deployment manifest from scratch (no copy-paste of a bundled example), `apply -f` it, watch it reach ACTIVE via `get deployments`, `get instances`, `gimle events`, and the console's Deployments/Instances/detail pages. | Every lifecycle transition (INSTALLED→RESOLVED→STARTING→ACTIVE) is a durable event visible in the CLI timeline and the instance detail page; table and JSON output agree. Each instance's reported resource usage is shown against the limit and isolation tier it was admitted under, and a shared-worker (TIER_1) instance is not drawn as though that limit were its own enforced ceiling. The Applications screen reads the same deployment as Healthy/Synced once it is placed, and names the reason whenever it does not. | GIMLE-371, GIMLE-274, GIMLE-219, GIMLE-045, GIMLE-084, GIMLE-032, GIMLE-377, GIMLE-437, GIMLE-439, GIMLE-444, GIMLE-753, GIMLE-157, GIMLE-779, GIMLE-787 |
| **DEP-2** | A | Re-apply with a bumped version while a caller is hitting the service across workers. | Old and new coexist briefly, callers cut over version-aware with zero failed calls (same-worker and cross-worker alike), the old layer disposes; `deployment revisions` and the console's revision panel list both. | GIMLE-058, GIMLE-054, GIMLE-685, GIMLE-601, GIMLE-602, GIMLE-714, GIMLE-001, GIMLE-797 |
| **DEP-3** | A | `deployment rollback --to-revision` to the first version, then from the console's revision panel. | The running version actually reverts (observable behaviour, not just the recorded manifest); the rollback itself becomes a new revision. | GIMLE-601, GIMLE-602, GIMLE-714, GIMLE-797 |
| **DEP-4** | A | Redeploy the same Tier-1 module 25 times in a loop while watching the Metrics screen's worker metaspace/thread gauges and the count of meters per instance. | Metaspace stays flat (no sawtooth), no leak is reported in the worker log, the per-module meter set is evicted on each uninstall rather than accumulating. | GIMLE-048, GIMLE-085, GIMLE-712, GIMLE-353, GIMLE-097 |
| **DEP-5** | A | Deploy the same module as Tier 1 (two replicas) and as Tier 2; inspect worker processes and `-Xmx` flags; kill the Tier-2 worker. | Tier-1 replicas share one worker (same `workerId` in the console, up to the configured density cap) whose `-Xmx` is the node's shared-worker budget rather than any one module's limit; Tier 2 gets its own worker with `-Xmx` equal to its limit; killing it never touches the Tier-1 siblings. | GIMLE-004, GIMLE-065, GIMLE-110, GIMLE-212, GIMLE-647, GIMLE-746, GIMLE-786 |
| **DEP-6** | A | `delete deployment` on a deployment with live traffic, then immediately reuse its name. | In-flight calls drain to the deadline, instances disappear with no ghost entries, no worker respawn is attempted for the deliberate stop, revision and event history are cleared so the reused name starts clean. | GIMLE-057, GIMLE-220, GIMLE-241, GIMLE-104, GIMLE-652, GIMLE-726, GIMLE-895 |
| **DEP-7** | A | Deploy a module whose readiness probe fails for a warm-up period, then flaps once. | The instance is excluded from Service endpoints and fabric traffic until ready, shown in a distinct not-ready state; readiness only counts after a continuous stabilization window; readiness failure alone never triggers a reschedule. | GIMLE-090, GIMLE-227, GIMLE-683, GIMLE-063, GIMLE-088, GIMLE-798 |
| **DEP-8** | A | Deploy a module whose liveness probe starts failing after it is serving, with a short declared interval/threshold; let it fail repeatedly. | Module-tier restart happens without operator action, is visible as a legible event, and repeated failure escalates with backoff until the budget is exhausted and the instance is marked FAILED — never a silent hot loop. | GIMLE-008, GIMLE-036, GIMLE-088, GIMLE-089, GIMLE-113, GIMLE-725, GIMLE-830 |
| **DEP-9** | A | Scale a deployment 1→4→2 by re-applying `replicas`. | Replica count converges each time; scale-down removes exactly the surplus and nothing else. | GIMLE-219, GIMLE-220, GIMLE-862 |
| **DEP-10** | A | Deploy a manifest naming a probe class that does not exist in the jar; deploy a jar whose install fails inside the worker. | Both end in FAILED with a durable event that names the cause — never stuck at INSTALLED with no explanation. | GIMLE-666, GIMLE-114, GIMLE-866, GIMLE-868, GIMLE-886 |
| **DEP-11** | A | `gimle get deployment <name> --watch` during an apply; `gimle get deployment <name> -o manifest \| gimle apply -f -`. | Watch streams the convergence and exits cleanly; the round trip is a no-op re-apply (same generation), proving `get`'s manifest projection is complete. | GIMLE-767, GIMLE-718 |
| **DEP-12** | A | Create a deployment through the console's New-deployment form, browse its detail page with 60 instances, force a write failure. | The form applies a valid manifest; instance tables are bounded and paginated; the failure surfaces as a toast and, because a toast auto-dismisses on its own timer, also as a persistent inline error banner naming the same rejection reason; the screen keeps itself current without a manual reload. | GIMLE-439, GIMLE-757, GIMLE-632, GIMLE-759, GIMLE-801 |
| **DEP-13** | A | Deploy a module that `requires` another within a version range, first with an in-range provider present, then out-of-range, then with a dependency cycle. | In-range resolves and starts; out-of-range and cyclic both fail resolution with a message naming the module and range/cycle. | GIMLE-002, GIMLE-043, GIMLE-001 |

#### App-2 — Batch, Cron, Node & Stateful workloads

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **BATCH-1** | A | Apply a Job; apply one with an `activeDeadline` it will exceed; apply one that fails; create one from the console's Jobs form. | Exactly one run to COMPLETED that does not linger like a Deployment; the deadline kills and marks the second; the third retries under exponential backoff, not every tick; the console form and list agree with the CLI. | GIMLE-372, GIMLE-052, GIMLE-092, GIMLE-235, GIMLE-236, GIMLE-680, GIMLE-440, GIMLE-713, GIMLE-787 |
| **BATCH-2** | A | Apply a CronJob on a one-minute schedule; let two fires happen; `cronjob trigger` between them; try each concurrency policy; suspend and resume; set history limits of 1/1; put the tenant over quota before a fire. | Three completed runs with no duplicate or skipped fire; Forbid/Replace behave as documented; suspension stops fires without deletion; terminal Jobs are pruned to the limits; the over-quota fire is refused through admission like any other Job. | GIMLE-373, GIMLE-171, GIMLE-237, GIMLE-238, GIMLE-239, GIMLE-724, GIMLE-670, GIMLE-658, GIMLE-441, GIMLE-713, GIMLE-896 |
| **BATCH-3** | A B | Apply a DaemonSet on Midgard, then on Fleet; add a node after the fact; taint one node and set `tolerateAllTaints`. | Exactly one instance per eligible node including the late-joining one; the tainted node is skipped until the toleration is set; console list/detail match `get daemonsets`, including the reconciler-published desired (eligible-node) count tracking the placed count as nodes join/taint/untaint. | GIMLE-374, GIMLE-218, GIMLE-231, GIMLE-442, GIMLE-675, GIMLE-789, GIMLE-862 |
| **BATCH-4** | A | Roll a bad version onto a DaemonSet and a StatefulSet, then `rollback` each; also change only the artifact of a StatefulSet. | Every node/index reverts, not just some; an artifact-only change is recognised as a rolling update. | GIMLE-601, GIMLE-602, GIMLE-694, GIMLE-714 |
| **BATCH-5** | A B | Apply a StatefulSet with three ordered replicas declaring a volume; kill a worker; scale 3→1; inspect the Volumes screen. | Start is ordered; each index keeps its identity, its node and gets its own volume back after the restart; scale-down retires one index at a time; volumes are tenant-scoped and their soft disk usage shows in the console. | GIMLE-375, GIMLE-007, GIMLE-069, GIMLE-117, GIMLE-160, GIMLE-217, GIMLE-233, GIMLE-234, GIMLE-443, GIMLE-612, GIMLE-621, GIMLE-622, GIMLE-751, GIMLE-630, GIMLE-655 |
| **BATCH-6** | A | Delete a StatefulSet and inspect its volumes; `gimle volume destroy` with and without `--tenant`, and against a coordinate with nothing on disk. | Volumes are retained by default and listed as orphaned; destroy targets the named tenant's volume only; a destroy that removed nothing returns 404, not a false success. | GIMLE-612, GIMLE-621, GIMLE-770, GIMLE-771, GIMLE-751, GIMLE-870 |
| **BATCH-7** | A | `apply -f` an ArtifactSet bundling several workload kinds under per-module tenant tags, with one member deliberately broken. | Healthy members come up together; the failure is reported against that member by name, not the whole bundle opaquely. | GIMLE-577, GIMLE-839 |
| **BATCH-8** | A | Deploy a Vessel (plain-jar process) workload from a bundle artifact: dynamic and fixed ports, env from a secret and from a port, a rendered config file, a TCP and an HTTP probe, a volume and a secret-backed file mount; kill its process; re-apply with only an env change. | It runs as its own supervised process; ports/env/files land as declared; probes gate readiness after the initial delay; the crash is respawned with the delay clock reset; the env-only change is detected as drift and applied. | GIMLE-009, GIMLE-118, GIMLE-119, GIMLE-120, GIMLE-121, GIMLE-681, GIMLE-629, GIMLE-608, GIMLE-846, GIMLE-847, GIMLE-897 |
| **BATCH-9** | A | `gimle get statefulsets`/`daemonsets`/`jobs`/`cronjobs` with default output. | Clean table columns like `get deployments`, never a raw spec dumped per cell. | GIMLE-637 |

#### Scheduling & Resilience

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **SCHED-1** | B | Deploy four replicas with `placement.antiAffinity: true`, then without; add a `requiredLabels: [edge]` constraint; taint a node and give one tenant a toleration. | With the flag no two replicas share a node while a free node exists; without it they may co-locate; the label constraint lands replicas only on the labelled node; the taint reserves its node for the tolerating tenant alone. | GIMLE-214, GIMLE-211, GIMLE-216, GIMLE-134, GIMLE-648, GIMLE-675, GIMLE-850 |
| **SCHED-2** | B | Request more memory than any node has free, at the default priority and then again with `placement.priority` set above what is already running; and once more at a priority equal to the residents'. | A clear no-feasible-placement outcome naming the dimension, the numbers and the shortfall — not a stuck INSTALLED instance. The higher-priority submission instead displaces strictly-lower-priority instances and lands; the equal-priority one does not, and nothing is evicted for it. | GIMLE-106, GIMLE-744, GIMLE-783, GIMLE-831, GIMLE-838, GIMLE-867, GIMLE-868, GIMLE-869 |
| **SCHED-3** | B | `cordon` a node carrying running instances, deploy more, `uncordon`; repeat from the console's node controls. | Nothing new lands while cordoned, existing instances are untouched (cordon is not drain), the node's page shows the flag and its capacity bars. | GIMLE-132, GIMLE-161, GIMLE-213, GIMLE-376, GIMLE-445, GIMLE-715, GIMLE-838, GIMLE-893 |
| **SCHED-4** | B | Kill a Tier-1 worker process directly. | Module-level recovery is attempted before worker-level escalation; the crash lands as a durable instance event; the deployment returns to ACTIVE. | GIMLE-103, GIMLE-089, GIMLE-113 |
| **SCHED-5** | B | Kill the same worker five times in quick succession; do the same to a StatefulSet's and a DaemonSet's instance. | Escalating CrashLoopBackOff-style backoff is visible for all three kinds — restarts do not hot-loop at a fixed interval. | GIMLE-036, GIMLE-226, GIMLE-674 |
| **SCHED-6** | B | Stop an agent process outright (machine loss) hosting Deployment, StatefulSet and DaemonSet instances, with a DisruptionBudget on the Deployment. | The node shows STALE, never vanishes from `get nodes`; all three kinds are rescheduled elsewhere; eviction respects the budget; its services leave the fabric catalog and the gossip view reaps it. | GIMLE-224, GIMLE-631, GIMLE-669, GIMLE-191, GIMLE-205, GIMLE-445, GIMLE-132 |
| **SCHED-7** | B | Drive `greeter-load-generator` traffic up and down against a deployment with request-rate autoscaling, then with WEIGHTED multi-signal mode, then with a tenant quota just above the current count. | Replicas track load in both directions with the declared stabilization windows and no flapping; WEIGHTED blends as documented; autoscaling never exceeds the tenant quota. | GIMLE-229, GIMLE-230, GIMLE-723, GIMLE-506 |
| **SCHED-8** | B | Roll a new version onto a four-replica deployment with `maxSurge` and a DisruptionBudget while a consumer keeps calling it. | Serving capacity never dips below the budget, at most the declared surge exists, migrations are throttled, and the consumer sees zero failed calls through the cutover. | GIMLE-222, GIMLE-223, GIMLE-682, GIMLE-685, GIMLE-865 |
| **SCHED-9** | B | Deploy Tier-2 workloads from two tenants onto a two-node pool. | Node-level tenant isolation keeps the two tenants' Tier-2 workers on different nodes when it can. | GIMLE-215 |
| **SCHED-10** | B | Deploy twelve tiny Tier-1 modules under one tenant with the density knob set to 4, then deploy modules whose declared `resources.limit.memory` sums past the shared-worker heap budget before that cap is reached, and one module declaring more heap than a whole budget. | Tiny instances pack four per worker and a fifth worker is spawned only when the cap is reached; the larger modules stop packing on the summed-limit budget instead, spawning a fresh worker rather than being refused; a module larger than a whole budget gets a worker sized to its own declared limit and nothing is packed alongside it; every shared worker's `-Xmx` is the configured budget, identical whichever instance spawned it. | GIMLE-110, GIMLE-746, GIMLE-786, GIMLE-832, GIMLE-869, GIMLE-904 |

#### Fabric & Networking

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **NET-1** | A B | Create a Service fronting a three-replica deployment (and one fronting a hosted module that calls `reportPort`); scale up and down; declare then omit `targetPort`; create a second Service overlapping the first; drive it from the console's Networking screen too. Also front a deployment whose module never reports a port at all, and read its endpoints. | `service endpoints` tracks live replicas exactly; a hosted module's reported port resolves; `targetPort` is authoritative when declared and absent when not; the overlap is announced as a warning; CLI and console agree. The Service backed by a module that reports no port returns 200 with an empty endpoints array and a stated exclusion naming that deployment, rather than an empty list indistinguishable from 'no replicas scheduled yet'. Redeploying the same blueprint onto the already-running cluster (no topology change) still reports every live process in the run's own processes list. | GIMLE-566, GIMLE-571, GIMLE-578, GIMLE-586, GIMLE-728, GIMLE-729, GIMLE-800, GIMLE-802, GIMLE-852, GIMLE-915, GIMLE-922 |
| **NET-2** | B | Query Skald from a node: `A` and `SRV` for a Service, a headless Service, a Service with zero live endpoints, a nonexistent name, a large answer over UDP then TCP, an ExternalName Service; then stop the control plane. Then open the console's Skald DNS screen and track that responder's address. Then apply gimle-skald/deploy/'s DaemonSet and UDP Service, and repeat the same queries against the Service's own ClusterIP rather than the standalone process; stop one replica's control-plane polling and watch its readiness. | Live endpoints resolve; SRV and headless answers are correct; zero endpoints is NODATA, nonexistent is NXDOMAIN; truncation falls back to TCP; ExternalName answers a CNAME; with the control plane down answers degrade only after the documented staleness window. The console's Skald DNS screen derives the same names, shows zero A records for the empty Service, and reports that responder's directory age and consecutive poll failures from its own shipped gauges. The deployed form answers identically through its Service; a replica whose directory goes stale leaves the Service's endpoint set without being restarted, and its /health stays green throughout. | GIMLE-569, GIMLE-613, GIMLE-620, GIMLE-628, GIMLE-686, GIMLE-721, GIMLE-774, GIMLE-776, GIMLE-784, GIMLE-842 |
| **NET-3** | B | Enable Bifrost on one agent; dial its local port for a Service repeatedly, then with ClientIP affinity, then from off-node via the NodePort-style exposure; do the same against a second Service declaring `protocol: UDP` in front of a datagram workload, from two clients at once; delete the Service. | Round-robin across live endpoints preferring local ones; affinity pins a client; off-node reaches the same backends; the listener closes on delete and never serves one more connection. The UDP Service relays datagrams and returns each reply to the client that sent the request, never to the other one. | GIMLE-568, GIMLE-626, GIMLE-618, GIMLE-748, GIMLE-782 |
| **NET-4** | A | Declare the gateway's routes as an `Ingress`: a path route, a vhost-constrained route, a SERVICE route, a VESSEL route, a typed-argument fabric route and two routes sharing a prefix. Edit the Ingress live; submit a deliberately malformed route; re-submit with a stale expectedVersion; stop the control plane while the gateway is serving. Then open the console's Gateway screen against that same table, including one route pointing at a Service that does not exist. Confirm there is no config key that accepts a route table at all. | Each request is dispatched by the right rule with longest-prefix matching; a wrong `Host` misses cleanly; typed arguments coerce; no-endpoint and connect failures map to specific status codes; the table reloads without restart; the gateway's own probes report ready. A malformed route is refused at submission naming the field, not accepted and silently unmatched; the stale re-submit is refused rather than overwriting; a control plane that goes away leaves the already-applied table serving rather than emptying it. The console's Gateway screen lists the same table it was given, names the route resolving to nothing, never claims a FABRIC target resolves, and offers no rejected-line panel because no such line can exist; its sidebar entry sits under an Edge group. Writing routes to a `gateway.routes` config key has no effect on any gateway. | GIMLE-356, GIMLE-357, GIMLE-358, GIMLE-360, GIMLE-362, GIMLE-364, GIMLE-366, GIMLE-367, GIMLE-570, GIMLE-679, GIMLE-684, GIMLE-773, GIMLE-775, GIMLE-776, GIMLE-777, GIMLE-778, GIMLE-785, GIMLE-840, GIMLE-841, GIMLE-863, GIMLE-871, GIMLE-873, GIMLE-874 |
| **NET-5** | C | Turn on gateway TLS termination with two virtual hosts carrying different certificates. Then, without restarting the instance, update gateway.tlsCertificates to add a third virtual host's binding and dial its SNI. | Plaintext no longer answers; each host is served the certificate matching the client's SNI; fabric calls behind the gateway succeed over mTLS. The newly-added third host is served its own certificate on the very next handshake with no restart, and the original two hosts keep being served correctly across the config change. | GIMLE-722, GIMLE-196, GIMLE-799 |
| **NET-6** | A C | Create a NetworkPolicy denying tenant B from tenant A's service (tenant-wide, then per-deployment, then per-interface); attempt the call from a tenant-B module and by dialling the instance directly, at the address `GET /instances/{name}/{index}/fabric-endpoint` reports; edit the allow-list one entry at a time; name a nonexistent tenant; close a tenant before its first policy; restart the control plane. | Refused with a legible reason at the caller; the listener side refuses the direct dial independently; scoped rules match only what they name; edits are version-guarded; the bad tenant is rejected; the closed tenant denies by default; the policy survives the restart; CLI and console Networking agree. | GIMLE-192, GIMLE-567, GIMLE-572, GIMLE-574, GIMLE-579, GIMLE-587, GIMLE-623, GIMLE-730, GIMLE-731, GIMLE-732, GIMLE-780, GIMLE-791, GIMLE-835 |
| **NET-7** | B | Hold a Bifrost connection open, then apply a NetworkPolicy to that Service's tenant. | The open connection is closed and new ones are refused (fail-closed) with the documented log reason; endpoints are untouched. | GIMLE-575, GIMLE-668 |
| **NET-8** | A | Run fraud-detection's three-hop chain and force one replica to start erroring, then heal it. | The breaker ejects it, traffic keeps flowing through healthy replicas, it is re-admitted once healthy; the breaker's state is visible in logs and shipped meters. | GIMLE-186, GIMLE-720, GIMLE-855 |
| **NET-9** | A B | Place a provider/consumer pair co-located in one worker, as two Tier-2 workers on one machine, and on two machines; watch the same call each way. | All three tiers work transparently to the module; the direct tier shows no socket, the same-machine tier a Unix domain socket, the cross-machine tier TCP; the service is discoverable cluster-wide through the gossip catalog. | GIMLE-181, GIMLE-182, GIMLE-183, GIMLE-093, GIMLE-055, GIMLE-056, GIMLE-190 |
| **NET-10** | C | Enable Bifrost's TLS identity-verifying mode on a Vault agent; dial it with a tenant-member client certificate, a foreign tenant's certificate, and no certificate. | Only the tenant member is forwarded; the other two are refused at the handshake with the documented reason. | GIMLE-627 |

#### Governance — Tenancy, Quotas & RBAC

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **GOV-1** | A C | Create two tenants with distinct quotas; deploy up to and past each; lower a quota below current usage; read tenant usage in CLI and console. | Over-quota is refused with a reason naming the dimension and shortfall; a retroactive violation flags, never evicts; tenant A's quota never blocks tenant B; usage is real, server-computed consumption. | GIMLE-378, GIMLE-037, GIMLE-162, GIMLE-228, GIMLE-246, GIMLE-716, GIMLE-446, GIMLE-744, GIMLE-856 |
| **GOV-2** | A | Set a LimitRange on a tenant; submit requests inside and outside its bounds via CLI and console. | Admission accepts the valid one and rejects the invalid ones with a field-level reason. | GIMLE-604, GIMLE-605, GIMLE-750, GIMLE-856, GIMLE-857 |
| **GOV-3** | C | Create a role scoped to one kind and one tenant, from a template and by hand with a wildcard; bind it to a new account and to a `group:`; `can-i` for in- and out-of-scope actions as that account (cert and console-login), then attempt them; build a role in the console's Roles picker. | `can-i` predicts exactly what the real attempt then does (in-scope 200, out-of-scope 403); group bindings work for cookie-authenticated principals too; the picker offers the live permission vocabulary; the built-in cluster-admin and operator/node groups behave as documented. | GIMLE-384, GIMLE-385, GIMLE-386, GIMLE-614, GIMLE-615, GIMLE-727, GIMLE-756, GIMLE-709, GIMLE-011, GIMLE-012, GIMLE-250, GIMLE-163, GIMLE-456 |
| **GOV-4** | A | Delete an account that owns an active binding; delete a role that several bindings name. | A sane, documented outcome for the account (refusal or cascade, stated); deleting the role cascades to every binding naming it — no dangling subjects or roles. | GIMLE-678, GIMLE-386, GIMLE-385 |
| **GOV-5** | A C | Perform every privileged action above, plus a certificate approval and a node join; page `gimle audit` with a cursor and filter by actor; use the console Audit screen's `since` filter. | Each action shows up once with the right actor and target; paging is stable across ring-buffer eviction; the `since` filter sends the format the API parses. | GIMLE-383, GIMLE-033, GIMLE-158, GIMLE-251, GIMLE-457, GIMLE-766, GIMLE-769, GIMLE-704, GIMLE-861 |
| **GOV-6** | C | Log into the console as a read-only account and try every write path. | Write controls are hidden or disabled up front, and a refused submit gives visible feedback — never a silent no-op. | GIMLE-456, GIMLE-435, GIMLE-436 |
| **GOV-7** | B | Autoscale a tenant's deployment past its quota ceiling under real load. | Autoscaling stops at the quota exactly as a manual scale would — no bypass. | GIMLE-229, GIMLE-246 |
| **GOV-8** | A | On plaintext Midgard, create a second real tenant and deploy to it; deploy an untenanted workload. | Plaintext is explicitly single-tenant and refuses the second tenant with a reason; the untenanted workload lands in the implicit default tenant and is addressable as such. | GIMLE-649, GIMLE-650, GIMLE-852 |
| **GOV-9** | C | Create the same-named deployment, StatefulSet volume and CronJob in two tenants; use `?tenant=`/`--tenant` on gets, deletes, logs, metrics rollups and `volume destroy`. | Every surface tells the two apart: store keys, logs, metrics rows, volumes and generated Jobs are tenant-scoped; omitting the tenant never silently resolves to the wrong one. | GIMLE-654, GIMLE-656, GIMLE-657, GIMLE-693, GIMLE-772, GIMLE-655, GIMLE-770, GIMLE-863, GIMLE-889 |
| **GOV-10** | A | Attempt to deploy into `gimle-system` as a normal account; set `policy.maxReplicasPerDeployment` and exceed it. | The reserved tenant is operator-only; the policy is enforced at admission with a specific reason. | GIMLE-252, GIMLE-247, GIMLE-856 |
| **GOV-11** | C | Re-apply an existing deployment's manifest with a different `tenant:`. | The re-tenanting is authorized against both tenants and refused unless the caller may write both. | GIMLE-249 |
| **GOV-12** | A | `apply -f` manifests for Tenant, LimitRange, Role, RoleBinding, Account, Service and NetworkPolicy. | Each kind is created from its manifest identically to its `gimle set <kind>` form. | GIMLE-717, GIMLE-894 |

#### Security & Secrets

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **SEC-1** | C A | Set a secret, read it back, update it twice, list versions, fetch an old version explicitly, declare a value type and submit a value violating it; repeat in the console's Secrets screen. | Versioning is real (old values retrievable by version); each version records who/when/type; the malformed typed value is rejected before storage; the proxy relays bytes untouched. | GIMLE-380, GIMLE-282, GIMLE-262, GIMLE-733, GIMLE-734, GIMLE-454, GIMLE-038 |
| **SEC-2** | C | `secret delete` without `--destroy`, read, undelete to the current and to an earlier version, then `--destroy`. | Soft delete hides but preserves; undelete restores the requested version; destroy is irreversible and says so. | GIMLE-284, GIMLE-671 |
| **SEC-3** | C | `secret rotate-key`, read old secrets, `retire-key` the old key; trigger rotation and retirement from the console; read the key-ring fingerprint from Fafnir's status and console. | Old secrets stay readable after rotation (re-encrypted or transparently decrypted) and only the retired key's material is unusable afterwards; rotation/retirement are authorized and audited; fingerprints agree across replicas. | GIMLE-263, GIMLE-279, GIMLE-281, GIMLE-465, GIMLE-755, GIMLE-280, GIMLE-462, GIMLE-692, GIMLE-853 |
| **SEC-4** | C | Fully offline: fetch the seal public key, seal a value with no server connection, feed the ciphertext into a SecretMap via `secretmap seal`, deploy a module that reads it; browse the console's Seal screen. | The module reads the plaintext; the committed ciphertext is safe to share; the Seal screen shows key lifecycle. | GIMLE-597, GIMLE-598, GIMLE-599, GIMLE-600, GIMLE-752 |
| **SEC-5** | A C | ConfigMap and SecretMap from `--from-literal` and `--from-file`; reference both from a deployment with a colliding key; `secretmap rollback`, `secretmap replace`, a batch write with one bad entry; plain Config versions and rollback; console ConfigMaps/SecretMaps screens with History. | Collision is rejected at admission; only referenced maps are delivered; rollback restores exactly the prior key set; replace is atomic; partial batch failure is signalled in HTTP status and exit code; Config history/rollback behaves like Secrets; screens agree with the CLI. | GIMLE-379, GIMLE-453, GIMLE-581, GIMLE-582, GIMLE-583, GIMLE-584, GIMLE-585, GIMLE-588, GIMLE-589, GIMLE-590, GIMLE-591, GIMLE-592, GIMLE-593, GIMLE-594, GIMLE-595, GIMLE-596, GIMLE-651, GIMLE-673, GIMLE-677, GIMLE-836 |
| **SEC-6** | A C | A module reads a Config value declared `--encrypted` and one that isn't; change both while it runs; inspect the store's on-disk state. | Both resolve at runtime; the encrypted one is unreadable at rest; live changes propagate to the running instance without restart. | GIMLE-116, GIMLE-619, GIMLE-738, GIMLE-379, GIMLE-453 |
| **SEC-7** | C | `cert token create`, join a node, `cert request`/`status`/`approve`; request a CSR claiming the operator group and one with a SAN it cannot prove; revoke a certificate and use it; hammer the CSR endpoint. | Approval-gated flow works end to end and is audited; the self-declared group is overwritten server-side; the unprovable SAN is refused; the revoked certificate is denied everywhere; the bootstrap endpoint rate-limits. (Renewal-banner timing is unit-cited, not waited out.) | GIMLE-034, GIMLE-071, GIMLE-072, GIMLE-258, GIMLE-259, GIMLE-387, GIMLE-624, GIMLE-702, GIMLE-704, GIMLE-743, GIMLE-834, GIMLE-903, GIMLE-905 |
| **SEC-8** | C | With a tenant-A certificate: read/write tenant A's secret, then tenant B's; call Fafnir directly with a forged forwarded-principal header; read Muninn's logs API unauthenticated; push to Andvari with a node certificate. | Fafnir refuses the foreign tenant independently of the control plane; the forged header is ignored unless the peer is the control plane's own certificate; Muninn refuses; the node identity may pull but never push. | GIMLE-285, GIMLE-690, GIMLE-691, GIMLE-310, GIMLE-311, GIMLE-253 |
| **SEC-9** | C A | Console sessions: log in, log out and replay the old cookie; wait out an expired session; fail login repeatedly; flood an ordinary read route from one source past its per-address budget while a second source keeps calling normally; log into Fafnir's and Andvari's consoles separately. | Logout revokes server-side (replay is 401); expiry is explained once in plain language; throttling kicks in with backoff; the flooded source is refused with 429 and a Retry-After while the second source is served throughout, the cluster's own node heartbeats included; each console keeps its own session that is useless against the others. | GIMLE-256, GIMLE-435, GIMLE-436, GIMLE-667, GIMLE-758, GIMLE-018, GIMLE-257, GIMLE-461, GIMLE-467, GIMLE-290, GIMLE-314, GIMLE-781, GIMLE-876 |
| **SEC-10** | C | Export a tenant's whole secret set, import it into a fresh tenant; submit an oversized secret value and an oversized request body. | Export/import is one authorized, audited call and round-trips exactly; both oversized submissions are rejected with the limit named. | GIMLE-735, GIMLE-736 |
| **SEC-11** | C | Export a service with `allowedTenantIds` and call it from an allowed and a disallowed tenant, including by dialling the raw address. | Allowed succeeds; disallowed is refused at the listener even when the caller-side filter is bypassed. | GIMLE-006, GIMLE-192 |
| **SEC-12** | C | Under mTLS with no operator-added RoleBindings: deploy a coordinate-only DaemonSet and a deployment that reads a ConfigMap. | The control plane's own certificate may read the registry and node agents may read their assigned tenants' config out of the box — the two default-RBAC seams hold. | GIMLE-633, GIMLE-634 |

#### Artifacts & Registry

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **ART-1** | A | `artifact push` a module jar, `list`/`get` it, open it in the main console's Artifacts screen and in Andvari's own console (catalog search, version detail, checksum, download with client-side verification, drag-and-drop push, copy-to-clipboard); also try typing a coordinate in Andvari console's Push dialog that doesn't match the jar's own bundled descriptor; read Andvari's status. | The coordinate comes from the jar's own bundled descriptor, never from a typed flag or field -- the CLI derives it and never exposed one to override, and Andvari console's Push dialog auto-fills and locks moduleId/version from the same descriptor the instant a jar with one is picked, leaving no way to push it under a mismatched coordinate; every surface shows the same SHA-256 and size; the download verifies; status reports transport and recent pushes. | GIMLE-265, GIMLE-297, GIMLE-305, GIMLE-381, GIMLE-455, GIMLE-468, GIMLE-469, GIMLE-470, GIMLE-471, GIMLE-472, GIMLE-474, GIMLE-804, GIMLE-843 |
| **ART-2** | A | Push the identical jar again, then a different jar at the same coordinate. | The first is an idempotent no-op; the second is refused with 409 and the stored jar is untouched. | GIMLE-297 |
| **ART-3** | A | `artifact delete` a version, then look for it in the CLI, both consoles and the audit trail. | Gone everywhere; the delete decision is audited with the actor. | GIMLE-305, GIMLE-313, GIMLE-470, GIMLE-861 |
| **ART-4** | A | Deploy a manifest naming only a pushed coordinate with no `artifactPath`. | Admission HEAD-checks the coordinate; the agent pulls through its cache with zero manual placement; a second deploy of the same coordinate is served from the cache. | GIMLE-061, GIMLE-115, GIMLE-248, GIMLE-010 |
| **ART-5** | A | Deploy referencing a coordinate that was never pushed. | Rejected up front at admission with a clear not-found — never scheduled and failing later. | GIMLE-248, GIMLE-858, GIMLE-886 |
| **ART-6** | A | Point a stock `mvn deploy` at Andvari's `/repository/**` for a throwaway artifact, then `mvn install` a project depending on it; fetch the `.jar.sha256` and `maven-metadata.xml` by hand; open the Maven view in Andvari's console. | Ordinary Maven tooling works with only a repository URL; checksums are server-computed; metadata is fresh; the console's Maven view shows the GAV translation. | GIMLE-306, GIMLE-307, GIMLE-308, GIMLE-473, GIMLE-837 |
| **ART-7** | A | Push a jar with no `gimle-module.yaml`, then one far above the configured size limit. | Both are refused with a specific reason and neither leaves a partial file behind. | GIMLE-060, GIMLE-299 |
| **ART-8** | B | With two Andvari replicas: push to one, pull and deploy from the other; then stop one replica and deploy a fresh coordinate. | Peer sync makes the push visible on both; the control plane and agents fail over to the surviving replica without operator action. | GIMLE-303, GIMLE-062, GIMLE-266, GIMLE-858 |

#### Observability & Console

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **OBS-1** | A | `gimle logs <instance> --follow` under fresh traffic; filter by `--category`, level threshold and text; page backwards with the cursor; `-o json`; then the console's Log explorer. | Lines arrive live; APPLICATION vs PLATFORM categorisation is real; filters filter rather than relabel; paging is stable; instance logs are kept per instance on the node. | GIMLE-019, GIMLE-023, GIMLE-026, GIMLE-127, GIMLE-382, GIMLE-451, GIMLE-737, GIMLE-762, GIMLE-832, GIMLE-872, GIMLE-892, GIMLE-897 |
| **OBS-2** | A | Crash a Tier-2 instance hard enough to produce an `hs_err_pid` dump. | The dump is listed and retrievable on the console's Logs screen and via the logs API. | GIMLE-129, GIMLE-452 |
| **OBS-3** | B | Stop the agent supervising a running instance, then read that instance's logs again through the control plane. | Still retrievable through the Muninn fallback for the whole node-death window — the shipped platform and instance logs are complete; asking the fallback to `--follow` is refused with a clear reason rather than hanging. | GIMLE-267, GIMLE-343, GIMLE-130, GIMLE-319, GIMLE-320, GIMLE-321, GIMLE-322 |
| **OBS-4** | A B | Compare a live metric to its history an hour later; `gimle metrics` and `metrics-history`; per-deployment rollup, per-instance error rate, per-module CPU/allocation attribution, the control plane's own request metrics; the Metrics screen's charts. | History persisted through Muninn rather than re-rendering the live snapshot; every CLI and console figure agrees; error rate is real; JFR attribution is per module. | GIMLE-268, GIMLE-323, GIMLE-324, GIMLE-344, GIMLE-449, GIMLE-754, GIMLE-763, GIMLE-764, GIMLE-275, GIMLE-710, GIMLE-351, GIMLE-273, GIMLE-448, GIMLE-097, GIMLE-880, GIMLE-902 |
| **OBS-5** | A B | Trigger a cross-worker fabric call and find its trace; follow it across processes on the Traces screen; `traces-history` from the CLI. | Both hops present and ordered with context propagated across the virtual-thread and wire boundaries. | GIMLE-195, GIMLE-087, GIMLE-325, GIMLE-326, GIMLE-450, GIMLE-741, GIMLE-764, GIMLE-880, GIMLE-881, GIMLE-899, GIMLE-900, GIMLE-901 |
| **OBS-6** | B | Load the Topology screen against multi-node, multi-tenant Fleet. | Placement shown matches `get instances`/`get nodes` independently, including each placement badge's own replica index when the control plane returns instances out of ascending-index order. | GIMLE-447, GIMLE-803 |
| **OBS-7** | A | Walk every console route as a normal logged-in user: Overview, HUD/Signal toggle, theme, Deployments, Jobs, CronJobs, DaemonSets, StatefulSets, Instances (with node/tenant filters), Nodes, Tenants, Config, ConfigMaps, Secrets, SecretMaps, Networking, Access-Control, Artifacts, Audit, Control-Plane, Metrics, Traces, Topology, Logs, Custom Resources, Volumes, LimitRanges, Seal, Applications (health/sync verdicts, filters, and one application's resource tree). | No route errors, blanks or contradicts the CLI's view of the same data. | GIMLE-437, GIMLE-438, GIMLE-444, GIMLE-445, GIMLE-458, GIMLE-459, GIMLE-664, GIMLE-751, GIMLE-750, GIMLE-752, GIMLE-457, GIMLE-440, GIMLE-441, GIMLE-442, GIMLE-443, GIMLE-446, GIMLE-453, GIMLE-454, GIMLE-455, GIMLE-456, GIMLE-585, GIMLE-593, GIMLE-586, GIMLE-587, GIMLE-787, GIMLE-876, GIMLE-877, GIMLE-889 |
| **OBS-8** | A | Log into the Fafnir and Andvari consoles: status overviews, tenant filter via URL param, secrets browsing/reveal/write/destroy, the global error banner on a forced failure. | Each console's status is truthful; the URL filter works; vault-native actions match the CLI; errors surface in the banner rather than vanishing. | GIMLE-461, GIMLE-462, GIMLE-463, GIMLE-464, GIMLE-466, GIMLE-467, GIMLE-468, GIMLE-843 |
| **OBS-9** | A | Read an instance's event timeline in CLI and console; create an AlertRule on a deployment's error rate pointed at a local webhook; trip and clear it; read its durable firing state via GET /alertrules/{name}/firing before, during, and after. | The timeline is complete and ordered; the webhook fires once on crossing and once on resolve; the firing endpoint reports known=false before the rule ever crosses, known=true/firing=true once it fires, and known=true/firing=false once it resolves -- the same answer regardless of which control-plane replica answers or whether one just restarted. | GIMLE-377, GIMLE-711, GIMLE-753, GIMLE-790, GIMLE-830, GIMLE-864, GIMLE-865, GIMLE-867, GIMLE-879, GIMLE-895 |
| **OBS-10** | A | From an instance row, follow the `workerId` deep link into the Metrics and Traces WORKER pickers. | The link lands on that worker's data, matching the agent's own view of which worker hosts it. | GIMLE-647, GIMLE-904 |

#### Journeys — Sample applications

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **JRN-1** | A | Deploy orders-platform whole; place a real order through its web surface; read the reconciliation and report-job tallies. | The order completes through every backing service including the DaemonSet-hosted inventory; the tallies are right; the NetworkPolicy example from its README applies as written. | GIMLE-636, GIMLE-118, GIMLE-566 |
| **JRN-2** | A | Submit mapreduce-wordcount over a real input set. | Completes with correct counts and genuinely parallelises across mapper instances (visible per instance). | GIMLE-052, GIMLE-092, GIMLE-235 |
| **JRN-3** | A | Run order-fulfillment-saga's happy path, then force the shipping step to fail after stock is reserved and payment charged. | Compensations run in reverse order — refund, then stock release — every time; the order ends clearly failed, not partial. | GIMLE-055, GIMLE-093 |
| **JRN-4** | A | Write through session-store, kill its instance with a real native crash, redeploy, read the same keys. | Values survive — the volume, not the instance, held the data; the crash's own dump is listed (OBS-2). | GIMLE-069, GIMLE-630, GIMLE-612 |
| **JRN-5** | B | Deploy node-local-cache across four nodes; call from a co-located caller while a less-loaded remote replica exists; saturate the local one. | Calls stay same-machine while the local replica is not saturated and spill over once every same-machine candidate is busier than the least-loaded remote; its first lookup race logs at INFO, not WARN. | GIMLE-184, GIMLE-638 |
| **JRN-6** | A | Deploy the whole greeter family (provider, consumer, load generator, hello-module) as a smoke check and drive it briefly. | Provider hooks and probes log as APPLICATION output; the consumer's real cross-worker call shows in its log; the load generator turns HTTP into real fabric traffic; hello-module's distinct request/limit values are honoured. | GIMLE-503, GIMLE-504, GIMLE-505, GIMLE-506, GIMLE-051 |
| **JRN-7** | A | Apply the `custom.Greeting` KindDefinition, deploy greeting-operator, apply valid and invalid custom resources, re-apply one unchanged; `gimle kinds`; read status; browse the console's Custom Resources screen; bind the operator's `svc:` principal a narrower role. | Defaults are persisted, unknown keys and bound violations rejected, tenant scope enforced, the identical re-apply is a generation no-op; the operator's status loop updates `observedGeneration`; printColumns render in CLI and console; per-kind RBAC and the workload-identity token gate the operator exactly. | GIMLE-659, GIMLE-660, GIMLE-661, GIMLE-662, GIMLE-663, GIMLE-664, GIMLE-625, GIMLE-787, GIMLE-877 |

#### Exploratory, Chaos & Negative-path

| ID | Env | Objective | Oracle | Requirements |
|---|---|---|---|---|
| **CHAOS-1** | A | Feed `apply -f` a typo'd field, a missing required field, valid YAML of no Gimlé kind, `request > limit`, a malformed quantity, an anti-affinity DaemonSet — for every workload kind. | Distinct, specific, client-side or admission errors — never a stack trace; each kind's parser names the offending field. | GIMLE-003, GIMLE-005, GIMLE-059, GIMLE-172, GIMLE-173, GIMLE-174, GIMLE-175, GIMLE-176, GIMLE-833, GIMLE-857, GIMLE-871, GIMLE-875, GIMLE-885 |
| **CHAOS-2** | A | `gimle delete <kind> <name>` for a name that does not exist, across every kind. | One consistent behaviour (idempotent success or a uniform not-found), not five behaviours for five kinds. | GIMLE-371, GIMLE-372, GIMLE-373, GIMLE-374, GIMLE-375 |
| **CHAOS-3** | A | Compare `-o table` and `-o json` for every `get`, and `-o json` on every mutating verb including node and volume ones. | Same underlying data — no field present in one and silently missing in the other. | GIMLE-388, GIMLE-760, GIMLE-637, GIMLE-873 |
| **CHAOS-4** | A | No `--server` and no `GIMLE_SERVER`; a manifest path that does not exist; `-h` at every verb level; a wrong flag; two positional names to a single-resource verb; switch clusters with `gimle context`. | Every failure exits with a code that names why, shows usage where a flag was wrong, rejects the extra positional rather than truncating, and gives a message a first-time user can act on; contexts switch cleanly. | GIMLE-653, GIMLE-761, GIMLE-665, GIMLE-765, GIMLE-635, GIMLE-894 |
| **CHAOS-5** | B | Fire a scale-up and a delete at the same deployment back to back; then two concurrent applies from two control-plane replicas. | Converges to deleted with no half-scaled zombie; the concurrent applies are generation-guarded so one loses with a conflict rather than a silent lost update. | GIMLE-646, GIMLE-241, GIMLE-874 |
| **CHAOS-6** | B | Kill the current Mimir leader mid-write on the three-replica store, then read the same resources back from every replica while the new leader is still settling. | A new leader is elected, writes resume within a bounded window, and no acknowledged write vanishes. No read anywhere returns a resource the cluster has already deleted or omits one it holds: a replica that cannot answer against a confirmed leadership errors rather than answering from its own copy. | GIMLE-136, GIMLE-148, GIMLE-829, GIMLE-849 |
| **CHAOS-7** | B | Kill one of the two control-plane replicas while a client points at the other. | Zero client disruption; reconciliation continues; the killed replica's health signal fails fast and recovers on restart. | GIMLE-706, GIMLE-393 |
| **CHAOS-8** | C | As a tenant-B account, attempt every action GOV-3 confirmed tenant-A-only, straight at the API rather than through the console; also directly at Fafnir, Andvari and Muninn. | Refused identically (403) everywhere, independent of any UI-level hiding. | GIMLE-250, GIMLE-285, GIMLE-310, GIMLE-691 |
| **CHAOS-9** | B | Unpack the Ragnarok archive; `ragnarok preflight` against Fleet; run a small chaos plan (worker kill through the agent's admin fault API, store bounce, an SSH-inventory link cut where the SSH machine exists); `report` and `replay` it; run a small `stress` workload with the bundled pause module. | Preflight names every unmet precondition; every strike is gated on recovery and lands in a replayable ledger; the report is complete; stress gates on its declared thresholds and writes a diffable summary. | GIMLE-641, GIMLE-639, GIMLE-533, GIMLE-548, GIMLE-640, GIMLE-645, GIMLE-643, GIMLE-644, GIMLE-642 |
| **CHAOS-10** | B | Stop the control plane for two minutes while workloads run, then restart it. | Running instances keep serving; Skald degrades only after its staleness window; on restart everything reconverges with no duplicate or lost instance. | GIMLE-686, GIMLE-706, GIMLE-848 |
<!-- /forseti:generated -->

## 6. Run plan

The Lead runs the pass in waves. A wave's agents are spawned together, not one after another, and a
wave completes only when every agent in it has filed its findings (or its not-executed report).
Later waves inherit clusters already carrying state worth colliding with rather than three empty
clusters running in isolation.

| Wave | Runs | Against | Depends on |
|---|---|---|---|
| **0 — Bring-up** | OPS-1…5, OPS-8; DEV-1, DEV-2 | Forge builds; Midgard, Fleet and Vault are stood up and declared ready or "not built this run" | The release candidate build |
| **1 — Baseline** | Midgard: DEP, BATCH, ART, OBS (Midgard rows), JRN, GOV/NET/SEC (Midgard rows), DEV-3…6. Fleet: SCHED-1…3, SCHED-7…10, NET (Fleet rows), BATCH-3/5 (Fleet rows), ART-8, OBS-3…6. Vault: SEC, GOV (Vault rows), NET-5/6/10 | Each environment's own personas in parallel, populating its own slice of state | Wave 0 |
| **2 — Pressure** | OPS-6/7/9…15; SCHED-4…6; CHAOS-1…10 | All three environments, colliding with Wave 1's live state — every process kill, membership change, and upgrade happens here | Wave 1 |
| **3 — Verdict** | LEAD | Nothing running; the raw findings only | Wave 2 |

**Parallelism.** Environments are independent clusters, so Wave 1's three environment groups run
concurrently. Within an environment, its personas run concurrently against the same cluster. The Lead
owns the schedule and never runs a scenario itself.

**Evidence handling.** Each agent writes its findings and its per-scenario execution record
(executed / not executed + reason, pass / finding IDs) to its own file. The Lead reads those files
and nothing else — an agent's chat narration is not evidence.

**Cost.** A full pass builds three clusters and runs a dozen agents against them, several for hours.
It is run deliberately, before a release, by someone who has budgeted for it — never triggered
automatically by a commit.

## 7. Reporting and deduplication

### Raw finding template

Every agent files every finding in this shape, regardless of persona. The consolidated report is
rendered from it, so no prose findings outside it.

```
id:            <persona>-<sequence>                      e.g. SEC-03
title:         one line stating the wrong behaviour, not the steps
severity:      blocker | major | minor | cosmetic
scenario:      the scenario ID(s) that surfaced it        e.g. SEC-7
requirements:  the GIMLE-NNN ID(s) it bears on            e.g. GIMLE-624
environment:   G | A | B | C
steps:         numbered, exactly what was run or clicked
expected:      what a normal user would expect
actual:        what happened instead
evidence:      command output / log excerpt / HTTP response / screenshot reference
reported_by:   agent name
```

### Severity taxonomy

| Level | Meaning |
|---|---|
| **Blocker** | Data loss, a stuck or unrecoverable state with no operator path out, or a security boundary that does not hold. |
| **Major** | A documented capability does not work, or works but reports success while doing the wrong thing. |
| **Minor** | Works, but the error message, output shape, or edge-case behaviour is wrong or inconsistent. |
| **Cosmetic** | A console or CLI presentation issue with no functional consequence. |

### Deduplication protocol (Lead only)

1. **Fingerprint** every raw finding as *(requirement IDs, environment, a normalised one-line
   symptom)*, stripped of anything scenario-specific.
2. **Group** findings sharing a fingerprint regardless of which agent or scenario surfaced them.
3. **Merge, don't drop.** A merged issue keeps the maximum severity any contributing agent assigned
   and the union of their reproduction steps, and lists every contributing raw ID.
4. **Split on inspection, not on symptom alone.** Identical wording across two resource kinds is one
   issue only if the same code path is plausibly responsible — the Lead may reason about that from
   documented architecture, still not from reading source.
5. **Never silently resolve a disagreement** between two agents about severity or bug-or-not. Record
   both readings and leave the call to the humans reading the report.
6. **A not-a-bug verdict needs a reason** a customer would accept, cited to documented behaviour.

### The findings artifact

One per run, dated and named for the release candidate. Published where the team reads reports,
never committed to this repository. Sections, in order:

1. **Executive summary** — the five most severe findings, delivered vs. designed reach, environments
   built.
2. **Deduplication and cross-checks** — every merge and every adjudicated disagreement, by raw ID.
3. **Blocker / Major / Minor / Cosmetic** — one subsection each; every issue in the template's shape
   with merged reproduction steps.
4. **What held up** — capabilities exercised under pressure that behaved exactly as documented.
5. **Coverage ledger** — per scenario: executed or not, against which environment, pass or finding
   IDs; per environment: built as designed, built reduced, or not built.
6. **Method, environments, roster, run plan** — as executed, with every deviation from this doctrine
   named.

## 8. Non-goals

Excluded from the user-observable denominator, not counted as gaps:

- Kernel-level resource enforcement (cgroup v2) and Tier 3 namespace isolation — deliberately not
  built yet; nothing for any mechanism to observe.
- Raft, gossip and fabric wire-level correctness, JFR accounting internals, codec correctness —
  invisible from outside the process and already the job of the unit and Holmgang suites, which the
  internal-group table cites row by row.
- Load and scale ceiling-finding (how many replicas before the cluster falls over) — Surtr's job, a
  performance-engineering exercise, not a functional QA pass. CHAOS-9 runs Surtr *small* to prove the
  tool works, not to find the ceiling.
- CI, build tooling internals, and the Maven plugin's implementation — a user runs the goals, never
  inspects them.
- The consoles' visual design — only whether they show correct, consistent data.

Also out of bounds during a pass: fuzzing, unbounded chaos duration, and any destructive action not
named in the catalog.

## 9. Classification tables

### Residual — user-observable requirements no fleet scenario reaches

<!-- forseti:generated residual -->
| ID | Feature | Mechanism | Evidence |
|---|---|---|---|
| GIMLE-363 | Route-table config DSL parsing | UNIT | `GatewayRouteConfigTest#parses_a_mix_of_fabric_and_vessel_routes_ignoring_blank_lines_and_comments`, `#an_unknown_kind_token_is_rejected`, `#a_fabric_line_with_the_wrong_number_of_fields_is_rejected`, `#a_non_integer_fabric_version_is_rejected`, `#a_fabric_param_type_outside_the_v1_restriction_is_rejected_at_parse_time` |
| GIMLE-805 | CliExtension seam dispatches an unrecognized verb to a ServiceLoader-discovered provider | UNIT | gimle-cli's CliExtensionSeamTest (classpath discovery via a test-only provider, dispatch, help folding, unknown-verb error preserved) and gimle-hugin's HuginExtensionTest. A further CliExtensionSeamTest case pins the scoped `-h` output. |
| GIMLE-807 | `gimle top` renders a live, read-only cluster view of nodes and instances | HOLMGANG | `terminal-view.feature` — A running deployment appears in the rendered frame with its real state |
| GIMLE-808 | A failed poll keeps the last good rows and ages them rather than clearing the screen | UNIT | gimle-hugin's ClusterPollerTest (failure keeps rows and age, recovery clears the marking, the pre-first-poll state, pause/resume) and ClusterScreenTest's stale status-line assertion. |
| GIMLE-809 | Instance drill-down with lifecycle timeline and a live log tail | UNIT | gimle-hugin's InstanceWatcherTest (backlog-then-follow ordering, the resume cursor, tenant scoping on every route, a failing route, a stream ending on its own) and InstanceScreenTest, plus SnapshotReaderTest's tier/limit parsing cases and InstanceScreenTest's per-tier rendering cases. |
| GIMLE-810 | Keyboard interaction: selection, filter, pause, refresh, help, and quit restoring the terminal | UNIT | gimle-hugin's UiStateTest. The JLine adapter itself (raw mode, key decoding, resize) is deliberately untested and kept minimal for that reason. Plus UiStateTest's positional-sort cases and InstanceScreenTest's log-filter cases. |
| GIMLE-811 | Terminal colour is the console's own tokens, degrading to 256-colour and to none | UNIT | gimle-hugin's StatusVariantTest (pins every lifecycle state against the console's mapping and fails when the platform adds one the mapping misses) and PainterTest (exact truecolor output, the 256-colour approximation, and NO_COLOR emitting nothing). |
| GIMLE-812 | The terminal view ships in the CLI archives and is removable in one directory delete | UNIT | HuginExtensionTest asserts classpath discovery of the shipped provider. The archive layout is verified by building the distribution, not by a test. |
| GIMLE-813 | The terminal view reports a workload short of replicas, over quota, or rejected by a LimitRange | HOLMGANG | `terminal-view.feature` — A workload the scheduler cannot place is reported rather than silently short; `terminal-view.feature` — A healthy cluster reports nothing unsettled |
| GIMLE-814 | DaemonSet and StatefulSet instances share the terminal view's instance table with Deployments | UNIT | gimle-hugin's SnapshotReaderTest (all three kinds in one ordered table, an unserved kind costing only its own rows, a DaemonSet's shortfall read from its computed desired count, and a workload carrying neither figure) and ClusterScreenTest (the KIND column, and its removal on a narrow terminal). |
| GIMLE-815 | A services screen showing each Service's live endpoint resolution | HOLMGANG | `terminal-view.feature` — A Service resolving to no endpoints is reported as the finding it is |
| GIMLE-816 | An activity view of what has been done to the cluster, over the audit trail | UNIT | gimle-hugin's ActivityReaderTest and ActivityScreenTest. |
| GIMLE-817 | The activity view reads three cluster records: authorization, lifecycle and alerts | UNIT | gimle-hugin's ActivityReaderTest (all three feeds' parses and their degraded shapes) and ActivityScreenTest (per-feed labelling, headings, colour and width). |
| GIMLE-818 | The terminal view browses every collection the control plane lists, including registered custom kinds | UNIT | gimle-hugin's ResourceCatalogTest (resolution, custom-kind discovery, collision, degraded discovery, suggestions), ResourceReaderTest (column resolution, the wrapped collection, permission and failure paths), ResourceScreenTest (header, label, filter, permission message, width) and JsonPathTest (the dotted path walk). |
| GIMLE-819 | The terminal view describes a selected resource as YAML without re-reading it | UNIT | gimle-hugin's YamlTest (nesting, lists, empty containers, null, quoting, escaping) and DescribeScreenTest (the whole object, the title, scrolling and its clamps, width, colour). |
| GIMLE-820 | The terminal view lists what it can open, and can be pointed at another control plane | UNIT | gimle-cli's ClusterReaderContextTest (context resolution, bare addresses, precedence, refusal) and gimle-hugin's KindsScreenTest and UiStateTest. |
| GIMLE-821 | The terminal view joins Services to the instances behind them and names the gaps | UNIT | gimle-hugin's XrayTest (the join, both findings, tenant scoping, ancestor-preserving filter) and XrayScreenTest (indentation, wording, counts, width, colour). |
| GIMLE-822 | The terminal view reads the control plane's own health alongside what it is running | UNIT | gimle-hugin's PulseReaderTest (health, unreachable, the rollup and its permission, orderings) and PulseScreenTest (the wording, both failure directions, width, colour). |
| GIMLE-823 | The terminal view reads a worker's shipped traces for the instance it is inspecting | UNIT | gimle-hugin's TraceReaderTest (parsing, grouping, the degraded shapes) and TraceScreenTest (the tree shape, the findings, width, colour). |
| GIMLE-824 | The terminal view narrows every screen to one tenant | UNIT | gimle-hugin's TenantScopeTest (each snapshot's narrowing and what it deliberately leaves alone) and UiStateTest (the scope's lifecycle). |
| GIMLE-825 | The terminal view scans the cluster for what is wrong | UNIT | gimle-hugin's ScanTest (each finding, its severity, and the cases deliberately not reported) and ScanScreenTest (ordering, counts, the clean-cluster wording and the filtered-to-nothing wording). |
| GIMLE-826 | The terminal view shows what the calling certificate may do | UNIT | gimle-hugin's PermissionReaderTest (the vocabulary-driven grid, silence never read as denial, the answering identity, escaping and the tenant scope) and PermissionScreenTest (the words in each cell, the unidentified-caller warning, and the unreadable-grid wording). |
| GIMLE-827 | The terminal view browses a tenant's own config and secret holdings | UNIT | gimle-hugin's ResourceReaderTest (the tenant-scoped route, the redaction in both the cells and the raw object, bare-name responses, and a secret listing's columns). |
| GIMLE-828 | The terminal view reads a config key's, ConfigMap's or secret's revision history | UNIT | gimle-hugin's VersionReaderTest (all four ledger shapes, ordering, no-ledger against empty, escaping) and VersionScreenTest (the in-effect label, blank rather than invented author and time, and the deleted marker). |
| GIMLE-958 | StatefulSet workloads can carry an AutoscalePolicy, identically to Deployment | UNIT | gimle-mimir and gimle-controlplane full module suites re-verified after the change (0 failures). Frontend: tsc/eslint/vitest/vite build all clean. |
| GIMLE-959 | StatefulSet workloads can carry a DisruptionBudget, and OrderedReady rolling updates now honor a configurable maxUnavailable | UNIT | `StatefulSetReconcilerTest` (21 tests, including the pre-existing GIMLE-682 flap-immunity pair, updated for the new same-tick budget-refill behavior) plus gimle-mimir's own StateStore/RaftCodec/DomainCodec round-trip tests -- full gimle-mimir and gimle-controlplane module suites re-verified (0 failures). Frontend: tsc/eslint/vitest/vite build all clean. |
<!-- /forseti:generated -->

### Exclusions — out of scope, with reasons

<!-- forseti:generated exclusions -->
| Reason | Why it is excluded | Requirements |
|---|---|---|
| `deferred` | Deliberately not built yet (kernel-level cgroup v2 enforcement, Tier 3 namespaces). Nothing exists for any test mechanism to exercise; re-enters scope when the code lands. | 4: GIMLE-066, GIMLE-067, GIMLE-107, GIMLE-108 |
| `documented-limitation` | The requirement records a known, documented scope limitation or a gap since superseded by a later requirement, not a capability a user can rely on. | 4: GIMLE-338, GIMLE-369, GIMLE-370, GIMLE-549 |
| `test-harness` | The requirement *is* a test asset or test-infrastructure component (a suite, a fixture, a harness primitive, a Gherkin scenario, a simulation). Its validation is running it, which the suites cited on each row already do; counting it as a platform capability would inflate the denominator with things a user never touches. | 61: GIMLE-049, GIMLE-426, GIMLE-460, GIMLE-496, GIMLE-497, GIMLE-498, GIMLE-499, GIMLE-500, GIMLE-501, GIMLE-502, GIMLE-507, GIMLE-508, GIMLE-509, GIMLE-510, GIMLE-511, GIMLE-512, GIMLE-513, GIMLE-514, GIMLE-515, GIMLE-516, GIMLE-517, GIMLE-518, GIMLE-519, GIMLE-520, GIMLE-521, GIMLE-522, GIMLE-523, GIMLE-524, GIMLE-525, GIMLE-526, GIMLE-527, GIMLE-528, GIMLE-529, GIMLE-530, GIMLE-531, GIMLE-532, GIMLE-534, GIMLE-535, GIMLE-536, GIMLE-537, GIMLE-538, GIMLE-539, GIMLE-540, GIMLE-541, GIMLE-542, GIMLE-543, GIMLE-544, GIMLE-545, GIMLE-546, GIMLE-547, GIMLE-550, GIMLE-551, GIMLE-552, GIMLE-553, GIMLE-554, GIMLE-555, GIMLE-556, GIMLE-557, GIMLE-558, GIMLE-559, GIMLE-565 |
| `build-time-only` | Build- or development-time configuration with no presence in a deployed cluster or a shipped archive. Verifying it means running the build or the dev server, which no fleet environment does. | 1: GIMLE-878 |
<!-- /forseti:generated -->

### Internal groups — covered by unit tests and Holmgang, not the fleet

<!-- forseti:generated internal-groups -->
| Group | Why a black-box tester cannot reach it | Requirements | Cited by |
|---|---|---:|---|
| `wire-protocol-codec` | Wire framing, codecs, marshalling, protocol shapes. Invisible from outside the process; correctness is a unit-test property. | 19 | Holmgang 0, unit 19 |
| `raft-internals` | Raft safety/liveness mechanics below the level a user observes (they observe 'the cluster kept accepting writes', which the fleet does test). | 24 | Holmgang 15, unit 9 |
| `gossip-internals` | SWIM protocol mechanics. The fleet observes membership converging and dead nodes disappearing; the protocol's own guarantees are unit/Holmgang territory. | 9 | Holmgang 0, unit 9 |
| `fabric-internals` | Load-balancer selection math, breaker scoring, timeouts, bounded concurrency, retry safety. The fleet observes the outcome (ejection, spillover), not the algorithm. | 12 | Holmgang 0, unit 12 |
| `jpms-and-classloading` | ModuleLayer construction, readability grants, module-info wiring, JFR retaining-path attribution. Observable only as 'the module loaded and ran', which the fleet covers elsewhere. | 12 | Holmgang 0, unit 12 |
| `process-plumbing` | Control-channel bootstrap, tick loops, executor isolation, stdout draining, lease bookkeeping, ledger persistence. Mechanism, not a user-facing capability. | 42 | Holmgang 2, unit 40 |
| `crypto-and-key-material` | Cipher construction, PEM handling, key-file permissions, hashing parameters, token signing. Verified by unit tests; the fleet verifies the behaviours built on them. | 12 | Holmgang 8, unit 4 |
| `tls-material-reload` | Zero-downtime TLS reload and rotation-check plumbing inside each process kind. Needs certificate expiry to observe; covered by unit tests against the real code. | 7 | Holmgang 0, unit 7 |
| `server-side-guards` | Authorization-engine internals, throttles and principal-resolution tiers whose user-visible effect (a 403, a 429) the fleet does test through the scenarios that trigger them. | 9 | Holmgang 1, unit 8 |
| `storage-engine` | Day-file layout, path sanitization, atomic writes, retention sweeps, integrity scrubs, quarantine. Time- or disk-tampering-driven; unit-tested against the real store code. | 16 | Holmgang 0, unit 16 |
| `shipping-and-telemetry-plumbing` | Exporter/shipper installation, fan-out, cursors, meter wrappers, sampling knobs. The fleet observes data arriving in Muninn and on the console screens. | 16 | Holmgang 0, unit 16 |
| `gateway-internals` | Endpoint TTL cache, stale-cache fallback, lifecycle-hook bootstrap. The fleet tests routing outcomes. | 3 | Holmgang 0, unit 3 |
| `tooling-internals` | Maven-plugin and Saga back-end internals (classpath resolution, git capture, report discovery, crash-safe append, flake-ledger derivation). Unit-tested; the goals and screens built on them are fleet-tested. | 13 | Holmgang 0, unit 13 |
| `abstraction-seams` | Pluggable interfaces with one implementation. Nothing to observe beyond the implementation, which is covered on its own row. | 3 | Holmgang 0, unit 3 |
| `api-only-no-client` | A real, tested platform API surface with no CLI subcommand or console screen wired to it yet -- the fleet interacts through real products (CLI, console, raw API calls an operator persona would plausibly make), and nothing in either product surfaces this capability today, so no fleet objective can reach it through anything but a hand-crafted HTTP call. Re-enters scope once a client consumes it. | 1 | Holmgang 0, unit 1 |
| `admission-and-density-control` | A real, shipped bugfix to an internal admission/packing mechanism, closing a Forseti finding (M1, M65) directly -- exercised today by targeted JUnit integration tests (a real ApiServer/AgentMain under concurrent load), not yet by a dedicated fleet scenario driving the same pressure against a live cluster. | 3 | Holmgang 0, unit 3 |
| `tenant-and-proxy-hardening` | A real, shipped bugfix closing a Forseti finding directly (M61's agent-side same-node cross-tenant supervision collision, M41's control-plane follow-log-proxy hang) -- exercised today by targeted JUnit tests against a real AgentMain/ApiServer, not yet by a dedicated fleet scenario driving the same collision or agent-down condition against a live cluster. | 2 | Holmgang 0, unit 2 |
<!-- /forseti:generated -->

## 10. Release history

One row per pass, appended by the Lead at the end of the run. The findings artifact is the record;
this table is the index.

| Release | Date | Environments built | Delivered reach | Findings (B / Ma / Mi / C) | Artifact |
|---|---|---|---|---|---|
| _none yet_ | | | | | |

## Appendix A — Full requirement coverage table

Every `GIMLE-NNN`, its class, the mechanism that covers it, and the evidence: fleet scenario IDs,
a Holmgang feature and scenario, a unit-test citation, or the exclusion reason.

<!-- forseti:generated coverage-table -->
| ID | Module | Feature | Class | Mechanism | Evidence |
|---|---|---|---|---|---|
| GIMLE-001 | `gimle-core` | Semantic module versioning | observable | FLEET | DEP-2, DEP-13 |
| GIMLE-002 | `gimle-core` | Version range constraint matching | observable | FLEET | DEP-13 |
| GIMLE-003 | `gimle-core` | Module descriptor validation (request ≤ limit invariant) | observable | FLEET | DEV-1, CHAOS-1 |
| GIMLE-004 | `gimle-core` | Tiered isolation model (TIER_1/TIER_2/TIER_3) | observable | FLEET | DEP-5 |
| GIMLE-005 | `gimle-core` | Kubernetes-shaped resource quantity parsing | observable | FLEET | DEV-1, CHAOS-1 |
| GIMLE-006 | `gimle-core` | Tenant-scoped service export | observable | FLEET | SEC-11 |
| GIMLE-007 | `gimle-core` | StatefulSet-shaped persistent volume declaration | observable | FLEET | BATCH-5 |
| GIMLE-008 | `gimle-core` | Health probe configuration: probe classes, initial delay, and per-module interval/timeout/failure threshold | observable | FLEET | DEV-1, DEP-8 |
| GIMLE-009 | `gimle-core` | Vessel hosting mode (plain-process workload) | observable | FLEET | BATCH-8 |
| GIMLE-010 | `gimle-core` | Artifact-registry vs local-path reference resolution | observable | FLEET | DEV-3, ART-4 |
| GIMLE-011 | `gimle-core` | RBAC domain model (resources, verbs, permissions, roles, bindings) | observable | FLEET | GOV-3 |
| GIMLE-012 | `gimle-core` | Built-in cluster-admin role and operator/node certificate groups | observable | FLEET | GOV-3 |
| GIMLE-013 | `gimle-core` | Console password hashing (PBKDF2-HMAC-SHA256) | internal | HOLMGANG | `console-security.feature` — A console login round-trips the right password and rejects the wrong one |
| GIMLE-014 | `gimle-core` | Mutual-TLS SSLContext construction | internal | HOLMGANG | `mtls.feature` — The cluster functions end to end over mutual TLS |
| GIMLE-015 | `gimle-core` | Cluster-wide transport protocol switch (plaintext/TLS) | observable | FLEET | OPS-5 |
| GIMLE-016 | `gimle-core` | Stateless HMAC-signed console session tokens | internal | HOLMGANG | `console-security.feature` — A console login round-trips the right password and rejects the wrong one |
| GIMLE-017 | `gimle-core` | Session-signing key file load-or-create with owner-only permissions | internal | UNIT | `SessionKeyFileManagerTest` (generates_on_first_run_reuses_on_later, rejects corrupted/empty key file) |
| GIMLE-018 | `gimle-core` | Per-key exponential-backoff login throttle | observable | FLEET | SEC-9 |
| GIMLE-019 | `gimle-core` | Structured JSON log encoding with APPLICATION/PLATFORM categorization | observable | FLEET | OBS-1 |
| GIMLE-020 | `gimle-core` | Human-readable colored console log encoding | observable | FLEET | OPS-2 |
| GIMLE-021 | `gimle-core` | Runtime-switchable console log format (text default, JSON opt-in) | observable | FLEET | OPS-4, OPS-15 |
| GIMLE-022 | `gimle-core` | MDC-tagged proxying for same-worker and probe-loop invocations | internal | UNIT | `InstanceMdcContextTest` (tag_proxy sets/restores MDC, restores on throw, run_tagged restores previous value) |
| GIMLE-023 | `gimle-core` | Per-instance sifted log files | observable | FLEET | OBS-1 |
| GIMLE-024 | `gimle-core` | Platform (non-instance) log file appender | observable | FLEET | OPS-15 |
| GIMLE-025 | `gimle-core` | Kubelet-style size/count log rotation | internal | UNIT | `LogRotationTest` (rolls over by size and evicts oldest, cursor paging/follow resolve correctly across rotation) |
| GIMLE-026 | `gimle-core` | Cursor-based log paging and live-follow streaming | observable | FLEET | OBS-1 |
| GIMLE-027 | `gimle-core` | Startup banner rendering with terminal color/Unicode auto-detection | observable | FLEET | OPS-2 |
| GIMLE-028 | `gimle-core` | Single-write length-prefixed wire framing | internal | UNIT | NONE recorded in the baseline |
| GIMLE-029 | `gimle-core` | Hand-rolled JSON parser/writer | internal | UNIT | `JsonTest` (nested objects/arrays, negative/exponent numbers, escaped strings, round trip, escapes special chars, malformed throws) |
| GIMLE-030 | `gimle-core` | Agent↔worker control-channel protocol and codec | internal | UNIT | `ControlMessageCodecTest` (module id with qualifier round trips, rejects empty line/unknown type/missing fields/malformed module id) |
| GIMLE-031 | `gimle-core` | Node registration/heartbeat/capacity-reporting protocol | internal | UNIT | NONE recorded in the baseline |
| GIMLE-032 | `gimle-core` | Instance lifecycle event log model | observable | FLEET | DEP-1 |
| GIMLE-033 | `gimle-core` | Cross-resource audit trail model | observable | FLEET | GOV-5 |
| GIMLE-034 | `gimle-core` | Certificate bootstrap (CSR) request/response protocol | observable | FLEET | SEC-7 |
| GIMLE-035 | `gimle-core` | Assigned-instance work-order model (incl. in-place rename and vessel dispatch) | internal | UNIT | NONE recorded in the baseline |
| GIMLE-036 | `gimle-core` | Bounded-retry-with-backoff restart policy (CrashLoopBackOff-equivalent) | observable | FLEET | DEP-8, SCHED-5 |
| GIMLE-037 | `gimle-core` | Tenant identity and resource quota model | observable | FLEET | GOV-1 |
| GIMLE-038 | `gimle-core` | Tenant-scoped config/secret entry model | observable | FLEET | SEC-1 |
| GIMLE-039 | `gimle-core` | Bundled SPA static-asset resolution from classpath | observable | FLEET | OPS-2 |
| GIMLE-040 | `gimle-core` | SPA static file serving with client-side-route fallback | observable | FLEET | OPS-2 |
| GIMLE-041 | `gimle-core` | Saga test-run event model and NDJSON codec | internal | UNIT | `SagaEventCodecTest` (single line naming type first, absent fields omitted) |
| GIMLE-042 | `gimle-core` | Stable failure-signature hashing for flaky-test clustering | internal | UNIT | `FailureSignatureTest` (run-specific numbers don't change signature, hex ids don't change it, different exception types differ, different messages differ, oversized messages truncated) |
| GIMLE-043 | `gimle-module` | Module dependency resolution with cycle detection | observable | FLEET | DEP-13 |
| GIMLE-044 | `gimle-module` | Module registry (install bookkeeping, idempotent re-install, content-mismatch rejection) | internal | UNIT | `ModuleRegistryTest` (register stores as installed, idempotent identical re-register, rejects differing re-register, unknown module id throws, named transitions update state, mark_failed reachable) |
| GIMLE-045 | `gimle-module` | Module lifecycle state machine (INSTALLED→RESOLVED→STARTING→ACTIVE→STOPPING→UNINSTALLED, plus FAILED/COMPLETED) | observable | FLEET | DEP-1 |
| GIMLE-046 | `gimle-module` | Dynamic per-module-version JPMS ModuleLayer construction | internal | UNIT | `ModuleLayerFactoryTest` (builds dependency-free layer, dependent layer calls into exported API, two versions get distinct layers, missing parent layer fails with GimleResolutionException) |
| GIMLE-047 | `gimle-module` | Unnamed-module readability grant for bundled hooks/probes | internal | UNIT | gimle-worker's `RealBundledHookAndProbeInvocationTest`; this module's own `ModuleLayerFactoryTest` exercises the general mechanism |
| GIMLE-048 | `gimle-module` | Classloader leak detection via PhantomReference | observable | FLEET | DEP-4 |
| GIMLE-049 | `gimle-module` | Repeated-redeploy flat-metaspace acceptance test | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-050 | `gimle-module` | Best-effort leak retaining-path attribution via JFR OldObjectSample | internal | UNIT | `RetainingPathAttributionTest#leak_detector_surfaces_a_retaining_path_when_the_worker_jvm_enables_path_to_gc_roots` |
| GIMLE-051 | `gimle-module` | Module lifecycle hooks (reflectively instantiated, JPMS-exported) | observable | FLEET | JRN-6 |
| GIMLE-052 | `gimle-module` | Job-kind run-to-completion hooks | observable | FLEET | BATCH-1, JRN-2 |
| GIMLE-053 | `gimle-module` | Module context API (in-flight tracking, service lookup, config, data dir, control-plane relay) | observable | FLEET | DEV-6 |
| GIMLE-054 | `gimle-module` | In-worker round-robin service registry with version-aware cutover | observable | FLEET | DEP-2 |
| GIMLE-055 | `gimle-module` | Cross-tier name-driven service invocation | observable | FLEET | NET-9, JRN-3 |
| GIMLE-056 | `gimle-module` | Same-worker cross-module service publish/discover | observable | FLEET | NET-9 |
| GIMLE-057 | `gimle-module` | Graceful drain-then-dispose stop with deadline | observable | FLEET | DEP-6 |
| GIMLE-058 | `gimle-module` | Hot redeploy (old/new version coexistence with pinned dependent wiring) | observable | FLEET | DEP-2 |
| GIMLE-059 | `gimle-module` | gimle-module.yaml descriptor parsing and validation | observable | FLEET | DEV-1, CHAOS-1 |
| GIMLE-060 | `gimle-module` | Module artifact reading — real-JPMS-module and descriptor-presence validation | observable | FLEET | DEV-1, ART-7 |
| GIMLE-061 | `gimle-module` | Andvari artifact-registry pull-through cache | observable | FLEET | ART-4 |
| GIMLE-062 | `gimle-module` | Multi-endpoint Andvari failover on pull | observable | FLEET | ART-8 |
| GIMLE-063 | `gimle-module` | Health probe interfaces (liveness/readiness) | observable | FLEET | DEP-7 |
| GIMLE-064 | `gimle-os` | Pluggable resource-limiter abstraction | internal | UNIT | exercised via `PortableJvmFlagsResourceLimiterTest` |
| GIMLE-065 | `gimle-os` | Portable JVM-flags resource enforcement (Tier 1/Tier 2) | observable | FLEET | DEP-5 |
| GIMLE-066 | `gimle-os` | Tier 3 (namespace isolation) — deliberately unsupported by the current limiter | out-of-scope | OUT OF SCOPE | deferred |
| GIMLE-067 | `gimle-os` | Kernel-level (cgroup v2) resource enforcement — deferred | out-of-scope | OUT OF SCOPE | deferred |
| GIMLE-068 | `gimle-os` | Pluggable persistent-volume-manager abstraction | internal | UNIT | exercised via `LocalDiskVolumeManagerTest` |
| GIMLE-069 | `gimle-os` | Local-disk persistent volume allocation for StatefulSet-shaped instances | observable | FLEET | BATCH-5, JRN-4 |
| GIMLE-070 | `gimle-pki` | Self-signed cluster CA generation | observable | FLEET | OPS-5 |
| GIMLE-071 | `gimle-pki` | CSR-to-leaf-certificate signing with signature verification | observable | FLEET | SEC-7 |
| GIMLE-072 | `gimle-pki` | Server-stamped Subject override on signing (prevents self-declared privileged group) | observable | FLEET | SEC-7 |
| GIMLE-073 | `gimle-pki` | CSR generation with typed Subject Alternative Names (DNS and IP) | observable | FLEET | OPS-5 |
| GIMLE-074 | `gimle-pki` | Hand-rolled PEM encode/decode for certs, CSRs, and private keys | internal | UNIT | exercised indirectly throughout CertificateAuthorityTest (`generated_leaf_certificate_is_readable_by_openssl`, `certificate_survives_a_keystore_round_trip`); NONE dedicated PemTest |
| GIMLE-075 | `gimle-pki` | Randomized certificate-renewal scheduling (anti-thundering-herd) | internal | UNIT | NONE recorded in the baseline |
| GIMLE-076 | `gimle-pki` | Own-certificate rotation over mTLS via CSR bootstrap endpoint | internal | HOLMGANG | `secrets-and-pki.feature` — A node rotates its own certificate over mTLS and keeps its identity |
| GIMLE-077 | `gimle-pki` | X.500 Subject utilities: server-side O= stamping and Principal derivation | internal | HOLMGANG | `secrets-and-pki.feature` — A node-join CSR that self-declares a privileged group is stamped with the node group instead; `secrets-and-pki.feature` — Fafnir independently authorizes node-scoped secret reads by tenant assignment |
| GIMLE-078 | `gimle-pki` | Cluster PKI bootstrap CLI (`mvn gimle:tls-init`) | observable | FLEET | DEV-2 |
| GIMLE-079 | `gimle-worker` | Worker JVM control-channel bootstrap | internal | UNIT | `ControlChannelClientTest#connect_with_retry_succeeds_once_the_listener_is_up`, `#connect_with_retry_gives_up_after_its_timeout_if_nothing_ever_listens`, `AgentWorkerIntegrationTest#agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel` (gimle-agent) |
| GIMLE-080 | `gimle-worker` | Newline-delimited control-channel wire protocol (worker side) | internal | UNIT | `ControlChannelClientTest#a_sent_message_is_received_intact_on_the_other_end`, `#receive_returns_empty_once_the_peer_closes_the_connection` |
| GIMLE-081 | `gimle-worker` | Module install/resolve/start/stop/uninstall command dispatch | internal | UNIT | NONE recorded in the baseline |
| GIMLE-082 | `gimle-worker` | Instance identity registration and rename-in-place | internal | UNIT | NONE recorded in the baseline |
| GIMLE-083 | `gimle-worker` | Per-instance MDC log tagging for lifecycle/hook/probe/request-dispatch logging | internal | UNIT | `BoundedModuleSchedulerTest#mdc_tags_are_visible_inside_a_tagged_submission`, `#empty_mdc_tags_leave_the_submission_untagged`; `InstanceTaggingServiceRegistryTest#registers_untagged_when_no_identity_is_known_for_the_owner`, `#registers_a_tagging_proxy_when_identity_is_known` |
| GIMLE-084 | `gimle-worker` | Durable InstanceEvent emission per lifecycle transition | observable | FLEET | DEP-1 |
| GIMLE-085 | `gimle-worker` | Classloader leak detection on undeploy | observable | FLEET | DEP-4 |
| GIMLE-086 | `gimle-worker` | Per-module bounded virtual-thread scheduler | internal | UNIT | `BoundedModuleSchedulerTest#concurrency_bound_limits_how_many_tasks_run_at_once`, `#closed_scheduler_rejects_further_submissions`, `#max_concurrency_below_one_is_rejected`, `#submitted_task_runs_and_returns_its_result`, `#a_thrown_exception_surfaces_through_the_future` |
| GIMLE-087 | `gimle-worker` | OpenTelemetry context propagation across virtual-thread dispatch | observable | FLEET | OBS-5 |
| GIMLE-088 | `gimle-worker` | Liveness/readiness probe loop with timeout and initial-delay | observable | FLEET | DEP-7, DEP-8 |
| GIMLE-089 | `gimle-worker` | Module-tier self-healing — restart on repeated liveness failure with backoff and budget exhaustion | observable | FLEET | DEP-8, SCHED-4 |
| GIMLE-090 | `gimle-worker` | Readiness-driven service registry availability (without restart) | observable | FLEET | DEP-7 |
| GIMLE-091 | `gimle-worker` | Stopping/Uninstalled teardown of scheduler, probes, and service registry | internal | UNIT | `WorkerRuntimeTest#stopping_a_module_makes_its_service_unreachable_and_removes_it_from_the_registry`, `#on_uninstalled_fires_the_close_callback_exactly_once_with_the_registered_identity` |
| GIMLE-092 | `gimle-worker` | Job-kind module execution (run-to-completion, not probed) | observable | FLEET | BATCH-1, JRN-2 |
| GIMLE-093 | `gimle-worker` | Fabric service registration, cross-worker/cross-machine invocation binding | observable | FLEET | NET-9, JRN-3 |
| GIMLE-094 | `gimle-worker` | Fabric TLS certificate rotation detection (mtime polling) | internal | UNIT | `FabricServerTlsWatcherTest#detects_a_rotated_certificate_file_and_reloads_the_fabric_server` |
| GIMLE-095 | `gimle-worker` | Control-plane read relay for hosted modules (RelayControlPlaneRead/Result round trip) | internal | UNIT | `ControlPlaneRelayTest#a_matching_response_completes_the_waiting_caller_and_leaves_no_pending_entry`, `#no_response_times_out_and_still_leaves_no_pending_entry`, `#a_late_response_after_the_caller_already_gave_up_is_dropped_without_error` |
| GIMLE-096 | `gimle-worker` | Worker-side trace relay to agent (no direct Muninn shipping) | internal | UNIT | `RelayingSpanExporterTest#a_real_span_batch_relays_as_a_traces_snapshot_with_the_given_worker_id`, `#export_never_throws_even_when_the_sink_throws`, `#flush_and_shutdown_always_report_success` |
| GIMLE-097 | `gimle-worker` | Per-module CPU/memory/request-rate/error-rate metrics reporting (portable, no cgroup) | observable | FLEET | DEP-4, OBS-4 |
| GIMLE-098 | `gimle-worker` | Worker-wide meter snapshot relay to Muninn (via agent) | internal | UNIT | NONE recorded in the baseline |
| GIMLE-099 | `gimle-worker` | `module-info.java` platform-layer/observability/fabric wiring for the worker module | internal | UNIT | NONE recorded in the baseline |
| GIMLE-100 | `gimle-worker` | Real bundled-hook/probe classloading against the platform layer | internal | UNIT | `RealBundledHookAndProbeInvocationTest#bundled_hooks_and_probes_load_and_cast_against_this_jvms_own_platform_types`, `#bundled_probes_instantiate_and_cast_cleanly` |
| GIMLE-101 | `gimle-agent` | Node agent registration and repeating reconcile/heartbeat/rotate tick loop | internal | UNIT | `AgentWorkerIntegrationTest#agent_spawns_a_real_worker_and_installs_a_module_over_the_control_channel`, `ControlPlaneAgentWorkerIntegrationTest#control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed` |
| GIMLE-102 | `gimle-agent` | Worker JVM process spawn and command-line construction | internal | UNIT | `AgentMainTest#the_spawned_command_carries_the_manifests_limit_not_its_request`, `#the_spawned_command_always_carries_exit_on_out_of_memory_error`, `#the_spawned_command_always_suppresses_the_startup_banner`, `#the_spawned_command_always_forces_json_console_logging`, `#the_spawned_command_forwards_the_default_deny_cross_tenant_flag`, `#the_spawned_command_omits_tls_flags_in_plaintext_mode`, `#the_spawned_command_forwards_this_agents_own_tls_material_when_tls_is_enabled`, `#prepare_resource_limit_hands_the_limiter_the_descriptors_limit_not_its_request` |
| GIMLE-103 | `gimle-agent` | Worker process crash detection, classification, and destroy-and-respawn | observable | FLEET | SCHED-4 |
| GIMLE-104 | `gimle-agent` | Deliberate-stop suppression of crash-respawn | observable | FLEET | DEP-6 |
| GIMLE-105 | `gimle-agent` | Worker stdout draining, JSON-line de-duplication, and raw SYSTEM-line capture | internal | UNIT | `SystemLogCaptureTest#system_log_capture_survives_a_respawn` |
| GIMLE-106 | `gimle-agent` | Machine-level capacity tracking and admission (memory/CPU) | observable | FLEET | SCHED-2 |
| GIMLE-107 | `gimle-agent` | Portable JVM-flags resource limiting (Tier 1/2), cgroup enforcement deliberately deferred | out-of-scope | OUT OF SCOPE | deferred |
| GIMLE-108 | `gimle-agent` | Tier 3 isolation rejection | out-of-scope | OUT OF SCOPE | deferred |
| GIMLE-109 | `gimle-agent` | Assignment reconciliation loop (fetch, start, replace, stop) | internal | UNIT | `AgentMainTest#a_module_id_change_at_the_same_key_requires_replacement`, `#an_artifact_path_change_with_the_same_module_id_requires_replacement`, `#an_unchanged_assignment_at_the_same_key_never_requires_replacement`; `ControlPlaneAgentWorkerIntegrationTest#control_plane_places_replicas_on_real_agents_and_reschedules_after_an_agent_is_killed` |
| GIMLE-110 | `gimle-agent` | Tier 1 density — shared-worker reuse for multiple module instances | observable | FLEET | DEP-5, SCHED-10 |
| GIMLE-111 | `gimle-agent` | Instance rename-in-place (no restart) | internal | UNIT | `AgentMainTest#find_rename_source_finds_the_already_supervised_instance_at_the_hinted_index`, `#find_rename_source_is_empty_without_a_rename_hint`, `#find_rename_source_falls_back_when_the_hinted_source_key_is_not_supervised`, `#find_rename_source_falls_back_when_the_source_runs_a_different_module`, `#rename_in_place_rekeys_supervised_and_shippers_and_updates_the_assigned_identity`, `#rename_in_place_notifies_the_connected_worker_of_its_new_identity` |
| GIMLE-112 | `gimle-agent` | Worker respawn handshake re-drive after crash | internal | HOLMGANG | `self-healing.feature` — A killed worker JVM is respawned and the deployment returns to ACTIVE |
| GIMLE-113 | `gimle-agent` | Worker-crash-to-durable-InstanceEvent relay | observable | FLEET | DEP-8, SCHED-4 |
| GIMLE-114 | `gimle-agent` | Install-phase Nack escalates to FAILED (closing the "stuck at INSTALLED" gap) | observable | FLEET | DEP-10 |
| GIMLE-115 | `gimle-agent` | Artifact-registry coordinate resolution via ArtifactPullCache | observable | FLEET | ART-4 |
| GIMLE-116 | `gimle-agent` | Instance-scoped log/config/secret delivery over the control channel | observable | FLEET | SEC-6 |
| GIMLE-117 | `gimle-agent` | Persistent volume allocation for StatefulSet-shaped instances | observable | FLEET | BATCH-5 |
| GIMLE-118 | `gimle-agent` | Vessel process supervision (plain-jar workload as its own dedicated process) | observable | FLEET | BATCH-8, JRN-1 |
| GIMLE-119 | `gimle-agent` | Vessel port allocation (dynamic/fixed) and env resolution (literal/port/secret) | observable | FLEET | BATCH-8 |
| GIMLE-120 | `gimle-agent` | Vessel config-file rendering to disk | observable | FLEET | BATCH-8 |
| GIMLE-121 | `gimle-agent` | Vessel health probing (process-alive + TCP/HTTP rungs, initial-delay aware) | observable | FLEET | BATCH-8 |
| GIMLE-122 | `gimle-agent` | Vessel crash respawn resets probe initial-delay clock | internal | UNIT | NONE recorded in the baseline |
| GIMLE-123 | `gimle-agent` | mTLS bootstrap CSR flow for node identity | observable | FLEET | OPS-5 |
| GIMLE-124 | `gimle-agent` | Periodic certificate rotation check and hot-swap of outbound HttpClient | internal | UNIT | NONE recorded in the baseline |
| GIMLE-125 | `gimle-agent` | SWIM gossip membership integration with service catalog relay | internal | UNIT | NONE recorded in the baseline |
| GIMLE-126 | `gimle-agent` | Gossip membership read-only HTTP surface | observable | FLEET | OPS-11 |
| GIMLE-127 | `gimle-agent` | Node/instance log-serving HTTP surface with tailing and follow | observable | FLEET | OBS-1 |
| GIMLE-128 | `gimle-agent` | Merged node-level SYSTEM log view | observable | FLEET | OPS-15 |
| GIMLE-129 | `gimle-agent` | `hs_err_pid*.log` crash-dump listing and fetch | observable | FLEET | OBS-2 |
| GIMLE-130 | `gimle-agent` | Node-agent log/metrics shipping to Muninn (own + supervised) | observable | FLEET | OBS-3 |
| GIMLE-131 | `gimle-agent` | Whitelisted control-plane read relay (worker→agent→control plane) with independent re-validation | internal | UNIT | `AgentRelayControlPlaneReadTest#a_non_whitelisted_path_is_rejected_locally_and_never_reaches_the_control_plane`, `#a_path_traversal_attempt_disguised_as_a_single_segment_is_rejected`, `#a_whitelisted_path_triggers_a_real_call_and_relays_the_response_back`; end-to-end via `RelayControlPlaneEndToEndTest#a_hosted_modules_relay_call_round_trips_through_a_real_worker_process` |
| GIMLE-132 | `gimle-agent` | Node capacity/instance-observation heartbeat reporting | observable | FLEET | SCHED-3, SCHED-6 |
| GIMLE-133 | `gimle-agent` | Instance-event forwarding (worker-reported and agent-originated) to control plane | internal | UNIT | NONE recorded in the baseline |
| GIMLE-134 | `gimle-agent` | Node placement-label registration | observable | FLEET | SCHED-1 |
| GIMLE-135 | `gimle-agent` | `module-info.java` wiring for the node agent module | internal | UNIT | NONE recorded in the baseline |
| GIMLE-136 | `gimle-mimir` | Raft Leader Election | observable | FLEET | CHAOS-6 |
| GIMLE-137 | `gimle-mimir` | Log Replication (AppendEntries) | internal | HOLMGANG | `raft-resilience.feature` — A store member dies mid-workload and nothing acknowledged is lost; `raft-resilience.feature` — The store leader dies mid-workload and nothing acknowledged is lost |
| GIMLE-138 | `gimle-mimir` | Election Safety Restriction (log up-to-date check) | internal | HOLMGANG | `raft-resilience.feature` — A stale, partitioned follower cannot win an election despite outracing the cluster's term |
| GIMLE-139 | `gimle-mimir` | Conflicting-Entry Truncation | internal | UNIT | `RaftNodeSafetyMechanicsTest#a_follower_truncates_a_conflicting_entry_and_everything_after_it_before_appending` |
| GIMLE-140 | `gimle-mimir` | Leader-Only-Commits-Own-Term Rule (Figure 8) | internal | UNIT | `RaftNodeSafetyMechanicsTest#the_leader_only_commits_an_entry_from_its_own_current_term` |
| GIMLE-141 | `gimle-mimir` | Strict Apply Ordering (commitIndex vs lastApplied) | internal | HOLMGANG | `raft-resilience.feature` — A store member dies mid-workload and nothing acknowledged is lost |
| GIMLE-142 | `gimle-mimir` | Proposal Timeout with Ghost-Write Prevention | internal | HOLMGANG | `partition-tolerance.feature` — A leader's write proposed while partitioned is truncated and never resurfaces |
| GIMLE-143 | `gimle-mimir` | Chunked InstallSnapshot Transfer (Figure 13) | internal | HOLMGANG | `raft-resilience.feature` — A learner catches up through a compacted leader's snapshot and only helps quorum once promoted |
| GIMLE-144 | `gimle-mimir` | Local Log Compaction / Snapshotting | internal | HOLMGANG | `raft-resilience.feature` — A learner catches up through a compacted leader's snapshot and only helps quorum once promoted |
| GIMLE-145 | `gimle-mimir` | Check-Quorum Leader Self-Demotion | internal | HOLMGANG | `partition-tolerance.feature` — A store leader silently partitioned from its peers steps down and writes stay bounded |
| GIMLE-146 | `gimle-mimir` | Etcd-Style Live Membership Change (AddServer/RemoveServer) | observable | FLEET | OPS-6 |
| GIMLE-147 | `gimle-mimir` | Non-Voting Learner & Automatic Promotion | internal | HOLMGANG | `raft-resilience.feature` — A learner catches up through a compacted leader's snapshot and only helps quorum once promoted |
| GIMLE-148 | `gimle-mimir` | Durable Raft Log Persistence | observable | FLEET | OPS-3, CHAOS-6 |
| GIMLE-149 | `gimle-mimir` | Raft Transport over Mutual TLS with Hot Cert Reload | internal | HOLMGANG | `mtls.feature` — The cluster functions end to end over mutual TLS; `mtls.feature` — The audit trail records and filters real authorization decisions over mutual TLS |
| GIMLE-150 | `gimle-mimir` | Raft RPC Wire Codec | internal | UNIT | `RaftCodecTest#round_trips_through_streams`, `#rejects_an_oversized_length_prefix_before_allocating`, `#rejects_a_negative_length_prefix_before_allocating`, `#rejects_a_forged_huge_entry_count_without_preallocating`, `#round_trips_a_state_snapshot`, `#round_trips_a_log_entry_carrying_a_membership_change` |
| GIMLE-151 | `gimle-mimir` | Atomic Durable File Writes | internal | UNIT | `AtomicFilesTest#writes_content_visible_under_the_final_name_with_no_leftover_tmp_file`, `#the_written_file_has_no_unflushed_dirty_state_after_writeatomically_returns` |
| GIMLE-152 | `gimle-mimir` | Raft WAL Persistence Engine with Snapshot-Replay Recovery | observable | FLEET | OPS-3 |
| GIMLE-153 | `gimle-mimir` | Full-State Snapshot / Restore | internal | HOLMGANG | `state-store-persistence.feature` — Tenants, roles, role bindings, and accounts survive a store restart, snapshot included |
| GIMLE-154 | `gimle-mimir` | Replicated Mutation Catalog (StateMutation) | internal | UNIT | `RaftCodecTest#round_trips_role_rolebinding_and_account_mutations_through_a_log_entry`, `#round_trips_an_append_instance_event_mutation_with_and_without_a_cause_summary`, `#round_trips_an_append_audit_event_mutation_allowed_and_denied_with_and_without_scope` |
| GIMLE-155 | `gimle-mimir` | Leader-Local Node Heartbeat Tracking | internal | HOLMGANG | `state-store-mechanics.feature` — Node heartbeats update continuously for a live node |
| GIMLE-156 | `gimle-mimir` | Distributed Lease Coordination (Grant/Renew/Release) | internal | HOLMGANG | `state-store-mechanics.feature` — A lease is exclusive to its holder until it expires |
| GIMLE-157 | `gimle-mimir` | Per-Instance Lifecycle Event Log with Retention Cap | observable | FLEET | DEP-1 |
| GIMLE-158 | `gimle-mimir` | Cluster-Wide Audit Trail with Filtering | observable | FLEET | GOV-5 |
| GIMLE-159 | `gimle-mimir` | Deployment Rolling-Update & Surge Bookkeeping | internal | HOLMGANG | `rolling-update.feature` — Zero-downtime rollout under a surge budget |
| GIMLE-160 | `gimle-mimir` | StatefulSet OrderedReady Index & Sticky Node Binding | observable | FLEET | BATCH-5 |
| GIMLE-161 | `gimle-mimir` | Node Cordon (Scheduler Exclusion Flag) | observable | FLEET | SCHED-3 |
| GIMLE-162 | `gimle-mimir` | Tenant Quota-Violation Flag Tracking | observable | FLEET | GOV-1 |
| GIMLE-163 | `gimle-mimir` | RBAC Data Persistence (Roles, RoleBindings, Accounts) | observable | FLEET | GOV-3 |
| GIMLE-164 | `gimle-mimir` | Client-Facing Store RPC with Leader Redirect & Follow | internal | HOLMGANG | `deployment-lifecycle.feature` — State written through one control-plane replica serves through another |
| GIMLE-165 | `gimle-mimir` | Store Read Load Balancing Across Replicas | internal | HOLMGANG | `deployment-lifecycle.feature` — State written through one control-plane replica serves through another |
| GIMLE-166 | `gimle-mimir` | Store Node Leader-Only Write Gating | internal | UNIT | `StoreNodeTest#a_non_leader_rejects_a_propose_with_not_leader_and_no_hint_yet`, `#a_non_leader_rejects_a_heartbeat_a_lease_acquire_and_a_lease_release`, `#a_non_leader_rejects_an_add_server_request_with_not_leader` |
| GIMLE-167 | `gimle-mimir` | Store Client Connection Timeout Bounds | internal | UNIT | `StoreConnectionTimeoutTest#a_connection_that_accepts_but_never_responds_times_out_instead_of_blocking_forever` |
| GIMLE-168 | `gimle-mimir` | Store RPC Wire Codec | internal | UNIT | `StoreCodecTest#round_trips_through_streams`, `#round_trips_a_weighted_autoscale_policy_with_every_weight_present`, `#round_trips_an_account_result_carrying_a_password_hash` |
| GIMLE-169 | `gimle-mimir` | RBAC Authorization Engine | internal | UNIT | `AuthorizerTest#a_principal_with_no_binding_and_no_group_is_denied_everything`, `#an_operator_group_member_is_allowed_everything_via_the_implicit_cluster_admin_binding`, `#a_custom_role_bound_to_a_user_grants_exactly_its_declared_permissions`, `#a_tenant_scoped_permission_only_matches_its_own_tenant`, `#a_node_may_act_on_its_own_node_and_log_endpoints_with_no_role_binding_at_all`, `#a_node_is_denied_another_nodes_endpoints`, `#a_binding_referencing_a_role_that_no_longer_exists_grants_nothing`, `#a_node_may_read_the_cluster_wide_service_and_network_policy_sets_with_no_binding_at_all`, `#a_node_may_never_write_or_delete_a_service_or_network_policy`; `ApiServerNodeServiceAndNetworkPolicyAuthzTest` (`gimle-controlplane`) exercises the same grant through the real mTLS/RBAC HTTP layer. |
| GIMLE-170 | `gimle-mimir` | Node-Tenant Assignment Check | internal | UNIT | `AuthorizerTest#a_node_with_an_active_assignment_for_the_tenant_is_assigned`, `#a_node_with_no_assignment_for_the_tenant_is_not_assigned`, `#a_node_with_no_assignments_at_all_is_not_assigned` |
| GIMLE-171 | `gimle-mimir` | Five-Field Cron Schedule Evaluator | observable | FLEET | BATCH-2 |
| GIMLE-172 | `gimle-mimir` | Deployment Manifest Parsing (incl. Autoscale & Disruption Budget) | observable | FLEET | CHAOS-1 |
| GIMLE-173 | `gimle-mimir` | DaemonSet Manifest Parsing (Anti-Affinity/Surge Rejection) | observable | FLEET | CHAOS-1 |
| GIMLE-174 | `gimle-mimir` | Job / CronJob Manifest Parsing | observable | FLEET | CHAOS-1 |
| GIMLE-175 | `gimle-mimir` | StatefulSet Manifest Parsing | observable | FLEET | CHAOS-1 |
| GIMLE-176 | `gimle-mimir` | Kind-Dispatching Manifest Parser | observable | FLEET | CHAOS-1 |
| GIMLE-177 | `gimle-mimir` | Shared Domain Binary Codec | internal | UNIT | `DomainCodecTest#a_vessel_spec_round_trips_through_the_wire`, `#an_absent_vessel_spec_round_trips_as_empty`, `#a_deployment_spec_with_a_vessel_round_trips` |
| GIMLE-178 | `gimle-mimir` | Store Process Bootstrap with TLS Rotation Ticker | internal | UNIT | NONE recorded in the baseline |
| GIMLE-179 | `gimle-mimir` | Store/Raft Metrics Instrumentation | internal | UNIT | NONE recorded in the baseline |
| GIMLE-180 | `gimle-mimir` | module-info JPMS Boundary for gimle-mimir | internal | UNIT | NONE recorded in the baseline |
| GIMLE-181 | `gimle-fabric` | Same-Worker Direct Invocation Tier | observable | FLEET | NET-9 |
| GIMLE-182 | `gimle-fabric` | Same-Machine Unix-Domain-Socket Invocation Tier | observable | FLEET | NET-9 |
| GIMLE-183 | `gimle-fabric` | Cross-Machine TCP Invocation Tier | observable | FLEET | NET-9 |
| GIMLE-184 | `gimle-fabric` | Locality-Aware Load Balancing with Spillover | observable | FLEET | JRN-5 |
| GIMLE-185 | `gimle-fabric` | Least-Outstanding-Requests Selection | internal | UNIT | `LeastOutstandingRequestsSelectorTest#selects_the_candidate_with_fewest_outstanding_requests`, `#ties_are_broken_round_robin`, `#end_never_goes_negative`, `FabricServiceRegistryTest#least_outstanding_requests_prefers_the_idle_endpoint` |
| GIMLE-186 | `gimle-fabric` | Per-Endpoint Circuit Breaker | observable | FLEET | NET-8 |
| GIMLE-187 | `gimle-fabric` | Circuit Breaker Exponential Cooldown Backoff | internal | UNIT | `CircuitBreakerTest#repeated_reopens_double_the_effective_cooldown`, `#the_doubling_backoff_stops_at_its_documented_ceiling`, `#a_successful_half_open_trial_resets_the_backoff_to_the_base_cooldown` |
| GIMLE-188 | `gimle-fabric` | Panic-Mode Ejection Floor | internal | UNIT | `FabricServiceRegistryTest#all_endpoints_failing_still_yields_a_candidate_once_the_panic_threshold_is_crossed`, `#no_known_exporter_anywhere_throws_gimle_cluster_exception` |
| GIMLE-189 | `gimle-fabric` | Application-Exception vs Transport-Failure Breaker Scoring | internal | UNIT | `FabricServiceRegistryTest#an_endpoint_whose_method_throws_an_application_exception_does_not_open_its_breaker` |
| GIMLE-190 | `gimle-fabric` | Gossip-Propagated Service Catalog | observable | FLEET | NET-9 |
| GIMLE-191 | `gimle-fabric` | Catalog Eviction on Gossip-Detected Node Death | observable | FLEET | SCHED-6 |
| GIMLE-192 | `gimle-fabric` | Cross-Tenant Service Export Access Control | observable | FLEET | NET-6, SEC-11 |
| GIMLE-193 | `gimle-fabric` | Runtime Name-Driven Cross-Tier Invocation (invokeByName) | internal | UNIT | `FabricServiceRegistryInvokeByNameTest#a_same_worker_registration_is_invoked_directly_by_name`, `#a_same_machine_registration_is_invoked_over_the_wire_by_name`, `#a_remote_registration_is_invoked_over_the_wire_by_name`, `#wrong_param_type_names_fail_clearly_rather_than_hanging_or_matching_a_wrong_overload` |
| GIMLE-194 | `gimle-fabric` | Inbound Call Dispatch with Bounded Concurrency | internal | UNIT | `FabricServerTest#a_real_inbound_call_is_visible_in_the_targets_in_flight_count_while_it_runs`, `#concurrent_calls_are_bounded_by_the_targets_executor_not_run_unbounded`, `#real_calls_are_recorded_in_the_targets_worker_metrics_including_errors` |
| GIMLE-195 | `gimle-fabric` | Distributed Trace Propagation Across Fabric Hops | observable | FLEET | OBS-5 |
| GIMLE-196 | `gimle-fabric` | Fabric Transport over Mutual TLS with Hot Cert Reload | observable | FLEET | OPS-5, NET-5 |
| GIMLE-197 | `gimle-fabric` | Fabric Call Timeout Enforcement | internal | UNIT | `FabricClientTest#a_peer_that_accepts_but_never_responds_times_out_within_the_configured_bound`, `#a_refused_connection_fails_fast_without_waiting_out_the_timeout` |
| GIMLE-198 | `gimle-fabric` | Fabric Frame Wire Codec | internal | UNIT | `FabricCodecTest#round_trips_through_streams`, `#round_trips_a_non_empty_tracestate_and_baggage`, `#rejects_an_oversized_length_prefix_before_allocating`, `#rejects_a_forged_huge_param_count_before_allocating` |
| GIMLE-199 | `gimle-fabric` | Cross-JVM Object Marshalling | internal | UNIT | NONE recorded in the baseline |
| GIMLE-200 | `gimle-fabric` | SWIM Gossip Membership Protocol (Ping/PingReq/Ack) | internal | UNIT | `GossipMemberTest#two_nodes_discover_each_other_via_join`, `#a_killed_member_converges_to_dead_across_the_rest`, `#a_lone_node_with_no_seeds_starts_as_a_new_cluster`, `#a_single_unreachable_seed_is_a_legitimate_bootstrap_not_an_error`, `#multiple_unreachable_seeds_throw_gimle_cluster_exception` |
| GIMLE-201 | `gimle-fabric` | SWIM Self-Refutation via Incarnation Bump | internal | UNIT | `GossipMemberTest#a_member_refutes_a_suspicion_of_itself_by_bumping_incarnation`, `#a_stale_suspicion_below_the_current_incarnation_is_ignored` |
| GIMLE-202 | `gimle-fabric` | Lifeguard-Style Local Health Multiplier | internal | UNIT | `GossipMemberTest#the_local_health_multiplier_clamps_rather_than_growing_unbounded` |
| GIMLE-203 | `gimle-fabric` | Round-Robin Bounded-Coverage Probe Target Selection | internal | UNIT | `GossipMemberTest#probe_target_selection_visits_every_live_member_within_one_cycle` |
| GIMLE-204 | `gimle-fabric` | Anti-Entropy Full-State Sync | internal | UNIT | `GossipMemberTest#anti_entropy_sync_delivers_a_change_piggyback_alone_cannot_carry` |
| GIMLE-205 | `gimle-fabric` | Dead-Member Reaping | observable | FLEET | SCHED-6 |
| GIMLE-206 | `gimle-fabric` | Gossip over Mutual DTLS with Deterministic Initiator Selection | internal | UNIT | `GossipMemberDtlsTest#two_nodes_discover_each_other_over_mutual_dtls`, `#a_killed_member_still_converges_to_dead_over_dtls`, `#members_trusting_different_cas_never_become_mutually_aware`, `#a_member_reaches_a_new_peer_over_dtls_after_reloading_rotated_material` |
| GIMLE-207 | `gimle-fabric` | SWIM Wire Codec | internal | UNIT | `SwimCodecTest#round_trips_through_a_datagram`, `#a_forged_huge_piggyback_count_fails_cleanly_instead_of_preallocating`, `#rejects_an_unrecognized_version_before_decoding_the_tag` |
| GIMLE-208 | `gimle-fabric` | Service Catalog Delta Wire Codec | internal | UNIT | `ServiceCatalogCodecTest#round_trips_a_catalog_delta`, `#round_trips_an_empty_delta_list`, `#a_forged_huge_delta_count_fails_cleanly_instead_of_preallocating` |
| GIMLE-209 | `gimle-fabric` | Reflective Cross-Module Method Dispatch | internal | UNIT | Exercised indirectly through `FabricServiceRegistryInvokeByNameTest`/`FabricServerTest` — NONE dedicated |
| GIMLE-210 | `gimle-fabric` | module-info JPMS Boundary for gimle-fabric | internal | UNIT | NONE recorded in the baseline |
| GIMLE-211 | `gimle-controlplane` | First-fit-decreasing bin-packing scheduler | observable | FLEET | SCHED-1 |
| GIMLE-212 | `gimle-controlplane` | Isolation-tier placement filtering | observable | FLEET | DEP-5 |
| GIMLE-213 | `gimle-controlplane` | Node cordon exclusion | observable | FLEET | SCHED-3 |
| GIMLE-214 | `gimle-controlplane` | Strict anti-affinity across nodes | observable | FLEET | SCHED-1 |
| GIMLE-215 | `gimle-controlplane` | Tier 2/3 node-level tenant isolation | observable | FLEET | SCHED-9 |
| GIMLE-216 | `gimle-controlplane` | Required node-label placement constraint | observable | FLEET | SCHED-1 |
| GIMLE-217 | `gimle-controlplane` | StatefulSet sticky node placement | observable | FLEET | BATCH-5 |
| GIMLE-218 | `gimle-controlplane` | DaemonSet eligible-node enumeration (`eligibleNodes`) | observable | FLEET | BATCH-3 |
| GIMLE-219 | `gimle-controlplane` | Deployment replica reconciliation (level-triggered) | observable | FLEET | DEP-1, DEP-9 |
| GIMLE-220 | `gimle-controlplane` | Deployment scale-down | observable | FLEET | DEP-6, DEP-9 |
| GIMLE-221 | `gimle-controlplane` | Artifact-hash drift detection at reconcile time | internal | UNIT | `DeploymentReconcilerTest` — `places_new_instances_when_the_recorded_artifact_hash_still_matches_the_jar_on_disk`, `refuses_to_place_new_instances_once_the_jar_on_disk_no_longer_matches_the_recorded_hash` |
| GIMLE-222 | `gimle-controlplane` | Rolling update via mismatched-index migration | observable | FLEET | SCHED-8 |
| GIMLE-223 | `gimle-controlplane` | Rolling update surge (maxSurge) | observable | FLEET | SCHED-8 |
| GIMLE-224 | `gimle-controlplane` | Node-death instance reclamation (`ReplicaCountReconciler`) | observable | FLEET | SCHED-6 |
| GIMLE-225 | `gimle-controlplane` | Persisted grace-period bookkeeping (survives leader failover) | internal | UNIT | `ReplicaCountReconcilerTest`; `HealthReconcilerTest#backoff_state_survives_a_reconciler_reconstruction_against_the_same_store` |
| GIMLE-226 | `gimle-controlplane` | Unhealthy-instance backoff-gated reschedule (`HealthReconciler`) | observable | FLEET | SCHED-5 |
| GIMLE-227 | `gimle-controlplane` | Readiness-only failures never trigger reschedule | observable | FLEET | DEP-7 |
| GIMLE-228 | `gimle-controlplane` | Tenant quota drift detection (`QuotaReconciler`) | observable | FLEET | GOV-1 |
| GIMLE-229 | `gimle-controlplane` | Horizontal autoscaling — multi-signal (`AutoscaleReconciler`) | observable | FLEET | SCHED-7, GOV-7 |
| GIMLE-230 | `gimle-controlplane` | Autoscaling WEIGHTED combination mode | observable | FLEET | SCHED-7 |
| GIMLE-231 | `gimle-controlplane` | DaemonSet reconciliation and rolling update | observable | FLEET | BATCH-3 |
| GIMLE-232 | `gimle-controlplane` | DaemonSet dark-node placement-safety grace period | internal | UNIT | `DaemonSetReconcilerTest#a_replica_on_a_dark_but_not_yet_timed_out_node_is_not_relocated`, `cordoning_a_dark_node_still_removes_its_assignment_immediately` |
| GIMLE-233 | `gimle-controlplane` | StatefulSet OrderedReady placement | observable | FLEET | BATCH-5 |
| GIMLE-234 | `gimle-controlplane` | StatefulSet one-index-at-a-time scale-down | observable | FLEET | BATCH-5 |
| GIMLE-235 | `gimle-controlplane` | JobRun run-to-completion reconciliation | observable | FLEET | BATCH-1, JRN-2 |
| GIMLE-236 | `gimle-controlplane` | Job active-deadline enforcement | observable | FLEET | BATCH-1 |
| GIMLE-237 | `gimle-controlplane` | CronJob schedule-driven Job materialization | observable | FLEET | BATCH-2 |
| GIMLE-238 | `gimle-controlplane` | CronJob concurrency policy (Allow/Forbid/Replace) | observable | FLEET | BATCH-2 |
| GIMLE-239 | `gimle-controlplane` | CronJob manual trigger (`gimle cronjob trigger`) | observable | FLEET | BATCH-2 |
| GIMLE-240 | `gimle-controlplane` | CronJob missed-schedule starting-deadline handling | internal | UNIT | Covered indirectly by `CronJobReconcilerTest`'s convergence/missed-schedule handling |
| GIMLE-241 | `gimle-controlplane` | Level-triggered orphan cleanup across every workload kind | observable | FLEET | DEP-6, CHAOS-5 |
| GIMLE-242 | `gimle-controlplane` | Reconciler-leader election via non-replicated lease | internal | UNIT | Indirect (multi-replica smoke/holmgang tests) |
| GIMLE-243 | `gimle-controlplane` | Independent-executor ticking (lease/reconcile/cert-rotation isolation) | internal | UNIT | `ControlPlaneSchedulingTest` — `cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_is_blocked_forever`, `cert_rotation_and_lease_renewal_keep_ticking_while_the_reconcile_tick_throws_every_time` |
| GIMLE-244 | `gimle-controlplane` | JPMS module boundary for gimle-controlplane | internal | UNIT | NONE recorded in the baseline |
| GIMLE-245 | `gimle-controlplane` | Admission chain extension point | internal | UNIT | `AdmissionChainTest` — `empty_chain_allows_the_spec_unchanged`, `a_rejecting_plugin_short_circuits_every_later_plugin`, `a_later_plugin_sees_the_spec_an_earlier_plugin_mutated` |
| GIMLE-246 | `gimle-controlplane` | Tenant resource quota admission check | observable | FLEET | GOV-1, GOV-7 |
| GIMLE-247 | `gimle-controlplane` | Organization-specific policy-as-data admission (`policy.maxReplicasPerDeployment`) | observable | FLEET | GOV-10 |
| GIMLE-248 | `gimle-controlplane` | Registry-coordinate artifact admission (Andvari integration) | observable | FLEET | ART-4, ART-5 |
| GIMLE-249 | `gimle-controlplane` | PUT-time re-tenanting double-authorization | observable | FLEET | GOV-11 |
| GIMLE-250 | `gimle-controlplane` | RBAC-gated resource CRUD across every workload kind | observable | FLEET | GOV-3, CHAOS-8 |
| GIMLE-251 | `gimle-controlplane` | WRITE/DELETE decisions durably audited (opt-in READ auditing) | observable | FLEET | GOV-5 |
| GIMLE-252 | `gimle-controlplane` | `gimle-system` reserved-tenant operator-only guard | observable | FLEET | GOV-10 |
| GIMLE-253 | `gimle-controlplane` | Node-scoped self-service authorization (`gimle:nodes` group) | observable | FLEET | SEC-8 |
| GIMLE-254 | `gimle-controlplane` | Node-tenant-scoped `/endpoints/*` read access | internal | UNIT | `ApiServerEndpointsAuthzTest` — `a_node_with_an_active_assignment_for_the_deployments_tenant_may_read_its_endpoints`, `a_node_with_no_assignment_for_the_deployments_tenant_is_forbidden` |
| GIMLE-255 | `gimle-controlplane` | mTLS-authenticated HTTP API server with client-cert principal resolution | observable | FLEET | OPS-5 |
| GIMLE-256 | `gimle-controlplane` | Console session login/logout/session cookie flow | observable | FLEET | SEC-9 |
| GIMLE-257 | `gimle-controlplane` | Login throttling (address + username keyed) | observable | FLEET | SEC-9 |
| GIMLE-258 | `gimle-controlplane` | Bootstrap node join via single-use token + CSR | observable | FLEET | OPS-5, SEC-7 |
| GIMLE-259 | `gimle-controlplane` | Operator-approval-gated CSR flow | observable | FLEET | SEC-7 |
| GIMLE-260 | `gimle-controlplane` | Certificate rotation (self-rotation and subject-preserving renewal) | internal | UNIT | `CertificateRotationTest` — `rotation_issues_a_new_cert_for_the_same_subject_and_it_works_immediately`, `rotation_csr_with_a_mismatched_subject_is_rejected` |
| GIMLE-261 | `gimle-controlplane` | Zero-downtime TLS material reload | internal | UNIT | Exercised via `CertificateRotationTest`; analogous pattern in `FafnirServerTlsTest`/`AndvariServerTlsTest` |
| GIMLE-262 | `gimle-controlplane` | `/secrets/*` byte-for-byte proxy to Fafnir | observable | FLEET | SEC-1 |
| GIMLE-263 | `gimle-controlplane` | Secrets key rotation trigger (proxied) | observable | FLEET | SEC-3 |
| GIMLE-264 | `gimle-controlplane` | CONFIG/SECRET resource-kind separation on one underlying store | internal | UNIT | `ApiServerAuthzTest#config_and_secret_permissions_are_independently_enforced_and_filtered` |
| GIMLE-265 | `gimle-controlplane` | `/artifacts/*` streaming proxy to Andvari | observable | FLEET | ART-1 |
| GIMLE-266 | `gimle-controlplane` | Andvari-client multi-endpoint failover with rotation | observable | FLEET | ART-8 |
| GIMLE-267 | `gimle-controlplane` | `/logs/*` proxy with Muninn fallback | observable | FLEET | OBS-3 |
| GIMLE-268 | `gimle-controlplane` | `/metrics-history/*` and `/traces-history/*` Muninn proxy | observable | FLEET | OBS-4 |
| GIMLE-269 | `gimle-controlplane` | Node registration, heartbeat, and assignment-fetch API | internal | HOLMGANG | `module-system.feature` — A hook that always throws on start never reaches ACTIVE |
| GIMLE-270 | `gimle-controlplane` | Unified `AssignedInstance` wire shape across every workload kind | internal | UNIT | `ApiServerEndpointsTest` — `a_job_run_is_listed_under_its_own_endpoints_route`, `a_daemonset_assignment_is_listed_under_its_own_endpoints_route`, `a_statefulset_assignment_is_listed_under_its_own_endpoints_route` |
| GIMLE-271 | `gimle-controlplane` | Reserved system-tenant auto-seeding | observable | FLEET | OPS-2 |
| GIMLE-272 | `gimle-controlplane` | Bundled web console static serving | observable | FLEET | OPS-2 |
| GIMLE-273 | `gimle-controlplane` | Per-endpoint request metrics instrumentation | observable | FLEET | OBS-4 |
| GIMLE-274 | `gimle-controlplane` | Deployment/Job/DaemonSet/StatefulSet CRUD manifest API | observable | FLEET | DEP-1 |
| GIMLE-275 | `gimle-controlplane` | Per-deployment and per-instance metrics rollup | observable | FLEET | OBS-4 |
| GIMLE-276 | `gimle-fafnir` | AES-256-GCM secret value encryption with versioned key IDs | internal | HOLMGANG | `secrets-and-pki.feature` — A secret's versions round-trip and a soft delete behaves differently from a hard one; `secrets-and-pki.feature` — Rotating the secrets key re-encrypts an existing secret under the new key id |
| GIMLE-277 | `gimle-fafnir` | Legacy pre-key-id ciphertext format fallback | internal | HOLMGANG | `secrets-and-pki.feature` — A legacy pre-key-id secret ciphertext still decrypts correctly |
| GIMLE-278 | `gimle-fafnir` | Local AES-256 key-file generation and loading | internal | HOLMGANG | `secrets-and-pki.feature` — A secret's versions round-trip and a soft delete behaves differently from a hard one; `secrets-and-pki.feature` — A legacy pre-key-id secret ciphertext still decrypts correctly |
| GIMLE-279 | `gimle-fafnir` | Key rotation with full-ring persistence (`KeyFileManager.rotate`) | observable | FLEET | SEC-3 |
| GIMLE-280 | `gimle-fafnir` | Key-ring fingerprinting for cross-replica drift detection | observable | FLEET | SEC-3 |
| GIMLE-281 | `gimle-fafnir` | Full-key-rotation re-encryption sweep | observable | FLEET | SEC-3 |
| GIMLE-282 | `gimle-fafnir` | Versioned secret storage layered over ConfigEntry | observable | FLEET | SEC-1 |
| GIMLE-283 | `gimle-fafnir` | Optimistic-write versioned put with narrow-lease serialization | internal | UNIT | `SecretStoreTest` (contention scenario per class javadoc) |
| GIMLE-284 | `gimle-fafnir` | Soft delete vs hard delete (`?destroy=true`) | observable | FLEET | SEC-2 |
| GIMLE-285 | `gimle-fafnir` | Fafnir's own independent RBAC re-check (defense-in-depth) | observable | FLEET | SEC-8, CHAOS-8 |
| GIMLE-286 | `gimle-fafnir` | Node-tenant-scoped secret reads (`gimle:nodes`) | internal | HOLMGANG | `secrets-and-pki.feature` — Fafnir independently authorizes node-scoped secret reads by tenant assignment |
| GIMLE-287 | `gimle-fafnir` | Authorization-failure throttling and dual audit logging | internal | UNIT | `FafnirObservabilityTest` — `repeated_authorization_failures_from_the_same_principal_are_eventually_throttled`, `a_successful_authorization_clears_prior_recorded_failures`, `audit_log_records_the_decision_without_ever_logging_the_secret_value` |
| GIMLE-288 | `gimle-fafnir` | Three-tier principal resolution (forwarded header > peer cert > session cookie) | internal | UNIT | `FafnirSecretsAuthzTest`; `FafnirServerAuthTest` |
| GIMLE-289 | `gimle-fafnir` | mTLS HTTP server with dynamic TLS material reload | internal | UNIT | `FafnirServerTlsTest` — `a_real_mtls_request_with_a_ca_signed_client_cert_succeeds`, `reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server` |
| GIMLE-290 | `gimle-fafnir` | Console session login (Fafnir's own operator dashboard) | observable | FLEET | SEC-9 |
| GIMLE-291 | `gimle-fafnir` | Plaintext-mode anonymous session carve-out | observable | FLEET | OPS-2 |
| GIMLE-292 | `gimle-fafnir` | Bundled web console static serving (Fafnir) | observable | FLEET | OPS-2 |
| GIMLE-293 | `gimle-fafnir` | Process status endpoint with key-ring fingerprint | observable | FLEET | OPS-11 |
| GIMLE-294 | `gimle-fafnir` | Muninn metrics/traces shipping | internal | UNIT | NONE recorded in the baseline |
| GIMLE-295 | `gimle-fafnir` | Fafnir-metrics observability instrumentation | internal | UNIT | `FafnirObservabilityTest#a_real_request_is_recorded_in_fafnir_metrics` |
| GIMLE-296 | `gimle-fafnir` | JPMS module boundary for gimle-fafnir | internal | UNIT | NONE recorded in the baseline |
| GIMLE-297 | `gimle-andvari` | Immutable, content-addressed artifact store | observable | FLEET | ART-1, ART-2 |
| GIMLE-298 | `gimle-andvari` | Streamed, digest-verified push with atomic commit | internal | UNIT | `ArtifactStoreTest` (push mechanics covered by round-trip tests) |
| GIMLE-299 | `gimle-andvari` | Size-limited streaming upload rejection | observable | FLEET | ART-7 |
| GIMLE-300 | `gimle-andvari` | On-disk corruption detection and quarantine | internal | UNIT | `AndvariServerTest#a_get_against_bytes_corrupted_on_disk_still_serves_them_but_quarantines_the_coordinate` |
| GIMLE-301 | `gimle-andvari` | Periodic full-store integrity scrub | internal | UNIT | `IntegrityScrubberTest` — `a_coordinate_whose_bytes_no_longer_match_its_recorded_digest_is_reported`, `an_uncorrupted_coordinate_is_never_reported`, `a_version_missing_its_jar_is_skipped_rather_than_reported_as_corrupted` |
| GIMLE-302 | `gimle-andvari` | Version retention sweeping (count and age based) | internal | UNIT | `ArtifactRetentionSweeperTest` — `retires_the_oldest_versions_once_a_module_exceeds_the_configured_count`, `retires_versions_older_than_the_configured_age`, `a_version_over_both_limits_is_reported_once_with_a_combined_reason`, `neither_policy_configured_retires_nothing` |
| GIMLE-303 | `gimle-andvari` | Multi-replica peer synchronization (no consensus) | observable | FLEET | ART-8 |
| GIMLE-304 | `gimle-andvari` | Peer-sync conflict detection (irreconcilable divergence) | internal | UNIT | Documented in class javadoc |
| GIMLE-305 | `gimle-andvari` | Push/pull/list/delete `/artifacts/*` operational HTTP surface | observable | FLEET | ART-1, ART-3 |
| GIMLE-306 | `gimle-andvari` | Maven-2-shaped `/repository/**` interop surface | observable | FLEET | ART-6 |
| GIMLE-307 | `gimle-andvari` | Server-computed checksum sidecars (never trusting client uploads) | observable | FLEET | ART-6 |
| GIMLE-308 | `gimle-andvari` | Generated `maven-metadata.xml` (never stored, always fresh) | observable | FLEET | ART-6 |
| GIMLE-309 | `gimle-andvari` | Maven GAV coordinate translation | internal | UNIT | `MavenCoordinatesTest` — `a_multi_segment_group_joins_with_the_artifact_id_by_dots`, `distinct_gavs_can_alias_to_the_same_module_coordinate` |
| GIMLE-310 | `gimle-andvari` | Defense-in-depth authorization (independent re-check, `ResourceKind.ARTIFACT`) | observable | FLEET | SEC-8, CHAOS-8 |
| GIMLE-311 | `gimle-andvari` | Module-scoped permission grants | observable | FLEET | SEC-8 |
| GIMLE-312 | `gimle-andvari` | Node pull-only artifact access, scoped to active assignments | internal | UNIT | `AndvariServerTlsTest#a_nodes_group_certificate_may_pull_only_coordinates_assigned_to_its_node` |
| GIMLE-313 | `gimle-andvari` | Dual audit logging for push/delete decisions | observable | FLEET | ART-3 |
| GIMLE-314 | `gimle-andvari` | Andvari's own console session story (`/auth/*`, bundled SPA) | observable | FLEET | SEC-9 |
| GIMLE-315 | `gimle-andvari` | mTLS server with dynamic TLS reload | internal | UNIT | `AndvariServerTlsTest#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server` |
| GIMLE-316 | `gimle-andvari` | Plaintext-mode loud supply-chain warning | observable | FLEET | OPS-2 |
| GIMLE-317 | `gimle-andvari` | Andvari observability instrumentation and Muninn shipping | internal | UNIT | `AndvariObservabilityTest` — `a_real_request_is_recorded_in_andvari_metrics`, `every_registered_route_is_independently_tagged` |
| GIMLE-318 | `gimle-andvari` | Process status endpoint (no RBAC gate) | observable | FLEET | OPS-11 |
| GIMLE-319 | `gimle-muninn` | Node platform-log ingest | observable | FLEET | OBS-3 |
| GIMLE-320 | `gimle-muninn` | Instance-log ingest | observable | FLEET | OBS-3 |
| GIMLE-321 | `gimle-muninn` | Node/instance log read with cursor paging | observable | FLEET | OBS-3 |
| GIMLE-322 | `gimle-muninn` | `follow=true` rejection on Muninn reads | observable | FLEET | OBS-3 |
| GIMLE-323 | `gimle-muninn` | Metrics ingest | observable | FLEET | OBS-4 |
| GIMLE-324 | `gimle-muninn` | Metrics read | observable | FLEET | OBS-4 |
| GIMLE-325 | `gimle-muninn` | Traces ingest | observable | FLEET | OBS-5 |
| GIMLE-326 | `gimle-muninn` | Traces read | observable | FLEET | OBS-5 |
| GIMLE-327 | `gimle-muninn` | Day-bucketed JSON-lines store with oldest-first cursor semantics | internal | UNIT | `MuninnDayFileStoreTest#lines_spanning_two_days_land_in_two_separate_day_files`, `#a_late_arriving_line_appends_into_the_existing_day_file_rather_than_overwriting_it` |
| GIMLE-328 | `gimle-muninn` | All-or-nothing batch validation on ingest | internal | UNIT | `MuninnDayFileStoreTest#a_malformed_line_rejects_the_whole_batch_and_writes_nothing`; `MuninnServerLogsIngestTest#a_malformed_batch_is_rejected_entirely_and_nothing_from_it_is_readable` |
| GIMLE-329 | `gimle-muninn` | Windows-safe on-disk path sanitization for colon-bearing processId | internal | UNIT | `MuninnDayFileStoreTest#a_subtree_path_containing_a_colon_round_trips_without_an_invalid_path_error` `MuninnDayFileStoreTest#a_process_id_containing_an_underscore_and_a_colon_survives_the_directory_name_escape`. |
| GIMLE-330 | `gimle-muninn` | Path-segment validation / directory-traversal defense | internal | UNIT | `MuninnServerLogsIngestTest#an_invalid_node_id_path_segment_is_rejected_before_touching_the_filesystem`, `MuninnServerMetricsIngestTest#an_invalid_process_kind_path_segment_is_rejected_before_touching_the_filesystem`, `MuninnServerTracesIngestTest` (same) |
| GIMLE-331 | `gimle-muninn` | Age-based retention sweep | internal | UNIT | `RetentionSweeperTest#a_day_file_older_than_the_retention_window_is_deleted`, `#a_day_file_within_the_retention_window_survives`, `#sweeping_twice_is_idempotent...`, `#sweeping_a_data_root_that_does_not_exist_yet_is_a_no_op` |
| GIMLE-332 | `gimle-muninn` | Plaintext-default transport with loud unauthenticated-mode warning | observable | FLEET | OPS-2 |
| GIMLE-333 | `gimle-muninn` | mTLS transport mode | observable | FLEET | OPS-5 |
| GIMLE-334 | `gimle-muninn` | Zero-downtime TLS material reload on certificate rotation | internal | UNIT | `MuninnServerTlsTest#reloading_tls_material_lets_a_fresh_connection_succeed_without_restarting_the_server` |
| GIMLE-335 | `gimle-muninn` | Node-identity check on node-log ingest | internal | UNIT | NONE recorded in the baseline |
| GIMLE-336 | `gimle-muninn` | Instance-owner check on instance-log ingest | internal | UNIT | NONE recorded in the baseline |
| GIMLE-337 | `gimle-muninn` | Verified-certificate-presence check on metrics/traces ingest | internal | UNIT | NONE recorded in the baseline |
| GIMLE-338 | `gimle-muninn` | Read surface has no RBAC/authorization re-check (documented-vs-actual gap) | out-of-scope | OUT OF SCOPE | documented-limitation |
| GIMLE-339 | `gimle-muninn` | `/status` operational endpoint | observable | FLEET | OPS-11 |
| GIMLE-340 | `gimle-observability` | Default OpenTelemetry tracer installation | internal | UNIT | `GimleTracingTest#install_is_idempotent_and_yields_a_working_tracer` |
| GIMLE-341 | `gimle-observability` | Configurable, batched span exporter installation | internal | UNIT | `GimleTracingInstallTest#install_swaps_in_the_given_exporter_and_a_real_span_reaches_it` |
| GIMLE-342 | `gimle-observability` | Bounded-wait tracer flush | internal | UNIT | `GimleTracingInstallTest#flush_forces_the_batch_processor_to_export_before_the_next_periodic_tick`, `#flush_before_any_install_is_a_noop` |
| GIMLE-343 | `gimle-observability` | Periodic log-file shipping to Muninn | observable | FLEET | OBS-3 |
| GIMLE-344 | `gimle-observability` | Periodic Micrometer metrics shipping | observable | FLEET | OBS-4 |
| GIMLE-345 | `gimle-observability` | One-shot trace-batch and prepared-batch shipping | internal | UNIT | `MuninnShipperTest#ship_trace_batch_is_a_one_shot_post_with_no_periodic_ticking`, `#ship_prepared_batch_posts_the_given_body_verbatim_with_no_periodic_ticking`, `#ship_prepared_batch_is_a_noop_for_an_empty_body` |
| GIMLE-346 | `gimle-observability` | Multi-endpoint best-effort fan-out shipping | internal | UNIT | `MuninnShipperTest#a_batch_ships_to_every_configured_endpoint`, `#a_batch_still_lands_on_reachable_endpoints_when_one_configured_endpoint_is_down` |
| GIMLE-347 | `gimle-observability` | In-memory (non-persisted) log-shipping cursor | internal | UNIT | NONE recorded in the baseline |
| GIMLE-348 | `gimle-observability` | Micrometer meter → NDJSON codec | internal | UNIT | `MeterSnapshotCodecTest#one_line_per_meter_with_the_meters_own_name`, `#a_timer_with_percentiles_ships_a_percentiles_map`, `#a_timer_without_percentiles_omits_the_percentiles_key`, `#an_empty_registry_produces_an_empty_string` |
| GIMLE-349 | `gimle-observability` | OpenTelemetry span → NDJSON codec | internal | UNIT | `SpanLineCodecTest#one_line_per_span_with_attributes_flattened_onto_it`, `#an_empty_batch_produces_an_empty_string` |
| GIMLE-350 | `gimle-observability` | `MuninnSpanExporter` (OpenTelemetry SDK integration) | internal | UNIT | `MuninnSpanExporterTest#a_real_span_batch_reaches_the_stub_ingest_server_with_the_expected_shape`, `#export_never_throws_even_when_shipping_fails` |
| GIMLE-351 | `gimle-observability` | JFR-based per-module CPU/allocation attribution | observable | FLEET | OBS-4 |
| GIMLE-352 | `gimle-observability` | Per-process tagged Micrometer metrics wrappers | internal | UNIT | `AgentMetricsTest`, `ApiServerMetricsTest`, `WorkerMetricsTest`, `StoreMetricsTest`, `FafnirMetricsTest` (e.g. `#record_request_increments_count_and_records_latency`, `#request_latency_timer_publishes_percentiles_for_muninn_shipping`, `#error_counter_is_not_created_when_no_error_ever_recorded`, `#different_endpoints_and_verbs_are_tagged_independently`) |
| GIMLE-353 | `gimle-observability` | WorkerMetrics thread-count / metaspace gauges | observable | FLEET | DEP-4 |
| GIMLE-354 | `gimle-observability` | Fafnir authz-failure counter (rate-limiting signal) | internal | UNIT | `FafnirMetricsTest#authz_failures_are_recorded_and_tagged_by_verb_only`, `#authz_failure_count_is_zero_before_any_failure_is_recorded` |
| GIMLE-355 | `gimle-observability` | Muninn endpoint list parsing from config | internal | UNIT | NONE recorded in the baseline |
| GIMLE-356 | `gimle-gateway` | Fabric-route HTTP-to-service dispatch | observable | FLEET | NET-4 |
| GIMLE-357 | `gimle-gateway` | Fabric-route argument coercion (`ParamType`) | observable | FLEET | NET-4 |
| GIMLE-358 | `gimle-gateway` | Vessel-route HTTP reverse-proxy dispatch | observable | FLEET | NET-4 |
| GIMLE-359 | `gimle-gateway` | Vessel-endpoint resolution with TTL cache | internal | UNIT | `VesselEndpointCacheTest#a_call_within_the_ttl_does_not_relay_again`, `#a_call_past_the_ttl_relays_again` |
| GIMLE-360 | `gimle-gateway` | Round-robin load balancing over ready vessel endpoints | observable | FLEET | NET-4 |
| GIMLE-361 | `gimle-gateway` | Stale-cache fallback on endpoint-refresh failure | internal | UNIT | `VesselEndpointCacheTest#a_non_2xx_refresh_falls_back_to_the_stale_cached_list`, `#a_terminal_relay_status_with_nothing_cached_yet_is_a_clear_error`, `#an_unparsable_relay_body_with_nothing_cached_yet_is_a_clear_error` |
| GIMLE-362 | `gimle-gateway` | Vessel-route error surfacing (no ready endpoint / connect failure) | observable | FLEET | NET-4 |
| GIMLE-363 | `gimle-gateway` | Route-table config DSL parsing | observable | UNIT | `GatewayRouteConfigTest#parses_a_mix_of_fabric_and_vessel_routes_ignoring_blank_lines_and_comments`, `#an_unknown_kind_token_is_rejected`, `#a_fabric_line_with_the_wrong_number_of_fields_is_rejected`, `#a_non_integer_fabric_version_is_rejected`, `#a_fabric_param_type_outside_the_v1_restriction_is_rejected_at_parse_time` |
| GIMLE-364 | `gimle-gateway` | Duplicate route-path rejection at config-parse time | observable | FLEET | NET-4 |
| GIMLE-365 | `gimle-gateway` | Gateway HTTP server bootstrap via module lifecycle hooks | internal | UNIT | NONE recorded in the baseline |
| GIMLE-366 | `gimle-gateway` | Gateway liveness and readiness probes | observable | FLEET | NET-4 |
| GIMLE-367 | `gimle-gateway` | HTTP status-code error mapping across the dispatcher | observable | FLEET | NET-4 |
| GIMLE-368 | `gimle-gateway` | Boot-only platform-layer JPMS workaround (`requires static`) | internal | UNIT | Indirectly covered by `RealBundledHookAndProbeInvocationTest` in `gimle-worker` (per CLAUDE.md, established for the same pattern in `greeter-provider`/`greeter-consumer`); no dedicated gateway-specific test found in `gimle-gateway` itself |
| GIMLE-369 | `gimle-gateway` | Vessel proxy: no TLS, no header forwarding (v1 scope limitation) | out-of-scope | OUT OF SCOPE | documented-limitation |
| GIMLE-370 | `gimle-gateway` | Fabric route "quiet success" ambiguity for a misrouted service name | out-of-scope | OUT OF SCOPE | documented-limitation |
| GIMLE-371 | `gimle-cli` | Deployment resource management (get/apply/delete) | observable | FLEET | DEP-1, CHAOS-2 |
| GIMLE-372 | `gimle-cli` | Job resource management (get/apply/delete) | observable | FLEET | BATCH-1, CHAOS-2 |
| GIMLE-373 | `gimle-cli` | CronJob management incl. manual trigger | observable | FLEET | BATCH-2, CHAOS-2 |
| GIMLE-374 | `gimle-cli` | DaemonSet resource management | observable | FLEET | BATCH-3, CHAOS-2 |
| GIMLE-375 | `gimle-cli` | StatefulSet resource management | observable | FLEET | BATCH-5, CHAOS-2 |
| GIMLE-376 | `gimle-cli` | Node inventory and cordon/uncordon | observable | FLEET | SCHED-3 |
| GIMLE-377 | `gimle-cli` | Instance lifecycle event timeline | observable | FLEET | DEP-1, OBS-9 |
| GIMLE-378 | `gimle-cli` | Tenant management and quota configuration | observable | FLEET | GOV-1 |
| GIMLE-379 | `gimle-cli` | Tenant plain configuration key/value store | observable | FLEET | SEC-5, SEC-6 |
| GIMLE-380 | `gimle-cli` | Versioned secrets management (Fafnir proxy) | observable | FLEET | SEC-1 |
| GIMLE-381 | `gimle-cli` | Artifact registry client (push/list/get/delete) | observable | FLEET | ART-1 |
| GIMLE-382 | `gimle-cli` | Log viewing and live tailing | observable | FLEET | OBS-1 |
| GIMLE-383 | `gimle-cli` | Audit trail query | observable | FLEET | GOV-5 |
| GIMLE-384 | `gimle-cli` | RBAC role management | observable | FLEET | GOV-3 |
| GIMLE-385 | `gimle-cli` | RBAC role binding management | observable | FLEET | GOV-3, GOV-4 |
| GIMLE-386 | `gimle-cli` | Operator account management | observable | FLEET | GOV-3, GOV-4 |
| GIMLE-387 | `gimle-cli` | Certificate lifecycle management (bootstrap token, CSR request/status/approve, renewal) | observable | FLEET | SEC-7 |
| GIMLE-388 | `gimle-cli` | Dual table/JSON output formatting | observable | FLEET | CHAOS-3 |
| GIMLE-389 | `gimle-cli` | kubectl-shaped global flag parsing, manifest-kind apply dispatch, and mTLS/leader-aware HTTP client | internal | UNIT | `GimleCliTest.a_bare_invocation_with_no_verb_prints_usage_rather_than_a_server_configuration_error`, `missing_server_configuration_is_a_clear_error`, `an_unreachable_control_plane_produces_a_clear_error_and_nonzero_exit`, `a_malformed_server_response_produces_a_clear_error_not_a_stack_trace`, `a_404_produces_a_clear_error_and_nonzero_exit`, `unknown_verb_prints_usage_and_nonzero_exit` |
| GIMLE-390 | `gimle-hilmir` | Topology validation (`hilmir validate`) | observable | FLEET | OPS-4 |
| GIMLE-391 | `gimle-hilmir` | Cluster launch planning (`hilmir plan`) | observable | FLEET | OPS-4 |
| GIMLE-392 | `gimle-hilmir` | Real multi-process cluster bring-up (`hilmir up`) | observable | FLEET | OPS-2, OPS-4 |
| GIMLE-393 | `gimle-hilmir` | Cluster teardown and status reporting (`hilmir down`/`status`) | observable | FLEET | OPS-3, OPS-4, CHAOS-7 |
| GIMLE-394 | `gimle-hilmir` | Cluster TLS/PKI bootstrap (`hilmir pki init`) | observable | FLEET | OPS-5 |
| GIMLE-395 | `gimle-hilmir` | Raft store membership add (`hilmir store add`) | observable | FLEET | OPS-6 |
| GIMLE-396 | `gimle-hilmir` | Raft store membership remove (`hilmir store remove`) | observable | FLEET | OPS-6 |
| GIMLE-397 | `gimle-hilmir` | Per-machine platform binary rolling upgrade with quorum-safe store restart (`hilmir upgrade-cluster`) | observable | FLEET | OPS-7 |
| GIMLE-398 | `gimle-hilmir` | Bundle-based fresh release deployment (`hilmir deploy`) | observable | FLEET | OPS-12 |
| GIMLE-399 | `gimle-hilmir` | Bundle upgrade with automatic resource pruning (`hilmir upgrade`) | observable | FLEET | OPS-12 |
| GIMLE-400 | `gimle-hilmir` | Release rollback to a prior revision (`hilmir rollback`) | observable | FLEET | OPS-12 |
| GIMLE-401 | `gimle-hilmir` | Full release teardown (`hilmir undeploy`) | observable | FLEET | OPS-12 |
| GIMLE-402 | `gimle-hilmir` | Release listing (`hilmir releases`) | observable | FLEET | OPS-12 |
| GIMLE-403 | `gimle-hilmir` | Release status inspection (`hilmir release-status`) | observable | FLEET | OPS-12 |
| GIMLE-404 | `gimle-hilmir` | GitOps directory reconciliation (`hilmir sync`, incl. `--watch` and `--prune`) | observable | FLEET | OPS-12 |
| GIMLE-405 | `gimle-hilmir` | `--watch` interval loop for sync | observable | FLEET | OPS-12 |
| GIMLE-406 | `gimle-hilmir` | Bundle value templating and override precedence (`${values.*}` substitution) | observable | FLEET | OPS-12 |
| GIMLE-407 | `gimle-hilmir` | Bundle manifest schema parsing and validation | observable | FLEET | OPS-12 |
| GIMLE-408 | `gimle-hilmir` | Workload readiness polling for `--wait` | observable | FLEET | OPS-12 |
| GIMLE-409 | `gimle-hilmir` | Doctor static deployability diagnostics (`hilmir doctor`) | observable | FLEET | DEV-1 |
| GIMLE-410 | `gimle-hilmir` | Doctor cluster-aware checks (`--server`, `--tenant`) | observable | FLEET | DEV-1 |
| GIMLE-411 | `gimle-hilmir` | Manifest scaffolding (`hilmir init`) | observable | FLEET | DEV-1 |
| GIMLE-412 | `gimle-hilmir` | Gateway extension enable (`hilmir enable gateway`) | observable | FLEET | OPS-13 |
| GIMLE-413 | `gimle-hilmir` | Gateway extension disable (`hilmir disable gateway`) | observable | FLEET | OPS-13 |
| GIMLE-414 | `gimle-hilmir` | Bundled JRE resolution for platform-binary launches | internal | UNIT | `BundledJreResolverTest` (6 tests); `LaunchPlannerTest` (2 tests) |
| GIMLE-415 | `gimle-hilmir` | `java @argfile` command-line rewriting | internal | UNIT | `JavaArgFileTest` (2 tests) |
| GIMLE-416 | `gimle-hilmir` | Run ledger persistence for `up`/`down`/`status`/`upgrade-cluster` | internal | UNIT | `RunLedgerTest` (9 tests) |
| GIMLE-417 | `gimle-hilmir` | TCP-connect readiness polling | internal | UNIT | `ReadinessPollerTest` (4 tests) |
| GIMLE-418 | `gimle-maven-plugin` | `mvn gimle:agent` — spawn a real node agent (plus its worker command tail) | observable | FLEET | DEV-2 |
| GIMLE-419 | `gimle-maven-plugin` | `mvn gimle:bootstrap` — full local-dev cluster orchestration in one foreground command | observable | FLEET | DEV-2 |
| GIMLE-420 | `gimle-maven-plugin` | Process-launcher Maven goals for individual platform processes (`controlplane`/`store`/`fafnir`/`muninn`/`andvari`/`tls-init`) | observable | FLEET | DEV-2 |
| GIMLE-421 | `gimle-maven-plugin` | `mvn gimle:deploy` — apply a deployment manifest via a real CLI subprocess | observable | FLEET | DEV-2 |
| GIMLE-422 | `gimle-maven-plugin` | `mvn gimle:doctor` — run hilmir doctor against the invoking project's own built jar | observable | FLEET | DEV-1 |
| GIMLE-423 | `gimle-maven-plugin` | `mvn gimle:init` — scaffold manifests for the invoking project's own built jar | observable | FLEET | DEV-1 |
| GIMLE-424 | `gimle-maven-plugin` | `mvn gimle:publish` — push a built module jar to the artifact registry | observable | FLEET | DEV-2 |
| GIMLE-425 | `gimle-maven-plugin` | `mvn gimle:docs` — full documentation site build pipeline | observable | FLEET | DEV-5 |
| GIMLE-426 | `gimle-maven-plugin` | `mvn gimle:flaky-tests` — run known-flaky-tagged tests in isolated standalone reactors | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-427 | `gimle-maven-plugin` | `mvn gimle:saga` — ensure a Saga test-report server is running | observable | FLEET | DEV-4 |
| GIMLE-428 | `gimle-maven-plugin` | `mvn gimle:verify` — full build run under Saga tracking | observable | FLEET | DEV-4 |
| GIMLE-429 | `gimle-maven-plugin` | `mvn gimle:saga-import` — standalone sweep-and-import of existing surefire reports | observable | FLEET | DEV-4 |
| GIMLE-430 | `gimle-maven-plugin` | `mvn gimle:saga-stop` — best-effort local Saga server shutdown | observable | FLEET | DEV-4 |
| GIMLE-431 | `gimle-maven-plugin` | Internal — Aether-based cross-module runtime classpath resolution | internal | UNIT | NONE recorded in the baseline |
| GIMLE-432 | `gimle-maven-plugin` | Internal — host-matching java/mvn executable resolution and subprocess supervision | internal | UNIT | `GimleProcessesTest` (6 tests) |
| GIMLE-433 | `gimle-maven-plugin` | Internal — git commit/branch capture for run identification | internal | UNIT | NONE recorded in the baseline |
| GIMLE-434 | `gimle-maven-plugin` | Internal — surefire report discovery and totals aggregation, including flaky-testcase counting | internal | UNIT | `SurefireReportsTest` |
| GIMLE-435 | `gimle-console` | Operator session login / logout | observable | FLEET | GOV-6, SEC-9 |
| GIMLE-436 | `gimle-console` | Session bootstrap & 401 handling | observable | FLEET | GOV-6, SEC-9 |
| GIMLE-437 | `gimle-console` | Cluster Overview dashboard | observable | FLEET | DEP-1, OBS-7 |
| GIMLE-438 | `gimle-console` | Tactical HUD / Signal display-mode toggle | observable | FLEET | OBS-7 |
| GIMLE-439 | `gimle-console` | Deployments list/create/detail/delete | observable | FLEET | DEP-1, DEP-12 |
| GIMLE-440 | `gimle-console` | Jobs (run-to-completion workload) list | observable | FLEET | BATCH-1, OBS-7 |
| GIMLE-441 | `gimle-console` | CronJobs list/detail | observable | FLEET | BATCH-2, OBS-7 |
| GIMLE-442 | `gimle-console` | DaemonSets list/detail | observable | FLEET | BATCH-3, OBS-7 |
| GIMLE-443 | `gimle-console` | StatefulSets list/detail | observable | FLEET | BATCH-5, OBS-7 |
| GIMLE-444 | `gimle-console` | Instances table with filtering (global + node/tenant-scoped) | observable | FLEET | DEP-1, OBS-7 |
| GIMLE-445 | `gimle-console` | Nodes list/detail with capacity bars and staleness | observable | FLEET | SCHED-3, SCHED-6, OBS-7 |
| GIMLE-446 | `gimle-console` | Tenants list/detail with quota management and delete | observable | FLEET | GOV-1, OBS-7 |
| GIMLE-447 | `gimle-console` | Topology placement map | observable | FLEET | OBS-6 |
| GIMLE-448 | `gimle-console` | Cluster metrics charts (lifecycle mix, capacity, quota pressure) | observable | FLEET | OBS-4 |
| GIMLE-449 | `gimle-console` | Per-process metrics history (Muninn-backed) | observable | FLEET | OBS-4 |
| GIMLE-450 | `gimle-console` | Trace span history viewer | observable | FLEET | OBS-5 |
| GIMLE-451 | `gimle-console` | Log explorer with live tailing | observable | FLEET | OBS-1 |
| GIMLE-452 | `gimle-console` | Crash-dump (hs_err) listing on Logs screen | observable | FLEET | OBS-2 |
| GIMLE-453 | `gimle-console` | Config entries management (per-tenant) | observable | FLEET | SEC-5, SEC-6, OBS-7 |
| GIMLE-454 | `gimle-console` | Secrets management (Fafnir-backed, versioned) | observable | FLEET | SEC-1, OBS-7 |
| GIMLE-455 | `gimle-console` | Module artifact registry browser (Andvari-backed) | observable | FLEET | ART-1, OBS-7 |
| GIMLE-456 | `gimle-console` | RBAC access control (roles, role bindings, accounts) | observable | FLEET | GOV-3, GOV-6, OBS-7 |
| GIMLE-457 | `gimle-console` | Audit trail viewer with filtering | observable | FLEET | GOV-5, OBS-7 |
| GIMLE-458 | `gimle-console` | Control-plane status panel | observable | FLEET | OBS-7 |
| GIMLE-459 | `gimle-console` | Theme toggle (light/dark) | observable | FLEET | OBS-7 |
| GIMLE-460 | `gimle-console` | Playwright end-to-end smoke suite against a real cluster | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-461 | `gimle-fafnir-console` | Vault operator login/logout (session-cookie auth) | observable | FLEET | SEC-9, OBS-8 |
| GIMLE-462 | `gimle-fafnir-console` | Vault status overview (uptime, active key, transport mode, tenants) | observable | FLEET | SEC-3, OBS-8 |
| GIMLE-463 | `gimle-fafnir-console` | Secrets browsing/reveal/version/write/destroy (vault-native UI) | observable | FLEET | OBS-8 |
| GIMLE-464 | `gimle-fafnir-console` | Tenant filter via URL search param | observable | FLEET | OBS-8 |
| GIMLE-465 | `gimle-fafnir-console` | Key rotation trigger | observable | FLEET | SEC-3 |
| GIMLE-466 | `gimle-fafnir-console` | Fafnir console error banner / global error capture | observable | FLEET | OBS-8 |
| GIMLE-467 | `gimle-andvari-console` | Andvari operator login/logout (session-cookie auth) | observable | FLEET | SEC-9, OBS-8 |
| GIMLE-468 | `gimle-andvari-console` | Registry status overview (uptime, transport, recent pushes) | observable | FLEET | ART-1, OBS-8 |
| GIMLE-469 | `gimle-andvari-console` | Artifact catalog browsing & search | observable | FLEET | ART-1 |
| GIMLE-470 | `gimle-andvari-console` | Artifact version detail (download, checksum display, delete) | observable | FLEET | ART-1, ART-3 |
| GIMLE-471 | `gimle-andvari-console` | Client-side SHA-256 checksum verification on download | observable | FLEET | ART-1 |
| GIMLE-472 | `gimle-andvari-console` | Push artifact dialog (drag-and-drop upload) | observable | FLEET | ART-1 |
| GIMLE-473 | `gimle-andvari-console` | Maven-2 repository interop view | observable | FLEET | ART-6 |
| GIMLE-474 | `gimle-andvari-console` | Andvari copy-to-clipboard utility | observable | FLEET | ART-1 |
| GIMLE-475 | `gimle-saga-console` | Runs list (no authentication) | observable | FLEET | DEV-4 |
| GIMLE-476 | `gimle-saga-console` | Live run detail with streaming test feed | observable | FLEET | DEV-4 |
| GIMLE-477 | `gimle-saga-console` | Run attachments: Gherkin scenario tree, Chaos ledger, Surtr phase table | observable | FLEET | DEV-4 |
| GIMLE-478 | `gimle-saga-console` | Test detail / per-test history | observable | FLEET | DEV-4 |
| GIMLE-479 | `gimle-saga-console` | Compare two runs (diff view) | observable | FLEET | DEV-4 |
| GIMLE-480 | `gimle-saga-console` | Gjallarhorn flake scoreboard | observable | FLEET | DEV-4 |
| GIMLE-481 | `gimle-saga-console` | Saga console theming (no auth surface) | observable | FLEET | DEV-4 |
| GIMLE-482 | `gimle-saga` | NDJSON event ingest API | observable | FLEET | DEV-4 |
| GIMLE-483 | `gimle-saga` | Idempotent per-run ingest / re-ingest replacement | internal | UNIT | `SagaStoreTest.java#re_ingesting_a_whole_run_replaces_it_without_double_counting_the_ledger` |
| GIMLE-484 | `gimle-saga` | Crash-safe append (torn-tail recovery) | internal | UNIT | `SagaStoreTest.java#a_torn_trailing_line_is_skipped_on_read`, `#an_append_after_a_torn_line_never_fuses_two_events_into_one` |
| GIMLE-485 | `gimle-saga` | Surefire/Failsafe XML import | observable | FLEET | DEV-4 |
| GIMLE-486 | `gimle-saga` | Fold-import safety net for a live run's gap | internal | UNIT | `SagaStoreTest.java#fold_appends_only_test_ids_the_live_stream_never_finished_and_drops_framing`, `#fold_without_an_existing_run_ingests_the_batch_unmodified` |
| GIMLE-487 | `gimle-saga` | Run listing, detail, and cursor-paginated event reads | observable | FLEET | DEV-4 |
| GIMLE-488 | `gimle-saga` | Live NDJSON tail (`follow=true`) of a run's event stream | observable | FLEET | DEV-4 |
| GIMLE-489 | `gimle-saga` | Abandoned-run detection on restart | internal | UNIT | `SagaStoreTest.java#a_live_run_is_marked_abandoned_at_startup` |
| GIMLE-490 | `gimle-saga` | Flake ledger derivation (fail-then-pass rule) and rebuild | internal | UNIT | `SagaStoreTest.java#a_failed_attempt_followed_by_a_passing_retry_yields_one_flake_observation`, `#a_test_that_fails_every_attempt_yields_no_flake_observation`, `#rebuild_ledger_reproduces_the_derived_observations_from_scratch`, `#an_unparseable_ledger_line_is_skipped_not_fatal` |
| GIMLE-491 | `gimle-saga` | Flaky scoreboard with time-window ranking | observable | FLEET | DEV-4 |
| GIMLE-492 | `gimle-saga` | Test-tag index and quarantine status | observable | FLEET | DEV-4 |
| GIMLE-493 | `gimle-saga` | Per-test history endpoint | observable | FLEET | DEV-4 |
| GIMLE-494 | `gimle-saga` | Path traversal protection on run IDs | internal | UNIT | `SagaStoreTest.java#a_run_id_that_could_escape_the_store_directory_is_rejected` |
| GIMLE-495 | `gimle-saga` | Bundled console static serving | observable | FLEET | DEV-4 |
| GIMLE-496 | `gimle-testkit` | Poll-until-condition primitive (`Await`) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-497 | `gimle-testkit` | Kernel-assigned loopback port leasing (`PortLease`) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-498 | `gimle-testkit` | Heimdall event-driven cluster condition harness | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-499 | `gimle-testkit` | Replica-scoped condition observation | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-500 | `gimle-testkit` | Deployment/node/log condition builders | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-501 | `gimle-testkit` | Time-windowed negative invariants (`Invariant`/`InvariantGuard`) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-502 | `gimle-testkit` | Forensic failure reporting | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-503 | `gimle-examples` | `hello-module` — minimal inert deployable fixture | observable | FLEET | JRN-6 |
| GIMLE-504 | `gimle-examples` | `greeter-provider` — real fabric service export with lifecycle hooks and health probes | observable | FLEET | JRN-6 |
| GIMLE-505 | `gimle-examples` | `greeter-consumer` — real cross-worker fabric call with MDC-tagged background caller | observable | FLEET | JRN-6 |
| GIMLE-506 | `gimle-examples` | `greeter-load-generator` — HTTP bridge for external load tools driving real fabric traffic | observable | FLEET | SCHED-7, JRN-6 |
| GIMLE-507 | `gimle-smoke-tests` | Real multi-process cluster fixture (store/control-plane/agent/Fafnir/Muninn) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-508 | `gimle-smoke-tests` | On-the-fly compiled module variants via `TestModuleBuilder` | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-509 | `gimle-smoke-tests` | Base cluster topology deploy across store cluster and multiple CP replicas | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-510 | `gimle-smoke-tests` | Raft store resilience (member loss, leader failover, live membership change) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-511 | `gimle-smoke-tests` | Tiered self-healing (worker respawn, liveness-exhaustion escalation to FAILED) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-512 | `gimle-smoke-tests` | Classloader leak detection wired into a real worker | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-513 | `gimle-smoke-tests` | Repeated redeploy stability without false-positive leaks | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-514 | `gimle-smoke-tests` | Tier 1 worker density packing and its cap | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-515 | `gimle-smoke-tests` | Node cordoning blocks new placement without evicting running instances | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-516 | `gimle-smoke-tests` | DaemonSet per-node fan-out and dead-node assignment cleanup | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-517 | `gimle-smoke-tests` | Job and CronJob real-cluster lifecycle | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-518 | `gimle-smoke-tests` | StatefulSet sticky placement and volume persistence across worker restart | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-519 | `gimle-smoke-tests` | Rolling update preserves serving capacity and reaches new version | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-520 | `gimle-smoke-tests` | Surge worker promotion carries out via in-place retarget, not respawn | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-521 | `gimle-smoke-tests` | Autoscaling under real request-rate, error-rate, queue-depth, and weighted-blended load | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-522 | `gimle-smoke-tests` | Multi-tenant quota enforcement (flag-not-evict, and admission rejection) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-523 | `gimle-smoke-tests` | Circuit breaker excludes a consistently-failing replica | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-524 | `gimle-smoke-tests` | Gossip/SWIM failure detection across real separate agent processes | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-525 | `gimle-smoke-tests` | Observability data survives agent death (Muninn fallback) and control-plane metrics round-trip | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-526 | `gimle-smoke-tests` | Worker-tier metrics/trace relay to Muninn via the agent | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-527 | `gimle-smoke-tests` | Artifact registry (Andvari) resolution path end to end | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-528 | `gimle-smoke-tests` | External HTTP request reaches a fabric service through the gateway | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-529 | `gimle-holmgang` | Declarative cluster topology DSL/YAML parsing and validation | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-530 | `gimle-holmgang` | Real subprocess cluster orchestration (`GimleCluster`) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-531 | `gimle-holmgang` | Cluster pooling per topology with destructive-scenario isolation | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-532 | `gimle-holmgang` | JUnit `@Holmgang`/`@HolmgangCluster` extension for plain-JUnit cluster tests | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-533 | `gimle-ragnarok` | Fenrir randomized chaos-fault soak executor | observable | FLEET | CHAOS-9 |
| GIMLE-534 | `gimle-holmgang` | Chaos ledger recording and rendering | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-535 | `gimle-holmgang` | Randomized fault soak with no lost writes (basic and compound-fault modes) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-536 | `gimle-holmgang` | Muninn/Andvari replica-bounce resilience soak | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-537 | `gimle-holmgang` | Live store membership change (AddServer/RemoveServer) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-538 | `gimle-holmgang` | Mutual TLS end-to-end operation and anonymous-client rejection | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-539 | `gimle-holmgang` | Control-plane partition tolerance (store-side) and reconvergence on heal | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-540 | `gimle-holmgang` | Store leader self-demotion under silent peer partition; bounded write latency | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-541 | `gimle-holmgang` | Tenant deployment lifecycle with secret delivery and clean deletion | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-542 | `gimle-holmgang` | Tenant quota retroactive violation (flag, not evict) and admission rejection | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-543 | `gimle-holmgang` | Node cordoning blocks placement until uncordoned | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-544 | `gimle-holmgang` | Worker-tier self-healing and liveness-exhaustion escalation (Gherkin coverage) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-545 | `gimle-holmgang` | Zero-downtime rolling update under surge budget (Gherkin coverage) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-546 | `gimle-holmgang` | Request-rate autoscaling under real Gatling-driven fabric load (Gherkin coverage) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-547 | `gimle-holmgang` | Artifact registry coordinate-only deployment (Gherkin coverage) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-548 | `gimle-ragnarok` | Surtr scale/churn/performance workload runner | observable | FLEET | CHAOS-9 |
| GIMLE-549 | `gimle-holmgang` | Surtr Muninn-window measurement (documented gap) | out-of-scope | OUT OF SCOPE | documented-limitation |
| GIMLE-550 | `gimle-holmgang` | Module-density Tier 1 packing Surtr reference workload | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-551 | `gimle-holmgang` | Saga unified run reporting (Gherkin + JUnit + Fenrir + Surtr) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-552 | `gimle-holmgang` | Saga best-effort shipping to a remote report server | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-553 | `gimle-holmgang` | Loki fault-injection proxy for store/control-plane link partitions | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-554 | `gimle-holmgang` | Utgard multi-container distributed boot ordering | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-555 | `gimle-holmgang` | Utgard real machine loss (hard container kill) and rejoin | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-556 | `gimle-holmgang` | Utgard network partition (vs hard kill) with reconvergence | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-557 | `gimle-holmgang` | Utgard real-hostname mTLS bootstrap across containers | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-558 | `gimle-holmgang` | Utgard Docker container fleet management primitives | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-559 | `gimle-holmgang` | Docker Compose manual validation topologies (bundled-JRE and full-JRE) | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-560 | `gimle-dist` | Standalone CLI distribution archive | observable | FLEET | OPS-1 |
| GIMLE-561 | `gimle-dist` | Standalone Hilmir bootstrap-tool distribution archive | observable | FLEET | OPS-1 |
| GIMLE-562 | `gimle-dist` | Cluster-machine platform distribution archive | observable | FLEET | OPS-1 |
| GIMLE-563 | `gimle-dist` | Opt-in bundled-JRE distribution variant (`dist-with-jre` profile) | observable | FLEET | OPS-8 |
| GIMLE-564 | `gimle-dist` | Distribution archive checksums and SBOM generation | observable | FLEET | OPS-1 |
| GIMLE-565 | `gimle-mimir` | Norn deterministic virtual-time Raft fault-injection simulation | out-of-scope | OUT OF SCOPE | test-harness |
| GIMLE-566 | `gimle-controlplane` | Service abstraction: stable name, CRUD API, and endpoint reconciliation | observable | FLEET | NET-1, JRN-1 |
| GIMLE-567 | `gimle-fabric` | Fabric listener-side tenant re-check on inbound service calls | observable | FLEET | NET-6 |
| GIMLE-568 | `gimle-agent` | gimle-bifrost: per-node service proxy (kube-proxy analogue) | observable | FLEET | NET-3 |
| GIMLE-569 | `gimle-skald` | gimle-skald: cluster DNS server resolving Service names to live endpoints | observable | FLEET | NET-2 |
| GIMLE-570 | `gimle-gateway` | Gateway virtual-host routing and Service-backed (SERVICE) route kind | observable | FLEET | NET-4 |
| GIMLE-571 | `gimle-module` | Hosted-module runtime port reporting folded into instance observation | observable | FLEET | NET-1 |
| GIMLE-572 | `gimle-mimir` | NetworkPolicySpec durable persistence through StoreClient | observable | FLEET | NET-6 |
| GIMLE-573 | `gimle-hilmir` | Doctor advisory-only outbound-connection hazard detection | observable | FLEET | DEV-1 |
| GIMLE-574 | `gimle-fabric` | Per-deployment-scoped NetworkPolicySpec enforcement | observable | FLEET | NET-6 |
| GIMLE-575 | `gimle-agent` | Bifrost fails closed for a NetworkPolicySpec-restricted Service | observable | FLEET | NET-7 |
| GIMLE-576 | `gimle-hilmir` | Remote (SSH) fleet bootstrap (`hilmir up/down/status --remote`) | observable | FLEET | OPS-9 |
| GIMLE-577 | `gimle-andvari` | Multi-jar publish with per-module tenant tagging (`kind: ArtifactSet`) | observable | FLEET | BATCH-7 |
| GIMLE-578 | `gimle-cli` | Service CRUD and live endpoint lookup | observable | FLEET | NET-1 |
| GIMLE-579 | `gimle-cli` | NetworkPolicy CRUD | observable | FLEET | NET-6 |
| GIMLE-580 | `gimle-hilmir` | `hilmir upgrade-cluster --remote` (SSH-dispatched platform binary rollout) | observable | FLEET | OPS-7 |
| GIMLE-581 | `gimle-controlplane` | ConfigMap store and API with optimistic-concurrency writes | observable | FLEET | SEC-5 |
| GIMLE-582 | `gimle-mimir` | Deployment `configMapRefs` field with admission-time collision rejection | observable | FLEET | SEC-5 |
| GIMLE-583 | `gimle-agent` | Narrowed config delivery to instances declaring `configMapRefs` | observable | FLEET | SEC-5 |
| GIMLE-584 | `gimle-cli` | `gimle configmap` command | observable | FLEET | SEC-5 |
| GIMLE-585 | `gimle-console` | ConfigMaps screen | observable | FLEET | SEC-5, OBS-7 |
| GIMLE-586 | `gimle-console` | Service CRUD and live endpoint lookup (Networking screen) | observable | FLEET | NET-1, OBS-7 |
| GIMLE-587 | `gimle-console` | NetworkPolicy CRUD (Networking screen) | observable | FLEET | NET-6, OBS-7 |
| GIMLE-588 | `gimle-fafnir` | SecretMap store and `/secretmaps/*` API | observable | FLEET | SEC-5 |
| GIMLE-589 | `gimle-mimir` | Deployment `secretMapRefs` field with admission-time collision rejection | observable | FLEET | SEC-5 |
| GIMLE-590 | `gimle-controlplane` | `/secretmaps/*` proxy and `ResourceKind.SECRETMAP` RBAC | observable | FLEET | SEC-5 |
| GIMLE-591 | `gimle-agent` | Narrowed secret delivery via `secretMapRefs` | observable | FLEET | SEC-5 |
| GIMLE-592 | `gimle-cli` | `gimle secretmap` command | observable | FLEET | SEC-5 |
| GIMLE-593 | `gimle-console` | SecretMaps screen | observable | FLEET | SEC-5, OBS-7 |
| GIMLE-594 | `gimle-fafnir` | SecretMap group-version ledger and rollback | observable | FLEET | SEC-5 |
| GIMLE-595 | `gimle-cli` | `secretmap versions`/`secretmap rollback` verbs | observable | FLEET | SEC-5 |
| GIMLE-596 | `gimle-console` | SecretMaps screen History panel | observable | FLEET | SEC-5 |
| GIMLE-597 | `gimle-fafnir` | Sealed SecretMap envelope crypto and key retirement | observable | FLEET | SEC-4 |
| GIMLE-598 | `gimle-fafnir` | `/seal/*` and key-retirement HTTP routes | observable | FLEET | SEC-4 |
| GIMLE-599 | `gimle-controlplane` | `/seal/*` and `/secrets/retire-key` proxy routes | observable | FLEET | SEC-4 |
| GIMLE-600 | `gimle-cli` | `gimle seal` command, `secret retire-key`, `secretmap seal` verbs | observable | FLEET | SEC-4 |
| GIMLE-601 | `gimle-mimir` | ControllerRevision history and Deployment/StatefulSet/DaemonSet rollback | observable | FLEET | DEP-2, DEP-3, BATCH-4 |
| GIMLE-602 | `gimle-cli` | `deployment`/`statefulset`/`daemonset` `revisions`/`rollback` verbs | observable | FLEET | DEP-2, DEP-3, BATCH-4 |
| GIMLE-603 | `gimle-agent` | Sleipnir: agent-managed JDK AOT startup cache for worker JVMs | observable | FLEET | OPS-14 |
| GIMLE-604 | `gimle-mimir` | LimitRange: per-workload resource min/max bound, admission check, and reconciler | observable | FLEET | GOV-2 |
| GIMLE-605 | `gimle-cli` | `limitrange` get/set/delete verbs | observable | FLEET | GOV-2 |
| GIMLE-606 | `gimle-mimir` | Group commit via batched mutations (StateMutation.Batch / proposeAll) | internal | UNIT | `MutationBatchTest#an_empty_batch_is_rejected`, `#a_nested_batch_is_rejected`, `#a_batch_applies_its_mutations_in_order`, `#propose_all_of_an_empty_list_proposes_nothing`, `#propose_all_of_a_single_mutation_proposes_it_bare_not_wrapped`, `#propose_all_of_several_mutations_proposes_one_batch_carrying_them_in_order`, `#a_batched_proposal_is_one_log_entry_and_applies_every_mutation`, `RaftCodecTest#round_trips_a_batch_mutation_through_a_log_entry` |
| GIMLE-607 | `gimle-controlplane` | Admission-time rejection of a manifest/artifact module-identity mismatch | observable | FLEET | DEV-3 |
| GIMLE-608 | `gimle-andvari` | Bundle artifacts: multi-file vessel applications as one zipped, entrypoint-carrying coordinate | observable | FLEET | BATCH-8 |
| GIMLE-609 | `gimle-mimir` | Manifest apiVersion: optional per-kind versioning with a permanent v1alpha1 default | observable | FLEET | DEV-3 |
| GIMLE-610 | `gimle-mimir` | Workload manifest v1: artifactPath rejected, artifact-registry resolution enforced, alpha use deprecated with surfaced warnings | observable | FLEET | DEV-3 |
| GIMLE-611 | `gimle-dist` | Midgard Docker dev-cluster distribution archive | observable | FLEET | OPS-1, OPS-2 |
| GIMLE-612 | `gimle-os` | Volume reclaim policy: Retain-by-default persistent volume release | observable | FLEET | BATCH-5, BATCH-6, JRN-4 |
| GIMLE-613 | `gimle-skald` | DNS-over-TCP fallback with UDP truncation | observable | FLEET | NET-2 |
| GIMLE-614 | `gimle-controlplane` | Self-subject access review endpoint (/authz/can-i) | observable | FLEET | GOV-3 |
| GIMLE-615 | `gimle-core` | Per-tenant built-in role templates (tenant-view/edit/admin) | observable | FLEET | GOV-3 |
| GIMLE-616 | `gimle-module` | Instance identity on ModuleContext (downward API) | observable | FLEET | DEV-6 |
| GIMLE-617 | `gimle-module` | Config key enumeration on ModuleContext | observable | FLEET | DEV-6 |
| GIMLE-618 | `gimle-agent` | Bifrost off-node service exposure (NodePort analogue) | observable | FLEET | NET-3 |
| GIMLE-619 | `gimle-agent` | Live config and secret propagation to running instances | observable | FLEET | SEC-6 |
| GIMLE-620 | `gimle-skald` | SRV records and headless A answers | observable | FLEET | NET-2 |
| GIMLE-621 | `gimle-controlplane` | Cluster-wide volume operator surface (/volumes API + CLI) | observable | FLEET | BATCH-5, BATCH-6 |
| GIMLE-622 | `gimle-agent` | Soft volume disk-usage observation in instance heartbeats | observable | FLEET | BATCH-5 |
| GIMLE-623 | `gimle-fabric` | NetworkPolicy interface scoping and egress enforcement | observable | FLEET | NET-6 |
| GIMLE-624 | `gimle-controlplane` | Certificate revocation denylist | observable | FLEET | SEC-7 |
| GIMLE-625 | `gimle-controlplane` | Workload identity: store-backed per-deployment tokens (ServiceAccount analogue) | observable | FLEET | JRN-7 |
| GIMLE-626 | `gimle-agent` | Bifrost locality-preferred forwarding and ClientIP session affinity | observable | FLEET | NET-3 |
| GIMLE-627 | `gimle-agent` | Bifrost TLS identity-verifying mode with tenant-membership client certificates | observable | FLEET | NET-10 |
| GIMLE-628 | `gimle-controlplane` | ExternalName Services resolved via Skald CNAME and Bifrost forwarding | observable | FLEET | NET-2 |
| GIMLE-629 | `gimle-agent` | Vessel persistent volumes and secret-backed file mounts | observable | FLEET | BATCH-8 |
| GIMLE-630 | `gimle-module` | Multi-volume modules: named volumes and dataDirectory(name) | observable | FLEET | DEV-6, BATCH-5, JRN-4 |
| GIMLE-631 | `gimle-controlplane` | StatefulSet/DaemonSet machine-level self-healing on node death | observable | FLEET | SCHED-6 |
| GIMLE-632 | `gimle-console` | Toast notifications render app-wide (write failures, and every other toast call site) | observable | FLEET | DEP-12 |
| GIMLE-633 | `gimle-mimir` | Node agents may read their currently-assigned tenants' config/configmap with no default RoleBinding | observable | FLEET | SEC-12 |
| GIMLE-634 | `gimle-mimir` | The control plane's own leaf certificate may read the artifact registry with no default RoleBinding | observable | FLEET | SEC-12 |
| GIMLE-635 | `gimle-hilmir` | hilmir scopes -h/--help the same way gimle-cli already does, instead of treating it as an unrecognized token | observable | FLEET | OPS-1, CHAOS-4 |
| GIMLE-636 | `gimle-examples` | orders-platform's NetworkPolicy example documents both the raw API and the gimle set networkpolicy CLI form, with the CLI's required --deny-all-callers flag spelled out explicitly | observable | FLEET | DEV-5, JRN-1 |
| GIMLE-637 | `gimle-cli` | gimle get statefulsets/daemonsets render clean table columns by default, matching gimle get deployments, instead of dumping each row's raw spec/instances JSON per cell | observable | FLEET | BATCH-9, CHAOS-3 |
| GIMLE-638 | `gimle-examples` | node-local-cache's flag-consumer logs its very first FeatureFlagCache lookup failure at INFO, not WARN, since it's an expected membership-propagation race, not a fault | observable | FLEET | JRN-5 |
| GIMLE-639 | `gimle-ragnarok` | Chaos-plan and target YAML configuration for Fenrir/Surtr | observable | FLEET | CHAOS-9 |
| GIMLE-640 | `gimle-ragnarok` | Bundled pause-image reference module for stress testing | observable | FLEET | CHAOS-9 |
| GIMLE-641 | `gimle-ragnarok` | ragnarok CLI: preflight/chaos/stress/replay/report verbs | observable | FLEET | CHAOS-9 |
| GIMLE-642 | `gimle-dist` | Standalone Ragnarok distribution archive | observable | FLEET | OPS-1, CHAOS-9 |
| GIMLE-643 | `gimle-ragnarok` | SSH-backed managed-inventory ClusterTarget for real process control | observable | FLEET | CHAOS-9 |
| GIMLE-644 | `gimle-ragnarok` | Real iptables host-firewall network faults over SSH | observable | FLEET | CHAOS-9 |
| GIMLE-645 | `gimle-ragnarok` | Admin Fault API -- SSH-free WORKER_KILL via a node agent's own authenticated HTTP surface | observable | FLEET | CHAOS-9 |
| GIMLE-646 | `gimle-mimir` | Deployment writes (apply/delete/rollback) are generation-guarded compare-and-set, closing the concurrent apply/delete lost-update race | observable | FLEET | CHAOS-5 |
| GIMLE-647 | `gimle-console` | Console instances surface their own workerId, and deep-link into the Metrics/Traces WORKER process picker | observable | FLEET | DEP-5, OBS-10 |
| GIMLE-648 | `gimle-controlplane` | Node Taints / Tenant Tolerations (Kubernetes-Pattern Scheduler Reservation) | observable | FLEET | SCHED-1 |
| GIMLE-649 | `gimle-controlplane` | Plaintext Transport Is Explicitly Single-Tenant | observable | FLEET | GOV-8 |
| GIMLE-650 | `gimle-mimir` | Implicit Default Tenant for Untenanted Workloads | observable | FLEET | OPS-2, GOV-8 |
| GIMLE-651 | `gimle-fafnir` | Explicit SecretMap Replace Verb | observable | FLEET | SEC-5 |
| GIMLE-652 | `gimle-mimir` | Deleting a Workload Clears Its Revision History | observable | FLEET | DEP-6 |
| GIMLE-653 | `gimle-cli` | CLI Flag Errors Always Show Usage | observable | FLEET | CHAOS-4 |
| GIMLE-654 | `gimle-mimir` | Tenant-scoped resource keying (compound (tenantId, name) store key) | observable | FLEET | GOV-9 |
| GIMLE-655 | `gimle-os` | Tenant-scoped StatefulSet persistent volume identity | observable | FLEET | BATCH-5, GOV-9 |
| GIMLE-656 | `gimle-controlplane` | Tenant-scoped heartbeat instance-observation matching and instance-log node resolution | observable | FLEET | GOV-9 |
| GIMLE-657 | `gimle-controlplane` | Explicit ?tenant= query parameter honored on single-resource GET/DELETE and endpoints lookup | observable | FLEET | GOV-9 |
| GIMLE-658 | `gimle-controlplane` | CronJob-generated Jobs run through tenant quota/limit-range admission | observable | FLEET | BATCH-2 |
| GIMLE-659 | `gimle-mimir` | KindDefinition mechanism: a manifest teaches the cluster a new custom kind (prefix-normalized, durably stored, catalogued) | observable | FLEET | JRN-7 |
| GIMLE-660 | `gimle-controlplane` | Schema-validated custom-resource admission: defaults persisted, unknown keys and bound violations rejected, tenant scope enforced, identical re-apply a generation no-op | observable | FLEET | JRN-7 |
| GIMLE-661 | `gimle-core` | Per-kind RBAC via the CUSTOM_RESOURCE permission qualifier ({kind} for specs, {kind}/status for status only) | observable | FLEET | JRN-7 |
| GIMLE-662 | `gimle-module` | Operator status loop: a hosted module polls its kind through the workload-identity relay and reports per-resource status | observable | FLEET | JRN-7 |
| GIMLE-663 | `gimle-cli` | CLI custom-kind surface: gimle kinds, declared-name noun resolution, apply fallthrough with bounded 409 retry, printColumns tables | observable | FLEET | JRN-7 |
| GIMLE-664 | `gimle-console` | Console Custom Resources screen: kind picker, printColumns instance table, spec/status detail pane with the generation/observedGeneration signal | observable | FLEET | OBS-7, JRN-7 |
| GIMLE-665 | `gimle-cli` | Single-resource CLI verbs reject more than one positional argument instead of silently truncating | observable | FLEET | CHAOS-4 |
| GIMLE-666 | `gimle-worker` | A liveness/readiness probe class that fails to load forces the module to FAILED with a durable event | observable | FLEET | DEP-10 |
| GIMLE-667 | `gimle-core` | Console session logout revokes the session token server-side, not just the client-side cookie | observable | FLEET | SEC-9 |
| GIMLE-668 | `gimle-agent` | A NetworkPolicy change closes an already-open Bifrost connection, not just future ones | observable | FLEET | NET-7 |
| GIMLE-669 | `gimle-controlplane` | Node-death instance eviction is throttled against the deployment's own DisruptionBudget | observable | FLEET | SCHED-6 |
| GIMLE-670 | `gimle-controlplane` | CronJob prunes its own terminal generated Jobs to configurable successful/failed history limits | observable | FLEET | BATCH-2 |
| GIMLE-671 | `gimle-fafnir` | A soft-deleted flat Secret can be undeleted, restoring the current or an explicit earlier version | observable | FLEET | SEC-2 |
| GIMLE-672 | `gimle-fabric` | Gossip service-catalog anti-entropy performs a real paginated full-state sync, not a partial one | internal | UNIT | `ServiceCatalogTest` and `GossipMemberTest` gain new anti-entropy coverage. Full gimle-fabric module suite re-verified (133 tests, 0 failures/errors); the new tests confirmed to fail against the pre-fix code. |
| GIMLE-673 | `gimle-controlplane` | Plain Config and ConfigMap entries have version history and rollback, the same as Secrets/SecretMaps | observable | FLEET | SEC-5 |
| GIMLE-674 | `gimle-controlplane` | Crash-loop backoff and reschedule for StatefulSet and DaemonSet instances (self-healing parity with Deployment) | observable | FLEET | SCHED-5 |
| GIMLE-675 | `gimle-controlplane` | DaemonSet opt-in taint toleration (tolerateAllTaints) | observable | FLEET | BATCH-3, SCHED-1 |
| GIMLE-676 | `gimle-fabric` | Background gossip rejoin after a seed-list join startup blip | internal | UNIT | `GossipMemberTest#several_unreachable_seeds_do_not_throw_and_leave_the_node_running_unjoined`; `GossipMemberTest#a_node_still_isolated_after_join_returns_finds_its_seed_once_it_recovers`. |
| GIMLE-677 | `gimle-fafnir` | SecretMap batch handlers signal partial failure via HTTP status and CLI exit code | observable | FLEET | SEC-5 |
| GIMLE-678 | `gimle-mimir` | Deleting a Role cascades to every RoleBinding naming it | observable | FLEET | GOV-4 |
| GIMLE-679 | `gimle-gateway` | Gateway route table reloads on a config change without a restart | observable | FLEET | NET-4 |
| GIMLE-680 | `gimle-controlplane` | Job retry attempts are gated by exponential backoff instead of retrying every reconcile tick | observable | FLEET | BATCH-1 |
| GIMLE-681 | `gimle-agent` | Vessel config drift (env/args/jvmFlags/files/probes/resources) is detected on reassignment, not just moduleId/artifactPath | observable | FLEET | BATCH-8 |
| GIMLE-682 | `gimle-controlplane` | A rolling update's disruption budget genuinely throttles concurrent migrations, immune to a flapping replacement | observable | FLEET | SCHED-8 |
| GIMLE-683 | `gimle-controlplane` | Instance readiness requires a stabilization window of continuous observed readiness, not a single heartbeat | observable | FLEET | DEP-7 |
| GIMLE-684 | `gimle-gateway` | Gateway route dispatch supports longest-prefix-match routing for VESSEL/SERVICE routes, not exact-literal-path-only | observable | FLEET | NET-4 |
| GIMLE-685 | `gimle-fabric` | Cross-worker service lookup applies the same version-aware cutover as the same-worker tier during a hot redeploy | observable | FLEET | DEP-2, SCHED-8 |
| GIMLE-686 | `gimle-skald` | Skald tracks control-plane poll staleness and degrades DNS answers once it is severely stale | observable | FLEET | NET-2, CHAOS-10 |
| GIMLE-687 | `gimle-core` | JVM DNS resolver cache capped to match Skald's own DNS-answer TTL | internal | UNIT | `DnsCacheTtlTest#sets_the_security_property_to_five_seconds` and `#applying_twice_is_idempotent` in gimle-core, asserting the Security property is set correctly and that repeated calls are safe. |
| GIMLE-688 | `gimle-fabric` | FabricServer bounds in-flight connections instead of spawning an unbounded virtual thread per accept | internal | UNIT | `FabricServerTest#a_connection_beyond_the_max_connections_limit_is_throttled_until_a_permit_frees` and `#a_malformed_frame_connection_releases_its_permit_the_same_as_a_well_formed_one` (composition proof with GIMLE-689). Full gimle-fabric, gimle-agent, and gimle-worker module suites re-verified. |
| GIMLE-689 | `gimle-fabric` | FabricServer catches a malformed frame's decode failure instead of letting it crash the connection thread | internal | UNIT | `FabricServerTest#a_malformed_frame_closes_the_connection_cleanly_and_the_server_keeps_serving_other_connections` and `#a_malformed_frame_connection_releases_its_permit_the_same_as_a_well_formed_one` (composition proof with GIMLE-688). Full gimle-fabric module suite re-verified. |
| GIMLE-690 | `gimle-fafnir` | resolvePrincipal only honors a forwarded principal from a genuine control-plane peer certificate | observable | FLEET | SEC-8 |
| GIMLE-691 | `gimle-muninn` | MuninnServer independently authorizes every log/metrics/traces read instead of trusting mere reachability on its own port | observable | FLEET | SEC-8, CHAOS-8 |
| GIMLE-692 | `gimle-fafnir` | FafnirServer authorizes cluster-wide secrets key rotation and retirement | observable | FLEET | SEC-3 |
| GIMLE-693 | `gimle-controlplane` | CronJobReconciler scopes its generated-Job firing lookup by tenant | observable | FLEET | GOV-9 |
| GIMLE-694 | `gimle-controlplane` | StatefulSetReconciler and DaemonSetReconciler compare artifactPath in their rolling-update staleness check | observable | FLEET | BATCH-4 |
| GIMLE-695 | `gimle-worker` | ProbeLoop gives each check key its own ticker thread instead of a shared platform-wide pool | internal | UNIT | `ProbeLoopTest#a_handful_of_permanently_hung_keys_do_not_starve_ticking_for_another_key` -- confirmed failing against the pre-fix shared-pool code, passing after the fix. Every pre-existing TestScheduler-driven test in the file re-verified unaffected. |
| GIMLE-696 | `gimle-controlplane` | AutoscaleReconciler gates on node heartbeat freshness before trusting an instance observation | internal | UNIT | `AutoscaleReconcilerTest#a_dead_nodes_frozen_observation_is_not_averaged_into_the_scale_decision` -- confirmed failing (scaled up regardless of staleness) against the pre-fix code, passing after the fix. Full autoscale test suite re-verified. |
| GIMLE-697 | `gimle-agent` | VesselProcessSupervisor resets its restart budget once a respawned vessel stays up past a stability threshold | internal | UNIT | `VesselProcessSupervisorTest#a_respawn_that_stays_up_past_the_stability_threshold_resets_the_backoff`, mirroring WorkerProcessSupervisorTest's own identically-named test and its proven gap-timing assertions. |
| GIMLE-698 | `gimle-observability` | MuninnShipper's log-shipping cursor no longer permanently drops a line sharing its exact predecessor's timestamp | internal | UNIT | `MuninnShipperTest#two_lines_sharing_the_exact_same_timestamp_across_ticks_are_both_shipped` -- verified to fail against the pre-fix tickLogs, passes with the fix; both lines shipped exactly once each and the cursor genuinely catches up. Full gimle-observability module suite re-verified. |
| GIMLE-699 | `gimle-muninn` | MuninnDayFileStore reads tolerate a day file removed by a concurrent retention sweep instead of surfacing a 500 | internal | UNIT | `MuninnDayFileStoreTest#a_day_file_removed_by_a_concurrent_retention_sweep_is_skipped_not_thrown` -- a second day file is repeatedly recreated and deleted from a background thread while the main thread reads 300 times in a loop, asserting neither readAfter nor readOlder ever throws. Full gimle-muninn module suite re-verified. |
| GIMLE-700 | `gimle-fabric` | CircuitBreaker closes on a success recorded while still OPEN, not only from HALF_OPEN | internal | UNIT | `CircuitBreakerTest#a_success_recorded_while_still_open_closes_the_breaker` and `#a_success_recorded_while_open_also_resets_the_backoff_to_the_base_cooldown`. Full gimle-fabric module suite re-verified. |
| GIMLE-701 | `gimle-mimir` | Operator-facing cluster backup/restore (gimle backup create/restore, GET /backup, PUT /restore) | observable | FLEET | OPS-10 |
| GIMLE-702 | `gimle-pki` | A CSR's requested Subject Alternative Name is trusted only up to what the connecting request can verify | observable | FLEET | SEC-7 |
| GIMLE-703 | `gimle-mimir` | RaftCodec/StoreCodec reject a wire-protocol version mismatch instead of silently misdecoding | internal | UNIT | RaftCodecTest#rejects_an_unrecognized_rpc_version_before_decoding_the_tag and #rejects_an_unrecognized_snapshot_version; StoreCodecTest#rejects_an_unrecognized_version_before_decoding_the_tag -- each forges a frame carrying an out-of-range version byte and asserts GimleCodecException naming both the declared and max-supported version, decoded before any tag/field is touched. Full gimle-mimir module suite (500+ pre-existing cases across both codecs) re-verified against the new framing. |
| GIMLE-704 | `gimle-controlplane` | Certificate-request approval and node/operator join are recorded in the durable audit trail | observable | FLEET | GOV-5, SEC-7 |
| GIMLE-705 | `gimle-agent` | Per-worker raw stdout/stderr SYSTEM capture is size/count-rotated instead of growing unbounded | internal | UNIT | WorkerProcessSupervisorSystemLogRotationTest drives a real subprocess (ChattyWorkerDriver) flooding stdout against a deliberately tiny gimle.log.maxFileSizeBytes, asserting a real .1 rotated file appears, the active file never grows past the cap, and rotated content survives through LogFileReader's ordinary rotated-file-aware read path. Full gimle-agent module suite (157 tests) re-verified. |
| GIMLE-706 | `gimle-mimir` | gimle-controlplane, gimle-mimir, and gimle-agent each expose an operator-pollable health signal | observable | FLEET | OPS-4, OPS-11, CHAOS-7, CHAOS-10 |
| GIMLE-707 | `gimle-mimir` | Audit-trail ring-buffer eviction is observable: logged, counted, and surfaced in the GET /audit response | internal | UNIT | Full gimle-mimir (574 tests), gimle-controlplane (606 tests), and gimle-cli (120 tests) module suites re-verified against the new envelope shape and every call site that parses GET /audit's body (gimle-holmgang's ClusterApi, ApiServerCustomKindsTest) was updated to match. No dedicated new eviction-at-50000-events test in this pass (impractical to run at full scale in a unit test); the envelope shape itself is exercised end to end by the updated call sites. |
| GIMLE-708 | `gimle-core` | Password hashes carry their own iteration count, so raising PasswordHashes.ITERATIONS never breaks an existing hash | internal | UNIT | PasswordHashesTest gained verify_still_succeeds_against_a_hash_produced_at_a_lower_iteration_count (hand-builds an old-count hash via the same PBKDF2WithHmacSHA256 primitive, independent of PasswordHashes' own hash()), plus needsRehash coverage for a today's-count hash, a lower-count hash, and a malformed blob. Full gimle-core (156 tests), gimle-pki (30 tests), gimle-controlplane (606 tests), gimle-fafnir (189 tests), and gimle-andvari module suites re-verified against the changed wire format -- every PasswordHashes.hash()/verify() call site in those modules round-trips through the new format transparently. |
| GIMLE-709 | `gimle-controlplane` | A group: RoleBinding subject now authorizes a session-cookie-authenticated (console/CLI-login) principal, not only a certificate-authenticated one | observable | FLEET | GOV-3 |
| GIMLE-710 | `gimle-console` | The console's Metrics and Instances screens surface per-instance error rate, which the control plane already shipped on the wire but no console type or screen ever read | observable | FLEET | OBS-4 |
| GIMLE-711 | `gimle-controlplane` | A declarative AlertRule primitive: a threshold on one deployment's observed signal that posts a webhook notification when crossed and again when resolved | observable | FLEET | OBS-9 |
| GIMLE-712 | `gimle-observability` | WorkerMetrics evicts a module's Micrometer meters on uninstall, so repeated redeploy no longer accumulates one permanent meter set per (module, version) forever | observable | FLEET | DEP-4 |
| GIMLE-713 | `gimle-console` | Job and CronJob console screens gain a create form, closing the console-only creation gap that previously forced apply -f as the only way to create either kind | observable | FLEET | BATCH-1, BATCH-2 |
| GIMLE-714 | `gimle-console` | Deployment/DaemonSet/StatefulSet detail pages gain a revision-history panel with rollback, exposing the already-real ControllerRevision/rollback API that previously had no console surface at all | observable | FLEET | DEP-2, DEP-3, BATCH-4 |
| GIMLE-715 | `gimle-console` | Node detail/list screens gain cordon/uncordon and taint/untaint controls, exposing the already-real node-scheduling API that previously had no console surface (and whose already-served cordoned/taints fields were silently discarded on the wire) | observable | FLEET | SCHED-3 |
| GIMLE-716 | `gimle-controlplane` | GET /tenants and /tenants/{id} expose real, server-computed usage (memoryBytes/cpuMillicores/instances) and a quotaViolating flag, closing the gap where the console's Tenants screens could show only configured limits, never actual consumption | observable | FLEET | GOV-1 |
| GIMLE-717 | `gimle-cli` | apply -f now covers Service, NetworkPolicy, Tenant, LimitRange, Role, RoleBinding, and Account manifests, closing the gap where these seven kinds needed their own bespoke gimle set <kind> flag-based command and had no manifest-driven creation path at all | observable | FLEET | GOV-12 |
| GIMLE-718 | `gimle-cli` | gimle get <workload-kind> <name> -o manifest projects the status response back into a re-appliable manifest, and apply -f - reads a manifest from stdin, closing the round-trip gap where get's own output could never be fed back into apply | observable | FLEET | DEP-11 |
| GIMLE-719 | `gimle-fabric` | Fabric calls retry only where retrying is provably safe, with server-side correlationId deduplication | internal | UNIT | InvocationDeduplicatorTest, FabricServerDeduplicationTest, FabricServiceRegistryRetryTest (+ RetryableGreeter fixture), FabricClientTest connect-time vs mid-call classification. FabricServiceRegistryTest's spillover test was changed from a refused port to an accept-then-hang-up endpoint so it still exercises a mid-call failure rather than being satisfied by the new connect-time failover. |
| GIMLE-720 | `gimle-fabric` | Per-endpoint circuit breaker state is visible as logs and shipped Micrometer meters | observable | FLEET | NET-8 |
| GIMLE-721 | `gimle-skald` | Cluster DNS answers NODATA, not NXDOMAIN, for a declared Service with no live endpoints | observable | FLEET | NET-2 |
| GIMLE-722 | `gimle-gateway` | Gateway TLS selects a per-virtual-host certificate from the client's SNI extension | observable | FLEET | NET-5 |
| GIMLE-723 | `gimle-mimir` | Autoscale policies carry scale-up and scale-down stabilization windows backed by durable last-scale state | observable | FLEET | SCHED-7 |
| GIMLE-724 | `gimle-mimir` | CronJob schedules can be suspended without deleting and recreating them | observable | FLEET | BATCH-2 |
| GIMLE-725 | `gimle-module` | Health probe interval, timeout and liveness failure threshold are declarable per module | observable | FLEET | DEV-1, DEP-8 |
| GIMLE-726 | `gimle-mimir` | Instance event history is cleared when a workload is removed, so a reused name starts clean | observable | FLEET | DEP-6 |
| GIMLE-727 | `gimle-core` | RBAC permissions accept a wildcard sentinel in the resource, verb and tenant-scope positions | observable | FLEET | GOV-3 |
| GIMLE-728 | `gimle-controlplane` | Overlapping Services are announced with a response warning rather than silently allowed | observable | FLEET | NET-1 |
| GIMLE-729 | `gimle-controlplane` | A Service's targetPort is authoritative when declared and genuinely absent when not | observable | FLEET | NET-1 |
| GIMLE-730 | `gimle-controlplane` | NetworkPolicy edits are version-guarded and can add or remove one allow-list entry at a time | observable | FLEET | NET-6 |
| GIMLE-731 | `gimle-controlplane` | A NetworkPolicy may only name tenants that exist | observable | FLEET | NET-6 |
| GIMLE-732 | `gimle-core` | A tenant can be closed to cross-tenant fabric calls before its first NetworkPolicy exists | observable | FLEET | NET-6 |
| GIMLE-733 | `gimle-fafnir` | Every secret version records who wrote it, when, and its declared type | observable | FLEET | SEC-1 |
| GIMLE-734 | `gimle-fafnir` | A secret write may declare its value's shape, validated before anything is stored | observable | FLEET | SEC-1 |
| GIMLE-735 | `gimle-fafnir` | A tenant's whole secret set can be exported and imported in one authorized, audited call | observable | FLEET | SEC-10 |
| GIMLE-736 | `gimle-core` | Secret and config payloads are bounded on both the value and the raw request body | observable | FLEET | SEC-10 |
| GIMLE-737 | `gimle-core` | Logs can be filtered by level threshold and text at the reader, on every surface | observable | FLEET | OBS-1 |
| GIMLE-738 | `gimle-agent` | A deleted config or secret key is retracted from a running instance, and modules can subscribe to changes | observable | FLEET | DEV-6, SEC-6 |
| GIMLE-739 | `gimle-observability` | Trace sampling is configurable and parent-based | internal | UNIT | GimleTracingSamplingTest (new): default ratio records everything, a configured ratio is honoured on every install path, an out-of-range or non-numeric ratio is rejected, and a child span follows its parent's sampling decision rather than re-deciding. |
| GIMLE-740 | `gimle-muninn` | Logs, metrics and traces each retain for their own configurable window | internal | UNIT | RetentionSweeperTest: each subtree swept on its own cutoff, a signal without an override follows the global window, an unknown subtree follows the global window, and sweeping twice or sweeping a missing data root stays a safe no-op. |
| GIMLE-741 | `gimle-console` | A trace can be followed across processes from the console's Traces screen | observable | FLEET | OBS-5 |
| GIMLE-742 | `gimle-pki` | The one-time bootstrap password never reaches a build log or any other persistent sink | observable | FLEET | OPS-5 |
| GIMLE-743 | `gimle-controlplane` | The unauthenticated CSR bootstrap endpoint is rate limited | observable | FLEET | SEC-7 |
| GIMLE-744 | `gimle-controlplane` | Placement and quota failures name the resource dimension, the numbers and the shortfall | observable | FLEET | SCHED-2, GOV-1 |
| GIMLE-745 | `gimle-pki` | A failing certificate rotation check is durably visible with a failure streak, metrics and remaining validity | internal | UNIT | CertificateRotationMonitorTest (8), OwnCertificateRotatorTest (4), CertificateRotationMetricsTest (5), CertificateRotationAuditorTest (5). |
| GIMLE-746 | `gimle-agent` | Tier-1 worker density is an operator-configurable, validated knob | observable | FLEET | OPS-4, DEP-5, SCHED-10 |
| GIMLE-747 | `gimle-gateway` | Gateway route-table and server fields are guarded under one monitor | internal | UNIT | Existing gimle-gateway suite (99 tests) green with spotbugs:check clean; verified against clean master that the two findings pre-existed this change set. |
| GIMLE-748 | `gimle-agent` | A closed Bifrost service listener never serves one more connection | observable | FLEET | NET-3 |
| GIMLE-749 | `gimle-controlplane` | Proxied query parameters are URL-encoded rather than relayed decoded | internal | UNIT | ApiServerLogsFallbackTest covers a text filter containing a space surviving the proxy hop to both the live-agent and Muninn-fallback paths. |
| GIMLE-750 | `gimle-console` | LimitRange management in the web console | observable | FLEET | GOV-2, OBS-7 |
| GIMLE-751 | `gimle-console` | Volumes screen: see and reclaim orphaned StatefulSet volumes | observable | FLEET | BATCH-5, BATCH-6, OBS-7 |
| GIMLE-752 | `gimle-console` | Seal-key lifecycle in the web console | observable | FLEET | SEC-4, OBS-7 |
| GIMLE-753 | `gimle-console` | Instance lifecycle event timeline on the instance detail page | observable | FLEET | DEP-1, OBS-9 |
| GIMLE-754 | `gimle-console` | Per-deployment metrics rollup on the Metrics screen | observable | FLEET | OBS-4 |
| GIMLE-755 | `gimle-console` | Secrets master-key retirement from the console | observable | FLEET | SEC-3 |
| GIMLE-756 | `gimle-controlplane` | Live permission vocabulary endpoint driving the console's Roles picker | observable | FLEET | GOV-3 |
| GIMLE-757 | `gimle-console` | Workload detail pages render bounded, paginated instance tables | observable | FLEET | DEP-12 |
| GIMLE-758 | `gimle-console` | An expired console session is explained once, in plain language | observable | FLEET | SEC-9 |
| GIMLE-759 | `gimle-console` | Console screens keep themselves current | observable | FLEET | DEP-12 |
| GIMLE-760 | `gimle-cli` | Every mutating verb honours -o json, including the node and volume ones | observable | FLEET | CHAOS-3 |
| GIMLE-761 | `gimle-cli` | A failed invocation exits with a code naming why it failed | observable | FLEET | CHAOS-4 |
| GIMLE-762 | `gimle-cli` | gimle logs honours -o json | observable | FLEET | OBS-1 |
| GIMLE-763 | `gimle-cli` | gimle metrics: the per-deployment rollup from the terminal | observable | FLEET | OBS-4 |
| GIMLE-764 | `gimle-cli` | gimle metrics-history and traces-history | observable | FLEET | OBS-4, OBS-5 |
| GIMLE-765 | `gimle-cli` | gimle context: pointing the CLI at more than one cluster | observable | FLEET | CHAOS-4 |
| GIMLE-766 | `gimle-controlplane` | The audit trail pages with an eviction-safe cursor | observable | FLEET | GOV-5 |
| GIMLE-767 | `gimle-cli` | gimle get --watch observes a resource converging | observable | FLEET | DEP-11 |
| GIMLE-768 | `gimle-controlplane` | Dry-run preview for a workload submission | observable | FLEET | DEV-3 |
| GIMLE-769 | `gimle-console` | The Audit screen's since filter sends the timestamp format the API parses | observable | FLEET | GOV-5 |
| GIMLE-770 | `gimle-cli` | `gimle volume destroy` addresses a volume's owning tenant explicitly, instead of silently resolving to whichever tenant the server defaulted to | observable | FLEET | BATCH-6, GOV-9 |
| GIMLE-771 | `gimle-agent` | A volume destroy that removed nothing reports 404 instead of a false success, and a blank `?tenant=` is a real spelling of the untenanted namespace | observable | FLEET | BATCH-6 |
| GIMLE-772 | `gimle-controlplane` | Each `GET /metrics` rollup row names its owning tenant, so two tenants running a same-named deployment are told apart rather than indistinguishable | observable | FLEET | GOV-9 |
| GIMLE-773 | `gimle-console` | A Gateway console screen showing the declared route table and what each route currently resolves to | observable | FLEET | NET-4 |
| GIMLE-774 | `gimle-console` | A Skald DNS console screen showing which `svc.gimle.local` names resolve, and each tracked responder's directory staleness | observable | FLEET | NET-2 |
| GIMLE-775 | `gimle-console` | Console addon screens declare their own sidebar entry, and the sidebar is grouped rather than one flat list | observable | FLEET | NET-4 |
| GIMLE-776 | `gimle-controlplane` | A tenant-scoped Service resolves its endpoints, and its own GET/DELETE, from a bare name, so gateway SERVICE routes, Skald DNS, and ordinary CRUD stop silently answering nothing | observable | FLEET | NET-2, NET-4 |
| GIMLE-777 | `gimle-controlplane` | A control plane advertises only the console addons its `consoleAddons` property names, validated at startup against the console's own bundled catalog | observable | FLEET | NET-4 |
| GIMLE-778 | `gimle-console` | Console addons are a catalog, a registry and a per-addon sidebar group, with a disabled addon explaining itself instead of 404ing | observable | FLEET | NET-4 |
| GIMLE-779 | `gimle-core` | An instance observation carries the declared isolation tier and resource limit, so every read surface can show a usage figure against the ceiling it runs under | observable | FLEET | DEP-1 |
| GIMLE-780 | `gimle-controlplane` | An instance's own service-fabric address is readable through the control plane, so the fabric's listener-side defences can be exercised against a real cluster | observable | FLEET | NET-6 |
| GIMLE-781 | `gimle-controlplane` | Every control-plane API route is rate limited per source address, not only the unauthenticated CSR submission | observable | FLEET | SEC-9 |
| GIMLE-782 | `gimle-agent` | A Service may declare `protocol: UDP`, and gimle-bifrost relays it with per-client session tracking rather than only TCP streams | observable | FLEET | NET-3 |
| GIMLE-783 | `gimle-controlplane` | Workload priority with scheduler preemption, so a critical workload can make room rather than sitting unplaced when the cluster is full | observable | FLEET | SCHED-2 |
| GIMLE-784 | `gimle-skald` | Skald can run as a managed DaemonSet workload behind a UDP Service, not only as its own process kind | observable | FLEET | NET-2 |
| GIMLE-785 | `gimle-controlplane` | Gateway routes are a declarative, versioned Ingress resource rather than only a flat hand-authored config string | observable | FLEET | NET-4 |
| GIMLE-786 | `gimle-agent` | Tier-1 shared workers are sized by a node budget and admit instances by summed declared limits | observable | FLEET | DEP-5, SCHED-10 |
| GIMLE-787 | `gimle-console` | An Applications addon presenting every deployable resource as one application, with health and sync as separate verdicts and a resource tree beneath each | observable | FLEET | DEP-1, BATCH-1, OBS-7, JRN-7 |
| GIMLE-788 | `gimle-controlplane` | Cluster-wide instance lifecycle event read | internal | UNIT | StateStoreTest (6 new), StoreCodecTest (round-trip), StoreNodeTest (2 new), InstanceEventPageTest (8), ApiServerClusterInstanceEventsTest (11), ApiServerAuthzTest (1 new), ApiServerTest (1 updated). |
| GIMLE-789 | `gimle-controlplane` | DaemonSet status reports a reconciler-computed desired (eligible-node) count alongside placed instances | observable | FLEET | BATCH-3 |
| GIMLE-790 | `gimle-controlplane` | A durable, replica-agnostic read of whether an AlertRule is currently firing | observable | FLEET | OBS-9 |
| GIMLE-791 | `gimle-agent` | Per-worker certificates: node-minted worker identity carrying the worker's tenant | observable | FLEET | NET-6 |
| GIMLE-792 | `gimle-controlplane` | ApiServer admits requests under a bounded concurrency budget, with a reserved lane for node-agent traffic | internal | UNIT | ConcurrencyLimiterTest (6 cases, gimle-core) and ApiServerAdmissionControlTest (gimle-controlplane): a real concurrent flood against a real ApiServer resolves entirely to 200/429 with real rejections past the configured budget, while a concurrent node-heartbeat hammer sharing the same process succeeds throughout. |
| GIMLE-793 | `gimle-agent` | Tier-1 shared workers are sized by a node budget and admit instances by summed declared limits | internal | UNIT | New Tier1WorkerBudgetTest (8 tests) covers default fallback, a malformed quantity naming the property it came from, a reserve as large as the heap rejected, budget sizing winning over a first instance's limit, an oversized module keeping its declared heap, summed admission against the post-reserve heap, an empty worker refusing an oversized claim, and cpu deliberately not summed. AgentMainTest gained coverage for: TIER_1 sized by the budget, TIER_2 still sized by the descriptor's limit, reuse refused once residents fill the heap, reuse granted while they still fit, and an oversized module getting a dedicated worker sized at its own limit plus the reserve. |
| GIMLE-794 | `gimle-agent` | The agent's own tick loop exits the process on a fatal Error instead of surviving as a silent zombie | internal | UNIT | AgentMainTest#a_fatal_error_during_a_tick_halts_the_process_with_the_workers_own_oom_exit_code exercises handleFatalTickError directly with a recording stub in place of Runtime.getRuntime()::halt, so the test process itself is not terminated by the assertion. |
| GIMLE-795 | `gimle-agent` | Tenant-scoped instance supervision keying (instanceKey) | internal | UNIT | `AgentMainTest#instance_key_is_scoped_by_tenant_not_just_deployment_name_and_index` |
| GIMLE-796 | `gimle-controlplane` | Control-plane follow-log proxy fails fast on an unreachable agent instead of hanging | internal | UNIT | `ApiServerLogsFallbackTest#follow_true_against_an_unreachable_agent_falls_back_to_muninn_instead_of_hanging`, `#follow_true_against_an_unreachable_agent_fails_fast_with_no_muninn_configured` |
| GIMLE-797 | `gimle-fabric` | A disposed instance's fabric endpoint is actively pruned on redeploy, not left for its circuit breaker to eventually notice | observable | FLEET | DEP-2, DEP-3 |
| GIMLE-798 | `gimle-agent` | A hosted module's own readiness probe result reaches the agent, not just its ACTIVE lifecycle state | observable | FLEET | DEP-7 |
| GIMLE-799 | `gimle-gateway` | Gateway per-host TLS certificate bindings (gateway.tlsCertificates) reload on a config change without a restart | observable | FLEET | NET-5 |
| GIMLE-800 | `gimle-examples` | A bundled example module reports a real listening port, so Midgard ships a real workload a Service can resolve | observable | FLEET | NET-1 |
| GIMLE-801 | `gimle-console` | The New Deployment form keeps a rejected write visible as a persistent inline error, not only an ephemeral toast | observable | FLEET | DEP-12 |
| GIMLE-802 | `gimle-console` | Service creation surfaces the control plane's X-Gimle-Warning header, matching gimle-cli | observable | FLEET | NET-1 |
| GIMLE-803 | `gimle-console` | Topology screen placement badges are labeled by each instance's own instanceIndex, not its position in the response array | observable | FLEET | OBS-6 |
| GIMLE-804 | `gimle-andvari-console` | Push artifact dialog derives the coordinate from the jar's own bundled gimle-module.yaml, rather than trusting a typed one | observable | FLEET | ART-1 |
| GIMLE-805 | `gimle-cli` | CliExtension seam dispatches an unrecognized verb to a ServiceLoader-discovered provider | observable | UNIT | gimle-cli's CliExtensionSeamTest (classpath discovery via a test-only provider, dispatch, help folding, unknown-verb error preserved) and gimle-hugin's HuginExtensionTest. A further CliExtensionSeamTest case pins the scoped `-h` output. |
| GIMLE-806 | `gimle-cli` | An extension is handed a read-only view of the control-plane API, never the client | internal | UNIT | CliExtensionSeamTest asserts structurally that no mutating method appears on ClusterReader. |
| GIMLE-807 | `gimle-hugin` | `gimle top` renders a live, read-only cluster view of nodes and instances | observable | HOLMGANG | `terminal-view.feature` — A running deployment appears in the rendered frame with its real state |
| GIMLE-808 | `gimle-hugin` | A failed poll keeps the last good rows and ages them rather than clearing the screen | observable | UNIT | gimle-hugin's ClusterPollerTest (failure keeps rows and age, recovery clears the marking, the pre-first-poll state, pause/resume) and ClusterScreenTest's stale status-line assertion. |
| GIMLE-809 | `gimle-hugin` | Instance drill-down with lifecycle timeline and a live log tail | observable | UNIT | gimle-hugin's InstanceWatcherTest (backlog-then-follow ordering, the resume cursor, tenant scoping on every route, a failing route, a stream ending on its own) and InstanceScreenTest, plus SnapshotReaderTest's tier/limit parsing cases and InstanceScreenTest's per-tier rendering cases. |
| GIMLE-810 | `gimle-hugin` | Keyboard interaction: selection, filter, pause, refresh, help, and quit restoring the terminal | observable | UNIT | gimle-hugin's UiStateTest. The JLine adapter itself (raw mode, key decoding, resize) is deliberately untested and kept minimal for that reason. Plus UiStateTest's positional-sort cases and InstanceScreenTest's log-filter cases. |
| GIMLE-811 | `gimle-hugin` | Terminal colour is the console's own tokens, degrading to 256-colour and to none | observable | UNIT | gimle-hugin's StatusVariantTest (pins every lifecycle state against the console's mapping and fails when the platform adds one the mapping misses) and PainterTest (exact truecolor output, the 256-colour approximation, and NO_COLOR emitting nothing). |
| GIMLE-812 | `gimle-hugin` | The terminal view ships in the CLI archives and is removable in one directory delete | observable | UNIT | HuginExtensionTest asserts classpath discovery of the shipped provider. The archive layout is verified by building the distribution, not by a test. |
| GIMLE-813 | `gimle-hugin` | The terminal view reports a workload short of replicas, over quota, or rejected by a LimitRange | observable | HOLMGANG | `terminal-view.feature` — A workload the scheduler cannot place is reported rather than silently short; `terminal-view.feature` — A healthy cluster reports nothing unsettled |
| GIMLE-814 | `gimle-hugin` | DaemonSet and StatefulSet instances share the terminal view's instance table with Deployments | observable | UNIT | gimle-hugin's SnapshotReaderTest (all three kinds in one ordered table, an unserved kind costing only its own rows, a DaemonSet's shortfall read from its computed desired count, and a workload carrying neither figure) and ClusterScreenTest (the KIND column, and its removal on a narrow terminal). |
| GIMLE-815 | `gimle-hugin` | A services screen showing each Service's live endpoint resolution | observable | HOLMGANG | `terminal-view.feature` — A Service resolving to no endpoints is reported as the finding it is |
| GIMLE-816 | `gimle-hugin` | An activity view of what has been done to the cluster, over the audit trail | observable | UNIT | gimle-hugin's ActivityReaderTest and ActivityScreenTest. |
| GIMLE-817 | `gimle-hugin` | The activity view reads three cluster records: authorization, lifecycle and alerts | observable | UNIT | gimle-hugin's ActivityReaderTest (all three feeds' parses and their degraded shapes) and ActivityScreenTest (per-feed labelling, headings, colour and width). |
| GIMLE-818 | `gimle-hugin` | The terminal view browses every collection the control plane lists, including registered custom kinds | observable | UNIT | gimle-hugin's ResourceCatalogTest (resolution, custom-kind discovery, collision, degraded discovery, suggestions), ResourceReaderTest (column resolution, the wrapped collection, permission and failure paths), ResourceScreenTest (header, label, filter, permission message, width) and JsonPathTest (the dotted path walk). |
| GIMLE-819 | `gimle-hugin` | The terminal view describes a selected resource as YAML without re-reading it | observable | UNIT | gimle-hugin's YamlTest (nesting, lists, empty containers, null, quoting, escaping) and DescribeScreenTest (the whole object, the title, scrolling and its clamps, width, colour). |
| GIMLE-820 | `gimle-hugin` | The terminal view lists what it can open, and can be pointed at another control plane | observable | UNIT | gimle-cli's ClusterReaderContextTest (context resolution, bare addresses, precedence, refusal) and gimle-hugin's KindsScreenTest and UiStateTest. |
| GIMLE-821 | `gimle-hugin` | The terminal view joins Services to the instances behind them and names the gaps | observable | UNIT | gimle-hugin's XrayTest (the join, both findings, tenant scoping, ancestor-preserving filter) and XrayScreenTest (indentation, wording, counts, width, colour). |
| GIMLE-822 | `gimle-hugin` | The terminal view reads the control plane's own health alongside what it is running | observable | UNIT | gimle-hugin's PulseReaderTest (health, unreachable, the rollup and its permission, orderings) and PulseScreenTest (the wording, both failure directions, width, colour). |
| GIMLE-823 | `gimle-hugin` | The terminal view reads a worker's shipped traces for the instance it is inspecting | observable | UNIT | gimle-hugin's TraceReaderTest (parsing, grouping, the degraded shapes) and TraceScreenTest (the tree shape, the findings, width, colour). |
| GIMLE-824 | `gimle-hugin` | The terminal view narrows every screen to one tenant | observable | UNIT | gimle-hugin's TenantScopeTest (each snapshot's narrowing and what it deliberately leaves alone) and UiStateTest (the scope's lifecycle). |
| GIMLE-825 | `gimle-hugin` | The terminal view scans the cluster for what is wrong | observable | UNIT | gimle-hugin's ScanTest (each finding, its severity, and the cases deliberately not reported) and ScanScreenTest (ordering, counts, the clean-cluster wording and the filtered-to-nothing wording). |
| GIMLE-826 | `gimle-hugin` | The terminal view shows what the calling certificate may do | observable | UNIT | gimle-hugin's PermissionReaderTest (the vocabulary-driven grid, silence never read as denial, the answering identity, escaping and the tenant scope) and PermissionScreenTest (the words in each cell, the unidentified-caller warning, and the unreadable-grid wording). |
| GIMLE-827 | `gimle-hugin` | The terminal view browses a tenant's own config and secret holdings | observable | UNIT | gimle-hugin's ResourceReaderTest (the tenant-scoped route, the redaction in both the cells and the raw object, bare-name responses, and a secret listing's columns). |
| GIMLE-828 | `gimle-hugin` | The terminal view reads a config key's, ConfigMap's or secret's revision history | observable | UNIT | gimle-hugin's VersionReaderTest (all four ledger shapes, ordering, no-ledger against empty, escaping) and VersionScreenTest (the in-effect label, blank rather than invented author and time, and the deleted marker). |
| GIMLE-829 | `gimle-mimir` | Linearizable reads via a Raft read index, replacing round-robin replica reads | observable | FLEET | CHAOS-6 |
| GIMLE-830 | `gimle-worker` | An instance timeline that opens at INSTALLED, names the transition it could not make, and records a liveness-driven restart as its own cause | observable | FLEET | DEP-8, OBS-9 |
| GIMLE-831 | `gimle-controlplane` | An unplaced workload reports the scheduler's own refusal in its own status | observable | FLEET | SCHED-2 |
| GIMLE-832 | `gimle-agent` | Instance logs located by deployment name and index rather than by a composed supervision key | observable | FLEET | SCHED-10, OBS-1 |
| GIMLE-833 | `gimle-controlplane` | A dry run that answers in verdict shape for every rejection, including a manifest the parser refuses | observable | FLEET | CHAOS-1 |
| GIMLE-834 | `gimle-controlplane` | A CSR submission answers 400 naming the field it is missing, never an opaque 500 | observable | FLEET | SEC-7 |
| GIMLE-835 | `gimle-controlplane` | A NetworkPolicy's own owning tenant is validated to exist | observable | FLEET | NET-6 |
| GIMLE-836 | `gimle-fafnir` | A SecretMap's members are unreadable through the flat secrets path | observable | FLEET | SEC-5 |
| GIMLE-837 | `gimle-andvari` | The Maven-shaped repository surface answers HEAD on every path it answers GET on | observable | FLEET | ART-6 |
| GIMLE-838 | `gimle-controlplane` | Placement failure reported against the nodes the operator's own labels actually named | observable | FLEET | SCHED-2, SCHED-3 |
| GIMLE-839 | `gimle-cli` | An ArtifactSet publishes every member it can read and reports the ones it could not | observable | FLEET | BATCH-7 |
| GIMLE-840 | `gimle-module` | A module's own background and config-callback logging carries its instance identity | observable | FLEET | DEV-6, NET-4 |
| GIMLE-841 | `gimle-console` | The Gateway screen finds a gateway DaemonSet by the module it runs, not by its name | observable | FLEET | NET-4 |
| GIMLE-842 | `gimle-skald` | A truncated UDP answer carries the records that fit rather than none | observable | FLEET | NET-2 |
| GIMLE-843 | `gimle-andvari-console` | The registry overview tells a failed or in-flight catalog read apart from an empty one | observable | FLEET | ART-1, OBS-8 |
| GIMLE-844 | `gimle-controlplane` | Node freshness is judged against how long the store could have heard a heartbeat, and reported by the control plane | observable | FLEET | OPS-7 |
| GIMLE-845 | `gimle-fabric` | A fabric target reports its own inbound backlog, and callers select an endpoint per call rather than once at lookup | internal | UNIT | `FabricServiceRegistryTest` gained `a_replica_saturated_by_other_callers_loses_to_an_idle_remote_one`, which stands a backend up reporting a fixed backlog while this caller has nothing in flight to it -- proven to fail against the pre-fix code. `FabricCodecTest` round-trips the new field. |
| GIMLE-846 | `gimle-agent` | A vessel probe's named port survives the assignments round trip | observable | FLEET | BATCH-8 |
| GIMLE-847 | `gimle-agent` | One unparseable assignment is skipped and reported rather than aborting the whole batch | observable | FLEET | BATCH-8 |
| GIMLE-848 | `gimle-agent` | Startup registration is retried with backoff instead of killing the agent | observable | FLEET | CHAOS-10 |
| GIMLE-849 | `gimle-controlplane` | `/health` answers from a background store probe rather than dialing the store inline | observable | FLEET | OPS-1, OPS-11, CHAOS-6 |
| GIMLE-850 | `gimle-controlplane` | An operator can label a running node, and the label counts for placement | observable | FLEET | SCHED-1 |
| GIMLE-851 | `gimle-hilmir` | A release is recorded in the ledger before `--wait`, so a timed-out wait still leaves it undeployable | observable | FLEET | OPS-12 |
| GIMLE-852 | `gimle-controlplane` | A Service declaring no tenant defaults to the default tenant, so it can front its deployments | observable | FLEET | NET-1, GOV-8 |
| GIMLE-853 | `gimle-fafnir` | Retiring a secrets key that still encrypts stored data is refused, and `gimle secret rewrap` re-encrypts under the active key | observable | FLEET | SEC-3 |
| GIMLE-854 | `gimle-mimir` | A peer with an RPC still in flight counts as reachable for check-quorum | internal | UNIT | `RaftNodeVirtualTimeTest#an_established_leader_survives_a_round_trip_that_merely_runs_slow` (a peer held mid-call past the check-quorum window on a virtual clock) |
| GIMLE-855 | `gimle-fabric` | A fabric target's application exception round-trips to its caller, named even when it cannot be loaded or serialized | observable | FLEET | NET-8 |
| GIMLE-856 | `gimle-controlplane` | Quota, LimitRange and `policy.maxReplicasPerDeployment` bind on the `default` tenant too | observable | FLEET | GOV-1, GOV-2, GOV-10 |
| GIMLE-857 | `gimle-controlplane` | A LimitRange body declaring no usable bound, or an unrecognized field, is refused | observable | FLEET | GOV-2, CHAOS-1 |
| GIMLE-858 | `gimle-andvari` | A failed peer-sync or registry call names its real cause instead of the literal text `null` | observable | FLEET | ART-5, ART-8 |
| GIMLE-859 | `gimle-pki` | A failed certificate-rotation check no longer ends renewal for the life of the process | internal | UNIT | `StoreMainResilienceTest`, `FafnirMainResilienceTest`, `MuninnMainResilienceTest` and `AndvariMainResilienceTest` each drive a tick whose rotation check throws and assert the next tick still runs. |
| GIMLE-860 | `gimle-mimir` | A store endpoint hostname that does not resolve at startup is retried rather than baked in unresolved | internal | UNIT | `AndvariMainResilienceTest`, `FafnirMainResilienceTest` and `MuninnMainResilienceTest` each assert an already-resolvable endpoint passes through untouched and that a malformed spec still fails fast rather than looping. |
| GIMLE-861 | `gimle-controlplane` | An artifact push or delete through the control plane names the artifact it affected in the audit trail | observable | FLEET | GOV-5, ART-3 |
| GIMLE-862 | `gimle-controlplane` | A shortfall count is never negative, and a DaemonSet's desired count covers what the same tick keeps placed | observable | FLEET | DEP-9, BATCH-3 |
| GIMLE-863 | `gimle-gateway` | A gateway follows its own tenant's Ingresses by default | observable | FLEET | NET-4, GOV-9 |
| GIMLE-864 | `gimle-module` | An instance's timeline opens with an INSTALLED event | observable | FLEET | OBS-9 |
| GIMLE-865 | `gimle-worker` | Promoting a surge instance records both indexes' timelines | observable | FLEET | SCHED-8, OBS-9 |
| GIMLE-866 | `gimle-module` | A lifecycle hook that fails with an `Error` is recorded rather than unwinding the worker | observable | FLEET | DEP-10 |
| GIMLE-867 | `gimle-agent` | A start this node refuses is recorded on the instance's own timeline, once per distinct cause | observable | FLEET | SCHED-2, OBS-9 |
| GIMLE-868 | `gimle-controlplane` | A placed instance that never started is reported as not running, in the API and in the CLI's health column | observable | FLEET | DEP-10, SCHED-2 |
| GIMLE-869 | `gimle-agent` | A node reports the binding memory and CPU budget as its capacity | observable | FLEET | SCHED-2, SCHED-10 |
| GIMLE-870 | `gimle-cli` | `gimle volume destroy` exits non-zero when it reclaimed nothing | observable | FLEET | BATCH-6 |
| GIMLE-871 | `gimle-mimir` | An Ingress route naming an unknown `paramType` is refused at submission | observable | FLEET | NET-4, CHAOS-1 |
| GIMLE-872 | `gimle-cli` | `gimle logs` prints the stack trace a log line carries | observable | FLEET | OBS-1 |
| GIMLE-873 | `gimle-cli` | `gimle get ingresses` lists the collection, and a leading `--tenant` filters rather than being read as a name | observable | FLEET | NET-4, CHAOS-3 |
| GIMLE-874 | `gimle-cli` | Every ingress apply is guarded by a compare-and-set version | observable | FLEET | NET-4, CHAOS-5 |
| GIMLE-875 | `gimle-controlplane` | An unknown Ingress route kind is rejected naming the kinds a manifest may declare | observable | FLEET | CHAOS-1 |
| GIMLE-876 | `gimle-console` | A throttled control-plane answer is retried instead of read as a signed-out session | observable | FLEET | SEC-9, OBS-7 |
| GIMLE-877 | `gimle-console` | An unread kind catalog is not reported as a cluster with no custom kinds | observable | FLEET | OBS-7, JRN-7 |
| GIMLE-878 | `gimle-console` | The console dev server proxies every API prefix its repositories use | out-of-scope | OUT OF SCOPE | build-time-only |
| GIMLE-879 | `gimle-controlplane` | An alert rule declaring no tenant is keyed under the default tenant, and a rule watching nothing says so once | observable | FLEET | OBS-9 |
| GIMLE-880 | `gimle-observability` | One source of truth for which process kinds ship metrics and which ship traces, served by the API | observable | FLEET | OBS-4, OBS-5 |
| GIMLE-881 | `gimle-controlplane` | The control plane starts a server span for every request it serves | observable | FLEET | OBS-5 |
| GIMLE-882 | `gimle-controlplane` | `-Dgimle.controlplane.muninnEndpoint` is honoured alongside the command-line flag | observable | FLEET | DEV-2 |
| GIMLE-883 | `gimle-hilmir` | `hilmir init` and `gimle:init` write generated manifests where the command was run | observable | FLEET | DEV-1 |
| GIMLE-884 | `gimle-maven-plugin` | `gimle:controlplane` and `gimle:agent` can name a Muninn to ship to | observable | FLEET | DEV-2 |
| GIMLE-885 | `gimle-module` | A rejected module name says which rule it breaks | observable | FLEET | DEV-1, CHAOS-1 |
| GIMLE-886 | `gimle-controlplane` | A deployment blocked by its artifact says whether it was rejected or unreadable, and why | observable | FLEET | DEP-10, ART-5 |
| GIMLE-887 | `gimle-hilmir` | `hilmir plan` rejects an unknown `--machine` the way `up` already does | observable | FLEET | OPS-4 |
| GIMLE-888 | `gimle-hilmir` | Every rejected topology document is reported as one coded finding | observable | FLEET | OPS-4 |
| GIMLE-889 | `gimle-console` | Config, ConfigMaps, Secrets and SecretMaps carry their tenant in the URL | observable | FLEET | GOV-9, OBS-7 |
| GIMLE-890 | `gimle-hilmir` | A topology may configure a store replica's health port | observable | FLEET | OPS-4, OPS-11 |
| GIMLE-891 | `gimle-hilmir` | `hilmir stop` stops one co-located role, or one process id, on a machine | observable | FLEET | OPS-4, OPS-9 |
| GIMLE-892 | `gimle-core` | A captured stdout record is read back as the record it was written as | observable | FLEET | OPS-15, OBS-1 |
| GIMLE-893 | `gimle-controlplane` | `GET /nodes/{nodeId}` serves a single node read | observable | FLEET | SCHED-3 |
| GIMLE-894 | `gimle-cli` | `gimle apply -f`'s help names every kind it accepts | observable | FLEET | GOV-12, CHAOS-4 |
| GIMLE-895 | `gimle-controlplane` | An instance event is filed only under a workload that exists | observable | FLEET | DEP-6, OBS-9 |
| GIMLE-896 | `gimle-controlplane` | A CronJob reports its last real firing separately from how far its schedule has been evaluated | observable | FLEET | BATCH-2 |
| GIMLE-897 | `gimle-agent` | A vessel's log directory resolves from its own name, so a bare-name log read reaches it | observable | FLEET | BATCH-8, OBS-1 |
| GIMLE-898 | `gimle-maven-plugin` | `gimle:saga-import` derives its run id from the reports, so re-importing folds into the same run | observable | FLEET | DEV-4 |
| GIMLE-899 | `gimle-observability` | A root span reports no parent rather than the all-zero span id | observable | FLEET | OBS-5 |
| GIMLE-900 | `gimle-muninn` | Every span of one trace is found across processes in a single read | observable | FLEET | OBS-5 |
| GIMLE-901 | `gimle-controlplane` | `GET /trace/{traceId}` and `gimle trace <traceId>` follow a whole trace, wherever it ran | observable | FLEET | OBS-5 |
| GIMLE-902 | `gimle-controlplane` | A history read forwards the backward-paging parameters instead of dropping them at the proxy hop | observable | FLEET | OBS-4 |
| GIMLE-903 | `gimle-agent` | A node bootstraps its own identity into its own writable data root, with a DNS-named leaf | observable | FLEET | OPS-5, SEC-7 |
| GIMLE-904 | `gimle-agent` | A worker's handshake is applied to every instance packed onto that worker | observable | FLEET | SCHED-10, OBS-10 |
| GIMLE-905 | `gimle-pki` | A store replica presents its own leaf certificate rather than the control plane's | observable | FLEET | OPS-5, SEC-7 |
| GIMLE-906 | `gimle-ivaldi` | Blueprint document storage API | observable | FLEET | DEV-7 |
| GIMLE-907 | `gimle-ivaldi` | Blueprint tier-2 validation against the real platform parsers | observable | FLEET | DEV-7 |
| GIMLE-908 | `gimle-maven-plugin` | Ivaldi server lifecycle Maven goals (gimle:ivaldi / gimle:ivaldi-stop) | observable | FLEET | DEV-7 |
| GIMLE-909 | `gimle-dist` | Ivaldi ships as a distribution archive (standalone and platform-bundled) | observable | FLEET | OPS-1 |
| GIMLE-910 | `gimle-ivaldi-console` | Ivaldi web console: blueprint designer canvas | observable | FLEET | DEV-7 |
| GIMLE-911 | `gimle-ivaldi` | Ivaldi run engine: cluster connections and running a Blueprint in-process | observable | FLEET | DEV-7 |
| GIMLE-912 | `gimle-ivaldi` | Ivaldi tracks every run it started, and stops them all on shutdown | observable | FLEET | DEV-7 |
| GIMLE-913 | `gimle-ivaldi-console` | Rendered workloads resolve through the artifact registry, not a local path | observable | FLEET | DEV-7 |
| GIMLE-914 | `gimle-hilmir` | Release upgrade prunes config and secret keys the new bundle drops | observable | FLEET | OPS-12 |
| GIMLE-915 | `gimle-controlplane` | Service endpoint resolution reports why a backing instance was excluded | observable | FLEET | NET-1 |
| GIMLE-916 | `gimle-hilmir` | Topology faults name the section they were read from | observable | FLEET | OPS-4 |
| GIMLE-917 | `gimle-ivaldi-console` | Ivaldi console shows which blueprints and clusters are actually running | observable | FLEET | DEV-7 |
| GIMLE-918 | `gimle-ivaldi` | A run's applied NetworkPolicy keeps a present-but-empty allow list | observable | FLEET | DEV-7 |
| GIMLE-919 | `gimle-ivaldi` | Deleting a cluster connection with a run still tracked is refused | observable | FLEET | DEV-7 |
| GIMLE-920 | `gimle-ivaldi` | A different blueprint cannot silently claim a cluster another blueprint still owns | observable | FLEET | DEV-7 |
| GIMLE-921 | `gimle-ivaldi` | Creating a blueprint at an id already taken is refused, not silently re-minted | observable | FLEET | DEV-7 |
| GIMLE-922 | `gimle-ivaldi` | A redeploy onto an already-running cluster still reports its live processes | observable | FLEET | NET-1 |
| GIMLE-923 | `gimle-ivaldi-console` | A LimitRange bound with only one of memory/cpu filled in is flagged before export | observable | FLEET | DEV-7 |
| GIMLE-924 | `gimle-ivaldi-console` | A Tenant node's Inspector panel shows its own Links section | observable | FLEET | DEV-7 |
| GIMLE-925 | `gimle-ivaldi-console` | Deleting a node with connected edges undoes as one step, not two | observable | FLEET | DEV-7 |
| GIMLE-926 | `gimle-ivaldi-console` | Dragging a node checkpoints one undo step regardless of intermediate positions | observable | FLEET | DEV-7 |
| GIMLE-927 | `gimle-ivaldi-console` | Duplicating or importing a blueprint mints a fresh id rather than reusing the source's own | observable | FLEET | DEV-7 |
| GIMLE-928 | `gimle-ivaldi` | A cluster's infra can host more than one blueprint's own deployment | observable | FLEET | DEV-7 |
| GIMLE-929 | `gimle-ivaldi` | A topology change is refused while another deployment still shares the cluster | observable | FLEET | DEV-7 |
| GIMLE-930 | `gimle-ivaldi` | Deleting a cluster connection is refused while any of its deployments is still live | observable | FLEET | DEV-7 |
| GIMLE-931 | `gimle-ivaldi` | Stopping a deployment on a shared cluster undeploys only its own release | observable | FLEET | DEV-7 |
| GIMLE-932 | `gimle-ivaldi-console` | The console tracks and stops each deployment on a shared cluster independently | observable | FLEET | DEV-7 |
| GIMLE-933 | `gimle-ivaldi` | Tier-2 validation catches a jar-sourced workload's real resources violating its tenant's LimitRange | observable | FLEET | DEV-7 |
| GIMLE-934 | `gimle-ivaldi-console` | A placedOn/belongsTo edge routed under a Machine's frame is genuinely clickable | observable | FLEET | DEV-7 |
| GIMLE-935 | `gimle-ivaldi-console` | Dragging a palette item onto a genuinely empty canvas actually adds a node | observable | FLEET | DEV-7 |
| GIMLE-936 | `gimle-ivaldi-console` | Click-to-add palette nodes no longer stack invisibly on top of each other | observable | FLEET | DEV-7 |
| GIMLE-937 | `gimle-ivaldi-console` | Cluster action failures show a toast title that matches which action actually failed | observable | FLEET | DEV-7 |
| GIMLE-938 | `gimle-ivaldi-console` | A blank LimitRange bound field no longer shows a spurious "not a valid value" error | observable | FLEET | DEV-7 |
| GIMLE-939 | `gimle-ivaldi` | Deleting a blueprint refuses while a run is still tracked against it | observable | FLEET | DEV-7 |
| GIMLE-940 | `gimle-ivaldi` | A Service-overlap advisory from the control plane now reaches the run log instead of being silently dropped | observable | FLEET | DEV-7 |
| GIMLE-941 | `gimle-ivaldi-console` | A Service's Target Port can be left blank, defaulting to Port, instead of coercing to an invalid 0 | observable | FLEET | DEV-7 |
| GIMLE-942 | `gimle-ivaldi-console` | Two Services in the same tenant fronting the same deployment now warn at design time (SERVICE_OVERLAP) | observable | FLEET | DEV-7 |
| GIMLE-943 | `gimle-ivaldi-console` | Negative autoscale minReplicas and negative disruption maxUnavailable/maxSurge are now rejected at design time | observable | FLEET | DEV-7 |
| GIMLE-944 | `gimle-ivaldi-console` | NetworkPolicy's Tenant id field states plainly that dragging to a Tenant adds an allowed caller, not the policy's own scope, and its Deployment names field shows the real POLICY_TENANT_WIDE code | observable | FLEET | DEV-7 |
| GIMLE-945 | `gimle-ivaldi-console` | DaemonSet's tolerateAllTaints field is now exposed in the Inspector and exported | observable | FLEET | DEV-7 |
| GIMLE-946 | `gimle-ivaldi-console` | Removing a placedOn/belongsTo link clears the surviving node's own copied machine/tenantId field instead of leaving it stale | observable | FLEET | DEV-7 |
| GIMLE-947 | `gimle-ivaldi-console` | A keyboard-focused-but-unselected canvas node now shows a real, visible focus indicator | observable | FLEET | DEV-7 |
| GIMLE-948 | `gimle-ivaldi-console` | Keyboard/screen-reader focus moves to an announced landmark on every client-side route change | observable | FLEET | DEV-7 |
| GIMLE-949 | `gimle-ivaldi-console` | Every screen's header row scrolls in place instead of forcing the whole page wider than the viewport | observable | FLEET | DEV-7 |
| GIMLE-950 | `gimle-ivaldi-console` | The Blueprint list table scrolls horizontally at phone width instead of clipping columns | observable | FLEET | DEV-7 |
| GIMLE-951 | `gimle-ivaldi-console` | Escape closes the Problems/Files/Run drawer, matching every other dismissible surface in the app | observable | FLEET | DEV-7 |
| GIMLE-952 | `gimle-ivaldi-console` | Every Inspector and Blueprint Settings form field now has a real accessible name | observable | FLEET | DEV-7 |
| GIMLE-953 | `gimle-ivaldi` | The mTLS IP-literal refusal names a cluster connection by its own display name, not its internal id | observable | FLEET | DEV-7 |
| GIMLE-954 | `gimle-ivaldi` | A cluster connection addressed at a port the topology's own control plane never listens on is refused before anything boots | observable | FLEET | DEV-7 |
| GIMLE-955 | `gimle-ivaldi-console` | The Clusters page's Control-plane URL field now carries an mTLS-aware hint and placeholder | observable | FLEET | DEV-7 |
| GIMLE-956 | `gimle-ivaldi-console` | Palette items carry a stable automation id distinct from their visible label | observable | FLEET | DEV-7 |
| GIMLE-957 | `gimle-ivaldi-console` | The DAEMONSET_MAX_SURGE validation finding is now reachable from the Inspector | observable | FLEET | DEV-7 |
| GIMLE-958 | `gimle-mimir` | StatefulSet workloads can carry an AutoscalePolicy, identically to Deployment | observable | UNIT | gimle-mimir and gimle-controlplane full module suites re-verified after the change (0 failures). Frontend: tsc/eslint/vitest/vite build all clean. |
| GIMLE-959 | `gimle-mimir` | StatefulSet workloads can carry a DisruptionBudget, and OrderedReady rolling updates now honor a configurable maxUnavailable | observable | UNIT | `StatefulSetReconcilerTest` (21 tests, including the pre-existing GIMLE-682 flap-immunity pair, updated for the new same-tick budget-refill behavior) plus gimle-mimir's own StateStore/RaftCodec/DomainCodec round-trip tests -- full gimle-mimir and gimle-controlplane module suites re-verified (0 failures). Frontend: tsc/eslint/vitest/vite build all clean. |
| GIMLE-960 | `gimle-ivaldi-console` | A blueprint edit lost to a tab or process kill inside the debounced-save window is recoverable on reload | observable | FLEET | DEV-7 |
| GIMLE-961 | `gimle-ivaldi` | A Blueprint whose topology declares more than one machine can actually be Run, booted concurrently across all of them | observable | FLEET | DEV-7 |
<!-- /forseti:generated -->
