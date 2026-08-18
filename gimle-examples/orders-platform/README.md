# Orders Platform

A lightweight Spring microservices app hosted as real Gimlé modules. This is **not platform
code** — it's a hand-built cowboy application for manually validating a running Gimlé cluster end
to end, the first real-world (not deliberately-inert, not a synthetic fixture) application ever
deployed on Gimlé. It is deliberately **not** listed in the repo root `pom.xml`'s modules list —
see that file's own comment, and this directory's own `pom.xml`, for why. Never add it there.

## What it is

Four real Java modules, each a genuine Spring `AnnotationConfigApplicationContext` doing real
dependency injection, packaged as real Gimlé module jars and talking to each other over Gimlé's
own fabric `ServiceRegistry` — not a second HTTP layer bolted on top of the platform's own service
mesh (the one exception, deliberately, is `web-ui`'s own outward-facing HTTP surface — see its own
entry below):

- **`orders-service`** — a Spring-managed `OrderBook` bean (constructor-injected with an
  `OrderIdGenerator` collaborator) publishing the fabric service `com.example.orders.OrderCatalog`.
  Seeds two demo orders on startup (5 widgets, 3 gadgets) so there's something to look at without
  writing a client first.
- **`inventory-service`** — a Spring-managed `StockLedger` bean publishing
  `com.example.inventory.InventoryLevels`, seeded with starting stock for the same two SKUs. Looks
  up `OrderCatalog` on a background virtual thread every 20s and logs a stock/ordered/remaining
  reconciliation line per SKU — the same "never call a fabric service inline from `onStart`, always
  from a background thread with MDC captured across it" pattern `gimle-examples/greeter-consumer`
  already establishes.
- **`orders-report-job`** — a `JobHooks` (not `ModuleLifecycleHooks`) module: looks up both
  `OrderCatalog` and `InventoryLevels`, logs a consolidated report, and returns
  `CompletionStatus.SUCCEEDED` — rendering "unavailable" for whichever collaborator it couldn't
  reach rather than failing the whole job over one missing dependency.
- **`web-ui`** — a plain-HTML/CSS/JS page plus a small JSON REST API
  (`GET /api/inventory`, `POST /api/orders`), served by a real embedded
  `com.sun.net.httpserver.HttpServer` `WebUiHooks` boots on a fixed port (no servlet container, no
  web framework — the same "hand-roll it, it's small" posture `gimle-controlplane`'s own
  `ApiServer` already uses). Every request looks up `OrderCatalog`/`InventoryLevels` over the
  fabric fresh, the same graceful-degradation pattern `orders-report-job` already establishes —
  this is the one client in the app a person outside the cluster can drive directly from a
  browser, instead of another hosted module's own code. It also reports its own port
  (`ctx.reportPort`), the one thing that lets a control-plane `Service`/`gimle-gateway` route
  reach it by name instead of a node-specific address — see
  [Reaching the web UI from outside the cluster](#reaching-the-web-ui-from-outside-the-cluster)
  below for the full story, and why its port still needs a different publishing story than the
  other three modules' fabric-only traffic.
  Also the only module in this app that's tenant-scoped and reads delivered config at all:
  `POST /api/orders` requires an `X-Admin-Token` header matching a real secret Fafnir delivers —
  see [Placing orders requires a real secret](#placing-orders-requires-a-real-secret) below.

Each module bundles its own literal, independently-compiled copy of any fabric interface it
consumes (see `OrderCatalog.java`'s own javadoc in `orders-service`) — the same "structural
contract, not a shared jar" convention `greeter-provider`/`greeter-consumer` already establish.

## The five workload kinds

Gimlé offers five workload manifest kinds; this app deploys real code under every one of them.
Three of the five reuse an already-built jar under a second manifest **unchanged** — deliberately:
which kind a module runs under is a scheduling/placement decision the control plane's own
reconcilers make, not something the module's own code has to know about or implement differently.

| Kind | Manifest | Module |
| --- | --- | --- |
| Deployment | `orders-service/deployment.yaml` | orders-service |
| StatefulSet | `inventory-service/statefulset.yaml` | inventory-service |
| DaemonSet | `inventory-service/daemonset.yaml` | inventory-service (same jar) |
| CronJob | `orders-report-job/cronjob.yaml` | orders-report-job, fires every 5 minutes |
| Job | `orders-report-job/job.yaml` | orders-report-job (same jar), run once on demand |

Deploy `statefulset.yaml` **or** `daemonset.yaml` for inventory-service, not both at once — they're
alternate manifests for the same module, not two different deployments meant to coexist. Same for
`cronjob.yaml`/`job.yaml`.

## Building

This tree is not part of the root reactor, so build it explicitly, from this directory:

```sh
mvn -f gimle-examples/orders-platform/pom.xml package
```

Prerequisite: `com.gimle:gimle-module` (and its own `gimle-core` dependency) must already be
installed into your local Maven repository at the exact version this tree's own `pom.xml` pins
(`gimle.platform.version`) — a real Gimlé build already does this:

```sh
mvn install -DskipTests   # from the repo root
```

Each module's `package` phase produces one self-contained jar (`orders-service.jar`,
`inventory-service.jar`, `orders-report-job.jar`, `web-ui.jar`) with Spring shaded directly in —
see the parent `pom.xml`'s own comments for exactly how and why (JPMS module-info handling around
maven-shade-plugin is the one genuinely tricky part of this whole app; that pom explains it in
detail rather than leaving it as unexplained magic). `orders-load-generator.jar` is the one
exception: no third-party dependency, so no shading step at all — see its own `pom.xml`.

## Deploying

Against a running Gimlé cluster (see `gimle-console/LOCAL_DEV.md` in the repo root for how to
stand one up locally: store, control plane, one agent):

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/orders-platform/orders-service/deployment.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/orders-platform/inventory-service/statefulset.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/orders-platform/orders-report-job/cronjob.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/orders-platform/web-ui/deployment.yaml
```

`web-ui`'s own manifest declares `tenantId: orders-platform` — create that tenant, and set the
secret it reads, *before* applying it (see
[Placing orders requires a real secret](#placing-orders-requires-a-real-secret) below); applying
it against a tenant that doesn't exist yet is rejected at admission, the same way an unpushed
artifact coordinate is.

Then watch it happen: the console's Logs screen, or `gimle-cli`'s own `logs --follow`. You should
see orders-service seed its two demo orders, inventory-service start logging reconciliation lines
once orders-service is up, and (every 5 minutes, or immediately if you deploy `job.yaml` instead
for an on-demand run) orders-report-job log a consolidated report of both.

## Reaching the web UI from outside the cluster

`web-ui` binds a fixed port (`8090`) rather than an agent-allocated one: a plain Gimlé module has
no port-allocation mechanism of its own — that exists only for `VesselSpec`-hosted processes,
which in turn have no fabric access at all (confirmed: no `ModuleContext`, no `ctx.lookupService`,
nothing — see `VesselSpec`'s own javadoc in `gimle-core`). Neither hosting mode offers both a
fabric connection and platform-managed ports, so a fixed port is the deliberate, simplest choice
here, not a gap. What *does* come from the platform now is discoverability: `WebUiHooks#onStart`
calls `ctx.reportPort("http", 8090)`, folding the port into this instance's own metrics report the
same way a Vessel's own agent-allocated port already does — which is what lets a
control-plane-declared `Service` resolve a live endpoint for it (see below), regardless of which
node the scheduler actually placed the instance on.

### The recommended path: a Service, fronted by the gateway

1. **Declare a `Service`** fronting `web-ui-deployment` — see `service.yaml` in this directory for
   the full `POST /services` body and why it's a checked-in doc file rather than something
   `gimle:deploy` consumes (a Service isn't one of the five workload manifest kinds). Apply it
   after `web-ui-deployment` reaches `ACTIVE`:

   ```sh
   curl -X POST http://localhost:8080/services -H 'Content-Type: application/json' \
     -d '{"name":"web-ui","tenantId":"orders-platform","deploymentNames":["web-ui-deployment"],
          "port":80,"targetPort":8090}'
   ```

   `GET /services/web-ui/endpoints` now resolves to wherever the real instance actually landed —
   no more caring which node that was.

2. **Add a gateway route.** `gimle-gateway` is an ordinary opt-in `DaemonSet` module elsewhere in
   this repo, not deployed by this app itself; if one is already running in your cluster (see
   `gimle-docs/docs/architecture/service-fabric.md` for how it's brought up), add these lines to
   its `gateway.routes` config value:

   ```
   SERVICE /api/inventory web-ui
   SERVICE /api/orders    web-ui
   SERVICE /             web-ui
   ```

   Now the gateway's own already-published address is the one thing that needs reaching from
   outside the cluster — not `web-ui`'s node, not its port, and (unlike the old fixed-port story)
   it round-robins across every live replica if `web-ui-deployment` is ever scaled past
   `replicas: 1`, something the raw-port approach below has no way to do at all.

   ```sh
   curl http://<gateway-host>:<gateway-port>/api/inventory
   curl -X POST http://<gateway-host>:<gateway-port>/api/orders -H 'Content-Type: application/json' \
     -H 'X-Admin-Token: <the same token>' -d '{"sku":"widget","quantity":2}'
   ```

3. **Optional: give it a DNS name.** Once the Service exists, `web-ui.orders-platform.svc.gimle.local`
   is resolvable by `gimle-skald` — a stable name to put in a `HOST` line instead of a raw gateway
   address, if a resolver in your environment points at Skald:

   ```
   HOST web-ui.orders-platform.svc.gimle.local SERVICE / web-ui
   ```

### The direct path: still works, useful for quick local debugging

Nothing above removes `web-ui`'s own real bound port — it's still there, still fixed, still
reachable directly if the node it landed on is reachable at all:

- **Local `mvn gimle:*` cluster** (`gimle-console/LOCAL_DEV.md`): the agent runs directly on your
  own machine, so `http://localhost:8090/` is reachable the moment `web-ui-deployment` reaches
  `ACTIVE` — nothing extra to configure.
- **`docker-compose.full-jre.yml`** (`gimle-holmgang/compose/`): the worker JVM hosting `web-ui`
  runs as a subprocess inside the `agent` container, sharing its network namespace. The compose
  file's own `agent` service publishes `8090:8090` for exactly this reason; `http://localhost:8090/`
  reaches it from the docker host.

```sh
curl http://localhost:8090/api/inventory
curl -X POST http://localhost:8090/api/orders -H 'Content-Type: application/json' \
  -d '{"sku":"widget","quantity":2}'
```

or just open `http://localhost:8090/` in a browser for the page itself. This is the one path that
bypasses the Service/gateway entirely, so it's also the one to reach for if you're debugging
whether a problem is in `web-ui` itself or in the Service/gateway layer in front of it.

## Restricting cross-tenant access (optional)

`networkpolicy.yaml` in this directory declares a deny-by-default `NetworkPolicySpec` scoped to
`web-ui-deployment` — apply it the same way as `service.yaml`, via `POST /networkpolicies`. Worth
being precise about what it actually restricts in this single-tenant sandbox, rather than
overstating it:

- **It has no effect on the gateway route above.** `gimle-gateway`'s own `SERVICE` route has no
  tenant-policy check of its own — external reachability is an operator's own opt-in publishing
  decision, a different concern from inter-tenant fabric traffic, which is what `NetworkPolicySpec`
  actually governs.
- **It has no effect via `FabricServer` either**, because `web-ui` doesn't export a fabric service
  of its own (it only *consumes* `OrderCatalog`/`InventoryLevels`) — `FabricServer.checkNetworkPolicyPermitted`
  only ever gates inbound calls to a module that exports one. In this app that's `orders-service`/
  `inventory-service`. `orders-service` is tenant-scoped now (see "Surviving redeploy with a real
  database" above) — the next section below is the policy that actually targets it, and the
  walkthrough that makes its effect observable, rather than just documenting what a policy scoped
  to `web-ui` itself can't reach. `inventory-service` stays untenanted, so it still can't be named
  by a policy.
- **What it does demonstrate**: if `gimle-bifrost` is enabled on the node hosting `web-ui`
  (`-Dgimle.agent.bifrostEnabled=true`), this policy makes `BifrostProxy` refuse to proxy `web-ui`'s
  Service to any same-node caller — Bifrost relays opaque bytes for whatever protocol a caller
  speaks, so it has no caller identity to check the policy's `allowedCallerTenantIds` against, and
  fails closed rather than risk silently bypassing a restriction the tenant opted into.

## Restricting cross-tenant access, for real this time

The policy above never actually rejects a fabric call in this app — `web-ui` exports nothing to
gate. `orders-service` does export something (`OrderCatalog`), and is tenant-scoped, so
`orders-service/networkpolicy.yaml` is the version of this demo that has a real, observable effect
on a real fabric call.

**Set up a second, independent tenant** and its own `web-ui` instance, listening on a different
port so it doesn't collide with the original if both land on the same node:

```sh
gimle set tenant orders-platform-2 --max-memory-bytes 268435456 --max-cpu-millicores 500 --max-instances 2
gimle set config orders-platform-2 web.port 8092
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/orders-platform/web-ui/deployment-second-tenant.yaml
```

**Confirm it can reach `OrderCatalog` before any policy exists** — same-tenant-only restrictions
don't apply yet, and `orders-platform-2` isn't `orders-platform`, but nothing is blocking anyone
until a policy is actually applied:

```sh
curl -s http://localhost:8092/api/inventory   # widget/gadget totals, same numbers the original
                                                # web-ui-deployment sees -- one shared orders-service
```

**Apply the policy** — deny-by-default, nobody outside `orders-platform` permitted yet:

```sh
curl -X POST http://localhost:8080/networkpolicies -H 'Content-Type: application/json' \
  -d '{"name":"orders-service-deny-cross-tenant","tenantId":"orders-platform",
       "deploymentNames":["orders-service-deployment"],"allowedCallerTenantIds":[]}'
```

**Confirm the block**: the second tenant's own instance now shows `OrderCatalog` as unavailable —
`"ordered":-1` for both SKUs in the JSON response (`WebUiService#inventoryJson`'s own
graceful-degradation rendering for a missing collaborator, fed by `WebUiHooks#lookupQuietly`
catching the rejection), while `"stock"` (from `InventoryLevels`, untenanted, unaffected by this
policy) keeps reporting real numbers. The *original* `web-ui-deployment` (tenant `orders-platform`
itself) keeps working exactly as before — same-tenant traffic is always permitted, policy or no
policy:

```sh
curl -s http://localhost:8092/api/inventory   # OrderCatalog now unavailable
curl -s http://localhost:8090/api/inventory   # unaffected -- same tenant as orders-service itself
```

Also expect `inventory-service`'s own log (its 20s reconciliation line) and `orders-report-job`'s
own report to start showing `OrderCatalog` as unavailable too, gracefully, for as long as this
policy stays applied — **this is expected, not a bug**: neither module is tenant-scoped (see
"Placing orders requires a real secret" above for why), and an untenanted caller is never permitted
once *any* `NetworkPolicySpec` exists for a tenant, regardless of `deploymentNames` scoping. This
is itself worth knowing about the platform: a deployment-scoped policy still walls off every
untenanted caller, not just the specific other tenant you're testing against.

**Now permit the second tenant explicitly**, and watch the same call start working without
redeploying anything:

```sh
curl -X POST http://localhost:8080/networkpolicies -H 'Content-Type: application/json' \
  -d '{"name":"orders-service-deny-cross-tenant","tenantId":"orders-platform",
       "deploymentNames":["orders-service-deployment"],
       "allowedCallerTenantIds":["orders-platform-2"]}'

curl -s http://localhost:8092/api/inventory   # OrderCatalog reachable again
```

`inventory-service`/`orders-report-job` stay blocked even after this — they're still untenanted,
and adding one specific tenant to the allow list doesn't change that. Removing the policy entirely
(`curl -X DELETE http://localhost:8080/networkpolicies/orders-service-deny-cross-tenant`) is what
restores them.

## Placing orders requires a real secret

`web-ui` is the one module in this app that's tenant-scoped and reads delivered config at all --
`GET /api/inventory` stays open (no tenant needed for a read that never touches `ctx.config`), but
`POST /api/orders` requires a real secret, delivered by Fafnir, not a hardcoded check:

```sh
gimle set tenant orders-platform --max-memory-bytes 268435456 --max-cpu-millicores 500 --max-instances 5
gimle secret set orders-platform orders.admin-token --value <pick-any-token>
```

Run both **before** applying `web-ui/deployment.yaml` (or redeploying it): its own `tenantId:
orders-platform` is submitted with the manifest, and `TenantQuotaPlugin` rejects admission
outright for a `tenantId` nothing has registered yet -- the same "push before apply" ordering
`gimle artifact push` already needs for a coordinate-only manifest. Config/secret delivery is
itself tenant-gated one level deeper than that: `AgentMain#deliverConfig` returns immediately, no
Fafnir call at all, for an *untenanted* instance -- which is exactly why `orders-service`/
`inventory-service`/`orders-report-job` (none of which reads `ctx.config`) stay untenanted on
purpose, and why `web-ui`, the one module that does, is the one that needs a tenant.

Once both commands above have run, paste the same token into the page's own "Admin token" field
(or send it as `X-Admin-Token`):

```sh
curl -X POST http://localhost:8090/api/orders -H 'Content-Type: application/json' \
  -H 'X-Admin-Token: <the same token>' -d '{"sku":"widget","quantity":2}'
```

A missing/wrong header answers `401`; a cluster where the secret was never set (or `web-ui` was
deployed without a `tenantId` at all) fails closed with `503` rather than silently letting every
order through.

## Surviving redeploy with a real database

Every module in this app keeps its own state in memory only — lost on redeploy, restart, or a
self-healing respawn — except `orders-service`. Its `OrderBook` is backed by a real Postgres table
through a real HikariCP connection pool, built once in `OrdersServiceHooks#onStart` from delivered
config and closed in `onStop`: the point is watching Gimlé's own module lifecycle manage a live
external connection correctly across repeated redeploys, not just an in-JVM data structure.

Bring up a local Postgres (its own tiny compose file, independent of anything under
`gimle-holmgang/`):

```sh
docker compose -f gimle-examples/orders-platform/docker-compose.yml up -d
```

`orders-service` now needs a `tenantId` too (see `deployment.yaml`), purely so config/secret
delivery reaches it at all — the same reason `web-ui` needed one. Two of its three connection
settings are plain, non-secret config (sensible defaults apply if you skip them: `localhost:5432`,
user `orders`); the password is a real secret, with no default, and startup fails loudly without
it:

```sh
gimle set tenant orders-platform --max-memory-bytes 268435456 --max-cpu-millicores 500 --max-instances 5   # skip if web-ui already created it
gimle set config orders-platform orders.db-url jdbc:postgresql://localhost:5432/orders   # optional, this is the default
gimle set config orders-platform orders.db-user orders                                    # optional, this is the default
gimle secret set orders-platform orders.db-password --value orders-demo-password          # matches docker-compose.yml's own POSTGRES_PASSWORD
```

Run these before applying (or redeploying) `orders-service/deployment.yaml`, the same ordering
`web-ui`'s own secret needs.

**The redeploy walkthrough**: place an order, note the running total, redeploy, check the total
again.

```sh
# 1. Note the current total (via web-ui's GET /api/inventory, or psql -h localhost -U orders orders
#    -c "SELECT sku, SUM(quantity) FROM orders GROUP BY sku;").
# 2. Redeploy orders-service (bump the version in both gimle-module.yaml and deployment.yaml,
#    rebuild, gimle:deploy again -- or just delete-and-reapply the same version to force a fresh
#    instance).
# 3. Check the total again: it only ever goes UP (this redeploy's own seed orders added on top of
#    whatever was already there), never resets to just the 2 fresh seed orders -- proof the table
#    survived the old instance's own JVM exiting, not just that a new one started cleanly.
# 4. Optional: watch connections don't pile up across repeated redeploys --
#    psql -h localhost -U orders orders -c \
#      "SELECT count(*) FROM pg_stat_activity WHERE datname = 'orders';"
#    should stay at or near HikariCP's own pool ceiling (4, see OrdersServiceHooks) regardless of
#    how many redeploys you've done, not grow with each one -- a growing count would mean onStop
#    isn't actually closing the old instance's pool before the new one opens its own.
```

## Watching AutoscaleReconciler scale this for real

`orders-service-deployment` carries a real `autoscale` policy (see its own `deployment.yaml`):
`targetCpuUtilizationPercent` is set deliberately unreachable (200%), so
`targetRequestRatePerSecond: 5.0` is the signal that actually decides replica count —
`AutoscaleReconciler` reads orders-service's own real, worker-reported request rate, not a
synthetic stand-in for it.

Driving that rate needs a real client hitting a real fabric call, and nothing outside a hosted
module can call `OrderCatalog` directly — the same problem `gimle-examples/greeter-load-generator`
solves for greeter-provider. `orders-load-generator` is the identical bridge, retargeted: every
`GET /call` it receives becomes one real `lookupService(OrderCatalog.class)` +
`totalUnitsOrdered("widget")` call, so an ordinary HTTP load tool controls orders-service's own
request rate.

```sh
gimle set config orders-platform orders.load-port 8091   # any free local port
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/orders-platform/orders-load-generator/deployment.yaml
```

Then drive it with whatever HTTP load tool you have — a plain bash loop works fine for this scale:

```sh
for i in $(seq 1 300); do curl -s http://localhost:8091/call > /dev/null & done; wait
```

or, for a real controlled rate, [`hey`](https://github.com/rakyll/hey) or
[`wrk`](https://github.com/wg/wrk) (both open source, either one is a single static binary):

```sh
hey -z 60s -q 8 -c 4 http://localhost:8091/call
```

Watch `gimle get deployments` (or the console's Deployments screen): `orders-service-deployment`
should climb from 1 replica to 2 once its own observed request rate clears 5/s, and back down to 1
a cooldown period after the load stops — the same behavior `AutoscaleIT`'s
`request-rate load scales the provider up` scenario proves for greeter-provider, now watchable by
hand against a real business-logic module instead of a synthetic one.

## Business metrics from structured logs

The obvious way to expose a business counter like "orders placed" would be a Micrometer meter --
except a hosted module has nothing to register one *into* that the platform actually ships
anywhere: `ModuleContext` (`gimle-module`) has no metrics accessor at all today (no
`meterRegistry()`, nothing) -- the platform's own per-module metrics (`WorkerMetrics`, what
`gimle-agent` ships to Muninn) are wired internally by `WorkerMain`, never exposed to hosted-module
code. This is the same deferred `gimle-api` gap `ModuleContext`'s own javadoc already documents,
not something this app can work around from the outside: a Micrometer counter registered into some
registry a module builds for itself would just be an isolated, un-shipped instance nobody ever
scrapes.

What already works, fully, today: every log line a hosted module emits is real, structured,
per-instance `APPLICATION`-category output, shipped through the exact same path `gimle-examples`'
own greeter modules prove out -- captured by the worker, relayed to this node's agent, and either
tailed live or, once the instance/node is gone, served back out of Muninn's own day-bucketed store
via the control plane's `/logs/*` fallback. No platform gap here at all, so this is where a
"business metric" in this app actually lives:

- **`orders placed`** -- `OrderBook#placeOrder` logs one line per placement (seed orders at
  startup, a `POST /api/orders` from either `web-ui` instance, or a call relayed through
  `orders-load-generator`), always the same shape: `order placed: sku=... quantity=... orderId=...`.
- **`inventory reconciled`** -- `InventoryServiceHooks`' existing 20s reconciliation loop already
  logs one line per tracked SKU per cycle: `reconcile widget: stock=... ordered=... remaining=...`.

**Watch them live**, the same way the "Deploying" section above already points at:

```sh
gimle logs instance/orders-service-deployment/0 --follow
gimle logs instance/inventory-service-deployment/0 --follow
```

(`--category` defaults to `APPLICATION` for an `instance/...` target, which is exactly where these
land -- no flag needed.)

**Turn the stream into a count** -- the manual-validation equivalent of reading a Micrometer
counter, since nothing here exposes one directly:

```sh
# Total orders placed since this instance started (resets on redeploy/restart -- the log, unlike
# the Postgres table itself, is per-instance, not cumulative across an instance's own lifetime).
gimle logs instance/orders-service-deployment/0 | grep -c "order placed"

# Units of widget ordered, summed straight from the log lines rather than a second Postgres query:
gimle logs instance/orders-service-deployment/0 \
  | grep "order placed: sku=widget" \
  | grep -oE "quantity=[0-9]+" | cut -d= -f2 \
  | awk '{s+=$1} END {print s}'
```

**After the instance (or its whole node) is gone**: the control plane's own `/logs/*` proxy falls
back to Muninn transparently -- the exact same `gimle logs`/console Log Explorer commands above
keep working with no change, reading from Muninn's day-bucketed store instead of the live worker.
`gimle-smoke-tests`' `ObservabilityIT` proves this exact fallback path for a different module's own
log line; nothing here is a new capability, just this app finally putting a real business event
through it.

## What was, and wasn't, verified building this

This sandbox has no JDK 25 (the platform's own required release) and no running Gimlé cluster, and
this app is deliberately excluded from CI along with everything else in this directory — so nothing
here was ever going to be verified by an automated build. What *was* verified, as thoroughly as
possible without either:

- The full JPMS-module-plus-shaded-Spring recipe (a real `mvn package`, JDK 21 as a mechanics
  stand-in for 25) — proven to actually compile, shade, and run standalone via `java --module-path`.
- Every hooks/probe class, reflectively instantiated through its own real `ModuleLayer` (built with
  the actual `ModuleLayerFactory`/`PlatformLayer` this platform's own worker uses, not a
  simplification), against a real `SimpleModuleContext`/`SimpleServiceRegistry`: Spring contexts
  boot, `OrderBook`'s constructor injection runs for real, demo orders get seeded, `registerService`
  succeeds, and `OrdersReportJobHooks.run` returns `SUCCEEDED` even when its lookups come back
  empty — the graceful-degradation path this app was designed around.

**The Postgres-backed `OrderBook` (added later, same constraints)**: this sandbox has no Docker
daemon either, on top of no JDK 25 and no running cluster, so the database path could not be
exercised at all, only built. What *was* verified: a real `mvn package` produces a correctly named
`com.example.orders` module (`jar --describe-module` — `requires java.sql`, `java.management`,
`java.naming` all present and resolvable, nothing stale left over from before the JDBC/HikariCP
addition), and `META-INF/services/java.sql.Driver` survives maven-shade-plugin's merge intact
(confirmed by unzipping the shaded jar directly) — the one new failure mode this change could have
introduced silently, since `java.sql.DriverManager` finding no registered driver fails at
`getConnection()` time with a message that doesn't obviously point back at a shading problem. What
was **not** verified, and needs a real Docker+cluster environment to close: that
`OrderBook`/`OrdersServiceHooks` actually connect, that the redeploy walkthrough above really shows
a growing total and a bounded `pg_stat_activity` count rather than a leak, and that startup really
does fail loudly (not silently) when `orders.db-password` is never set.

**`orders-load-generator` and the autoscale policy (added later, same constraints)**: verified the
same mechanical way — `mvn package` compiles and produces a correctly named `com.example.ordersload`
module (`jar --describe-module`), no shading needed since it has no third-party dependency at all
(confirmed: no `original-orders-load-generator.jar` byproduct, unlike its three siblings). What was
**not** verified, and needs a real cluster: that `orders-service-deployment`'s `autoscale` block
parses and is honored by `AutoscaleReconciler` exactly the way `AutoscaleIT`'s own greeter-provider
scenario proves, and that a driven `/call` rate against `orders-load-generator` actually shows up as
orders-service's own reported `requestRatePerSecond` and triggers a real scale-up/scale-down.

**The second-tenant `NetworkPolicySpec` walkthrough (added later, same constraints)**: this is the
one addition in this file that changes *existing* behavior rather than only adding new surface —
`InventoryServiceHooks`/`OrdersReportJobHooks`/`WebUiHooks` all gained a
`GimleFabricAuthorizationException` catch alongside their existing `GimleClusterException` one,
without which applying `orders-service/networkpolicy.yaml` would (per `FabricServer`'s own
`checkNetworkPolicyPermitted` and `NetworkPolicyRule#permitsCallerTenant`, both read directly, not
assumed) permanently kill `InventoryServiceHooks`' background reconciler thread and turn every
`orders-report-job` run into a hard `FAILED` rather than a graceful "unavailable" report — a real
regression this sandbox could reason about from the source alone (an uncaught `RuntimeException`
subtype escaping a `while` loop is not in question), but never actually trigger, since doing so
needs a real cluster with a real second tenant and a real applied policy to reproduce the original
crash and then confirm the fix. `mvn package` only confirms the three modules still compile with
the added import and catch clause.

**The `OrderBook#placeOrder` log line (added later, same constraints)**: this one needed no new
platform capability at all -- `APPLICATION`-category log shipping is already real, tested surface
(`gimle-smoke-tests`' `GreeterClusterTopologyIT`/`ObservabilityIT`), so there's nothing here that
`mvn package` compiling cleanly doesn't already cover mechanically. What's unverified is purely
this app's own new content: that the log line actually renders with real `sku`/`quantity`/`orderId`
values in place of the `{}` placeholders (the SLF4J call itself compiles regardless of whether the
format string and argument count actually line up correctly across a refactor -- they were
re-checked by hand here, not by a running logger), and that `gimle logs
instance/orders-service-deployment/0` really surfaces it the way the walkthrough above describes.

One thing that verification setup could *not* exercise correctly, and by construction never could:
`SimpleServiceRegistry` matches services by `Class` object identity, and since each module
carries its own independently-compiled copy of `OrderCatalog`/`InventoryLevels` (deliberately, see
above), the `Class` objects differ across separate `ModuleLayer`s even with byte-for-byte identical
source — so a same-JVM, bare-`ServiceRegistry` test can never resolve cross-module lookups here, no
matter how correct the calling code is. Real TIER_2-to-TIER_2 traffic never goes through that same
code path at all: it goes through `gimle-fabric`'s own cross-worker dispatch, which resolves by
interface *name*, not `Class` identity, specifically to handle this exact situation — the same real,
already-tested mechanism `gimle-examples/greeter-provider`/`greeter-consumer` prove out end to end
in `gimle-smoke-tests`' `GreeterClusterTopologyIT`. This app's own fabric calls
(`ctx.registerService`/`ctx.lookupService`) are written the identical way greeter's are; whether
they actually resolve across a real multi-worker cluster is exactly the manual validation this app
exists for.
