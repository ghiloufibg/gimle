# Gimle Gateway

Gateway is Gimlé's north-south HTTP story: a real, hosted (`gimle-module`) Tier-2 module, not a
separate process kind — deployed as a `DaemonSet` onto operator-labeled edge nodes into the
reserved `gimle-system` tenant, a first-party platform component rather than an example. It gives
the platform an HTTP ingress point that can proxy into the service fabric, into a named
deployment's live instance, or into a control-plane-declared `Service`, terminating TLS itself when
configured. Unlike every other module discussed in this repository's other READMEs, Gateway is
consumed the same way any hosted workload is — via a manifest and the ordinary deployment lifecycle
— not launched as its own `*Main` process.

## Why a module, not a process

`com.gimle.module`, `com.gimle.core`, and `org.slf4j` are declared `requires static` in
`module-info.java`, not `requires` — the same boot-only-platform-layer workaround every other
hosted module in this codebase uses (see `ModuleLayerFactory`'s own javadoc in `gimle-module`):
resolution succeeds with the dependency unsatisfied at `Configuration.resolve` time, and
`ModuleLayerFactory` separately grants this module's layer readability to the platform's own
classes at runtime.

## Route kinds

`GatewayRoute` is a sealed interface over three record shapes, each dispatched differently by
`GatewayDispatcher`:

| Kind | Target | Resolved via | Dispatch |
|---|---|---|---|
| `FabricRoute` | An interface/method by name | `ModuleContext#invokeServiceByName` (a runtime-config-driven name, not a compile-time generic type) | `GET` for a zero-arg route, `POST` for a one-arg route, body coerced to the declared `ParamType` |
| `VesselRoute` | A live instance of a named deployment, on a named port | `VesselEndpointCache` → `ModuleContext#relayControlPlaneRead("/endpoints/{name}")` | Verbatim proxy — exact path, every HTTP method, full body, via `VesselProxyClient` |
| `ServiceRoute` | A live endpoint of a control-plane-declared `Service` | `ServiceEndpointCache` → `relayControlPlaneRead("/services/{name}/endpoints")` | Verbatim proxy, identical to `VesselRoute` but with no separate `portName` — a `Service`'s endpoints already carry the one port they're reachable on |

`FabricRoute`'s argument shape is deliberately restricted in v1: zero arguments, or exactly one
plain `String`/boxed primitive (`ParamType.NONE`/`STRING`/`INT`/`LONG`/`DOUBLE`/`BOOLEAN`) — never
general JSON-to-POJO mapping. `VesselRoute`/`ServiceRoute` proxy the request unrestricted on method
and body, but forward the inbound path to the target **verbatim** — no prefix stripping, no
wildcard expansion, a deliberate v1 scope limit stated up front rather than discovered lazily.
Dispatch itself is exact-path lookup only (`Map.get` against a route table keyed by literal path) —
no prefix/wildcard routing for any route kind, which is exactly what lets the vessel/service
proxying stay verbatim with no rewriting logic to get wrong.

## Host-constrained (vhost) routing

Every route kind carries an optional `host()` — the `Host` header value it requires — additive on
top of the original path-only matching, fully backward-compatible: a route with no `HOST` segment
still matches any host, exactly as before host-based routing existed. `GatewayDispatcher.selectRoute`
resolves precedence among a path's candidate routes: an exact (case-insensitive) `Host` match wins
outright; failing that, a route declaring no host constraint serves as the fallback; failing that —
every candidate demands a specific host and none matched — there is no route to serve the request.
This is what lets a host-unconstrained route keep answering exactly as before even on a path that
also carries host-constrained siblings, and lets one path host several virtual hosts plus an
optional default.

## Route configuration

`GatewayRouteConfig.parse` reads the `gateway.routes` config key in a deliberately simple,
line-oriented format — not YAML/JSON, not a general route DSL:

```
[HOST <hostname>] FABRIC <httpPath> <interfaceName> <majorVersion> <methodName> <paramType>
[HOST <hostname>] VESSEL <httpPath> <deploymentName> <portName>
[HOST <hostname>] SERVICE <httpPath> <serviceName>
```

```
# kind    path         interface/deployment/service                  version  method  paramType
FABRIC    /greet       com.gimle.examples.greeter.Greeter            1        greet   STRING
HOST orders.example.com VESSEL /api/orders orders-service HTTP_PORT
SERVICE   /api/payments payments
```

Two routes at the same path with the *same* host constraint (including two both left
unconstrained) are a config error, rejected at parse time. Two routes at the same path with
*different* host constraints are the ordinary virtual-hosting shape — not a duplicate. Blank lines
and `#` comments are ignored.

## Lifecycle and transport

`GatewayHooks` is this module's `ModuleLifecycleHooks`: `onStart` reads `gateway.port` and
`gateway.routes` from `ctx.config(...)` (both required — there is no fixed default port, since an
operator must pick a non-colliding port across co-located `DaemonSet` instances), parses the route
table, and binds one `HttpServer`/`HttpsServer` context per distinct path (not per route — a
host-constrained route and its sibling can share a path, since `HttpServer#createContext` rejects a
second context at an already-bound path; `GatewayDispatcher` itself resolves which route of a
path's set actually serves a given request).

TLS termination is built the same way `ApiServer`/`FafnirServer`/`MuninnServer`/`AndvariServer`
build theirs: plaintext by default, `-Dgimle.transport.protocol=tls` (plus
`gimle.tls.certFile`/`keyFile`/`caFile`) opts into an `HttpsServer` with `wantClientAuth` rather
than `needClientAuth` — a north-south caller reaching this gateway from outside the cluster has no
cluster-issued client certificate to present, unlike the East-West `needClientAuth` posture the
control plane and other internal servers use. This is TLS **termination**, not a TLS relay: an
external caller's connection is decrypted here, and the gateway speaks to the rest of the cluster
the same way it always has (plain HTTP to a resolved fabric/vessel/service target via
`VesselProxyClient`). TLS is not a `ctx.config(...)` key — it's the same cluster-wide system
property every other TLS-capable listener in this codebase reads, forwarded onto this module's
worker JVM by the supervising agent the same way any operator `-D` flag is.

Each inbound request runs on its own virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`)
— a request blocks synchronously on a real fabric round trip, possibly cross-machine, so a
fixed-size platform-thread pool would itself become the bottleneck the gateway exists not to be. A
request body is read through a `SizeLimitedInputStream` capped at 50 MiB, the same
attacker-controlled-ingest discipline Muninn's and Saga's own ingest endpoints apply.

## Endpoint resolution: TTL cache, not per-request

`VesselEndpointCache` and `ServiceEndpointCache` share one posture: resolve through
`ModuleContext#relayControlPlaneRead` and cache the result on a fixed TTL (`ServiceEndpointCache`
reuses `VesselEndpointCache.DEFAULT_TTL` directly — "no independent judgment call needed"), round-
robin the ready targets, and on a refresh failure fall back to the still-cached list rather than
failing every in-flight request on one bad refresh. A relay round trip crosses this instance's own
worker and its supervising agent on every call, which is what makes polling on a TTL rather than
per-request the right tradeoff. An endpoint entry is usable only with a non-blank host and a
positive port; `ServiceEndpointCache` additionally needs no `portName` lookup the way
`VesselEndpointCache` does, since a `Service`'s endpoints already fix the one port they're reachable
on.

## Key types

| Type | Role |
|---|---|
| `GatewayRoute` | Sealed interface — `FabricRoute`/`VesselRoute`/`ServiceRoute` |
| `GatewayRouteConfig` | Parses `gateway.routes` text into `GatewayRoute`s |
| `GatewayDispatcher` | Path/host route selection and per-kind dispatch; deliberately free of `com.sun.net.httpserver` types so it's testable without a bound socket |
| `GatewayHooks` | `ModuleLifecycleHooks` — binds the listener, wires the dispatcher, TLS termination |
| `VesselEndpointCache` / `ServiceEndpointCache` | TTL-cached endpoint resolution for VESSEL/SERVICE routes |
| `VesselProxyClient` | The actual HTTP proxy call to a resolved target |
| `GatewayLivenessProbe` / `GatewayReadinessProbe` | Probe implementations — this module has no failure mode of its own to report once loaded |
| `HostPort` | Resolved proxy target |
| `GatewayConfigException` / `GatewayBadRequestException` / `GatewayUpstreamException` | Config parse errors, bad request-body coercion, upstream relay failures |

## How other modules relate to it

- Deployed like any other Tier-2 hosted module, through `gimle-controlplane`'s ordinary
  `DaemonSetSpec` lifecycle — nothing about its deployment path is special-cased.
- `FabricRoute` dispatches through `gimle-fabric`'s `FabricServiceRegistry.invokeByName` (via
  `ModuleContext`), the same name-driven path `gimle-worker` exposes to any hosted module.
- `VesselRoute`/`ServiceRoute` both read `gimle-controlplane`'s `/endpoints/*` and
  `/services/*/endpoints` APIs through the relay mechanism every hosted module's `ModuleContext`
  exposes for control-plane reads.
