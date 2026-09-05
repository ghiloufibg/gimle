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
| `VesselRoute` | A live instance of a named deployment, on a named port | `VesselEndpointCache` → `ModuleContext#relayControlPlaneRead("/endpoints/{name}")` | Verbatim proxy — exact or prefix path (see below), every HTTP method, full body, via `VesselProxyClient` |
| `ServiceRoute` | A live endpoint of a control-plane-declared `Service` | `ServiceEndpointCache` → `relayControlPlaneRead("/services/{name}/endpoints")` | Verbatim proxy, identical to `VesselRoute` but with no separate `portName` — a `Service`'s endpoints already carry the one port they're reachable on |

`FabricRoute`'s argument shape is deliberately restricted in v1: zero arguments, or exactly one
plain `String`/boxed primitive (`ParamType.NONE`/`STRING`/`INT`/`LONG`/`DOUBLE`/`BOOLEAN`) — never
general JSON-to-POJO mapping. `VesselRoute`/`ServiceRoute` proxy the request unrestricted on method
and body, and forward the inbound path to the target **verbatim** — the full, untouched inbound
path, never a rewritten/stripped one, whether the matched route is exact or a prefix (see below).

## Path matching: exact and prefix

Dispatch has two tiers, tried in order: an exact-literal-path lookup exactly as this module has
always done it, then, if that misses, a prefix-match scan — one bucket per declared prefix,
pre-sorted longest-prefix-first, so the most specific matching prefix always wins (the same
longest-prefix-match rule an nginx `location` block or a Kubernetes Ingress rule set uses, not
"first registered wins"). Exact match is always the most specific possible match; it never loses to
a prefix, even one whose declared string is identical.

Only `VesselRoute`/`ServiceRoute` can be declared as a prefix, via a trailing `/*` on `httpPath` in
the config format below (`/api/orders/*`, or bare `/*` for a catch-all matching every path) — the
same spelling a Kubernetes Ingress path or an nginx `location` prefix uses. `FabricRoute` is
permanently exact-path-only (rejected at parse time if given a `/*`-suffixed path): it names one
specific fabric method call, not a resource subtree, and `GatewayDispatcher#dispatchFabric` never
reads the inbound path beyond the one the route is registered under, so a path segment past a
would-be prefix would carry no meaning. Matching is segment-boundary aware — `/api/orders/*`
matches `/api/orders` and `/api/orders/42`, but not `/api/orders2`.

A prefix match keeps the vessel/service proxying "verbatim" in exactly the sense above: unlike an
Ingress `rewrite-target` rule, which strips the matched prefix before forwarding, a plain prefix
match keeps the full original path on the proxied call (Kubernetes Ingress's own default
`pathType: Prefix` behavior) — no new path-rewriting logic for `GatewayDispatcher` to get wrong.

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

Routes are declared as `Ingress` resources, one per tenant, and reach a gateway by polling the
control plane's `GET /ingresses` (`HttpIngressSource`, converted by `IngressRoutes`). There is no
config key carrying routes: a route table written as opaque text could only ever be checked when a
gateway happened to parse it, so a typo reached the cluster as an accepted write and surfaced
seconds later as a route that silently never matched. An `Ingress` is validated where it is
submitted, and is listed and RBAC-gated like any other resource.

```yaml
kind: Ingress
name: edge
tenantId: gimle-system
routes:
  - kind: FABRIC
    path: /greet
    interfaceName: com.gimle.examples.greeter.Greeter
    majorVersion: 1
    methodName: greet
    paramType: STRING
  - kind: VESSEL
    host: orders.example.com
    path: /api/orders
    prefix: true
    deploymentName: orders-service
    portName: HTTP_PORT
  - kind: SERVICE
    path: /api/payments
    serviceName: payments
```

Two routes at the same path with *different* host constraints are the ordinary virtual-hosting
shape — not a conflict; likewise, an exact route and a prefix route sharing the same base path and
host are a deliberate pair (an exact match on a collection's own root served one way, everything
nested under it proxied another way), resolved unambiguously by exact-beats-prefix precedence. Two
routes that do collide outright are resolved by the same precedence rather than refused at
submission: neither is wrong on its own, and rejecting the second would make the outcome depend on
which was submitted first.

## Lifecycle and transport

`GatewayHooks` is this module's `ModuleLifecycleHooks`: `onStart` reads `gateway.port` and
`gateway.controlPlaneEndpoint` from `ctx.config(...)` (both required — there is no fixed default
port, since an operator must pick a non-colliding port across co-located `DaemonSet` instances),
binds its listener with an empty route table, and lets the first reload tick fetch the declared
Ingresses. It binds one `HttpServer`/`HttpsServer` context per distinct path (not per route — a
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
`VesselProxyClient`). Whether TLS is on is not a `ctx.config(...)` key — it's the same cluster-wide
system property every other TLS-capable listener in this codebase reads, forwarded onto this
module's worker JVM by the supervising agent the same way any operator `-D` flag is.

### Per-virtual-host certificates (SNI)

One cluster-wide certificate is not enough for a gateway that routes by `Host`: a client verifies
the presented certificate against the hostname *it* dialled, so with a single certificate every
routed hostname outside that certificate's SAN fails TLS before its otherwise-functional route is
ever consulted. The optional `gateway.tlsCertificates` config key binds hostnames to their own key
pairs, one per line (`GatewayTlsConfig`):

```text
<hostname> <certFile> <keyFile>
```

At handshake time a custom `X509ExtendedKeyManager` (`SniKeyManager` in `gimle-core`) reads the
hostname from the client's SNI extension and presents that hostname's certificate. A client that
sends no SNI at all, and one naming a hostname with no binding here, both get the cluster-wide
`gimle.tls.certFile`/`keyFile` certificate — so a gateway that configures nothing behaves exactly
as a single-certificate listener always did. Selection deliberately never *rejects* a connection
(no `SNIMatcher` is installed): an unrecognized hostname is still served by a host-unconstrained
route, and failing its handshake closed would take that fallback routing down with it.

Bindings carry no `caFile` of their own — trust is cluster-wide and already carried by
`gimle.tls.caFile`; what varies per virtual host is only the identity the gateway presents.
`gateway.tlsCertificates` is re-read on the same background interval the route table is: SNI
selection already runs fresh on every new handshake, so swapping which certificate a hostname
resolves to is not a rebind the way changing `gateway.port` is — a config change reaches an
already-running instance the same way a route-table change does.

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
