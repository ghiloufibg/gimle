package com.example.reporting;

import com.example.inventory.InventoryLevels;
import com.example.orders.OrderCatalog;
import com.gimle.core.exception.GimleClusterException;
import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.JobHooks;
import com.gimle.module.lifecycle.ModuleContext;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The run-to-completion counterpart of the other two modules' {@code ModuleLifecycleHooks}: a
 * Job-kind module's {@link #run} executes exactly once on its own virtual thread after the module
 * reaches ACTIVE (see {@code JobHooksExecutionTest} in gimle-worker for the platform's own proof
 * of that contract), then whatever {@link CompletionStatus} it returns drives the module straight
 * to COMPLETED or FAILED -- there's no {@code onStart}/{@code onStop} split to worry about here.
 *
 * <p>Both fabric lookups are retried a bounded number of times rather than failing on the first
 * miss: as a short-lived job, this module can easily reach ACTIVE before orders-service/
 * inventory-service have registered their own services, especially right after a fresh cluster
 * boot where every module races to start together. A real production job would more likely lean
 * on Gimlé's own placement/scheduling ordering (or an explicit {@code activeDeadlineSeconds}/
 * {@code backoffLimit} on the workload manifest and simply let a too-early run fail and retry) --
 * this hand-rolled retry is the simplest choice for a lightweight demo, not a recommended pattern
 * to copy into something that matters.
 */
public final class OrdersReportJobHooks implements JobHooks {

  private static final Logger log = LoggerFactory.getLogger(OrdersReportJobHooks.class);
  private static final int MAX_LOOKUP_ATTEMPTS = 5;
  private static final Duration LOOKUP_RETRY_DELAY = Duration.ofSeconds(2);

  @Override
  public CompletionStatus run(ModuleContext ctx) {
    try (AnnotationConfigApplicationContext springContext =
        new AnnotationConfigApplicationContext(ReportConfiguration.class)) {
      ReportGenerator reportGenerator = springContext.getBean(ReportGenerator.class);

      Optional<OrderCatalog> orderCatalog = awaitService(() -> ctx.lookupService(OrderCatalog.class));
      Optional<InventoryLevels> inventoryLevels = awaitService(() -> ctx.lookupService(InventoryLevels.class));

      String report = reportGenerator.buildReport(orderCatalog, inventoryLevels);
      log.info("{}", report);
      return CompletionStatus.SUCCEEDED;
    } catch (RuntimeException e) {
      log.error(
          "orders-report-job failed: {}: {}", e.getClass().getName(), e.getMessage(), e);
      return CompletionStatus.FAILED;
    }
  }

  /** Retries {@code lookup} up to {@link #MAX_LOOKUP_ATTEMPTS} times, {@link #LOOKUP_RETRY_DELAY}
   * apart, returning the first present result -- or an empty {@link Optional} if every attempt
   * came back empty, which {@link ReportGenerator} renders as "unavailable" rather than failing
   * the whole job over one missing collaborator.
   *
   * <p>{@code lookup} throws {@link GimleClusterException} rather than returning empty when
   * literally nobody in the cluster has ever exported the interface yet (see
   * {@code FabricServiceRegistry#lookup}) -- exactly the "raced a fresh cluster boot" case this
   * retry exists for, so it's treated the same as an empty result rather than left to fail the
   * whole attempt on its very first unlucky poll. */
  private static <T> Optional<T> awaitService(Supplier<Optional<T>> lookup) {
    for (int attempt = 1; attempt <= MAX_LOOKUP_ATTEMPTS; attempt++) {
      Optional<T> found;
      try {
        found = lookup.get();
      } catch (GimleClusterException e) {
        found = Optional.empty();
      }
      if (found.isPresent()) {
        return found;
      }
      if (attempt < MAX_LOOKUP_ATTEMPTS) {
        sleep(LOOKUP_RETRY_DELAY);
      }
    }
    return Optional.empty();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
