# `gimle-gjallarhorn` — exporting Gimlé telemetry to third-party observability backends, design proposal

## The gap this closes

Gimlé's telemetry is a closed loop. Logs, metrics and traces are produced by every process kind,
shipped as first-party NDJSON to `gimle-muninn`, stored in day files, and read back through exactly
three surfaces — the web console, `gimle logs`/`gimle metrics-history`/`gimle traces-history`, and
`gimle-hugin`. Nothing leaves the cluster.

That is fine for a cluster operated entirely through Gimlé's own tooling and untenable for anything
else. An organization running Gimlé alongside other systems already has Grafana, Datadog, Splunk or
an ELK stack, already has on-call routing wired to that stack's alerting, and already has retention
and compliance policy expressed there. Today Gimlé cannot participate: correlating a Gimlé module's
error rate against the database it calls means reading two unrelated UIs, and `AlertRuleSpec`'s
single-signal/single-comparator webhook (see `gimle-docs/docs/architecture/observability.md`
§Alerting) is deliberately not a replacement for a real alerting engine.

The absence is total, not partial. There is no scrape endpoint, no OTLP exporter, no vendor
adapter, no documented egress path of any kind. `MuninnDayFileStore` doesn't even expose a
subtree-enumeration method — nothing can currently ask Muninn "what streams do you hold".

This is distinct from the platform's documented non-goals. "No non-Java runtime dependencies" and
"not built on an existing service mesh" are about what *Gimlé itself* is made of; they say nothing
about whether Gimlé can talk to something an operator already runs. Nor does this reverse the
decision recorded in `CLAUDE.md` — see the next section, which exists specifically to address it.

## This does not reverse the "Prometheus/OTLP rejected" decision

`CLAUDE.md` records that `gimle-muninn` replaced "the originally-considered (and explicitly
rejected) Prometheus/OTLP approach" with a first-party `MuninnShipper` mechanism. That rejection
was about the **internal** transport: how a Gimlé process hands telemetry to Gimlé's own sink. It
stands, and this design changes nothing about it. `MuninnShipper` keeps POSTing NDJSON to
`/ingest/{logs,metrics,traces}/*`; no process gains an OTLP client; Muninn gains no OTLP ingest
route; no OpenTelemetry Collector runs anywhere in a Gimlé deployment.

What this design adds is **egress at the cluster boundary**, which is the one place a standard
genuinely earns its cost: the party on the other side is not ours, and hand-rolling a wire format
for it means hand-rolling one per vendor, forever. The internal path stays first-party precisely
because both ends are ours; the external path becomes standard precisely because one end is not.

## Goals

- **Free and open-source backends are the target.** Grafana's stack (Loki/Mimir/Tempo, reached via
  Alloy or the OpenTelemetry Collector), Prometheus, VictoriaMetrics, Jaeger, OpenSearch, SigNoz,
  OpenObserve, Uptrace — self-hostable, no licence, no account. Proprietary SaaS (Datadog, New
  Relic, Splunk, Dynatrace) is *reachable* as a consequence of choosing an open standard, and is
  explicitly not what any decision here optimizes for. Where the two pull in different directions,
  the open-source target wins; §2's encoding decision is exactly such a case.
- Nothing added to Gimlé is itself non-free: OTLP is an Apache-2.0 CNCF specification, the
  Prometheus exposition format is open, and v1 implements both directly — no proprietary SDK, no
  vendor client library, no dependency with a licence more restrictive than the reactor already
  carries.
- One configured egress point for the whole cluster — not one exporter configuration per process
  kind per node.
- One protocol reaching the majority of tools, rather than one adapter per vendor.
- Zero new third-party dependencies in v1, consistent with the posture that produced
  `JsonLogEncoder`, `Json` and `ControlMessageCodec`.
- Export is strictly best-effort and strictly downstream of durability: an unreachable backend can
  never slow ingest, stall a shipper's cursor, or lose a line Muninn would otherwise have stored.
- A backend outage is *recoverable*, not merely survivable — the stored day files make a replay of
  a missed window possible, which a direct process-to-vendor exporter could never offer.
- Exported telemetry is *useful* on arrival, not merely parseable: OpenTelemetry semantic
  conventions on resource and log attributes, so a stock Grafana or Datadog view populates without
  per-field mapping work by the operator.
- Off by default, with an unconfigured deployment behaving exactly as it does today.

## Non-goals (v1)

- No OTLP *ingest* into Muninn (third-party agents shipping into Gimlé). Symmetric, plausible
  future work; not this.
- No OpenTelemetry Collector, Alloy, or any other non-Java process shipped or supervised by Gimlé.
  The operator runs the collector if they want one; Gimlé speaks to it.
- No vendor-native protocols (Datadog `/api/v2/series`, Loki push, Elasticsearch `_bulk`,
  Splunk HEC). See §"Rejected alternatives".
- No redaction, sampling, or filtering pipeline on the egress path beyond a per-signal on/off
  switch. Whatever Muninn stores is what gets exported.
- No per-tenant export targets in v1 — the seam is designed for it (§9), the manifest kind is not
  built.
- No replacement for `AlertRuleSpec`. Alerting stays where it is; this makes the *external* stack's
  alerting possible, which is a different thing.

## Naming: `gimle-gjallarhorn`

Gjallarhorn is Heimdall's horn — the instrument whose entire purpose is to be heard *outside*, by
everyone, at the boundary. That is exactly this component: Muninn remembers, Gjallarhorn announces.
It fits the project's register (`Drakkar, Þjappa, Skald, Bifrost, Galdr, Muninn, Fafnir, Andvari`)
and collides with nothing — `Heimdall` itself is already taken by `gimle-testkit`'s cluster-condition
watcher, and Gjallarhorn is a distinct enough noun that the two never read as the same thing.

## 1. Placement: a subsystem of `gimle-muninn`, not a ninth process kind

Gjallarhorn lives in `com.gimle.muninn.gjallarhorn`, inside `gimle-muninn`, off by default behind
`-Dgimle.muninn.gjallarhorn.enabled=true`.

The precedent is exact: `gimle-bifrost` is package `com.gimle.agent.bifrost` inside `gimle-agent`,
off by default behind `-Dgimle.agent.bifrostEnabled=true`, and is documented as "embedded in the
agent, not a new process kind". Same shape, same reasoning.

Muninn is where the choice becomes obvious. All three signals from all eight process kinds already
converge there — including worker JVMs, which have no outbound network identity of their own and
reach Muninn only by relaying through their agent. Any other placement re-solves a problem Muninn
has already solved:

| Placement | Config surface | Covers workers | Backfill after outage | Verdict |
|---|---|---|---|---|
| Per-process exporter alongside each `MuninnShipper` | N processes × M backends, credentials on every node | Only via new agent-relay plumbing | No — nothing is stored on the egress path | Rejected |
| A ninth process kind pulling from Muninn | One, but needs a new Muninn read/enumerate API plus its own auth identity | Yes | Yes | Rejected: a whole process to avoid a package |
| **Subsystem inside Muninn** | **One** | **Yes, for free** | **Yes, from day files** | **Chosen** |

The cost of the choice, stated plainly: Muninn becomes the first Gimlé process to make a deliberate
outbound connection to a non-Gimlé endpoint, and export latency inherits Muninn's own ship interval
(≈5s from `MUNINN_SHIP_INTERVAL`) on top of Gjallarhorn's flush interval. Neither is acceptable for
a latency-critical trace pipeline; both are irrelevant for the dashboards-and-alerting use case this
targets. A deployment that genuinely needs sub-second trace egress should install a direct OTLP
exporter per process instead — documented as an escape hatch in §11, deliberately not built.

## 2. The standard: OTLP over HTTP, JSON encoding

**OpenTelemetry Protocol (OTLP)** is the choice: the one protocol accepted by the majority of tools,
the only one covering all three signals, and — decisively for the open-source goal above — the
native ingest protocol of essentially every free observability stack currently maintained.

| Open-source target | Reached how | Signals |
|---|---|---|
| Grafana Loki / Mimir / Tempo | OTLP → Grafana Alloy or OpenTelemetry Collector, which fans out | logs, metrics, traces |
| Prometheus | `GET /prometheus` scrape (§6), or OTLP → its own write receiver | metrics |
| VictoriaMetrics | scrapes the same Prometheus format (§6) | metrics |
| Jaeger | OTLP natively | traces |
| SigNoz, OpenObserve, Uptrace | OTLP natively, all three signals in one place | logs, metrics, traces |
| OpenSearch / Elasticsearch | OTLP → Collector `opensearch`/`elasticsearch` exporter | logs, traces |

A collector — Alloy or the OTel Collector, both Apache-2.0 — is the recommended topology and the
one every row above except the two scrape-based ones assumes. It is the operator's process, never
Gimlé's (§"Rejected alternatives"), and it is what turns one OTLP emitter into fan-out, buffering
and routing that Gjallarhorn deliberately does not reimplement.

Proprietary SaaS falls out of the same choice for free — the Datadog Agent, New Relic, Splunk,
Honeycomb, Dynatrace, AWS and Azure all publish OTLP endpoints — but no decision below is made for
their benefit.

Three sub-decisions, each with a real trade-off:

**D1 — HTTP, not gRPC.** gRPC would mean `grpc-java`, which means Netty, which is an explicit
project non-goal, and would be the largest dependency in the reactor. OTLP/HTTP needs nothing
beyond `java.net.http.HttpClient`, which `MuninnShipper` already uses.

**D2 — JSON encoding, not protobuf, in v1.** This is the load-bearing decision and it follows from
what Muninn actually holds. `gimle-muninn` depends on `gimle-core`, `gimle-mimir`, `gimle-pki` and
SLF4J — no Micrometer, no OpenTelemetry SDK. It never sees a `SpanData` or a `Meter`; it sees
`Map<String, Object>` lines parsed from NDJSON. Producing protobuf would mean either reconstructing
SDK objects to hand to `opentelemetry-exporter-otlp` (adding the whole OTel SDK to the one process
that has no use for it) or hand-rolling a protobuf writer (well past "hand-roll it, it's small").
OTLP/JSON is a **spec-defined encoding of the identical schema**, and producing it is a
`Map`-to-`Map` transformation ending in the `Json.write` this codebase already owns — zero new
dependencies, end to end.

The open-source priority is what makes this decision cheap rather than risky. The OTel Collector and
Grafana Alloy share the same `otlpreceiver` implementation and accept the JSON encoding, and they
are the front door for Loki, Mimir, Tempo, OpenSearch and every other row in the table above — so
the intended topology is covered by the encoding that costs zero dependencies. The residual
uncertainty is concentrated in the two direct-ingest cases (Prometheus' own OTLP write receiver, and
SigNoz/Jaeger addressed without a collector), which must be verified at implementation time rather
than assumed from this document; a target that turns out to be protobuf-only is reached through a
collector, which the operator is running anyway. Had proprietary SaaS endpoints been the priority,
protobuf would likely have won this decision — that is exactly the divergence the goals section
resolves in favour of open source.

Protobuf encoding remains the natural v2 addition behind the same `OtlpEncoder` seam, and §3's
normalized model exists so adding it never touches the conversion logic.

**D3 — Semantic conventions are part of the contract, not decoration.** An OTLP payload whose
resource attributes are wrong is syntactically valid and practically useless: no backend will group
it, and no stock dashboard will populate. §4 fixes the mapping explicitly.

Alongside OTLP, a **Prometheus text exposition / OpenMetrics scrape endpoint** (§6) is offered for
metrics only. It is not a second standard competing with OTLP — it is the open-source metrics idiom.
Prometheus and VictoriaMetrics both scrape this exact format, and it is the one path in this design
that needs *no* intermediary process at all: point a Prometheus at Muninn and metrics flow, with no
collector to run. That makes it the cheapest possible entry point for a small self-hosted
deployment, which is why it is promoted to v1 (M2) rather than deferred.

## 3. Internal shape: one normalized model, several encoders

Writing log→OTLP, metric→OTLP, trace→OTLP *and* metric→Prometheus as four independent converters
guarantees they drift. Instead:

```
ingest batch (List<Map<String,Object>>) + subtree path
        │
        ▼
  StreamIdentity            ← parsed once per subtree path; carries the resource attributes
        │
        ▼
  Normalizer ──► NormalizedLog | NormalizedMetric | NormalizedSpan   (records, immutable)
        │
        ├──► OtlpJsonEncoder ──► BatchHttpSink ──► collector / vendor endpoint
        └──► PrometheusTextEncoder ──► GET /prometheus (last-value view)
```

- `StreamIdentity` is derived from the subtree path Muninn already computes
  (`logs/instances/{deployment}#{index}/{category}`, `metrics/{processKind}/{processId}`, …), so no
  new identity plumbing is needed anywhere.
- The `Normalized*` types are records, immutable, per this repo's conventions.
- `BatchHttpSink` should be **extracted from `MuninnShipper`, not copied**. That class is already
  "POST a batch to N endpoints, best-effort per endpoint, own virtual thread, never throw" — which
  is precisely what an OTLP sink is, modulo content type and auth headers. Extracting it into
  `gimle-observability` and having `MuninnShipper` use it leaves one implementation of the fan-out
  and retry semantics rather than two that diverge. (Note the module boundary: `gimle-muninn` does
  not currently depend on `gimle-observability`. Either it gains that dependency for the sink alone,
  or the sink lands in `gimle-core` next to `Json`/`SizeLimitedInputStream`. **Recommendation:
  `gimle-core`** — it is dependency-free HTTP batching with no observability semantics, and putting
  it there keeps Muninn's dependency set untouched.)

## 4. Signal mapping, and the fidelity gaps it exposes

Reading the existing codecs against the OTLP schema surfaces several real gaps. Each is listed with
the concrete change that closes it. `CLAUDE.md`'s "no backward-compatibility concern by default"
applies throughout — these are wire-format revisions, applied directly, with no dual-path fallback.

### 4a. Logs → OTLP `LogRecord`

`JsonLogEncoder` emits `timestamp`, `level`, `logger`, `thread`, `message`, `category`,
`processRole`, `nodeId`, plus `moduleId`/`moduleVersion`/`deploymentName`/`instanceIndex`/`tenantId`
for APPLICATION lines, plus `stackTrace`. That maps cleanly:

| Gimlé field | OTLP | Note |
|---|---|---|
| `timestamp` | `timeUnixNano` | Millisecond precision only (`Instant.ofEpochMilli`) — acceptable, stated for the record |
| `level` | `severityText` + `severityNumber` | `TRACE`→1, `DEBUG`→5, `INFO`→9, `WARN`→13, `ERROR`→17 |
| `message` | `body.stringValue` | |
| `logger` | attribute `code.namespace` | semconv |
| `thread` | attribute `thread.name` | semconv |
| `stackTrace` | attribute `exception.stacktrace` | semconv — this is what makes a backend render it as an error, not a long string |
| `deploymentName` | resource `service.name` | for APPLICATION lines |
| `processRole` | resource `service.name` = `gimle.<role>` | for PLATFORM lines |
| `tenantId` | resource `service.namespace` | the multi-tenant partition key on the far side (§9) |
| `nodeId`, `instanceIndex` | resource `service.instance.id`, `host.name` | |
| `moduleId`, `moduleVersion`, `category` | attributes `gimle.module.id`, `gimle.module.version`, `gimle.log.category` | no semconv equivalent; `gimle.` prefix keeps them clearly ours |

**Gap L1 — no trace correlation.** A Gimlé log line carries no `traceId`/`spanId`, so an exported
log can never be linked to an exported span. This is the single largest thing a Grafana or Datadog
user expects from an export and it is currently impossible. The fix does *not* require
`gimle-core` to depend on OpenTelemetry: add `traceId`/`spanId` MDC keys to `InstanceMdcKeys`, set
by whichever code already establishes the OTel context around a call (`FabricServer`'s inbound
dispatch, `FabricServiceRegistry`'s outbound proxy), and read by `JsonLogEncoder` exactly the way it
already reads `deploymentName`. Logback MDC is the standard mechanism for this and costs one field
on lines that have a trace, nothing on lines that don't. `traceId`/`spanId` then populate the OTLP
`LogRecord`'s own dedicated fields, not attributes.

### 4b. Metrics → OTLP `Metric`

`MeterSnapshotCodec` emits `timestamp`, `name`, `type`, `tags`, `measurements` (a
`Statistic`-name → value map), and optionally `percentiles`.

| Micrometer type | OTLP | Note |
|---|---|---|
| `COUNTER` | `Sum`, monotonic, cumulative temporality | see gap M2 |
| `GAUGE` | `Gauge` | direct |
| `TIMER` | `COUNT`→`Sum`; `TOTAL_TIME`→`Sum` (seconds); `MAX`→`Gauge` | see gap M3 |

Meter names are already dot-separated (`gimle.module.request.latency`), matching OTel naming
conventions, and tags map one-to-one onto data-point attributes. Three gaps:

**Gap M1 — no unit, no description.** `Meter.Id` carries `baseUnit` and `description`; the codec
ships neither. OTLP has `Metric.unit` and `Metric.description`, and both are what make a Grafana
panel render `2.4 s` rather than `2.4`, and what populate a metric browser's help text. Add both to
the shipped line.

**Gap M2 — no counter reset signal.** OTLP cumulative sums require a `startTimeUnixNano`, and a
consumer needs it to detect that a process restarted and its counters went back to zero. Nothing in
the shipped line carries process start time. Without it, every restart reads downstream as a
negative rate or a spurious spike. Add `processStartTime` (the process's own fixed start instant) to
each shipped snapshot line; Gjallarhorn passes it straight through as `startTimeUnixNano`, and a
changed value is exactly the reset signal the consumer needs.

**Gap M3 — no histogram buckets, so no cross-instance quantiles.** The registries publish
percentiles (`publishPercentiles(0.5, 0.95, 0.99)`) and the codec ships them as pre-computed
per-instance quantiles. Those cannot be aggregated: averaging three instances' p95 does not give the
fleet p95, and every backend will do exactly that if handed them as gauges. The correct OTLP shape
is an explicit-bucket `Histogram`, and Micrometer already produces one — `publishPercentileHistogram()`
plus `HistogramSnapshot.histogramCounts()` yields bucket boundaries and counts that map directly.
Recommendation: build the timers with `publishPercentileHistogram()`, ship the buckets, and map to
OTLP `Histogram`; keep shipping the percentiles too (Gimlé's own console reads them today) but
export them as clearly-suffixed gauges so nobody mistakes them for aggregatable series.

### 4c. Traces → OTLP `Span`

This is the mapping that is not currently viable, and the finding is worth stating bluntly:
**`SpanLineCodec`'s format cannot produce a usable OTLP span.** It ships `timestamp` (the span's
*end* time), `traceId`, `spanId`, `parentSpanId`, `name`, `kind`, `status`, and then flattens every
attribute onto the same JSON object.

- **T1 — no start time.** `SpanData.getStartEpochNanos()` is simply not shipped. Every exported
  span would have unknown or zero duration, which is the one thing a trace view exists to show.
- **T2 — attribute flattening collides with reserved keys.** An attribute literally named `name`,
  `kind`, `status` or `timestamp` silently overwrites the span field of that name. This is a latent
  correctness bug in the current format independent of any export.
- **T3 — dropped entirely:** span events, links, instrumentation scope, resource attributes, the
  status *description*, and dropped-attribute counts.

The fix is a `SpanLineCodec` revision: nest attributes under their own `attributes` key (closing T2),
add `startTime` and `endTime` as distinct fields (closing T1), and carry `events`, `links`, `scope`
and `status.description` (closing T3). `RelayingSpanExporter` in `gimle-worker` shares this codec, so
both the direct and relayed paths pick the revision up together — which is exactly why that codec was
extracted in the first place. **Trace export must be gated on this revision landing**; exporting the
current format would produce spans that look present and are wrong, which is worse than not
exporting them.

## 5. Streaming export path

Gjallarhorn hooks `MuninnServer.ingest(...)`, which already holds both the parsed batch and the
subtree path, **after** `dayFileStore.appendLines(...)` returns successfully. Ordering is the whole
design: durability first, export second, always.

The batch is offered to a bounded in-memory queue drained by a dedicated virtual thread — never
exported on the ingest request thread. A slow or unreachable backend must not add latency to an
ingest response, because that response is what advances a `MuninnShipper`'s cursor: coupling them
would let a Datadog outage stall log shipping cluster-wide. On queue overflow, drop oldest, count it
(`gimle.gjallarhorn.dropped`), and log at most once per minute. This is the same best-effort posture
`MuninnShipper` itself already takes, and it is safe here for a stronger reason: the data is already
durable in Muninn, so a drop costs freshness in the external backend and nothing else.

The drain thread batches by size or flush interval (whichever first), encodes once per target
signal, and hands the body to `BatchHttpSink`, which fans out to every configured target
independently — one unreachable target never blocks another, exactly as `MuninnShipper` already
does across Muninn replicas.

## 6. Prometheus scrape surface (metrics only)

`GET /prometheus` on Muninn's existing port, behind
`-Dgimle.muninn.gjallarhorn.prometheus.enabled=true`, rendering the Prometheus text exposition
format from a last-value-per-series map maintained on ingest.

Three things must be said about it plainly:

- It serves **last known values**, not a time series. Staleness is bounded by the producing
  process's ship interval (≈5s) plus scrape interval. That is correct for Prometheus's own model
  (it builds the series from repeated scrapes) but means a scrape immediately after a Muninn restart
  returns little until shippers tick.
- The last-value map is **unbounded by nature** and must be capped: a cluster that churns
  deployments accumulates one series per historical `(process, meter, tags)` tuple forever. Cap the
  series count and evict by TTL, and expose both the cap and the eviction count as meters so hitting
  the cap is visible rather than silent.
- Muninn's port is mTLS in TLS mode. A stock Prometheus scraper must therefore be configured with a
  cluster-issued client certificate, or the deployment must run Muninn in plaintext on a trusted
  network. There is no third option and the docs must say so rather than letting an operator
  discover it through a handshake failure.

Metric name translation (dots to underscores, `_total` on counters, unit suffixes) follows the
OpenTelemetry→Prometheus translation the OTel spec already defines, so the same series name results
whether it arrived by scrape or via OTLP through a collector. This is a real advantage of deriving
both from one normalized model and worth not giving up.

## 7. Configuration

System properties, matching `gimle-muninn`'s existing `-Dgimle.muninn.*` style:

```
-Dgimle.muninn.gjallarhorn.enabled=true
-Dgimle.muninn.gjallarhorn.otlp.endpoint=https://collector.example:4318   (comma-separated for N targets)
-Dgimle.muninn.gjallarhorn.otlp.signals=logs,metrics,traces              (default: all)
-Dgimle.muninn.gjallarhorn.otlp.headersFile=/etc/gimle/otlp-headers      (see below)
-Dgimle.muninn.gjallarhorn.batchSize=512
-Dgimle.muninn.gjallarhorn.flushInterval=5s
-Dgimle.muninn.gjallarhorn.queueCapacity=10000
-Dgimle.muninn.gjallarhorn.resourceAttributes=deployment.environment=prod,cluster=eu-west-1
-Dgimle.muninn.gjallarhorn.prometheus.enabled=false
```

`gimle-hilmir`'s `LaunchPlanner` threads these the same way it already threads
`-Dgimle.*.muninnEndpoint` for five process kinds, and `gimle-holmgang`'s topology YAML gains a
`gjallarhorn:` block alongside its existing `muninn:` one.

Credentials are **optional**, and for the primary topology usually absent: a self-hosted collector
on a trusted network typically needs no auth header at all, and Grafana Cloud-style basic auth or a
bearer token is the exception rather than the norm. An unset `headersFile` must therefore be a
normal, silent configuration — not a warning, and certainly not a startup failure.

When they are needed: **credentials must never be a system property.** An API key passed as `-D` lands in `ps` output, in
`/proc`, and in `LaunchPlanner`'s own logged command line. v1 reads them from a file of
`Header: value` lines with `0600` permissions — the same posture `KeyFileManager` already takes for
Fafnir's master key. v2 should replace that with a Fafnir secret reference resolved at startup and
on rotation; this is deferred only because Muninn holds a `StoreClient` and no `FafnirClient` today,
and adding one is a larger change than v1 should carry.

**TLS trust is a separate store from the cluster's.** The OTLP sink talks to a public or
operator-run endpoint whose certificate is signed by a public CA, not Gimlé's own. It must use the
JDK default truststore (or a separately configured one), explicitly *not*
`SslContexts.forMutualTls(TlsSettings.fromConfig())` — reusing the cluster truststore here would
fail every handshake against a real vendor endpoint, and is the single easiest mistake to make when
implementing this in a codebase where every other outbound call is mTLS.

## 8. Failure modes

| Condition | Behaviour |
|---|---|
| Target unreachable | Queue fills, drop-oldest, counter + rate-limited `WARN`. Ingest unaffected, day files intact, replay available. |
| `429`/`503` | Exponential backoff with jitter, honouring `Retry-After`. Per-target, never global. |
| `400` (malformed payload) | Never retried — a poison batch retried forever starves everything behind it. Logged once with the offending `StreamIdentity`, counted. |
| OTLP partial success | The response's `partialSuccess.rejectedDataPoints` is logged and counted, not retried; the accepted remainder stands. |
| Muninn restart | The in-memory queue is lost. Bounded and accepted — the same tradeoff `MuninnShipper`'s in-memory cursor already makes. Replay (§10) is the recovery path. |
| Duplicate export | `MuninnShipper`'s in-memory cursor already re-ships from "nothing shipped" after a process restart, so Muninn already holds a small duplicate window — which now propagates outward. Stated explicitly because duplicates against a *billed* vendor matter more than duplicates in a local day file. |
| One target failing among N | Isolated per target, exactly as `MuninnShipper` isolates Muninn replicas. |

Gjallarhorn's own health is exported through the same path it exports everything else: queue depth,
dropped batches, per-target success/failure counts and export latency, as ordinary meters. Muninn is
today "the one process with no meter registry of its own"; enabling Gjallarhorn gives it one. That
is a real change to Muninn's shape and should be documented in `observability.md` rather than
slipped in.

## 9. Multi-tenancy: the honest limitation

Muninn's read API is RBAC-gated per tenant — `Authorizer.authorize(..., ResourceKind.LOGS, READ,
tenantId, ...)` on every read. A single cluster-wide export target **flattens that**: every tenant's
logs land in one external account, where Gimlé's RBAC does not apply.

v1's position is that this is an operator decision, made explicit rather than hidden: enabling a
cluster-wide export target declares the external backend to be inside the cluster's trust boundary.
The mitigation that makes the future fix possible is stamping `service.namespace = tenantId` on
every exported record, so a backend that supports scoped access can re-partition on it.

v2's answer is an `ExportTargetSpec` — a Raft-replicated manifest kind alongside `ServiceSpec`,
`NetworkPolicySpec` and `AlertRuleSpec`, naming a tenant and its own endpoint and credentials, so
tenant A's telemetry reaches tenant A's Datadog org and nowhere else. The seam for it belongs in v1:
target selection must be a function of the record's `StreamIdentity`, even while that function only
ever returns the single configured target. Retrofitting per-tenant routing into a design that
assumed one global target is considerably more expensive than leaving the parameter in place.

## 10. Replay and backfill

The reason to accept store-then-forward's extra latency is that it makes an outage recoverable:
`gimle gjallarhorn replay --since <ts> [--until <ts>] [--signals logs,metrics]` re-reads day files
and re-exports a window. A direct process-to-vendor exporter structurally cannot offer this.

It needs one thing that does not exist: **`MuninnDayFileStore` has no subtree-enumeration API.**
Every current read resolves a caller-supplied subtree path; nothing can ask "what streams exist".
Add a `listSubtrees()` (and a day-file listing per subtree) — `RetentionSweeper` already walks the
data root with `Files.walk`, so the traversal shape is established; this exposes it as a first-class
read rather than a sweep-internal detail.

Replay is inherently at-least-once. Say so in the CLI's own help text: a replayed window may
duplicate records the streaming path already delivered, and deduplication is the backend's problem.

## 11. Rejected alternatives

**Vendor-native protocols** (Loki push, Elasticsearch `_bulk`, Datadog `/api/v2/series`, Splunk
HEC). Each is one adapter, one auth scheme, one schema and one deprecation treadmill, forever, and
the set is never complete. This applies to the open-source ones too, and is worth saying explicitly
because Loki's push API is genuinely tempting: it looks like a shortcut to the primary target. It
is not — it would bind Gimlé to Loki's own schema and version cadence, cover exactly one signal,
and duplicate what a twenty-line Alloy config already does. OTLP reaches Loki, Tempo, Mimir and
OpenSearch alike through one emitter. If a specific backend's OTLP path proves genuinely deficient
for a real deployment, that is the moment to reconsider — one adapter, driven by evidence, behind
the same `BatchHttpSink` seam.

**Prometheus `remote_write`.** A real open standard and a plausible second metrics path, rejected
for v1 on cost/benefit: it is protobuf + snappy framing (a new dependency and a hand-rolled encoder,
against §2's zero-dependency result), and it duplicates coverage the scrape endpoint already gives
for the same backends. Reconsider only if pull-based scraping proves unworkable at a real
deployment's scale — a push path matters when a scraper cannot reach Muninn, not merely when it
would be tidier.

**Shipping an OpenTelemetry Collector as a Gimlé process.** The collector is a Go binary. "No
non-Java runtime dependencies" is a first-order project constraint, and this would break it more
directly than anything else in the reactor.

**OTLP directly from every process, no Muninn involvement.** Rejected on config surface (N processes
× M backends, with credentials distributed to every node), on worker coverage (workers have no
outbound identity — new relay plumbing per signal), and on the loss of backfill. It remains the
right answer for a deployment needing sub-second trace egress, and should be documented as a
supported escape hatch: `GimleTracing.install(SpanExporter)` already takes an arbitrary exporter, so
an operator wiring `OtlpHttpSpanExporter` there needs no change to Gimlé at all. That existing
generality is worth pointing at in the docs rather than rebuilding.

**gRPC OTLP.** See D1 — Netty.

## 12. Milestones

| # | Scope | Depends on |
|---|---|---|
| M1 | `StreamIdentity`, normalized model, `OtlpJsonEncoder`, `BatchHttpSink` (extracted from `MuninnShipper` into `gimle-core`), streaming hook, **logs only**, file-based headers | — |
| M2 | Metrics export + `/prometheus` scrape surface | Gaps M1/M2/M3 (unit, description, `processStartTime`, histogram buckets) |
| M3 | Traces export + log↔trace correlation | `SpanLineCodec` revision (T1/T2/T3), `traceId`/`spanId` MDC keys (L1) |
| M4 | `gimle gjallarhorn replay` | `MuninnDayFileStore` enumeration API |
| M5 | `ExportTargetSpec` per-tenant targets, Fafnir-held credentials | `FafnirClient` in Muninn |

M1 is deliberately logs-only and deliberately first: logs are the signal whose current format needs
no revision at all, so it validates the whole egress path end to end before any codec change is
made.

## 13. Testing

Per this repo's conventions, failure paths are as required as happy paths, and reconciler-style
convergence does not apply here (this is a pipeline, not a control loop) — but queue and backoff
behaviour does.

- **Unit**: encoder golden fixtures (an OTLP/JSON body asserted byte-for-byte against a
  spec-shaped payload), severity mapping across every level including unknown ones, Prometheus text
  format and name translation, `StreamIdentity` parsing for all three subtree shapes including
  malformed ones.
- **Failure paths**: queue overflow drops oldest and counts; `400` is not retried; `429` backs off
  and honours `Retry-After`; one failing target does not affect another; an unreachable target never
  delays an ingest response (assert on ingest latency with a sink that blocks).
- **Integration**: a stub OTLP receiver built on `com.sun.net.httpserver`, the same style as the
  existing `MuninnServer*Test` classes, asserting exact received payloads. This is the contract
  boundary — a real Grafana, Prometheus or Datadog is deliberately not a test dependency, and the
  test path must stay pure-Java like everything else here.
- **Smoke** (`gimle-smoke-tests`, `-Psmoke`): extend `ObservabilityIT` — the real cluster ships to a
  real Muninn, which exports to an in-test OTLP receiver; assert a `greeter-provider` APPLICATION log
  line arrives with `service.name`/`service.namespace` set from its real deployment and tenant, and
  (once M3 lands) that the consumer's real cross-worker fabric span arrives with a start time, a
  non-zero duration, and a `traceId` matching the correlated log line.
- **Holmgang** (`-Pvalidation`): a `telemetry-export.feature` scenario booting a topology with
  `gjallarhorn:` enabled, covering both the healthy path and a deliberately-unreachable target
  (asserting ingest and Muninn reads stay entirely unaffected — the property this whole design
  exists to guarantee).
- **Manual, and made easy**: `gimle-dist`'s Midgard dev cluster is already a `docker-compose.yaml`,
  so it should grow an **optional, opt-in compose profile** bringing up Alloy + Loki + Tempo + Mimir
  + Grafana (all Apache-2.0/AGPL, all self-hosted, nothing to sign up for) with Gjallarhorn
  pre-pointed at it. That turns "verify the export actually works against a real open-source stack"
  into one command instead of a runbook nobody follows, and gives the project a place to keep
  reference Grafana dashboards for Gimlé's own meters. Opt-in only — the default Midgard bring-up
  must not gain five containers.
- Deliberately **not** a test dependency: those containers stay out of `mvn verify`, `-Psmoke` and
  `-Pvalidation`. The stub OTLP receiver above is the automated contract boundary; the compose
  profile is for humans.

## 14. Repository bookkeeping this change carries

Per `CLAUDE.md`, and not as deferred follow-up:

- **`requirements-matrix.json`**: new sequential IDs from `GIMLE-844` (the current maximum is
  `GIMLE-843`) for each shipped capability — OTLP export, Prometheus scrape surface, replay,
  trace correlation — with real source locations and real test coverage, never aspirational.
- **`rtm.json`**: matching entries, `"status": "New"`, `"coverage": "Covered"` only once a genuine
  Holmgang Cucumber scenario exercises it end to end — the smoke and unit tests above do not count
  toward that field.
- **`forseti.json`**: each new ID placed either in a scenario's `requirements` (this is
  user-observable — an operator configures it and sees data arrive) or in an `internal` group with a
  reason. The generator fails loudly on an unplaced ID.
- Then `python3 scripts/generate_requirements_docs.py`, which re-renders `REQUIREMENTS_MATRIX.md`,
  `RTM.md`, `uat-checklist.json`, `UAT_CHECKLIST.md` and Forseti's generated tables.
- **`gimle-docs`**: a new "Exporting to external backends" section in
  `docs/architecture/observability.md`; a note in `docs/architecture/node-topology.md`'s Muninn
  section that Muninn now optionally makes outbound calls and carries its own meter registry;
  `docs/reference/cli-reference.md` for `gimle gjallarhorn replay`; and the operator runbook for
  pointing it at a collector.
