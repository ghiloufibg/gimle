package com.gimle.core.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConfigEntryTest {

  @Test
  void a_value_is_copied_in_and_out_so_a_caller_can_never_mutate_a_stored_entry() {
    byte[] original = "hunter2".getBytes(StandardCharsets.UTF_8);
    ConfigEntry entry = new ConfigEntry("acme", "db-password", original, false);

    original[0] = 'X';

    assertArrayEquals("hunter2".getBytes(StandardCharsets.UTF_8), entry.value());
    assertNotSame(entry.value(), entry.value());
  }

  @Test
  void a_value_exactly_at_the_cap_is_accepted() {
    ConfigEntry entry =
        new ConfigEntry("acme", "big", new byte[ConfigEntry.MAX_VALUE_BYTES], false);

    assertEquals(ConfigEntry.MAX_VALUE_BYTES, entry.value().length);
  }

  @Test
  void a_value_one_byte_over_the_cap_is_rejected() {
    byte[] oversized = new byte[ConfigEntry.MAX_VALUE_BYTES + 1];

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConfigEntry("acme", "too-big", oversized, false));

    assertTrue(thrown.getMessage().contains("acme/too-big"));
    assertTrue(thrown.getMessage().contains(String.valueOf(ConfigEntry.MAX_VALUE_BYTES)));
  }

  @Test
  void a_blank_tenant_a_blank_key_and_a_null_value_are_all_rejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new ConfigEntry(" ", "k", new byte[0], false));
    assertThrows(
        IllegalArgumentException.class, () -> new ConfigEntry("acme", " ", new byte[0], false));
    assertThrows(IllegalArgumentException.class, () -> new ConfigEntry("acme", "k", null, false));
  }
}
