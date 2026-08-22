# Order Fulfillment Saga

A real, deployable e-commerce checkout saga hosted as genuine Gimlé modules. Like the other
standalone `gimle-examples/*` apps, this is **not platform code** — it's a hand-built sample
proving out a genuinely new distributed pattern for this repo: **compensating transactions**. It
is deliberately **not** listed in the repo root `pom.xml`'s modules list — see that file's own
comment, and this directory's own `pom.xml`, for why. Never add it there.

## Why this app exists

Every other multi-service example in this repo (`orders-platform`, `fraud-detection`) only ever
reads or does a single, independent mutation — nothing chains multiple services' worth of state
changes together with rollback on failure. A real order-fulfillment flow is the textbook case for
exactly that: reserve stock, charge a card, ship the package — and if charging fails, release the
stock back; if shipping fails, refund the charge *and* release the stock. This app is the saga
pattern, for real, over the real fabric.

## What it is

Four real Gimlé modules (all `TIER_2` except the orchestrator, all bundling their own literal
copies of the interfaces/records they share — the same "structural contract, not a shared jar"
convention `greeter-provider`/`greeter-consumer` already establish):

- **`inventory-reservation`** — `reserve`/`release`. A single in-memory stock ledger
  (deliberately **`replicas: 1`** — see its own hooks javadoc for why more than one would
  double-sell) seeded with one plentiful sku (`widget`) and one deliberately scarce one
  (`gadget`, 200 units) so a real batch genuinely exhausts it.
- **`payment-service`** — `charge`/`refund`. A configurable `payment.failureRate` (default
  `0.15`) makes a real fraction of charges fail on purpose.
- **`shipping-service`** — `ship`. A configurable `shipping.failureRate` (default `0.1`) makes a
  real fraction of shipments fail on purpose — the trigger for the *full* refund-and-release
  compensation chain.
- **`saga-orchestrator`** — a `JobHooks` batch driver (like `orders-platform`'s own
  `orders-report-job` and `mapreduce-wordcount`'s own coordinator): generates a deterministic
  batch of synthetic orders, runs each independently through
  `reserve → charge → ship` on its own virtual thread (bounded concurrency via
  `saga.maxInFlight`, the same posture `mapreduce-wordcount`'s coordinator establishes),
  compensating on any downstream business failure, and tolerating a bounded fraction of genuine
  infrastructure failures (`saga.maxFailureRatio`) rather than failing the whole run over a
  handful of stragglers.

## The four order outcomes

| Outcome | What happened | Compensation |
| --- | --- | --- |
| `FULFILLED` | Reserve, charge, and ship all succeeded. | None needed. |
| `REJECTED` | Reservation itself failed (insufficient stock). | None — nothing was ever reserved. |
| `COMPENSATED` | Charge or shipping failed as a legitimate business decision *after* a real reservation (and possibly a real charge) existed. | Release the reservation; refund the charge too if shipping is what failed. |
| `INFRA_FAILED` | A step's own fabric call never got a response after every retry — not a business decision. | Best-effort release/refund is still attempted; counted against `saga.maxFailureRatio`. |

`SagaOrchestratorHooks#invokeWithRetry` is what makes this distinction real, not asserted:
`InventoryReservationService`/`PaymentService`/`ShippingService` never throw for a business
decision (a declined card comes back as `ChargeResult(success=false, ...)`, not an exception), so
a thrown `RuntimeException` from any of them always means genuine infrastructure trouble worth
retrying against a fresh `lookupService` call — the same "re-resolve every time" posture every
other example in this repo already establishes.

## Tenancy is required

`saga-orchestrator`, `payment-service`, and `shipping-service` all read tenant config, sharing one
tenant (`order-fulfillment-saga`) whose config keys are namespaced by prefix so there's no
collision:

```sh
gimle set tenant order-fulfillment-saga --max-memory-bytes 1073741824 --max-cpu-millicores 4000 --max-instances 10
gimle set config order-fulfillment-saga saga.orderCount 200
gimle set config order-fulfillment-saga saga.maxInFlight 16
gimle set config order-fulfillment-saga saga.maxFailureRatio 0.05
gimle set config order-fulfillment-saga payment.failureRate 0.15
gimle set config order-fulfillment-saga shipping.failureRate 0.1
```

`inventory-reservation` never reads `ctx.config`, so it stays untenanted — the same "only the
module that actually needs config gets a tenant" posture `orders-platform` establishes.

## Building

This tree is not part of the root reactor, so build it explicitly, from this directory:

```sh
mvn -f gimle-examples/order-fulfillment-saga/pom.xml package
```

Prerequisite: `com.gimle:gimle-module` and `com.gimle:gimle-core` must already be installed into
your local Maven repository at this tree's own pinned `gimle.platform.version`:

```sh
mvn install -DskipTests   # from the repo root
```

## Deploying

Against a running Gimlé cluster (see `gimle-console/LOCAL_DEV.md`), run the tenant/config block
above first, then:

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/order-fulfillment-saga/inventory-reservation/deployment.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/order-fulfillment-saga/payment-service/deployment.yaml
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/order-fulfillment-saga/shipping-service/deployment.yaml
```

Wait for all three to reach `ACTIVE`, then run the saga once on demand:

```sh
mvn gimle:deploy -Dgimle.deploy.file=gimle-examples/order-fulfillment-saga/saga-orchestrator/job.yaml
```

or deploy `cronjob.yaml` instead for a fresh batch every 5 minutes. Deploy `job.yaml` **or**
`cronjob.yaml`, not both at once — alternate manifests for the same module, not two deployments
meant to coexist.

Watch `saga-orchestrator`'s own log for the final tally:
`saga-orchestrator processed 200 orders in Xms: N fulfilled, N compensated, N rejected
(insufficient stock), N infra-failed` — and `inventory-reservation`'s/`payment-service`'s own logs
for the matching `released reservation ...`/`refunded charge ...` lines proving the compensation
actually happened, not just that the orchestrator claimed it would.

## What was, and wasn't, verified building this

This sandbox has no JDK 25, no network access to fetch one, and no running Gimlé cluster — the
same limitation every other standalone example in this directory documents. Every hooks/probe
class here is stub-compiled against the real `ModuleContext`/`JobHooks`/`ModuleLifecycleHooks`/
`LivenessProbe`/`ReadinessProbe`/`CompletionStatus` signatures to catch type/syntax errors,
following the exact same bounded-retry, re-resolve-per-call, and bounded-concurrency patterns
`orders-platform`'s `orders-report-job` and `mapreduce-wordcount`'s coordinator already establish
end to end.
