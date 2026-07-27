package com.gimle.fabric.trace;

/**
 * The wire representation of an OpenTelemetry span's identity: carried inside {@code InvokeRequest}
 * across the two transport hops that need it (same-machine UDS, cross-machine TCP). A same-worker
 * call needs no wire representation at all -- the current {@code io.opentelemetry.context.Context}
 * already propagates through scoped values into any virtual thread the call spawns, with zero
 * fabric involvement. {@code flags} mirrors the W3C traceparent's single trace-flags byte (bit 0 =
 * sampled), the smallest representation that still lets a receiving span honor the caller's
 * sampling decision.
 */
public record TraceContext(long traceIdHigh, long traceIdLow, long spanId, byte flags) {}
