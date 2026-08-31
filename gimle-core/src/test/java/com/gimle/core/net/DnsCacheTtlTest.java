package com.gimle.core.net;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.Security;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

// Security.setProperty mutates a JVM-global java.security property map; excludes this class from
// running concurrently with any other class touching the same key, under class-level parallel
// execution (root pom.xml).
@ResourceLock("networkaddress.cache.ttl")
class DnsCacheTtlTest {

  private static final String PROPERTY = "networkaddress.cache.ttl";

  @AfterEach
  void restoreDefault() {
    Security.setProperty(PROPERTY, "-1");
  }

  @Test
  void sets_the_security_property_to_five_seconds() {
    DnsCacheTtl.apply();
    assertEquals("5", Security.getProperty(PROPERTY));
  }

  @Test
  void applying_twice_is_idempotent() {
    DnsCacheTtl.apply();
    assertDoesNotThrow(DnsCacheTtl::apply);
    assertEquals("5", Security.getProperty(PROPERTY));
  }
}
