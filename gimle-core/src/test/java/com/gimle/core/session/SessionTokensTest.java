package com.gimle.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.time.TestClock;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class SessionTokensTest {

  private static SecretKey key() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void issue_then_verify_round_trips_the_username() {
    SecretKey key = key();
    String token = SessionTokens.issue("alice", key, Duration.ofHours(1));

    assertEquals("alice", SessionTokens.verify(token, key).orElseThrow().username());
  }

  @Test
  void an_expired_token_is_rejected(TestClock clock) {
    SecretKey key = key();
    // A real session TTL, expired instantly rather than a 1ms token raced by a sleep.
    Duration ttl = Duration.ofHours(8);
    String token = SessionTokens.issue("alice", key, ttl, clock);

    clock.advance(ttl.plusMillis(1));

    assertEquals(Optional.empty(), SessionTokens.verify(token, key, clock));
  }

  @Test
  void a_token_is_still_accepted_at_the_last_millisecond_before_it_expires(TestClock clock) {
    SecretKey key = key();
    Duration ttl = Duration.ofHours(8);
    String token = SessionTokens.issue("alice", key, ttl, clock);

    // The expiry comparison is a strict >, so the expiry millisecond itself still verifies. Only
    // virtual time can assert this side of the boundary at all.
    clock.advance(ttl);

    assertEquals("alice", SessionTokens.verify(token, key, clock).orElseThrow().username());
  }

  @Test
  void a_token_signed_with_a_different_key_is_rejected() {
    String token = SessionTokens.issue("alice", key(), Duration.ofHours(1));

    assertEquals(Optional.empty(), SessionTokens.verify(token, key()));
  }

  @Test
  void a_tampered_token_is_rejected() {
    SecretKey key = key();
    String token = SessionTokens.issue("alice", key, Duration.ofHours(1));
    // Flip a character in the middle of the token, not the last one: the final base64url
    // character of a string whose length isn't a multiple of 4 only encodes the tail group's
    // remaining significant bits (as few as 2), so occasionally flipping it lands on a value that
    // decodes to the same byte -- a real, previously-observed source of test flakiness, not
    // timing. A middle character always sits inside a full 4-char/3-byte group, so any change to
    // it maps to a different 6-bit value and therefore a genuinely different decoded byte.
    int index = token.length() / 2;
    char original = token.charAt(index);
    char flipped = original == 'A' ? 'B' : 'A';
    String tampered = token.substring(0, index) + flipped + token.substring(index + 1);

    assertEquals(Optional.empty(), SessionTokens.verify(tampered, key));
  }

  @Test
  void garbage_input_is_rejected_rather_than_throwing() {
    assertEquals(Optional.empty(), SessionTokens.verify("not-a-valid-token!!", key()));
    assertEquals(Optional.empty(), SessionTokens.verify("", key()));
  }
}
