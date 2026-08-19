# greeter-consumer

Companion to `greeter-provider`: a real, deployable Gimlé module that looks up the provider's
`Greeter` service over the fabric and calls it, exercising `ModuleContext#lookupService`'s actual
cross-worker/cross-machine proxy and wire-codec path — not a same-worker in-JVM shortcut.

## Manifest

```yaml
name: com.gimle.examples.greeter.consumer
version: 1.0.0
isolation:
  tier: TIER_2
resources:
  request:
    memory: 32Mi
    cpu: 20m
  limit:
    memory: 64Mi
    cpu: 100m
lifecycle:
  hooks: com.gimle.examples.greeter.consumer.GreeterConsumerHooks
health:
  liveness: com.gimle.examples.greeter.consumer.GreeterLivenessProbe
  readiness: com.gimle.examples.greeter.consumer.GreeterReadinessProbe
```

No `exports` section — this module only consumes a service, it publishes none. Tier 2, same as
`greeter-provider`: both being dedicated-worker modules guarantees they can never share a worker JVM,
so the lookup below can never take the same-worker `Class`-identity shortcut and always goes over the
real fabric wire protocol.

## What it does

- **`GreeterConsumerHooks.onStart`** spawns a background virtual thread (`greeter-consumer-caller`)
  rather than calling the fabric inline: `onStart` runs synchronously on the worker's single
  control-channel receive loop, the same loop that delivers the `CatalogUpdate` messages service
  discovery depends on, so blocking there while waiting for that same catalog to populate would
  deadlock against itself. The captured MDC context map is restored on the background thread so its
  logging still lands as this instance's own APPLICATION output rather than untagged PLATFORM output.
- That thread loops every 5 seconds (`CALL_INTERVAL`), each iteration calling
  `ctx.lookupService(Greeter.class)` fresh (never caching the resolved proxy — a proxy is bound to one
  specific `ServiceEndpoint`, so caching it across a provider redeploy would leave this consumer
  permanently stuck against a dead endpoint) and, if found, `greeter.greet("Gimlé")`, logging the
  reply. Lookup/call failures are logged as warnings and retried on the next iteration.
- **`GreeterReadinessProbe`** reports ready only once a call has ever succeeded
  (`GreeterConsumerHooks.everSucceeded`) — a consumer isn't really ready to serve traffic until its own
  dependency is reachable.
- **`GreeterLivenessProbe`** always reports alive.

`module-info.java` follows the same `requires static com.gimle.module`/`org.slf4j` pattern as
`greeter-provider` (the worker's platform layer is boot-only, so a plain `requires` would fail
resolution), and exports both `com.gimle.examples.greeter.consumer` and `com.gimle.examples.greeter`
(the latter for `FabricServer`'s reflective dispatch).

## Deploying it

`deployment.yaml` points at
`gimle-examples/greeter-consumer/target/greeter-consumer-0.1.0-alpha.2.jar`. Deploy alongside
`greeter-provider` and watch this instance's application log for `Hello, Gimlé! (from provider)` lines
— the real cross-worker call succeeding on a live heartbeat, asserted end to end by
`gimle-smoke-tests`' `GreeterClusterTopologyIT`.
