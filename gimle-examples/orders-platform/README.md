# Orders Platform

A lightweight Spring microservices app hosted as real Gimlé modules. This is **not platform
code** — it's a hand-built cowboy application for manually validating a running Gimlé cluster end
to end, the first real-world (not deliberately-inert, not a synthetic fixture) application ever
deployed on Gimlé. It is deliberately **not** listed in the repo root `pom.xml`'s modules list —
see that file's own comment, and this directory's own `pom.xml`, for why. Never add it there.

## What it is

Three real Java modules, each a genuine Spring `AnnotationConfigApplicationContext` doing real
dependency injection, packaged as real Gimlé module jars and talking to each other over Gimlé's
own fabric `ServiceRegistry` — not a second HTTP layer bolted on top of the platform's own service
mesh:

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
```

Then watch it happen: the console's Logs screen, or `gimle-cli`'s own `logs --follow`. You should
see orders-service seed its two demo orders, inventory-service start logging reconciliation lines
once orders-service is up, and (every 5 minutes, or immediately if you deploy `job.yaml` instead
for an on-demand run) orders-report-job log a consolidated report of both.

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
