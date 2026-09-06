package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@link GimleTracing#startServerSpan} -- what gives a request-serving process spans of its own
 * instead of an installed exporter with nothing to export. Holds {@code GlobalOpenTelemetry}'s
 * static state exclusively, the same way {@code GimleTracingInstallTest} does and for the same
 * reason.
 */
@ResourceLock(Resources.GLOBAL)
class GimleTracingServerSpanTest {

  @BeforeEach
  void resetTracingBefore() {
    GimleTracing.resetForTesting();
  }

  @AfterEach
  void resetTracingAfter() {
    GimleTracing.resetForTesting();
  }

  @Test
  @Timeout(20)
  void a_started_server_span_reaches_the_installed_exporter_with_its_endpoint_and_status() {
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    GimleTracing.install(exporter);

    try (ServerSpan span =
        GimleTracing.startServerSpan("com.gimle.test", "GET /deployments", "deployments", "GET")) {
      span.recordStatus(200);
    }
    GimleTracing.flush();

    List<SpanData> captured = exporter.captured();
    assertEquals(1, captured.size(), captured.toString());
    SpanData span = captured.get(0);
    assertEquals("GET /deployments", span.getName());
    assertEquals("deployments", span.getAttributes().get(AttributeKey.stringKey("gimle.endpoint")));
    assertEquals("GET", span.getAttributes().get(AttributeKey.stringKey("http.request.method")));
    assertEquals(200L, span.getAttributes().get(AttributeKey.longKey("http.response.status_code")));
    assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
  }

  @Test
  @Timeout(20)
  void a_server_error_marks_the_span_as_failed() {
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    GimleTracing.install(exporter);

    try (ServerSpan span =
        GimleTracing.startServerSpan("com.gimle.test", "GET /deployments", "deployments", "GET")) {
      span.recordStatus(500);
    }
    GimleTracing.flush();

    assertEquals(StatusCode.ERROR, exporter.captured().get(0).getStatus().getStatusCode());
  }

  /**
   * A request served before the process finishes installing its own tracing must not cost it every
   * later span: reading the OpenTelemetry global installs a no-op one as a side effect, and the
   * real installation afterwards would then be refused outright.
   */
  @Test
  @Timeout(20)
  void a_span_started_before_installation_is_inert_and_does_not_block_a_later_installation() {
    ServerSpan span =
        GimleTracing.startServerSpan("com.gimle.test", "GET /health", "health", "GET");
    span.recordStatus(200);
    span.close();
    assertSame(ServerSpan.NOOP, span);

    CapturingSpanExporter exporter = new CapturingSpanExporter();
    GimleTracing.install(exporter);
    try (ServerSpan installed =
        GimleTracing.startServerSpan("com.gimle.test", "GET /health", "health", "GET")) {
      installed.recordStatus(200);
    }
    GimleTracing.flush();

    assertEquals(1, exporter.captured().size(), "the later installation must have taken effect");
  }
}
