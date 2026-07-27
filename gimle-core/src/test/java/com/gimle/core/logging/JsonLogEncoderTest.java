package com.gimle.core.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.gimle.core.protocol.Json;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class JsonLogEncoderTest {

  private final JsonLogEncoder encoder = new JsonLogEncoder();
  // The real, statically-bound LoggerContext, not `new LoggerContext()`: LoggingEvent's MDC
  // capture needs the MDCAdapter that SLF4J's static binder wired up, which a fresh unbound
  // context doesn't have.
  private final Logger logger =
      ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("test.logger");

  @AfterEach
  void clearMdcAndSystemProperties() {
    MDC.clear();
    System.clearProperty("gimle.process.role");
    System.clearProperty("gimle.node.id");
  }

  private Map<String, Object> encodeToMap(LoggingEvent event) {
    byte[] bytes = encoder.encode(event);
    String json = new String(bytes, StandardCharsets.UTF_8).strip();
    return Json.asObject(Json.parse(json));
  }

  @Test
  void a_line_with_no_instance_mdc_is_categorized_platform() {
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "worker started", null, null);

    Map<String, Object> line = encodeToMap(event);

    assertEquals("PLATFORM", line.get("category"));
    assertEquals("INFO", line.get("level"));
    assertEquals("test.logger", line.get("logger"));
    assertEquals("worker started", line.get("message"));
    assertFalse(line.containsKey("moduleId"), "PLATFORM lines omit instance fields entirely");
    assertFalse(line.containsKey("deploymentName"));
  }

  @Test
  void a_line_with_deployment_and_instance_mdc_is_categorized_application() {
    MDC.put(InstanceMdcKeys.DEPLOYMENT_NAME, "orders-prod");
    MDC.put(InstanceMdcKeys.INSTANCE_INDEX, "2");
    MDC.put(InstanceMdcKeys.MODULE_ID, "orders-service");
    MDC.put(InstanceMdcKeys.MODULE_VERSION, "1.4.2");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.WARN, "queue backing up", null, null);

    Map<String, Object> line = encodeToMap(event);

    assertEquals("APPLICATION", line.get("category"));
    assertEquals("orders-prod", line.get("deploymentName"));
    assertEquals(2L, line.get("instanceIndex"));
    assertEquals("orders-service", line.get("moduleId"));
    assertEquals("1.4.2", line.get("moduleVersion"));
    assertFalse(line.containsKey("tenantId"), "omitted, not null-padded, when absent from MDC");
  }

  @Test
  void tenant_id_is_included_only_when_present_in_mdc() {
    MDC.put(InstanceMdcKeys.DEPLOYMENT_NAME, "orders-prod");
    MDC.put(InstanceMdcKeys.INSTANCE_INDEX, "0");
    MDC.put(InstanceMdcKeys.MODULE_ID, "orders-service");
    MDC.put(InstanceMdcKeys.MODULE_VERSION, "1.0.0");
    MDC.put(InstanceMdcKeys.TENANT_ID, "acme-corp");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "order accepted", null, null);

    Map<String, Object> line = encodeToMap(event);

    assertEquals("acme-corp", line.get("tenantId"));
  }

  @Test
  void process_role_and_node_id_are_read_fresh_from_system_properties() {
    System.setProperty("gimle.process.role", "WORKER");
    System.setProperty("gimle.node.id", "node-7");
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "hello", null, null);

    Map<String, Object> line = encodeToMap(event);

    assertEquals("WORKER", line.get("processRole"));
    assertEquals("node-7", line.get("nodeId"));
  }

  @Test
  void missing_system_properties_fall_back_rather_than_throwing() {
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "hello", null, null);

    Map<String, Object> line = encodeToMap(event);

    assertEquals("UNKNOWN", line.get("processRole"));
    assertEquals("unknown", line.get("nodeId"));
  }

  @Test
  void header_and_footer_bytes_are_absent() {
    assertNull(encoder.headerBytes());
    assertNull(encoder.footerBytes());
  }

  @Test
  void encoded_output_is_one_json_line_terminated_by_the_platform_line_separator() {
    LoggingEvent event =
        new LoggingEvent(getClass().getName(), logger, Level.INFO, "hello", null, null);

    byte[] bytes = encoder.encode(event);
    String text = new String(bytes, StandardCharsets.UTF_8);

    assertTrue(text.endsWith(System.lineSeparator()));
    // exactly one JSON value on the line, i.e. no embedded raw newline before the terminator
    String withoutTerminator = text.substring(0, text.length() - System.lineSeparator().length());
    assertFalse(withoutTerminator.contains("\n"));
  }
}
