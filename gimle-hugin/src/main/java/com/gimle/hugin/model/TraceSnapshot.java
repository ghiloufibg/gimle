package com.gimle.hugin.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One reading of a worker's shipped trace history, grouped into the traces its spans belong to.
 *
 * <p>{@code shipped} false is a state rather than a failure: shipping to Muninn is optional, and a
 * cluster whose processes ship nowhere has no history at all. Reporting that as an error would
 * blame the reader for a deployment choice; reporting it as an empty list would claim the worker
 * served nothing.
 */
public record TraceSnapshot(
    String serverAddress,
    Optional<Instant> fetchedAt,
    List<SpanRow> spans,
    boolean shipped,
    Optional<String> staleReason)
    implements Staleable<TraceSnapshot> {

  public TraceSnapshot {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("serverAddress must not be blank");
    }
    if (fetchedAt == null || staleReason == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    spans = List.copyOf(spans);
  }

  public static TraceSnapshot connecting(final String serverAddress) {
    return new TraceSnapshot(
        serverAddress, Optional.empty(), List.of(), true, Optional.of("connecting"));
  }

  /** No history to read, because nothing ships any. */
  public static TraceSnapshot notShipped(final String serverAddress) {
    return new TraceSnapshot(
        serverAddress, Optional.of(Instant.now()), List.of(), false, Optional.empty());
  }

  @Override
  public TraceSnapshot stale(final String reason) {
    return new TraceSnapshot(serverAddress, fetchedAt, spans, shipped, Optional.of(reason));
  }

  public boolean connected() {
    return fetchedAt.isPresent() && staleReason.isEmpty();
  }

  public Optional<Duration> age(final Instant now) {
    return fetchedAt.map(at -> Duration.between(at, now));
  }

  /** How many traces carry a span that failed -- what this view is opened to find. */
  public long failedTraceCount() {
    return traces(null).stream().filter(Trace::failed).count();
  }

  /**
   * The spans grouped by the trace they belong to, newest trace first.
   *
   * <p>Grouped rather than listed flat because a span on its own says almost nothing: what an
   * operator is looking for is the one call that failed and what it was part of. A trace whose root
   * span was never shipped -- because it began in another process, or was trimmed from this
   * worker's own history -- still appears, named by its id, rather than being dropped for want of a
   * heading.
   */
  public List<Trace> traces(final String filter) {
    Map<String, List<SpanRow>> byTrace = new LinkedHashMap<>();
    for (SpanRow span : matching(filter)) {
      byTrace.computeIfAbsent(span.traceId(), id -> new ArrayList<>()).add(span);
    }
    List<Trace> traces = new ArrayList<>();
    for (Map.Entry<String, List<SpanRow>> entry : byTrace.entrySet()) {
      List<SpanRow> ordered =
          entry.getValue().stream().sorted(java.util.Comparator.comparing(SpanRow::at)).toList();
      traces.add(new Trace(entry.getKey(), ordered));
    }
    traces.sort(java.util.Comparator.comparing(Trace::latest).reversed());
    return traces;
  }

  private List<SpanRow> matching(final String filter) {
    if (filter == null || filter.isBlank()) {
      return spans;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    return spans.stream().filter(span -> span.searchText().contains(needle)).toList();
  }

  /** One trace: every span of it this worker shipped, oldest first. */
  public record Trace(String traceId, List<SpanRow> spans) {

    public Trace {
      spans = List.copyOf(spans);
    }

    /** The root span's name when there is one, and the trace's own id when there is not. */
    public String label() {
      return spans.stream()
          .filter(SpanRow::root)
          .map(SpanRow::name)
          .findFirst()
          .orElseGet(() -> traceId.length() <= 8 ? traceId : traceId.substring(0, 8) + "…");
    }

    public boolean failed() {
      return spans.stream().anyMatch(SpanRow::failed);
    }

    public Instant latest() {
      return spans.stream().map(SpanRow::at).max(Instant::compareTo).orElse(Instant.EPOCH);
    }
  }
}
