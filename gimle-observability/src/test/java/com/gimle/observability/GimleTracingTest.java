package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;

class GimleTracingTest {

  @Test
  void install_is_idempotent_and_yields_a_working_tracer() {
    GimleTracing.installDefault();
    GimleTracing.installDefault(); // must not throw or double-register

    Tracer tracer = GlobalOpenTelemetry.getTracer("gimle-observability-test");
    Span span = tracer.spanBuilder("test-span").startSpan();
    try {
      assertNotNull(span);
      assertTrue(span.getSpanContext().isValid());
    } finally {
      span.end();
    }
  }
}
