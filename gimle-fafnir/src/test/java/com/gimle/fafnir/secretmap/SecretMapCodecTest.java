package com.gimle.fafnir.secretmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretMapCodecTest {

  @Test
  void raw_key_joins_name_and_key_with_the_reserved_prefix_and_colon_separator() {
    assertEquals("secretmap:db-creds:username", SecretMapCodec.rawKey("db-creds", "username"));
  }

  @Test
  void raw_key_round_trips_back_to_name_and_key() {
    String rawKey = SecretMapCodec.rawKey("db-creds", "username");

    assertEquals("db-creds", SecretMapCodec.nameFromKey(rawKey));
    assertEquals("username", SecretMapCodec.keyFromKey(rawKey));
  }

  @Test
  void is_secret_map_key_recognizes_the_reserved_prefix_and_nothing_else() {
    assertTrue(SecretMapCodec.isSecretMapKey("secretmap:db-creds:username"));
    assertFalse(SecretMapCodec.isSecretMapKey("db-creds:username"));
    assertFalse(SecretMapCodec.isSecretMapKey("plain-secret-key"));
  }

  @Test
  void a_name_containing_the_reserved_colon_separator_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> SecretMapCodec.rawKey("weird:name", "key"));
  }

  @Test
  void a_key_containing_the_reserved_colon_separator_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> SecretMapCodec.rawKey("name", "weird:key"));
  }

  @Test
  void a_blank_name_or_key_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> SecretMapCodec.rawKey("", "key"));
    assertThrows(IllegalArgumentException.class, () -> SecretMapCodec.rawKey("name", ""));
  }

  @Test
  void name_from_key_rejects_a_key_that_is_not_a_secret_map_key_at_all() {
    assertThrows(IllegalArgumentException.class, () -> SecretMapCodec.nameFromKey("plain-key"));
  }
}
