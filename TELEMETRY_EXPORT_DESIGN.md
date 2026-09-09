# `gimle-gjallarhorn` — exporting Gimlé telemetry to Grafana (Loki/Tempo/Mimir), design proposal

## What this is, in one paragraph

An **optional extension of `gimle-muninn`**, off by default, that forwards the logs/metrics/traces
Muninn already stores to a self-hosted Grafana observability stack — **Loki** (logs), **Tempo**
(traces), **Mimir** (metrics), plus a Prometheus-compatible scrape endpoint as a zero-infrastructure
fallback for metrics. Nothing about Gimlé's default behavior changes when it is disabled. This
supersedes the earlier draft of this document, which scoped a generic multi-vendor OTLP exporter;
that scope is deliberately narrowed here to one target, chosen and justified in §2.

## 0. This is an extension, not a default — and what that means concretely

This is the load-bearing decision of the whole design, stated first because everything else follows
from it.

**Precedent already in the codebase**: `gimle-bifrost` is a per-node service proxy embedded in
`gimle-agent`, package `com.gimle.agent.bifrost`, "off by default
(`-Dgimle.agent.bifrostEnabled=true`)". Gjallarhorn is the same shape: `com.gimle.muninn.gjallarhorn`,
embedded in `gimle-muninn`, off by default behind `-Dgimle.muninn.gjallarhorn.enabled=true`.

Concretely, "extension, not default" means every one of these is true and testable:

- **A Muninn process with the flag unset starts, ingests, and serves reads exactly as it does
  today.** No new thread, no new outbound socket, no new meter registry, no new config parsing
  beyond reading one absent system property. Verified by a test that boots `MuninnServer` with no
  Gjallarhorn properties set and asserts no outbound connection is ever attempted.
- **No existing wire format changes because Gjallarhorn exists.** `MuninnShipper`'s ingest payloads,
  `MuninnServer`'s ingest/read routes, and `MuninnDayFileStore`'s on-disk layout are untouched by
  this design. The two codec revisions this design does depend on (§4) are corrections that stand on
  their own merits — a latent bug and a missing field — not something Gjallarhorn's existence
  requires of a disabled deployment.
- **`gimle-console` and `gimle-cli` are completely unaware this exists.** They keep reading Muninn
  through `/logs/*`, `/metrics-history/*`, `/traces-history/*` exactly as today. Gjallarhorn adds no
  route those surfaces call.
- **Turning it on is additive, not a migration.** An operator who enables it gets a second place
  their telemetry now also lands; nothing that already worked stops working, and nothing needs
  reconfiguring on the Gimlé side to turn it back off.
- **The moment it's disabled everywhere, deleting the package deletes the feature and nothing else.**
  No default-path code should ever call into `com.gimle.muninn.gjallarhorn` — the dependency points
  one way, from the extension into Muninn's existing internals, never back.

## 1. The gap this closes

Gimlé's telemetry is a closed loop today: logs, metrics and traces are produced by every process
kind, shipped as first-party NDJSON to `gimle-muninn`, stored in day files, and read back through
exactly three surfaces — the web console, `gimle logs`/`gimle metrics-history`/`gimle traces-history`,
and `gimle-hugin`. Nothing leaves the cluster. An operator who already runs Grafana cannot see a
Gimlé module's error rate next to the rest of their stack, and `AlertRuleSpec`'s single-signal
webhook is not a substitute for real alerting on that stack.

## 2. Why Grafana, and why this doesn't reopen the OTLP-vendor question

The earlier draft aimed at "whatever OTLP tool" and carried an explicit open question: does
OTLP/JSON actually work against every candidate backend, verified per-vendor at implementation time.
Picking one target closes that question rather than deferring it, and Grafana is the specific right
choice for this codebase's stated goal (open-source, self-hostable):

- **Loki, Tempo, and Mimir each accept OTLP natively today** — no OpenTelemetry Collector or Alloy
  in between is required. This removes a whole piece of assumed infrastructure from the original
  design: an operator with a bare Loki+Tempo+Mimir install gets full coverage with nothing else to
  run. A collector remains a supported *option* (useful for multi-cluster fan-in or added routing),
  never a requirement.
- **One target means one contract to verify, not a family of them.** The integration tests in §12
  run against real Loki/Tempo/Mimir containers, not a generic stub standing in for "some OTLP
  receiver" — closing the uncertainty the earlier draft left open.
- **The encoding decision (OTLP/HTTP, JSON body) is unchanged and still the right one for the reason
  already established**: `gimle-muninn` depends on `gimle-core`/`gimle-mimir`/`gimle-pki`/SLF4J only
  — no Micrometer, no OpenTelemetry SDK, no gRPC (which would mean Netty, an explicit project
  non-goal). It never holds a `SpanData` or a `Meter`, only `Map<String, Object>` parsed from
  NDJSON. OTLP/JSON is a spec-defined encoding of the same schema, so producing it is a
  `Map`-to-`Map` transformation ending in the `Json.write` this codebase already owns — zero new
  dependencies. Loki/Tempo/Mimir's OTLP receivers accept JSON, so nothing is given up by staying
  with it.
- **Prometheus's own text exposition format stays in scope too**, not as a second standard competing
  with OTLP but because it needs no push logic at all: Mimir, Prometheus itself, and VictoriaMetrics
  all scrape it identically, and it is the only path here needing zero queue/retry/backoff code on
  Gjallarhorn's side (§8). Promoted to the same milestone as metrics OTLP export (§13, M2) rather
  than treated as a lesser fallback.

## 3. Placement, restated for this narrower scope

`com.gimle.muninn.gjallarhorn`, inside `gimle-muninn`. All three signals already converge on Muninn
from every process kind, including worker JVMs — which have no outbound network identity of their
own and reach Muninn only by relaying through their agent (`WorkerMain`'s only arguments are
`nodeId`/`tenantId`/a control-socket path). Any placement outside Muninn would need to either
re-solve that relay for a second consumer, or leave workers uncovered; both were evaluated in the
earlier draft and rejected. Nothing here changes that conclusion.

## 4. Internal shape

```
MuninnServer.ingest(...)                    (unchanged — durability first, always)
        │  after dayFileStore.appendLines(...) returns
        ▼  (only when gjallarhorn.enabled)
  StreamIdentity            ← parsed from the same subtree path Muninn already computes
        │
        ▼
  Normalizer ──► NormalizedLog | NormalizedMetric | NormalizedSpan   (records, immutable)
        │
        ├──► OtlpJsonEncoder ──► BatchHttpSink ──► Loki /otlp   (logs)
        ├──► OtlpJsonEncoder ──► BatchHttpSink ──► Tempo /v1/traces  (traces)
        ├──► OtlpJsonEncoder ──► BatchHttpSink ──► Mimir /otlp/v1/metrics  (metrics)
        └──► PrometheusTextEncoder ──► GET /prometheus   (Mimir/Prometheus/VictoriaMetrics scrape)
```

`StreamIdentity` is derived from the subtree path Muninn already computes
(`logs/instances/{deployment}#{index}/{category}`, `metrics/{processKind}/{processId}`, …) — no new
identity plumbing. The `Normalized*` types are immutable records per this repo's conventions.

`BatchHttpSink` is extracted from `MuninnShipper`, not copied: that class already is "POST a batch
to N endpoints, best-effort, own virtual thread, never throw," which is exactly what shipping to
Loki/Tempo/Mimir independently needs, modulo content type and headers. **Recommendation: land it in
`gimle-core`** next to `Json`/`SizeLimitedInputStream` — dependency-free HTTP batching with no
observability semantics, keeping `gimle-muninn`'s current dependency set (`gimle-core`, `gimle-mimir`,
`gimle-pki`, SLF4J) untouched, and letting `MuninnShipper` itself reuse it later without `gimle-muninn`
gaining a new module dependency.

## 5. Signal mapping and the codec fixes it depends on

Unchanged in substance from the earlier draft — narrowing the target doesn't change what OTLP
requires — restated here with the milestone each fix now belongs to (see §13).

### 5a. Logs → Loki (via OTLP)

`JsonLogEncoder`'s existing fields map directly: `timestamp`→`timeUnixNano`, `level`→`severityText`/
`severityNumber` (`TRACE`=1, `DEBUG`=5, `INFO`=9, `WARN`=13, `ERROR`=17), `message`→`body`,
`stackTrace`→attribute `exception.stacktrace`, `deploymentName`/`processRole`→resource
`service.name`, `tenantId`→resource `service.namespace`, `nodeId`/`instanceIndex`→resource
`service.instance.id`/`host.name`, `moduleId`/`moduleVersion`/`category`→`gimle.*` attributes.

**Fix required, M1**: no `traceId`/`spanId` on a log line today, so an exported log can never link
to its trace in Tempo/Loki's own correlation view — the single feature a Grafana user expects most.
Add `traceId`/`spanId` MDC keys to `InstanceMdcKeys`, set wherever the OTel context is already
established (`FabricServer`'s inbound dispatch, `FabricServiceRegistry`'s outbound proxy), read by
`JsonLogEncoder` exactly as it already reads `deploymentName`. No new dependency — Logback MDC is
the standard mechanism, and `gimle-core` gains no OpenTelemetry dependency by doing this.

### 5b. Metrics → Mimir (via OTLP) and Prometheus scrape

`MeterSnapshotCodec`'s `COUNTER`→`Sum`, `GAUGE`→`Gauge`, `TIMER`→`Sum`(count)+`Sum`(total time,
seconds)+`Gauge`(max) mapping is direct. Two fixes, both M2:

- **No unit/description shipped.** `Meter.Id` carries both; OTLP's `Metric.unit`/`.description` are
  what make a Grafana panel render `2.4 s` instead of `2.4`. Add both to the shipped line.
- **No histogram buckets — percentiles are pre-computed per-instance and cannot be aggregated**
  across replicas. Build the timers with `publishPercentileHistogram()`, ship the buckets, map to
  OTLP `Histogram`. Keep shipping the existing percentiles too (the console reads them today) as
  clearly-suffixed gauges, not conflated with the aggregatable histogram.
- **No `processStartTime`**, needed for OTLP cumulative sums' `startTimeUnixNano` (Mimir needs this
  to detect a restart and not read it as a negative rate). Add the process's own fixed start instant
  to each shipped metrics snapshot line.

### 5c. Traces → Tempo (via OTLP)

**`SpanLineCodec`'s current format cannot produce a usable span, independent of any export** — this
is a correctness finding, not an export-specific gap, which is why it moves to M1 (§13) rather than
waiting for M3:

- No start time is shipped (only the end-time `timestamp`) — every span would show zero/unknown
  duration, the one thing a trace view exists to show.
- Attributes are flattened directly onto the line, so an attribute literally named `name`, `kind`,
  `status`, or `timestamp` silently overwrites that span field — a latent bug today, with or without
  export.
- Span events, links, instrumentation scope, and status description are dropped entirely.

Fix: nest attributes under their own key, add `startTime` alongside the existing end `timestamp`,
carry `events`/`links`/`scope`/`status.description`. `RelayingSpanExporter` in `gimle-worker` shares
this codec, so the direct and worker-relayed paths pick up the fix together. **Trace export to
Tempo is gated on this landing** — exporting today's format would produce spans that look present
and are wrong.

## 6. Endpoints and configuration

```
-Dgimle.muninn.gjallarhorn.enabled=true
-Dgimle.muninn.gjallarhorn.loki.endpoint=http://loki:3100          (logs, OTLP path: /otlp/v1/logs)
-Dgimle.muninn.gjallarhorn.tempo.endpoint=http://tempo:4318        (traces, OTLP path: /v1/traces)
-Dgimle.muninn.gjallarhorn.mimir.endpoint=http://mimir:9009        (metrics, OTLP path: /otlp/v1/metrics)
-Dgimle.muninn.gjallarhorn.prometheus.enabled=false                (GET /prometheus scrape surface)
-Dgimle.muninn.gjallarhorn.headersFile=/etc/gimle/grafana-headers  (optional — see below)
-Dgimle.muninn.gjallarhorn.batchSize=512
-Dgimle.muninn.gjallarhorn.flushInterval=5s
-Dgimle.muninn.gjallarhorn.queueCapacity=10000
-Dgimle.muninn.gjallarhorn.resourceAttributes=deployment.environment=prod,cluster=eu-west-1
```

Each of `loki.endpoint`/`tempo.endpoint`/`mimir.endpoint` is independently optional — a deployment
running only Loki (say) sets that one property and gets logs export with metrics/traces untouched.
`gimle-hilmir`'s `LaunchPlanner` threads these the same way it already threads five process kinds'
`-Dgimle.*.muninnEndpoint`, and `gimle-holmgang`'s topology YAML gains a `gjallarhorn:` block
alongside its existing `muninn:` one.

**Credentials are optional, and normally absent.** A self-hosted Loki/Tempo/Mimir on a trusted
network needs no auth header. When one is configured (Grafana Cloud's hosted LGTM, or a
authenticated self-hosted install), it is read from a `Header: value` file at `0600` permissions —
the same posture `KeyFileManager` already takes for Fafnir's master key — never a system property
(which would land in `ps` output and in `LaunchPlanner`'s own logged command line).

**TLS trust is separate from the cluster's own.** These endpoints are outside the mTLS cluster
boundary; the sink uses the JDK default truststore, explicitly not
`SslContexts.forMutualTls(TlsSettings.fromConfig())` — the single easiest mistake to make in a
codebase where every other outbound call is mTLS.

## 7. Failure modes

| Condition | Behaviour |
|---|---|
| Target unreachable | Queue fills, drop-oldest, counter + rate-limited `WARN`. Ingest and day-file durability unaffected. |
| `429`/`503` | Exponential backoff with jitter, honouring `Retry-After`, per target. |
| `400` (malformed payload) | Never retried — logged once with the offending `StreamIdentity`, counted. |
| OTLP partial success | Logged and counted, not retried; accepted remainder stands. |
| Muninn restart | In-memory queue lost — same tradeoff `MuninnShipper`'s own in-memory cursor already accepts. Recoverable via replay (§10). |
| One of Loki/Tempo/Mimir down, others up | Isolated per target — a Mimir outage never blocks logs reaching Loki. |

Gjallarhorn's own health rides the same path it exports through: queue depth, dropped batches,
per-target success/failure counts, export latency — ordinary meters. This gives Muninn its first
meter registry (today it is "the one process with no meter registry of its own"), and that
should be called out in `observability.md` (§14) as a real, if small, change to Muninn's own shape,
happening only when the extension is enabled.

## 8. Multi-tenancy — the honest limitation, unchanged by the narrower scope

Muninn's read API is RBAC-gated per tenant; a single cluster-wide Loki/Tempo/Mimir target flattens
that — every tenant's telemetry lands in one Grafana org. v1's position: this is an explicit
operator decision (enabling export declares the target inside the cluster's trust boundary), and
`service.namespace = tenantId` is stamped on every exported record so a future per-tenant target can
re-partition on it. `StreamIdentity` carries the tenant through the whole pipeline from M1, even
while target selection is a constant function of it — the seam costs nothing to keep and is
expensive to retrofit later. An `ExportTargetSpec` manifest kind (alongside `ServiceSpec`/
`NetworkPolicySpec`/`AlertRuleSpec`) is the v2 answer and is not designed here; it has no present
user, and CLAUDE.md's "no backward-compatibility concern by default" cuts the same way against
building unused generality now.

## 9. Multiple Muninn replicas: single-instance export in v1, stated plainly

Muninn can run multiple replicas; each ingests independently. If every replica ran Gjallarhorn, each
would forward the same batch, so Loki/Tempo/Mimir would see the fleet's telemetry duplicated once
per replica. v1's answer: **document that Gjallarhorn is enabled on exactly one designated Muninn
replica**, operationally, the same way a single-writer role is designated elsewhere in ops practice.
No leader election is built for this — the reconcilers and relays in this codebase (`NetworkPolicyRelay`,
`ServiceReconciler`) avoid needing one because they are idempotent by construction; a push to an
external, non-idempotent sink is a different shape of problem, and building coordination for it
without a real multi-replica-export deployment to validate against would be exactly the speculative
work CLAUDE.md warns against. Revisit only if a deployment actually needs HA export, at which point
a `gimle-mimir`-backed lease is the natural mechanism (it would require Muninn to hold a writing
`StoreClient`, which it doesn't today — a real, scoped follow-on, not a v1 concern).

## 10. Replay

`gimle gjallarhorn replay --since <ts> [--until <ts>] [--signals logs,metrics,traces]` re-reads day
files and re-exports a window — the reason store-then-forward's extra latency (versus a direct
process-to-Grafana exporter) is worth it: an outage becomes recoverable instead of a permanent gap.
Needs `MuninnDayFileStore` to gain subtree enumeration (`listSubtrees()`, per-subtree day-file
listing) — nothing today can ask "what streams exist"; `RetentionSweeper` already walks the data
root with `Files.walk`, so the traversal shape is established, just not exposed as a first-class
read. Package-private, matching `appendLines`/`readAfter`/`readOlder`'s own visibility, until a
second caller needs it public. Replay is inherently at-least-once; the CLI's help text says so
rather than implying otherwise.

## 11. Validating this against a real stack: the Midgard Grafana profile

Because the target is now one specific, self-hostable stack rather than an open-ended vendor list,
verifying this design against the real thing is cheap and should be built, not left as a someday
runbook. `gimle-dist`'s Midgard dev cluster is already a `docker-compose.yaml`; add an **opt-in
compose profile** (`docker compose --profile grafana up`) bringing up Loki + Tempo + Mimir + Grafana
(all Apache-2.0/AGPLv3, self-hosted, nothing to sign up for) with Gjallarhorn pre-pointed at them,
plus a couple of reference Grafana dashboards for Gimlé's own meters checked in alongside it. This
turns "does a `greeter-provider` log line actually land in Loki with the right labels" into one
command. Opt-in only — default Midgard bring-up gains nothing extra. Kept out of `mvn verify`,
`-Psmoke`, and `-Pvalidation`; it's for a human to look at, not for CI.

## 12. Rejected alternatives (updated for the narrower scope)

**Staying generic across many vendors** (the earlier draft's own scope). Rejected now because the
open question it left permanently open — "verify OTLP/JSON per target at implementation time" — is
exactly what committing to one target closes, and every stated goal (open-source, self-hostable,
simplest correct thing) is better served by one well-verified integration than several
half-verified ones. The `OtlpJsonEncoder`/`BatchHttpSink` seam doesn't foreclose adding a second
target later; nothing here is Grafana-specific at the code level except the three configured
endpoints.

**Requiring an OpenTelemetry Collector or Alloy in front of Loki/Tempo/Mimir.** True in the earlier
draft, no longer true: all three accept OTLP directly, so requiring a collector would add
infrastructure the target doesn't need. Still a supported *option* for an operator who wants
fan-in from multiple clusters or extra routing — not built or assumed by Gjallarhorn itself.

**Loki's native (non-OTLP) push API.** Genuinely tempting once the target is fixed to Loki
specifically, and rejected for the same reason as before: it would bind this code to Loki's own
schema and version cadence for one signal, when the OTLP path already reaches Loki directly with no
translation layer of its own to maintain.

**Prometheus `remote_write` for metrics, instead of Mimir's own OTLP endpoint.** Real and open, but
protobuf + snappy framing — a new dependency and a hand-rolled encoder — for coverage the OTLP path
and the scrape endpoint already give against this exact target. Reconsider only if pull-based
scraping proves unworkable at real scale.

**Shipping any of Loki/Tempo/Mimir/Grafana as a Gimlé-supervised process.** They're Go binaries;
"no non-Java runtime dependencies" is a first-order project constraint this would break directly.
The operator runs them (or the opt-in Midgard profile does, for evaluation); Gjallarhorn only talks
to them.

## 13. Milestones

| # | Scope | Depends on |
|---|---|---|
| M1 | `StreamIdentity`, normalized model, `OtlpJsonEncoder`, `BatchHttpSink` (extracted into `gimle-core`), streaming hook, **logs → Loki**, `traceId`/`spanId` MDC keys, `SpanLineCodec` revision (landed here even though traces ship later — it's a standalone correctness fix), Midgard Grafana compose profile | — |
| M2 | **Metrics → Mimir** + Prometheus scrape surface, unit/description/`processStartTime`/histogram-bucket fixes | M1's normalized model |
| M3 | **Traces → Tempo**, log↔trace correlation exercised end to end | M1's `SpanLineCodec` revision and MDC keys |
| M4 | `gimle gjallarhorn replay` | `MuninnDayFileStore` enumeration API |
| M5 | Per-tenant `ExportTargetSpec` (design only until a real user needs it) | Muninn gaining a `FafnirClient` |

M1 is logs-first because that signal's wire format needs no revision at all — it validates the whole
extension end to end, including the Midgard profile, before any codec change is made elsewhere.

## 14. Testing

- **Unit**: OTLP/JSON golden fixtures per signal, severity mapping (including unknown levels),
  Prometheus text format, `StreamIdentity` parsing including malformed subtree paths.
- **Failure paths**: queue overflow drops oldest and counts; `400` never retried; `429` backs off
  honouring `Retry-After`; one target's failure never affects another; an unreachable target never
  delays an ingest response (assert on ingest latency with a deliberately-blocking sink).
- **Integration**: real Loki, Tempo, and Mimir containers (this is the payoff of narrowing to one
  target — a genuine contract test, not a stand-in stub), asserting a known batch round-trips and is
  queryable back out via each product's own query API (LogQL, TraceQL, PromQL).
- **Smoke** (`gimle-smoke-tests`, `-Psmoke`): extend `ObservabilityIT` — the real cluster ships to a
  real Muninn with Gjallarhorn enabled against the same containers; assert a `greeter-provider`
  APPLICATION log line arrives in Loki with `service.name`/`service.namespace` set from its real
  deployment/tenant, and (once M3 lands) the consumer's real cross-worker fabric span arrives in
  Tempo with a non-zero duration and a `traceId` matching the correlated Loki line.
- **Holmgang** (`-Pvalidation`): a `telemetry-export.feature` scenario booting a topology with
  `gjallarhorn:` enabled against the profile from §11, covering the healthy path and a
  deliberately-unreachable target (asserting ingest and Muninn reads stay entirely unaffected — the
  property the whole "extension, not default" framing in §0 exists to guarantee).
- **Extension-boundary test, specifically for §0's claims**: `MuninnServer` started with no
  Gjallarhorn properties set, asserting zero outbound connections are attempted and every existing
  ingest/read behavior is byte-for-byte unchanged from today's test suite.

## 15. Repository bookkeeping this change carries

Per `CLAUDE.md`, not deferred:

- **`requirements-matrix.json`**: sequential IDs from `GIMLE-844` (current max `GIMLE-843`) for each
  shipped capability (Loki/Tempo/Mimir export, Prometheus scrape, replay), real source/tests, never
  aspirational.
- **`rtm.json`**: matching entries, `"status": "New"`, `"coverage": "Covered"` only once a genuine
  Holmgang scenario exercises it end to end — unit/smoke tests don't count toward that field.
- **`forseti.json`**: each new ID placed in a scenario's `requirements` or an `internal` group with a
  reason; the generator fails loudly on an unplaced ID.
- Then `python3 scripts/generate_requirements_docs.py`.
- **`gimle-docs`**: a new "Exporting to Grafana" section in `docs/architecture/observability.md`
  (explicitly framed as an optional extension, per §0); a note in `docs/architecture/node-topology.md`'s
  Muninn section that it optionally makes outbound calls and gains a meter registry only when
  enabled; `docs/reference/cli-reference.md` for `gimle gjallarhorn replay`; the Midgard profile's
  own README section.
