package com.gimle.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    assertEquals(Optional.of("alice"), SessionTokens.verify(token, key));
  }

  @Test
  void an_expired_token_is_rejected() throws InterruptedException {
    SecretKey key = key();
    String token = SessionTokens.issue("alice", key, Duration.ofMillis(1));

    // Sleeping in a unit test is undesirable, so this is deliberately built with a 1ms TTL plus a
    // short pause -- the one temporal case that cannot be verified without any elapsed time at all,
    // same posture as CertificateAuthorityTest's own expired-certificate test.
    Thread.sleep(50);
    assertEquals(Optional.empty(), SessionTokens.verify(token, key));
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
