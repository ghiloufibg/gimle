package com.gimle.core.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.gimle.core.protocol.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hand-rolled structured JSON log line encoder. Plain {@code logback-classic} ships no JSON encoder
 * itself, so this follows the same "hand-roll it, it's small" posture already established by {@code
 * Json}/{@code ControlMessageCodec}, reusing {@link Json#write} for the actual serialization rather
 * than a third hand-rolled writer.
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
 * otherwise. Optional fields are omitted rather than null-padded.
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
    boolean isApplication = InstanceMdcKeys.isApplicationCategory(mdc);

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
    String stackTrace = formatThrowable(event);
    if (stackTrace != null) {
      line.put("stackTrace", stackTrace);
    }
    return (Json.write(line) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * {@code null} when the event carries no throwable ({@code log.error(msg)}, not {@code
   * log.error(msg, e)}) -- omitted from the line entirely, the same "optional fields are omitted"
   * convention every other field here already follows, rather than an empty string padding every
   * throwable-less line.
   *
   * <p>Without this, a call site's own {@code Throwable} argument was silently discarded: {@link
   * ILoggingEvent#getFormattedMessage()} (the only thing {@link #encode} previously read) is just
   * the message template with its {@code {}} placeholders substituted, never the exception itself
   * -- {@code event.getThrowableProxy()} is the only place Logback still carries it. Uses {@link
   * ThrowableProxyConverter}, the same standard renderer {@link TextLogEncoder#formatThrowable}
   * already reuses, so a stack trace reads identically whether it came from a console tail or this
   * structured trail -- rather than hand-rolling a second frame-walking format here.
   */
  private static String formatThrowable(ILoggingEvent event) {
    if (event.getThrowableProxy() == null) {
      return null;
    }
    ThrowableProxyConverter converter = new ThrowableProxyConverter();
    converter.start();
    try {
      return converter.convert(event);
    } finally {
      converter.stop();
    }
  }
}
