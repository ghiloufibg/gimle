package com.gimle.skald;

import com.gimle.skald.directory.ServiceDirectory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Micrometer wiring for Skald's own directory-staleness signal -- the same {@link
 * MeterRegistry}-wrapping shape {@code AndvariMetrics}/{@code FafnirMetrics} already establish in
 * {@code gimle-observability}, kept local to this module instead because Skald has no
 * request-shaped metric to track (no {@code endpoint}/{@code verb} tags, no counters): a directory
 * either has fresh data or it doesn't, so two gauges reading the {@link ServiceDirectory} directly
 * are the whole shape. Defaults to an in-memory {@link SimpleMeterRegistry}, matching every other
 * metrics wrapper in this codebase; {@code SkaldMain} ships it to Muninn when configured, the same
 * optional-endpoint posture every other small process kind here already takes.
 */
public final class SkaldMetrics {

  private final MeterRegistry registry;

  public SkaldMetrics(ServiceDirectory directory) {
    this(new SimpleMeterRegistry(), directory);
  }

  public SkaldMetrics(MeterRegistry registry, ServiceDirectory directory) {
    this.registry = registry;
    Gauge.builder(
            "gimle.skald.directory.staleness.seconds",
            directory,
            d -> d.timeSinceLastSuccess().toMillis() / 1000.0)
        .description(
            "Seconds since the service directory was last refreshed by a successful"
                + " control-plane poll")
        .register(registry);
    Gauge.builder(
            "gimle.skald.directory.consecutive.failures",
            directory,
            d -> (double) d.consecutiveFailures())
        .description("Consecutive failed control-plane polls since the last success")
        .register(registry);
  }

  public MeterRegistry registry() {
    return registry;
  }
}
