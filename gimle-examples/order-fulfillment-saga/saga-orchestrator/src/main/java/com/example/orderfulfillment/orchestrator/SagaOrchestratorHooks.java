package com.example.orderfulfillment.orchestrator;

import com.example.orderfulfillment.ChargeResult;
import com.example.orderfulfillment.InventoryReservationService;
import com.example.orderfulfillment.PaymentService;
import com.example.orderfulfillment.ReservationResult;
import com.example.orderfulfillment.ShipmentResult;
import com.example.orderfulfillment.ShippingService;
import com.gimle.core.exception.GimleClusterException;
import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.JobHooks;
import com.gimle.module.lifecycle.ModuleContext;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The saga driver: runs a batch of synthetic orders through
 * {@code reserve -> charge -> ship}, compensating (release/refund) on a downstream step's
 * business-level failure -- a genuinely new distributed pattern for this repo, compensating
 * transactions, not just a chain of calls. Every order is processed independently on its own
 * virtual thread, bounded by a {@link Semaphore} to {@code saga.maxInFlight} concurrent orders at
 * once and tolerating a bounded fraction of {@link OrderOutcome#INFRA_FAILED} orders before
 * failing the whole run -- the same bounded-concurrency/partial-failure-tolerance posture
 * gimle-examples/mapreduce-wordcount's own coordinator establishes, applied here to a saga instead
 * of a map-reduce fan-out.
 *
 * <p>Every fabric call in this class goes through {@link #invokeWithRetry}: a fresh {@code
 * lookupService} on every attempt (the same "re-resolve every time" posture every other example in
 * this repo establishes), retrying only an actual infrastructure failure (no member currently
 * exports the service, or the resolved endpoint threw) -- {@code
 * InventoryReservationService}/{@code PaymentService}/{@code ShippingService} never throw for a
 * business decision (insufficient stock, a declined card, a carrier rejection), so a
 * {@link RuntimeException} from any of them always means genuine infra trouble worth retrying.
 */
public final class SagaOrchestratorHooks implements JobHooks {

  private static final Logger log = LoggerFactory.getLogger(SagaOrchestratorHooks.class);

  private static final int DEFAULT_ORDER_COUNT = 200;
  private static final int DEFAULT_MAX_IN_FLIGHT = 16;
  private static final double DEFAULT_MAX_FAILURE_RATIO = 0.05;

  private static final int MAX_LOOKUP_ATTEMPTS = 5;
  private static final Duration LOOKUP_RETRY_DELAY = Duration.ofSeconds(2);

  @Override
  public CompletionStatus run(ModuleContext ctx) {
    int orderCount = intConfig(ctx, "saga.orderCount", DEFAULT_ORDER_COUNT);
    int maxInFlight = intConfig(ctx, "saga.maxInFlight", DEFAULT_MAX_IN_FLIGHT);
    double maxFailureRatio = doubleConfig(ctx, "saga.maxFailureRatio", DEFAULT_MAX_FAILURE_RATIO);

    List<SyntheticOrder> orders = SyntheticOrders.generate(orderCount);
    log.info(
        "saga-orchestrator processing {} orders, at most {} in flight at once", orderCount,
        maxInFlight);

    Instant start = Instant.now();
    Map<OrderOutcome, Integer> tally = new EnumMap<>(OrderOutcome.class);
    for (OrderOutcome outcome : OrderOutcome.values()) {
      tally.put(outcome, 0);
    }
    Semaphore inFlight = new Semaphore(maxInFlight);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<OrderOutcome>> futures = new java.util.ArrayList<>(orders.size());
      for (SyntheticOrder order : orders) {
        futures.add(executor.submit(() -> processOrderBounded(ctx, order, inFlight)));
      }
      for (Future<OrderOutcome> future : futures) {
        OrderOutcome outcome = awaitFuture(future);
        tally.merge(outcome, 1, Integer::sum);
      }
    }

    Duration elapsed = Duration.between(start, Instant.now());
    int infraFailed = tally.get(OrderOutcome.INFRA_FAILED);
    double failureRatio = infraFailed / (double) orderCount;

    log.info(
        "saga-orchestrator processed {} orders in {}ms: {} fulfilled, {} compensated, {}"
            + " rejected (insufficient stock), {} infra-failed",
        orderCount,
        elapsed.toMillis(),
        tally.get(OrderOutcome.FULFILLED),
        tally.get(OrderOutcome.COMPENSATED),
        tally.get(OrderOutcome.REJECTED),
        infraFailed);

    if (failureRatio > maxFailureRatio) {
      log.error(
          "saga-orchestrator failing the run: {} of {} orders infra-failed ({}%), over the"
              + " configured tolerance of {}%",
          infraFailed, orderCount, pct(failureRatio), pct(maxFailureRatio));
      return CompletionStatus.FAILED;
    }
    if (infraFailed > 0) {
      log.warn(
          "saga-orchestrator succeeded with a degraded result: {} of {} orders infra-failed"
              + " ({}%), within the configured tolerance of {}%",
          infraFailed, orderCount, pct(failureRatio), pct(maxFailureRatio));
    }
    return CompletionStatus.SUCCEEDED;
  }

  private OrderOutcome processOrderBounded(ModuleContext ctx, SyntheticOrder order, Semaphore inFlight) {
    try {
      inFlight.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return OrderOutcome.INFRA_FAILED;
    }
    try {
      return processOrder(ctx, order);
    } finally {
      inFlight.release();
    }
  }

  private OrderOutcome processOrder(ModuleContext ctx, SyntheticOrder order) {
    Optional<ReservationResult> reservation =
        invokeWithRetry(
            ctx,
            InventoryReservationService.class,
            s -> s.reserve(order.orderId(), order.sku(), order.quantity()),
            "reserve(" + order.orderId() + ")");
    if (reservation.isEmpty()) {
      return OrderOutcome.INFRA_FAILED;
    }
    ReservationResult reservationResult = reservation.get();
    if (!reservationResult.success()) {
      log.info("order {} rejected: {}", order.orderId(), reservationResult.reason());
      return OrderOutcome.REJECTED;
    }

    Optional<ChargeResult> charge =
        invokeWithRetry(
            ctx, PaymentService.class, s -> s.charge(order.orderId(), order.amount()),
            "charge(" + order.orderId() + ")");
    if (charge.isEmpty()) {
      release(ctx, order, reservationResult.reservationId());
      return OrderOutcome.INFRA_FAILED;
    }
    ChargeResult chargeResult = charge.get();
    if (!chargeResult.success()) {
      log.info(
          "order {} compensated: {} (releasing reservation {})",
          order.orderId(),
          chargeResult.reason(),
          reservationResult.reservationId());
      release(ctx, order, reservationResult.reservationId());
      return OrderOutcome.COMPENSATED;
    }

    Optional<ShipmentResult> shipment =
        invokeWithRetry(
            ctx, ShippingService.class, s -> s.ship(order.orderId()), "ship(" + order.orderId() + ")");
    if (shipment.isEmpty()) {
      refund(ctx, order, chargeResult.chargeId());
      release(ctx, order, reservationResult.reservationId());
      return OrderOutcome.INFRA_FAILED;
    }
    ShipmentResult shipmentResult = shipment.get();
    if (!shipmentResult.success()) {
      log.info(
          "order {} compensated: {} (refunding charge {}, releasing reservation {})",
          order.orderId(),
          shipmentResult.reason(),
          chargeResult.chargeId(),
          reservationResult.reservationId());
      refund(ctx, order, chargeResult.chargeId());
      release(ctx, order, reservationResult.reservationId());
      return OrderOutcome.COMPENSATED;
    }

    return OrderOutcome.FULFILLED;
  }

  private void release(ModuleContext ctx, SyntheticOrder order, String reservationId) {
    // The lambda returns a non-null sentinel (Boolean.TRUE), not the void call's own result:
    // invokeWithRetry uses Optional.ofNullable on whatever the lambda returns to tell "the call
    // actually succeeded" apart from "every attempt failed" -- a literal null here would make a
    // genuine success indistinguishable from exhausted retries.
    Optional<Boolean> result =
        invokeWithRetry(
            ctx,
            InventoryReservationService.class,
            s -> {
              s.release(reservationId);
              return Boolean.TRUE;
            },
            "release(" + order.orderId() + ")");
    if (result.isEmpty()) {
      log.error(
          "could not release reservation {} for order {} after every retry -- it may be stranded",
          reservationId,
          order.orderId());
    }
  }

  private void refund(ModuleContext ctx, SyntheticOrder order, String chargeId) {
    // Same non-null-sentinel reasoning as release(...) above.
    Optional<Boolean> result =
        invokeWithRetry(
            ctx,
            PaymentService.class,
            s -> {
              s.refund(chargeId);
              return Boolean.TRUE;
            },
            "refund(" + order.orderId() + ")");
    if (result.isEmpty()) {
      log.error(
          "could not refund charge {} for order {} after every retry -- it may be stranded",
          chargeId,
          order.orderId());
    }
  }

  /**
   * Retries a fresh {@code lookupService} + {@code call} up to {@link #MAX_LOOKUP_ATTEMPTS} times,
   * {@link #LOOKUP_RETRY_DELAY} apart -- the same bounded-retry tolerance
   * gimle-examples/orders-platform's own {@code OrdersReportJobHooks} and
   * gimle-examples/mapreduce-wordcount's own {@code WordCountJobHooks} already establish. An empty
   * {@link Optional} here means every attempt either found no exporting member or had its call
   * throw -- a genuine infrastructure failure, since none of this app's own service interfaces
   * ever throw for a business decision.
   */
  private <S, R> Optional<R> invokeWithRetry(
      ModuleContext ctx, Class<S> serviceClass, Function<S, R> call, String opName) {
    for (int attempt = 1; attempt <= MAX_LOOKUP_ATTEMPTS; attempt++) {
      Optional<S> service;
      try {
        service = ctx.lookupService(serviceClass);
      } catch (GimleClusterException e) {
        service = Optional.empty();
      }
      if (service.isPresent()) {
        try {
          return Optional.ofNullable(call.apply(service.get()));
        } catch (RuntimeException e) {
          log.warn("{} failed on attempt {}/{}: {}", opName, attempt, MAX_LOOKUP_ATTEMPTS,
              e.getMessage());
        }
      } else {
        log.warn("{}: no member available on attempt {}/{}", opName, attempt, MAX_LOOKUP_ATTEMPTS);
      }
      if (attempt < MAX_LOOKUP_ATTEMPTS) {
        sleep(LOOKUP_RETRY_DELAY);
      }
    }
    return Optional.empty();
  }

  private static OrderOutcome awaitFuture(Future<OrderOutcome> future) {
    try {
      return future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      log.warn("an order's saga task threw unexpectedly: {}", cause.getMessage());
      return OrderOutcome.INFRA_FAILED;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return OrderOutcome.INFRA_FAILED;
    }
  }

  private static int intConfig(ModuleContext ctx, String key, int defaultValue) {
    return ctx.config(key).map(String::strip).map(Integer::parseInt).orElse(defaultValue);
  }

  private static double doubleConfig(ModuleContext ctx, String key, double defaultValue) {
    return ctx.config(key).map(String::strip).map(Double::parseDouble).orElse(defaultValue);
  }

  private static String pct(double ratio) {
    return String.format("%.1f", ratio * 100);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
