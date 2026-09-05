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
`inventory-service.jar`, `orders-report-job.jar`) with Spring shaded directly in — see the parent
`pom.xml`'s own comments for exactly how and why (JPMS module-info handling around
maven-shade-plugin is the one genuinely tricky part of this whole app; that pom explains it in
detail rather than leaving it as unexplained magic).

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
   declare an `Ingress` for its tenant:

   ```yaml
   kind: Ingress
   name: orders-platform
   tenantId: gimle-system
   routes:
     - {kind: SERVICE, path: /api/inventory, serviceName: web-ui}
     - {kind: SERVICE, path: /api/orders, serviceName: web-ui}
     - {kind: SERVICE, path: /, serviceName: web-ui}
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
`web-ui-deployment` — apply it the same way as `service.yaml`, via `POST /networkpolicies`, or via
`gimle set networkpolicy web-ui-deny-cross-tenant --tenant orders-platform --deployment
web-ui-deployment --deny-all-callers` (see `networkpolicy.yaml`'s own header comment for both
forms side by side; the CLI requires the explicit `--deny-all-callers` flag to mean "deny every
cross-tenant caller" — just omitting `--allowed-caller-tenant` means "no restriction at all" in
that direction, not deny-all). Worth being precise about what it actually restricts in this
single-tenant sandbox, rather than overstating it:

- **It has no effect on the gateway route above.** `gimle-gateway`'s own `SERVICE` route has no
  tenant-policy check of its own — external reachability is an operator's own opt-in publishing
  decision, a different concern from inter-tenant fabric traffic, which is what `NetworkPolicySpec`
  actually governs.
- **It has no effect via `FabricServer` either**, because `web-ui` doesn't export a fabric service
  of its own (it only *consumes* `OrderCatalog`/`InventoryLevels`) — `FabricServer.checkNetworkPolicyPermitted`
  only ever gates inbound calls to a module that exports one. In this app that's `orders-service`/
  `inventory-service`, and neither is tenant-scoped (deliberately, for the config-delivery reasons
  above), so a policy can't target them without first giving them a tenant — out of scope for this
  file.
- **What it does demonstrate**: if `gimle-bifrost` is enabled on the node hosting `web-ui`
  (`-Dgimle.agent.bifrostEnabled=true`), this policy makes `BifrostProxy` refuse to proxy `web-ui`'s
  Service to any same-node caller — Bifrost relays opaque bytes for whatever protocol a caller
  speaks, so it has no caller identity to check the policy's `allowedCallerTenantIds` against, and
  fails closed rather than risk silently bypassing a restriction the tenant opted into.

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

## What was, and wasn't, verified building this

**Update:** the real end-to-end validation this section originally called out as never having
happened has since happened, in a session with real JDK 25 and a real distribution-artifact
cluster: `mvn -f gimle-examples/orders-platform/pom.xml package` builds cleanly against JDK 25 with
no changes needed, and all four modules (`orders-service` as a Deployment, `inventory-service` as a
StatefulSet, `orders-report-job` as an on-demand Job, `web-ui` as a tenant-scoped Deployment) reach
`ACTIVE`/`HEALTHY` against a real `hilmir`-launched cluster. The cross-module fabric lookup this
section's last paragraph could only reason about in-process (`OrderCatalog`/`InventoryLevels`
resolved from `inventory-service` and `orders-report-job`) resolves correctly against the real
cluster; `web-ui`'s tenant-scoped secret gate (`POST /api/orders` needing the real Fafnir-delivered
`X-Admin-Token`) and its `Service`/`GET /services/{name}/endpoints` resolution both work exactly as
documented above. See the end-user application-deployment QA entry in `QA_FINDINGS.md` for the full
session this ran in, alongside a real, unmodified upstream Spring PetClinic deployed the same way.

Before that, this sandbox had no JDK 25 (the platform's own required release) and no running Gimlé
cluster, and this app is deliberately excluded from CI along with everything else in this
directory — so nothing here was going to be verified by an automated build. What *was* verified at
that point, as thoroughly as possible without either:

- The full JPMS-module-plus-shaded-Spring recipe (a real `mvn package`, JDK 21 as a mechanics
  stand-in for 25) — proven to actually compile, shade, and run standalone via `java --module-path`.
- Every hooks/probe class, reflectively instantiated through its own real `ModuleLayer` (built with
  the actual `ModuleLayerFactory`/`PlatformLayer` this platform's own worker uses, not a
  simplification), against a real `SimpleModuleContext`/`SimpleServiceRegistry`: Spring contexts
  boot, `OrderBook`'s constructor injection runs for real, demo orders get seeded, `registerService`
  succeeds, and `OrdersReportJobHooks.run` returns `SUCCEEDED` even when its lookups come back
  empty — the graceful-degradation path this app was designed around.

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
