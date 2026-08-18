# greeter-load-generator

A third real, deployable module in the greeter family, alongside `greeter-provider` and
`greeter-consumer`. Its only job is translating inbound HTTP requests into real
`ModuleContext#lookupService` fabric calls against `greeter-provider`'s `Greeter` — the bridge an
external HTTP load-generation tool (Gatling, in `gimle-smoke-tests`) needs to drive genuine,
high-rate fabric traffic against a deployed instance, since the fabric's own wire protocol has no
client outside a hosted module. Every inbound HTTP request is exactly one real, synchronous
cross-worker fabric call, so a request rate an external tool controls becomes `greeter-provider`'s own
real, worker-reported `requestRatePerSecond` — a genuine signal, not a synthetic stand-in for one.

## Manifest

```yaml
name: com.gimle.examples.greeter.loadgen
version: 1.0.0
isolation:
  tier: TIER_2
resources:
  request:
    memory: 64Mi
    cpu: 50m
  limit:
    memory: 128Mi
    cpu: 300m
lifecycle:
  hooks: com.gimle.examples.greeter.loadgen.GreeterLoadGeneratorHooks
health:
  liveness: com.gimle.examples.greeter.loadgen.GreeterLoadGeneratorLivenessProbe
  readiness: com.gimle.examples.greeter.loadgen.GreeterLoadGeneratorReadinessProbe
```

Tier 2, like the other two greeter modules — a dedicated worker JVM, so its calls to
`greeter-provider` are genuine cross-worker fabric calls. Higher resource requests/limits than the
provider or consumer, reflecting the concurrent request volume it's meant to absorb.

## What it does

`GreeterLoadGeneratorHooks.onStart` reads a required `load.port` value via `ctx.config(...)` (the same
config-driven pattern `gimle-gateway`'s own `gateway.port` uses — a caller leases a real port and
delivers it as tenant config, so multiple concurrently deployed instances never collide on a fixed
port) and starts a `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1:<port>`, one context at
`/call`, backed by `Executors.newVirtualThreadPerTaskExecutor()` (a fixed-size pool would itself become
the bottleneck under a bursty concurrent load test). Each `/call` request does one synchronous
`ctx.lookupService(Greeter.class)` + `greet("Gatling")` and reflects the outcome as an HTTP status: 200
with the reply, 503 if no `Greeter` is registered yet, 502 if the call itself failed. Unlike
`GreeterConsumerHooks`, it deliberately logs nothing per request — a Gatling run driving tens of
requests per second would flood the instance's own log file and skew the load characteristics under
test.

A missing or non-integer `load.port` config value is rejected up front via
`LoadGeneratorConfigException`, before any HTTP listener binds.

`GreeterLoadGeneratorReadinessProbe` reports ready only once the HTTP port is actually bound;
`GreeterLoadGeneratorLivenessProbe` always reports alive. `module-info.java` follows the same
`requires static com.gimle.module`/`org.slf4j` pattern as its siblings, adds a plain `requires
jdk.httpserver` (a real JDK platform module, always present in the boot layer, so it needs none of that
workaround), and exports both its own package and `com.gimle.examples.greeter` — this module never
registers a `Greeter`, but looking one up by `Class<Greeter>` needs the same export for its own literal
copy of the interface to resolve identically to the provider's.

## Deploying it

No `deployment.yaml` is checked in here (unlike its two siblings) — `load.port` must be supplied as
per-deployment config rather than a fixed manifest value, since concurrently running instances (as in
`gimle-smoke-tests`' `AutoscaleIT`/`RollingUpdateIT`, which each deploy one) must not collide on the
same listen port. Build the jar and submit your own `Deployment` alongside `greeter-provider`, with
`load.port` set in that deployment's config, then point an HTTP client or load tool at
`http://127.0.0.1:<load.port>/call`.
