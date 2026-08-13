package com.gimle.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ships every exported span batch to Muninn (design doc Part B/O-13), serializing each {@link
 * SpanData} to the same no-envelope JSON-line shape the rest of Muninn's ingest surface uses:
 * {@code traceId}/{@code spanId}/{@code parentSpanId}/{@code name}/{@code kind}/{@code timestamp}
 * (the span's own end time, not export wall-clock time, matching {@code MuninnDayFileStore}'s
 * "bucket by the line's own timestamp" contract)/{@code status}, plus every span attribute
 * flattened directly onto the line -- unlike the metrics wire shape's nested {@code tags}/{@code
 * measurements}, since a span's attribute set is unbounded and free-form rather than a small fixed
 * dimension set, and nothing downstream needs to distinguish "an attribute" from "one of the fixed
 * span fields" yet. A span attribute key colliding with one of those fixed fields would silently
 * overwrite it -- an accepted, documented tradeoff, not a gap, given nothing reads this shape back
 * with a strict schema today.
 *
 * <p>{@link #export} never surfaces a shipping failure to the OpenTelemetry SDK: {@link
 * MuninnShipper#shipTraceBatch} is itself already best-effort (see that method's own javadoc), and
 * {@link CompletableResultCode#ofSuccess()} here means "the SDK's job is done," not "Muninn
 * definitely received this batch."
 */
public final class MuninnSpanExporter implements SpanExporter {

  private static final Logger log = LoggerFactory.getLogger(MuninnSpanExporter.class);

  private final MuninnShipper shipper;

  public MuninnSpanExporter(MuninnShipper shipper) {
    this.shipper = shipper;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    List<Map<String, Object>> lines = spans.stream().map(SpanLineCodec::toLine).toList();
    try {
      shipper.shipTraceBatch(lines);
    } catch (RuntimeException e) {
      log.debug("failed to ship a trace batch to muninn: {}", e.getMessage());
    }
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }
}
