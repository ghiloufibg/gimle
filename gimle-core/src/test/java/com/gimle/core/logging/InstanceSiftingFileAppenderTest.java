package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class InstanceSiftingFileAppenderTest {

  @TempDir Path instancesDir;

  // The real, statically-bound LoggerContext, not `new LoggerContext()`: LoggingEvent's MDC
  // capture needs the MDCAdapter that SLF4J's static binder wired up, same reasoning as
  // JsonLogEncoderTest.
  private final Logger logger =
      ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("test.sifting");

  private InstanceSiftingFileAppender appender;

  @AfterEach
  void tearDown() {
    MDC.clear();
    // Now backed by Logback's own RollingFileAppender (java.io.FileOutputStream internally, unlike
    // the plain OutputStream this class used to open via Files.newOutputStream), which doesn't
    // request FILE_SHARE_DELETE on Windows -- an unstopped appender leaves its file handle open and
    // @TempDir's cleanup fails to delete it. Every real caller already stops this on
    // uninstall/shutdown; tests need to do the same now that the underlying I/O mechanism changed.
    if (appender != null) {
      appender.stop();
    }
  }

  private InstanceSiftingFileAppender startedAppender() {
    appender = new InstanceSiftingFileAppender(instancesDir);
    appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    appender.start();
    return appender;
  }

  /**
   * Builds the event and appends it in one step, while the given MDC is still set: {@link
   * LoggingEvent#getMDCPropertyMap()} captures MDC lazily on its first read (inside {@code
   * append()} here), not eagerly at construction, so clearing MDC before that first read -- as a
   * two-step build-then-append helper would -- would silently drop the tags.
   */
  private void appendInstanceEvent(
      InstanceSiftingFileAppender appender,
      String deploymentName,
      int instanceIndex,
      String message) {
    MDC.put(InstanceMdcKeys.DEPLOYMENT_NAME, deploymentName);
    MDC.put(InstanceMdcKeys.INSTANCE_INDEX, Integer.toString(instanceIndex));
    MDC.put(InstanceMdcKeys.MODULE_ID, "mod");
    MDC.put(InstanceMdcKeys.MODULE_VERSION, "1.0.0");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, message, null, null);
    appender.append(event);
    MDC.clear();
  }

  private void appendPlatformEvent(InstanceSiftingFileAppender appender, String message) {
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, message, null, null);
    appender.append(event);
  }

  @Test
  void routes_application_lines_to_a_file_named_after_deployment_and_instance() throws IOException {
    InstanceSiftingFileAppender appender = startedAppender();

    appendInstanceEvent(appender, "orders", 2, "hello from orders instance 2");

    Path file = instancesDir.resolve("orders-2.log");
    assertTrue(Files.exists(file), "expected a file named after the deployment+instance key");
    String content = Files.readString(file);
    assertTrue(content.contains("hello from orders instance 2"));
    assertTrue(content.contains("\"category\":\"APPLICATION\""));
  }

  @Test
  void skips_platform_lines_entirely() throws IOException {
    InstanceSiftingFileAppender appender = startedAppender();

    appendPlatformEvent(appender, "a platform-scoped line");

    try (var files = Files.list(instancesDir)) {
      assertTrue(
          files.findAny().isEmpty(), "no instance file should be created for a PLATFORM line");
    }
  }

  @Test
  void never_leaks_one_instances_lines_into_anothers_file() throws IOException {
    InstanceSiftingFileAppender appender = startedAppender();

    appendInstanceEvent(appender, "orders", 0, "orders-0 secret");
    appendInstanceEvent(appender, "orders", 1, "orders-1 secret");

    String instance0 = Files.readString(instancesDir.resolve("orders-0.log"));
    String instance1 = Files.readString(instancesDir.resolve("orders-1.log"));

    assertTrue(instance0.contains("orders-0 secret"));
    assertFalse(instance0.contains("orders-1 secret"));
    assertTrue(instance1.contains("orders-1 secret"));
    assertFalse(instance1.contains("orders-0 secret"));
  }

  @Test
  void reopens_a_file_after_close_instance_and_a_new_line_for_the_same_key() throws IOException {
    InstanceSiftingFileAppender appender = startedAppender();

    appendInstanceEvent(appender, "orders", 3, "before close");
    appender.closeInstance("orders", 3);
    appendInstanceEvent(appender, "orders", 3, "after reopen");

    String content = Files.readString(instancesDir.resolve("orders-3.log"));
    assertTrue(content.contains("before close"));
    assertTrue(content.contains("after reopen"));
  }
}
