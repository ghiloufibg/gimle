package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.gimle.core.protocol.Json;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;

// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ConsoleLogEncoderTest {

  private static final String MODE_PROPERTY = "gimle.log.console";

  private final Logger logger =
      ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("test.logger");

  @AfterEach
  void clearProperty() {
    System.clearProperty(MODE_PROPERTY);
  }

  private ConsoleLogEncoder started() {
    ConsoleLogEncoder encoder = new ConsoleLogEncoder();
    encoder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    encoder.start();
    return encoder;
  }

  @Test
  void explicit_json_override_produces_parseable_json() {
    System.setProperty(MODE_PROPERTY, "json");
    ConsoleLogEncoder encoder = started();
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "hello", null, null);

    String text = new String(encoder.encode(event), StandardCharsets.UTF_8).strip();

    assertDoesNotThrow(() -> Json.parse(text), "expected valid JSON: " + text);
  }

  @Test
  void explicit_text_override_does_not_produce_json() {
    System.setProperty(MODE_PROPERTY, "text");
    ConsoleLogEncoder encoder = started();
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "hello", null, null);

    String text = new String(encoder.encode(event), StandardCharsets.UTF_8).strip();

    assertThrows(RuntimeException.class, () -> Json.parse(text), "expected non-JSON: " + text);
    assertTrue(text.contains("hello"));
  }

  @Test
  void no_override_defaults_to_text_not_json() {
    // The core promise this class's own javadoc makes: CONSOLE defaults to the human-friendly
    // format everywhere, since the structured JSON trail every process needs already exists
    // unconditionally in its own *-platform.log file, independent of this appender entirely.
    ConsoleLogEncoder encoder = started();
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "hello", null, null);

    String text = new String(encoder.encode(event), StandardCharsets.UTF_8).strip();

    assertThrows(RuntimeException.class, () -> Json.parse(text), "expected non-JSON: " + text);
    assertTrue(text.contains("hello"));
  }

  @Test
  void header_and_footer_bytes_delegate_to_the_chosen_encoder() {
    System.setProperty(MODE_PROPERTY, "json");
    ConsoleLogEncoder encoder = started();

    assertNull(encoder.headerBytes());
    assertNull(encoder.footerBytes());
  }
}
