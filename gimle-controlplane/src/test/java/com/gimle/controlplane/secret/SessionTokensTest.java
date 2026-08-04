package com.gimle.controlplane.secret;

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
    // Flip the last character -- corrupts the trailing HMAC tag byte.
    char last = token.charAt(token.length() - 1);
    char flipped = last == 'A' ? 'B' : 'A';
    String tampered = token.substring(0, token.length() - 1) + flipped;

    assertEquals(Optional.empty(), SessionTokens.verify(tampered, key));
  }

  @Test
  void garbage_input_is_rejected_rather_than_throwing() {
    assertEquals(Optional.empty(), SessionTokens.verify("not-a-valid-token!!", key()));
    assertEquals(Optional.empty(), SessionTokens.verify("", key()));
  }
}
