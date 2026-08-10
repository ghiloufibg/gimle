package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml) --
// the same convention JsonLogEncoderTest already establishes.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TextLogEncoderTest {

  private static final String COLOR_PROPERTY = "gimle.color";

  private final TextLogEncoder encoder = new TextLogEncoder();
  // The real, statically-bound LoggerContext, not `new LoggerContext()`: LoggingEvent's MDC
  // capture needs the MDCAdapter that SLF4J's static binder wired up, which a fresh unbound
  // context doesn't have.
  private final Logger logger =
      ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.gimle.example.Thing");

  @AfterEach
  void clearMdcAndSystemProperties() {
    MDC.clear();
    System.clearProperty(COLOR_PROPERTY);
  }

  private String encodeToText(LoggingEvent event) {
    return new String(encoder.encode(event), StandardCharsets.UTF_8);
  }

  @Test
  void a_platform_line_carries_no_instance_tag() {
    System.setProperty(COLOR_PROPERTY, "never");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "worker started", null, null);

    String line = encodeToText(event);

    assertFalse(line.contains("#"), "no deployment#index tag expected on a PLATFORM line: " + line);
  }

  @Test
  void an_application_line_is_tagged_with_deployment_and_instance_index() {
    System.setProperty(COLOR_PROPERTY, "never");
    MDC.put(InstanceMdcKeys.DEPLOYMENT_NAME, "orders-prod");
    MDC.put(InstanceMdcKeys.INSTANCE_INDEX, "2");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "queue backing up", null, null);

    String line = encodeToText(event);

    assertTrue(line.contains("[orders-prod#2]"), "expected an instance tag: " + line);
  }

  @Test
  void the_logger_name_is_shortened_to_its_last_segment() {
    System.setProperty(COLOR_PROPERTY, "never");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "hello", null, null);

    String line = encodeToText(event);

    assertTrue(line.contains("Thing"), "expected the short logger name: " + line);
    assertFalse(
        line.contains("com.gimle.example.Thing"),
        "expected the fully-qualified name to be trimmed: " + line);
  }

  @Test
  void the_level_and_message_both_appear() {
    System.setProperty(COLOR_PROPERTY, "never");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.WARN, "disk almost full", null, null);

    String line = encodeToText(event);

    assertTrue(line.contains("WARN"));
    assertTrue(line.contains("disk almost full"));
  }

  @Test
  void color_never_produces_no_ansi_escapes() {
    System.setProperty(COLOR_PROPERTY, "never");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.ERROR, "boom", null, null);

    String line = encodeToText(event);

    assertFalse(line.contains("["), "expected no ANSI escapes with color=never: " + line);
  }

  @Test
  void color_always_produces_ansi_escapes() {
    System.setProperty(COLOR_PROPERTY, "always");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.ERROR, "boom", null, null);

    String line = encodeToText(event);

    assertTrue(line.contains("["), "expected ANSI escapes with color=always: " + line);
  }

  @Test
  void error_and_info_levels_are_colored_differently() {
    System.setProperty(COLOR_PROPERTY, "always");
    LoggingEvent errorEvent =
        new LoggingEvent(getClass().getName(), logger, Level.ERROR, "boom", null, null);
    LoggingEvent infoEvent =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "fine", null, null);

    String errorLine = encodeToText(errorEvent);
    String infoLine = encodeToText(infoEvent);

    // Both carry ANSI escapes, but not the identical level-color escape sequence, since ERROR and
    // INFO are deliberately different colors in the palette.
    assertFalse(
        errorLine
            .substring(0, errorLine.indexOf("ERROR"))
            .equals(infoLine.substring(0, infoLine.indexOf("INFO"))),
        "expected ERROR and INFO to use different colors");
  }

  @Test
  void header_and_footer_bytes_are_absent() {
    assertNull(encoder.headerBytes());
    assertNull(encoder.footerBytes());
  }

  @Test
  void encoded_output_ends_with_the_platform_line_separator() {
    System.setProperty(COLOR_PROPERTY, "never");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "hello", null, null);

    String text = encodeToText(event);

    assertTrue(text.endsWith(System.lineSeparator()));
  }
}
