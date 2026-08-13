package com.gimle.fabric.trace;

/**
 * The wire representation of an OpenTelemetry span's identity: carried inside {@code InvokeRequest}
 * across the two transport hops that need it (same-machine UDS, cross-machine TCP). A same-worker
 * call needs no wire representation at all -- {@code gimle-worker}'s {@code BoundedModuleScheduler}
 * captures the caller's OTel {@code Context} via {@code Context.wrap} (not a {@code ScopedValue} --
 * see that class's own javadoc for why) and restores it on the virtual thread the submitted task
 * runs on, with zero fabric involvement. {@code flags} mirrors the W3C traceparent's single
 * trace-flags byte (bit 0 = sampled), the smallest representation that still lets a receiving span
 * honor the caller's sampling decision.
 *
 * <p>{@code tracestate}/{@code baggage} are the W3C {@code tracestate} and {@code baggage} headers'
 * own {@code key1=value1,key2=value2} wire syntax, captured and replayed verbatim by {@code
 * FabricServiceRegistry}/{@code FabricServer} rather than decomposed into a richer type here --
 * this record's whole job is "bytes that cross the wire," not modeling either header's semantics.
 * Empty string means "none present," never {@code null}, matching the field-added-later back-compat
 * shape {@code DeploymentSpec}'s own {@code Optional} fields use elsewhere in this codebase (a
 * plain empty string rather than {@code Optional} here since this type crosses the wire directly,
 * with no YAML/manifest layer to make {@code Optional} worth its own encoding).
 */
public record TraceContext(
    long traceIdHigh, long traceIdLow, long spanId, byte flags, String tracestate, String baggage) {

  public TraceContext {
    if (tracestate == null) {
      throw new IllegalArgumentException("tracestate must be an empty string, not null");
    }
    if (baggage == null) {
      throw new IllegalArgumentException("baggage must be an empty string, not null");
    }
  }

  /**
   * Back-compat: defaults {@code tracestate} and {@code baggage} to empty, for callers built before
   * those fields existed.
   */
  public TraceContext(long traceIdHigh, long traceIdLow, long spanId, byte flags) {
    this(traceIdHigh, traceIdLow, spanId, flags, "", "");
  }
}
