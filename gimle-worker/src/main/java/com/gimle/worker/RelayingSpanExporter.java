package com.gimle.worker;

import com.gimle.core.protocol.ControlMessage;
import com.gimle.observability.SpanLineCodec;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A worker JVM's own {@link SpanExporter}, used in place of {@code MuninnSpanExporter} because a
 * worker has no outbound network identity of its own (design doc §6a) -- every other
 * Muninn-shipping process talks to Muninn directly over HTTP; a worker instead serializes its span
 * batch with the same {@link SpanLineCodec} those processes use and relays it to its supervising
 * agent as a {@link ControlMessage.TracesSnapshot} over the existing control channel, the one
 * channel that already crosses the worker/agent process boundary. The agent decides whether (and
 * where) to actually ship it on to Muninn -- this class only ever needs to reach the agent, never
 * Muninn itself.
 *
 * <p>Package-private, matching {@link ControlChannelClient}'s own visibility: it must live in
 * {@code com.gimle.worker} to hold a reference to that channel, which is exactly why this class
 * can't live in {@code gimle-observability} alongside {@code MuninnSpanExporter}.
 */
final class RelayingSpanExporter implements SpanExporter {

  private static final Logger log = LoggerFactory.getLogger(RelayingSpanExporter.class);

  private final ControlChannelClient channel;
  private final String workerId;

  RelayingSpanExporter(ControlChannelClient channel, String workerId) {
    this.channel = channel;
    this.workerId = workerId;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    String ndjson = SpanLineCodec.toNdjson(spans);
    if (!ndjson.isEmpty()) {
      try {
        channel.send(new ControlMessage.TracesSnapshot(workerId, ndjson));
      } catch (IOException e) {
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
