package com.gimle.worker;

import com.gimle.core.protocol.ControlMessage;
import com.gimle.observability.SpanLineCodec;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ships every exported span batch to the agent over the worker's own control channel, rather than
 * to Muninn directly (design doc §6c) -- a worker has no outbound network identity of its own (see
 * {@code MuninnServer}'s own javadoc), so it can't run a {@code MuninnSpanExporter}/{@code
 * MuninnShipper} pair the way {@code gimle-controlplane}/{@code gimle-fafnir}/{@code gimle-mimir}/
 * {@code gimle-agent} do. {@link SpanLineCodec} produces byte-identical NDJSON to what {@code
 * MuninnSpanExporter} ships directly, so Muninn's own ingest/storage code never needs to know
 * whether a batch arrived straight from its owning process or relayed through an agent. This class
 * lives here rather than in {@code gimle-observability}: it needs {@code ControlMessage} and this
 * worker's own control-channel connection, a dependency {@code gimle-observability} shouldn't take
 * on.
 *
 * <p>{@link #export} never surfaces a relay failure to the OpenTelemetry SDK -- {@code sink} is
 * itself already best-effort (the same {@code sendQuietly} posture every other worker-to-agent
 * control message uses), and {@link CompletableResultCode#ofSuccess()} here means "the SDK's job is
 * done," not "the agent (or Muninn beyond it) definitely received this batch."
 */
final class RelayingSpanExporter implements SpanExporter {

  private static final Logger log = LoggerFactory.getLogger(RelayingSpanExporter.class);

  private final String workerId;
  private final Consumer<ControlMessage> sink;

  RelayingSpanExporter(String workerId, Consumer<ControlMessage> sink) {
    this.workerId = workerId;
    this.sink = sink;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    String body = SpanLineCodec.toNdjson(spans);
    if (!body.isEmpty()) {
      try {
        sink.accept(new ControlMessage.TracesSnapshot(workerId, body));
      } catch (RuntimeException e) {
        log.debug("failed to relay a trace batch to the agent: {}", e.getMessage());
      }
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
