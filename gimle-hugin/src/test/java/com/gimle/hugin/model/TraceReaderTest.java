package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reading a worker's shipped spans, including when nothing ships any. */
class TraceReaderTest {

  @Test
  void a_span_is_read_with_the_trace_it_belongs_to_and_the_status_it_ended_in() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                TraceReader.path("node-alpha", "worker-7") + "?limit=200",
                Map.of("lines", List.of(span("t1", "s1", "", "greet", "SERVER", "OK"))));

    SpanRow span = read(reader).spans().getFirst();

    assertEquals("t1", span.traceId());
    assertEquals("greet", span.name());
    assertEquals("SERVER", span.kind());
    assertEquals("OK", span.status());
    assertTrue(span.root(), "a span with no parent begins its trace");
  }

  @Test
  void a_parent_of_all_zeroes_is_how_the_sdk_spells_no_parent_and_reads_as_a_root() {
    // Left as-is, every span would look like a child of something that does not exist.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                TraceReader.path("node-alpha", "worker-7") + "?limit=200",
                Map.of(
                    "lines",
                    List.of(span("t1", "s1", "0000000000000000", "greet", "SERVER", "OK"))));

    assertTrue(read(reader).spans().getFirst().root());
  }

  @Test
  void a_worker_that_ships_nowhere_reports_no_history_rather_than_an_error() {
    // Shipping is optional; blaming the reader for a deployment choice would be wrong.
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.notFound("no muninn endpoint configured"));

    TraceSnapshot snapshot = read(reader);

    assertFalse(snapshot.shipped());
    assertTrue(snapshot.spans().isEmpty());
  }

  @Test
  void a_line_carrying_no_span_identity_is_dropped_rather_than_drawn_as_a_blank_one() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                TraceReader.path("node-alpha", "worker-7") + "?limit=200",
                Map.of(
                    "lines",
                    List.of(
                        Map.of("name", "orphan"), span("t1", "s1", "", "greet", "SERVER", "OK"))));

    assertEquals(1, read(reader).spans().size());
  }

  @Test
  void an_unparseable_timestamp_costs_that_spans_ordering_and_nothing_else() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                TraceReader.path("node-alpha", "worker-7") + "?limit=200",
                Map.of(
                    "lines",
                    List.of(
                        Map.of(
                            "timestamp", "not-a-time",
                            "traceId", "t1",
                            "spanId", "s1",
                            "name", "greet",
                            "kind", "SERVER",
                            "status", "OK"))));

    assertEquals(Instant.EPOCH, read(reader).spans().getFirst().at());
  }

  @Test
  void spans_group_into_the_traces_they_belong_to_with_the_newest_trace_first() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                TraceReader.path("node-alpha", "worker-7") + "?limit=200",
                Map.of(
                    "lines",
                    List.of(
                        span("old", "s1", "", "first-call", "SERVER", "OK", "2026-09-01T14:00:00Z"),
                        span("new", "s2", "", "later-call", "SERVER", "OK", "2026-09-01T14:05:00Z"),
                        span(
                            "new",
                            "s3",
                            "s2",
                            "inner-call",
                            "CLIENT",
                            "ERROR",
                            "2026-09-01T14:05:01Z"))));

    List<TraceSnapshot.Trace> traces = read(reader).traces("");

    assertEquals(
        List.of("later-call", "first-call"),
        traces.stream().map(TraceSnapshot.Trace::label).toList());
    assertEquals(2, traces.getFirst().spans().size());
    assertTrue(traces.getFirst().failed(), "a trace with a failed span is a failed trace");
    assertFalse(traces.getLast().failed());
  }

  @Test
  void a_trace_whose_root_was_never_shipped_is_still_listed_under_its_own_id() {
    // The root may have begun in another process, or been trimmed from this worker's history.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                TraceReader.path("node-alpha", "worker-7") + "?limit=200",
                Map.of(
                    "lines",
                    List.of(span("abcdef0123456789", "s2", "s1", "inner", "CLIENT", "OK"))));

    TraceSnapshot.Trace trace = read(reader).traces("").getFirst();

    assertTrue(trace.label().startsWith("abcdef01"), trace.label());
  }

  @Test
  void the_filter_narrows_by_span_name_kind_status_and_trace() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                TraceReader.path("node-alpha", "worker-7") + "?limit=200",
                Map.of(
                    "lines",
                    List.of(
                        span("t1", "s1", "", "greet", "SERVER", "OK"),
                        span("t2", "s2", "", "checkout", "CLIENT", "ERROR"))));

    TraceSnapshot snapshot = read(reader);

    assertEquals(1, snapshot.traces("checkout").size());
    assertEquals(1, snapshot.traces("error").size());
    assertEquals(2, snapshot.traces("").size());
  }

  private static TraceSnapshot read(final FakeClusterReader reader) {
    return new TraceReader(reader, "node-alpha", "worker-7").read();
  }

  private static Map<String, Object> span(
      final String traceId,
      final String spanId,
      final String parentSpanId,
      final String name,
      final String kind,
      final String status) {
    return span(traceId, spanId, parentSpanId, name, kind, status, "2026-09-01T14:02:41.702Z");
  }

  private static Map<String, Object> span(
      final String traceId,
      final String spanId,
      final String parentSpanId,
      final String name,
      final String kind,
      final String status,
      final String timestamp) {
    return Map.of(
        "timestamp", timestamp,
        "traceId", traceId,
        "spanId", spanId,
        "parentSpanId", parentSpanId,
        "name", name,
        "kind", kind,
        "status", status);
  }
}
