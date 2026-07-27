package com.gimle.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.gimle.core.protocol.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-rolled structured JSON log line encoder (log-explorer-design.md §4) -- plain {@code
 * logback-classic} ships no JSON encoder itself (a correction to that design doc's assumption; see
 * the addendum), so this follows the same "hand-roll it, it's small" posture already established by
 * {@code Json}/{@code ControlMessageCodec}, reusing {@link Json#write} for the actual serialization
 * rather than a third hand-rolled writer.
 *
 * <p>{@code processRole}/{@code nodeId} are process-wide constants read fresh from system
 * properties on every call (not cached, not passed at construction) so this works correctly however
 * Logback happens to construct it -- declaratively from {@code logback.xml} at JVM-startup
 * auto-configuration time (before a process's {@code main} has had a chance to set them) or
 * programmatically later via {@link GimleLogging}. System properties are process-global, not
 * thread-local, so this is safe to read from any thread, unlike MDC.
 *
 * <p>{@code category} is derived, never itself stored in MDC: APPLICATION exactly when both {@link
 * InstanceMdcKeys#DEPLOYMENT_NAME} and {@link InstanceMdcKeys#INSTANCE_INDEX} are present, PLATFORM
 * otherwise (design §3). Optional fields are omitted rather than null-padded, per design §4.
 */
public final class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

  @Override
  public byte[] headerBytes() {
    return null;
  }

  @Override
  public byte[] footerBytes() {
    return null;
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    Map<String, String> mdc = event.getMDCPropertyMap();
    String deploymentName = mdc.get(InstanceMdcKeys.DEPLOYMENT_NAME);
    String instanceIndexText = mdc.get(InstanceMdcKeys.INSTANCE_INDEX);
    boolean isApplication =
        deploymentName != null && !deploymentName.isEmpty() && instanceIndexText != null;

    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
    line.put("level", event.getLevel().toString());
    line.put("logger", event.getLoggerName());
    line.put("thread", event.getThreadName());
    line.put("message", event.getFormattedMessage());
    line.put("category", isApplication ? "APPLICATION" : "PLATFORM");
    line.put("processRole", System.getProperty("gimle.process.role", "UNKNOWN"));
    line.put("nodeId", System.getProperty("gimle.node.id", "unknown"));
    if (isApplication) {
      line.put("moduleId", mdc.get(InstanceMdcKeys.MODULE_ID));
      line.put("moduleVersion", mdc.get(InstanceMdcKeys.MODULE_VERSION));
      line.put("deploymentName", deploymentName);
      line.put("instanceIndex", Integer.parseInt(instanceIndexText));
      String tenantId = mdc.get(InstanceMdcKeys.TENANT_ID);
      if (tenantId != null) {
        line.put("tenantId", tenantId);
      }
    }
    return (Json.write(line) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
  }
}
