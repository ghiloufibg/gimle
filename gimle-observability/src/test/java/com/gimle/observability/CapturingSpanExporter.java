package com.gimle.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link SpanExporter} test double that just appends every exported span to an in-memory list --
 * previously reimplemented as an identical anonymous class in four separate test files.
 */
final class CapturingSpanExporter implements SpanExporter {

  private final List<SpanData> captured = new CopyOnWriteArrayList<>();

  List<SpanData> captured() {
    return captured;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    captured.addAll(spans);
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
