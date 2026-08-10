package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentMetricsTest {

  @Test
  void record_tick_increments_count_and_records_latency() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AgentMetrics metrics = new AgentMetrics(registry);

    metrics.recordTick(Duration.ofMillis(12), false);
    metrics.recordTick(Duration.ofMillis(8), false);

    assertEquals(2.0, metrics.tickCount());
    assertEquals(2, registry.find("gimle.agent.tick.latency").timer().count());
  }

  @Test
  void a_failed_tick_also_increments_the_error_counter() {
    AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());

    metrics.recordTick(Duration.ofMillis(5), true);

    assertEquals(1.0, metrics.errorCount());
  }

  @Test
  void error_counter_is_not_created_when_no_tick_ever_failed() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AgentMetrics metrics = new AgentMetrics(registry);

    metrics.recordTick(Duration.ofMillis(5), false);

    Search search = registry.find("gimle.agent.tick.errors");
    assertNull(search.counter());
  }

  @Test
  void tick_and_error_count_are_zero_before_any_tick_is_recorded() {
    AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());

    assertEquals(0.0, metrics.tickCount());
    assertEquals(0.0, metrics.errorCount());
  }
}
