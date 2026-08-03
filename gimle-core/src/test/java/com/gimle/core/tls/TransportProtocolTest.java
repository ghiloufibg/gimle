package com.gimle.core.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// System.setProperty mutates a JVM-global; excludes this class from running concurrently with
// any other class holding the same lock, under class-level parallel execution (root pom.xml).
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TransportProtocolTest {

  private static final String PROPERTY = "gimle.transport.protocol";

  @AfterEach
  void clearProperty() {
    System.clearProperty(PROPERTY);
  }

  @Test
  void defaults_to_plaintext_when_nothing_is_configured() {
    assertEquals(TransportProtocol.PLAINTEXT, TransportProtocol.fromConfig());
  }

  @Test
  void system_property_selects_tls() {
    System.setProperty(PROPERTY, "tls");
    assertEquals(TransportProtocol.TLS, TransportProtocol.fromConfig());
  }

  @Test
  void system_property_selects_plaintext_explicitly() {
    System.setProperty(PROPERTY, "plaintext");
    assertEquals(TransportProtocol.PLAINTEXT, TransportProtocol.fromConfig());
  }

  @Test
  void system_property_value_is_case_insensitive() {
    System.setProperty(PROPERTY, "TLS");
    assertEquals(TransportProtocol.TLS, TransportProtocol.fromConfig());
  }

  @Test
  void rejects_an_unrecognized_value() {
    System.setProperty(PROPERTY, "not-a-real-protocol");
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, TransportProtocol::fromConfig);
    assertTrue(e.getMessage().contains(PROPERTY));
  }
}
