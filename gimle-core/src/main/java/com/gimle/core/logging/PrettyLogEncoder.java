package com.gimle.core.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.gimle.core.banner.AnsiPalette;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The human-friendly counterpart to {@link JsonLogEncoder}: one colored line per event instead of a
 * JSON object, for a developer watching a process's own terminal (see {@link ConsoleLogEncoder} for
 * how the two are chosen between). Colors come from {@link AnsiPalette} -- the same 256-color
 * palette {@code GimleBanner} renders its own startup banner with (gold/mint/slate/red), so a
 * process's terminal output reads as one consistent, colored whole rather than a plain banner
 * followed by plain logs.
 *
 * <p>Format: {@code HH:mm:ss.SSS | LEVEL | thread | [deployment#index] logger : message}. The
 * {@code [deployment#index]} tag only appears for an APPLICATION-category event (see {@link
 * InstanceMdcKeys}, the same MDC keys {@link JsonLogEncoder} reads to make the identical
 * PLATFORM-vs-APPLICATION distinction) -- a developer watching a worker's own console already knows
 * which process this is; what they don't get for free is which of possibly several hosted instances
 * a given line came from. Thread names are worth keeping, unlike in a typical thread-pool-bound
 * server: this codebase's own virtual threads carry meaningful, purpose-specific names (see e.g.
 * {@code gimle-instance-reader-*}, {@code gimle-fafnir-cert-rotation-tick}), not generic {@code
 * pool-1-thread-3}-style noise.
 */
public final class PrettyLogEncoder extends EncoderBase<ILoggingEvent> {

  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

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
    Map<String, String> c = AnsiPalette.colorsFor(AnsiPalette.detectMode());
    Map<String, String> mdc = event.getMDCPropertyMap();
    String deploymentName = mdc.get(InstanceMdcKeys.DEPLOYMENT_NAME);
    String instanceIndex = mdc.get(InstanceMdcKeys.INSTANCE_INDEX);
    boolean isApplication =
        deploymentName != null && !deploymentName.isEmpty() && instanceIndex != null;

    String levelColor = colorForLevel(c, event.getLevel().toString());
    String time = TIME_FORMAT.format(Instant.ofEpochMilli(event.getTimeStamp()));
    String logger = shortLoggerName(event.getLoggerName());
    String instanceTag = isApplication ? "[" + deploymentName + "#" + instanceIndex + "] " : "";
    String pipe = c.get("C_SLATE") + " | " + c.get("C_RESET");

    String line =
        c.get("C_SLATE")
            + time
            + c.get("C_RESET")
            + pipe
            + levelColor
            + pad(event.getLevel().toString(), 5)
            + c.get("C_RESET")
            + pipe
            + c.get("C_MINT")
            + event.getThreadName()
            + c.get("C_RESET")
            + pipe
            + c.get("C_GOLD_B")
            + instanceTag
            + logger
            + c.get("C_RESET")
            + " : "
            + event.getFormattedMessage();

    String throwable = formatThrowable(event);
    return ((line + throwable) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
  }

  private static String colorForLevel(Map<String, String> c, String level) {
    return switch (level) {
      case "ERROR" -> c.get("C_RED");
      case "WARN" -> c.get("C_GOLD_B");
      case "INFO" -> c.get("C_MINT");
      default -> c.get("C_SLATE"); // DEBUG, TRACE
    };
  }

  /**
   * The last segment of a fully-qualified logger name -- {@code AgentMain}, not {@code
   * com.gimle.agent.AgentMain} -- since the terminal this prints to is already scoped to one
   * process, the package prefix is redundant noise on every single line.
   */
  private static String shortLoggerName(String loggerName) {
    int lastDot = loggerName.lastIndexOf('.');
    return lastDot < 0 ? loggerName : loggerName.substring(lastDot + 1);
  }

  private static String pad(String s, int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }

  private static String formatThrowable(ILoggingEvent event) {
    if (event.getThrowableProxy() == null) {
      return "";
    }
    // Logback's own ThrowableProxyConverter is the standard way to render a full stack trace from
    // an IThrowableProxy without re-deriving the original Throwable (not always available -- e.g.
    // after deserialization) -- reused here rather than hand-rolling frame formatting.
    ThrowableProxyConverter converter = new ThrowableProxyConverter();
    converter.start();
    try {
      return System.lineSeparator() + converter.convert(event);
    } finally {
      converter.stop();
    }
  }
}
